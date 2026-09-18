#![allow(dead_code)]
pub mod account;
pub mod aethernoize;
pub mod api;
pub mod apifront;
pub mod bridges;
pub mod cli;
pub mod config;
pub mod consts;
pub mod dns;
pub mod egress;
pub mod error;
// Upstream's own C API is deliberately not vendored. Nothing here calls it --
// native/android-bridge is this project's FFI and predates it -- and its
// spawn_job wrapper around run_with does not satisfy Send for our merged
// engine, so carrying it would mean carrying a build failure for an interface
// we do not ship.
pub mod fragment;
pub mod identity;
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
pub mod tor;
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

/// MASQUE over HTTP/2 carries its capsules on a TCP stream, where nothing has
/// to fit inside a single UDP datagram. The 1280 that keeps a QUIC datagram
/// whole only buys the netstack more segments to cut on that path, so it gets
/// an ordinary ethernet MTU instead.
const H2_TUNNEL_MTU: usize = 1500;

/// The inner MTU for the MASQUE tunnel. `AETHER_MASQUE_MTU` overrides it, for a
/// path where the edge turns out not to carry full-size packets.
fn masque_tunnel_mtu() -> usize {
    if let Some(mtu) = std::env::var("AETHER_MASQUE_MTU")
        .ok()
        .and_then(|value| value.trim().parse::<usize>().ok())
        .filter(|mtu| (576..=1500).contains(mtu))
    {
        return mtu;
    }

    if masque_h2::enabled() {
        H2_TUNNEL_MTU
    } else {
        MASQUE_MTU
    }
}
const DEFAULT_CONFIG: &str = "aether.toml";

pub async fn run() -> Result<()> {
    run_with(std::env::args().skip(1).collect()).await
}

pub async fn run_with(args: Vec<String>) -> Result<()> {
    if cli::parse_args(args)? == cli::Parsed::Done {
        return Ok(());
    }

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
    sysprofile::raise_fd_limit();
    egress::init()?;

    install_netstack_panic_guard();

    let listen: SocketAddr = std::env::var("AETHER_SOCKS")
        .ok()
        .and_then(|s| s.parse().ok())
        .unwrap_or_else(|| "127.0.0.1:1819".parse().unwrap());

    drop(socks::bind_listener("socks5", listen).await?);
    drop(bind_http_proxy().await?);

    let base_config = std::env::var("AETHER_CONFIG").unwrap_or_else(|_| DEFAULT_CONFIG.to_string());

    if tor::mode() == tor::Mode::Only {
        return tor::run_only(listen, tor::state_dir(&base_config)).await;
    }

    // A malformed address is worth reporting before an account is provisioned.
    let pinned_wiw = wiw_endpoints_from_env()?;
    let pinned_mim = mim_endpoints_from_env()?;

    let protocol = match std::env::var("AETHER_PROTOCOL") {
        Ok(v) => Protocol::parse(&v),
        // Naming a warp-in-warp hop only makes sense for warp-in-warp.
        Err(_) if !pinned_wiw.is_empty() => Protocol::WarpInWarp,
        Err(_) if !pinned_mim.is_empty() => Protocol::MasqueInMasque,
        Err(_)
            if std::env::var("AETHER_PEER").is_ok() || std::env::var("AETHER_WG_PEER").is_ok() =>
        {
            Protocol::Masque
        }
        Err(_) => select_protocol(&base_config).await,
    };

    if tor::mode() == tor::Mode::Only {
        return tor::run_only(listen, tor::state_dir(&base_config)).await;
    }

    if protocol != Protocol::WarpInWarp && !pinned_wiw.is_empty() {
        log::warn!(
            "[-] the warp-in-warp endpoints you set are ignored on {}; they only apply to --gool",
            protocol.label()
        );
    }

    if protocol != Protocol::MasqueInMasque && !pinned_mim.is_empty() {
        log::warn!(
            "[-] the masque-in-masque endpoints you set are ignored on {}; they only apply to --mim",
            protocol.label()
        );
    }

    match tor::mode() {
        tor::Mode::Chain => {
            let through = listen;
            let state = tor::state_dir(&base_config);
            tokio::spawn(async move {
                if let Err(e) = tor::run_chain(through, state).await {
                    log::error!("[-] tor: {e}");
                }
            });
        }
        tor::Mode::Reverse => {
            if matches!(protocol, Protocol::WireGuard | Protocol::WarpInWarp) {
                return Err(AetherError::Other(format!(
                    "tor carries tcp only and warp's wireguard endpoints answer on udp alone, so \
                     {} can never be reached through tor; use --masque, which this mode runs over \
                     http/2, or put tor inside the tunnel instead with --tor",
                    protocol.label()
                )));
            }

            let socks = tor::start_reverse(tor::state_dir(&base_config)).await?;
            std::env::set_var("AETHER_UPSTREAM", format!("socks5://{socks}"));
            std::env::set_var("AETHER_MASQUE_HTTP2", "1");
            log::info!("[+] the tunnel is dialled through tor on the http/2 carrier");
        }
        tor::Mode::Only | tor::Mode::Off => {}
    }

    match protocol {
        Protocol::Masque => {
            select_masque_transport().await;
            let site = identity_site(&base_config, identity::Slot::Masque);
            let config_path = site.path.clone();
            let identity = load_or_provision_masque(&site).await?;
            log::info!(
                "[+] identity ready: device={} ipv4={} ipv6={}",
                identity.device_id,
                identity.ipv4,
                identity.ipv6
            );
            let ech = attempt_ech().await;
            let lastconn_path = lastconn_path(&config_path);
            run_masque(identity, ech, listen, lastconn_path).await
        }
        Protocol::WireGuard => {
            let site = identity_site(&base_config, identity::Slot::Wireguard);
            let config_path = site.path.clone();
            let identity = load_or_provision_warp(&site).await?;
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
            let primary =
                load_or_provision_warp(&identity_site(&base_config, identity::Slot::Wireguard))
                    .await?;
            let secondary = load_or_provision_warp(&identity_site(
                &base_config,
                identity::Slot::WireguardInner,
            ))
            .await?;
            log::info!(
                "[+] outer device={} ipv4={} | inner device={} ipv4={}",
                primary.device_id,
                primary.ipv4,
                secondary.device_id,
                secondary.ipv4
            );
            run_gool(primary, secondary, listen).await
        }
        Protocol::MasqueInMasque => {
            select_masque_transport().await;
            let primary =
                load_or_provision_masque(&identity_site(&base_config, identity::Slot::Masque))
                    .await?;
            let secondary =
                load_or_provision_masque(&identity_site(&base_config, identity::Slot::MasqueInner))
                    .await?;
            log::info!(
                "[+] outer device={} ipv4={} | inner device={} ipv4={}",
                primary.device_id,
                primary.ipv4,
                secondary.device_id,
                secondary.ipv4
            );
            let ech = attempt_ech().await;
            run_mim(primary, secondary, ech, listen).await
        }
    }
}

/// The port Cloudflare's WireGuard edges usually answer on. It is only ever
/// shown as an example: an endpoint has to carry its own port, because which
/// port gets through is exactly what differs between one network and the next.
const WG_EXAMPLE_PORT: u16 = 2408;

/// The two hops of a warp-in-warp tunnel, as far as they were chosen by hand. A
/// hop left as `None` is one the scan still has to find.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
struct WiwEndpoints {
    outer: Option<SocketAddr>,
    inner: Option<SocketAddr>,
}

impl WiwEndpoints {
    fn is_empty(&self) -> bool {
        self.outer.is_none() && self.inner.is_none()
    }

    /// The hops have to leave through different edges: sending the inner tunnel
    /// back out of the address it already arrived on gains nothing, and
    /// `run_warp_in_warp` refuses it.
    fn checked(self) -> Result<Self> {
        match (self.outer, self.inner) {
            (Some(outer), Some(inner)) if outer.ip() == inner.ip() => {
                Err(AetherError::Other(format!(
                    "the two hops need separate edges, but both point at {}",
                    outer.ip()
                )))
            }
            _ => Ok(self),
        }
    }
}

/// Reads one endpoint. The port has to be written out: which port answers is
/// the part that differs from network to network, so filling one in on
/// somebody's behalf would only send them at an address nobody offered.
fn parse_endpoint(raw: &str) -> Result<SocketAddr> {
    let text = raw.trim();

    if let Ok(peer) = text.parse::<SocketAddr>() {
        return Ok(peer);
    }

    // An address with the port left off is the likely slip, so name what is
    // missing rather than calling the whole thing unreadable.
    let portless = text.parse::<IpAddr>().ok().or_else(|| {
        text.strip_prefix('[')
            .and_then(|rest| rest.strip_suffix(']'))
            .and_then(|inner| inner.parse::<IpAddr>().ok())
    });

    if let Some(address) = portless {
        return Err(AetherError::Other(format!(
            "{text} carries no port, and the port is required; write it out, as in {}",
            SocketAddr::new(address, WG_EXAMPLE_PORT)
        )));
    }

    Err(AetherError::Other(format!(
        "'{text}' is not an endpoint; write an address and a port together, \
         such as 162.159.192.1:{WG_EXAMPLE_PORT}"
    )))
}

/// Reads the one or two endpoints of a warp-in-warp pair, separated by commas,
/// semicolons or spaces.
fn parse_endpoint_list(raw: &str) -> Result<Vec<SocketAddr>> {
    let mut peers = Vec::new();

    for part in raw.split([',', ';', ' ']) {
        if part.trim().is_empty() {
            continue;
        }
        peers.push(parse_endpoint(part)?);
    }

    match peers.len() {
        0 => Err(AetherError::Other(
            "no endpoint was given; expected one or two addresses".to_string(),
        )),
        1 | 2 => Ok(peers),
        found => Err(AetherError::Other(format!(
            "warp-in-warp has two hops, but {found} addresses were given"
        ))),
    }
}

fn env_value(key: &str) -> Option<String> {
    std::env::var(key)
        .ok()
        .map(|value| value.trim().to_string())
        .filter(|value| !value.is_empty())
}

/// True when the endpoints were deliberately left to the scan, so there is
/// nothing left to ask about.
fn wiw_scan_requested(lookup: &dyn Fn(&str) -> Option<String>) -> bool {
    match lookup("AETHER_WIW_PEERS") {
        Some(value) => matches!(
            value.to_lowercase().as_str(),
            "auto" | "scan" | "none" | "off" | "0"
        ),
        None => false,
    }
}

fn scan_keyword(value: &str) -> bool {
    matches!(
        value.to_lowercase().as_str(),
        "auto" | "scan" | "none" | "off" | "0"
    )
}

fn nested_endpoints_of(
    lookup: &dyn Fn(&str) -> Option<String>,
    list_key: &str,
    outer_key: &str,
    inner_key: &str,
) -> Result<WiwEndpoints> {
    let mut chosen = WiwEndpoints::default();

    if let Some(list) = lookup(list_key) {
        if !scan_keyword(&list) {
            let peers = parse_endpoint_list(&list)?;
            chosen.outer = peers.first().copied();
            chosen.inner = peers.get(1).copied();
        }
    }

    if let Some(value) = lookup(outer_key) {
        chosen.outer = Some(parse_endpoint(&value)?);
    }

    if let Some(value) = lookup(inner_key) {
        chosen.inner = Some(parse_endpoint(&value)?);
    }

    chosen.checked()
}

fn wiw_endpoints_of(lookup: &dyn Fn(&str) -> Option<String>) -> Result<WiwEndpoints> {
    nested_endpoints_of(
        lookup,
        "AETHER_WIW_PEERS",
        "AETHER_WIW_OUTER_PEER",
        "AETHER_WIW_INNER_PEER",
    )
}

fn wiw_endpoints_from_env() -> Result<WiwEndpoints> {
    wiw_endpoints_of(&env_value)
}

fn mim_endpoints_of(lookup: &dyn Fn(&str) -> Option<String>) -> Result<WiwEndpoints> {
    nested_endpoints_of(
        lookup,
        "AETHER_MIM_PEERS",
        "AETHER_MIM_OUTER_PEER",
        "AETHER_MIM_INNER_PEER",
    )
}

fn mim_endpoints_from_env() -> Result<WiwEndpoints> {
    mim_endpoints_of(&env_value)
}

/// `--peer` and `--wg-peer` are older than the warp-in-warp settings and the
/// guides already pair them with `--gool`, so they still name the outer hop.
fn wiw_endpoints_with_fallback(lookup: &dyn Fn(&str) -> Option<String>) -> Result<WiwEndpoints> {
    let mut chosen = wiw_endpoints_of(lookup)?;

    if chosen.outer.is_some() {
        return Ok(chosen);
    }

    let forced = lookup("AETHER_WG_PEER").or_else(|| lookup("AETHER_PEER"));
    let Some(forced) = forced else {
        return Ok(chosen);
    };

    let peers = parse_endpoint_list(&forced)?;
    chosen.outer = peers.first().copied();
    if chosen.inner.is_none() {
        chosen.inner = peers.get(1).copied();
    }

    chosen.checked()
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
            Protocol::Masque | Protocol::MasqueInMasque => masque_config_path(&self.config_path),
            Protocol::WireGuard | Protocol::WarpInWarp => warp_config_path(&self.config_path),
        }
    }

    /// The second account, for the inner hop of a nested tunnel.
    ///
    /// Two accounts, not one used twice: the inner tunnel handshakes through the
    /// outer one, and Cloudflare would see the same device connecting to itself.
    fn secondary_identity_path(&self) -> String {
        derive_sibling_path(&self.identity_path(), "secondary")
    }

    /// The outer account's slot, and where this install keeps it.
    fn identity_site(&self) -> IdentitySite {
        identity_site(
            &self.config_path,
            match self.protocol() {
                Protocol::Masque | Protocol::MasqueInMasque => identity::Slot::Masque,
                Protocol::WireGuard | Protocol::WarpInWarp => identity::Slot::Wireguard,
            },
        )
    }

    /// The inner account's slot, for a nested tunnel.
    fn secondary_identity_site(&self) -> IdentitySite {
        identity_site(
            &self.config_path,
            match self.protocol() {
                Protocol::Masque | Protocol::MasqueInMasque => identity::Slot::MasqueInner,
                Protocol::WireGuard | Protocol::WarpInWarp => identity::Slot::WireguardInner,
            },
        )
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
/// Format 2 carries every identity an install holds; format 1 carried the two
/// WireGuard ones and silently left MASQUE's behind -- which is the only one a
/// default install has, so for most people the backup was refused as empty.
/// Both are read; 2 is written.
const IDENTITY_EXPORT_VERSION: u32 = 2;

/// Every identity file an install can hold, and the slot each one fills.
///
/// One definition of where these live, used by the backup, by the migration
/// into the identity store, and by the file each slot is still written to
/// while both exist.
fn identity_slots(base_config: &str) -> [(identity::Slot, String); 4] {
    let warp = warp_config_path(base_config);
    let masque = masque_config_path(base_config);
    [
        (identity::Slot::Wireguard, warp.clone()),
        (
            identity::Slot::WireguardInner,
            derive_sibling_path(&warp, "secondary"),
        ),
        (identity::Slot::Masque, masque.clone()),
        (
            identity::Slot::MasqueInner,
            derive_sibling_path(&masque, "secondary"),
        ),
    ]
}

/// What the backup format calls each slot.
///
/// The format's names, not the store's: `identity` stays what format 1 called
/// the WireGuard account rather than being tidied into something better,
/// because a file somebody exported last month has to keep importing.
fn export_key(slot: identity::Slot) -> &'static str {
    match slot {
        identity::Slot::Wireguard => "identity",
        identity::Slot::WireguardInner => "secondary",
        identity::Slot::Masque => "masque",
        identity::Slot::MasqueInner => "masque_secondary",
    }
}

/// Where this install keeps the store, beside the files it supersedes.
fn store_path(base_config: &str) -> String {
    derive_sibling_path(&warp_config_path(base_config), "store")
}

/// Everything the provisioning path needs for one identity.
///
/// Which role it fills, the store that answers for it, and the file that role
/// is still written to -- plus every other file, because the store is built
/// from them the first time it is read.
#[derive(Debug, Clone)]
struct IdentitySite {
    slot: identity::Slot,
    /// The file this slot still gets written to, for one release.
    path: String,
    store_path: String,
    legacy: Vec<(identity::Slot, String)>,
}

fn identity_site(base_config: &str, slot: identity::Slot) -> IdentitySite {
    let legacy = identity_slots(base_config).to_vec();
    let path = legacy
        .iter()
        .find(|(candidate, _)| *candidate == slot)
        .map(|(_, path)| path.clone())
        .unwrap_or_else(|| warp_config_path(base_config));
    IdentitySite {
        slot,
        path,
        store_path: store_path(base_config),
        legacy,
    }
}

/// Records an identity as the device filling this slot.
///
/// In the store, which is what this build reads, *and* in the file an earlier
/// build reads. Both for one release: writing only the store would cost a user
/// who goes back to 1.8.1 their registration, and writing only the file is what
/// the store exists to stop.
fn record(
    store: &mut identity::Store,
    site: &IdentitySite,
    identity: &account::Identity,
) -> Result<()> {
    let id = identity.device_id.clone();
    let mut device = identity::device_from(identity, account::now_unix());

    if let Some(existing) = store.device(&id) {
        // When the device was first registered is a fact about the device, not
        // about this write.
        if existing.registered_at != 0 {
            device.registered_at = existing.registered_at;
        }
        device.refused_at = existing.refused_at;
        // A certificate that has arrived is the answer to the enrolment that
        // was in flight. One that has not leaves the question standing.
        device.enrolment_pending_since = if device.has_certificate() {
            0
        } else {
            existing.enrolment_pending_since
        };
    }

    store.put(&id, device);
    store.assign(site.slot, &id)?;
    identity::save(&site.store_path, store)?;
    config::save(&site.path, identity)
}

/// Packages this install's identities so they survive a reinstall.
///
/// Cloudflare rate-limits device registrations per address, and uninstalling
/// discards the identity -- so a few reinstalls can leave an address refused
/// outright. Carrying the registration across is the difference between that and
/// connecting immediately.
///
/// Every account goes in. Each nested tunnel needs a second one for its inner
/// hop, and MASQUE's is a separate registration from WireGuard's -- leaving any
/// of them behind has the user pay for it again, which is the cost this exists
/// to avoid.
///
/// Format 1 exported only the two WireGuard files. A default install has never
/// run WireGuard -- the transport starts on MASQUE -- so it had no such file,
/// and the one defence against losing an identity answered "there is no
/// identity to export yet" to the people who most needed it.
///
/// Read with [`config::peek`]: a backup is a read, and [`config::load`] would
/// set aside a file it could not parse. Refusing to back up the rest because
/// one slot is damaged is the wrong way round.
pub fn export_identity(base_config: &str) -> Result<String> {
    let site = identity_site(base_config, identity::Slot::Wireguard);
    let loaded = identity::load(&site.store_path, &site.legacy)?;

    // From the store, which is what this build dials with -- so what leaves the
    // device is what it would have used, rather than what the files beside it
    // happen to say. A slot the invariants cleared is a slot with nothing worth
    // carrying: restoring a device whose key Cloudflare overwrote would hand
    // the next install the same three-minute search that found nothing.
    let held: Vec<(identity::Slot, account::Identity)> = identity::Slot::ALL
        .into_iter()
        .filter_map(|slot| {
            let id = loaded.store.device_id(slot)?;
            let device = loaded.store.device(id)?;
            identity::identity_from(id, device)
                .ok()
                .map(|identity| (slot, identity))
        })
        .collect();

    let Some((_, first)) = held.first() else {
        return Err(AetherError::Other(
            "there is no identity to export yet".into(),
        ));
    };

    let mut out = String::new();
    out.push_str(&format!("version = {IDENTITY_EXPORT_VERSION}\n"));
    out.push_str(&format!("device_id = {:?}\n", first.device_id));
    for (slot, identity) in &held {
        out.push_str(&format!("\n[{}]\n", export_key(*slot)));
        out.push_str(&config::to_text(identity)?);
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

    // Older formats are read, not refused: a backup is written once and
    // restored much later, and the whole point of it is the moment when
    // registering again is not an option. Only a format from the future is
    // refused, because guessing at a shape this build has never seen and
    // writing the result over a working identity is worse than saying no.
    if envelope.version == 0 || envelope.version > IDENTITY_EXPORT_VERSION {
        return Err(AetherError::Other(format!(
            "this file was written by a different version of WhiteAesther (format {}, this build reads {IDENTITY_EXPORT_VERSION})",
            envelope.version
        )));
    }

    // Everything is parsed before a single byte is written, so a file that is
    // half readable cannot leave the device half restored.
    let carried = [
        envelope.identity,
        envelope.secondary,
        envelope.masque,
        envelope.masque_secondary,
    ];
    let mut ready: Vec<(identity::Slot, String, account::Identity)> = Vec::new();
    for ((slot, path), value) in identity_slots(base_config).into_iter().zip(carried) {
        let Some(value) = value else { continue };
        let text = toml::to_string(&value).map_err(|e| {
            AetherError::Other(format!(
                "the {} identity is malformed: {e}",
                export_key(slot)
            ))
        })?;
        ready.push((slot, path, config::parse(&text)?));
    }

    if ready.is_empty() {
        return Err(AetherError::Other(
            "this file is a WhiteAesther identity backup but carries no identity".into(),
        ));
    }

    let site = identity_site(base_config, identity::Slot::Wireguard);
    let mut loaded = identity::load(&site.store_path, &site.legacy)?;
    for (slot, _, identity) in &ready {
        let incoming = identity::device_from(identity, account::now_unix());
        // The same device can arrive twice, once per slot -- which is exactly
        // what a backup of an install 1.8.0 had broken looks like. Whichever
        // copy shows an enrolment is the one that knows what Cloudflare holds;
        // a copy that does not is silence, and silence is not evidence that a
        // key came back. Merged rather than overwritten so the answer does not
        // depend on which section the file happened to list first.
        let merged = match loaded.store.device(&identity.device_id) {
            Some(existing)
                if existing.tunnel_type == identity::TunnelType::Masque
                    && !incoming.has_certificate() =>
            {
                identity::Device {
                    tunnel_type: identity::TunnelType::Masque,
                    cert_pem: existing.cert_pem.clone(),
                    key_pem: existing.key_pem.clone(),
                    cert_issued_at: existing.cert_issued_at,
                    ..incoming
                }
            }
            _ => incoming,
        };
        loaded.store.put(&identity.device_id, merged);
        loaded.store.assign(*slot, &identity.device_id)?;
    }

    // Checked like anything else. A backup taken from an install that 1.8.0
    // had already broken carries one device in two slots, and restoring that
    // faithfully would restore the breakage with it.
    let repairs = loaded.store.repair();
    identity::save(&site.store_path, &loaded.store)?;

    let mut restored = 0usize;
    for (slot, path, identity) in &ready {
        if loaded.store.device_id(*slot) != Some(identity.device_id.as_str()) {
            log::warn!(
                "[-] the {} identity in this backup was not restored: its device is not one                  Cloudflare still holds a key for",
                export_key(*slot)
            );
            continue;
        }
        config::write_identity(path, identity)?;
        restored += 1;
    }

    log::info!(
        "[+] imported {restored} identit{}, the first for device {}",
        if restored == 1 { "y" } else { "ies" },
        ready[0].2.device_id
    );
    if !repairs.is_empty() {
        log::info!("[!] some of the backup was set aside: {repairs:?}");
    }
    Ok(())
}

#[derive(serde::Deserialize)]
struct ExportEnvelope {
    version: u32,
    /// The WireGuard account. Named `identity` because format 1 named it that,
    /// back when it was the only one a backup carried.
    #[serde(default)]
    identity: Option<toml::Value>,
    #[serde(default)]
    secondary: Option<toml::Value>,
    #[serde(default)]
    masque: Option<toml::Value>,
    #[serde(default)]
    masque_secondary: Option<toml::Value>,
}

/// Provisions the identities this configuration needs, and nothing else.
///
/// No endpoint search and no tunnel. Preparation does both, and they are the
/// slow half -- several thousand probes -- which is exactly what makes them the
/// wrong thing to do here. This runs while some other carrier is already
/// carrying the user's traffic, for one purpose: to buy the registration that
/// carrier has made reachable, so the *next* connect can be the engine's own
/// direct one.
///
/// Every request it makes goes out through whatever `AETHER_UPSTREAM` names, so
/// with a working carrier's SOCKS listener there, registration leaves by the
/// route that works instead of the one that does not. On a network where
/// `api.cloudflareclient.com` is unreachable in every direction this is the
/// only way an identity is ever obtained at all.
///
/// Costs nothing when the identities are already held: both loaders answer from
/// the store without a round trip.
pub async fn provision_embedded(config: &EmbeddedConfig) -> Result<Vec<String>> {
    let nested = matches!(
        config.protocol(),
        Protocol::MasqueInMasque | Protocol::WarpInWarp
    );

    let mut devices = vec![
        match config.protocol() {
            Protocol::Masque | Protocol::MasqueInMasque => {
                load_or_provision_masque(&config.identity_site()).await?
            }
            Protocol::WireGuard | Protocol::WarpInWarp => {
                load_or_provision_warp(&config.identity_site()).await?
            }
        }
        .device_id,
    ];

    if nested {
        devices.push(
            match config.protocol() {
                Protocol::MasqueInMasque => {
                    load_or_provision_masque(&config.secondary_identity_site()).await?
                }
                _ => load_or_provision_warp(&config.secondary_identity_site()).await?,
            }
            .device_id,
        );
    }

    Ok(devices)
}

pub async fn prepare_embedded(config: &EmbeddedConfig) -> Result<EmbeddedPrepared> {
    // Before anything is registered or searched for: a promise that cannot be
    // kept should be refused at the start, not discovered at the handshake.
    if matches!(
        config.protocol(),
        Protocol::Masque | Protocol::MasqueInMasque
    ) {
        ech_policy_satisfied().await?;
    }
    let config_path = config.identity_path();
    // Matched exhaustively rather than "MASQUE, otherwise WireGuard". 2.0.0
    // added a fourth protocol, and under the old shape it would have gone down
    // the WireGuard path -- provisioning a WARP account and building a tunnel
    // nobody asked for, without saying so. Now the next protocol upstream adds
    // is a compile error here instead of a silent one.
    let identity = match config.protocol() {
        Protocol::Masque | Protocol::MasqueInMasque => {
            load_or_provision_masque(&config.identity_site()).await?
        }
        Protocol::WireGuard | Protocol::WarpInWarp => {
            load_or_provision_warp(&config.identity_site()).await?
        }
    };
    // Nested, this is the outer edge. The inner ones are derived from it when
    // the tunnel is built rather than chosen here, so preparing a nested tunnel
    // costs exactly what preparing a single one does.
    let peer = match config.protocol() {
        Protocol::Masque | Protocol::MasqueInMasque => {
            select_embedded_peer(&identity, config, &config_path).await?
        }
        Protocol::WireGuard | Protocol::WarpInWarp => {
            select_embedded_wg_peer(&identity, config, &config_path)
                .await?
                .0
        }
    };

    // Nested, the interface is addressed for the inner account: that is the one
    // whose packets reach the internet, and addressing it as the outer would
    // give every connection the wrong source.
    if config.protocol() == Protocol::MasqueInMasque {
        let secondary = load_or_provision_masque(&config.secondary_identity_site()).await?;
        return Ok(EmbeddedPrepared {
            ipv4: secondary.ipv4,
            ipv6: secondary.ipv6,
            peer,
        });
    }

    if config.protocol() == Protocol::WarpInWarp {
        let secondary = load_or_provision_warp(&config.secondary_identity_site()).await?;
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
        if cached.applies_to(&attempt_proof("wg")) {
            if let Ok(peer) = cached.peer.parse::<SocketAddr>() {
                let profile = aethernoize::from_profile(&cached.profile);
                if verify_wg_peer(identity, peer, &profile).await.is_ok() {
                    log::info!("[+] cached WireGuard endpoint {peer} still works");
                    return Ok((peer, profile, cached.profile.clone()));
                }
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
    if !matches!(config.protocol(), Protocol::Masque) {
        return scan_embedded_wg(config, limit, cancelled).await;
    }
    let identity = load_or_provision_masque(&config.identity_site()).await?;
    let probe = masque_probe(&identity, prober::IpScan::parse(&config.ip_scan)).await;
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
    if !matches!(config.protocol(), Protocol::Masque) {
        let identity = load_or_provision_warp(&config.identity_site()).await?;
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
    let identity = load_or_provision_masque(&config.identity_site()).await?;
    let rtt = verify_masque_peer(&identity, peer).await?;
    Ok(EmbeddedScanResult { peer, rtt })
}

pub async fn run_embedded(
    config: EmbeddedConfig,
    endpoint: EmbeddedEndpoint,
    mut ready: Option<tokio::sync::oneshot::Sender<()>>,
) -> Result<()> {
    // Before any listener is bound. Every tunnel below reaches socks::serve by
    // its own path, and none of them carries this.
    socks::configure_access(config.access.clone());

    let peer = config
        .peer
        .ok_or_else(|| AetherError::Other("embedded peer is required".into()))?;
    let config_path = config.identity_path();

    if matches!(
        config.protocol(),
        Protocol::WireGuard | Protocol::WarpInWarp
    ) {
        let identity = load_or_provision_warp(&config.identity_site()).await?;
        // Which profile reaches this endpoint is part of what the scan found,
        // and it is not recorded anywhere the caller could hand back -- so it is
        // established again here rather than guessed.
        let (peer, profile, name) =
            select_embedded_wg_peer(&identity, &config.clone_with_peer(peer), &config_path).await?;
        log::info!("[+] WireGuard endpoint {peer} using aethernoize profile '{name}'");
        let ready = record_on_ready(
            lastconn_path(&config_path),
            peer,
            lastconn::Proof {
                profile: name.clone(),
                ..attempt_proof("wg")
            },
            &mut ready,
        );

        if config.protocol() == Protocol::WarpInWarp {
            let secondary = load_or_provision_warp(&config.secondary_identity_site()).await?;
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

    let identity = load_or_provision_masque(&config.identity_site()).await?;
    let ech = attempt_ech().await;
    let mut endpoint = Some(endpoint);
    // The endpoint is remembered when the tunnel confirms it carries data, not
    // when it is chosen. Nested, this is the outer edge -- the one that came
    // out of the pool and is worth leading with next time.
    let mut ready = record_on_ready(
        lastconn_path(&config_path),
        peer,
        attempt_proof(masque_framing()),
        &mut ready,
    );

    if config.protocol() == Protocol::MasqueInMasque {
        // A second account, not the same one twice: the inner tunnel
        // handshakes through the outer, and Cloudflare would otherwise see one
        // device connecting to itself.
        let secondary = load_or_provision_masque(&config.secondary_identity_site()).await?;
        return run_masque_in_masque_embedded(
            &identity,
            &secondary,
            peer,
            ech,
            config.listen,
            &mut endpoint,
            &mut ready,
        )
        .await;
    }

    // Translated here rather than inside, because the inner hop of a nested
    // pair dials a local forwarder that must not be translated again.
    let dial = if masque_h2::enabled() {
        masque_h2::h2_peer(peer)
    } else {
        peer
    };
    run_masque_tunnel_embedded(
        &identity,
        dial,
        ech,
        MasqueShape::single(),
        config.listen,
        &mut endpoint,
        &mut ready,
    )
    .await
}

/// Two nested MASQUE hops behind the embedded endpoint abstraction.
///
/// The shape of `run_warp_in_warp_embedded`, with MASQUE on both hops: the
/// outer one keeps a userspace network stack because the forwarder needs
/// somewhere to open a socket, and the inner one's packets are the user's, so
/// they go to whatever the caller asked for.
///
/// For a network that has learnt to recognise a single MASQUE hop. The inner
/// edges are derived from the outer one rather than scanned for -- the same
/// list the desktop path uses -- so this costs no discovery beyond the outer
/// peer that prepare already chose.
async fn run_masque_in_masque_embedded(
    primary: &account::Identity,
    secondary: &account::Identity,
    peer: SocketAddr,
    ech: Option<Vec<u8>>,
    listen: SocketAddr,
    endpoint: &mut Option<EmbeddedEndpoint>,
    ready: &mut Option<tokio::sync::oneshot::Sender<()>>,
) -> Result<()> {
    let h2 = masque_h2::enabled();
    let outer_mtu = masque_tunnel_mtu();
    let outer_dial = if h2 { masque_h2::h2_peer(peer) } else { peer };

    log::info!("[*] establishing outer MASQUE tunnel to {peer}...");
    let outer = establish_masque(
        primary,
        outer_dial,
        ech,
        h2,
        outer_mtu,
        quic::MAX_DATAGRAM_SIZE,
        true,
        masque_startup_timeout(),
        "outer",
    )
    .await?;

    let candidates: Vec<SocketAddr> = inner_masque_candidates(peer, MIM_INNER_TRIES)
        .into_iter()
        .filter(|candidate| candidate.ip() != peer.ip())
        .collect();
    if candidates.is_empty() {
        outer.exit.abort();
        return Err(AetherError::Other(
            "no second masque edge is known for the inner hop".into(),
        ));
    }

    let mut last: Option<AetherError> = None;
    for inner_peer in candidates {
        let shape = MasqueShape::mim_inner(outer_mtu, inner_peer, h2);
        if !h2 && shape.datagram + 28 > outer_mtu {
            log::warn!(
                "[-] the outer link carries {outer_mtu} bytes, too little for an inner quic \
                 datagram; raise AETHER_MASQUE_MTU or use --h2 for both hops"
            );
        }

        // Through the outer tunnel, so the inner handshake never touches the
        // network directly -- which is the whole point of nesting them.
        let (forwarder, _forwarder_guard) = if h2 {
            spawn_tcp_forwarder(&outer.stack, inner_peer).await?
        } else {
            spawn_udp_forwarder(&outer.stack, inner_peer).await?
        };
        log::info!(
            "[*] trying inner MASQUE edge {inner_peer} through the outer tunnel via {forwarder}"
        );

        let outcome =
            run_masque_tunnel_embedded(secondary, forwarder, None, shape, listen, endpoint, ready)
                .await;

        match outcome {
            Ok(()) => return Ok(()),
            // It took the endpoint, so it was carrying the session: this is the
            // session ending rather than a candidate refusing, and there is
            // nothing left to offer the next one.
            Err(error) if endpoint.is_none() => {
                outer.exit.abort();
                return Err(error);
            }
            Err(error) => {
                log::info!(
                    "[-] inner edge {inner_peer} does not serve masque from inside the tunnel: \
                     {error}"
                );
                last = Some(error);
            }
        }
    }

    outer.exit.abort();
    Err(last.unwrap_or_else(|| {
        AetherError::Other("no inner masque edge answered from inside the outer tunnel".into())
    }))
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
            // Bound out here rather than inside the task, so that a port
            // already in use is an error this call returns instead of one that
            // disappears into a spawned future. bind_listener also says why.
            let listener = socks::bind_listener("socks5", listen).await?;
            tokio::spawn(async move { socks::serve(listener, stack).await })
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
    limit: usize,
    cancelled: &AtomicBool,
) -> Result<Vec<EmbeddedScanResult>> {
    let identity = load_or_provision_warp(&config.identity_site()).await?;
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
            // Bound out here rather than inside the task, so that a port
            // already in use is an error this call returns instead of one that
            // disappears into a spawned future. bind_listener also says why.
            let listener = socks::bind_listener("socks5", listen).await?;
            tokio::spawn(async move { socks::serve(listener, stack).await })
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
        // Only when it was earned by the attempt being made now. A gateway that
        // answered over TCP says nothing about a QUIC attempt, and leading with
        // it costs a check at the front of every connect.
        if cached.applies_to(&attempt_proof(masque_framing())) {
            if let Ok(peer) = cached.peer.parse::<SocketAddr>() {
                if quick_verify_masque_peer(identity, peer).await {
                    return Ok(peer);
                }
            }
        } else {
            log::debug!(
                "[*] the remembered endpoint was proven over {}, not this attempt's shape",
                cached.proof.transport
            );
        }
    }

    // The endpoint Cloudflare assigned this device, before any searching.
    //
    // It is in every registration answer -- `config.peers[0].endpoint` -- and
    // until now it was read, stored, and then used only by Zero Trust. So a
    // consumer account ignored the one address the server had just named and
    // brute-forced three thousand candidates instead: sixteen at a time, six
    // seconds each, a hundred and twenty second budget. That is roughly a tenth
    // of the pool, which is why MASQUE took minutes when it worked at all,
    // while WireGuard -- which does have a documented-anchor pass -- connects in
    // about three seconds.
    //
    // Measured against a live account: the assigned endpoint answered in 253ms
    // over H2 and 543ms over QUIC, while an address from the hard-coded pool
    // refused every protocol.
    for peer in assigned_masque_peers(identity).await {
        if quick_verify_masque_peer(identity, peer).await {
            log::info!("[+] the endpoint Cloudflare assigned this device answered: {peer}");
            return Ok(peer);
        }
        log::info!("[-] the assigned endpoint {peer} did not answer");
    }

    hunt_masque_peer(
        identity,
        &config.scan_mode,
        prober::IpScan::parse(&config.ip_scan),
    )
    .await
}

/// The MASQUE endpoints Cloudflare has named for this device, in order.
///
/// Two of them, because the answer moves. The address stored at registration is
/// free to try and is usually right; Cloudflare also reassigns a device between
/// edges, and `refresh_profile` has always had a line for noticing it. Measured
/// on one account minutes apart: registration said 162.159.192.2 and the device
/// record said 162.159.198.2.
///
/// So the stored one goes first because it costs nothing, and the current one
/// is asked for only when that fails -- one API call, skipped silently when it
/// cannot be answered, because the search still works afterwards. Slowly, which
/// is the whole reason this exists.
async fn assigned_masque_peers(identity: &account::Identity) -> Vec<SocketAddr> {
    fn on_443(host: &str) -> Option<SocketAddr> {
        let host = host.trim();
        if host.is_empty() {
            return None;
        }
        let candidate = if host.contains(':') && !host.starts_with('[') {
            format!("[{host}]:443")
        } else {
            format!("{host}:443")
        };
        candidate.parse::<SocketAddr>().ok()
    }

    let mut peers: Vec<SocketAddr> = Vec::new();
    if let Some(stored) = on_443(&identity.assigned_endpoint) {
        peers.push(stored);
    }

    // Bounded, because this runs on the path to a connect. Asking costs a round
    // trip to an API that some of these networks black-hole, and a stall here
    // would delay the search that still works -- so it gets a few seconds and
    // then we get on with it.
    let asked = tokio::time::timeout(
        ASSIGNED_ENDPOINT_LOOKUP,
        account::fetch_device(&identity.device_id, &identity.access_token),
    )
    .await;

    match asked {
        Err(_) => log::debug!("[-] no answer in time about which endpoint is assigned"),
        Ok(Ok(reg)) => {
            if let Some(current) = on_443(&account::endpoint_from(&reg)) {
                if !peers.contains(&current) {
                    if peers.is_empty() {
                        log::info!("[+] Cloudflare names {current} for this device");
                    } else {
                        log::info!("[+] Cloudflare has moved this device to {current}");
                    }
                    peers.push(current);
                }
            }
        }
        Ok(Err(error)) => log::debug!("[-] could not ask which endpoint is assigned: {error}"),
    }

    peers
}

/// How long the assigned-endpoint question may take before it is abandoned.
///
/// Short on purpose: it is an optimisation on the way to a connect, and the
/// search behind it works without an answer.
const ASSIGNED_ENDPOINT_LOOKUP: std::time::Duration = std::time::Duration::from_secs(4);

/// How one MASQUE hop is sized, and what it may skip.
///
/// A single hop, and the outer hop of a nested pair, get the whole budget. The
/// inner hop of a nested pair has to fit inside whatever the outer one carries,
/// and does not repeat the version bait its outer hop has already sent -- that
/// packet is for the network watching the outside of the tunnel, and inside
/// there is nobody to fool.
struct MasqueShape {
    mtu: usize,
    datagram: usize,
    version_bait: bool,
    startup: std::time::Duration,
    label: &'static str,
}

impl MasqueShape {
    /// One hop straight to the edge.
    fn single() -> Self {
        Self {
            mtu: masque_tunnel_mtu(),
            datagram: quic::MAX_DATAGRAM_SIZE,
            version_bait: true,
            startup: masque_startup_timeout(),
            label: "masque",
        }
    }

    /// The inner hop of masque-in-masque, sized to fit the outer one.
    fn mim_inner(outer_mtu: usize, inner_peer: SocketAddr, h2: bool) -> Self {
        let (datagram, mtu) = mim_inner_budget(outer_mtu, inner_peer, h2);
        Self {
            mtu,
            datagram,
            version_bait: false,
            startup: mim_inner_startup(),
            label: "inner",
        }
    }
}

/// One MASQUE hop behind the embedded endpoint abstraction.
///
/// \nparam peer already dialable: for HTTP/2 the caller translates the edge to
///   its H2 port, exactly as the desktop path does, because the inner hop of a
///   nested pair dials a local forwarder that must not be translated again.
/// \nparam endpoint taken only once this hop is up. Nested, the caller tries
///   inner edges until one answers, and an endpoint moved into a hop that
///   failed could not be offered to the next one. `ready` follows it for the
///   same reason, and because signalling it earlier would tell the app a route
///   is open while an inner edge is still being chosen.
async fn run_masque_tunnel_embedded(
    identity: &account::Identity,
    peer: SocketAddr,
    ech: Option<Vec<u8>>,
    shape: MasqueShape,
    listen: SocketAddr,
    endpoint: &mut Option<EmbeddedEndpoint>,
    ready: &mut Option<tokio::sync::oneshot::Sender<()>>,
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
        max_datagram: shape.datagram,
        // The QUIC v2 version-negotiation bait that goes out ahead of the real
        // handshake. Gated again at run time, so this is the setting rather
        // than the decision.
        version_bait: shape.version_bait,
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
            peer,
            sni: consts::CONNECT_SNI.to_string(),
            authority: quic::default_authority().to_string(),
            path: quic::default_path().to_string(),
            cert_pem: identity.cert_pem.clone(),
            key_pem: identity.key_pem.clone(),
            local_ipv4: parse_local_v4(&identity.ipv4),
            quiet: false,
            pin_endpoint: true,
            expected_pins: consts::masque_pins()
                .iter()
                .map(|pin| pin.to_vec())
                .collect(),
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

    let label = shape.label;
    match tokio::time::timeout(shape.startup, ready_rx).await {
        Ok(Ok(())) => {}
        Ok(Err(_)) => {
            return embedded_tunnel_result(
                tunnel_task.await,
                &format!("[{label}] tunnel exited before validation"),
            )
        }
        Err(_) => {
            tunnel_task.abort();
            let _ = tunnel_task.await;
            return Err(AetherError::Other(format!(
                "[{label}] embedded tunnel startup timed out"
            )));
        }
    }
    // Taken only now. Everything above can fail and be retried against another
    // edge; from here the endpoint belongs to this hop.
    let endpoint = endpoint
        .take()
        .ok_or_else(|| AetherError::Other(format!("[{label}] has no endpoint to carry")))?;
    if let Some(ready) = ready.take() {
        let _ = ready.send(());
    }

    let mut endpoint_task = match endpoint {
        EmbeddedEndpoint::Socks => {
            // Sized by framing, as of 2.0.0: a MASQUE tunnel carried over
            // HTTP/2 is a TCP stream, where nothing has to fit inside one UDP
            // datagram, so the 1280 that keeps a QUIC datagram whole only buys
            // the netstack more segments to cut. Nested, this is smaller again,
            // because the inner hop's packets travel inside the outer one's.
            let stack = netstack::spawn(
                &identity.ipv4,
                &identity.ipv6,
                shape.mtu,
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
            let listener = socks::bind_listener("socks5", listen).await?;
            tokio::spawn(async move { socks::serve(listener, stack).await })
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
    // Scanning is what happens unless somebody named an endpoint themselves.
    let pinned = wiw_endpoints_with_fallback(&env_value)?;

    match (pinned.outer, pinned.inner) {
        (Some(outer), Some(inner)) => log::info!(
            "[+] warp-in-warp endpoints given by hand: {outer} (outer) and {inner} (inner); the scan is skipped"
        ),
        (Some(outer), None) => log::info!(
            "[+] outer warp-in-warp endpoint given by hand: {outer}; scanning for the inner one"
        ),
        (None, Some(inner)) => log::info!(
            "[+] inner warp-in-warp endpoint given by hand: {inner}; scanning for the outer one"
        ),
        (None, None) => {}
    }

    let mut outer_peer = pinned.outer;
    let mut inner_peer = pinned.inner;
    let mut consecutive_fails: u32 = 0;
    const MAX_CONSECUTIVE_FAILS: u32 = 2;
    let mut scan_settings: Option<(String, prober::IpScan)> = None;

    loop {
        if consecutive_fails >= MAX_CONSECUTIVE_FAILS {
            // A hop that was given by hand is kept: it was asked for on purpose,
            // and replacing it behind the user's back is not ours to do.
            let mut rescanning = false;

            if pinned.outer.is_none() {
                if let Some(peer) = outer_peer.take() {
                    log::warn!(
                        "[-] outer endpoint {peer} failed {consecutive_fails} times in a row; blacklisting and rescanning"
                    );
                    rescanning = true;
                }
            }

            if pinned.inner.is_none() {
                if let Some(peer) = inner_peer.take() {
                    log::warn!(
                        "[-] inner endpoint {peer} failed {consecutive_fails} times in a row; blacklisting and rescanning"
                    );
                    rescanning = true;
                }
            }

            if !rescanning {
                log::warn!(
                    "[-] the endpoints you chose failed {consecutive_fails} times in a row; still retrying them, drop --wiw-outer/--wiw-inner to let the scan pick instead"
                );
            }

            consecutive_fails = 0;
        }

        let (peer, inner_peer_now) = match (outer_peer, inner_peer) {
            (Some(outer), Some(inner)) => (outer, inner),
            (known_outer, known_inner) => {
                let wanted =
                    usize::from(known_outer.is_none()) + usize::from(known_inner.is_none());
                let avoid: HashSet<IpAddr> = known_outer
                    .into_iter()
                    .chain(known_inner)
                    .map(|peer| peer.ip())
                    .collect();

                let (mode_str, ip) = match &scan_settings {
                    Some(settings) => settings.clone(),
                    None => {
                        let mode_str = select_scan_mode_str(WIW_MANUAL_TIP).await;
                        let ip = select_ip_version().await;
                        scan_settings.insert((mode_str, ip)).clone()
                    }
                };

                let found = match select_wg_peers(&primary, &mode_str, ip, wanted, &avoid).await {
                    Ok(found) => found,
                    Err(e) => {
                        log::warn!("[-] no usable WARP endpoint found: {e}; rescanning shortly");
                        tokio::time::sleep(wg_reconnect_delay()).await;
                        continue;
                    }
                };

                let mut found = found.into_iter();
                let outer = known_outer.or_else(|| found.next());
                let inner = known_inner.or_else(|| found.next());

                match (outer, inner) {
                    (Some(outer), Some(inner)) => (outer, inner),
                    _ => {
                        log::warn!(
                            "[-] the scan only turned up one edge, so warp-in-warp would use it twice; rescanning"
                        );
                        outer_peer = pinned.outer;
                        inner_peer = pinned.inner;
                        tokio::time::sleep(wg_reconnect_delay()).await;
                        continue;
                    }
                }
            }
        };

        log::info!("[+] using cloudflare edge {peer} (outer) and {inner_peer_now} (inner)");
        outer_peer = Some(peer);
        inner_peer = Some(inner_peer_now);

        match run_warp_in_warp(
            primary.clone(),
            secondary.clone(),
            peer,
            inner_peer_now,
            listen,
        )
        .await
        {
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

/// Serialises everything that can cost a Cloudflare registration.
///
/// One lock for all identity files rather than one per file, because what it
/// guards is not a file -- it is the per-address registration quota behind
/// them. Two provisionings that never touch the same file still spend the same
/// allowance, and an address that has spent it is refused outright, for hours,
/// on every version of this app the user might fall back to.
///
/// Held across the whole cycle: register, enrol, and the writes that record
/// them. The app has a guard of its own, but it covers one preparation against
/// another -- not a preparation against the run that follows a cancelled one,
/// which is the pair that could actually overlap.
fn provisioning_lock() -> &'static tokio::sync::Mutex<()> {
    static LOCK: std::sync::OnceLock<tokio::sync::Mutex<()>> = std::sync::OnceLock::new();
    LOCK.get_or_init(|| tokio::sync::Mutex::new(()))
}

/// Every file beside `path` that holds an identity, `path` itself excluded.
///
/// Read through [`config::peek`], which does not set aside what it cannot
/// parse. [`config::load`] would: the cache of the last working endpoint lives
/// in this directory under a name of the same shape, and quarantining it for
/// failing to be an identity would throw away a working cache on every connect.
///
/// Names that no longer end in `.toml` are skipped, which is what a quarantined
/// file and a half-written temporary both look like.
fn identity_siblings(path: &str) -> Vec<(String, account::Identity)> {
    let dir_end = path
        .rfind(|c| c == '/' || c == '\\')
        .map(|i| i + 1)
        .unwrap_or(0);
    let dir = if dir_end == 0 { "." } else { &path[..dir_end] };

    let entries = match std::fs::read_dir(dir) {
        Ok(entries) => entries,
        Err(_) => return Vec::new(),
    };

    let mut found = Vec::new();
    for entry in entries.flatten() {
        let name = entry.file_name().to_string_lossy().to_string();
        if !name.ends_with(".toml") {
            continue;
        }
        let sibling = if dir_end == 0 {
            name
        } else {
            format!("{}{name}", &path[..dir_end])
        };
        if sibling == path {
            continue;
        }
        if let Some(identity) = config::peek(&sibling) {
            found.push((sibling, identity));
        }
    }
    found
}

/// Where this device was enrolled for MASQUE, if it was enrolled anywhere else.
///
/// Enrolment is `PATCH /reg/{id}`, and it overwrites the same `key` field that
/// registration filled with the Curve25519 public key. Cloudflare then holds no
/// WireGuard key for the device at all, so every endpoint everywhere meets the
/// key on disk with silence -- which is indistinguishable from a network that
/// drops UDP, and sends the endpoint search off to blame the network for three
/// minutes and then report it as dead.
///
/// The guard meant to catch this asked the WireGuard file whether it carried a
/// certificate, and the enrolment that shipped happens on a *copy*: MASQUE
/// adopts a WireGuard-only identity into its own file and enrols it there, so
/// the WireGuard file goes on looking untouched for the rest of the install's
/// life. The question has to be put to the directory, not to one file.
///
/// A certificate at all, rather than a usable one. An expired certificate still
/// means the device's WireGuard key was overwritten; expiry says the MASQUE
/// credential needs renewing, not that WireGuard came back.
fn device_enrolled_elsewhere(config_path: &str, device_id: &str) -> Option<String> {
    identity_siblings(config_path)
        .into_iter()
        .find(|(_, other)| other.device_id == device_id && !other.cert_pem.is_empty())
        .map(|(path, _)| path)
}

/// Records an enrolment on every other file holding the same device.
///
/// So that the next read of that file can see from the file alone that the
/// WireGuard key in it is not one Cloudflare recognises any more -- rather than
/// finding out by searching several thousand endpoints that will never answer.
///
/// The certificate travels; the private key does not. What the mark has to
/// carry is the fact of the enrolment, and that file is not the one that will
/// present the certificate -- copying the secret into a second file would widen
/// its reach for no use.
///
/// Best effort on purpose. This runs after an enrolment that has already
/// succeeded, and failing to annotate a sibling must not fail the connect that
/// earned it; [`device_enrolled_elsewhere`] reaches the same conclusion from
/// the other direction if it does.
fn record_enrolment_beside(config_path: &str, identity: &account::Identity) {
    if identity.cert_pem.is_empty() {
        return;
    }
    for (path, other) in identity_siblings(config_path) {
        if other.device_id != identity.device_id || !other.cert_pem.is_empty() {
            continue;
        }
        match config::save_masque_creds(&path, &identity.cert_pem, &[], identity.cert_issued_at) {
            Ok(()) => log::info!(
                "[!] {path} holds the same device, whose WireGuard key this enrolment revoked; \
                 marked it so that protocol provisions an account of its own"
            ),
            Err(error) => log::warn!("[-] could not mark {path} as enrolled: {error}"),
        }
    }
}

/// Registers an account, unless this address still has a wait to serve.
///
/// The budget lives in the store because a wait a restart forgets is not a
/// wait: the failure it exists to stop is a phone asking again every few
/// seconds against an address Cloudflare has already refused, which is how an
/// allowance goes from spent to spent for a very long time.
///
/// Recorded whichever way it goes, and saved on the failing path too -- writing
/// down what it cost is the whole point of having asked.
async fn provision_within_budget(
    store: &mut identity::Store,
    site: &IdentitySite,
) -> Result<account::Identity> {
    if let Err(wait) = store.registration.may_attempt(account::now_unix()) {
        log::warn!(
            "[-] not registering: {wait}s of the wait from the last attempt is still to run ({})",
            store.registration.last_reason
        );
        return Err(AetherError::RegistrationOnHold {
            reason: store.registration.last_reason.clone(),
            wait,
        });
    }

    match provision_account().await {
        Ok(identity) => {
            store.registration.succeeded(account::now_unix());
            Ok(identity)
        }
        Err(error) => {
            store
                .registration
                .failed(account::now_unix(), &error.to_string(), error.retry_after());
            if let Err(write) = identity::save(&site.store_path, store) {
                log::warn!("[-] could not record what the registration attempt cost: {write}");
            }
            Err(error)
        }
    }
}

async fn load_or_provision_warp(site: &IdentitySite) -> Result<account::Identity> {
    let _provisioning = provisioning_lock().lock().await;
    let mut loaded = identity::load(&site.store_path, &site.legacy)?;

    if let Some((id, device)) = loaded.store.take_usable(site.slot) {
        // A second opinion, from the file rather than the store. The store's
        // invariants and the reconciliation that feeds them should already have
        // caught a device Cloudflare no longer holds a WireGuard key for -- but
        // the cost of being wrong here is not an error, it is a three-minute
        // endpoint search that finds nothing and reports the network as dead.
        // A directory read is cheap next to that.
        match device_enrolled_elsewhere(&site.path, &id) {
            Some(other) => log::info!(
                "[!] the store offered device {id} for the {} slot, but {other} shows it was                  enrolled for MASQUE; provisioning an account of its own",
                site.slot.key()
            ),
            None => {
                log::info!("[+] loaded existing warp identity for device {id}");
                let identity = identity::identity_from(&id, &device)?;
                let identity = adopt_team_profile(identity).await;
                record(&mut loaded.store, site, &identity)?;
                return Ok(identity);
            }
        }
    } else {
        log::info!(
            "[+] no usable warp identity for the {} slot; provisioning a dedicated wireguard account",
            site.slot.key()
        );
    }

    let identity = provision_within_budget(&mut loaded.store, site).await?;
    // Written before anything else is asked of the network. Cloudflare has
    // already counted this registration against the address whether or not the
    // rest of the connect succeeds, so the one thing that must not happen is
    // reaching the next await without it on disk.
    record(&mut loaded.store, site, &identity)?;
    let identity = adopt_team_profile(identity).await;
    record(&mut loaded.store, site, &identity)?;
    log::info!(
        "[+] provisioned and saved a new warp identity for device {}",
        identity.device_id
    );
    Ok(identity)
}

/// Enrols a MASQUE key onto `identity`, recording the attempt before it is made.
///
/// The marker is written and flushed first, and cleared by the write that
/// stores the certificate. Without that order there is a third outcome nothing
/// can see: Cloudflare accepted the PATCH, the answer never reached disk, and
/// the device is a MASQUE device that every file here still describes as a
/// WireGuard one. A file cannot record a change that was never written, so the
/// record has to come before the change.
async fn enrol_and_record(
    store: &mut identity::Store,
    site: &IdentitySite,
    identity: account::Identity,
) -> Result<account::Identity> {
    if let Some(device) = store.device_mut(&identity.device_id) {
        device.enrolment_pending_since = account::now_unix();
    }
    identity::save(&site.store_path, store)?;

    let enrollment = account::ensure_masque_enrolled(&identity).await?;
    let identity = account::Identity {
        cert_pem: enrollment.cert_pem,
        key_pem: enrollment.key_pem,
        cert_issued_at: enrollment.issued_at,
        ..identity
    };
    let identity = adopt_team_profile(identity).await;
    record(store, site, &identity)?;
    // The file an earlier build reads has no store to tell it any of this, and
    // it decides by sweeping for a sibling holding the same device. Nothing
    // here shares a device across families any more, so this is almost always a
    // no-op -- almost being the reason it still runs.
    record_enrolment_beside(&site.path, &identity);
    Ok(identity)
}

async fn load_or_provision_masque(site: &IdentitySite) -> Result<account::Identity> {
    let _provisioning = provisioning_lock().lock().await;
    let mut loaded = identity::load(&site.store_path, &site.legacy)?;

    if let Some((id, device)) = loaded.store.take_usable(site.slot) {
        let identity = identity::identity_from(&id, &device)?;

        if identity.has_masque_credentials() {
            log::info!("[+] loaded existing masque identity for device {id}");
            let identity = adopt_team_profile(identity).await;
            if !identity.refused {
                record(&mut loaded.store, site, &identity)?;
                return Ok(identity);
            }
            if !keep_saved_identity() {
                return Ok(identity);
            }
            if let Some(device) = loaded.store.device_mut(&id) {
                device.refused_at = account::now_unix();
            }
            log::warn!("[*] registering a fresh masque account to replace the refused identity");
        } else {
            // Resumed rather than restarted. This is where a registration whose
            // enrolment failed or was cancelled is picked up, which is what
            // stops the next attempt buying another one.
            log::info!("[+] the masque identity for device {id} needs a certificate; enrolling");
            match enrol_and_record(&mut loaded.store, site, identity.clone()).await {
                Ok(identity) => return Ok(identity),
                Err(AetherError::IdentityRefused(reason)) => {
                    log::warn!("[-] the saved masque identity was refused: {reason}");
                    if !keep_saved_identity() {
                        return Ok(account::Identity {
                            refused: true,
                            ..identity
                        });
                    }
                    if let Some(device) = loaded.store.device_mut(&id) {
                        device.refused_at = account::now_unix();
                    }
                    log::warn!(
                        "[*] registering a fresh masque account to replace the refused identity"
                    );
                }
                Err(error) => return Err(error),
            }
        }
    } else {
        log::info!(
            "[+] no usable masque identity for the {} slot; provisioning a dedicated masque account",
            site.slot.key()
        );
    }

    let identity = provision_within_budget(&mut loaded.store, site).await?;
    // Before the enrolment, which is a second network call and the longer of
    // the two. Registration is the expensive half -- Cloudflare counts it
    // against this address the moment it answers -- and the branch above
    // resumes from here, so an enrolment that fails costs a round trip rather
    // than a registration.
    record(&mut loaded.store, site, &identity)?;
    let identity = enrol_and_record(&mut loaded.store, site, identity).await?;
    log::info!(
        "[+] provisioned and saved a new masque identity for device {}",
        identity.device_id
    );
    Ok(identity)
}

async fn select_peer(identity: &account::Identity, protocol: Protocol) -> Result<SocketAddr> {
    let force_peer = match protocol {
        Protocol::Masque | Protocol::MasqueInMasque => std::env::var("AETHER_PEER").ok(),
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

    let mode_str = select_scan_mode_str("").await;
    let ip = select_ip_version().await;

    match protocol {
        Protocol::Masque | Protocol::MasqueInMasque => {
            log::info!("[*] hunting for a working MASQUE gateway (deep connect-ip verification)");
            let mode = prober::ScanMode::parse(&mode_str);
            let probe = prober::MasqueProbe {
                sni: consts::CONNECT_SNI.to_string(),
                authority: quic::default_authority().to_string(),
                path: quic::default_path().to_string(),
                cert_pem: std::sync::Arc::from(identity.cert_pem.clone()),
                key_pem: std::sync::Arc::from(identity.key_pem.clone()),
                ech_config_list: attempt_ech().await.map(std::sync::Arc::from),
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
            let peers = select_wg_peers(identity, &mode_str, ip, 1, &HashSet::new()).await?;
            Ok(peers[0])
        }
    }
}

/// Hunts for `want` endpoints, leaving out the addresses in `avoid`. Warp-in-warp
/// passes the hop it already has there, so the scan cannot hand back the same
/// edge for both ends of the tunnel.
async fn select_wg_peers(
    identity: &account::Identity,
    mode_str: &str,
    ip: prober::IpScan,
    want: usize,
    avoid: &HashSet<IpAddr>,
) -> Result<Vec<SocketAddr>> {
    log::info!(
        "[*] hunting for {want} working WireGuard endpoint(s) (handshake + data-plane verification)"
    );
    let mode = wg_prober::WgScanMode::parse(mode_str);

    let private_key = identity.private_key_bytes()?;
    let peer_public = identity.peer_public_key_bytes()?;

    let ports = wireguard::WG_PORTS.to_vec();
    let excluded: HashSet<SocketAddr> = avoid
        .iter()
        .flat_map(|address| {
            ports
                .iter()
                .map(move |port| SocketAddr::new(*address, *port))
        })
        .collect();

    if !avoid.is_empty() {
        log::info!(
            "[*] the scan leaves out {} address(es) already taken by the other hop",
            avoid.len()
        );
    }

    let probe = wg_prober::WgProbe {
        private_key: std::sync::Arc::new(private_key),
        peer_public_key: std::sync::Arc::new(peer_public),
        client_id: identity.client_id.clone(),
        local_ipv4: identity
            .ipv4
            .parse()
            .map_err(|_| AetherError::Other("invalid ipv4".into()))?,
        aethernoize: aethernoize_config(),
        ports,
        ip,
        excluded,
    };

    let found = wg_prober::hunt_wg_endpoints(&probe, mode, want).await?;

    // The exclusion above already keeps these out of the sweep; this is the
    // belt to its braces, since handing a hop its own address back is fatal.
    let picked: Vec<wg_prober::WgProbeResult> = found
        .into_iter()
        .filter(|pr| !avoid.contains(&pr.ip))
        .take(want)
        .collect();

    if picked.is_empty() {
        return Err(AetherError::NoCleanEndpoint);
    }

    for pr in &picked {
        log::info!(
            "[+] selected WireGuard endpoint {}:{} (rtt {:?})",
            pr.ip,
            pr.port,
            pr.rtt
        );
    }

    Ok(picked
        .into_iter()
        .map(|pr| SocketAddr::new(pr.ip, pr.port))
        .collect())
}

/// The ECHConfigList this attempt uses, resolved once and shared.
///
/// Everything that validates an endpoint and the thing that finally dials it
/// have to agree. They did not: the scanner, the endpoint hunt and every quick
/// verification passed `None`, while the connection resolved ECH and used it.
/// So the scanner blessed gateways the connection could not use, and the
/// failure arrived long after the check that was supposed to prevent it -- on
/// exactly the networks where ECH is the tactic that gets through.
///
/// Resolved once because it is a DNS lookup, and held briefly so that probe and
/// connect cannot disagree merely because the lookup succeeded once and failed
/// once. Short enough that a rotated configuration is picked up on the next
/// connect rather than the next launch.
///
/// Nothing on the HTTP/2 framing: that transport carries no ECH, so fetching
/// one there is a DNS round trip spent on something nothing reads.
pub async fn attempt_ech() -> Option<Vec<u8>> {
    if masque_h2::enabled() {
        return None;
    }

    let mut held = ech_cache().lock().await;

    if let Some((at, value)) = held.as_ref() {
        if at.elapsed() < ECH_CACHE_FOR {
            return value.clone();
        }
    }

    let resolved = resolve_ech().await;
    *held = Some((std::time::Instant::now(), resolved.clone()));
    resolved
}

/// How long one resolved ECHConfigList is reused across probes and the connect.
const ECH_CACHE_FOR: std::time::Duration = std::time::Duration::from_secs(600);

type EchCache = tokio::sync::Mutex<Option<(std::time::Instant, Option<Vec<u8>>)>>;

fn ech_cache() -> &'static EchCache {
    static CACHED: std::sync::OnceLock<EchCache> = std::sync::OnceLock::new();
    CACHED.get_or_init(|| tokio::sync::Mutex::new(None))
}

/// Forgets the resolved ECHConfigList, so a test can set the policy and ask again.
#[cfg(test)]
async fn forget_ech() {
    *ech_cache().lock().await = None;
}

/// Whether ECH is a requirement rather than a preference.
///
/// `off` (or unset) never asks for one; `auto` asks and carries on without when
/// the answer does not come; `require` refuses to connect instead. The third is
/// the only one that keeps a promise: without it a resolver that quietly fails
/// downgrades a user who asked for their SNI to be hidden, and tells them
/// nothing.
fn ech_required() -> bool {
    matches!(
        std::env::var("AETHER_ECH").as_deref(),
        Ok(v) if v.eq_ignore_ascii_case("require")
    )
}

/// Fails when ECH was required and could not be had.
///
/// Checked once, where a connect begins, rather than at each of the places that
/// would otherwise carry on regardless.
pub async fn ech_policy_satisfied() -> Result<()> {
    if !ech_required() || attempt_ech().await.is_some() {
        return Ok(());
    }
    Err(AetherError::Ech(if masque_h2::enabled() {
        "ECH was required, and the HTTP/2 framing does not carry it; choose H3 or set          AETHER_ECH=auto"
            .into()
    } else {
        "ECH was required and no ECHConfigList could be resolved; refusing to send the SNI in          cleartext"
            .into()
    }))
}

/// The shape this attempt is being made with.
///
/// Built from what the engine is actually about to do, so a remembered endpoint
/// can be judged against it rather than offered to every attempt regardless.
fn attempt_proof(transport: &str) -> lastconn::Proof {
    lastconn::Proof {
        transport: transport.to_string(),
        profile: std::env::var("AETHER_NOIZE").unwrap_or_else(|_| "firewall".to_string()),
        fragment_tls: std::env::var("AETHER_MASQUE_H2_FRAGMENT").is_ok(),
        ech: !matches!(
            std::env::var("AETHER_ECH").as_deref(),
            Err(_) | Ok("") | Ok("off")
        ),
    }
}

/// The framing a MASQUE attempt is using, as the cache names it.
fn masque_framing() -> &'static str {
    if masque_h2::enabled() {
        "h2"
    } else {
        "h3"
    }
}

/// Forwards a readiness signal, and records the endpoint that earned it.
///
/// The cache used to be written the moment an endpoint was *chosen*, which is
/// before anything has gone through it -- so an address that passed every check
/// and then failed to build a session was stored as the last good one, and the
/// next connect led with it. This fires instead when the tunnel has confirmed
/// end-to-end data, which is the only moment the word "good" is earned.
fn record_on_ready(
    path: String,
    peer: SocketAddr,
    proof: lastconn::Proof,
    onward: &mut Option<tokio::sync::oneshot::Sender<()>>,
) -> Option<tokio::sync::oneshot::Sender<()>> {
    let forward = onward.take();
    let (tx, rx) = tokio::sync::oneshot::channel();
    tokio::spawn(async move {
        // Dropped without firing means the tunnel never carried anything, and
        // there is nothing to remember.
        if rx.await.is_err() {
            return;
        }
        lastconn::save(&path, &peer.to_string(), &proof);
        log::debug!("[+] remembered {peer} as an endpoint that carried traffic");
        if let Some(tx) = forward {
            let _ = tx.send(());
        }
    });
    Some(tx)
}

async fn resolve_ech() -> Option<Vec<u8>> {
    match std::env::var("AETHER_ECH") {
        Ok(v) if v.eq_ignore_ascii_case("auto") || v.eq_ignore_ascii_case("require") => {
            match dns::fetch_ech_config().await {
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
            }
        }
        Ok(b64) if !b64.is_empty() && !b64.eq_ignore_ascii_case("off") => {
            match tls::decode_ech_config_list(&b64) {
                Ok(v) => {
                    log::info!("[+] using ECHConfigList from AETHER_ECH");
                    Some(v)
                }
                Err(e) => {
                    log::warn!("[-] bad AETHER_ECH: {e}; continuing without ECH");
                    None
                }
            }
        }
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
        .map(|v| v.min(86_400))
        .unwrap_or(2);
    std::time::Duration::from_secs(secs)
}

fn masque_startup_timeout() -> std::time::Duration {
    let secs = std::env::var("AETHER_MASQUE_STARTUP_SECS")
        .ok()
        .and_then(|v| v.parse::<u64>().ok())
        .filter(|&v| v > 0)
        .map(|v| v.min(86_400))
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
    let probe = masque_probe(identity, ip).await;

    let best = prober::hunt_best_gateway(&probe, mode).await?;
    log::info!(
        "[+] selected MASQUE gateway {}:{} (rtt {:?})",
        best.ip,
        best.port,
        best.rtt
    );
    Ok(SocketAddr::new(best.ip, best.port))
}

async fn masque_probe(identity: &account::Identity, ip: prober::IpScan) -> prober::MasqueProbe {
    prober::MasqueProbe {
        sni: consts::CONNECT_SNI.to_string(),
        authority: quic::default_authority().to_string(),
        path: quic::default_path().to_string(),
        cert_pem: std::sync::Arc::from(identity.cert_pem.clone()),
        key_pem: std::sync::Arc::from(identity.key_pem.clone()),
        ech_config_list: attempt_ech().await.map(std::sync::Arc::from),
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

async fn quick_verify_masque_peer(identity: &account::Identity, peer: SocketAddr) -> bool {
    verify_masque_peer(identity, peer).await.is_ok()
}

/// The parameters [`verify_masque_peer`] would dial with, without dialling.
#[cfg(test)]
async fn verify_params_for_test(
    identity: &account::Identity,
    peer: SocketAddr,
) -> quic::VerifyParams {
    quic::VerifyParams {
        peer,
        sni: consts::CONNECT_SNI.to_string(),
        authority: quic::default_authority().to_string(),
        path: quic::default_path().to_string(),
        cert_pem: identity.cert_pem.clone(),
        key_pem: identity.key_pem.clone(),
        ech_config_list: attempt_ech().await,
        noize: noize_config(),
        timeout: std::time::Duration::from_secs(5),
        local_ipv4: parse_local_v4(&identity.ipv4),
    }
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
        ech_config_list: attempt_ech().await,
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
            expected_pins: consts::masque_pins().iter().map(|p| p.to_vec()).collect(),
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
        scan_settings_from_env()
    } else {
        let mode_str = select_scan_mode_str("").await;
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

        last_good_peer = Some(peer);

        // Recorded after the session, not before it. A tunnel that closes
        // having carried traffic returns Ok; one that never established
        // returns an error naming why, which is not a memory worth keeping.
        match run_masque_tunnel(&identity, peer, ech.clone(), listen).await {
            Ok(()) => {
                if forced.is_none() {
                    lastconn::save(
                        &lastconn_path,
                        &peer.to_string(),
                        &attempt_proof(masque_framing()),
                    );
                }
                log::warn!("[-] MASQUE tunnel closed; reconnecting");
            }
            Err(e) => log::warn!("[-] MASQUE tunnel ended: {e}; reconnecting"),
        }

        tokio::time::sleep(masque_reconnect_delay()).await;
    }
}

struct MasqueHop {
    stack: netstack::StackHandle,
    exit: TunnelExit,
    _ctrl: tokio::sync::mpsc::Sender<quic::Control>,
    _guard: TaskGuard,
}

#[allow(clippy::too_many_arguments)]
async fn establish_masque(
    identity: &account::Identity,
    peer: SocketAddr,
    ech: Option<Vec<u8>>,
    h2: bool,
    mtu: usize,
    datagram: usize,
    version_bait: bool,
    startup: std::time::Duration,
    label: &str,
) -> Result<MasqueHop> {
    let (chans, internals) = quic::channels();
    let quic::Channels {
        outbound_tx,
        inbound_rx,
        ctrl_tx,
    } = chans;

    let stack = netstack::spawn(&identity.ipv4, &identity.ipv6, mtu, inbound_rx, outbound_tx)?;

    let mut guard = TaskGuard::new();

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
    guard.push(bridge_task.abort_handle());

    let (ready_tx, ready_rx) = tokio::sync::oneshot::channel::<()>();

    let tunnel_task = if h2 {
        let h2cfg = masque_h2::H2TunnelConfig {
            peer,
            sni: consts::CONNECT_SNI.to_string(),
            authority: quic::default_authority().to_string(),
            path: quic::default_path().to_string(),
            cert_pem: identity.cert_pem.clone(),
            key_pem: identity.key_pem.clone(),
            local_ipv4: parse_local_v4(&identity.ipv4),
            quiet: false,
            pin_endpoint: true,
            expected_pins: consts::masque_pins().iter().map(|p| p.to_vec()).collect(),
        };
        log::info!("[+] [{label}] MASQUE transport: HTTP/2 (TCP) to {peer} (inner mtu {mtu})");
        tokio::spawn(masque_h2::run(
            h2cfg,
            internals,
            Some(addr_tx),
            Some(ready_tx),
        ))
    } else {
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
            max_datagram: datagram,
            version_bait,
        };
        log::info!(
            "[+] [{label}] MASQUE transport: HTTP/3 (QUIC) to {peer} (inner mtu {mtu}, datagram {})",
            cfg.datagram_budget()
        );
        tokio::spawn(quic::run(cfg, internals, Some(addr_tx), Some(ready_tx)))
    };
    guard.push(tunnel_task.abort_handle());

    let startup_timeout = startup;
    match tokio::time::timeout(startup_timeout, ready_rx).await {
        Ok(Ok(())) => {}
        Ok(Err(_)) => {
            let joined = tunnel_task.await;
            let msg = match joined {
                Ok(Ok(())) => format!("[{label}] tunnel exited before validation"),
                Ok(Err(e)) => format!("[{label}] tunnel failed before validation: {e}"),
                Err(e) => format!("[{label}] tunnel task join error: {e}"),
            };
            return Err(AetherError::Other(msg));
        }
        Err(_) => {
            tunnel_task.abort();
            let _ = tunnel_task.await;
            return Err(AetherError::Other(format!(
                "[{label}] tunnel startup timed out after {startup_timeout:?}"
            )));
        }
    }

    Ok(MasqueHop {
        stack,
        exit: tunnel_task,
        _ctrl: ctrl_tx,
        _guard: guard,
    })
}

async fn run_masque_tunnel(
    identity: &account::Identity,
    peer: SocketAddr,
    ech: Option<Vec<u8>>,
    listen: SocketAddr,
) -> Result<()> {
    let h2 = masque_h2::enabled();
    let dial = if h2 { masque_h2::h2_peer(peer) } else { peer };

    let mut hop = establish_masque(
        identity,
        dial,
        ech,
        h2,
        masque_tunnel_mtu(),
        quic::MAX_DATAGRAM_SIZE,
        true,
        masque_startup_timeout(),
        "masque",
    )
    .await?;

    let socks_listener = socks::bind_listener("socks5", listen).await?;
    let http_listener = bind_http_proxy().await?;

    let mut tasks = TaskGuard::new();

    let socks_stack = hop.stack.clone();
    let socks_task = tokio::spawn(async move { socks::serve(socks_listener, socks_stack).await });
    tasks.push(socks_task.abort_handle());

    let http_task = spawn_http_proxy(http_listener, &hop.stack);
    if let Some(task) = &http_task {
        tasks.push(task.abort_handle());
    }

    let tunnel_result = (&mut hop.exit).await;

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

const MASQUE_DATAGRAM_OVERHEAD: usize = quic::MAX_DATAGRAM_SIZE - MASQUE_MTU;

fn mim_inner_budget(outer_mtu: usize, inner_peer: SocketAddr, h2: bool) -> (usize, usize) {
    if h2 {
        let mtu = outer_mtu.saturating_sub(100).clamp(576, 1500);
        return (quic::MAX_DATAGRAM_SIZE, mtu);
    }

    let headers = if inner_peer.is_ipv4() { 28 } else { 48 };
    let datagram = outer_mtu
        .saturating_sub(headers)
        .clamp(quic::MIN_DATAGRAM_SIZE, quic::MAX_DATAGRAM_SIZE);
    let mtu = datagram
        .saturating_sub(MASQUE_DATAGRAM_OVERHEAD)
        .clamp(576, 1500);
    (datagram, mtu)
}

const MASQUE_INNER_PORT: u16 = 443;
const MIM_INNER_TRIES: usize = 6;

fn mim_inner_startup() -> std::time::Duration {
    masque_startup_timeout().min(std::time::Duration::from_secs(12))
}

fn inner_masque_candidates(outer: SocketAddr, count: usize) -> Vec<SocketAddr> {
    use rand::RngExt;

    let mut rng = rand::rng();
    let mut out: Vec<SocketAddr> = Vec::new();

    match outer.ip() {
        IpAddr::V4(v4) => {
            let octets = v4.octets();
            let mut hosts: Vec<u8> = (1..=254u8).filter(|host| *host != octets[3]).collect();
            for index in (1..hosts.len()).rev() {
                let other = rng.random_range(0..=index);
                hosts.swap(index, other);
            }
            for host in hosts.into_iter().take(count) {
                let ip = Ipv4Addr::new(octets[0], octets[1], octets[2], host);
                out.push(SocketAddr::new(IpAddr::V4(ip), MASQUE_INNER_PORT));
            }
        }
        IpAddr::V6(v6) => {
            let mut segments = v6.segments();
            let last = segments[7];
            let mut seen: HashSet<u16> = HashSet::new();
            while out.len() < count && seen.len() < count * 8 {
                let candidate = rng.random_range(1..=u16::MAX);
                if candidate == last || !seen.insert(candidate) {
                    continue;
                }
                segments[7] = candidate;
                out.push(SocketAddr::new(
                    IpAddr::V6(std::net::Ipv6Addr::from(segments)),
                    MASQUE_INNER_PORT,
                ));
            }
        }
    }

    out
}

async fn spawn_tcp_forwarder(
    outer: &netstack::StackHandle,
    remote: SocketAddr,
) -> Result<(SocketAddr, TaskGuard)> {
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await?;
    let local = listener.local_addr()?;
    let stack = outer.clone();

    let task = tokio::spawn(async move {
        let mut clients = tokio::task::JoinSet::new();
        loop {
            tokio::select! {
                accepted = listener.accept() => {
                    let Ok((sock, _)) = accepted else { break };
                    let stack = stack.clone();
                    clients.spawn(async move {
                        match stack.open_tcp(remote).await {
                            Ok(conn) => {
                                let (sender, from_stack) = conn.into_split();
                                socks::relay_tunneled(
                                    sock,
                                    sender,
                                    from_stack,
                                    socks::half_close_linger(),
                                )
                                .await;
                            }
                            Err(e) => log::warn!(
                                "[-] the inner hop could not reach {remote} through the outer tunnel: {e}"
                            ),
                        }
                    });
                }
                Some(_) = clients.join_next(), if !clients.is_empty() => {}
            }
        }
    });

    Ok((local, TaskGuard(vec![task.abort_handle()])))
}

async fn run_masque_in_masque(
    primary: &account::Identity,
    secondary: &account::Identity,
    peer: SocketAddr,
    inner_peers: &[SocketAddr],
    ech: Option<Vec<u8>>,
    listen: SocketAddr,
) -> Result<()> {
    let h2 = masque_h2::enabled();
    let outer_mtu = masque_tunnel_mtu();

    log::info!("[*] establishing outer MASQUE tunnel to {peer}...");
    let mut outer = establish_masque(
        primary,
        peer,
        ech,
        h2,
        outer_mtu,
        quic::MAX_DATAGRAM_SIZE,
        true,
        masque_startup_timeout(),
        "outer",
    )
    .await?;

    let mut chosen: Option<(SocketAddr, MasqueHop, TaskGuard)> = None;

    for inner_peer in inner_peers
        .iter()
        .copied()
        .filter(|candidate| candidate.ip() != peer.ip())
    {
        let (inner_datagram, inner_mtu) = mim_inner_budget(outer_mtu, inner_peer, h2);

        if !h2 && inner_datagram + 28 > outer_mtu {
            log::warn!(
                "[-] the outer link carries {outer_mtu} bytes, too little for an inner quic \
                 datagram; raise AETHER_MASQUE_MTU or use --h2 for both hops"
            );
        }

        let (forwarder, forwarder_guard) = if h2 {
            spawn_tcp_forwarder(&outer.stack, inner_peer).await?
        } else {
            spawn_udp_forwarder(&outer.stack, inner_peer).await?
        };
        log::info!(
            "[*] trying inner MASQUE edge {inner_peer} through the outer tunnel via {forwarder}"
        );

        match establish_masque(
            secondary,
            forwarder,
            None,
            h2,
            inner_mtu,
            inner_datagram,
            false,
            mim_inner_startup(),
            "inner",
        )
        .await
        {
            Ok(hop) => {
                log::info!("[+] inner MASQUE tunnel established through {inner_peer}");
                chosen = Some((inner_peer, hop, forwarder_guard));
                break;
            }
            Err(e) => log::info!(
                "[-] inner edge {inner_peer} does not serve masque from inside the tunnel: {e}"
            ),
        }
    }

    let Some((inner_peer, mut inner, _forwarder_guard)) = chosen else {
        return Err(AetherError::Other(
            "no inner masque edge answered through the outer tunnel".into(),
        ));
    };

    let socks_listener = socks::bind_listener("socks5", listen).await?;
    let http_listener = bind_http_proxy().await?;

    let mut tasks = TaskGuard::new();
    let http_task = spawn_http_proxy(http_listener, &inner.stack);
    if let Some(task) = &http_task {
        tasks.push(task.abort_handle());
    }

    let socks_stack = inner.stack.clone();
    let mut socks_task =
        tokio::spawn(async move { socks::serve(socks_listener, socks_stack).await });
    tasks.push(socks_task.abort_handle());

    log::info!("[+] masque-in-masque ready: {peer} (outer) and {inner_peer} (inner)");

    #[derive(PartialEq)]
    enum Winner {
        Outer,
        Inner,
        Socks,
    }

    let (outcome, winner) = tokio::select! {
        result = &mut outer.exit => (join_outcome("outer masque tunnel", result), Winner::Outer),
        result = &mut inner.exit => (join_outcome("inner masque tunnel", result), Winner::Inner),
        result = &mut socks_task => (join_outcome("socks5 server", result), Winner::Socks),
    };

    if winner != Winner::Outer {
        outer.exit.abort();
        let _ = (&mut outer.exit).await;
    }
    if winner != Winner::Inner {
        inner.exit.abort();
        let _ = (&mut inner.exit).await;
    }
    if winner != Winner::Socks {
        socks_task.abort();
        let _ = (&mut socks_task).await;
    }

    outcome
}

async fn run_mim(
    primary: account::Identity,
    secondary: account::Identity,
    ech: Option<Vec<u8>>,
    listen: SocketAddr,
) -> Result<()> {
    let pinned = mim_endpoints_from_env()?;

    match (pinned.outer, pinned.inner) {
        (Some(outer), Some(inner)) => log::info!(
            "[+] masque-in-masque endpoints given by hand: {outer} (outer) and {inner} (inner); the scan is skipped"
        ),
        (Some(outer), None) => log::info!(
            "[+] outer masque-in-masque endpoint given by hand: {outer}; the inner one is chosen for you"
        ),
        (None, Some(inner)) => log::info!(
            "[+] inner masque-in-masque endpoint given by hand: {inner}; scanning for the outer one"
        ),
        (None, None) => {}
    }

    let mut outer_peer = pinned.outer;
    let mut inner_peer = pinned.inner;
    let mut consecutive_fails: u32 = 0;
    let mut scan_settings: Option<(String, prober::IpScan)> = None;
    const MAX_CONSECUTIVE_FAILS: u32 = 2;

    loop {
        if consecutive_fails >= MAX_CONSECUTIVE_FAILS {
            if pinned.outer.is_none() {
                if let Some(peer) = outer_peer.take() {
                    log::warn!(
                        "[-] outer edge {peer} failed {consecutive_fails} times in a row; rescanning"
                    );
                }
            }
            if pinned.inner.is_none() {
                if let Some(peer) = inner_peer.take() {
                    log::warn!(
                        "[-] inner edge {peer} failed {consecutive_fails} times in a row; trying another"
                    );
                }
            }
            consecutive_fails = 0;
        }

        let outer = match outer_peer {
            Some(peer) => peer,
            None => {
                let (mode_str, ip) = match &scan_settings {
                    Some(settings) => settings.clone(),
                    None => {
                        let mode_str = select_scan_mode_str(MIM_MANUAL_TIP).await;
                        let ip = select_ip_version().await;
                        scan_settings.insert((mode_str, ip)).clone()
                    }
                };

                match hunt_masque_peer(&primary, &mode_str, ip).await {
                    Ok(peer) => peer,
                    Err(e) => {
                        log::warn!("[-] no usable MASQUE gateway found: {e}; rescanning shortly");
                        tokio::time::sleep(masque_reconnect_delay()).await;
                        continue;
                    }
                }
            }
        };

        let candidates = match inner_peer {
            Some(peer) => vec![peer],
            None => inner_masque_candidates(outer, MIM_INNER_TRIES),
        };

        if candidates.is_empty() {
            return Err(AetherError::Other(
                "no second masque edge is known for the inner hop".into(),
            ));
        }

        outer_peer = Some(outer);

        match run_masque_in_masque(
            &primary,
            &secondary,
            outer,
            &candidates,
            ech.clone(),
            listen,
        )
        .await
        {
            Ok(()) => log::warn!("[-] masque-in-masque tunnel closed; reconnecting"),
            Err(e) => log::warn!("[-] masque-in-masque tunnel ended: {e}; reconnecting"),
        }
        consecutive_fails += 1;

        tokio::time::sleep(masque_reconnect_delay()).await;
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
        .map(|v| v.min(86_400))
        .unwrap_or(2);
    std::time::Duration::from_secs(secs)
}

fn wg_endpoint_cooldown() -> std::time::Duration {
    let secs = std::env::var("AETHER_WG_ENDPOINT_COOLDOWN_SECS")
        .ok()
        .and_then(|v| v.parse::<u64>().ok())
        .filter(|&v| v > 0)
        .map(|v| v.min(86_400))
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
        scan_settings_from_env()
    } else {
        let mode_str = select_scan_mode_str("").await;
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
                            None => {
                                log::warn!(
                                    "[-] forced peer {peer} failed with every aethernoize profile; retrying shortly"
                                );
                                tokio::time::sleep(wg_reconnect_delay()).await;
                                continue;
                            }
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

        let is_same_peer_as_before = last_good.as_ref().map(|(p, _, _)| *p) == Some(peer);
        if !is_same_peer_as_before {
            consecutive_fails_on_peer = 0;
        }
        let remembered_profile = profile_name.clone();
        last_good = Some((peer, profile.clone(), profile_name));

        match run_wireguard_tunnel(identity.clone(), peer, profile, listen).await {
            Ok(()) => {
                if forced.is_none() {
                    lastconn::save(
                        &lastconn_path,
                        &peer.to_string(),
                        &lastconn::Proof {
                            profile: remembered_profile,
                            ..attempt_proof("wg")
                        },
                    );
                }
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
        .map(|v| v.min(86_400))
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

    let socks_listener = socks::bind_listener("socks5", listen).await?;
    let http_listener = bind_http_proxy().await?;

    let socks_stack = stack.clone();
    let socks_task = tokio::spawn(async move { socks::serve(socks_listener, socks_stack).await });
    tasks.push(socks_task.abort_handle());

    let http_task = spawn_http_proxy(http_listener, &stack);
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

async fn bind_http_proxy() -> Result<Option<tokio::net::TcpListener>> {
    match http_proxy_listen() {
        Some(listen) => Ok(Some(socks::bind_listener("http proxy", listen).await?)),
        None => Ok(None),
    }
}

fn spawn_http_proxy(
    listener: Option<tokio::net::TcpListener>,
    stack: &netstack::StackHandle,
) -> Option<TunnelExit> {
    let listener = listener?;
    let stack = stack.clone();
    Some(tokio::spawn(async move {
        socks::serve_http(listener, stack).await
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
                    {
                        let mut known = up_peer.lock().await;
                        match *known {
                            Some(peer) if peer != from => continue,
                            Some(_) => {}
                            None => *known = Some(from),
                        }
                    }
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

    let mut tasks = TaskGuard::new();

    log::info!("[*] establishing outer WARP tunnel to {peer}...");
    let (outer_stack, mut outer_exit) =
        establish_wg(&primary, peer, WIREGUARD_MTU, true, 5, "outer").await?;
    tasks.push(outer_exit.abort_handle());

    let (forwarder, _forwarder_guard) = spawn_udp_forwarder(&outer_stack, inner_peer).await?;
    log::info!("[+] inner endpoint {inner_peer} tunneled through outer warp via {forwarder}");

    log::info!("[*] establishing inner WARP tunnel (warp-in-warp)...");
    let (inner_stack, mut inner_exit) =
        establish_wg(&secondary, forwarder, INNER_MTU, false, 20, "inner").await?;
    tasks.push(inner_exit.abort_handle());

    let socks_listener = socks::bind_listener("socks5", listen).await?;
    let http_listener = bind_http_proxy().await?;

    let http_task = spawn_http_proxy(http_listener, &inner_stack);
    if let Some(task) = &http_task {
        tasks.push(task.abort_handle());
    }
    let mut socks_task =
        tokio::spawn(async move { socks::serve(socks_listener, inner_stack).await });
    tasks.push(socks_task.abort_handle());

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

/// Shown above the scan mode question on warp-in-warp, where the addresses can
/// be handed over instead of hunted for.
const MIM_MANUAL_TIP: &str = "\n(tip: you can skip this scan and give the two masque hops yourself:\n        aether --mim --mim-outer <ip:port> --mim-inner <ip:port>\n      the port is required, and naming just the outer one lets aether pick\n      the inner edge for you)\n";

const WIW_MANUAL_TIP: &str = "\n(tip: you can skip this scan and give the two gool hops yourself:\n        aether --gool --wiw-outer <ip:port> --wiw-inner <ip:port>\n      the port is required, and naming just one of the two lets the scan\n      find the other)\n";

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

/// `tip` is printed above the question, for whatever the caller wants to point
/// out about scanning in the mode it is about to run.
async fn select_scan_mode_str(tip: &str) -> String {
    if let Ok(v) = std::env::var("AETHER_SCAN") {
        return v;
    }

    let answer = prompt_line(&format!("{tip}{SCAN_MODE_PROMPT}")).await;

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
        let (tor_entries, last) = if cfg!(feature = "tor") {
            (
                "  [5] Tor alone, with no warp under it\n  \
                 [6] Tor and warp chained, either way round\n"
                    .to_string(),
                7,
            )
        } else {
            (String::new(), 5)
        };

        let zero_trust_key = last.to_string();
        let zero_trust = match team_scope() {
            Some(team) => {
                format!("  [{last}] Zero Trust: signed in to {team}, pick another team\n")
            }
            None => {
                format!("  [{last}] Zero Trust: sign in to an organization (WARP for teams)\n")
            }
        };

        let answer = prompt_line(&format!(
            "\nProtocol:\n  [1] MASQUE (modern, QUIC/H3, default)\n  \
             [2] WireGuard (classic, faster)\n  [3] WARP-in-WARP / gool\n  \
             [4] MASQUE-in-MASQUE (two masque hops, for a different exit address)\n\
             {tor_entries}{zero_trust}Choose [1-{last}] (default 1): "
        ))
        .await;

        match answer.as_deref() {
            Some("2") => return Protocol::WireGuard,
            Some("3") => return Protocol::WarpInWarp,
            Some("4") => return Protocol::MasqueInMasque,
            Some(choice) if choice == zero_trust_key => {
                enrol_zero_trust(base).await;
                continue;
            }
            Some("5") if cfg!(feature = "tor") => {
                std::env::set_var("AETHER_TOR", "only");
                return Protocol::Masque;
            }
            Some("6") if cfg!(feature = "tor") => return select_tor_chain().await,
            _ => return Protocol::Masque,
        }
    }
}

async fn select_tor_chain() -> Protocol {
    let answer = prompt_line(
        "\nWhich way round?\n  \
         [1] tor inside warp: you, warp, tor, the internet. The exit is a tor exit, and a \
         network that blocks tor never sees it (default)\n  \
         [2] warp inside tor: you, tor, warp, the internet. The exit is a warp exit reached \
         from a tor exit, and your network never sees warp\n\
         Choose [1-2] (default 1): ",
    )
    .await;

    if matches!(answer.as_deref(), Some("2")) {
        std::env::set_var("AETHER_TOR", "reverse");
        log::info!("[*] the tunnel will be dialled through tor, on the http/2 carrier");
        return Protocol::Masque;
    }

    std::env::set_var("AETHER_TOR", "chain");
    log::info!("[*] tor will be carried inside the tunnel");
    select_chain_carrier().await
}

async fn select_chain_carrier() -> Protocol {
    let answer = prompt_line(
        "\nWhat carries tor?\n  \
         [1] MASQUE, over quic/h3 or http/2, asked next (default)\n  \
         [2] WireGuard, warp over udp\n  \
         [3] WARP-in-WARP / gool\n  \
         [4] MASQUE-in-MASQUE\n\
         Choose [1-4] (default 1): ",
    )
    .await;

    match answer.as_deref() {
        Some("2") => Protocol::WireGuard,
        Some("3") => Protocol::WarpInWarp,
        Some("4") => Protocol::MasqueInMasque,
        _ => Protocol::Masque,
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Protocol {
    Masque,
    WireGuard,
    WarpInWarp,
    MasqueInMasque,
}

impl Protocol {
    fn parse(s: &str) -> Protocol {
        match s.trim().to_lowercase().as_str() {
            "wg" | "wireguard" => Protocol::WireGuard,
            "gool" | "wiw" | "warp-in-warp" | "warpinwarp" => Protocol::WarpInWarp,
            "mim" | "m2" | "masque-in-masque" | "masqueinmasque" => Protocol::MasqueInMasque,
            _ => Protocol::Masque,
        }
    }

    fn label(&self) -> &'static str {
        match self {
            Protocol::Masque => "MASQUE",
            Protocol::WireGuard => "WireGuard",
            Protocol::WarpInWarp => "WARP-in-WARP (gool)",
            Protocol::MasqueInMasque => "MASQUE-in-MASQUE",
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

fn scan_settings_from_env() -> (String, prober::IpScan) {
    let mode = std::env::var("AETHER_SCAN").unwrap_or_default();
    let ip = std::env::var("AETHER_IP")
        .map(|value| prober::IpScan::parse(&value))
        .unwrap_or(prober::IpScan::V4);
    (mode, ip)
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

    /// A device that has never been enrolled, which is what a WireGuard slot
    /// holds. A certificate on one of those is a contradiction now: it is the
    /// mark of an enrolment, and an enrolment is what takes the WireGuard key
    /// away.
    fn wireguard_identity(device: &str) -> account::Identity {
        account::Identity {
            cert_pem: Vec::new(),
            key_pem: Vec::new(),
            cert_issued_at: 0,
            ..sample_identity(device)
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
        config::save(base, &wireguard_identity("device-one")).unwrap();

        let payload = export_identity(base).unwrap();

        // The point of the whole feature: a reinstall starts here instead of at
        // a registration Cloudflare may refuse.
        let restored = scratch("import").join("aether.toml");
        let restored = restored.to_str().unwrap();
        import_identity(restored, &payload).unwrap();

        let identity = config::load(restored).unwrap().unwrap();
        assert_eq!("device-one", identity.device_id);
        assert_eq!([3u8; 32], identity.wg_private_key);
        assert_eq!([5u8; 32], identity.wg_peer_public_key);

        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn the_nested_tunnels_second_account_travels_too() {
        isolated();
        let dir = scratch("export-two");
        let base = dir.join("aether.toml");
        let base = base.to_str().unwrap();
        config::save(base, &wireguard_identity("outer")).unwrap();
        config::save(
            &derive_sibling_path(base, "secondary"),
            &wireguard_identity("inner"),
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

    /// The backup the default install could not take.
    ///
    /// The transport starts on MASQUE, so an install that was never switched to
    /// WireGuard has only `aether-masque.toml` -- and format 1 looked at
    /// `aether.toml` alone and reported that there was nothing to export. The
    /// one defence against losing a registration was missing from exactly the
    /// configuration almost everybody runs.
    #[test]
    fn an_install_that_has_only_ever_run_masque_can_be_backed_up() {
        isolated();
        let dir = scratch("export-masque-only");
        let base = dir.join("aether.toml");
        let base = base.to_str().unwrap();
        config::save(&masque_config_path(base), &sample_identity("masque-one")).unwrap();

        let payload = export_identity(base).expect("a masque install has an identity to export");

        let target = scratch("import-masque-only").join("aether.toml");
        let target = target.to_str().unwrap();
        import_identity(target, &payload).unwrap();

        assert_eq!(
            "masque-one",
            config::load(&masque_config_path(target))
                .unwrap()
                .expect("the masque identity did not travel")
                .device_id,
        );
        assert!(
            config::peek(target).is_none(),
            "a wireguard file was invented out of a masque backup",
        );

        let _ = std::fs::remove_dir_all(&dir);
    }

    /// All four accounts travel, and land back in their own files.
    #[test]
    fn every_account_an_install_holds_travels_in_the_backup() {
        isolated();
        let dir = scratch("export-all");
        let base = dir.join("aether.toml");
        let base = base.to_str().unwrap();
        let masque = masque_config_path(base);

        config::save(base, &wireguard_identity("warp-outer")).unwrap();
        config::save(
            &derive_sibling_path(base, "secondary"),
            &wireguard_identity("warp-inner"),
        )
        .unwrap();
        config::save(&masque, &sample_identity("masque-outer")).unwrap();
        config::save(
            &derive_sibling_path(&masque, "secondary"),
            &sample_identity("masque-inner"),
        )
        .unwrap();

        let payload = export_identity(base).unwrap();

        let target = scratch("import-all").join("aether.toml");
        let target = target.to_str().unwrap();
        import_identity(target, &payload).unwrap();

        let target_masque = masque_config_path(target);
        for (path, expected) in [
            (target.to_string(), "warp-outer"),
            (derive_sibling_path(target, "secondary"), "warp-inner"),
            (target_masque.clone(), "masque-outer"),
            (
                derive_sibling_path(&target_masque, "secondary"),
                "masque-inner",
            ),
        ] {
            assert_eq!(
                expected,
                config::load(&path)
                    .unwrap()
                    .unwrap_or_else(|| panic!("{expected} did not travel"))
                    .device_id,
            );
        }

        let _ = std::fs::remove_dir_all(&dir);
    }

    /// A backup written by an older build still restores.
    ///
    /// Someone exported before updating precisely because they were about to
    /// reinstall. Refusing their file on the way back in would waste the
    /// registration the backup existed to carry.
    #[test]
    fn a_backup_in_the_older_format_is_still_read() {
        isolated();
        let dir = scratch("import-v1");
        let target = dir.join("aether.toml");
        let target = target.to_str().unwrap();

        let mut payload = String::from("version = 1\ndevice_id = \"old-backup\"\n\n[identity]\n");
        payload.push_str(&config::to_text(&wireguard_identity("old-backup")).unwrap());

        import_identity(target, &payload).expect("a format 1 backup must still import");
        assert_eq!(
            "old-backup",
            config::load(target).unwrap().unwrap().device_id,
        );

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
        config::save(base, &wireguard_identity("current")).unwrap();

        let payload = export_identity(base).unwrap().replace(
            &format!("version = {IDENTITY_EXPORT_VERSION}"),
            "version = 99",
        );
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

    /// A wait that has not run out is served here, not spent at Cloudflare.
    ///
    /// The attempt is what costs the allowance, so an attempt made while the
    /// last one's wait is still running spends it to learn something already
    /// on disk. No network is reached in this test, which is the point.
    #[tokio::test]
    async fn a_registration_is_refused_here_while_the_wait_runs() {
        isolated();
        let dir = scratch("budget");
        let base = dir.join("aether.toml");
        let base = base.to_str().unwrap();

        let mut store = identity::Store::default();
        store.registration.failed(
            account::now_unix(),
            "registration: too many registrations from this address",
            Some(1_800),
        );
        identity::save(&store_path(base), &store).unwrap();

        let site = identity_site(base, identity::Slot::Wireguard);
        let error = load_or_provision_warp(&site).await.unwrap_err();

        assert!(
            matches!(error, AetherError::RegistrationOnHold { .. }),
            "expected the wait to be served here, got {error}",
        );
        assert!(
            error.to_string().contains("too many registrations"),
            "the reason Cloudflare gave has to survive: {error}",
        );

        let _ = std::fs::remove_dir_all(&dir);
    }

    /// A backup of an install 1.8.0 had already broken must not restore the break.
    ///
    /// One device in both halves, with the certificate on the MASQUE one: the
    /// WireGuard key it also carries was overwritten when that certificate was
    /// enrolled. Restoring it faithfully would hand the next install a key
    /// nothing answers, and the three-minute search that goes with it.
    #[test]
    fn a_backup_holding_one_device_in_both_families_is_repaired_on_the_way_in() {
        isolated();
        let dir = scratch("import-broken");
        let target = dir.join("aether.toml");
        let target = target.to_str().unwrap();

        let mut payload = format!(
            "version = {IDENTITY_EXPORT_VERSION}
device_id = \"dev-shared\"

[identity]
"
        );
        payload.push_str(&config::to_text(&wireguard_identity("dev-shared")).unwrap());
        payload.push_str(
            "
[masque]
",
        );
        payload.push_str(&config::to_text(&sample_identity("dev-shared")).unwrap());

        import_identity(target, &payload).unwrap();

        let site = identity_site(target, identity::Slot::Wireguard);
        let loaded = identity::load(&site.store_path, &site.legacy).unwrap();

        assert_eq!(
            Some("dev-shared"),
            loaded.store.device_id(identity::Slot::Masque),
            "the certificate is real and cost a registration; it is worth keeping",
        );
        assert_eq!(
            None,
            loaded.store.device_id(identity::Slot::Wireguard),
            "the wireguard half of that device is a key Cloudflare no longer holds",
        );
        assert!(
            config::peek(target).is_none(),
            "and it must not be written to the file an earlier build reads either",
        );

        let _ = std::fs::remove_dir_all(&dir);
    }

    /// The backup is taken from the store, not from the files beside it.
    #[test]
    fn the_backup_carries_what_the_engine_would_actually_dial_with() {
        isolated();
        let dir = scratch("export-from-store");
        let base = dir.join("aether.toml");
        let base = base.to_str().unwrap();

        let identity = wireguard_identity("dev-in-store");
        let mut store = identity::Store::default();
        store.put(
            "dev-in-store",
            identity::device_from(&identity, 1_789_000_000),
        );
        store
            .assign(identity::Slot::Wireguard, "dev-in-store")
            .unwrap();
        identity::save(&store_path(base), &store).unwrap();

        let payload = export_identity(base).expect("the store holds an identity");
        assert!(payload.contains("dev-in-store"), "{payload}");

        let _ = std::fs::remove_dir_all(&dir);
    }

    /// Provisioning behind a carrier asks for nothing it already has.
    ///
    /// This runs while somebody else's tunnel is carrying the user's traffic,
    /// so the one thing it must not do is spend a registration to rediscover an
    /// identity that is already on disk -- or reach the network at all in that
    /// case, which is what makes this testable.
    #[tokio::test]
    async fn provisioning_behind_a_carrier_costs_nothing_when_the_identity_is_held() {
        isolated();
        let dir = scratch("provision-held");
        let base = dir.join("aether.toml");
        let base = base.to_str().unwrap();

        // Issued now, not at the fixture's fixed date. An expiring certificate
        // is a renewal, and a renewal is a request -- correct behaviour, and
        // not what this test is about.
        let identity = account::Identity {
            cert_issued_at: account::now_unix(),
            ..sample_identity("dev-masque")
        };
        let mut store = identity::Store::default();
        store.put(
            "dev-masque",
            identity::device_from(&identity, 1_789_000_000),
        );
        store.assign(identity::Slot::Masque, "dev-masque").unwrap();
        identity::save(&store_path(base), &store).unwrap();

        let config = EmbeddedConfig {
            config_path: base.to_string(),
            protocol: "masque".to_string(),
            listen: "127.0.0.1:0".parse().unwrap(),
            peer: None,
            peer_fallback: false,
            scan_mode: "balanced".to_string(),
            ip_scan: "v4".to_string(),
            access: socks::Access::default(),
        };
        let devices = provision_embedded(&config).await.unwrap();

        assert_eq!(vec!["dev-masque".to_string()], devices);

        let _ = std::fs::remove_dir_all(&dir);
    }

    /// The store answers, and the older build's file is kept current.
    ///
    /// No network reaches this test, which is the point twice over: a usable
    /// device in the store is the whole answer, and the dual write beside it is
    /// what stops a user who goes back to 1.8.1 paying for a registration they
    /// already own.
    #[tokio::test]
    async fn a_device_in_the_store_is_used_without_asking_cloudflare() {
        isolated();
        let dir = scratch("store-answers");
        let base = dir.join("aether.toml");
        let base = base.to_str().unwrap();

        let identity = wireguard_identity("dev-wg");
        let mut store = identity::Store::default();
        store.put("dev-wg", identity::device_from(&identity, 1_789_000_000));
        store.assign(identity::Slot::Wireguard, "dev-wg").unwrap();
        identity::save(&store_path(base), &store).unwrap();

        let site = identity_site(base, identity::Slot::Wireguard);
        let found = load_or_provision_warp(&site).await.unwrap();

        assert_eq!("dev-wg", found.device_id);
        assert_eq!(
            "dev-wg",
            config::peek(&site.path)
                .expect("the file an earlier build reads was not written")
                .device_id,
        );

        let _ = std::fs::remove_dir_all(&dir);
    }

    /// Adoption is gone, and its absence is the point.
    ///
    /// Filling MASQUE's file by copying WireGuard's saved a registration and
    /// cost the WireGuard key, because enrolling the copy revoked it on the
    /// device both files described. The identity store cannot express that --
    /// one device, one record, and no slot may hold another family's device --
    /// so each family registers its own account. The cost is the same as 1.8.1
    /// paid anyway: one extra registration, once, since there the WireGuard
    /// side re-provisioned instead.
    ///
    /// `identity::tests::migrating_an_install_broken_by_an_earlier_build_repairs_it`
    /// covers what happens to an install that was already adopted.
    #[test]
    fn nothing_adopts_another_protocols_identity_any_more() {
        isolated();
        let dir = scratch("no-adoption");
        let base = dir.join("aether.toml");
        let base = base.to_str().unwrap();
        // An install that has only ever run WireGuard, which is precisely what
        // adoption used to reach for.
        config::save(base, &wireguard_identity("wireguard-only")).unwrap();

        let legacy = identity_slots(base).to_vec();
        let loaded = identity::load(&store_path(base), &legacy).unwrap();

        assert_eq!(
            Some("wireguard-only"),
            loaded.store.device_id(identity::Slot::Wireguard),
        );
        assert_eq!(
            None,
            loaded.store.device_id(identity::Slot::Masque),
            "masque took over the wireguard device, which is what revokes its key",
        );

        let _ = std::fs::remove_dir_all(&dir);
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

    /// The bug that broke 1.8, in the form the code can check.
    ///
    /// Separate files were not enough. An install that had only ever run
    /// WireGuard has its identity *copied* into MASQUE's file and enrolled
    /// there, and the WireGuard file is never told -- so it goes on offering a
    /// key Cloudflare threw away, every endpoint answers with silence, and the
    /// search reports the network as dead. Downgrading does not help: the
    /// damage is on the account, and the older build reads the same file and
    /// reaches the same wrong conclusion.
    #[test]
    fn a_device_enrolled_in_another_file_is_not_a_wireguard_identity_any_more() {
        isolated();
        let dir = scratch("enrolled-elsewhere");
        let warp = dir.join("aether.toml");
        let warp = warp.to_str().unwrap();
        let masque = masque_config_path(warp);

        // What an install that only ever ran WireGuard looks like once MASQUE
        // has adopted its identity: the same device in both files, the
        // certificate only on MASQUE's.
        let mut wireguard_only = sample_identity("device-shared");
        wireguard_only.cert_pem = Vec::new();
        wireguard_only.key_pem = Vec::new();
        wireguard_only.cert_issued_at = 0;
        config::save(warp, &wireguard_only).unwrap();
        config::save(&masque, &sample_identity("device-shared")).unwrap();

        assert_eq!(
            Some(masque.clone()),
            device_enrolled_elsewhere(warp, "device-shared"),
            "the enrolment on the sibling has to be found from the wireguard file",
        );

        // And a different device in the same directory is somebody else's
        // enrolment, which says nothing about this one.
        assert_eq!(
            None,
            device_enrolled_elsewhere(warp, "some-other-device"),
            "an unrelated device must not be read as this one being revoked",
        );

        let _ = std::fs::remove_dir_all(&dir);
    }

    /// An expired certificate still means the WireGuard key is gone.
    ///
    /// The guard used to ask `has_masque_credentials`, which is false once the
    /// certificate is old enough to need renewing. Expiry is a statement about
    /// the MASQUE credential, not about the WireGuard key that enrolment
    /// overwrote a year earlier -- and reading it as one would hand the dead
    /// key back to the endpoint search.
    #[test]
    fn an_expired_certificate_still_marks_the_device_as_enrolled() {
        isolated();
        let dir = scratch("expired-mark");
        let warp = dir.join("aether.toml");
        let warp = warp.to_str().unwrap();
        let masque = masque_config_path(warp);

        let mut long_ago = sample_identity("device-old");
        long_ago.cert_issued_at = 1;
        config::save(&masque, &long_ago).unwrap();

        assert!(
            !long_ago.has_masque_credentials(),
            "this certificate is meant to be past renewal",
        );
        assert_eq!(
            Some(masque),
            device_enrolled_elsewhere(warp, "device-old"),
            "an expired certificate is still evidence of an enrolment",
        );

        let _ = std::fs::remove_dir_all(&dir);
    }

    /// The mark records the enrolment without spreading the private key.
    #[test]
    fn marking_a_sibling_writes_the_certificate_and_not_the_secret() {
        isolated();
        let dir = scratch("mark-sibling");
        let warp = dir.join("aether.toml");
        let warp = warp.to_str().unwrap();
        let masque = masque_config_path(warp);

        let mut wireguard_only = sample_identity("device-shared");
        wireguard_only.cert_pem = Vec::new();
        wireguard_only.key_pem = Vec::new();
        wireguard_only.cert_issued_at = 0;
        config::save(warp, &wireguard_only).unwrap();

        let enrolled = sample_identity("device-shared");
        config::save(&masque, &enrolled).unwrap();
        record_enrolment_beside(&masque, &enrolled);

        let marked = config::load(warp)
            .unwrap()
            .expect("the file is still there");
        assert!(
            !marked.cert_pem.is_empty(),
            "the wireguard file was not marked, so it will be used again",
        );
        assert!(
            marked.key_pem.is_empty(),
            "the private key does not belong in a file that will never present it",
        );
        assert_eq!(
            [3u8; 32], marked.wg_private_key,
            "marking must not disturb the rest of the file",
        );

        let _ = std::fs::remove_dir_all(&dir);
    }

    /// A sweep of the directory must not eat what is not an identity.
    ///
    /// `config::load` sets aside anything it cannot parse, and the cache of the
    /// last working endpoint lives right beside these files. Reading the
    /// directory with it would quarantine that cache on every connect, so the
    /// endpoint found last time would be searched for again from scratch.
    #[test]
    fn a_cache_beside_the_identities_is_left_alone() {
        isolated();
        let dir = scratch("sweep-safety");
        let warp = dir.join("aether.toml");
        let warp = warp.to_str().unwrap();
        config::save(warp, &sample_identity("device-one")).unwrap();

        let cache = dir.join("aether-lastconn.toml");
        std::fs::write(
            &cache,
            "peer = \"162.159.192.1:2408\"\nprofile = \"firewall\"\n",
        )
        .unwrap();

        let siblings = identity_siblings(warp);
        assert!(
            siblings.is_empty(),
            "a cache was read as an identity: {siblings:?}",
        );
        assert!(cache.exists(), "the cache was quarantined by a sweep");

        let _ = std::fs::remove_dir_all(&dir);
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
    /// The 1.8.0 failure and its repair, end to end, against the live API.
    ///
    /// Everything else about this defect is checked on files. This checks the
    /// thing the files are about: that Cloudflare really does stop recognising
    /// the WireGuard key when the same device is enrolled for MASQUE, and that
    /// the engine now notices and replaces it instead of handing it back to an
    /// endpoint search that will never get an answer.
    ///
    /// Two registrations against the running address, which is why it is gated
    /// twice. Run it deliberately:
    ///
    ///     AETHER_LIVE_ENROLL_TEST=1 cargo test -p aether adopted_by_masque -- \
    ///         --ignored --nocapture
    #[tokio::test]
    #[ignore = "registers two real warp devices and waits out an edge propagation"]
    async fn a_wireguard_identity_adopted_by_masque_is_replaced_rather_than_reused() {
        if std::env::var("AETHER_LIVE_ENROLL_TEST").is_err() {
            eprintln!("set AETHER_LIVE_ENROLL_TEST=1 to run this");
            return;
        }
        for name in [
            "AETHER_MASQUE_CONFIG",
            "AETHER_WG_CONFIG",
            "AETHER_TEAM",
            "CF_TEAM",
        ] {
            std::env::remove_var(name);
        }

        let dir = std::env::temp_dir().join(format!("aether-repair-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let base = dir.join("aether.toml");
        let base = base.to_str().unwrap();

        // An install that has only ever run WireGuard. Under the store this is
        // the slot rather than the file, which is the whole point of it -- and
        // the scenario below is the one the store makes unrepresentable.
        let warp_site = identity_site(base, identity::Slot::Wireguard);
        let first = load_or_provision_warp(&warp_site)
            .await
            .expect("the first registration");
        eprintln!("[test] wireguard device {}", first.device_id);
        assert!(
            shakes_hands(&first).await.is_ok(),
            "a freshly registered wireguard identity has to hand shake",
        );

        // The user taps a MASQUE profile. It adopts that identity rather than
        // paying for a second, and enrolling the adopted copy is what revokes
        // the WireGuard key on the device both files describe.
        let masque_site = identity_site(base, identity::Slot::Masque);
        let masque = load_or_provision_masque(&masque_site)
            .await
            .expect("the enrolment");
        assert_eq!(
            first.device_id, masque.device_id,
            "this scenario only exists when masque adopts rather than registers",
        );

        // The damage is not immediate: the change takes up to a minute to reach
        // the edge, which is long enough for a check made straight afterwards
        // to come back clean.
        tokio::time::sleep(Duration::from_secs(90)).await;
        assert!(
            shakes_hands(&first).await.is_err(),
            "cloudflare still answers the old key, so this test proves nothing yet",
        );

        // Back to WireGuard. In 1.8.0 this handed the revoked identity straight
        // back, and the endpoint search spent three minutes being met with
        // silence before reporting the network as dead.
        let second = load_or_provision_warp(&warp_site)
            .await
            .expect("the replacement registration");
        eprintln!("[test] replacement device {}", second.device_id);
        assert_ne!(
            first.device_id, second.device_id,
            "the revoked identity was handed back instead of replaced",
        );
        assert!(
            shakes_hands(&second).await.is_ok(),
            "the replacement has to be an identity that actually works",
        );

        let _ = std::fs::remove_dir_all(&dir);
    }

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

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::BTreeMap;

    fn env(pairs: &[(&str, &str)]) -> impl Fn(&str) -> Option<String> {
        let values: BTreeMap<String, String> = pairs
            .iter()
            .map(|(key, value)| (key.to_string(), value.to_string()))
            .collect();
        move |key: &str| values.get(key).cloned()
    }

    #[test]
    fn masque_in_masque_is_selected_by_name() {
        for written in ["mim", "m2", "masque-in-masque", "MIM", "MasqueInMasque"] {
            assert_eq!(
                Protocol::parse(written),
                Protocol::MasqueInMasque,
                "{written}"
            );
        }
    }

    #[test]
    fn the_masque_hops_can_be_named_by_hand() {
        let chosen = mim_endpoints_of(&env(&[
            ("AETHER_MIM_OUTER_PEER", "162.159.192.1:443"),
            ("AETHER_MIM_INNER_PEER", "188.114.96.1:443"),
        ]))
        .expect("both hops");

        assert_eq!(chosen.outer, Some("162.159.192.1:443".parse().unwrap()));
        assert_eq!(chosen.inner, Some("188.114.96.1:443".parse().unwrap()));
    }

    #[test]
    fn the_same_masque_edge_cannot_serve_as_both_hops() {
        assert!(mim_endpoints_of(&env(&[(
            "AETHER_MIM_PEERS",
            "162.159.192.1:443,162.159.192.1:8443"
        )]))
        .is_err());
    }

    #[test]
    fn an_inner_quic_datagram_fits_inside_the_outer_link() {
        let inner: SocketAddr = "162.159.192.1:443".parse().unwrap();
        let (datagram, mtu) = mim_inner_budget(MASQUE_MTU, inner, false);

        assert!(
            datagram + 28 <= MASQUE_MTU,
            "a {datagram} byte datagram plus headers must fit {MASQUE_MTU}"
        );
        assert!(
            datagram >= quic::MIN_DATAGRAM_SIZE,
            "quic needs at least 1200"
        );
        assert!(
            mtu < datagram,
            "the inner link has to leave room for quic itself"
        );
    }

    #[test]
    fn an_ipv6_inner_hop_leaves_room_for_the_bigger_header() {
        let v4: SocketAddr = "162.159.192.1:443".parse().unwrap();
        let v6: SocketAddr = "[2606:4700:d0::a29f:c001]:443".parse().unwrap();

        let (over_v4, _) = mim_inner_budget(MASQUE_MTU, v4, false);
        let (over_v6, _) = mim_inner_budget(MASQUE_MTU, v6, false);
        assert!(
            over_v6 < over_v4,
            "{over_v6} should leave more room than {over_v4}"
        );
        assert!(over_v6 + 48 <= MASQUE_MTU);
    }

    #[test]
    fn an_http2_pair_keeps_the_full_datagram_budget() {
        let inner: SocketAddr = "162.159.192.1:443".parse().unwrap();
        let (datagram, mtu) = mim_inner_budget(H2_TUNNEL_MTU, inner, true);

        assert_eq!(datagram, quic::MAX_DATAGRAM_SIZE);
        assert!(
            mtu < H2_TUNNEL_MTU,
            "the inner link stays under the outer one"
        );
    }

    #[test]
    fn the_inner_edges_come_from_the_range_that_answered() {
        let outer: SocketAddr = "162.159.198.104:8443".parse().unwrap();
        let candidates = inner_masque_candidates(outer, MIM_INNER_TRIES);

        assert_eq!(candidates.len(), MIM_INNER_TRIES);
        for candidate in &candidates {
            assert_ne!(
                candidate.ip(),
                outer.ip(),
                "the inner hop needs its own edge"
            );
            assert_eq!(candidate.port(), MASQUE_INNER_PORT);
            match candidate.ip() {
                IpAddr::V4(v4) => {
                    let outer_octets = match outer.ip() {
                        IpAddr::V4(ip) => ip.octets(),
                        IpAddr::V6(_) => unreachable!(),
                    };
                    assert_eq!(
                        v4.octets()[..3],
                        outer_octets[..3],
                        "same /24 as the edge that worked"
                    );
                }
                IpAddr::V6(_) => unreachable!(),
            }
        }

        let all: HashSet<SocketAddr> = candidates.iter().copied().collect();
        assert_eq!(all.len(), candidates.len(), "candidates must be distinct");
    }

    #[test]
    fn an_ipv6_outer_edge_yields_ipv6_inner_candidates() {
        let outer: SocketAddr = "[2606:4700:d0::a29f:c601]:443".parse().unwrap();
        let candidates = inner_masque_candidates(outer, 4);

        assert_eq!(candidates.len(), 4);
        assert!(candidates.iter().all(|candidate| candidate.is_ipv6()));
        assert!(candidates
            .iter()
            .all(|candidate| candidate.ip() != outer.ip()));
    }

    #[test]
    fn an_address_and_a_port_are_read_together() {
        let peer = parse_endpoint("162.159.192.1:894").expect("address and port");
        assert_eq!(peer, "162.159.192.1:894".parse().unwrap());
    }

    #[test]
    fn an_address_without_a_port_is_refused_rather_than_guessed_at() {
        let message = parse_endpoint("162.159.192.1")
            .expect_err("the port carries too much meaning to be assumed")
            .to_string();
        assert!(
            message.contains("162.159.192.1:2408"),
            "the error should spell out the shape wanted, got: {message}"
        );
    }

    #[test]
    fn an_ipv6_address_without_a_port_is_refused_the_same_way() {
        for written in ["2606:4700:d0::a29f:c001", "[2606:4700:d0::a29f:c001]"] {
            let message = parse_endpoint(written).expect_err(written).to_string();
            assert!(
                message.contains("[2606:4700:d0::a29f:c001]:2408"),
                "the error should bracket the address it suggests, got: {message}"
            );
        }
    }

    #[test]
    fn surrounding_whitespace_is_forgiven() {
        let peer = parse_endpoint("  162.159.192.1:2408  ").expect("padded");
        assert_eq!(peer, "162.159.192.1:2408".parse().unwrap());
    }

    #[test]
    fn ipv6_is_read_when_it_is_bracketed_and_carries_its_port() {
        let peer = parse_endpoint("[2606:4700:d0::a29f:c001]:2408").expect("ipv6 endpoint");
        assert_eq!(peer, "[2606:4700:d0::a29f:c001]:2408".parse().unwrap());
    }

    #[test]
    fn nonsense_is_reported_with_an_example_to_copy() {
        let message = parse_endpoint("not-an-address")
            .expect_err("a hostname is not an address")
            .to_string();
        assert!(
            message.contains("162.159.192.1:2408"),
            "the error should show the shape expected, got: {message}"
        );
    }

    #[test]
    fn an_impossible_port_is_rejected_rather_than_wrapped() {
        assert!(parse_endpoint("162.159.192.1:70000").is_err());
    }

    #[test]
    fn a_pair_may_be_written_with_a_comma_or_a_space() {
        for written in [
            "162.159.192.1:2408,162.159.195.1:500",
            "162.159.192.1:2408, 162.159.195.1:500",
            "162.159.192.1:2408 162.159.195.1:500",
        ] {
            let peers = parse_endpoint_list(written).expect(written);
            assert_eq!(peers.len(), 2, "{written} names two hops");
            assert_eq!(peers[0], "162.159.192.1:2408".parse().unwrap());
            assert_eq!(peers[1], "162.159.195.1:500".parse().unwrap());
        }
    }

    #[test]
    fn a_third_address_is_refused_because_there_are_only_two_hops() {
        let outcome = parse_endpoint_list("1.1.1.1:2408,2.2.2.2:2408,3.3.3.3:2408");
        assert!(outcome.is_err());
    }

    #[test]
    fn the_pair_setting_fills_the_outer_hop_first() {
        let chosen = wiw_endpoints_of(&env(&[(
            "AETHER_WIW_PEERS",
            "162.159.192.1:2408,162.159.195.1:500",
        )]))
        .expect("a usable pair");
        assert_eq!(chosen.outer, Some("162.159.192.1:2408".parse().unwrap()));
        assert_eq!(chosen.inner, Some("162.159.195.1:500".parse().unwrap()));
    }

    #[test]
    fn one_address_pins_the_outer_hop_and_leaves_the_inner_one_to_the_scan() {
        let chosen = wiw_endpoints_of(&env(&[("AETHER_WIW_PEERS", "162.159.192.1:894")]))
            .expect("a single hop");
        assert_eq!(chosen.outer, Some("162.159.192.1:894".parse().unwrap()));
        assert_eq!(chosen.inner, None);
    }

    #[test]
    fn a_hop_named_on_its_own_wins_over_the_pair() {
        let chosen = wiw_endpoints_of(&env(&[
            ("AETHER_WIW_PEERS", "162.159.192.1:2408,162.159.195.1:500"),
            ("AETHER_WIW_INNER_PEER", "188.114.96.1:1701"),
        ]))
        .expect("the inner override");
        assert_eq!(chosen.outer, Some("162.159.192.1:2408".parse().unwrap()));
        assert_eq!(chosen.inner, Some("188.114.96.1:1701".parse().unwrap()));
    }

    #[test]
    fn only_the_inner_hop_may_be_pinned() {
        let chosen = wiw_endpoints_of(&env(&[("AETHER_WIW_INNER_PEER", "188.114.96.1:2408")]))
            .expect("the inner hop");
        assert_eq!(chosen.outer, None);
        assert_eq!(chosen.inner, Some("188.114.96.1:2408".parse().unwrap()));
    }

    #[test]
    fn one_address_cannot_serve_as_both_hops() {
        let outcome = wiw_endpoints_of(&env(&[
            ("AETHER_WIW_OUTER_PEER", "162.159.192.1:2408"),
            ("AETHER_WIW_INNER_PEER", "162.159.192.1:894"),
        ]));

        let message = outcome
            .expect_err("the same edge twice is not warp-in-warp")
            .to_string();
        assert!(
            message.contains("162.159.192.1"),
            "the error should name the address, got: {message}"
        );
    }

    #[test]
    fn asking_for_a_scan_leaves_both_hops_open() {
        for written in ["auto", "scan", "off", "none", "0"] {
            let lookup = env(&[("AETHER_WIW_PEERS", written)]);
            assert!(wiw_scan_requested(&lookup), "{written} means scan");
            assert!(
                wiw_endpoints_of(&lookup).expect(written).is_empty(),
                "{written} should pin nothing"
            );
        }
    }

    #[test]
    fn nothing_set_pins_nothing() {
        assert!(wiw_endpoints_of(&env(&[])).expect("empty").is_empty());
    }

    #[test]
    fn a_malformed_address_is_an_error_rather_than_a_silent_scan() {
        assert!(wiw_endpoints_of(&env(&[("AETHER_WIW_OUTER_PEER", "162.159.192")])).is_err());
    }

    #[test]
    fn a_hop_set_without_a_port_is_an_error_rather_than_a_silent_scan() {
        assert!(wiw_endpoints_of(&env(&[("AETHER_WIW_OUTER_PEER", "162.159.192.1")])).is_err());
    }

    #[test]
    fn the_older_wg_peer_setting_still_names_the_outer_hop() {
        let chosen = wiw_endpoints_with_fallback(&env(&[("AETHER_WG_PEER", "162.159.192.1:2408")]))
            .expect("the documented --gool --wg-peer pairing");
        assert_eq!(chosen.outer, Some("162.159.192.1:2408".parse().unwrap()));
        assert_eq!(chosen.inner, None);
    }

    #[test]
    fn the_generic_peer_setting_is_the_last_fallback() {
        let chosen = wiw_endpoints_with_fallback(&env(&[("AETHER_PEER", "162.159.192.1:2408")]))
            .expect("--peer names the outer hop too");
        assert_eq!(chosen.outer, Some("162.159.192.1:2408".parse().unwrap()));
    }

    #[test]
    fn a_hop_chosen_for_warp_in_warp_beats_the_older_setting() {
        let chosen = wiw_endpoints_with_fallback(&env(&[
            ("AETHER_WG_PEER", "162.159.192.1:2408"),
            ("AETHER_WIW_OUTER_PEER", "188.114.96.1:2408"),
        ]))
        .expect("the warp-in-warp setting is the specific one");
        assert_eq!(chosen.outer, Some("188.114.96.1:2408".parse().unwrap()));
    }

    #[test]
    fn the_older_setting_may_carry_both_hops_at_once() {
        let chosen = wiw_endpoints_with_fallback(&env(&[(
            "AETHER_WG_PEER",
            "162.159.192.1:2408,188.114.96.1:2408",
        )]))
        .expect("a pair");
        assert_eq!(chosen.outer, Some("162.159.192.1:2408".parse().unwrap()));
        assert_eq!(chosen.inner, Some("188.114.96.1:2408".parse().unwrap()));
    }

    #[test]
    fn the_older_setting_does_not_overwrite_a_pinned_inner_hop() {
        let chosen = wiw_endpoints_with_fallback(&env(&[
            ("AETHER_WG_PEER", "162.159.192.1:2408,188.114.96.1:2408"),
            ("AETHER_WIW_INNER_PEER", "162.159.195.1:2408"),
        ]))
        .expect("the inner hop stays where it was put");
        assert_eq!(chosen.outer, Some("162.159.192.1:2408".parse().unwrap()));
        assert_eq!(chosen.inner, Some("162.159.195.1:2408".parse().unwrap()));
    }
}

/// What the checks and the connection agree about, and what the policy allows.
#[cfg(test)]
mod ech_tests {
    use super::*;

    /// Serialised: these set process-wide variables and share one cache.
    fn one_at_a_time() -> std::sync::MutexGuard<'static, ()> {
        static LOCK: std::sync::Mutex<()> = std::sync::Mutex::new(());
        LOCK.lock().unwrap_or_else(|poisoned| poisoned.into_inner())
    }

    async fn with_policy(value: Option<&str>, h2: bool) {
        forget_ech().await;
        match value {
            Some(v) => std::env::set_var("AETHER_ECH", v),
            None => std::env::remove_var("AETHER_ECH"),
        }
        if h2 {
            std::env::set_var("AETHER_MASQUE_HTTP2", "1");
        } else {
            std::env::remove_var("AETHER_MASQUE_HTTP2");
        }
    }

    /// The framing that cannot carry ECH does not go looking for one.
    ///
    /// It was fetched regardless, which is a DNS round trip on the way to a
    /// connect, spent on something no part of the H2 path reads.
    #[tokio::test]
    async fn the_http2_framing_does_not_fetch_a_configuration_it_cannot_use() {
        let _serial = one_at_a_time();
        with_policy(Some("auto"), true).await;
        assert!(attempt_ech().await.is_none());
        with_policy(None, false).await;
    }

    /// Off is the default, and off means nothing is resolved.
    #[tokio::test]
    async fn ech_is_off_unless_it_is_asked_for() {
        let _serial = one_at_a_time();
        with_policy(None, false).await;
        assert!(attempt_ech().await.is_none());
        with_policy(Some("off"), false).await;
        assert!(attempt_ech().await.is_none());
        assert!(ech_policy_satisfied().await.is_ok());
    }

    /// Required means refused, not quietly downgraded.
    ///
    /// Someone who asks for their SNI to be hidden and is silently given a
    /// connection that sends it in the clear has been told nothing, which is the
    /// one outcome worse than failing.
    #[tokio::test]
    async fn requiring_ech_on_a_framing_that_has_none_is_refused_by_name() {
        let _serial = one_at_a_time();
        with_policy(Some("require"), true).await;

        let refused = ech_policy_satisfied().await.unwrap_err().to_string();
        assert!(refused.contains("HTTP/2"), "{refused}");

        with_policy(None, false).await;
    }

    /// The checks and the connection read the same value.
    ///
    /// The defect this exists for: the scanner, the endpoint hunt and every
    /// quick verification passed `None` while the connection resolved ECH and
    /// used it -- so an endpoint could pass validation and then fail to carry a
    /// session, and the failure arrived nowhere near the check meant to prevent
    /// it. One resolver, held for the attempt, is what makes them agree.
    #[tokio::test]
    async fn every_path_reads_one_resolved_value() {
        let _serial = one_at_a_time();
        // A configuration given directly, so the value is real and no lookup is
        // needed: with ECH off everything is None and this test would pass
        // against the defect it exists to catch.
        with_policy(Some("q83vAAAA"), false).await;

        let first = attempt_ech().await;
        assert!(first.is_some(), "the test needs a value to compare");
        let second = attempt_ech().await;
        assert_eq!(first, second);

        let identity = account::Identity {
            device_id: "d".into(),
            access_token: "t".into(),
            cert_pem: b"c".to_vec(),
            key_pem: b"k".to_vec(),
            cert_issued_at: 0,
            ipv4: "172.16.0.2".into(),
            ipv6: "2606:4700:110::1".into(),
            wg_private_key: [1u8; 32],
            wg_peer_public_key: [2u8; 32],
            client_id: [0, 0, 0],
            organization: String::new(),
            gateway_proxy: String::new(),
            assigned_endpoint: String::new(),
            refused: false,
        };
        let probe = masque_probe(&identity, prober::IpScan::V4).await;
        assert_eq!(
            first.clone().map(std::sync::Arc::from),
            probe.ech_config_list,
            "the scanner has to probe with what the connection will dial with",
        );

        // And the quick check every assigned endpoint, cached endpoint and
        // custom endpoint goes through reads it too.
        let checked = verify_params_for_test(&identity, "162.159.198.2:443".parse().unwrap()).await;
        assert_eq!(first, checked.ech_config_list);

        with_policy(None, false).await;
    }
}

/// Why a MASQUE probe fails, made visible.
///
/// The prober turns every failure into `None` at trace level, and the Android
/// logger is capped at info -- so several thousand probes failing for one
/// common reason and several thousand failing because the network is hostile
/// look identical from outside: `no clean endpoint found`. This asks a handful
/// of documented gateways directly and prints what actually came back, with the
/// certificate pins on and then off.
///
///     AETHER_LIVE_PROBE_TEST=1 cargo test -p aether why_masque -- --ignored --nocapture
#[cfg(test)]
mod masque_reachability_tests {
    use super::*;
    use std::time::Duration;

    #[tokio::test]
    #[ignore = "probes the live cloudflare edge from this network"]
    async fn why_masque_probes_fail_here() {
        if std::env::var("AETHER_LIVE_PROBE_TEST").is_err() {
            eprintln!("set AETHER_LIVE_PROBE_TEST=1 to run this");
            return;
        }
        for name in [
            "AETHER_MASQUE_CONFIG",
            "AETHER_WG_CONFIG",
            "AETHER_TEAM",
            "CF_TEAM",
        ] {
            std::env::remove_var(name);
        }

        let dir = std::env::temp_dir().join(format!("aether-probe-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let base = dir.join("aether.toml");
        let base = base.to_str().unwrap();

        std::env::set_var("AETHER_MASQUE_HTTP2", "1");
        let identity = load_or_provision_masque(&identity_site(base, identity::Slot::Masque))
            .await
            .expect("a masque identity");
        eprintln!("[test] identity device {}", identity.device_id);
        // The enrolment reaches the edge in something under a minute, so a
        // probe made straight afterwards can be refused for a reason that has
        // nothing to do with the endpoint.
        eprintln!("[test] waiting 90s for the enrolment to reach the edge");
        tokio::time::sleep(Duration::from_secs(90)).await;

        // What Cloudflare itself says about this device. AccountData drops every
        // field it was not written to know about, and the question here is
        // exactly whether there is a field we are not reading.
        let raw = reqwest::Client::builder()
            .user_agent(consts::UA_REGISTER)
            .timeout(Duration::from_secs(20))
            .build()
            .unwrap()
            .get(format!(
                "{}/{}/reg/{}",
                consts::API_URL,
                consts::API_VERSION,
                identity.device_id
            ))
            .header("CF-Client-Version", consts::CF_CLIENT_VERSION)
            .bearer_auth(&identity.access_token)
            .send()
            .await
            .unwrap()
            .text()
            .await
            .unwrap();
        eprintln!(
            "[test] registration says:
{raw}"
        );

        // The endpoint Cloudflare assigned this device, which is the one the
        // engine should be trying first and currently never tries at all.
        let reg = account::fetch_device(&identity.device_id, &identity.access_token)
            .await
            .expect("the device record");
        let assigned = account::endpoint_from(&reg);
        eprintln!("[test] cloudflare assigned {assigned}");
        let assigned_443 = format!("{assigned}:443");

        // The selection the engine now makes, which is the fix this proves.
        let chosen = assigned_masque_peers(&identity).await;
        eprintln!("[test] engine would try {chosen:?} before searching");
        assert!(
            chosen.contains(&assigned_443.parse::<SocketAddr>().unwrap()),
            "the endpoint Cloudflare names has to be among the ones tried first: {chosen:?}",
        );

        let targets = [
            assigned_443.as_str(),
            // A control: an address from the hard-coded pool that the scan
            // spends its budget on.
            "162.159.192.1:443",
        ];

        for pinned in [true, false] {
            eprintln!("\n[test] ---- pin_endpoint = {pinned} ----");
            for target in targets {
                let cfg = masque_h2::H2TunnelConfig {
                    peer: target.parse().unwrap(),
                    sni: consts::CONNECT_SNI.to_string(),
                    authority: quic::default_authority().to_string(),
                    path: quic::default_path().to_string(),
                    cert_pem: identity.cert_pem.clone(),
                    key_pem: identity.key_pem.clone(),
                    local_ipv4: parse_local_v4(&identity.ipv4),
                    quiet: true,
                    pin_endpoint: pinned,
                    expected_pins: consts::masque_pins().iter().map(|p| p.to_vec()).collect(),
                };
                match masque_h2::verify_h2(&cfg, Duration::from_secs(6)).await {
                    Ok(rtt) => eprintln!("[test] {target} OK rtt={rtt:?}"),
                    Err(error) => eprintln!("[test] {target} FAILED {error}"),
                }
            }
        }

        eprintln!(
            "
[test] ---- h3 / quic ----"
        );
        for target in targets {
            let vp = quic::VerifyParams {
                peer: target.parse().unwrap(),
                sni: consts::CONNECT_SNI.to_string(),
                authority: quic::default_authority().to_string(),
                path: quic::default_path().to_string(),
                cert_pem: identity.cert_pem.clone(),
                key_pem: identity.key_pem.clone(),
                ech_config_list: None,
                noize: noize_config(),
                timeout: Duration::from_secs(6),
                local_ipv4: parse_local_v4(&identity.ipv4),
            };
            match quic::verify_masque(&vp).await {
                Ok(rtt) => eprintln!("[test] {target} OK rtt={rtt:?}"),
                Err(error) => eprintln!("[test] {target} FAILED {error}"),
            }
        }

        let _ = std::fs::remove_dir_all(&dir);
    }
}
