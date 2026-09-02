#![allow(dead_code)]
pub mod account;
pub mod aethernoize;
pub mod api;
pub mod apifront;
pub mod cli;
pub mod config;
pub mod consts;
pub mod dns;
pub mod error;
// Upstream's own C API is deliberately not vendored. Nothing here calls it --
// native/android-bridge is this project's FFI and predates it -- and its
// spawn_job wrapper around run_with does not satisfy Send for our merged
// engine, so carrying it would mean carrying a build failure for an interface
// we do not ship.
pub mod fragment;
pub mod lastconn;
pub mod masque;
pub mod masque_h2;
pub mod netstack;
pub mod noize;
pub mod prober;
pub mod quic;
pub mod routing;
// Ours: the VpnService callback that keeps a socket out of the tunnel.
pub mod sniff;
mod socketprotect;
// Public for its access policy: the embedded caller decides who may use the
// listener, and that type has to cross the crate boundary with the config.
pub mod socks;
pub mod sysprofile;
pub mod tls;
pub mod tunnelping;
pub mod upstream;
pub mod wg_prober;
pub mod wireguard;
pub mod zerotrust;

use std::collections::{HashMap, HashSet};
use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::sync::atomic::AtomicBool;
use std::time::Instant;

pub use error::{AetherError, Result};

pub fn set_socket_protector(
    protector: Option<std::sync::Arc<dyn Fn(i32) -> bool + Send + Sync + 'static>>,
) {
    socketprotect::set(protector);
}

fn parse_local_v4(s: &str) -> Ipv4Addr {
    s.split('/')
        .next()
        .unwrap_or(s)
        .parse()
        .unwrap_or(Ipv4Addr::UNSPECIFIED)
}

/// What MASQUE can carry, and it is not ours to choose.
///
/// Cloudflare caps the payload, so this is a limit the tunnel is given rather
/// than one it picks.
const MASQUE_MTU: usize = 1280;

/// What WireGuard can carry, which is a different question.
///
/// The 1280 above was applied to both for no better reason than sharing a
/// constant, and it cost hysteria2 and tuic nodes behind the tunnel: they need
/// a 1280-byte UDP payload, and 1280 inner leaves 1252 after the 28-byte outer
/// header. WireGuard adds 32 bytes of its own, so 1340 inner goes out as a 1400
/// byte datagram -- under the 1500 any ordinary path carries -- and leaves 1312
/// for the payload, which is enough.
///
/// MASQUE cannot follow it there. The cap is Cloudflare's, so QUIC-based nodes
/// stay impossible on that transport and the app says so.
const WIREGUARD_MTU: usize = 1340;
const INNER_MTU: usize = 1200;
const DEFAULT_CONFIG: &str = "aether.toml";

pub async fn run() -> Result<()> {
    run_with(std::env::args().skip(1).collect()).await
}

pub async fn run_with(args: Vec<String>) -> Result<()> {
    cli::parse_args(args)?;

    let level = std::env::var("AETHER_LOG_LEVEL")
        .ok()
        .map(|v| v.trim().to_lowercase())
        .filter(|v| matches!(v.as_str(), "error" | "warn" | "info" | "debug" | "trace"))
        .unwrap_or_else(|| "info".to_string());
    let default_filter = format!("info,aether={level}");
    let _ =
        env_logger::Builder::from_env(env_logger::Env::default().default_filter_or(default_filter))
            .format_timestamp_millis()
            .try_init();

    log::info!("Aether v{}", env!("CARGO_PKG_VERSION"));
    sysprofile::log_summary();

    install_netstack_panic_guard();

    let listen: SocketAddr = std::env::var("AETHER_SOCKS")
        .ok()
        .and_then(|s| s.parse().ok())
        .unwrap_or_else(|| "127.0.0.1:1819".parse().unwrap());

    let base_config = std::env::var("AETHER_CONFIG").unwrap_or_else(|_| DEFAULT_CONFIG.to_string());

    let protocol =
        if std::env::var("AETHER_PEER").is_ok() || std::env::var("AETHER_WG_PEER").is_ok() {
            match std::env::var("AETHER_PROTOCOL") {
                Ok(v) => Protocol::parse(&v),
                Err(_) => Protocol::Masque,
            }
        } else {
            select_protocol(&base_config).await
        };

    match protocol {
        Protocol::Masque => {
            select_masque_transport().await;
            let config_path = masque_config_path(&base_config);
            let identity = load_or_provision_masque(&config_path).await?;
            log::info!(
                "[+] identity ready: device={} ipv4={} ipv6={}",
                identity.device_id,
                identity.ipv4,
                identity.ipv6
            );
            let ech = resolve_ech().await;
            let lastconn_path = lastconn_path(&config_path);
            run_masque(identity, ech, listen, lastconn_path).await
        }
        Protocol::WireGuard => {
            let config_path = warp_config_path(&base_config);
            let identity = load_or_provision_warp(&config_path).await?;
            log::info!(
                "[+] identity ready: device={} ipv4={} ipv6={}",
                identity.device_id,
                identity.ipv4,
                identity.ipv6
            );
            let lastconn_path = lastconn_path(&config_path);
            run_wireguard(identity, listen, lastconn_path).await
        }
        Protocol::WarpInWarp => {
            let primary_path = warp_config_path(&base_config);
            let secondary_path = derive_sibling_path(&primary_path, "secondary");
            let primary = load_or_provision_warp(&primary_path).await?;
            let secondary = load_or_provision_warp(&secondary_path).await?;
            log::info!(
                "[+] outer device={} ipv4={} | inner device={} ipv4={}",
                primary.device_id,
                primary.ipv4,
                secondary.device_id,
                secondary.ipv4
            );
            run_gool(primary, secondary, listen).await
        }
    }
}

pub const fn version() -> &'static str {
    env!("CARGO_PKG_VERSION")
}

#[derive(Debug, Clone)]
pub struct EmbeddedConfig {
    pub config_path: String,
    pub listen: SocketAddr,
    pub peer: Option<SocketAddr>,
    pub peer_fallback: bool,
    pub scan_mode: String,
    pub ip_scan: String,
    /// Which tunnel to build: `masque` or `wireguard`.
    ///
    /// Defaults to MASQUE when empty, because that is what every embedded
    /// caller wanted before there was a choice.
    pub protocol: String,
    /// Who may use the SOCKS5 listener.
    ///
    /// Carried here rather than derived from [`Self::listen`]: whether a
    /// password is demanded is the caller's decision, and a listener on the
    /// local network is a deliberate one either way.
    pub access: socks::Access,
}

impl EmbeddedConfig {
    fn protocol(&self) -> Protocol {
        match self.protocol.trim() {
            "" => Protocol::Masque,
            other => Protocol::parse(other),
        }
    }

    /// A copy that pins [`peer`], so the run path re-establishes the profile for
    /// the endpoint prepare already chose rather than scanning again.
    fn clone_with_peer(&self, peer: SocketAddr) -> Self {
        Self {
            peer: Some(peer),
            ..self.clone()
        }
    }

    /// Where this protocol's identity lives.
    ///
    /// MASQUE and WARP provision separate accounts against different Cloudflare
    /// APIs, so they cannot share a file. Switching protocol keeps both.
    fn identity_path(&self) -> String {
        match self.protocol() {
            Protocol::Masque => masque_config_path(&self.config_path),
            _ => warp_config_path(&self.config_path),
        }
    }

    /// The second account, for the inner hop of a nested tunnel.
    ///
    /// Two accounts, not one used twice: the inner tunnel handshakes through the
    /// outer one, and Cloudflare would see the same device connecting to itself.
    fn secondary_identity_path(&self) -> String {
        derive_sibling_path(&self.identity_path(), "secondary")
    }
}

#[derive(Debug, Clone)]
pub struct EmbeddedPrepared {
    pub ipv4: String,
    pub ipv6: String,
    pub peer: SocketAddr,
}

#[derive(Debug, Clone, Copy)]
pub struct EmbeddedScanResult {
    pub peer: SocketAddr,
    pub rtt: std::time::Duration,
}

pub enum EmbeddedEndpoint {
    Socks,
    Tun {
        device_to_tunnel: tokio::sync::mpsc::Receiver<Vec<u8>>,
        tunnel_to_device: tokio::sync::mpsc::Sender<Vec<u8>>,
    },
}

/// The envelope an exported identity travels in.
///
/// Versioned because it leaves the device and can come back into a build that
/// did not write it. Refusing an unknown version is the honest failure; guessing
/// at its shape and writing the result over a working identity is not.
const IDENTITY_EXPORT_VERSION: u32 = 1;

/// Packages this install's identities so they survive a reinstall.
///
/// Cloudflare rate-limits device registrations per address, and uninstalling
/// discards the identity -- so a few reinstalls can leave an address refused
/// outright. Carrying the registration across is the difference between that and
/// connecting immediately.
///
/// Both accounts go in. WARP-in-WARP needs a second one for its inner hop, and
/// leaving it behind would have the user pay for it again on the first nested
/// connect, which is the cost this exists to avoid.
pub fn export_identity(base_config: &str) -> Result<String> {
    let shared = warp_config_path(base_config);
    adopt_legacy_masque_identity(&shared)?;
    let identity = config::load(&shared)?
        .ok_or_else(|| AetherError::Other("there is no identity to export yet".into()))?;

    let secondary = derive_sibling_path(&shared, "secondary");
    let inner = config::load(&secondary).ok().flatten();

    let mut out = String::new();
    out.push_str(&format!("version = {IDENTITY_EXPORT_VERSION}\n"));
    out.push_str(&format!("device_id = {:?}\n\n", identity.device_id));
    out.push_str("[identity]\n");
    out.push_str(&config::to_text(&identity)?);
    if let Some(inner) = inner {
        out.push_str("\n[secondary]\n");
        out.push_str(&config::to_text(&inner)?);
    }
    Ok(out)
}

/// Restores identities produced by [`export_identity`].
///
/// Everything is parsed and checked before a single byte is written. Half an
/// import is worse than none: it would leave the device holding an identity
/// Cloudflare does not recognise, with the working one already gone.
pub fn import_identity(base_config: &str, payload: &str) -> Result<()> {
    let envelope: ExportEnvelope = toml::from_str(payload).map_err(|e| {
        AetherError::Other(format!("this is not a WhiteAesther identity file: {e}"))
    })?;

    if envelope.version != IDENTITY_EXPORT_VERSION {
        return Err(AetherError::Other(format!(
            "this file was written by a different version of WhiteAesther (format {}, this build reads {IDENTITY_EXPORT_VERSION})",
            envelope.version
        )));
    }

    let identity = config::parse(
        &toml::to_string(&envelope.identity)
            .map_err(|e| AetherError::Other(format!("this identity file is malformed: {e}")))?,
    )?;
    let secondary = match envelope.secondary {
        Some(value) => Some(config::parse(&toml::to_string(&value).map_err(|e| {
            AetherError::Other(format!("the second identity is malformed: {e}"))
        })?)?),
        None => None,
    };

    let shared = warp_config_path(base_config);
    config::write_identity(&shared, &identity)?;
    if let Some(secondary) = secondary {
        config::write_identity(&derive_sibling_path(&shared, "secondary"), &secondary)?;
    }
    log::info!("[+] imported identity for device {}", identity.device_id);
    Ok(())
}

#[derive(serde::Deserialize)]
struct ExportEnvelope {
    version: u32,
    identity: toml::Value,
    #[serde(default)]
    secondary: Option<toml::Value>,
}

pub async fn prepare_embedded(config: &EmbeddedConfig) -> Result<EmbeddedPrepared> {
    let config_path = config.identity_path();
    let identity = match config.protocol() {
        Protocol::Masque => load_or_provision_masque(&config_path).await?,
        _ => load_or_provision_warp(&config_path).await?,
    };
    let peer = match config.protocol() {
        Protocol::Masque => select_embedded_peer(&identity, config, &config_path).await?,
        _ => {
            select_embedded_wg_peer(&identity, config, &config_path)
                .await?
                .0
        }
    };

    let profile = std::env::var("AETHER_NOIZE").unwrap_or_else(|_| "firewall".to_string());
    lastconn::save(&lastconn_path(&config_path), &peer.to_string(), &profile);

    // Nested, the interface is addressed for the inner account: that is the one
    // whose packets reach the internet, and addressing it as the outer would
    // give every connection the wrong source.
    if config.protocol() == Protocol::WarpInWarp {
        let secondary = load_or_provision_warp(&config.secondary_identity_path()).await?;
        return Ok(EmbeddedPrepared {
            ipv4: secondary.ipv4,
            ipv6: secondary.ipv6,
            peer,
        });
    }

    Ok(EmbeddedPrepared {
        ipv4: identity.ipv4,
        ipv6: identity.ipv6,
        peer,
    })
}

/// Picks a WireGuard endpoint, and the obfuscation profile that reached it.
///
/// The profile travels with the address because for WireGuard they are one
/// answer, not two: an endpoint verified under one profile does not necessarily
/// answer under another, so carrying the address alone would lose half of what
/// the scan established.
async fn select_embedded_wg_peer(
    identity: &account::Identity,
    config: &EmbeddedConfig,
    config_path: &str,
) -> Result<(SocketAddr, aethernoize::AetherNoizeConfig, String)> {
    let candidates = wg_profile_candidates();

    if let Some(peer) = config.peer {
        for (name, profile) in &candidates {
            if verify_wg_peer(identity, peer, profile).await.is_ok() {
                return Ok((peer, profile.clone(), name.clone()));
            }
        }
        if !config.peer_fallback {
            return Err(AetherError::Other(format!(
                "custom endpoint {peer} failed WireGuard validation"
            )));
        }
        log::warn!("[-] custom endpoint {peer} failed; falling back to automatic discovery");
    }

    if let Some(cached) = lastconn::load(&lastconn_path(config_path)) {
        if let Ok(peer) = cached.peer.parse::<SocketAddr>() {
            let profile = aethernoize::from_profile(&cached.profile);
            if verify_wg_peer(identity, peer, &profile).await.is_ok() {
                log::info!("[+] cached WireGuard endpoint {peer} still works");
                return Ok((peer, profile, cached.profile.clone()));
            }
        }
    }

    hunt_wg_peer(
        identity,
        &candidates,
        &config.scan_mode,
        prober::IpScan::parse(&config.ip_scan),
        &HashSet::new(),
    )
    .await
}

async fn verify_wg_peer(
    identity: &account::Identity,
    peer: SocketAddr,
    profile: &aethernoize::AetherNoizeConfig,
) -> Result<std::time::Duration> {
    let private_key = identity.private_key_bytes()?;
    let peer_public = identity.peer_public_key_bytes()?;
    let ipv4: std::net::Ipv4Addr = identity
        .ipv4
        .parse()
        .map_err(|_| AetherError::Other("invalid ipv4".into()))?;
    wireguard::verify_endpoint(
        peer,
        private_key,
        peer_public,
        identity.client_id,
        ipv4,
        profile,
        std::time::Duration::from_secs(8),
        None,
    )
    .await
}

pub async fn scan_embedded(
    config: &EmbeddedConfig,
    limit: usize,
    cancelled: &AtomicBool,
) -> Result<Vec<EmbeddedScanResult>> {
    let config_path = config.identity_path();
    if !matches!(config.protocol(), Protocol::Masque) {
        return scan_embedded_wg(config, &config_path, limit, cancelled).await;
    }
    let identity = load_or_provision_masque(&config_path).await?;
    let probe = masque_probe(&identity, prober::IpScan::parse(&config.ip_scan));
    let results = prober::scan_gateways(
        &probe,
        prober::ScanMode::parse(&config.scan_mode),
        limit,
        cancelled,
    )
    .await?;
    Ok(results
        .into_iter()
        .map(|result| EmbeddedScanResult {
            peer: SocketAddr::new(result.ip, result.port),
            rtt: result.rtt,
        })
        .collect())
}

pub async fn test_embedded_peer(config: &EmbeddedConfig) -> Result<EmbeddedScanResult> {
    let peer = config
        .peer
        .ok_or_else(|| AetherError::Other("custom endpoint is required".into()))?;
    let config_path = config.identity_path();
    if !matches!(config.protocol(), Protocol::Masque) {
        let identity = load_or_provision_warp(&config_path).await?;
        // Every profile, because an endpoint that refuses one may answer
        // another, and reporting the first refusal as "dead" would be wrong.
        let mut last = AetherError::NoCleanEndpoint;
        for (_, profile) in wg_profile_candidates() {
            match verify_wg_peer(&identity, peer, &profile).await {
                Ok(rtt) => return Ok(EmbeddedScanResult { peer, rtt }),
                Err(error) => last = error,
            }
        }
        return Err(last);
    }
    let identity = load_or_provision_masque(&config_path).await?;
    let rtt = verify_masque_peer(&identity, peer).await?;
    Ok(EmbeddedScanResult { peer, rtt })
}

pub async fn run_embedded(
    config: EmbeddedConfig,
    endpoint: EmbeddedEndpoint,
    ready: Option<tokio::sync::oneshot::Sender<()>>,
) -> Result<()> {
    // Before any listener is bound. Every tunnel below reaches socks::serve by
    // its own path, and none of them carries this.
    socks::configure_access(config.access.clone());

    let peer = config
        .peer
        .ok_or_else(|| AetherError::Other("embedded peer is required".into()))?;
    let config_path = config.identity_path();

    if !matches!(config.protocol(), Protocol::Masque) {
        let identity = load_or_provision_warp(&config_path).await?;
        // Which profile reaches this endpoint is part of what the scan found,
        // and it is not recorded anywhere the caller could hand back -- so it is
        // established again here rather than guessed.
        let (peer, profile, name) =
            select_embedded_wg_peer(&identity, &config.clone_with_peer(peer), &config_path).await?;
        log::info!("[+] WireGuard endpoint {peer} using aethernoize profile '{name}'");
        lastconn::save(&lastconn_path(&config_path), &peer.to_string(), &name);

        if config.protocol() == Protocol::WarpInWarp {
            let secondary = load_or_provision_warp(&config.secondary_identity_path()).await?;
            return run_warp_in_warp_embedded(
                &identity,
                &secondary,
                peer,
                config.listen,
                endpoint,
                ready,
            )
            .await;
        }

        return run_wireguard_tunnel_embedded(
            &identity,
            peer,
            profile,
            config.listen,
            endpoint,
            ready,
        )
        .await;
    }

    let identity = load_or_provision_masque(&config_path).await?;
    let ech = resolve_ech().await;
    run_masque_tunnel_embedded(&identity, peer, ech, config.listen, endpoint, ready).await
}

/// A WARP-in-WARP tunnel behind the embedded endpoint abstraction.
///
/// Two WireGuard tunnels, the inner one handshaking through the outer. What an
/// observer sees is a single WARP session carrying opaque UDP; what actually
/// reaches the internet leaves the inner account, one hop further in.
///
/// The outer tunnel keeps a userspace network stack because the forwarder needs
/// somewhere to open a socket. The inner one does not: its packets are the
/// user's, so they go straight to whatever the caller asked for.
async fn run_warp_in_warp_embedded(
    primary: &account::Identity,
    secondary: &account::Identity,
    peer: SocketAddr,
    listen: SocketAddr,
    endpoint: EmbeddedEndpoint,
    ready: Option<tokio::sync::oneshot::Sender<()>>,
) -> Result<()> {
    log::info!("[*] establishing outer WARP tunnel to {peer}...");
    let (outer_stack, mut outer_exit) =
        establish_wg(primary, peer, WIREGUARD_MTU, true, 5, "outer").await?;

    let (forwarder, _forwarder_guard) = spawn_udp_forwarder(&outer_stack, peer).await?;
    log::info!("[+] inner endpoint tunneled through outer warp via {forwarder}");

    // Obfuscation off and a slower keepalive on the inner hop: it is already
    // inside an obfuscated tunnel, so a second layer costs bytes and hides
    // nothing that the outer one has not hidden already.
    log::info!("[*] establishing inner WARP tunnel (warp-in-warp)...");
    let inner = establish_wg_channels(secondary, forwarder, false, 20, "inner").await?;
    let mut inner_exit = inner.exit;

    // Both hops are up and have each carried a packet, so this is the first
    // point at which the tunnel is genuinely usable.
    if let Some(ready) = ready {
        let _ = ready.send(());
    }

    let mut endpoint_task = match endpoint {
        EmbeddedEndpoint::Socks => {
            let stack = netstack::spawn(
                &secondary.ipv4,
                &secondary.ipv6,
                INNER_MTU,
                inner.inbound_rx,
                inner.outbound_tx,
            )?;
            tokio::spawn(async move {
                log::info!("[+] socks5 server listening on {listen}");
                socks::serve(listen, stack).await
            })
        }
        EmbeddedEndpoint::Tun {
            mut device_to_tunnel,
            tunnel_to_device,
        } => {
            let outbound_tx = inner.outbound_tx;
            let mut inbound_rx = inner.inbound_rx;
            tokio::spawn(async move {
                loop {
                    tokio::select! {
                        packet = device_to_tunnel.recv() => match packet {
                            Some(packet) => outbound_tx.send(packet).await.map_err(|_| {
                                AetherError::Other("embedded tunnel outbound channel closed".into())
                            })?,
                            None => return Ok(()),
                        },
                        packet = inbound_rx.recv() => match packet {
                            Some(packet) => tunnel_to_device.send(packet).await.map_err(|_| {
                                AetherError::Other("Android TUN writer channel closed".into())
                            })?,
                            None => return Ok(()),
                        },
                    }
                }
            })
        }
    };

    #[derive(PartialEq)]
    enum Finished {
        Outer,
        Inner,
        Endpoint,
    }

    let (outcome, finished) = tokio::select! {
        result = &mut outer_exit => (join_outcome("outer wireguard tunnel", result), Finished::Outer),
        result = &mut inner_exit => (join_outcome("inner wireguard tunnel", result), Finished::Inner),
        result = &mut endpoint_task => (
            match result {
                Ok(result) => result,
                Err(error) => {
                    Err(AetherError::Other(format!("embedded endpoint task failed: {error}")))
                }
            },
            Finished::Endpoint,
        ),
    };

    // The handle that just resolved above must not be awaited again. A
    // JoinHandle polled after it has yielded Ready panics, and a panic here
    // unwinds into the JNI boundary, which Android turns into an abort with no
    // Java stack trace -- a session that ends by vanishing rather than
    // stopping. Aborting a task that has already finished is harmless; awaiting
    // its handle a second time is not.
    if finished != Finished::Outer {
        outer_exit.abort();
        let _ = outer_exit.await;
    }
    if finished != Finished::Inner {
        inner_exit.abort();
        let _ = inner_exit.await;
    }
    if finished != Finished::Endpoint {
        endpoint_task.abort();
        let _ = endpoint_task.await;
    }
    drop(outer_stack);

    outcome
}

/// The same endpoint scan as [`scan_embedded`], for WireGuard.
async fn scan_embedded_wg(
    config: &EmbeddedConfig,
    config_path: &str,
    limit: usize,
    cancelled: &AtomicBool,
) -> Result<Vec<EmbeddedScanResult>> {
    let identity = load_or_provision_warp(config_path).await?;
    let private_key = identity.private_key_bytes()?;
    let peer_public = identity.peer_public_key_bytes()?;

    let probe = wg_prober::WgProbe {
        private_key: std::sync::Arc::new(private_key),
        peer_public_key: std::sync::Arc::new(peer_public),
        client_id: identity.client_id,
        local_ipv4: identity
            .ipv4
            .parse()
            .map_err(|_| AetherError::Other("invalid ipv4".into()))?,
        aethernoize: aethernoize_config(),
        ports: wireguard::WG_PORTS.to_vec(),
        ip: prober::IpScan::parse(&config.ip_scan),
        excluded: HashSet::new(),
    };

    let results = wg_prober::scan_wg_endpoints(
        &probe,
        wg_prober::WgScanMode::parse(&config.scan_mode),
        limit,
        cancelled,
    )
    .await?;

    Ok(results
        .into_iter()
        .map(|result| EmbeddedScanResult {
            peer: SocketAddr::new(result.ip, result.port),
            rtt: result.rtt,
        })
        .collect())
}

/// A WireGuard tunnel behind the embedded endpoint abstraction.
///
/// The MASQUE equivalent has to wait for the far end to assign an address; here
/// the address came with the account, so there is nothing to wait for and the
/// handshake itself is the readiness signal.
async fn run_wireguard_tunnel_embedded(
    identity: &account::Identity,
    peer: SocketAddr,
    aethernoize: aethernoize::AetherNoizeConfig,
    listen: SocketAddr,
    endpoint: EmbeddedEndpoint,
    ready: Option<tokio::sync::oneshot::Sender<()>>,
) -> Result<()> {
    let private_key = identity.private_key_bytes()?;
    let peer_public = identity.peer_public_key_bytes()?;
    let ipv4: std::net::Ipv4Addr = identity
        .ipv4
        .parse()
        .map_err(|_| AetherError::Other("invalid ipv4".into()))?;

    log::info!("[*] validating WireGuard tunnel with {peer} (handshake + data-plane)...");
    let (_, session) = wireguard::verify_endpoint_keep_session(
        peer,
        private_key,
        peer_public,
        identity.client_id,
        ipv4,
        &aethernoize,
        wg_tunnel_validate_timeout(),
        Some(wg_keepalive_secs()),
    )
    .await
    .map_err(|error| AetherError::Other(format!("tunnel failed validation: {error}")))?;
    log::info!("[+] wireguard tunnel validated (end-to-end data confirmed)");

    // Only now, because unlike MASQUE this is the first point at which the far
    // end has actually carried a packet.
    if let Some(ready) = ready {
        let _ = ready.send(());
    }

    let (outbound_tx, outbound_rx) = tokio::sync::mpsc::channel(sysprofile::channel_capacity());
    let (inbound_tx, inbound_rx) = tokio::sync::mpsc::channel(sysprofile::channel_capacity());

    let tunnel = wireguard::WgTunnel::from_established(
        session,
        std::sync::Arc::new(aethernoize),
        inbound_tx,
        ipv4,
    );

    let mut endpoint_task = match endpoint {
        EmbeddedEndpoint::Socks => {
            let stack = netstack::spawn(
                &identity.ipv4,
                &identity.ipv6,
                WIREGUARD_MTU,
                inbound_rx,
                outbound_tx,
            )?;
            tokio::spawn(async move {
                log::info!("[+] socks5 server listening on {listen}");
                socks::serve(listen, stack).await
            })
        }
        EmbeddedEndpoint::Tun {
            mut device_to_tunnel,
            tunnel_to_device,
        } => tokio::spawn(async move {
            let mut inbound_rx = inbound_rx;
            loop {
                tokio::select! {
                    packet = device_to_tunnel.recv() => match packet {
                        Some(packet) => outbound_tx.send(packet).await.map_err(|_| {
                            AetherError::Other("embedded tunnel outbound channel closed".into())
                        })?,
                        None => return Ok(()),
                    },
                    packet = inbound_rx.recv() => match packet {
                        Some(packet) => tunnel_to_device.send(packet).await.map_err(|_| {
                            AetherError::Other("Android TUN writer channel closed".into())
                        })?,
                        None => return Ok(()),
                    },
                }
            }
        }),
    };

    let mut tunnel_task = tokio::spawn(tunnel.run(outbound_rx));

    tokio::select! {
        result = &mut tunnel_task => {
            endpoint_task.abort();
            embedded_tunnel_result(result, "wireguard tunnel exited")
        }
        result = &mut endpoint_task => {
            tunnel_task.abort();
            let _ = tunnel_task.await;
            match result {
                Ok(result) => result,
                Err(error) => Err(AetherError::Other(format!("embedded endpoint task failed: {error}"))),
            }
        }
    }
}

async fn select_embedded_peer(
    identity: &account::Identity,
    config: &EmbeddedConfig,
    config_path: &str,
) -> Result<SocketAddr> {
    if let Some(peer) = config.peer {
        if quick_verify_masque_peer(identity, peer).await {
            return Ok(peer);
        }
        if !config.peer_fallback {
            return Err(AetherError::Other(format!(
                "custom endpoint {peer} failed MASQUE validation"
            )));
        }
        log::warn!("[-] custom endpoint {peer} failed; falling back to automatic discovery");
    }

    if let Some(assigned) = std::env::var("AETHER_TEAM_ENDPOINT")
        .ok()
        .and_then(|value| value.parse::<SocketAddr>().ok())
    {
        if quick_verify_masque_peer(identity, assigned).await {
            return Ok(assigned);
        }
    }

    if let Some(cached) = lastconn::load(&lastconn_path(config_path)) {
        if let Ok(peer) = cached.peer.parse::<SocketAddr>() {
            if quick_verify_masque_peer(identity, peer).await {
                return Ok(peer);
            }
        }
    }

    hunt_masque_peer(
        identity,
        &config.scan_mode,
        prober::IpScan::parse(&config.ip_scan),
    )
    .await
}

async fn run_masque_tunnel_embedded(
    identity: &account::Identity,
    peer: SocketAddr,
    ech: Option<Vec<u8>>,
    listen: SocketAddr,
    endpoint: EmbeddedEndpoint,
    ready: Option<tokio::sync::oneshot::Sender<()>>,
) -> Result<()> {
    let (chans, internals) = quic::channels();
    let cfg = quic::TunnelConfig {
        peer,
        sni: consts::CONNECT_SNI.to_string(),
        authority: quic::default_authority().to_string(),
        path: quic::default_path().to_string(),
        cert_pem: identity.cert_pem.clone(),
        key_pem: identity.key_pem.clone(),
        ech_config_list: ech,
        noize: noize_config(),
        local_ipv4: parse_local_v4(&identity.ipv4),
        quiet: false,
    };

    let quic::Channels {
        outbound_tx,
        inbound_rx,
        ctrl_tx,
    } = chans;
    let (addr_tx, mut addr_rx) = tokio::sync::mpsc::channel::<quic::AssignedAddr>(8);
    let (ready_tx, ready_rx) = tokio::sync::oneshot::channel::<()>();

    let mut tunnel_task = if masque_h2::enabled() {
        let h2cfg = masque_h2::H2TunnelConfig {
            peer: masque_h2::h2_peer(peer),
            sni: consts::CONNECT_SNI.to_string(),
            authority: quic::default_authority().to_string(),
            path: quic::default_path().to_string(),
            cert_pem: identity.cert_pem.clone(),
            key_pem: identity.key_pem.clone(),
            local_ipv4: parse_local_v4(&identity.ipv4),
            quiet: false,
            pin_endpoint: true,
            expected_pins: consts::MASQUE_PINS.iter().map(|pin| pin.to_vec()).collect(),
        };
        tokio::spawn(masque_h2::run(
            h2cfg,
            internals,
            Some(addr_tx),
            Some(ready_tx),
        ))
    } else {
        tokio::spawn(quic::run(cfg, internals, Some(addr_tx), Some(ready_tx)))
    };

    match tokio::time::timeout(masque_startup_timeout(), ready_rx).await {
        Ok(Ok(())) => {}
        Ok(Err(_)) => {
            return embedded_tunnel_result(tunnel_task.await, "tunnel exited before validation")
        }
        Err(_) => {
            tunnel_task.abort();
            let _ = tunnel_task.await;
            return Err(AetherError::Other(
                "embedded tunnel startup timed out".into(),
            ));
        }
    }
    if let Some(ready) = ready {
        let _ = ready.send(());
    }

    let mut endpoint_task = match endpoint {
        EmbeddedEndpoint::Socks => {
            let stack = netstack::spawn(
                &identity.ipv4,
                &identity.ipv6,
                MASQUE_MTU,
                inbound_rx,
                outbound_tx,
            )?;
            let bridge_stack = stack.clone();
            tokio::spawn(async move {
                while let Some(address) = addr_rx.recv().await {
                    let result = match address.ip {
                        IpAddr::V4(v4) => {
                            bridge_stack
                                .set_addrs(Some((v4, address.prefix)), None)
                                .await
                        }
                        IpAddr::V6(v6) => {
                            bridge_stack
                                .set_addrs(None, Some((v6, address.prefix)))
                                .await
                        }
                    };
                    if let Err(error) = result {
                        log::warn!("failed to update embedded netstack address: {error}");
                    }
                }
            });
            tokio::spawn(async move { socks::serve(listen, stack).await })
        }
        EmbeddedEndpoint::Tun {
            mut device_to_tunnel,
            tunnel_to_device,
        } => {
            tokio::spawn(async move {
                while let Some(address) = addr_rx.recv().await {
                    log::debug!(
                        "edge assigned embedded TUN address {}/{}",
                        address.ip,
                        address.prefix,
                    );
                }
            });
            tokio::spawn(async move {
                let mut inbound_rx = inbound_rx;
                loop {
                    tokio::select! {
                        packet = device_to_tunnel.recv() => match packet {
                            Some(packet) => outbound_tx.send(packet).await.map_err(|_| {
                                AetherError::Other("embedded tunnel outbound channel closed".into())
                            })?,
                            None => return Ok(()),
                        },
                        packet = inbound_rx.recv() => match packet {
                            Some(packet) => tunnel_to_device.send(packet).await.map_err(|_| {
                                AetherError::Other("Android TUN writer channel closed".into())
                            })?,
                            None => return Ok(()),
                        },
                    }
                }
            })
        }
    };

    tokio::select! {
        result = &mut tunnel_task => {
            endpoint_task.abort();
            embedded_tunnel_result(result, "embedded tunnel exited")
        }
        result = &mut endpoint_task => {
            let _ = ctrl_tx.send(quic::Control::Close).await;
            tunnel_task.abort();
            let _ = tunnel_task.await;
            match result {
                Ok(result) => result,
                Err(error) => Err(AetherError::Other(format!("embedded endpoint task failed: {error}"))),
            }
        }
    }
}

fn embedded_tunnel_result(
    result: std::result::Result<Result<()>, tokio::task::JoinError>,
    context: &str,
) -> Result<()> {
    match result {
        Ok(Ok(())) => Ok(()),
        Ok(Err(error)) => Err(AetherError::Other(format!("{context}: {error}"))),
        Err(error) => Err(AetherError::Other(format!("{context}: {error}"))),
    }
}

async fn run_gool(
    primary: account::Identity,
    secondary: account::Identity,
    listen: SocketAddr,
) -> Result<()> {
    let mut last_peer: Option<SocketAddr> = None;
    let mut last_inner: Option<SocketAddr> = None;
    let mut consecutive_fails: u32 = 0;
    const MAX_CONSECUTIVE_FAILS: u32 = 2;

    loop {
        let peer = if consecutive_fails < MAX_CONSECUTIVE_FAILS {
            if let Some(p) = last_peer {
                Some(p)
            } else {
                None
            }
        } else {
            if let Some(p) = last_peer {
                log::warn!(
                    "[-] outer endpoint {p} failed {consecutive_fails} times in a row; blacklisting and rescanning"
                );
            }
            None
        };

        let pair = match peer {
            Some(p) => Some((p, last_inner)),
            None => {
                let mode_str = select_scan_mode_str().await;
                let ip = select_ip_version().await;
                match select_wg_peers(&primary, &mode_str, ip, 2).await {
                    Ok(found) => {
                        consecutive_fails = 0;
                        let outer = found[0];
                        let inner = found.get(1).copied();
                        Some((outer, inner))
                    }
                    Err(e) => {
                        log::warn!(
                            "[-] no usable outer WARP endpoint found: {e}; rescanning shortly"
                        );
                        tokio::time::sleep(wg_reconnect_delay()).await;
                        continue;
                    }
                }
            }
        };

        let (peer, inner_peer) = match pair {
            Some(pair) => pair,
            None => continue,
        };

        let inner_peer = match inner_peer {
            Some(inner) => inner,
            None => {
                log::warn!(
                    "[-] the scan only turned up {peer}, so warp-in-warp would use one edge twice; rescanning"
                );
                last_peer = None;
                last_inner = None;
                tokio::time::sleep(wg_reconnect_delay()).await;
                continue;
            }
        };

        log::info!("[+] using cloudflare edge {peer} (outer) and {inner_peer} (inner)");
        last_peer = Some(peer);
        last_inner = Some(inner_peer);

        match run_warp_in_warp(primary.clone(), secondary.clone(), peer, inner_peer, listen).await {
            Ok(()) => log::warn!("[-] gool tunnel closed; reconnecting"),
            Err(e) => log::warn!("[-] gool tunnel ended: {e}; reconnecting"),
        }
        consecutive_fails += 1;

        tokio::time::sleep(wg_reconnect_delay()).await;
    }
}

fn install_netstack_panic_guard() {
    let default_hook = std::panic::take_hook();
    std::panic::set_hook(Box::new(move |info| {
        let from_netstack = info
            .location()
            .map(|l| l.file().contains("smoltcp"))
            .unwrap_or(false);
        if from_netstack {
            log::debug!("[netstack] recovered from a malformed segment: {info}");
        } else {
            default_hook(info);
        }
    }));
}

fn noize_config() -> noize::NoizeConfig {
    let profile = std::env::var("AETHER_NOIZE").unwrap_or_else(|_| "firewall".to_string());
    log::info!("[+] obfuscation profile: {profile}");
    noize::from_profile(&profile)
}

fn aethernoize_config() -> aethernoize::AetherNoizeConfig {
    let profile = std::env::var("AETHER_NOIZE").unwrap_or_else(|_| "balanced".to_string());
    log::info!("[+] aethernoize profile: {profile}");
    aethernoize::from_profile(&profile)
}

fn team_scope() -> Option<String> {
    zerotrust::TeamSettings::from_env().map(|settings| settings.team)
}

fn enrolled_teams(base: &str) -> Vec<String> {
    let dir_end = base
        .rfind(|c| c == '/' || c == '\\')
        .map(|i| i + 1)
        .unwrap_or(0);
    let dir = if dir_end == 0 { "." } else { &base[..dir_end] };
    let stem = match base[dir_end..].rfind('.') {
        Some(rel) => &base[dir_end..dir_end + rel],
        None => &base[dir_end..],
    };
    let prefix = format!("{stem}-team-");

    let entries = match std::fs::read_dir(dir) {
        Ok(entries) => entries,
        Err(_) => return Vec::new(),
    };

    let mut teams: Vec<String> = Vec::new();
    for entry in entries.flatten() {
        let name = entry.file_name().to_string_lossy().to_string();
        let Some(rest) = name.strip_prefix(&prefix) else {
            continue;
        };
        let Some(team) = rest.strip_suffix(".toml") else {
            continue;
        };
        if team.is_empty() || team.ends_with("-secondary") || team.ends_with("-lastconn") {
            continue;
        }
        if !teams.iter().any(|known| known == team) {
            teams.push(team.to_string());
        }
    }
    teams.sort();
    teams
}

async fn enrol_zero_trust(base: &str) {
    let known = enrolled_teams(base);

    let prompt = match known.first() {
        Some(team) => format!(
            "\nZero Trust organization.\n  already enrolled: {}\nTeam name from \
             <team>.cloudflareaccess.com, or blank to reuse '{}': ",
            known.join(", "),
            team
        ),
        None => "\nZero Trust organization.\nTeam name from <team>.cloudflareaccess.com \
                 (blank to cancel): "
            .to_string(),
    };

    let answer = prompt_line(&prompt).await.unwrap_or_default();
    let answer = answer.trim().to_string();

    let team = if answer.is_empty() {
        match known.first() {
            Some(team) => team.clone(),
            None => {
                log::info!("[*] Zero Trust skipped; staying on personal WARP");
                return;
            }
        }
    } else {
        match zerotrust::normalize_team(&answer) {
            Some(team) => team,
            None => {
                log::warn!("[-] '{answer}' is not a usable team name");
                return;
            }
        }
    };

    std::env::set_var("AETHER_TEAM", &team);

    if known.iter().any(|enrolled| *enrolled == team) {
        log::info!("[+] reusing the saved enrolment for team {team}; no sign-in needed");
        return;
    }

    let needs_method = match zerotrust::TeamSettings::from_env() {
        Some(settings) => {
            !(settings.token.is_some() || settings.has_service_token() || settings.email.is_some())
        }
        None => {
            std::env::remove_var("AETHER_TEAM");
            return;
        }
    };

    if needs_method {
        let email = prompt_line("Email address for the one-time login code (blank to cancel): ")
            .await
            .unwrap_or_default();
        let email = email.trim().to_string();

        if email.is_empty() {
            log::warn!("[-] no email given; staying on personal WARP");
            std::env::remove_var("AETHER_TEAM");
            return;
        }

        std::env::set_var("AETHER_ACCESS_EMAIL", &email);
    }

    let settings = match zerotrust::TeamSettings::from_env() {
        Some(settings) => settings,
        None => {
            std::env::remove_var("AETHER_TEAM");
            return;
        }
    };

    match zerotrust::resolve_token(&settings).await {
        Ok(_) => log::info!("[+] signed in to team {team}; now pick the transport to use"),
        Err(error) => {
            log::error!("[-] Zero Trust sign-in failed: {error}");
            log::warn!("[-] staying on personal WARP");
            std::env::remove_var("AETHER_TEAM");
            std::env::remove_var("AETHER_ACCESS_EMAIL");
        }
    }
}

async fn provision_account() -> Result<account::Identity> {
    match zerotrust::TeamSettings::from_env() {
        Some(settings) => {
            log::info!(
                "[*] enrolling this device into the Zero Trust organization {} ({})",
                settings.team,
                settings.team_domain()
            );
            let identity =
                account::provision_team(consts::DEFAULT_MODEL, consts::DEFAULT_LOCALE, &settings)
                    .await?;
            Ok(account::refresh_profile(identity).await)
        }
        None => account::provision_wg(consts::DEFAULT_MODEL, consts::DEFAULT_LOCALE, None).await,
    }
}

async fn adopt_team_profile(identity: account::Identity) -> account::Identity {
    if team_scope().is_none() {
        return identity;
    }

    let identity = account::refresh_profile(identity).await;

    if !identity.gateway_proxy.is_empty() {
        if std::env::var("AETHER_GATEWAY").is_ok() {
            socks::set_gateway_proxy(&identity.gateway_proxy);
        } else {
            log::debug!(
                "[zerotrust] the organization offers a gateway proxy at {}; pass --gateway to route http through it",
                identity.gateway_proxy
            );
        }
    }

    if !identity.assigned_endpoint.is_empty() && std::env::var("AETHER_PEER").is_err() {
        let port = if std::env::var("AETHER_PROTOCOL")
            .map(|value| value == "wg" || value == "gool")
            .unwrap_or(false)
        {
            2408
        } else {
            443
        };
        let peer = format!("{}:{port}", identity.assigned_endpoint);
        if peer.parse::<SocketAddr>().is_ok() {
            log::info!("[+] the organization assigned endpoint {peer}; trying it before scanning");
            std::env::set_var("AETHER_TEAM_ENDPOINT", &peer);
        }
    }

    identity
}

fn warp_config_path(base: &str) -> String {
    if let Ok(p) = std::env::var("AETHER_WG_CONFIG") {
        return p;
    }
    match team_scope() {
        Some(team) => derive_sibling_path(base, &format!("team-{team}")),
        None => base.to_string(),
    }
}

/// Where MASQUE's identity lives -- its own file, apart from WireGuard's.
///
/// It looks like it should be the same file. A Cloudflare WARP account already
/// carries WireGuard keys, and a MASQUE certificate is enrolled onto that same
/// device rather than being a second account, so sharing one registration would
/// halve the number a user spends against a rate limit that counts per IP.
///
/// Sharing it destroys WireGuard. Enrolment is `PATCH /reg/{id}` and it writes
/// the same `key` field that registration filled with the Curve25519 public
/// key, replacing it with a secp256r1 SPKI and setting `tunnel_type: masque`.
/// Cloudflare then has no WireGuard key for the device, and WireGuard answers
/// an unrecognised peer with silence -- so every endpoint everywhere stops
/// replying, which is indistinguishable from a network that drops UDP.
///
/// The damage is not immediate. Measured against the live API the old key keeps
/// working for something between thirty and sixty seconds while the change
/// reaches the edge, which is long enough for a check made right after
/// enrolment to come back clean. `wireguard_dies_when_masque_is_enrolled_on_the
/// _same_account` waits it out.
fn masque_config_path(base: &str) -> String {
    if let Ok(p) = std::env::var("AETHER_MASQUE_CONFIG") {
        return p;
    }
    derive_sibling_path(&warp_config_path(base), "masque")
}

/// The file the two protocols briefly shared, given MASQUE's own path.
///
/// Read only when MASQUE's own file is absent, so an install that already paid
/// for a registration keeps it instead of buying another.
fn shared_config_path(masque_path: &str) -> Option<String> {
    strip_sibling_path(masque_path, "masque")
}

/// The inverse of [`derive_sibling_path`]: the file a sibling was derived from.
///
/// `None` when the path is not a sibling of that kind, which is what an
/// override through `AETHER_MASQUE_CONFIG` looks like -- and an override points
/// somewhere deliberate, so nothing should be migrated into it.
fn strip_sibling_path(path: &str, suffix: &str) -> Option<String> {
    let dir_end = path
        .rfind(|c| c == '/' || c == '\\')
        .map(|i| i + 1)
        .unwrap_or(0);
    let marker = format!("-{suffix}");
    match path[dir_end..].rfind('.') {
        Some(rel) => {
            let dot = dir_end + rel;
            let stem = &path[..dot];
            stem.strip_suffix(&marker)
                .map(|trimmed| format!("{trimmed}{}", &path[dot..]))
        }
        None => path.strip_suffix(&marker).map(|t| t.to_string()),
    }
}

fn derive_sibling_path(base: &str, suffix: &str) -> String {
    let dir_end = base
        .rfind(|c| c == '/' || c == '\\')
        .map(|i| i + 1)
        .unwrap_or(0);
    match base[dir_end..].rfind('.') {
        Some(rel) => {
            let dot = dir_end + rel;
            format!("{}-{}{}", &base[..dot], suffix, &base[dot..])
        }
        None => format!("{base}-{suffix}"),
    }
}

fn keep_saved_identity() -> bool {
    !matches!(
        std::env::var("AETHER_REPROVISION").as_deref(),
        Ok("0") | Ok("off") | Ok("false")
    )
}

async fn load_or_provision_warp(config_path: &str) -> Result<account::Identity> {
    if let Some(identity) = config::load(config_path)? {
        // A certificate on this identity means a MASQUE key was enrolled onto
        // the same Cloudflare device, which replaced its WireGuard public key.
        // Nothing recognises the key on disk any more, so every endpoint would
        // meet it with silence and the search would blame the network. Left
        // over from the release where the two protocols shared a file; MASQUE
        // adopts this identity for itself, and WireGuard starts again.
        if identity.has_masque_credentials() {
            log::info!(
                "[!] the identity in {config_path} was enrolled for MASQUE, which revokes its \
                 WireGuard key; provisioning a separate wireguard account"
            );
            // Hand it to MASQUE before overwriting it. The certificate on it is
            // valid and was paid for with a registration; losing it here would
            // cost the user another one for no reason.
            preserve_masque_identity(config_path, &identity)?;
        } else {
            log::info!("[+] loaded existing warp identity from {config_path}");
            let identity = adopt_team_profile(identity).await;
            config::save(config_path, &identity)?;
            return Ok(identity);
        }
    } else {
        log::info!("[+] no warp identity found; provisioning dedicated wireguard account");
    }

    let identity = provision_account().await?;
    let identity = adopt_team_profile(identity).await;
    config::save(config_path, &identity)?;
    log::info!("[+] provisioned and saved new warp identity to {config_path}");
    Ok(identity)
}

/// Copies a shared-file identity to MASQUE's own file before WireGuard
/// replaces it.
///
/// Only when MASQUE has nothing of its own yet, so a later WireGuard
/// re-provision cannot overwrite a MASQUE identity that has moved on.
fn preserve_masque_identity(config_path: &str, identity: &account::Identity) -> Result<()> {
    let masque = masque_config_path(config_path);
    if masque == config_path || config::load(&masque)?.is_some() {
        return Ok(());
    }
    log::info!("[+] keeping the enrolled identity for MASQUE at {masque}");
    config::save(&masque, identity)
}

async fn load_or_provision_masque(config_path: &str) -> Result<account::Identity> {
    adopt_legacy_masque_identity(config_path)?;
    if let Some(identity) = config::load(config_path)? {
        log::info!("[+] loaded existing masque identity from {config_path}");
        let refused = if identity.has_masque_credentials() {
            let identity = adopt_team_profile(identity).await;
            if !identity.refused {
                config::save(config_path, &identity)?;
                return Ok(identity);
            }
            identity
        } else {
            log::info!("[+] masque identity needs a certificate; enrolling masque key");
            match account::ensure_masque_enrolled(&identity).await {
                Ok(enrollment) => {
                    let identity = account::Identity {
                        cert_pem: enrollment.cert_pem,
                        key_pem: enrollment.key_pem,
                        cert_issued_at: enrollment.issued_at,
                        ..identity
                    };
                    config::save(config_path, &identity)?;
                    return Ok(identity);
                }
                Err(AetherError::IdentityRefused(reason)) => {
                    log::warn!("[-] the saved masque identity was refused: {reason}");
                    account::Identity {
                        refused: true,
                        ..identity
                    }
                }
                Err(error) => return Err(error),
            }
        };

        if !keep_saved_identity() {
            return Ok(refused);
        }
        log::warn!("[*] registering a fresh masque account to replace the refused identity");
    }

    log::info!("[+] no masque identity found; provisioning dedicated masque account");
    let identity = provision_account().await?;
    let enrollment = account::ensure_masque_enrolled(&identity).await?;
    let identity = account::Identity {
        cert_pem: enrollment.cert_pem,
        key_pem: enrollment.key_pem,
        cert_issued_at: enrollment.issued_at,
        ..identity
    };
    let identity = adopt_team_profile(identity).await;
    config::save(config_path, &identity)?;
    log::info!("[+] provisioned and saved new masque identity to {config_path}");
    Ok(identity)
}

async fn select_peer(identity: &account::Identity, protocol: Protocol) -> Result<SocketAddr> {
    let force_peer = match protocol {
        Protocol::Masque => std::env::var("AETHER_PEER").ok(),
        Protocol::WireGuard | Protocol::WarpInWarp => std::env::var("AETHER_WG_PEER")
            .ok()
            .or_else(|| std::env::var("AETHER_PEER").ok()),
    };

    if let Some(p) = force_peer {
        let peer: SocketAddr = p
            .parse()
            .map_err(|_| AetherError::Other(format!("bad peer address {p}")))?;
        log::info!("[+] using forced peer {peer} (probe skipped)");
        return Ok(peer);
    }

    log::info!("[+] selected protocol: {}", protocol.label());

    let mode_str = select_scan_mode_str().await;
    let ip = select_ip_version().await;

    match protocol {
        Protocol::Masque => {
            log::info!("[*] hunting for a working MASQUE gateway (deep connect-ip verification)");
            let mode = prober::ScanMode::parse(&mode_str);
            let probe = prober::MasqueProbe {
                sni: consts::CONNECT_SNI.to_string(),
                authority: quic::default_authority().to_string(),
                path: quic::default_path().to_string(),
                cert_pem: std::sync::Arc::from(identity.cert_pem.clone()),
                key_pem: std::sync::Arc::from(identity.key_pem.clone()),
                ech_config_list: None,
                noize: noize_config(),
                ports: prober::MASQUE_PORTS.to_vec(),
                ip,
                local_ipv4: parse_local_v4(&identity.ipv4),
            };

            let best = prober::hunt_best_gateway(&probe, mode).await?;
            log::info!(
                "[+] selected MASQUE gateway {}:{} (rtt {:?})",
                best.ip,
                best.port,
                best.rtt
            );
            Ok(SocketAddr::new(best.ip, best.port))
        }
        Protocol::WireGuard | Protocol::WarpInWarp => {
            let peers = select_wg_peers(identity, &mode_str, ip, 1).await?;
            Ok(peers[0])
        }
    }
}

async fn select_wg_peers(
    identity: &account::Identity,
    mode_str: &str,
    ip: prober::IpScan,
    want: usize,
) -> Result<Vec<SocketAddr>> {
    log::info!(
        "[*] hunting for {want} working WireGuard endpoint(s) (handshake + data-plane verification)"
    );
    let mode = wg_prober::WgScanMode::parse(mode_str);

    let private_key = identity.private_key_bytes()?;
    let peer_public = identity.peer_public_key_bytes()?;

    let probe = wg_prober::WgProbe {
        private_key: std::sync::Arc::new(private_key),
        peer_public_key: std::sync::Arc::new(peer_public),
        client_id: identity.client_id.clone(),
        local_ipv4: identity
            .ipv4
            .parse()
            .map_err(|_| AetherError::Other("invalid ipv4".into()))?,
        aethernoize: aethernoize_config(),
        ports: wireguard::WG_PORTS.to_vec(),
        ip,
        excluded: HashSet::new(),
    };

    let found = wg_prober::hunt_wg_endpoints(&probe, mode, want).await?;
    for pr in &found {
        log::info!(
            "[+] selected WireGuard endpoint {}:{} (rtt {:?})",
            pr.ip,
            pr.port,
            pr.rtt
        );
    }

    Ok(found
        .into_iter()
        .map(|pr| SocketAddr::new(pr.ip, pr.port))
        .collect())
}

async fn resolve_ech() -> Option<Vec<u8>> {
    match std::env::var("AETHER_ECH") {
        Ok(v) if v.eq_ignore_ascii_case("auto") => match dns::fetch_ech_config().await {
            Ok(raw) => {
                log::info!(
                    "[+] fetched ECHConfigList automatically ({} bytes)",
                    raw.len()
                );
                Some(raw)
            }
            Err(e) => {
                log::warn!("[-] ECH auto-fetch failed ({e}); continuing without ECH");
                None
            }
        },
        Ok(b64) if !b64.is_empty() => match tls::decode_ech_config_list(&b64) {
            Ok(v) => {
                log::info!("[+] using ECHConfigList from AETHER_ECH");
                Some(v)
            }
            Err(e) => {
                log::warn!("[-] bad AETHER_ECH: {e}; continuing without ECH");
                None
            }
        },
        _ => {
            log::info!("[+] ECH disabled (warp masque endpoint does not accept ECH); SNI sent in cleartext");
            None
        }
    }
}

fn masque_reconnect_delay() -> std::time::Duration {
    let secs = std::env::var("AETHER_MASQUE_RECONNECT_SECS")
        .ok()
        .and_then(|v| v.parse::<u64>().ok())
        .filter(|&v| v > 0)
        .unwrap_or(2);
    std::time::Duration::from_secs(secs)
}

fn masque_startup_timeout() -> std::time::Duration {
    let secs = std::env::var("AETHER_MASQUE_STARTUP_SECS")
        .ok()
        .and_then(|v| v.parse::<u64>().ok())
        .filter(|&v| v > 0)
        .unwrap_or(30);
    std::time::Duration::from_secs(secs)
}

async fn hunt_masque_peer(
    identity: &account::Identity,
    mode_str: &str,
    ip: prober::IpScan,
) -> Result<SocketAddr> {
    log::info!(
        "[*] hunting for a working MASQUE gateway (deep connect-ip + data-plane verification)"
    );
    let mode = prober::ScanMode::parse(mode_str);
    let probe = masque_probe(identity, ip);

    let best = prober::hunt_best_gateway(&probe, mode).await?;
    log::info!(
        "[+] selected MASQUE gateway {}:{} (rtt {:?})",
        best.ip,
        best.port,
        best.rtt
    );
    Ok(SocketAddr::new(best.ip, best.port))
}

fn masque_probe(identity: &account::Identity, ip: prober::IpScan) -> prober::MasqueProbe {
    prober::MasqueProbe {
        sni: consts::CONNECT_SNI.to_string(),
        authority: quic::default_authority().to_string(),
        path: quic::default_path().to_string(),
        cert_pem: std::sync::Arc::from(identity.cert_pem.clone()),
        key_pem: std::sync::Arc::from(identity.key_pem.clone()),
        ech_config_list: None,
        noize: noize_config(),
        ports: prober::MASQUE_PORTS.to_vec(),
        ip,
        local_ipv4: parse_local_v4(&identity.ipv4),
    }
}

/// Where the last working endpoint for one protocol is remembered.
///
/// Per protocol even though the identity is shared: a MASQUE gateway and a
/// WireGuard endpoint are different addresses on different ports, and offering
/// one to the other wastes a validation attempt on every connect.
/// Where the last working endpoint for an identity is remembered.
///
/// Taken from the identity's own file rather than from the protocol, because
/// each protocol has its own file again and the distinction is already in the
/// path. Applying it twice is what produced `aether-masque-masque-lastconn`.
///
/// The names this yields are the ones installs already wrote -- MASQUE's
/// identity is `aether-masque.toml`, so its cache is still
/// `aether-masque-lastconn.toml` -- so nobody loses a remembered endpoint and
/// re-scans on the first connect after updating.
fn lastconn_path(config_path: &str) -> String {
    derive_sibling_path(config_path, "lastconn")
}

/// Takes over the identity from the release where both protocols shared a file.
///
/// That identity is a perfectly good MASQUE one -- it is the WireGuard half of
/// it that enrolment destroyed -- so it is copied here rather than thrown away,
/// and MASQUE keeps working without spending another registration. WireGuard
/// gets a fresh account of its own; see [`load_or_provision_warp`].
fn adopt_legacy_masque_identity(config_path: &str) -> Result<()> {
    if config::load(config_path)?.is_some() {
        return Ok(());
    }
    let Some(shared) = shared_config_path(config_path) else {
        return Ok(());
    };
    let Some(identity) = config::load(&shared)? else {
        return Ok(());
    };
    log::info!("[+] adopting the existing identity from {shared}; no new registration needed");
    config::save(config_path, &identity)?;
    Ok(())
}

async fn quick_verify_masque_peer(identity: &account::Identity, peer: SocketAddr) -> bool {
    verify_masque_peer(identity, peer).await.is_ok()
}

async fn verify_masque_peer(
    identity: &account::Identity,
    peer: SocketAddr,
) -> Result<std::time::Duration> {
    let vp = quic::VerifyParams {
        peer,
        sni: consts::CONNECT_SNI.to_string(),
        authority: quic::default_authority().to_string(),
        path: quic::default_path().to_string(),
        cert_pem: identity.cert_pem.clone(),
        key_pem: identity.key_pem.clone(),
        ech_config_list: None,
        noize: noize_config(),
        timeout: std::time::Duration::from_secs(5),
        local_ipv4: parse_local_v4(&identity.ipv4),
    };

    if masque_h2::enabled() {
        let cfg = masque_h2::H2TunnelConfig {
            peer: masque_h2::h2_peer(peer),
            sni: consts::CONNECT_SNI.to_string(),
            authority: quic::default_authority().to_string(),
            path: quic::default_path().to_string(),
            cert_pem: identity.cert_pem.clone(),
            key_pem: identity.key_pem.clone(),
            local_ipv4: parse_local_v4(&identity.ipv4),
            quiet: true,
            pin_endpoint: true,
            expected_pins: consts::MASQUE_PINS.iter().map(|p| p.to_vec()).collect(),
        };
        return masque_h2::verify_h2(&cfg, std::time::Duration::from_secs(5))
            .await
            .map_err(|error| {
                AetherError::Other(format!("HTTP/2 endpoint validation failed: {error}"))
            });
    }

    quic::verify_masque(&vp)
        .await
        .map_err(|error| AetherError::Other(format!("HTTP/3 endpoint validation failed: {error}")))
}

async fn want_quick_reconnect(cached: &lastconn::LastConnection) -> bool {
    match std::env::var("AETHER_QUICK_RECONNECT").as_deref() {
        Ok("1") | Ok("true") | Ok("yes") | Ok("on") => return true,
        Ok("0") | Ok("false") | Ok("no") | Ok("off") => return false,
        _ => {}
    }

    let answer = prompt_line(&format!(
        "\nLast working gateway: {} (profile '{}')\nReconnect to it now without rescanning? [Y/n]: ",
        cached.peer, cached.profile
    ))
    .await;

    !matches!(answer.as_deref(), Some(a) if a.eq_ignore_ascii_case("n") || a.eq_ignore_ascii_case("no"))
}

async fn run_masque(
    identity: account::Identity,
    ech: Option<Vec<u8>>,
    listen: SocketAddr,
    lastconn_path: String,
) -> Result<()> {
    let forced = std::env::var("AETHER_PEER").ok();

    let mut quick_peer: Option<SocketAddr> = None;

    if forced.is_none() {
        if let Some(assigned) = std::env::var("AETHER_TEAM_ENDPOINT")
            .ok()
            .and_then(|value| value.parse::<SocketAddr>().ok())
        {
            log::info!("[*] verifying the endpoint the organization assigned: {assigned}");
            if quick_verify_masque_peer(&identity, assigned).await {
                log::info!("[+] the assigned endpoint {assigned} works; skipping the scan");
                quick_peer = Some(assigned);
            } else {
                log::warn!(
                    "[-] the assigned endpoint {assigned} did not answer; falling back to scanning"
                );
            }
        }
    }

    if forced.is_none() && quick_peer.is_none() {
        if let Some(cached) = lastconn::load(&lastconn_path) {
            if let Ok(peer) = cached.peer.parse::<SocketAddr>() {
                if want_quick_reconnect(&cached).await {
                    log::info!("[*] verifying cached gateway {peer} before reuse");
                    if quick_verify_masque_peer(&identity, peer).await {
                        log::info!("[+] cached gateway {peer} still works; skipping scan");
                        quick_peer = Some(peer);
                    } else {
                        log::warn!("[-] cached gateway {peer} no longer works; scanning fresh");
                    }
                }
            }
        }
    }

    let (mode_str, ip) = if forced.is_some() || quick_peer.is_some() {
        (String::new(), prober::IpScan::V4)
    } else {
        let mode_str = select_scan_mode_str().await;
        let ip = select_ip_version().await;
        (mode_str, ip)
    };

    let mut last_good_peer: Option<SocketAddr> = None;

    loop {
        let peer = if let Some(p) = quick_peer.take() {
            p
        } else {
            let retried = match last_good_peer {
                Some(p) => {
                    log::info!("[*] retrying last known-good gateway {p} before rescanning");
                    if quick_verify_masque_peer(&identity, p).await {
                        Some(p)
                    } else {
                        log::warn!(
                            "[-] last known-good gateway {p} no longer responds; rescanning"
                        );
                        None
                    }
                }
                None => None,
            };

            match retried {
                Some(p) => p,
                None => match &forced {
                    Some(p) => match p.parse::<SocketAddr>() {
                        Ok(peer) => {
                            log::info!("[+] using forced peer {peer} (probe skipped)");
                            peer
                        }
                        Err(_) => return Err(AetherError::Other(format!("bad peer address {p}"))),
                    },
                    None => match hunt_masque_peer(&identity, &mode_str, ip).await {
                        Ok(peer) => peer,
                        Err(e) => {
                            log::warn!(
                                "[-] no usable MASQUE gateway found: {e}; rescanning shortly"
                            );
                            tokio::time::sleep(masque_reconnect_delay()).await;
                            continue;
                        }
                    },
                },
            }
        };

        log::info!("[+] using cloudflare edge {peer}");

        if forced.is_none() {
            let profile = std::env::var("AETHER_NOIZE").unwrap_or_else(|_| "firewall".to_string());
            lastconn::save(&lastconn_path, &peer.to_string(), &profile);
        }

        last_good_peer = Some(peer);

        match run_masque_tunnel(&identity, peer, ech.clone(), listen).await {
            Ok(()) => log::warn!("[-] MASQUE tunnel closed; reconnecting"),
            Err(e) => log::warn!("[-] MASQUE tunnel ended: {e}; reconnecting"),
        }

        tokio::time::sleep(masque_reconnect_delay()).await;
    }
}

async fn run_masque_tunnel(
    identity: &account::Identity,
    peer: SocketAddr,
    ech: Option<Vec<u8>>,
    listen: SocketAddr,
) -> Result<()> {
    let (chans, internals) = quic::channels();

    let cfg = quic::TunnelConfig {
        peer,
        sni: consts::CONNECT_SNI.to_string(),
        authority: quic::default_authority().to_string(),
        path: quic::default_path().to_string(),
        cert_pem: identity.cert_pem.clone(),
        key_pem: identity.key_pem.clone(),
        ech_config_list: ech,
        noize: noize_config(),
        local_ipv4: parse_local_v4(&identity.ipv4),
        quiet: false,
    };

    let quic::Channels {
        outbound_tx,
        inbound_rx,
        ctrl_tx,
    } = chans;

    let stack = netstack::spawn(
        &identity.ipv4,
        &identity.ipv6,
        MASQUE_MTU,
        inbound_rx,
        outbound_tx,
    )?;
    let _ctrl = ctrl_tx;

    let mut tasks = TaskGuard::new();

    let (addr_tx, mut addr_rx) = tokio::sync::mpsc::channel::<quic::AssignedAddr>(8);
    let bridge_stack = stack.clone();
    let bridge_task = tokio::spawn(async move {
        while let Some(a) = addr_rx.recv().await {
            let res = match a.ip {
                IpAddr::V4(v4) => bridge_stack.set_addrs(Some((v4, a.prefix)), None).await,
                IpAddr::V6(v6) => bridge_stack.set_addrs(None, Some((v6, a.prefix))).await,
            };
            if let Err(e) = res {
                log::warn!("[-] failed to sync edge address into netstack: {e}");
            }
        }
    });
    tasks.push(bridge_task.abort_handle());

    let (ready_tx, ready_rx) = tokio::sync::oneshot::channel::<()>();

    let tunnel_task = if masque_h2::enabled() {
        let h2cfg = masque_h2::H2TunnelConfig {
            peer: masque_h2::h2_peer(peer),
            sni: consts::CONNECT_SNI.to_string(),
            authority: quic::default_authority().to_string(),
            path: quic::default_path().to_string(),
            cert_pem: identity.cert_pem.clone(),
            key_pem: identity.key_pem.clone(),
            local_ipv4: parse_local_v4(&identity.ipv4),
            quiet: false,
            pin_endpoint: true,
            expected_pins: consts::MASQUE_PINS.iter().map(|p| p.to_vec()).collect(),
        };
        log::info!("[+] MASQUE transport: HTTP/2 (TCP) to {}", h2cfg.peer);
        tokio::spawn(masque_h2::run(
            h2cfg,
            internals,
            Some(addr_tx),
            Some(ready_tx),
        ))
    } else {
        log::info!("[+] MASQUE transport: HTTP/3 (QUIC) to {}", peer);
        tokio::spawn(quic::run(cfg, internals, Some(addr_tx), Some(ready_tx)))
    };
    tasks.push(tunnel_task.abort_handle());

    let startup_timeout = masque_startup_timeout();
    match tokio::time::timeout(startup_timeout, ready_rx).await {
        Ok(Ok(())) => {}
        Ok(Err(_)) => {
            let joined = tunnel_task.await;
            let msg = match joined {
                Ok(Ok(())) => "tunnel exited before validation".to_string(),
                Ok(Err(e)) => format!("tunnel failed before validation: {e}"),
                Err(e) => format!("tunnel task join error: {e}"),
            };
            return Err(AetherError::Other(msg));
        }
        Err(_) => {
            tunnel_task.abort();
            let _ = tunnel_task.await;
            return Err(AetherError::Other(format!(
                "tunnel startup timed out after {:?}",
                startup_timeout
            )));
        }
    }

    let socks_stack = stack.clone();
    let socks_task = tokio::spawn(async move {
        log::info!("[+] socks5 server listening on {listen}");
        socks::serve(listen, socks_stack).await
    });
    tasks.push(socks_task.abort_handle());

    let http_task = spawn_http_proxy(&stack);
    if let Some(task) = &http_task {
        tasks.push(task.abort_handle());
    }

    let tunnel_result = tunnel_task.await;

    if let Some(task) = &http_task {
        task.abort();
    }
    socks_task.abort();

    match tunnel_result {
        Ok(Ok(())) => Ok(()),
        Ok(Err(e)) => Err(AetherError::Other(format!("tunnel exited: {e}"))),
        Err(e) => Err(AetherError::Other(format!("tunnel task join error: {e}"))),
    }
}

fn wg_keepalive_secs() -> u16 {
    std::env::var("AETHER_WG_KEEPALIVE")
        .ok()
        .and_then(|v| v.parse().ok())
        .filter(|&v| v > 0)
        .unwrap_or(5)
}

fn wg_profile_candidates() -> Vec<(String, aethernoize::AetherNoizeConfig)> {
    let primary = std::env::var("AETHER_NOIZE").unwrap_or_else(|_| "balanced".to_string());
    log::info!("[+] aethernoize primary profile: {primary}");

    let mut names = vec![primary.clone()];
    if std::env::var("AETHER_WG_NO_PROFILE_RETRY").is_err() {
        for fallback in ["balanced", "aggressive", "light", "off"] {
            if !names.iter().any(|n| n.eq_ignore_ascii_case(fallback)) {
                names.push(fallback.to_string());
            }
        }
    }

    names
        .into_iter()
        .map(|n| {
            let cfg = aethernoize::from_profile(&n);
            (n, cfg)
        })
        .collect()
}

async fn hunt_wg_peer_with_profile(
    identity: &account::Identity,
    mode_str: &str,
    ip: prober::IpScan,
    profile: aethernoize::AetherNoizeConfig,
    excluded: &HashSet<SocketAddr>,
) -> Result<SocketAddr> {
    let mode = wg_prober::WgScanMode::parse(mode_str);
    let private_key = identity.private_key_bytes()?;
    let peer_public = identity.peer_public_key_bytes()?;

    let probe = wg_prober::WgProbe {
        private_key: std::sync::Arc::new(private_key),
        peer_public_key: std::sync::Arc::new(peer_public),
        client_id: identity.client_id,
        local_ipv4: identity
            .ipv4
            .parse()
            .map_err(|_| AetherError::Other("invalid ipv4".into()))?,
        aethernoize: profile,
        ports: wireguard::WG_PORTS.to_vec(),
        ip,
        excluded: excluded.clone(),
    };

    let best = wg_prober::hunt_best_wg_endpoint(&probe, mode).await?;
    Ok(SocketAddr::new(best.ip, best.port))
}

fn wg_reconnect_delay() -> std::time::Duration {
    let secs = std::env::var("AETHER_WG_RECONNECT_SECS")
        .ok()
        .and_then(|v| v.parse::<u64>().ok())
        .filter(|&v| v > 0)
        .unwrap_or(2);
    std::time::Duration::from_secs(secs)
}

fn wg_endpoint_cooldown() -> std::time::Duration {
    let secs = std::env::var("AETHER_WG_ENDPOINT_COOLDOWN_SECS")
        .ok()
        .and_then(|v| v.parse::<u64>().ok())
        .filter(|&v| v > 0)
        .unwrap_or(300);
    std::time::Duration::from_secs(secs)
}

/// How long the whole hunt may take before it gives up and says so.
///
/// Five obfuscation profiles, each scanning the pool for its own full budget,
/// added up to the better part of seven minutes before one attempt reported
/// failure -- and the service then retried the whole thing. From outside that is
/// indistinguishable from a hang, and the user has no way to tell whether
/// waiting longer would help. Bounded, so the answer arrives while they are
/// still looking at it.
fn wg_hunt_budget() -> std::time::Duration {
    std::env::var("AETHER_WG_HUNT_BUDGET_SECS")
        .ok()
        .and_then(|v| v.parse::<u64>().ok())
        .map(std::time::Duration::from_secs)
        .unwrap_or_else(|| std::time::Duration::from_secs(210))
}

/// Tries Cloudflare's documented WireGuard endpoints under every obfuscation
/// profile, before any sampling.
///
/// The sweep below finishes an entire pool scan under one profile before trying
/// the next, so an endpoint that answers only under a later profile is reached
/// after every profile ahead of it has spent its full budget. The anchors are a
/// few dozen probes; running them across all profiles first turns the ordinary
/// case from minutes into seconds, and costs nothing when they are blocked.
async fn hunt_wg_anchors(
    identity: &account::Identity,
    candidates: &[(String, aethernoize::AetherNoizeConfig)],
    ip: prober::IpScan,
) -> Option<(SocketAddr, aethernoize::AetherNoizeConfig, String)> {
    use futures::StreamExt;

    let private_key = identity.private_key_bytes().ok()?;
    let peer_public = identity.peer_public_key_bytes().ok()?;
    let local_ipv4: std::net::Ipv4Addr = identity.ipv4.parse().ok()?;

    let mut anchors: Vec<IpAddr> = Vec::new();
    if ip.want_v4() {
        anchors.extend(
            wireguard::wg_seeds_v4()
                .iter()
                .filter_map(|s| s.parse::<Ipv4Addr>().ok())
                .map(IpAddr::V4),
        );
    }
    if ip.want_v6() {
        anchors.extend(
            wireguard::WG_SEEDS_V6
                .iter()
                .filter_map(|s| s.parse::<std::net::Ipv6Addr>().ok())
                .map(IpAddr::V6),
        );
    }
    if anchors.is_empty() {
        return None;
    }

    let ports: Vec<u16> = wireguard::WG_PORTS.iter().copied().take(4).collect();

    // Every combination, flattened, so they can all be in flight at once. Run
    // one at a time this is nine anchors by four ports by five profiles at three
    // seconds each -- nine minutes of waiting on a network where none of them
    // answer, which is worse than the sweep it was meant to shortcut.
    let mut probes: Vec<(SocketAddr, &String, &aethernoize::AetherNoizeConfig)> = Vec::new();
    for (name, profile) in candidates {
        for port in &ports {
            for anchor in &anchors {
                probes.push((SocketAddr::new(*anchor, *port), name, profile));
            }
        }
    }

    let probes = interleave_groups(&probes, ports.len() * anchors.len());

    let concurrency = sysprofile::cap_concurrency(24);
    let deadline = Instant::now() + ANCHOR_BUDGET;
    log::info!(
        "[*] trying {} endpoints across {} profiles ({} probes), {} at a time",
        anchors.len() * ports.len(),
        candidates.len(),
        probes.len(),
        concurrency,
    );

    wg_prober::begin_probe_pass();
    let stream =
        futures::stream::iter(probes.into_iter().map(|(peer, name, profile)| async move {
            let reached = match wireguard::verify_endpoint(
                peer,
                private_key,
                peer_public,
                identity.client_id,
                local_ipv4,
                profile,
                ANCHOR_PROBE_TIMEOUT,
                None,
            )
            .await
            {
                Ok(_) => true,
                Err(error) => {
                    wg_prober::record_probe_failure(&error);
                    false
                }
            };
            (reached, peer, name, profile)
        }))
        .buffer_unordered(concurrency);
    tokio::pin!(stream);

    loop {
        let remaining = deadline.saturating_duration_since(Instant::now());
        if remaining.is_zero() {
            log::info!(
                "[-] documented endpoints did not answer in time ({}); sampling the pool",
                wg_prober::probe_pass_summary(),
            );
            return None;
        }
        match tokio::time::timeout(remaining, stream.next()).await {
            Err(_) | Ok(None) => break,
            Ok(Some((false, ..))) => continue,
            Ok(Some((true, peer, name, profile))) => {
                log::info!("[+] documented endpoint {peer} answered under profile '{name}'");
                return Some((peer, profile.clone(), name.clone()));
            }
        }
    }

    log::info!(
        "[-] no documented endpoint answered ({}); sampling the address pool",
        wg_prober::probe_pass_summary(),
    );
    None
}

/// Reorders equal-sized groups so they are sampled in parallel, not in turn.
///
/// The anchor pass is deliberately given less time than it needs -- it is a
/// shortcut, and every second it spends is one the real scan does not get. So
/// the deadline always cuts something. Laid out group by group, what it cuts is
/// not a slice off each profile: it is the whole of the last one, every time.
/// A network that answers only under the fifth obfuscation would never see it
/// tried, and would be indistinguishable from a network that answers under
/// none. Interleaved, the same deadline takes an even bite out of all of them.
fn interleave_groups<T: Copy>(items: &[T], stride: usize) -> Vec<T> {
    if stride == 0 {
        return items.to_vec();
    }
    let groups = items.len().div_ceil(stride);
    let mut out = Vec::with_capacity(items.len());
    for offset in 0..stride {
        for group in 0..groups {
            if let Some(item) = items.get(group * stride + offset) {
                out.push(*item);
            }
        }
    }
    out
}

/// How long the documented endpoints get before the pool sweep takes over.
///
/// They are a shortcut, not the search. Where they answer it is in a second or
/// two; where they are blocked, every extra second here is one the real scan
/// does not get.
const ANCHOR_BUDGET: std::time::Duration = std::time::Duration::from_secs(20);
const ANCHOR_PROBE_TIMEOUT: std::time::Duration = std::time::Duration::from_secs(3);

async fn hunt_wg_peer(
    identity: &account::Identity,
    candidates: &[(String, aethernoize::AetherNoizeConfig)],
    mode_str: &str,
    ip: prober::IpScan,
    excluded: &HashSet<SocketAddr>,
) -> Result<(SocketAddr, aethernoize::AetherNoizeConfig, String)> {
    if let Some(found) = hunt_wg_anchors(identity, candidates, ip).await {
        return Ok(found);
    }

    // A hard ceiling on the sweep, not a check between profiles: checking
    // between them lets the last one start just under the limit and then run its
    // own full budget past it, which is how a bounded search still takes minutes
    // longer than the bound.
    match tokio::time::timeout(
        wg_hunt_budget(),
        hunt_wg_peer_sweep(identity, candidates, mode_str, ip, excluded),
    )
    .await
    {
        Ok(found) => found,
        Err(_) => {
            log::warn!("[-] the WireGuard search reached its time limit");
            Err(no_wireguard_endpoint())
        }
    }
}

async fn hunt_wg_peer_sweep(
    identity: &account::Identity,
    candidates: &[(String, aethernoize::AetherNoizeConfig)],
    mode_str: &str,
    ip: prober::IpScan,
    excluded: &HashSet<SocketAddr>,
) -> Result<(SocketAddr, aethernoize::AetherNoizeConfig, String)> {
    let multi = candidates.len() > 1;
    for (name, profile) in candidates {
        log::info!(
            "[*] hunting for a working WireGuard endpoint (handshake + data-plane verification, aethernoize='{name}')"
        );
        match hunt_wg_peer_with_profile(identity, mode_str, ip, profile.clone(), excluded).await {
            Ok(peer) => {
                log::info!(
                    "[+] selected WireGuard endpoint {peer} using aethernoize profile '{name}'"
                );
                return Ok((peer, profile.clone(), name.clone()));
            }
            Err(e) => {
                if multi {
                    log::warn!("[-] profile '{name}' found no data-plane endpoint: {e}; trying next profile");
                } else {
                    log::warn!("[-] profile '{name}' found no data-plane endpoint: {e}");
                }
            }
        }
    }
    Err(no_wireguard_endpoint())
}

/// Named rather than generic. WireGuard is UDP, and a network that blocks UDP
/// outright cannot be scanned around -- telling the user to keep waiting would
/// be advice that never comes good.
fn no_wireguard_endpoint() -> AetherError {
    AetherError::Other(
        "no WireGuard endpoint answered on this network. WireGuard needs UDP, which some \
         networks block entirely -- MASQUE H2 runs over TCP and works where it does."
            .into(),
    )
}

async fn run_wireguard(
    identity: account::Identity,
    listen: SocketAddr,
    lastconn_path: String,
) -> Result<()> {
    let candidates = wg_profile_candidates();

    let forced = std::env::var("AETHER_WG_PEER")
        .ok()
        .or_else(|| std::env::var("AETHER_PEER").ok());

    let private_key = identity.private_key_bytes()?;
    let peer_public = identity.peer_public_key_bytes()?;
    let ipv4: std::net::Ipv4Addr = identity
        .ipv4
        .parse()
        .map_err(|_| AetherError::Other("invalid ipv4".into()))?;

    let mut quick: Option<(SocketAddr, aethernoize::AetherNoizeConfig, String)> = None;

    if forced.is_none() {
        if let Some(assigned) = std::env::var("AETHER_TEAM_ENDPOINT")
            .ok()
            .and_then(|value| value.parse::<SocketAddr>().ok())
        {
            log::info!("[*] verifying the endpoint the organization assigned: {assigned}");
            for (name, profile) in &candidates {
                match wireguard::verify_endpoint(
                    assigned,
                    private_key,
                    peer_public,
                    identity.client_id,
                    ipv4,
                    profile,
                    std::time::Duration::from_secs(8),
                    None,
                )
                .await
                {
                    Ok(rtt) => {
                        log::info!(
                            "[+] the assigned endpoint {assigned} works with profile '{name}' (rtt {rtt:?}); skipping the scan"
                        );
                        quick = Some((assigned, profile.clone(), name.clone()));
                        break;
                    }
                    Err(e) => {
                        log::debug!(
                            "[-] assigned endpoint {assigned} failed profile '{name}': {e}"
                        );
                    }
                }
            }
            if quick.is_none() {
                log::warn!(
                    "[-] the assigned endpoint {assigned} did not pass validation; falling back to scanning"
                );
            }
        }
    }

    if forced.is_none() && quick.is_none() {
        if let Some(cached) = lastconn::load(&lastconn_path) {
            if let Ok(peer) = cached.peer.parse::<SocketAddr>() {
                if want_quick_reconnect(&cached).await {
                    let profile = aethernoize::from_profile(&cached.profile);
                    log::info!("[*] verifying cached WireGuard endpoint {peer} before reuse");
                    match wireguard::verify_endpoint(
                        peer,
                        private_key,
                        peer_public,
                        identity.client_id,
                        ipv4,
                        &profile,
                        std::time::Duration::from_secs(6),
                        None,
                    )
                    .await
                    {
                        Ok(rtt) => {
                            log::info!(
                                "[+] cached endpoint {peer} still works (rtt {:?}); skipping scan",
                                rtt
                            );
                            quick = Some((peer, profile, cached.profile.clone()));
                        }
                        Err(e) => {
                            log::warn!(
                                "[-] cached endpoint {peer} no longer works ({e}); scanning fresh"
                            );
                        }
                    }
                }
            }
        }
    }

    let (mode_str, ip) = if forced.is_some() || quick.is_some() {
        (String::new(), prober::IpScan::V4)
    } else {
        let mode_str = select_scan_mode_str().await;
        let ip = select_ip_version().await;
        (mode_str, ip)
    };

    let mut last_good: Option<(SocketAddr, aethernoize::AetherNoizeConfig, String)> = None;
    let mut consecutive_fails_on_peer: u32 = 0;
    let mut endpoint_cooldowns: HashMap<SocketAddr, Instant> = HashMap::new();
    const MAX_CONSECUTIVE_FAILS: u32 = 2;

    loop {
        let now = Instant::now();
        endpoint_cooldowns.retain(|_, until| *until > now);
        if consecutive_fails_on_peer >= MAX_CONSECUTIVE_FAILS {
            if let Some((peer, _, _)) = last_good.take() {
                let cooldown = wg_endpoint_cooldown();
                endpoint_cooldowns.insert(peer, now + cooldown);
                log::warn!(
                    "[-] endpoint {peer} failed {consecutive_fails_on_peer} times in a row; excluding it for {:?}",
                    cooldown
                );
            }
            consecutive_fails_on_peer = 0;
        }

        let (peer, profile, profile_name) = if let Some(q) = quick.take() {
            q
        } else {
            let retried = match &last_good {
                Some((p, profile, _)) => {
                    log::info!(
                        "[*] retrying last known-good WireGuard endpoint {p} before rescanning"
                    );
                    match wireguard::verify_endpoint(
                        *p,
                        private_key,
                        peer_public,
                        identity.client_id,
                        ipv4,
                        profile,
                        std::time::Duration::from_secs(6),
                        None,
                    )
                    .await
                    {
                        Ok(_) => Some(last_good.clone().unwrap()),
                        Err(e) => {
                            log::warn!("[-] last known-good endpoint {p} no longer responds ({e}); rescanning");
                            None
                        }
                    }
                }
                None => None,
            };

            match retried {
                Some(v) => v,
                None => {
                    if let Some(ref p) = forced {
                        let peer: SocketAddr = p
                            .parse()
                            .map_err(|_| AetherError::Other(format!("bad peer address {p}")))?;
                        log::info!("[+] using forced peer {peer} (probe skipped)");

                        let mut chosen = None;
                        for (name, profile) in &candidates {
                            log::info!(
                                "[*] testing forced peer {peer} with aethernoize profile '{name}'"
                            );
                            match wireguard::verify_endpoint(
                                peer,
                                private_key,
                                peer_public,
                                identity.client_id,
                                ipv4,
                                profile,
                                std::time::Duration::from_secs(10),
                                None,
                            )
                            .await
                            {
                                Ok(rtt) => {
                                    log::info!(
                                        "[+] profile '{}' passed handshake + data-plane (rtt {:?})",
                                        name,
                                        rtt
                                    );
                                    chosen = Some((peer, profile.clone(), name.clone()));
                                    break;
                                }
                                Err(e) => {
                                    log::warn!("[-] profile '{name}' failed on forced peer: {e}");
                                }
                            }
                        }
                        match chosen {
                            Some(v) => v,
                            None => return Err(AetherError::NoCleanEndpoint),
                        }
                    } else {
                        let excluded: HashSet<SocketAddr> =
                            endpoint_cooldowns.keys().copied().collect();
                        match hunt_wg_peer(&identity, &candidates, &mode_str, ip, &excluded).await {
                            Ok(v) => v,
                            Err(e) => {
                                log::warn!("[-] no usable WireGuard endpoint found: {e}; rescanning shortly");
                                tokio::time::sleep(wg_reconnect_delay()).await;
                                continue;
                            }
                        }
                    }
                }
            }
        };

        log::info!("[+] using cloudflare edge {peer}");

        if forced.is_none() {
            lastconn::save(&lastconn_path, &peer.to_string(), &profile_name);
        }

        let is_same_peer_as_before = last_good.as_ref().map(|(p, _, _)| *p) == Some(peer);
        if !is_same_peer_as_before {
            consecutive_fails_on_peer = 0;
        }
        last_good = Some((peer, profile.clone(), profile_name));

        match run_wireguard_tunnel(identity.clone(), peer, profile, listen).await {
            Ok(()) => {
                log::warn!("[-] WireGuard tunnel closed; reconnecting");
                consecutive_fails_on_peer += 1;
            }
            Err(e) => {
                log::warn!("[-] WireGuard tunnel ended: {e}; reconnecting");
                consecutive_fails_on_peer += 1;
            }
        }

        tokio::time::sleep(wg_reconnect_delay()).await;
    }
}

fn wg_tunnel_validate_timeout() -> std::time::Duration {
    let secs = std::env::var("AETHER_WG_VALIDATE_SECS")
        .ok()
        .and_then(|v| v.parse::<u64>().ok())
        .filter(|&v| v > 0)
        .unwrap_or(10);
    std::time::Duration::from_secs(secs)
}

async fn run_wireguard_tunnel(
    identity: account::Identity,
    peer: SocketAddr,
    aethernoize: aethernoize::AetherNoizeConfig,
    listen: SocketAddr,
) -> Result<()> {
    let private_key = identity.private_key_bytes()?;
    let peer_public = identity.peer_public_key_bytes()?;
    let ipv4: std::net::Ipv4Addr = identity
        .ipv4
        .parse()
        .map_err(|_| AetherError::Other("invalid ipv4".into()))?;

    log::info!("[*] validating WireGuard tunnel with {peer} (handshake + data-plane) before exposing socks5...");
    let (_, session) = wireguard::verify_endpoint_keep_session(
        peer,
        private_key,
        peer_public,
        identity.client_id,
        ipv4,
        &aethernoize,
        wg_tunnel_validate_timeout(),
        Some(wg_keepalive_secs()),
    )
    .await
    .map_err(|e| AetherError::Other(format!("tunnel failed validation: {e}")))?;
    log::info!("[+] wireguard tunnel validated (end-to-end data confirmed); exposing socks5");

    let (outbound_tx, outbound_rx) = tokio::sync::mpsc::channel(sysprofile::channel_capacity());
    let (inbound_tx, inbound_rx) = tokio::sync::mpsc::channel(sysprofile::channel_capacity());

    let tunnel = wireguard::WgTunnel::from_established(
        session,
        std::sync::Arc::new(aethernoize),
        inbound_tx,
        ipv4,
    );

    let stack = netstack::spawn(
        &identity.ipv4,
        &identity.ipv6,
        WIREGUARD_MTU,
        inbound_rx,
        outbound_tx,
    )?;

    let mut tasks = TaskGuard::new();

    let socks_stack = stack.clone();
    let socks_task = tokio::spawn(async move {
        log::info!("[+] socks5 server listening on {listen}");
        socks::serve(listen, socks_stack).await
    });
    tasks.push(socks_task.abort_handle());

    let http_task = spawn_http_proxy(&stack);
    if let Some(task) = &http_task {
        tasks.push(task.abort_handle());
    }

    let tunnel_result = tunnel.run(outbound_rx).await;

    if let Some(task) = &http_task {
        task.abort();
    }

    socks_task.abort();
    let _ = socks_task.await;

    drop(stack);

    match tunnel_result {
        Ok(()) => Ok(()),
        Err(e) => Err(AetherError::Other(format!("wireguard tunnel exited: {e}"))),
    }
}

type TunnelExit = tokio::task::JoinHandle<Result<()>>;

/// The packet ends of a running WireGuard tunnel.
///
/// A tunnel that fronts a userspace TCP stack and one that is handed straight to
/// an Android interface differ only in what consumes these, so they are taken
/// out here rather than each caller rebuilding the tunnel.
fn http_proxy_listen() -> Option<SocketAddr> {
    let raw = std::env::var("AETHER_HTTP_PROXY").ok()?;
    let trimmed = raw.trim();
    if trimmed.is_empty() {
        return None;
    }
    match trimmed.parse::<SocketAddr>() {
        Ok(addr) => Some(addr),
        Err(_) => {
            log::warn!("[-] ignoring an unparsable http proxy address: {trimmed}");
            None
        }
    }
}

fn spawn_http_proxy(stack: &netstack::StackHandle) -> Option<TunnelExit> {
    let listen = http_proxy_listen()?;
    let stack = stack.clone();
    Some(tokio::spawn(async move {
        log::info!("[+] http proxy listening on {listen}");
        socks::serve_http(listen, stack).await
    }))
}

struct WgChannels {
    /// Packets arriving from the far end.
    inbound_rx: tokio::sync::mpsc::Receiver<Vec<u8>>,
    /// Packets to send to it.
    outbound_tx: tokio::sync::mpsc::Sender<Vec<u8>>,
    exit: TunnelExit,
}

async fn establish_wg(
    identity: &account::Identity,
    peer: SocketAddr,
    mtu: usize,
    obfuscate: bool,
    keepalive: u16,
    label: &'static str,
) -> Result<(netstack::StackHandle, TunnelExit)> {
    let channels = establish_wg_channels(identity, peer, obfuscate, keepalive, label).await?;
    let stack = netstack::spawn(
        &identity.ipv4,
        &identity.ipv6,
        mtu,
        channels.inbound_rx,
        channels.outbound_tx,
    )?;
    Ok((stack, channels.exit))
}

async fn establish_wg_channels(
    identity: &account::Identity,
    peer: SocketAddr,
    obfuscate: bool,
    keepalive: u16,
    label: &'static str,
) -> Result<WgChannels> {
    let private_key = identity.private_key_bytes()?;
    let peer_public = identity.peer_public_key_bytes()?;

    let ipv4: std::net::Ipv4Addr = identity
        .ipv4
        .parse()
        .map_err(|_| AetherError::Other("invalid ipv4".into()))?;

    let profile = if obfuscate {
        aethernoize_config()
    } else {
        aethernoize::from_profile("off")
    };

    log::info!("[*] [{label}] validating WireGuard tunnel with {peer} (handshake + data-plane)...");
    let (_, session) = wireguard::verify_endpoint_keep_session(
        peer,
        private_key,
        peer_public,
        identity.client_id,
        ipv4,
        &profile,
        wg_tunnel_validate_timeout(),
        Some(keepalive),
    )
    .await
    .map_err(|e| AetherError::Other(format!("[{label}] tunnel failed validation: {e}")))?;
    log::info!("[+] [{label}] wireguard tunnel validated (end-to-end data confirmed)");

    let (outbound_tx, outbound_rx) = tokio::sync::mpsc::channel(sysprofile::channel_capacity());
    let (inbound_tx, inbound_rx) = tokio::sync::mpsc::channel(sysprofile::channel_capacity());

    let tunnel = wireguard::WgTunnel::from_established(
        session,
        std::sync::Arc::new(profile),
        inbound_tx,
        ipv4,
    );

    let exit = tokio::spawn(async move {
        match tunnel.run(outbound_rx).await {
            Ok(()) => {
                log::warn!("[-] [{label}] wireguard tunnel closed");
                Ok(())
            }
            Err(e) => {
                log::warn!("[-] [{label}] wireguard tunnel exited: {e}");
                Err(AetherError::Other(format!("[{label}] {e}")))
            }
        }
    });

    Ok(WgChannels {
        inbound_rx,
        outbound_tx,
        exit,
    })
}

struct TaskGuard(Vec<tokio::task::AbortHandle>);

impl TaskGuard {
    fn new() -> Self {
        Self(Vec::new())
    }

    fn push(&mut self, handle: tokio::task::AbortHandle) {
        self.0.push(handle);
    }
}

impl Drop for TaskGuard {
    fn drop(&mut self) {
        for handle in self.0.drain(..) {
            handle.abort();
        }
    }
}

async fn spawn_udp_forwarder(
    outer: &netstack::StackHandle,
    remote: SocketAddr,
) -> Result<(SocketAddr, TaskGuard)> {
    let sock = std::sync::Arc::new(tokio::net::UdpSocket::bind("127.0.0.1:0").await?);
    let local = sock.local_addr()?;

    let udp = outer.open_udp().await?;
    let (udp_tx, mut udp_rx) = udp.into_split();

    let inner_peer: std::sync::Arc<tokio::sync::Mutex<Option<SocketAddr>>> =
        std::sync::Arc::new(tokio::sync::Mutex::new(None));

    let up_sock = sock.clone();
    let up_peer = inner_peer.clone();
    let up_task = tokio::spawn(async move {
        let mut buf = vec![0u8; 65536];
        loop {
            match up_sock.recv_from(&mut buf).await {
                Ok((n, from)) => {
                    *up_peer.lock().await = Some(from);
                    if udp_tx.send_to(remote, buf[..n].to_vec()).await.is_err() {
                        break;
                    }
                }
                Err(_) => break,
            }
        }
    });

    let down_sock = sock.clone();
    let down_peer = inner_peer.clone();
    let down_task = tokio::spawn(async move {
        while let Some((_src, data)) = udp_rx.recv().await {
            let dst = *down_peer.lock().await;
            if let Some(dst) = dst {
                let _ = down_sock.send_to(&data, dst).await;
            }
        }
    });

    let guard = TaskGuard(vec![up_task.abort_handle(), down_task.abort_handle()]);

    Ok((local, guard))
}

async fn run_warp_in_warp(
    primary: account::Identity,
    secondary: account::Identity,
    peer: SocketAddr,
    inner_peer: SocketAddr,
    listen: SocketAddr,
) -> Result<()> {
    if inner_peer.ip() == peer.ip() {
        return Err(AetherError::Other(format!(
            "warp-in-warp needs two separate edges but both hops landed on {}",
            peer.ip()
        )));
    }

    log::info!("[*] establishing outer WARP tunnel to {peer}...");
    let (outer_stack, mut outer_exit) =
        establish_wg(&primary, peer, WIREGUARD_MTU, true, 5, "outer").await?;

    let (forwarder, _forwarder_guard) = spawn_udp_forwarder(&outer_stack, inner_peer).await?;
    log::info!("[+] inner endpoint {inner_peer} tunneled through outer warp via {forwarder}");

    log::info!("[*] establishing inner WARP tunnel (warp-in-warp)...");
    let (inner_stack, mut inner_exit) =
        establish_wg(&secondary, forwarder, INNER_MTU, false, 20, "inner").await?;

    log::info!("[+] socks5 server listening on {listen}");
    let http_task = spawn_http_proxy(&inner_stack);
    let mut socks_task = tokio::spawn(async move { socks::serve(listen, inner_stack).await });

    #[derive(PartialEq)]
    enum Winner {
        Outer,
        Inner,
        Socks,
    }

    let (outcome, winner) = tokio::select! {
        result = &mut outer_exit => (join_outcome("outer wireguard tunnel", result), Winner::Outer),
        result = &mut inner_exit => (join_outcome("inner wireguard tunnel", result), Winner::Inner),
        result = &mut socks_task => (join_outcome("socks5 server", result), Winner::Socks),
    };

    if let Some(task) = &http_task {
        task.abort();
    }

    // Whichever handle already resolved inside the select! above must not be
    // polled again: tokio panics with "JoinHandle polled after completion"
    // if you .await a JoinHandle that has already yielded Ready.
    if winner != Winner::Outer {
        outer_exit.abort();
        let _ = outer_exit.await;
    }
    if winner != Winner::Inner {
        inner_exit.abort();
        let _ = inner_exit.await;
    }
    if winner != Winner::Socks {
        socks_task.abort();
        let _ = socks_task.await;
    }

    drop(outer_stack);

    outcome
}

fn join_outcome(
    what: &str,
    result: std::result::Result<Result<()>, tokio::task::JoinError>,
) -> Result<()> {
    match result {
        Ok(Ok(())) => Err(AetherError::Other(format!("{what} stopped"))),
        Ok(Err(e)) => Err(e),
        Err(e) if e.is_cancelled() => Err(AetherError::Other(format!("{what} was cancelled"))),
        Err(e) => Err(AetherError::Other(format!("{what} panicked: {e}"))),
    }
}

async fn prompt_line(prompt: &str) -> Option<String> {
    use std::io::IsTerminal;
    use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};

    if !std::io::stdin().is_terminal() {
        return None;
    }

    let mut stdout = tokio::io::stdout();
    let _ = stdout.write_all(prompt.as_bytes()).await;
    let _ = stdout.flush().await;

    let mut line = String::new();
    let mut reader = BufReader::new(tokio::io::stdin());
    match reader.read_line(&mut line).await {
        Ok(0) | Err(_) => None,
        Ok(_) => Some(line.trim().to_string()),
    }
}

const SCAN_MODE_PROMPT: &str = "\nScan mode:\n  [1] turbo     (fast, first hit)\n  [2] balanced  (default)\n  [3] thorough  (deep, best ping)\n  [4] stealth   (quiet, patient)\n  [5] ironclad  (real tunnel + real HTTP check per candidate, guaranteed working)\nChoose [1-5] (default 2): ";

async fn select_scan_mode() -> prober::ScanMode {
    if let Ok(v) = std::env::var("AETHER_SCAN") {
        return prober::ScanMode::parse(&v);
    }

    let answer = prompt_line(SCAN_MODE_PROMPT).await;

    match answer.as_deref() {
        Some("1") => prober::ScanMode::Turbo,
        Some("3") => prober::ScanMode::Thorough,
        Some("4") => prober::ScanMode::Stealth,
        Some("5") => prober::ScanMode::Ironclad,
        _ => prober::ScanMode::Balanced,
    }
}

async fn select_scan_mode_str() -> String {
    if let Ok(v) = std::env::var("AETHER_SCAN") {
        return v;
    }

    let answer = prompt_line(SCAN_MODE_PROMPT).await;

    match answer.as_deref() {
        Some("1") => "turbo".to_string(),
        Some("3") => "thorough".to_string(),
        Some("4") => "stealth".to_string(),
        Some("5") => "ironclad".to_string(),
        _ => "balanced".to_string(),
    }
}

async fn select_protocol(base: &str) -> Protocol {
    if let Ok(v) = std::env::var("AETHER_PROTOCOL") {
        return Protocol::parse(&v);
    }

    loop {
        let zero_trust = match team_scope() {
            Some(team) => format!("  [4] Zero Trust: signed in to {team}, pick another team\n"),
            None => "  [4] Zero Trust: sign in to an organization (WARP for teams)\n".to_string(),
        };

        let answer = prompt_line(&format!(
            "\nProtocol:\n  [1] MASQUE (modern, QUIC/H3, default)\n  \
             [2] WireGuard (classic, faster)\n  [3] WARP-in-WARP / gool\n{zero_trust}\
             Choose [1-4] (default 1): "
        ))
        .await;

        match answer.as_deref() {
            Some("2") => return Protocol::WireGuard,
            Some("3") => return Protocol::WarpInWarp,
            Some("4") => {
                enrol_zero_trust(base).await;
                continue;
            }
            _ => return Protocol::Masque,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Protocol {
    Masque,
    WireGuard,
    WarpInWarp,
}

impl Protocol {
    fn parse(s: &str) -> Protocol {
        match s.trim().to_lowercase().as_str() {
            "wg" | "wireguard" => Protocol::WireGuard,
            "gool" | "wiw" | "warp-in-warp" | "warpinwarp" => Protocol::WarpInWarp,
            _ => Protocol::Masque,
        }
    }

    fn label(&self) -> &'static str {
        match self {
            Protocol::Masque => "MASQUE",
            Protocol::WireGuard => "WireGuard",
            Protocol::WarpInWarp => "WARP-in-WARP (gool)",
        }
    }
}

async fn select_masque_transport() {
    if std::env::var("AETHER_MASQUE_HTTP2").is_ok() || std::env::var("AETHER_PEER").is_ok() {
        return;
    }

    let answer = prompt_line(
        "\nMASQUE transport:\n  [1] HTTP/3 (QUIC)  (default; fastest handshake, best on healthy UDP networks)\n  [2] HTTP/2 (TCP)   (looks like ordinary HTTPS; use if UDP/QUIC is blocked or throttled)\nChoose [1-2] (default 1): ",
    )
    .await;

    if matches!(answer.as_deref(), Some("2")) {
        std::env::set_var("AETHER_MASQUE_HTTP2", "1");
    }
}

async fn select_ip_version() -> prober::IpScan {
    if let Ok(v) = std::env::var("AETHER_IP") {
        return prober::IpScan::parse(&v);
    }

    let answer = prompt_line(
        "\nIP version to scan:\n  [1] IPv4 (default)\n  [2] IPv6\n  [3] Both\nChoose [1-3] (default 1): ",
    )
    .await;

    match answer.as_deref() {
        Some("2") => prober::IpScan::V6,
        Some("3") => prober::IpScan::Both,
        _ => prober::IpScan::V4,
    }
}

#[cfg(test)]
mod identity_tests {
    use super::*;

    /// Clears the environment these paths read, so a developer's own settings
    /// cannot decide whether the suite passes.
    fn isolated() {
        std::env::remove_var("AETHER_MASQUE_CONFIG");
        std::env::remove_var("AETHER_WG_CONFIG");
        std::env::remove_var("CF_TEAM");
        std::env::remove_var("AETHER_TEAM");
    }

    fn sample_identity(device: &str) -> account::Identity {
        account::Identity {
            // Upstream now records whether Cloudflare has refused this identity.
            refused: false,
            device_id: device.into(),
            access_token: "token".into(),
            cert_pem: b"-----BEGIN CERTIFICATE-----".to_vec(),
            key_pem: b"-----BEGIN PRIVATE KEY-----".to_vec(),
            cert_issued_at: 1_700_000_000,
            ipv4: "172.16.0.2".into(),
            ipv6: "2606:4700:110::1".into(),
            wg_private_key: [3u8; 32],
            wg_peer_public_key: [5u8; 32],
            client_id: [9, 8, 7],
            organization: String::new(),
            gateway_proxy: String::new(),
            assigned_endpoint: String::new(),
        }
    }

    fn scratch(name: &str) -> std::path::PathBuf {
        let dir = std::env::temp_dir().join(format!("aether-{name}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        dir
    }

    #[test]
    fn an_exported_identity_comes_back_as_the_same_device() {
        isolated();
        let dir = scratch("export");
        let base = dir.join("aether.toml");
        let base = base.to_str().unwrap();
        config::save(base, &sample_identity("device-one")).unwrap();

        let payload = export_identity(base).unwrap();

        // The point of the whole feature: a reinstall starts here instead of at
        // a registration Cloudflare may refuse.
        let restored = scratch("import").join("aether.toml");
        let restored = restored.to_str().unwrap();
        import_identity(restored, &payload).unwrap();

        let identity = config::load(restored).unwrap().unwrap();
        assert_eq!("device-one", identity.device_id);
        assert_eq!([3u8; 32], identity.wg_private_key);
        assert_eq!(b"-----BEGIN PRIVATE KEY-----".to_vec(), identity.key_pem);

        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn the_nested_tunnels_second_account_travels_too() {
        isolated();
        let dir = scratch("export-two");
        let base = dir.join("aether.toml");
        let base = base.to_str().unwrap();
        config::save(base, &sample_identity("outer")).unwrap();
        config::save(
            &derive_sibling_path(base, "secondary"),
            &sample_identity("inner"),
        )
        .unwrap();

        let payload = export_identity(base).unwrap();

        let target = scratch("import-two").join("aether.toml");
        let target = target.to_str().unwrap();
        import_identity(target, &payload).unwrap();

        // Leaving it behind would have the user buy it again on the first
        // WARP-in-WARP connect, which is the cost this exists to avoid.
        let inner = config::load(&derive_sibling_path(target, "secondary"))
            .unwrap()
            .expect("the second identity did not travel");
        assert_eq!("inner", inner.device_id);

        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn an_install_with_no_identity_says_so_rather_than_exporting_nothing() {
        isolated();
        let dir = scratch("export-empty");
        let base = dir.join("aether.toml");
        let error = export_identity(base.to_str().unwrap()).unwrap_err();
        assert!(error.to_string().contains("no identity"));
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn a_file_that_is_not_an_identity_is_refused_before_anything_is_written() {
        isolated();
        let dir = scratch("import-junk");
        let base = dir.join("aether.toml");
        let base = base.to_str().unwrap();
        let existing = sample_identity("still-here");
        config::save(base, &existing).unwrap();

        for junk in ["", "hello", "version = 1", "{\"json\": true}"] {
            assert!(import_identity(base, junk).is_err(), "accepted {junk:?}");
        }

        // Half an import is worse than none: it would leave the device holding
        // an identity Cloudflare does not know, with the working one gone.
        assert_eq!(
            "still-here",
            config::load(base).unwrap().unwrap().device_id,
            "a refused import damaged the identity in use",
        );

        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn a_file_from_a_future_format_is_refused_by_name() {
        isolated();
        let dir = scratch("import-future");
        let base = dir.join("aether.toml");
        let base = base.to_str().unwrap();
        config::save(base, &sample_identity("current")).unwrap();

        let payload = export_identity(base)
            .unwrap()
            .replace("version = 1", "version = 99");
        let error = import_identity(base, &payload).unwrap_err().to_string();

        // Guessing at an unknown shape and writing the result over a working
        // identity is the one outcome worse than refusing.
        assert!(error.contains("different version"), "{error}");
        assert_eq!("current", config::load(base).unwrap().unwrap().device_id);

        let _ = std::fs::remove_dir_all(&dir);
    }

    /// The arithmetic that made WireGuard look broken.
    ///
    /// Everything here is a bound the search has to satisfy for a user to get an
    /// answer while they are still looking at the screen. Each was violated at
    /// some point, and each failure looked like a hang rather than a refusal.
    #[test]
    fn the_documented_endpoint_pass_cannot_outlast_the_search_it_shortcuts() {
        let anchors = wireguard::wg_seeds_v4().len() + wireguard::WG_SEEDS_V6.len();
        let probes = anchors * 4 * 5; // four ports, five obfuscation profiles

        // Run one at a time this was nine minutes of waiting on a network where
        // none of them answer -- worse than the sweep it was meant to shortcut.
        let sequential = ANCHOR_PROBE_TIMEOUT * probes as u32;
        assert!(
            sequential > ANCHOR_BUDGET * 4,
            "the sequential cost is no longer worth bounding; check this still runs concurrently",
        );
        assert!(
            ANCHOR_BUDGET <= std::time::Duration::from_secs(30),
            "the shortcut may not become the search",
        );
    }

    /// What the deadline takes when it cannot take everything.
    #[test]
    fn a_truncated_anchor_pass_still_tries_every_obfuscation_profile() {
        // Five profiles, thirty-six endpoints each, in the order the pass
        // builds them: all of profile 0, then all of profile 1, and so on.
        let stride = 36;
        let profiles = 5;
        let probes: Vec<usize> = (0..stride * profiles).collect();
        let profile_of = |probe: usize| probe / stride;

        let ordered = interleave_groups(&probes, stride);

        assert_eq!(ordered.len(), probes.len(), "no probe may be dropped");
        let mut sorted = ordered.clone();
        sorted.sort_unstable();
        assert_eq!(sorted, probes, "no probe may be duplicated or invented");

        // Roughly what fits in the budget: twenty seconds, twenty-four at a
        // time, three seconds each.
        let reached = 20 / 3 * 24;
        let tried: Vec<usize> = ordered
            .iter()
            .take(reached)
            .map(|p| profile_of(*p))
            .collect();

        for profile in 0..profiles {
            let count = tried.iter().filter(|p| **p == profile).count();
            assert!(
                count > 0,
                "profile {profile} was never tried before the deadline; \
                 a network that only answers under it would look dead",
            );
        }

        // And evenly, not merely non-zero.
        let counts: Vec<usize> = (0..profiles)
            .map(|profile| tried.iter().filter(|p| **p == profile).count())
            .collect();
        let spread = counts.iter().max().unwrap() - counts.iter().min().unwrap();
        assert!(
            spread <= 1,
            "the profiles were sampled unevenly: {counts:?}"
        );
    }

    #[test]
    fn the_whole_search_is_bounded_by_something_a_user_would_wait_for() {
        let total = ANCHOR_BUDGET + wg_hunt_budget();
        assert!(
            total <= std::time::Duration::from_secs(300),
            "a search this long is indistinguishable from a hang: {total:?}",
        );
    }

    #[test]
    fn wireguard_searches_at_least_as_hard_as_masque_does() {
        // WireGuard covers fifty-four ports where MASQUE covers a handful, and
        // was given half the concurrency and two thirds the budget -- about one
        // candidate in forty before giving up. Failing was arithmetic, not
        // evidence that anything was blocked.
        let wg = wg_prober::WgScanMode::parse("balanced").strategy_for_test();
        let masque = prober::ScanMode::parse("balanced").strategy_for_test();
        assert!(
            wg.0 >= masque.0,
            "wireguard concurrency {} is below masque's {}",
            wg.0,
            masque.0,
        );
        assert!(
            wg.1 >= masque.1,
            "wireguard budget {:?} is below masque's {:?}",
            wg.1,
            masque.1,
        );
    }

    #[test]
    fn each_protocol_remembers_its_own_endpoint() {
        isolated();
        let base = "/data/aether.toml";
        // A MASQUE gateway and a WireGuard endpoint are different addresses on
        // different ports. Sharing this file would offer each the other's, and
        // waste a validation on every connect.
        assert_ne!(
            lastconn_path(&masque_config_path(base)),
            lastconn_path(&warp_config_path(base)),
        );
    }

    #[test]
    fn remembered_endpoints_keep_the_names_existing_installs_wrote() {
        isolated();
        let base = "/data/aether.toml";
        // Renaming these would not break anything visibly -- it would just
        // silently discard the cached endpoint and make the next connect scan
        // from scratch, which is the slow path this file exists to avoid.
        assert!(lastconn_path(&masque_config_path(base)).ends_with("aether-masque-lastconn.toml"));
        assert!(lastconn_path(&warp_config_path(base)).ends_with("aether-lastconn.toml"));
    }

    #[test]
    fn masque_adopts_the_identity_from_the_release_that_shared_one_file() {
        isolated();
        let dir = std::env::temp_dir().join(format!("aether-adopt-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let base = dir.join("aether.toml");
        let base = base.to_str().unwrap();

        // What an install of the release that shared one file looks like: a
        // single identity, carrying the MASQUE certificate that was enrolled
        // onto it.
        let identity = account::Identity {
            // Upstream now records whether Cloudflare has refused this identity.
            refused: false,
            device_id: "device-from-the-shared-release".into(),
            access_token: "token".into(),
            cert_pem: b"-----BEGIN CERTIFICATE-----".to_vec(),
            key_pem: b"-----BEGIN PRIVATE KEY-----".to_vec(),
            cert_issued_at: 1,
            ipv4: "172.16.0.2".into(),
            ipv6: "2606:4700:110::1".into(),
            wg_private_key: [7u8; 32],
            wg_peer_public_key: [9u8; 32],
            client_id: [1, 2, 3],
            organization: String::new(),
            gateway_proxy: String::new(),
            assigned_endpoint: String::new(),
        };
        config::save(base, &identity).unwrap();

        let masque = masque_config_path(base);
        assert_ne!(masque, base, "the two protocols must not share a file");
        adopt_legacy_masque_identity(&masque).unwrap();

        // The certificate on it is valid and cost a registration. Only the
        // WireGuard half died, so MASQUE keeps the account rather than the user
        // paying for another against a per-IP rate limit.
        let adopted = config::load(&masque)
            .unwrap()
            .expect("identity was not adopted");
        assert_eq!("device-from-the-shared-release", adopted.device_id);
    }

    #[test]
    fn adoption_never_overwrites_an_identity_already_in_place() {
        isolated();
        let dir = std::env::temp_dir().join(format!("aether-keep-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let base = dir.join("aether.toml");
        let base = base.to_str().unwrap();

        let current = account::Identity {
            // Upstream now records whether Cloudflare has refused this identity.
            refused: false,
            device_id: "current".into(),
            access_token: "token".into(),
            cert_pem: Vec::new(),
            key_pem: Vec::new(),
            cert_issued_at: 0,
            ipv4: "172.16.0.2".into(),
            ipv6: "2606:4700:110::1".into(),
            wg_private_key: [1u8; 32],
            wg_peer_public_key: [2u8; 32],
            client_id: [4, 5, 6],
            organization: String::new(),
            gateway_proxy: String::new(),
            assigned_endpoint: String::new(),
        };
        let stale = account::Identity {
            device_id: "stale".into(),
            ..current.clone()
        };
        let masque = masque_config_path(base);
        config::save(&masque, &current).unwrap();
        config::save(base, &stale).unwrap();

        adopt_legacy_masque_identity(&masque).unwrap();

        // A leftover file from before the migration must never displace the
        // identity in use: that would swap the device mid-life and strand the
        // certificate enrolled against it.
        assert_eq!("current", config::load(&masque).unwrap().unwrap().device_id);
    }

    /// The bug that shipped, in the form the code can check.
    #[test]
    fn the_two_protocol_families_never_share_an_identity_file() {
        isolated();
        // Enrolling MASQUE rewrites the device's key at Cloudflare and revokes
        // its WireGuard half. One file for both means connecting with MASQUE
        // silently destroys WireGuard for that install, and the endpoint search
        // then reports the network as dead.
        for base in ["/data/aether.toml", "/data/aether", "C:\\x\\aether.toml"] {
            assert_ne!(
                masque_config_path(base),
                warp_config_path(base),
                "masque and wireguard identities must live in separate files",
            );
        }
    }

    #[test]
    fn the_shared_file_is_recovered_from_masques_own_path() {
        // The migration reads the old shared file given only MASQUE's path, so
        // the derivation has to be reversible or nothing is ever adopted and
        // every upgrading install pays for a second registration.
        for base in ["/data/aether.toml", "/data/aether", "C:\\x\\aether.toml"] {
            let masque = masque_config_path(base);
            assert_eq!(
                Some(warp_config_path(base)),
                shared_config_path(&masque),
                "could not get back to the shared file from {masque}",
            );
        }

        // A deliberate override is not a derived sibling and nothing should be
        // migrated into it.
        assert_eq!(None, shared_config_path("/data/somewhere-else.toml"));
    }
}

/// What MASQUE enrollment does to the WireGuard half of the same registration.
///
/// These talk to Cloudflare and hold a real handshake open, so they are
/// `#[ignore]`d and additionally gated: a registration costs an entry against
/// the per-IP rate limit, and CI running this on every push would exhaust it
/// for everybody sharing the runner's address.
///
///     AETHER_LIVE_ENROLL_TEST=1 cargo test -p aether enrollment -- --ignored --nocapture
#[cfg(test)]
mod enrollment_tests {
    use super::*;
    use std::time::Duration;

    /// A documented WARP anchor. If a key is registered, this answers in well
    /// under a second from almost anywhere.
    const ANCHOR: &str = "162.159.192.1:2408";

    async fn shakes_hands(identity: &account::Identity) -> Result<Duration> {
        wireguard::verify_endpoint(
            ANCHOR.parse().unwrap(),
            identity.wg_private_key,
            identity.wg_peer_public_key,
            identity.client_id,
            identity
                .ipv4
                .parse()
                .map_err(|_| AetherError::Other("invalid ipv4".into()))?,
            &aethernoize::AetherNoizeConfig::off(),
            Duration::from_secs(6),
            None,
        )
        .await
    }

    /// Whether an identity a phone could not connect with works from here.
    ///
    /// This is the fork the diagnostics could not resolve. A device reports
    /// that no WireGuard endpoint anywhere answers; the same engine, the same
    /// anchors and a freshly made identity hand shake from a desktop in about a
    /// second. Either the credentials that device is holding are unusable, or
    /// they are fine and something on the device stops the packets being what
    /// Cloudflare expects. Those have nothing in common as fixes.
    ///
    /// Pull the file off the device and run it against the anchors from a
    /// machine known to work:
    ///
    ///     adb shell run-as <pkg> cat files/aether.toml > id.toml
    ///     AETHER_TEST_IDENTITY=id.toml cargo test -p aether identity_from -- \
    ///         --ignored --nocapture
    #[tokio::test]
    #[ignore = "needs an identity file pulled off a device"]
    async fn an_identity_from_a_device_hand_shakes_from_here() {
        let Ok(path) = std::env::var("AETHER_TEST_IDENTITY") else {
            eprintln!("set AETHER_TEST_IDENTITY to a device's aether.toml");
            return;
        };

        let identity = config::load(&path)
            .expect("read the identity file")
            .expect("the file held an identity");

        eprintln!("[test] device {}", identity.device_id);
        eprintln!("[test] ipv4 {}", identity.ipv4);
        eprintln!("[test] client_id {:?}", identity.client_id);
        eprintln!("[test] assigned endpoint {:?}", identity.assigned_endpoint);
        eprintln!(
            "[test] has masque credentials: {}",
            identity.has_masque_credentials()
        );

        // Both framings, because the engine tries a plain handshake alongside
        // the obfuscated ones and a difference between them is itself a result.
        let profiles = [
            ("off", aethernoize::AetherNoizeConfig::off()),
            ("firewall", aethernoize::from_profile("firewall")),
        ];

        let mut answered = Vec::new();
        for (name, profile) in &profiles {
            for seed in wireguard::wg_seeds_v4().iter().take(4) {
                for port in wireguard::WG_PORTS.iter().copied().take(2) {
                    let peer: SocketAddr = format!("{seed}:{port}").parse().unwrap();
                    let result = wireguard::verify_endpoint(
                        peer,
                        identity.wg_private_key,
                        identity.wg_peer_public_key,
                        identity.client_id,
                        identity.ipv4.parse().expect("ipv4"),
                        profile,
                        std::time::Duration::from_secs(4),
                        None,
                    )
                    .await;
                    match result {
                        Ok(rtt) => {
                            eprintln!("[test] {name:9} {peer} -> OK {rtt:?}");
                            answered.push((name.to_string(), peer));
                        }
                        Err(error) => eprintln!("[test] {name:9} {peer} -> {error}"),
                    }
                }
            }
        }

        assert!(
            !answered.is_empty(),
            "this identity got no answer from any anchor even from a machine \
             where a fresh one works, so the credentials are the problem, not \
             the device's network",
        );
        eprintln!("[test] {} endpoints answered", answered.len());
    }

    /// Enrolling MASQUE revokes the WireGuard key on the same account.
    ///
    /// Both write the same `key` field on the same device record: registration
    /// puts a Curve25519 public key there with `tunnel_type: wireguard`, and
    /// `enroll_key` PATCHes it to a secp256r1 SPKI with `tunnel_type: masque`.
    /// Afterwards Cloudflare has no WireGuard key for the device, and WireGuard
    /// meets an unrecognised peer with silence rather than a rejection -- so
    /// every endpoint everywhere stops answering, and the endpoint search
    /// reports the network as dead when the credentials are what died.
    ///
    /// The delay is the reason this needed a test rather than a reading of the
    /// source. The old key keeps working for roughly thirty to sixty seconds
    /// after the PATCH, so a check made straight afterwards comes back clean
    /// and the sharing looks safe. It is not, and a released version shipped
    /// believing it was.
    ///
    /// If this ever starts failing, Cloudflare has changed the model and the
    /// two protocols could share one registration again -- which would halve
    /// what a user spends against a per-IP rate limit, and is worth taking.
    async fn wireguard_dies_when_masque_is_enrolled_on_the_same_account() {
        if std::env::var("AETHER_LIVE_ENROLL_TEST").is_err() {
            eprintln!("set AETHER_LIVE_ENROLL_TEST=1 to run this");
            return;
        }

        let identity = account::provision_wg(consts::DEFAULT_MODEL, consts::DEFAULT_LOCALE, None)
            .await
            .expect("provision a fresh wireguard registration");
        eprintln!("[test] registered device {}", identity.device_id);

        let before = shakes_hands(&identity).await;
        eprintln!("[test] handshake before enrollment: {before:?}");
        assert!(
            before.is_ok(),
            "a freshly registered key should hand shake with {ANCHOR}; got {before:?}. \
             If this fails the network is blocking UDP and the test proves nothing.",
        );

        let keypair = account::generate_masque_keypair().expect("masque keypair");
        account::enroll_key(
            &identity.device_id,
            &identity.access_token,
            &keypair.spki_der,
            None,
        )
        .await
        .expect("enroll the masque key");
        eprintln!("[test] enrolled a masque key on the same device record");

        // Not immediately. Cloudflare does not propagate a key change to its
        // edge synchronously, so asking straight away gets an answer from an
        // edge that has not heard yet -- which is exactly how an earlier
        // version of this test concluded enrollment was harmless, and sent the
        // investigation off after the network instead.
        let mut after = shakes_hands(&identity).await;
        for elapsed in [0u64, 30, 60, 90, 120] {
            if elapsed > 0 {
                tokio::time::sleep(Duration::from_secs(30)).await;
                after = shakes_hands(&identity).await;
            }
            eprintln!("[test] handshake {elapsed:>3}s after enrollment: {after:?}");
        }

        assert!(
            after.is_err(),
            "enrolling a MASQUE key left the WireGuard key working. Cloudflare has \
             changed the model: the two families could share one registration \
             again, halving what a user spends against the per-IP rate limit. \
             Re-check before taking it -- the revocation used to take up to a \
             minute to reach the edge; got {after:?}",
        );
    }
}
