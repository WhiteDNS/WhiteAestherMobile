use std::sync::{Arc, OnceLock, RwLock};

use crate::Result;

type Protector = Arc<dyn Fn(i32) -> bool + Send + Sync + 'static>;

fn protector() -> &'static RwLock<Option<Protector>> {
    static PROTECTOR: OnceLock<RwLock<Option<Protector>>> = OnceLock::new();
    PROTECTOR.get_or_init(|| RwLock::new(None))
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

    if let Some(callback) = callback {
        let fd = socket.as_raw_fd();
        if !callback(fd) {
            return Err(crate::AetherError::Other(format!(
                "Android VpnService rejected upstream socket fd {fd}",
            )));
        }
    }

    Ok(())
}

#[cfg(not(unix))]
pub fn protect<T>(_socket: &T) -> Result<()> {
    Ok(())
}
