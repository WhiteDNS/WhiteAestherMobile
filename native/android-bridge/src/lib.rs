// dlopen and friends are a Unix API; the host build on Windows has no chain.
#[cfg(unix)]
mod chain;
#[cfg(unix)]
mod chain_jni;
mod tun;

use std::collections::VecDeque;
use std::net::SocketAddr;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::{
    atomic::{AtomicBool, Ordering},
    Arc,
};

use jni::objects::{GlobalRef, JClass, JObject, JString, JValue};
use jni::sys::{jboolean, jint, jobjectArray, jstring, JNI_FALSE, JNI_TRUE};
use jni::{JNIEnv, JavaVM};
use once_cell::sync::Lazy;
use parking_lot::Mutex;
use serde::Deserialize;
use serde_json::json;
use tokio::sync::{mpsc, oneshot};

const BRIDGE_VERSION: &str = "0.2.0";
const PACKET_QUEUE: usize = 1_024;
const SCAN_RESULT_LIMIT: usize = 6;

static STOP_SENDER: Lazy<Mutex<Option<oneshot::Sender<()>>>> = Lazy::new(|| Mutex::new(None));
static SCAN_RUNNING: AtomicBool = AtomicBool::new(false);
static SCAN_CANCELLED: AtomicBool = AtomicBool::new(false);

struct ScanRunningGuard;

impl Drop for ScanRunningGuard {
    fn drop(&mut self) {
        SCAN_RUNNING.store(false, Ordering::SeqCst);
    }
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct BridgeConfig {
    mode: String,
    config_path: String,
    #[serde(default = "default_proxy_port")]
    listen_port: u16,
    peer: Option<String>,
    #[serde(default)]
    peer_fallback: bool,
    #[serde(default = "default_scan_mode")]
    scan_mode: String,
    #[serde(default = "default_ip_scan")]
    ip_scan: String,
    #[serde(default = "default_transport")]
    transport: String,
    #[serde(default = "default_noize")]
    noize: String,
    #[serde(default = "default_true")]
    validation_enabled: bool,
    /// Split the TLS ClientHello across several small writes on the HTTP/2
    /// transport. Deep packet inspection that blocks on the SNI generally reads
    /// only the first segment, so splitting it is what gets a handshake through
    /// networks that filter by hostname.
    #[serde(default)]
    fragment_tls: bool,
    /// Encrypted Client Hello. Hides the SNI outright where the upstream
    /// supports it.
    #[serde(default)]
    encrypted_hello: bool,
}

fn default_proxy_port() -> u16 {
    1819
}

fn default_scan_mode() -> String {
    "balanced".into()
}

fn default_ip_scan() -> String {
    "both".into()
}

fn default_transport() -> String {
    "h3".into()
}

fn default_noize() -> String {
    "firewall".into()
}

fn default_true() -> bool {
    true
}

impl BridgeConfig {
    fn parse(raw: &str) -> Result<Self, String> {
        let config: Self = serde_json::from_str(raw).map_err(|error| error.to_string())?;
        if !matches!(config.mode.as_str(), "proxy" | "tun") {
            return Err("mode must be proxy or tun".into());
        }
        if config.config_path.trim().is_empty() {
            return Err("configPath is required".into());
        }
        if !(1_024..=65_535).contains(&config.listen_port) {
            return Err("listenPort must be between 1024 and 65535".into());
        }
        if !matches!(config.transport.as_str(), "h3" | "h2" | "wg" | "wiw") {
            return Err("transport must be h3, h2, wg or wiw".into());
        }
        if let Some(peer) = config.peer.as_deref() {
            let address = peer
                .parse::<SocketAddr>()
                .map_err(|_| "peer must be an IP:port address".to_string())?;
            if address.port() == 0 {
                return Err("peer port must be between 1 and 65535".into());
            }
        } else if config.peer_fallback {
            return Err("peerFallback requires a custom peer".into());
        }
        Ok(config)
    }

    fn embedded(
        &self,
        prepared_peer: Option<SocketAddr>,
    ) -> Result<aether::EmbeddedConfig, String> {
        let peer = match prepared_peer {
            Some(peer) => Some(peer),
            None => self
                .peer
                .as_deref()
                .map(str::parse)
                .transpose()
                .map_err(|_| "peer must be an IP:port address".to_string())?,
        };
        Ok(aether::EmbeddedConfig {
            config_path: self.config_path.clone(),
            listen: SocketAddr::from(([127, 0, 0, 1], self.listen_port)),
            peer,
            peer_fallback: self.peer_fallback,
            scan_mode: self.scan_mode.clone(),
            ip_scan: self.ip_scan.clone(),
            // h2 and h3 are two framings of one protocol, chosen by an
            // environment variable; wg is a different tunnel entirely, with its
            // own account, its own endpoints and its own prober.
            protocol: match self.transport.as_str() {
                "wg" => "wireguard".into(),
                "wiw" => "warp-in-warp".into(),
                _ => "masque".into(),
            },
        })
    }

    fn apply_environment(&self) {
        std::env::set_var("AETHER_NOIZE", &self.noize);
        if self.transport == "h2" {
            std::env::set_var("AETHER_MASQUE_HTTP2", "1");
        } else {
            std::env::remove_var("AETHER_MASQUE_HTTP2");
        }
        // WireGuard reads its obfuscation from the same AETHER_NOIZE set above,
        // but the profile names differ in meaning between the two tunnels, so
        // the engine resolves candidates itself rather than trusting one name.

        std::env::set_var("AETHER_QUICK_RECONNECT", "1");
        if self.fragment_tls {
            std::env::set_var("AETHER_MASQUE_H2_FRAGMENT", "1");
        } else {
            std::env::remove_var("AETHER_MASQUE_H2_FRAGMENT");
        }
        if self.encrypted_hello {
            std::env::set_var("AETHER_ECH", "auto");
        } else {
            std::env::remove_var("AETHER_ECH");
        }
        if self.validation_enabled {
            std::env::remove_var("AETHER_MASQUE_NO_DATA_CHECK");
        } else {
            std::env::set_var("AETHER_MASQUE_NO_DATA_CHECK", "1");
        }
    }
}

/// Recent lines from the engine, newest last.
///
/// The engine explains itself through `log` -- which endpoint it is testing,
/// which obfuscation profile answered, why a handshake failed. Sending that only
/// to logcat means it is unreachable from a phone the developer does not hold,
/// so a user reporting "it will not connect" has nothing to send that says why.
/// Buffered here and drained into the app's diagnostics instead.
static ENGINE_LOG: Mutex<VecDeque<String>> = Mutex::new(VecDeque::new());

/// Enough for a whole connect attempt including a full endpoint scan, and
/// bounded because nothing guarantees anybody is draining.
const MAX_ENGINE_LOG: usize = 400;

/// Writes each line to logcat and keeps a copy the app can read.
///
/// One logger, because `log` has room for exactly one. Delegating the logcat
/// half keeps `adb logcat -s aether` working exactly as before.
struct TeeLogger(android_logger::AndroidLogger);

impl log::Log for TeeLogger {
    fn enabled(&self, metadata: &log::Metadata<'_>) -> bool {
        self.0.enabled(metadata)
    }

    fn log(&self, record: &log::Record<'_>) {
        self.0.log(record);
        if !self.0.enabled(record.metadata()) {
            return;
        }
        let line = format!("{}: {}", record.target(), record.args());
        let mut buffer = ENGINE_LOG.lock();
        if buffer.len() >= MAX_ENGINE_LOG {
            buffer.pop_front();
        }
        buffer.push_back(line);
    }

    fn flush(&self) {
        self.0.flush();
    }
}

/// Sends the engine's own log to logcat and to the app, once.
///
/// The engine reports what it is doing through the `log` crate -- which endpoint
/// it is testing, which obfuscation profile answered, why a handshake failed.
/// On Android nothing consumes that by default, so all of it was being dropped
/// and every failure looked the same from outside.
///
/// `RUST_LOG` still wins where it is set, so a diagnostic build can turn this up
/// without a code change.
fn install_logger() {
    static ONCE: std::sync::Once = std::sync::Once::new();
    ONCE.call_once(|| {
        let config = android_logger::Config::default()
            .with_max_level(log::LevelFilter::Info)
            .with_tag("aether");
        let logger = TeeLogger(android_logger::AndroidLogger::new(config));
        if log::set_boxed_logger(Box::new(logger)).is_ok() {
            log::set_max_level(log::LevelFilter::Info);
        }
        log::info!("aether bridge logging installed");
    });
}

/// Packages this install's identity so it survives a reinstall.
///
/// Uninstalling takes the identity with it, and Cloudflare rate-limits device
/// registrations per address -- so a few reinstalls can leave an address refused
/// outright, which looks exactly like a broken app.
#[no_mangle]
pub extern "system" fn Java_com_whitedns_whiteaesther_core_NativeAetherBridge_nativeExportIdentity<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    config_path: JString<'local>,
) -> jstring {
    install_logger();
    let path: String = match env.get_string(&config_path) {
        Ok(value) => value.into(),
        Err(error) => return java_string(env, &failure_json(&error.to_string())),
    };

    match aether::export_identity(&path) {
        Ok(payload) => java_string(env, &json!({ "ok": true, "payload": payload }).to_string()),
        Err(error) => java_string(env, &failure_json(&error.to_string())),
    }
}

/// Restores an identity produced by the export above.
///
/// Everything is validated before anything is written: a half-finished import
/// would leave the device holding an identity Cloudflare does not recognise,
/// with the working one already gone.
#[no_mangle]
pub extern "system" fn Java_com_whitedns_whiteaesther_core_NativeAetherBridge_nativeImportIdentity<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    config_path: JString<'local>,
    payload: JString<'local>,
) -> jstring {
    install_logger();
    let read = |env: &mut JNIEnv<'local>, value: JString<'local>| -> Result<String, String> {
        env.get_string(&value)
            .map(Into::into)
            .map_err(|error| error.to_string())
    };

    let result = (|| -> Result<(), String> {
        let path = read(&mut env, config_path)?;
        let payload = read(&mut env, payload)?;
        aether::import_identity(&path, &payload).map_err(|error| error.to_string())
    })();

    match result {
        Ok(()) => java_string(env, r#"{"ok":true}"#),
        Err(reason) => java_string(env, &failure_json(&reason)),
    }
}

/// Takes every engine log line since the last call.
///
/// Pulled rather than pushed: these arrive on the engine's own threads, and a
/// connect attempt produces hundreds during an endpoint scan.
#[no_mangle]
pub extern "system" fn Java_com_whitedns_whiteaesther_core_NativeAetherBridge_nativeDrainLog<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jobjectArray {
    let lines: Vec<String> = ENGINE_LOG.lock().drain(..).collect();
    let empty = match env.new_string("") {
        Ok(value) => value,
        Err(_) => return std::ptr::null_mut(),
    };
    let array = match env.new_object_array(lines.len() as i32, "java/lang/String", &empty) {
        Ok(value) => value,
        Err(_) => return std::ptr::null_mut(),
    };
    for (index, line) in lines.iter().enumerate() {
        let Ok(value) = env.new_string(line) else {
            continue;
        };
        let _ = env.set_object_array_element(&array, index as i32, value);
    }
    array.into_raw()
}

fn failure_json(reason: &str) -> String {
    json!({ "ok": false, "error": reason }).to_string()
}

fn java_string(mut env: JNIEnv<'_>, value: &str) -> jstring {
    match env.new_string(value) {
        Ok(output) => output.into_raw(),
        Err(error) => {
            let _ = env.throw_new(
                "java/lang/IllegalStateException",
                format!("failed to create native string: {error}"),
            );
            std::ptr::null_mut()
        }
    }
}

fn read_java_string(env: &mut JNIEnv<'_>, value: &JString<'_>) -> Result<String, String> {
    env.get_string(value)
        .map(Into::into)
        .map_err(|error| format!("invalid JNI string: {error}"))
}

fn response(ok: bool, fields: serde_json::Value) -> String {
    let mut object = fields.as_object().cloned().unwrap_or_default();
    object.insert("ok".into(), serde_json::Value::Bool(ok));
    serde_json::Value::Object(object).to_string()
}

fn error_response(error: impl std::fmt::Display) -> String {
    response(false, serde_json::json!({"error": error.to_string()}))
}

fn runtime() -> Result<tokio::runtime::Runtime, String> {
    tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .thread_name("whiteaesther-core")
        .build()
        .map_err(|error| format!("failed to start native runtime: {error}"))
}

#[no_mangle]
pub extern "system" fn Java_com_whitedns_whiteaesther_core_NativeAetherBridge_nativeVersion(
    env: JNIEnv<'_>,
    _class: JClass<'_>,
) -> jstring {
    install_logger();
    catch_unwind(AssertUnwindSafe(|| {
        java_string(
            env,
            &format!("{}+android.{BRIDGE_VERSION}", aether::version()),
        )
    }))
    .unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_whitedns_whiteaesther_core_NativeAetherBridge_validateConfig(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    config: JString<'_>,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| {
        let result = read_java_string(&mut env, &config)
            .and_then(|raw| BridgeConfig::parse(&raw).map(|_| ()))
            .map(|_| {
                response(
                    true,
                    serde_json::json!({
                        "coreVersion": aether::version(),
                        "bridgeVersion": BRIDGE_VERSION,
                    }),
                )
            })
            .unwrap_or_else(error_response);
        java_string(env, &result)
    }))
    .unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_whitedns_whiteaesther_core_NativeAetherBridge_nativePrepare(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    config: JString<'_>,
) -> jstring {
    install_logger();
    catch_unwind(AssertUnwindSafe(|| {
        let result = (|| -> Result<String, String> {
            if STOP_SENDER.lock().is_some() {
                return Err("engine is already running".into());
            }
            if SCAN_RUNNING.load(Ordering::SeqCst) {
                return Err("endpoint scan is already running".into());
            }
            let raw = read_java_string(&mut env, &config)?;
            let config = BridgeConfig::parse(&raw)?;
            config.apply_environment();
            let embedded = config.embedded(None)?;
            let prepared = runtime()?
                .block_on(aether::prepare_embedded(&embedded))
                .map_err(|error| error.to_string())?;
            Ok(response(
                true,
                serde_json::json!({
                    "ipv4": prepared.ipv4,
                    "ipv6": prepared.ipv6,
                    "peer": prepared.peer.to_string(),
                }),
            ))
        })()
        .unwrap_or_else(error_response);
        java_string(env, &result)
    }))
    .unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_whitedns_whiteaesther_core_NativeAetherBridge_nativeScan(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    config: JString<'_>,
) -> jstring {
    install_logger();
    catch_unwind(AssertUnwindSafe(|| {
        let result = (|| -> Result<String, String> {
            if STOP_SENDER.lock().is_some() {
                return Err("disconnect before scanning endpoints".into());
            }
            if SCAN_RUNNING.swap(true, Ordering::SeqCst) {
                return Err("endpoint scan is already running".into());
            }
            let _guard = ScanRunningGuard;
            SCAN_CANCELLED.store(false, Ordering::SeqCst);
            let raw = read_java_string(&mut env, &config)?;
            let config = BridgeConfig::parse(&raw)?;
            config.apply_environment();
            let embedded = config.embedded(None)?;
            let results = runtime()?
                .block_on(aether::scan_embedded(
                    &embedded,
                    SCAN_RESULT_LIMIT,
                    &SCAN_CANCELLED,
                ))
                .map_err(|error| error.to_string())?;
            Ok(response(
                true,
                serde_json::json!({
                    "results": results.into_iter().map(|result| serde_json::json!({
                        "peer": result.peer.to_string(),
                        "rttMs": result.rtt.as_millis().min(u64::MAX as u128) as u64,
                    })).collect::<Vec<_>>(),
                }),
            ))
        })()
        .unwrap_or_else(error_response);
        java_string(env, &result)
    }))
    .unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_whitedns_whiteaesther_core_NativeAetherBridge_nativeTestEndpoint(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    config: JString<'_>,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| {
        let result = (|| -> Result<String, String> {
            if STOP_SENDER.lock().is_some() || SCAN_RUNNING.load(Ordering::SeqCst) {
                return Err("disconnect and stop scanning before testing an endpoint".into());
            }
            let raw = read_java_string(&mut env, &config)?;
            let config = BridgeConfig::parse(&raw)?;
            config.apply_environment();
            let embedded = config.embedded(None)?;
            let result = runtime()?
                .block_on(aether::test_embedded_peer(&embedded))
                .map_err(|error| error.to_string())?;
            Ok(response(
                true,
                serde_json::json!({
                    "peer": result.peer.to_string(),
                    "rttMs": result.rtt.as_millis().min(u64::MAX as u128) as u64,
                }),
            ))
        })()
        .unwrap_or_else(error_response);
        java_string(env, &result)
    }))
    .unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_whitedns_whiteaesther_core_NativeAetherBridge_nativeCancelScan(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
) -> jboolean {
    if SCAN_RUNNING.load(Ordering::SeqCst) {
        SCAN_CANCELLED.store(true, Ordering::SeqCst);
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

#[no_mangle]
pub extern "system" fn Java_com_whitedns_whiteaesther_core_NativeAetherBridge_nativeRun(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    config: JString<'_>,
    prepared_peer: JString<'_>,
    tun_fd: jint,
    listener: JObject<'_>,
) -> jstring {
    install_logger();
    catch_unwind(AssertUnwindSafe(|| {
        let result = (|| -> Result<String, String> {
            let raw = read_java_string(&mut env, &config)?;
            let peer: SocketAddr = read_java_string(&mut env, &prepared_peer)?
                .parse()
                .map_err(|_| "prepared peer is invalid".to_string())?;
            let config = BridgeConfig::parse(&raw)?;
            config.apply_environment();
            let embedded = config.embedded(Some(peer))?;
            let vm = Arc::new(env.get_java_vm().map_err(|error| error.to_string())?);
            let listener = Arc::new(
                env.new_global_ref(listener)
                    .map_err(|error| error.to_string())?,
            );
            let runtime = runtime()?;
            let (stop_tx, stop_rx) = oneshot::channel();
            {
                let mut sender = STOP_SENDER.lock();
                if sender.is_some() {
                    return Err("engine is already running".into());
                }
                *sender = Some(stop_tx);
            }

            let (endpoint, pump) = if config.mode == "tun" {
                if tun_fd < 0 {
                    STOP_SENDER.lock().take();
                    return Err("TUN mode requires a valid file descriptor".into());
                }
                let (device_tx, device_rx) = mpsc::channel(PACKET_QUEUE);
                let (tunnel_tx, tunnel_rx) = mpsc::channel(PACKET_QUEUE);
                let pump = tun::TunPump::start(tun_fd, device_tx, tunnel_rx)
                    .map_err(|error| format!("failed to start TUN pump: {error}"))?;
                (
                    aether::EmbeddedEndpoint::Tun {
                        device_to_tunnel: device_rx,
                        tunnel_to_device: tunnel_tx,
                    },
                    Some(pump),
                )
            } else {
                if tun_fd >= 0 {
                    unsafe { libc::close(tun_fd) };
                }
                (aether::EmbeddedEndpoint::Socks, None)
            };

            let (ready_tx, mut ready_rx) = oneshot::channel();
            let outcome = runtime.block_on(async {
                let mut engine = Box::pin(aether::run_embedded(embedded, endpoint, Some(ready_tx)));
                let mut stop_rx = Box::pin(stop_rx);
                tokio::select! {
                    result = &mut engine => result.map_err(|error| error.to_string()),
                    _ = &mut stop_rx => Ok(()),
                    ready = &mut ready_rx => {
                        if ready.is_ok() {
                            call_engine_ready(&vm, &listener)
                                .map_err(|error| error.to_string())?;
                        }
                        tokio::select! {
                            result = &mut engine => result.map_err(|error| error.to_string()),
                            _ = &mut stop_rx => Ok(()),
                        }
                    }
                }
            });
            STOP_SENDER.lock().take();
            drop(runtime);
            if let Some(pump) = pump {
                pump.stop();
            }
            outcome?;
            Ok(response(true, serde_json::json!({"stopped": true})))
        })()
        .unwrap_or_else(|error| {
            STOP_SENDER.lock().take();
            error_response(error)
        });
        java_string(env, &result)
    }))
    .unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_whitedns_whiteaesther_core_NativeAetherBridge_nativeStop(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
) -> jboolean {
    STOP_SENDER
        .lock()
        .take()
        .map(|sender| {
            let _ = sender.send(());
            JNI_TRUE
        })
        .unwrap_or(JNI_FALSE)
}

#[no_mangle]
pub extern "system" fn Java_com_whitedns_whiteaesther_core_NativeAetherBridge_nativeSetSocketProtector(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    protector: JObject<'_>,
) {
    let result = (|| -> jni::errors::Result<()> {
        if protector.is_null() {
            aether::set_socket_protector(None);
            return Ok(());
        }

        let vm = Arc::new(env.get_java_vm()?);
        let listener = Arc::new(env.new_global_ref(protector)?);
        aether::set_socket_protector(Some(Arc::new(move |fd| {
            call_socket_protector(&vm, &listener, fd).unwrap_or(false)
        })));
        Ok(())
    })();

    if let Err(error) = result {
        let _ = env.throw_new(
            "java/lang/IllegalStateException",
            format!("failed to register socket protector: {error}"),
        );
    }
}

fn call_socket_protector(vm: &JavaVM, listener: &GlobalRef, fd: i32) -> jni::errors::Result<bool> {
    let mut env = vm.attach_current_thread()?;
    env.call_method(
        listener.as_obj(),
        "protectSocket",
        "(I)Z",
        &[JValue::Int(fd)],
    )?
    .z()
}

fn call_engine_ready(vm: &JavaVM, listener: &GlobalRef) -> jni::errors::Result<()> {
    let mut env = vm.attach_current_thread()?;
    env.call_method(listener.as_obj(), "onNativeReady", "()V", &[])?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn validates_proxy_port_and_mode() {
        let valid = r#"{"mode":"proxy","configPath":"aether.toml","listenPort":1819}"#;
        assert!(BridgeConfig::parse(valid).is_ok());
        assert!(BridgeConfig::parse(r#"{"mode":"bad","configPath":"aether.toml"}"#).is_err());
        assert!(BridgeConfig::parse(
            r#"{"mode":"proxy","configPath":"aether.toml","listenPort":80}"#
        )
        .is_err());
        assert!(BridgeConfig::parse(
            r#"{"mode":"proxy","configPath":"aether.toml","peer":"162.159.197.3:443"}"#
        )
        .is_ok());
        assert!(BridgeConfig::parse(
            r#"{"mode":"proxy","configPath":"aether.toml","peer":"cloudflare.example:443"}"#
        )
        .is_err());
        assert!(BridgeConfig::parse(
            r#"{"mode":"proxy","configPath":"aether.toml","peerFallback":true}"#
        )
        .is_err());
    }

    #[test]
    fn reports_core_version() {
        assert_eq!(BRIDGE_VERSION.split('.').count(), 3);
        assert_eq!(aether::version(), "1.5.0");
    }
}
