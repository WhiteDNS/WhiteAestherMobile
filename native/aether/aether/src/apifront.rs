//! Adaptive WARP API bootstrap. Race authenticated TLS handshakes, then send
//! the HTTP request only on the winner: registrations must not be duplicated.

use std::net::{Ipv4Addr, SocketAddr};
use std::time::Duration;

use futures::future::BoxFuture;
use futures::stream::FuturesUnordered;
use futures::{FutureExt, StreamExt};
use rand::RngExt;
use tokio::net::TcpStream;

use crate::error::{AetherError, Result};
use crate::fragment::{FragmentConfig, FragmentingStream};
use crate::{https, tls};

const SEEDS: &[[u8; 4]] = &[
    [188, 114, 97, 6],
    [188, 114, 97, 1],
    [162, 159, 198, 1],
    [162, 159, 199, 1],
];
const PREFIXES: &[[u8; 3]] = &[[188, 114, 96], [188, 114, 97], [141, 101, 113]];
const SAMPLES_PER_RANGE: usize = 3;
const PROBE_TIMEOUT: Duration = Duration::from_millis(1200);
const HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(8);
const MAX_REACHABLE: usize = 4;

struct Session {
    tls: tokio_boring::SslStream<FragmentingStream<TcpStream>>,
    route: String,
    ech: Option<Vec<u8>>,
}

#[derive(Clone, Copy)]
enum Method {
    RawDirect,
    RawEdge,
    Split,
    SplitLegacy,
    Ech,
}

impl Method {
    fn label(self) -> &'static str {
        match self {
            Self::RawDirect => "raw-direct",
            Self::RawEdge => "raw-edge",
            Self::Split => "split-clienthello",
            Self::SplitLegacy => "split-tls12",
            Self::Ech => "ech",
        }
    }
}

fn edge_pool() -> Vec<SocketAddr> {
    let mut pool: Vec<_> = SEEDS
        .iter()
        .map(|ip| SocketAddr::from((Ipv4Addr::from(*ip), 443)))
        .collect();
    for prefix in PREFIXES {
        for _ in 0..SAMPLES_PER_RANGE {
            let ip = Ipv4Addr::new(
                prefix[0],
                prefix[1],
                prefix[2],
                rand::rng().random_range(1..=254),
            );
            let address = SocketAddr::from((ip, 443));
            if !pool.contains(&address) {
                pool.push(address);
            }
        }
    }
    pool
}

async fn probe_edges(host: &str) -> Vec<SocketAddr> {
    let mut pool = edge_pool();
    // Names are resolved only on the unmarked direct path. With a proxy, its
    // resolver handles the raw lane; IP probes still go through that proxy.
    if crate::egress::mark() == 0 && crate::upstream::configured().is_none() {
        if let Ok(Ok(resolved)) =
            tokio::time::timeout(PROBE_TIMEOUT, tokio::net::lookup_host((host, 443))).await
        {
            for address in resolved.take(2) {
                if !pool.contains(&address) {
                    pool.push(address);
                }
            }
        }
    }
    let mut probes = FuturesUnordered::new();
    for address in pool.iter().copied() {
        probes.push(async move {
            let connected = async {
                match crate::upstream::configured() {
                    Some(proxy) => proxy.connect(address).await,
                    None => crate::egress::tcp_connect(address)
                        .await
                        .map_err(AetherError::from),
                }
            };
            match tokio::time::timeout(PROBE_TIMEOUT, connected).await {
                Ok(Ok(stream)) => {
                    drop(stream);
                    Some(address)
                }
                _ => None,
            }
        });
    }
    let mut live = Vec::new();
    while let Some(answer) = probes.next().await {
        if let Some(address) = answer {
            live.push(address);
            if live.len() == MAX_REACHABLE {
                break;
            }
        }
    }
    // A short TCP probe can miss a slow edge. Keep a bounded fallback, rather
    // than declaring the API unreachable before trying an actual handshake.
    if live.is_empty() {
        pool.truncate(MAX_REACHABLE);
        live = pool;
    }
    log::info!(
        "[*] API bootstrap: {} candidate edges after TCP probing",
        live.len()
    );
    live
}

async fn first_ready<T>(mut pending: FuturesUnordered<BoxFuture<'_, Result<T>>>) -> Result<T> {
    let mut failure = AetherError::Api("every API handshake route failed".into());
    while let Some(result) = pending.next().await {
        match result {
            Ok(winner) => return Ok(winner),
            Err(error) => {
                log::debug!("[api bootstrap] {error}");
                failure = error;
            }
        }
    }
    Err(failure)
    // All losing futures and their sockets are dropped here, also on cancellation.
}

async fn open_route(
    request: &https::Request<'_>,
    fingerprint: &tls::Fingerprint,
    address: Option<SocketAddr>,
    fragments: FragmentConfig,
    mut ech: Option<Vec<u8>>,
    method: Method,
) -> Result<Session> {
    let label = method.label();
    // Retain the old TLS 1.2 split profile as an alternative within the split
    // lane; some inspectors treat its shorter ClientHello differently.
    let legacy = tls::Fingerprint {
        ciphers: Some("ECDHE-ECDSA-CHACHA20-POLY1305:ECDHE-ECDSA-AES128-GCM-SHA256:ECDHE-RSA-AES128-GCM-SHA256:ECDHE-ECDSA-AES256-SHA:ECDHE-RSA-AES256-SHA:AES256-SHA".into()),
        groups: "X25519:P-256".into(),
        grease: false,
    };
    let maximum = if matches!(method, Method::SplitLegacy) {
        Some(boring::ssl::SslVersion::TLS1_2)
    } else {
        None
    };
    let fingerprint = if maximum.is_some() {
        &legacy
    } else {
        fingerprint
    };
    let address_host = address.map(|address| address.ip().to_string());
    let routed = https::Request {
        address: address_host
            .as_deref()
            .zip(address.map(|address| address.port())),
        ..*request
    };
    let tls = tokio::time::timeout(
        HANDSHAKE_TIMEOUT,
        https::open(&routed, fingerprint, ech.as_mut(), fragments, maximum),
    )
    .await
    .map_err(|_| AetherError::Api(format!("{label} TLS handshake timed out")))??;
    Ok(Session {
        tls,
        route: format!(
            "{label} / {}",
            address.map_or_else(|| request.host.to_string(), |a| a.to_string())
        ),
        ech,
    })
}

async fn open_edges(
    request: &https::Request<'_>,
    fingerprint: &tls::Fingerprint,
    addresses: Vec<SocketAddr>,
    fragments: FragmentConfig,
    ech: Option<Vec<u8>>,
    method: Method,
) -> Result<Session> {
    let pending = FuturesUnordered::new();
    for address in addresses {
        pending.push(
            open_route(
                request,
                fingerprint,
                Some(address),
                fragments,
                ech.clone(),
                method,
            )
            .boxed(),
        );
    }
    first_ready(pending).await
}

/// The three methods share one edge probe. ECH resolution runs inside its
/// lane, so a blocked resolver cannot delay raw TLS or a split ClientHello.
pub(crate) async fn send(
    request: &https::Request<'_>,
    fingerprint: &tls::Fingerprint,
    timeout: Duration,
) -> Result<(https::Response, Option<Vec<u8>>)> {
    tokio::time::timeout(timeout, async {
        let edges = probe_edges(request.host).boxed().shared();
        let pending: FuturesUnordered<BoxFuture<'_, Result<Session>>> = FuturesUnordered::new();
        let raw_edges = edges.clone();
        pending.push(
            async {
                let raw = FuturesUnordered::new();
                if crate::egress::mark() == 0 || crate::upstream::configured().is_some() {
                    raw.push(
                        open_route(
                            request,
                            fingerprint,
                            None,
                            FragmentConfig::disabled(),
                            None,
                            Method::RawDirect,
                        )
                        .boxed(),
                    );
                }
                raw.push(
                    async {
                        open_edges(
                            request,
                            fingerprint,
                            raw_edges.await,
                            FragmentConfig::disabled(),
                            None,
                            Method::RawEdge,
                        )
                        .await
                    }
                    .boxed(),
                );
                first_ready(raw).await
            }
            .boxed(),
        );
        let split_edges = edges.clone();
        pending.push(
            async {
                let mut fragments = FragmentConfig::from_env();
                fragments.enabled = true;
                let addresses = split_edges.await;
                let split = FuturesUnordered::new();
                split.push(
                    open_edges(
                        request,
                        fingerprint,
                        addresses.clone(),
                        fragments,
                        None,
                        Method::Split,
                    )
                    .boxed(),
                );
                split.push(
                    open_edges(
                        request,
                        fingerprint,
                        addresses,
                        fragments,
                        None,
                        Method::SplitLegacy,
                    )
                    .boxed(),
                );
                first_ready(split).await
            }
            .boxed(),
        );
        pending.push(
            async {
                let lookup = async {
                    if let Some(key) = crate::account::api_ech_in_use().or_else(tls::session_ech) {
                        return Ok(key);
                    }
                    crate::dns::fetch_ech_config(&crate::dns::SESSION_ECH).await
                };
                let (addresses, key) = tokio::join!(edges, lookup);
                open_edges(
                    request,
                    fingerprint,
                    addresses,
                    FragmentConfig::disabled(),
                    Some(key?),
                    Method::Ech,
                )
                .await
            }
            .boxed(),
        );
        let winner = first_ready(pending).await?;
        log::info!("[+] API TLS route won: {}", winner.route);
        let response = https::send_on_session(winner.tls, request).await?;
        Ok((response, winner.ech))
    })
    .await
    .map_err(|_| AetherError::Api("API bootstrap/exchange timed out".into()))?
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn an_untrusted_api_certificate_cannot_win_the_race() {
        let _setting = crate::upstream::hold_setting().await;
        let pair = crate::account::generate_masque_keypair().unwrap();
        let mut acceptor =
            boring::ssl::SslAcceptor::mozilla_intermediate_v5(boring::ssl::SslMethod::tls())
                .unwrap();
        acceptor
            .set_certificate(&boring::x509::X509::from_pem(&pair.cert_pem).unwrap())
            .unwrap();
        acceptor
            .set_private_key(&boring::pkey::PKey::private_key_from_pem(&pair.key_pem).unwrap())
            .unwrap();
        let acceptor = acceptor.build();
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let address = listener.local_addr().unwrap();
        let server = tokio::spawn(async move {
            let (tcp, _) = listener.accept().await.unwrap();
            let _ = tokio_boring::accept(&acceptor, tcp).await;
        });
        let request = https::Request {
            method: "POST",
            host: "api.cloudflareclient.com",
            port: 443,
            address: None,
            sni: None,
            path: "/reg",
            headers: &[],
            body: Some(b"secret"),
        };
        assert!(open_route(
            &request,
            &tls::Fingerprint::default(),
            Some(address),
            FragmentConfig::disabled(),
            None,
            Method::RawDirect
        )
        .await
        .is_err());
        server.await.unwrap();
    }

    #[tokio::test]
    async fn racing_real_tls_sessions_sends_the_registration_only_once() {
        use std::sync::atomic::{AtomicUsize, Ordering};
        use std::sync::Arc;
        let _setting = crate::upstream::hold_setting().await;
        let (address, listener, acceptor) = https::test_server(b"\x02h2").await;
        let received = Arc::new(AtomicUsize::new(0));
        let count = received.clone();
        let server = tokio::spawn(async move {
            let mut connections = tokio::task::JoinSet::new();
            loop {
                let (tcp, _) = listener.accept().await.unwrap();
                let acceptor = acceptor.clone();
                let count = count.clone();
                connections.spawn(async move {
                    let Ok(tls) = tokio_boring::accept(&acceptor, tcp).await else {
                        return;
                    };
                    let Ok(mut h2) = h2::server::handshake(tls).await else {
                        return;
                    };
                    while let Some(Ok((mut request, mut reply))) = h2.accept().await {
                        count.fetch_add(1, Ordering::SeqCst);
                        assert_eq!(request.method(), "POST");
                        assert_eq!(request.uri().host(), Some("api.cloudflareclient.com"));
                        let mut payload = Vec::new();
                        while let Some(chunk) = request.body_mut().data().await {
                            payload.extend_from_slice(&chunk.unwrap());
                        }
                        assert_eq!(payload, b"{\"key\":\"test\"}");
                        reply
                            .send_response(
                                http::Response::builder().status(204).body(()).unwrap(),
                                true,
                            )
                            .unwrap();
                    }
                });
            }
        });
        let stalled_listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let stalled_address = stalled_listener.local_addr().unwrap();
        let stalled = tokio::spawn(async move {
            let (_tcp, _) = stalled_listener.accept().await.unwrap();
            futures::future::pending::<()>().await;
        });
        let request = https::Request {
            method: "POST",
            host: "api.cloudflareclient.com",
            port: 443,
            address: None,
            sni: None,
            path: "/reg",
            headers: &[],
            body: Some(b"{\"key\":\"test\"}"),
        };
        let fingerprint = tls::Fingerprint::default();
        let mut fragments = FragmentConfig::from_env();
        fragments.enabled = true;
        let pending = FuturesUnordered::new();
        pending.push(
            open_route(
                &request,
                &fingerprint,
                Some(stalled_address),
                FragmentConfig::disabled(),
                None,
                Method::RawDirect,
            )
            .boxed(),
        );
        pending.push(
            open_route(
                &request,
                &fingerprint,
                Some(address),
                FragmentConfig::disabled(),
                None,
                Method::RawEdge,
            )
            .boxed(),
        );
        pending.push(
            open_route(
                &request,
                &fingerprint,
                Some(address),
                fragments,
                None,
                Method::SplitLegacy,
            )
            .boxed(),
        );
        let winner = tokio::time::timeout(Duration::from_secs(3), first_ready(pending))
            .await
            .unwrap()
            .unwrap();
        assert_eq!(
            received.load(Ordering::SeqCst),
            0,
            "handshake racers sent an HTTP request"
        );
        let response = tokio::time::timeout(
            Duration::from_secs(3),
            https::send_on_session(winner.tls, &request),
        )
        .await
        .unwrap()
        .unwrap();
        assert_eq!(response.status, 204);
        tokio::time::sleep(Duration::from_millis(100)).await;
        assert_eq!(received.load(Ordering::SeqCst), 1);
        let legacy = open_route(
            &request,
            &fingerprint,
            Some(address),
            fragments,
            None,
            Method::SplitLegacy,
        )
        .await
        .unwrap();
        assert_eq!(legacy.tls.ssl().version_str(), "TLSv1.2");
        assert_eq!(
            received.load(Ordering::SeqCst),
            1,
            "a split handshake sent an early request"
        );
        let response = https::send_on_session(legacy.tls, &request).await.unwrap();
        assert_eq!(response.status, 204);
        assert_eq!(received.load(Ordering::SeqCst), 2);
        server.abort();
        stalled.abort();
    }

    #[test]
    fn bootstrap_pool_includes_the_reported_edge_and_multiple_ranges() {
        let pool = edge_pool();
        assert!(pool.contains(&"188.114.97.6:443".parse().unwrap()));
        for prefix in PREFIXES {
            assert!(pool.iter().any(|address| match address.ip() {
                std::net::IpAddr::V4(ip) => ip.octets()[..3] == prefix[..],
                _ => false,
            }));
        }
        assert!(pool.iter().all(|address| address.port() == 443));
    }

    #[tokio::test]
    async fn a_stalled_lane_does_not_delay_a_winner_and_is_dropped() {
        struct Dropped(std::sync::Arc<std::sync::atomic::AtomicBool>);
        impl Drop for Dropped {
            fn drop(&mut self) {
                self.0.store(true, std::sync::atomic::Ordering::SeqCst);
            }
        }
        let dropped = std::sync::Arc::new(std::sync::atomic::AtomicBool::new(false));
        let guard = Dropped(dropped.clone());
        let pending: FuturesUnordered<BoxFuture<'_, Result<u8>>> = FuturesUnordered::new();
        pending.push(
            async move {
                let _guard = guard;
                futures::future::pending().await
            }
            .boxed(),
        );
        pending.push(async { Err(AetherError::Api("blocked".into())) }.boxed());
        pending.push(async { Ok(7) }.boxed());
        assert_eq!(
            tokio::time::timeout(Duration::from_millis(100), first_ready(pending))
                .await
                .unwrap()
                .unwrap(),
            7
        );
        assert!(dropped.load(std::sync::atomic::Ordering::SeqCst));
    }
}
