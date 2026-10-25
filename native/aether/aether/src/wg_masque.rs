//! WARP WireGuard over WARP MASQUE. Adapted from patterniha/Aether 2.3.0
//! (512726803eb7245802d0d877eb9682243a31bac5), AGPL-3.0-only.
use super::*;
use std::time::Duration;

fn cached_inner(config: &EmbeddedConfig) -> Result<Option<account::Identity>> {
    let site = config.secondary_identity_site();
    let loaded = identity::load(&site.store_path, &site.legacy)?;
    match loaded.store.take_usable(site.slot) {
        Some((id, device))
            if device.warp_enabled && device_enrolled_elsewhere(&site.path, &id).is_none() =>
        {
            Ok(Some(identity::identity_from(&id, &device)?))
        }
        _ => Ok(None),
    }
}

async fn outer(primary: &account::Identity, peer: SocketAddr) -> Result<MasqueHop> {
    let h2 = masque_h2::enabled();
    let dial = if h2 { masque_h2::h2_peer(peer) } else { peer };
    establish_masque(
        primary,
        dial,
        attempt_ech().await?,
        h2,
        masque_tunnel_mtu(),
        quic::MAX_DATAGRAM_SIZE,
        true,
        true,
        masque_startup_timeout(),
        "outer MASQUE",
    )
    .await
}

async fn inner_identity(
    config: &EmbeddedConfig,
    stack: &netstack::StackHandle,
) -> Result<account::Identity> {
    if let Some(identity) = cached_inner(config)? {
        return Ok(identity);
    }
    let listener =
        socks::bind_listener("inner registration", "127.0.0.1:0".parse().unwrap()).await?;
    let address = listener.local_addr()?;
    let proxy = upstream::Upstream::parse(&format!("socks5h://{address}"))?;
    let stack = stack.clone();
    let server = tokio::spawn(async move { socks::serve_private(listener, stack).await });
    let _guard = TaskGuard(vec![server.abort_handle()]);
    let site = config.secondary_identity_site();
    upstream::through(proxy, async {
        // Registration is saved before activation. If activation is interrupted,
        // the next attempt reuses that key rather than spending another registration.
        let inner = load_or_provision_warp(&site).await?;
        let _lock = provisioning_lock().lock().await;
        let mut loaded = identity::load(&site.store_path, &site.legacy)?;
        let enabled = loaded
            .store
            .device(&inner.device_id)
            .is_some_and(|d| d.warp_enabled);
        if !enabled {
            account::enable_warp(&inner.device_id, &inner.access_token).await?;
            let mut device = loaded
                .store
                .device(&inner.device_id)
                .cloned()
                .ok_or_else(|| AetherError::Other("inner WARP identity disappeared".into()))?;
            device.warp_enabled = true;
            loaded.store.put(&inner.device_id, device);
            identity::save(&site.store_path, &loaded.store)?;
        }
        Ok(inner)
    })
    .await
}

pub(super) async fn prepare(
    config: &EmbeddedConfig,
    primary: &account::Identity,
    peer: SocketAddr,
) -> Result<account::Identity> {
    if let Some(identity) = cached_inner(config)? {
        return Ok(identity);
    }
    // Preparation must return the inner interface addresses, which are only
    // available after the first registration through the validated outer hop.
    let outer = outer(primary, peer).await?;
    inner_identity(config, &outer.stack).await
}

fn inner_peers(identity: &account::Identity) -> Vec<SocketAddr> {
    let mut peers = Vec::new();
    for host in std::iter::once(identity.assigned_endpoint.as_str()).chain(wireguard::wg_seeds_v4())
    {
        if let Ok(ip) = host.parse::<IpAddr>() {
            let peer = SocketAddr::new(ip, WG_EXAMPLE_PORT);
            if !peers.contains(&peer) {
                peers.push(peer);
            }
        }
    }
    peers.truncate(6);
    peers
}

pub(super) async fn run(
    config: &EmbeddedConfig,
    primary: &account::Identity,
    peer: SocketAddr,
    endpoint: EmbeddedEndpoint,
    ready: Option<tokio::sync::oneshot::Sender<()>>,
) -> Result<()> {
    let mut outer = outer(primary, peer).await?;
    let _control = hold_tunnel_control(&outer._ctrl);
    let secondary = inner_identity(config, &outer.stack).await?;
    let mut selected = None;
    let mut last = AetherError::NoCleanEndpoint;
    for remote in inner_peers(&secondary) {
        let (forwarder, guard) = spawn_udp_forwarder(&outer.stack, remote).await?;
        log::info!("[*] validating inner WARP {remote} through MASQUE {peer}");
        match tokio::time::timeout(
            Duration::from_secs(8),
            establish_wg_channels(&secondary, forwarder, false, 25, "inner WARP"),
        )
        .await
        {
            Ok(Ok(inner)) => {
                selected = Some((inner, guard));
                break;
            }
            Ok(Err(error)) => last = error,
            Err(_) => last = AetherError::Other("inner WARP validation timed out".into()),
        }
    }
    let (inner, _forwarder_guard) = selected.ok_or(last)?;
    let mut inner_exit = inner.exit;
    let mut tasks = TaskGuard(vec![inner_exit.abort_handle()]);
    let mut endpoint_task = match endpoint {
        socks_endpoint @ (EmbeddedEndpoint::Socks | EmbeddedEndpoint::HeldSocks(_)) => {
            let stack = netstack::spawn(
                &secondary.ipv4,
                &secondary.ipv6,
                INNER_MTU,
                inner.inbound_rx,
                inner.outbound_tx,
            )?;
            let listener = crate::embedded_listener(socks_endpoint, config.listen).await?;
            tokio::spawn(async move { socks::serve(listener, stack).await })
        }
        EmbeddedEndpoint::Tun {
            mut device_to_tunnel,
            tunnel_to_device,
        } => {
            let mut inbound = inner.inbound_rx;
            let outbound = inner.outbound_tx;
            tokio::spawn(async move {
                loop {
                    tokio::select! {
                        packet = device_to_tunnel.recv() => match packet {
                            Some(packet) => outbound.send(packet).await.map_err(|_| AetherError::Other("inner WARP outbound closed".into()))?,
                            None => return Ok(()),
                        },
                        packet = inbound.recv() => match packet {
                            Some(packet) => tunnel_to_device.send(packet).await.map_err(|_| AetherError::Other("Android TUN writer closed".into()))?,
                            None => return Ok(()),
                        },
                    }
                }
            })
        }
    };
    tasks.push(endpoint_task.abort_handle());
    if let Some(ready) = ready {
        let _ = ready.send(());
    }
    log::info!("[+] WARP WireGuard over MASQUE validated and ready");
    tokio::select! {
        result = &mut outer.exit => join_outcome("outer MASQUE", result),
        result = &mut inner_exit => join_outcome("inner WARP", result),
        result = &mut endpoint_task => join_outcome("WARP endpoint", result),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn inner_candidates_use_warp_ports_and_prioritize_the_assigned_edge() {
        let identity = account::Identity {
            assigned_endpoint: "162.159.193.9".into(),
            device_id: String::new(),
            access_token: String::new(),
            cert_pem: Vec::new(),
            key_pem: Vec::new(),
            cert_issued_at: 0,
            ipv4: String::new(),
            ipv6: String::new(),
            wg_private_key: [0; 32],
            wg_peer_public_key: [0; 32],
            client_id: [0; 3],
            organization: String::new(),
            gateway_proxy: String::new(),
            refused: false,
        };
        let peers = inner_peers(&identity);
        assert_eq!(peers.first().unwrap().to_string(), "162.159.193.9:2408");
        assert!(peers.iter().all(|peer| peer.port() == 2408));
        assert_eq!(peers.iter().collect::<HashSet<_>>().len(), peers.len());
        assert!(peers.len() <= 6);
    }
}
