//! Explicit live diagnostic. Keeps credentials in the supplied private cache;
//! prints only endpoint addresses, protocol outcomes and public close reasons.
use std::net::SocketAddr;
use std::time::Duration;

use aether::{api, consts, masque_h2, noize, quic, tunnelping};

type DiagnosticError = Box<dyn std::error::Error + Send + Sync>;

fn main() -> Result<(), DiagnosticError> {
    // Match the Android bridge's owned stacks; Windows main has a smaller
    // default stack than the production core thread.
    let task = std::thread::Builder::new()
        .name("endpoint-diagnostic".into())
        .stack_size(8 << 20)
        .spawn(|| -> Result<(), DiagnosticError> {
            tokio::runtime::Builder::new_multi_thread()
                .enable_all()
                .thread_stack_size(8 << 20)
                .build()?
                .block_on(Box::pin(run()))
        })?;
    match task.join() {
        Ok(result) => result,
        Err(_) => Err("diagnostic thread panicked".into()),
    }
}

async fn run() -> Result<(), DiagnosticError> {
    if !std::env::args().any(|argument| argument == "--allow-live") {
        return Err("live registration/credential use requires explicit authorization; pass --allow-live only after approval".into());
    }
    let path = std::env::args()
        .nth(1)
        .ok_or("supply an identity cache path")?;
    let production_only = std::env::args().any(|argument| argument == "--production-only");
    if production_only && api::load_identity(&path)?.is_none() {
        return Err(
            "production-only requires this diagnostic's already-created test identity".into(),
        );
    }
    if let Some(parent) = std::path::Path::new(&path).parent() {
        std::fs::create_dir_all(parent)?;
    }
    // Persist registration before enrolling a key, so an interrupted enrollment
    // never spends another registration on the next diagnostic run.
    let request = api::ProvisionRequest::default();
    println!(
        "stage=registration: {}",
        if production_only {
            "reusing the existing diagnostic identity; creation is disabled in this mode"
        } else {
            "creating or loading only this diagnostic's cached identity"
        }
    );
    let registered =
        tokio::time::timeout(Duration::from_secs(60), api::open_identity(&path, &request))
            .await??;
    println!("stage=registration: success; credentials are not printed");
    println!("stage=enrollment: requesting a MASQUE certificate");
    let identity =
        tokio::time::timeout(Duration::from_secs(30), api::attach_masque_cert(registered))
            .await??;
    api::save_identity(&path, &identity)?;
    println!("stage=enrollment: success; credentials saved privately and not printed");
    if production_only {
        return production_path(&path, &identity).await;
    }
    let host = identity.assigned_endpoint.trim().trim_matches(['[', ']']);
    let mut peers: Vec<SocketAddr> = Vec::new();
    if let Ok(ip) = host.parse() {
        peers.push(SocketAddr::new(ip, 443));
    }
    for address in ["162.159.198.2:443", "162.159.197.3:443"] {
        let peer = address.parse()?;
        if !peers.contains(&peer) {
            peers.push(peer);
        }
    }
    for peer in peers {
        for sni in [consts::CONNECT_SNI, consts::L4_CONNECT_SNI] {
            let cfg = masque_h2::H2TunnelConfig {
                peer,
                sni: sni.into(),
                authority: quic::default_authority().into(),
                path: quic::default_path().into(),
                cert_pem: identity.cert_pem.clone(),
                key_pem: identity.key_pem.clone(),
                local_ipv4: identity.ipv4.parse()?,
                quiet: true,
                pin_endpoint: true,
                expected_pins: consts::masque_pins().iter().map(|p| p.to_vec()).collect(),
                ech_config_list: None,
            };
            let h2 = masque_h2::verify_h2(&cfg, Duration::from_secs(8)).await;
            println!("H2 {peer} sni={sni}: {h2:?}");
            if h2.is_ok() {
                std::env::set_var("AETHER_MASQUE_HTTP2", "1");
                println!(
                    "H2 HTTP traffic {peer} sni={sni}: {:?}",
                    tunnelping::masque_http_ping(
                        &tunnelping::MasquePingParams {
                            peer,
                            sni: sni.into(),
                            authority: cfg.authority.clone(),
                            path: cfg.path.clone(),
                            cert_pem: identity.cert_pem.clone(),
                            key_pem: identity.key_pem.clone(),
                            local_ipv4: cfg.local_ipv4,
                            local_ipv4_str: identity.ipv4.clone(),
                            local_ipv6_str: identity.ipv6.clone(),
                            noize: noize::from_profile("off"),
                            ech_config_list: None,
                        },
                        Duration::from_secs(15)
                    )
                    .await
                );
                std::env::remove_var("AETHER_MASQUE_HTTP2");
            }
            let params = quic::VerifyParams {
                peer,
                sni: sni.into(),
                authority: quic::default_authority().into(),
                path: quic::default_path().into(),
                cert_pem: identity.cert_pem.clone(),
                key_pem: identity.key_pem.clone(),
                local_ipv4: identity.ipv4.parse()?,
                noize: noize::from_profile("off"),
                timeout: Duration::from_secs(8),
                ech_config_list: None,
            };
            let h3 = quic::verify_masque(&params).await;
            println!("H3 {peer} sni={sni}: {h3:?}");
            if h3.is_ok() {
                println!(
                    "H3 HTTP traffic {peer} sni={sni}: {:?}",
                    tunnelping::masque_http_ping(
                        &tunnelping::MasquePingParams {
                            peer,
                            sni: sni.into(),
                            authority: cfg.authority.clone(),
                            path: cfg.path.clone(),
                            cert_pem: identity.cert_pem.clone(),
                            key_pem: identity.key_pem.clone(),
                            local_ipv4: cfg.local_ipv4,
                            local_ipv4_str: identity.ipv4.clone(),
                            local_ipv6_str: identity.ipv6.clone(),
                            noize: noize::from_profile("off"),
                            ech_config_list: None,
                        },
                        Duration::from_secs(15)
                    )
                    .await
                );
            }
        }
    }
    Ok(())
}

/// Exercise the Android engine entry points with the SAME diagnostic identity.
/// No additional account is registered, and no listener is exposed externally.
async fn production_path(
    path: &str,
    identity: &aether::account::Identity,
) -> Result<(), DiagnosticError> {
    let base = std::path::Path::new(path)
        .parent()
        .ok_or("cache has no parent")?
        .join("embedded.toml")
        .to_string_lossy()
        .into_owned();
    let masque_path = api::identity_path(&base, api::Transport::Masque, None);
    if let Some(saved) = api::load_identity(&masque_path)? {
        if saved.device_id != identity.device_id {
            return Err(
                "refusing to replace a different identity in the diagnostic directory".into(),
            );
        }
    } else {
        api::save_identity(&masque_path, identity)?;
    }
    std::env::remove_var("AETHER_MASQUE_NO_DATA_CHECK");
    std::env::set_var("AETHER_NOIZE", "firewall");
    let mut config = aether::EmbeddedConfig {
        config_path: base,
        listen: "127.0.0.1:1819".parse()?,
        peer: None,
        peer_fallback: true,
        scan_mode: "turbo".into(),
        ip_scan: "v4".into(),
        protocol: "masque".into(),
        access: aether::socks::Access::default(),
    };
    let devices = tokio::time::timeout(
        Duration::from_secs(15),
        Box::pin(aether::provision_embedded(&config)),
    )
    .await??;
    if devices.len() != 1 || devices[0] != identity.device_id {
        return Err("embedded provisioning did not reuse the single diagnostic identity".into());
    }
    println!("stage=embedded-provision: reused_same_identity=true; no additional registration");
    for transport in ["h2", "h3"] {
        if transport == "h2" {
            std::env::set_var("AETHER_MASQUE_HTTP2", "1");
        } else {
            std::env::remove_var("AETHER_MASQUE_HTTP2");
        }
        config.peer = None;
        config.peer_fallback = true;
        config.scan_mode = "turbo".into();
        let cancelled = std::sync::atomic::AtomicBool::new(false);
        let scanned = tokio::time::timeout(
            Duration::from_secs(20),
            Box::pin(aether::scan_embedded(&config, 1, &cancelled)),
        )
        .await??;
        let hit = scanned
            .first()
            .ok_or("embedded scan returned no candidates")?;
        println!(
            "stage=embedded-scan protocol={transport} peer={} rtt={:?}",
            hit.peer, hit.rtt
        );
        config.peer = Some(hit.peer);
        config.peer_fallback = false;
        let prepared = tokio::time::timeout(
            Duration::from_secs(15),
            Box::pin(aether::prepare_embedded(&config)),
        )
        .await??;
        println!(
            "stage=embedded-prepare protocol={transport} selected_expected_peer={}",
            prepared.peer == hit.peer
        );
        config.scan_mode = "ironclad".into();
        for attempt in 1..=2 {
            let checked = tokio::time::timeout(
                Duration::from_secs(20),
                Box::pin(aether::test_embedded_peer(&config)),
            )
            .await??;
            println!(
                "stage=embedded-http protocol={transport} independent_check={attempt} rtt={:?}",
                checked.rtt
            );
        }
    }
    let reloaded = api::load_identity(&masque_path)?.ok_or("saved identity missing")?;
    if reloaded.device_id != identity.device_id {
        return Err("identity changed during embedded checks".into());
    }
    println!("stage=embedded-final: same_identity_preserved=true");
    Ok(())
}
