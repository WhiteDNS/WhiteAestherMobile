//! JNI entry points for the exit chain.
//!
//! Kept apart from the engine's own entry points because the two are
//! independent: the chain is optional and loaded at run time, and a build
//! without its library must still start.

use std::sync::Arc;

use jni::objects::{JClass, JObject, JString};
use jni::sys::{jboolean, jint, jobjectArray, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;

use crate::chain;
use crate::{call_socket_protector, java_string};

/// Reports whether the chain library is present and loadable.
///
/// Separate from starting it so the UI can hide the feature on a build that
/// does not ship it, rather than offering a switch that always fails.
#[no_mangle]
pub extern "system" fn Java_com_whitedns_whiteaesther_core_NativeChainBridge_nativeAvailable(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
) -> jboolean {
    if chain::available() {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

/// Sends one action to mihomo and returns its reply verbatim.
///
/// The reply is JSON and is parsed on the Kotlin side. Going through the action
/// protocol rather than mihomo's HTTP control API is deliberate: that API is
/// Go's `net/http`, which chunk-frames any reply too large to buffer, and the
/// desktop client shipped a version that parsed `/version` at 35 bytes and
/// failed on the node list for exactly that reason.
#[no_mangle]
pub extern "system" fn Java_com_whitedns_whiteaesther_core_NativeChainBridge_nativeInvoke<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    params: JString<'local>,
) -> jstring {
    let params: String = match env.get_string(&params) {
        Ok(value) => value.into(),
        Err(error) => return java_string(env, &failure(&error.to_string())),
    };

    match chain::invoke(&params) {
        Ok(reply) => java_string(env, &reply),
        Err(reason) => java_string(env, &failure(&reason)),
    }
}

/// Hands mihomo the tun descriptor. Ownership passes to the chain.
#[no_mangle]
pub extern "system" fn Java_com_whitedns_whiteaesther_core_NativeChainBridge_nativeStartTun<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    fd: jint,
    stack: JString<'local>,
    address: JString<'local>,
    dns: JString<'local>,
) -> jstring {
    let read = |env: &mut JNIEnv<'local>, value: JString<'local>| -> Result<String, String> {
        env.get_string(&value)
            .map(Into::into)
            .map_err(|error| error.to_string())
    };

    let result = (|| -> Result<(), String> {
        let stack = read(&mut env, stack)?;
        let address = read(&mut env, address)?;
        let dns = read(&mut env, dns)?;
        chain::start_tun(fd, &stack, &address, &dns)
    })();

    match result {
        Ok(()) => java_string(env, r#"{"ok":true}"#),
        Err(reason) => java_string(env, &failure(&reason)),
    }
}

/// Takes every event mihomo has produced since the last call.
///
/// Pulled rather than pushed because these arrive on Go's own threads, and
/// pushing would mean attaching each one to the JVM for a log line.
#[no_mangle]
pub extern "system" fn Java_com_whitedns_whiteaesther_core_NativeChainBridge_nativeDrainEvents<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jobjectArray {
    let events = chain::drain_events();
    let empty = match env.new_string("") {
        Ok(value) => value,
        Err(_) => return std::ptr::null_mut(),
    };
    let array = match env.new_object_array(events.len() as i32, "java/lang/String", &empty) {
        Ok(value) => value,
        Err(_) => return std::ptr::null_mut(),
    };
    for (index, event) in events.iter().enumerate() {
        let Ok(value) = env.new_string(event) else { continue };
        let _ = env.set_object_array_element(&array, index as i32, value);
    }
    array.into_raw()
}

/// Starts delivering mihomo's events into the buffer [`nativeDrainEvents`]
/// reads.
#[no_mangle]
pub extern "system" fn Java_com_whitedns_whiteaesther_core_NativeChainBridge_nativeListenForEvents(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
) -> jboolean {
    if chain::listen_for_events().is_ok() {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

#[no_mangle]
pub extern "system" fn Java_com_whitedns_whiteaesther_core_NativeChainBridge_nativeStopTun(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
) {
    chain::stop_tun();
}

/// Gives the chain a way to reach `VpnService.protect()`.
///
/// mihomo opens sockets of its own -- to the node, for DNS, for subscription
/// downloads and health checks -- and the tun carries a default route. Every one
/// of those that is not protected is captured by the tunnel mihomo is building.
#[no_mangle]
pub extern "system" fn Java_com_whitedns_whiteaesther_core_NativeChainBridge_nativeSetSocketProtector<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    protector: JObject<'local>,
) {
    if protector.is_null() {
        chain::set_socket_protector(None);
        return;
    }

    let wired = (|| -> jni::errors::Result<()> {
        let vm = Arc::new(env.get_java_vm()?);
        let listener = Arc::new(env.new_global_ref(protector)?);
        chain::set_socket_protector(Some(Arc::new(move |fd| {
            call_socket_protector(&vm, &listener, fd).unwrap_or(false)
        })));
        Ok(())
    })();

    if wired.is_err() {
        // Better to have no protector than one that cannot be called: the
        // caller can still refuse to start the chain.
        chain::set_socket_protector(None);
    }
}

/// Shaped like the action protocol's own replies so the Kotlin side has one
/// thing to parse whether the failure came from mihomo or from getting to it.
fn failure(reason: &str) -> String {
    format!(
        r#"{{"ok":false,"error":{}}}"#,
        serde_json::to_string(reason).unwrap_or_else(|_| "\"unknown\"".into())
    )
}
