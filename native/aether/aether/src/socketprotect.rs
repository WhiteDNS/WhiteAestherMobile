use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, OnceLock, RwLock};

use crate::error::AetherError;
use crate::Result;

type Protector = Arc<dyn Fn(i32) -> bool + Send + Sync + 'static>;

fn protector() -> &'static RwLock<Option<Protector>> {
    static PROTECTOR: OnceLock<RwLock<Option<Protector>>> = OnceLock::new();
    PROTECTOR.get_or_init(|| RwLock::new(None))
}

/// Sockets opened while no protector was installed.
///
/// Protection fails open by design -- there is no protector on desktop, and
/// none in proxy mode -- which means forgetting to install one on Android is
/// completely silent. It is also ruinous: an unprotected socket is routed by
/// whatever tunnel is currently up, so probes looking for a way out instead go
/// into the interface being replaced and nothing ever answers. Counting them
/// turns that into something a diagnostics report can show.
static UNPROTECTED: AtomicUsize = AtomicUsize::new(0);

/// How many sockets have been opened with no protector since the last reset.
pub fn unprotected_count() -> usize {
    UNPROTECTED.load(Ordering::Relaxed)
}

/// Starts a fresh count, so a figure describes one connect attempt.
pub fn reset_unprotected_count() {
    UNPROTECTED.store(0, Ordering::Relaxed);
}

pub fn set(protector_callback: Option<Protector>) {
    if let Ok(mut current) = protector().write() {
        *current = protector_callback;
    }
}

#[cfg(unix)]
pub fn protect<T: std::os::fd::AsRawFd>(socket: &T) -> Result<()> {
    let callback = protector()
        .read()
        .map_err(|_| crate::AetherError::Other("socket protector lock poisoned".into()))?
        .clone();

    let Some(callback) = callback else {
        // Only Android installs one, so only there does its absence mean a
        // socket escaped the VpnService. Elsewhere there is nothing to escape.
        #[cfg(target_os = "android")]
        UNPROTECTED.fetch_add(1, Ordering::Relaxed);
        return Ok(());
    };

    let fd = socket.as_raw_fd();
    if !callback(fd) {
        return Err(crate::AetherError::Other(format!(
            "Android VpnService rejected upstream socket fd {fd}",
        )));
    }

    Ok(())
}

#[cfg(not(unix))]
pub fn protect<T>(_socket: &T) -> Result<()> {
    Ok(())
}

/// Connects a TCP socket that the tunnel cannot swallow.
///
/// Protection has to happen between creating the socket and connecting it, so
/// this cannot be `TcpStream::connect`: by the time that returns, the SYN has
/// already gone out through whatever interface currently holds the default
/// route -- on Android, the tunnel being built.
///
/// Lives here rather than beside its callers because there are now several,
/// and every one of them that forgets is a socket routed into the tunnel it is
/// trying to establish.
pub async fn connect_tcp(peer: std::net::SocketAddr) -> Result<tokio::net::TcpStream> {
    use socket2::{Domain, Protocol, Socket, Type};

    let domain = if peer.is_ipv4() {
        Domain::IPV4
    } else {
        Domain::IPV6
    };
    let socket = Socket::new(domain, Type::STREAM, Some(Protocol::TCP)).map_err(AetherError::Io)?;
    socket.set_nonblocking(true).map_err(AetherError::Io)?;
    protect(&socket)?;
    match socket.connect(&peer.into()) {
        Ok(()) => {}
        Err(error)
            if error.kind() == std::io::ErrorKind::WouldBlock
                || error.raw_os_error() == Some(libc::EINPROGRESS) => {}
        Err(error) => return Err(AetherError::Io(error)),
    }
    let stream = tokio::net::TcpStream::from_std(socket.into()).map_err(AetherError::Io)?;
    stream.writable().await.map_err(AetherError::Io)?;
    if let Some(error) = stream.take_error().map_err(AetherError::Io)? {
        return Err(AetherError::Io(error));
    }
    Ok(stream)
}

/// Resolves a `host:port` and connects to it with protection in place.
///
/// The upstream proxy is configured as a name, not an address, so the address
/// is not known until it is looked up. Only the first result is tried, which
/// is what `TcpStream::connect` effectively did here before -- the difference
/// is that the socket now exists before it is connected, which is the only
/// window in which it can be protected.
pub async fn connect_tcp_host(endpoint: &str) -> Result<tokio::net::TcpStream> {
    let mut last = None;
    for address in tokio::net::lookup_host(endpoint)
        .await
        .map_err(AetherError::Io)?
    {
        match connect_tcp(address).await {
            Ok(stream) => return Ok(stream),
            Err(error) => last = Some(error),
        }
    }
    Err(last.unwrap_or_else(|| {
        AetherError::Io(std::io::Error::new(
            std::io::ErrorKind::AddrNotAvailable,
            format!("no address for {endpoint}"),
        ))
    }))
}
