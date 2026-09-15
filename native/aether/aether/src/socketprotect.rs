use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, OnceLock, RwLock};

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
