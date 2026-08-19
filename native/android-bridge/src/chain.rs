//! Loads the Go library that carries mihomo, and gives Kotlin a way to drive it.
//!
//! # Why the chain lives behind this bridge rather than beside it
//!
//! The exit chain needs a second engine, and mihomo is Go. Go supports one
//! runtime per process, so there can be exactly one `-buildmode=c-shared`
//! library: two of them export the same runtime symbols into a single linker
//! namespace, and a call binding to the wrong copy enters a runtime that has
//! never heard of the calling goroutine. This crate is Rust, so Rust plus that
//! one Go library is fine.
//!
//! It is loaded with `dlopen` rather than linked, so the chain is optional. A
//! build that does not ship the library still runs; the chain simply reports
//! itself unavailable instead of the app failing to start.
//!
//! # The contract
//!
//! The Go side calls back through five function pointers it expects the host to
//! fill in. Ownership across that boundary is not symmetric, and getting it
//! wrong is a double free:
//!
//! - strings we pass in are freed by us, through `free_string`, once Go has
//!   copied them
//! - the string Go passes to `result` belongs to Go, which frees it when
//!   `result` returns, so it must be copied rather than taken
//! - the opaque callback handle is ours, and Go says when to drop it through
//!   `release_object`
//!
//! `protect` is the important one. mihomo calls it for every socket it opens,
//! and it has to reach `VpnService.protect()`. Without that, the tun's default
//! route pulls mihomo's own connections back into the tunnel it is building.

use std::collections::VecDeque;
use std::ffi::{c_char, c_int, c_void, CStr, CString};
use std::sync::mpsc::{self, RecvTimeoutError, Sender};
use std::sync::Arc;
use std::time::Duration;

use once_cell::sync::OnceCell;
use parking_lot::Mutex;

/// How long to wait for an action to answer. Generous, because importing a
/// subscription reaches the network.
const ACTION_TIMEOUT: Duration = Duration::from_secs(60);

const LIBRARY: &[u8] = b"libwhiteaestherchain.so\0";

type InvokeMethod = unsafe extern "C" fn(*mut c_void, *mut c_char);
type SetEventListener = unsafe extern "C" fn(*mut c_void);
/// Returns `true` always -- see [`start_tun`]. Kept in the signature because
/// that is what the library exports, not because it carries a verdict.
type StartTun =
    unsafe extern "C" fn(*mut c_void, c_int, *mut c_char, *mut c_char, *mut c_char) -> u8;
type StopTun = unsafe extern "C" fn();

type FreeString = unsafe extern "C" fn(*mut c_char);
type ReleaseObject = unsafe extern "C" fn(*mut c_void);
type Result_ = unsafe extern "C" fn(*mut c_void, *const c_char);
type Protect = unsafe extern "C" fn(*mut c_void, c_int);
type ResolveProcess =
    unsafe extern "C" fn(*mut c_void, c_int, *const c_char, *const c_char, c_int) -> *mut c_char;

/// Reaches `VpnService.protect()`. Named because it appears in three signatures
/// and spelling it out each time is what clippy objects to.
pub type SocketProtector = Arc<dyn Fn(i32) -> bool + Send + Sync>;

/// Set by the service so mihomo's sockets can be kept out of the tun.
static PROTECTOR: Mutex<Option<SocketProtector>> = Mutex::new(None);

pub fn set_socket_protector(protector: Option<SocketProtector>) {
    *PROTECTOR.lock() = protector;
}

struct Core {
    invoke_method: InvokeMethod,
    start_tun: StartTun,
    stop_tun: StopTun,
    set_event_listener: SetEventListener,
}

/// What a callback handle means when Go calls back through it.
///
/// Go has one `result` entry point and uses it for both a one-shot reply and
/// the event stream, so the handle has to carry which it is. Getting this wrong
/// is not a wrong answer -- it is a `Sender` interpreted as an event sink, or a
/// `Box` freed as the wrong type.
enum Callback {
    /// One action's reply. Sent once, then released.
    Reply(Sender<String>),
    /// The event stream. Lives for the process; never released by Go.
    Events,
}

/// Recent events from mihomo, newest last.
///
/// Buffered rather than pushed to Kotlin, because these arrive on Go's own
/// threads and pushing would mean attaching each one to the JVM. Bounded,
/// because at log level info a busy tunnel produces a line per connection and
/// nothing guarantees anyone is draining.
static EVENTS: Mutex<VecDeque<String>> = Mutex::new(VecDeque::new());

const MAX_EVENTS: usize = 512;

// Only ever read after initialisation, and the Go side is internally
// synchronised.
unsafe impl Send for Core {}
unsafe impl Sync for Core {}

static CORE: OnceCell<Result<Core, String>> = OnceCell::new();

/// Resolves a symbol, naming the missing one rather than leaving a null to be
/// called later.
unsafe fn symbol(handle: *mut c_void, name: &[u8]) -> Result<*mut c_void, String> {
    let address = libc::dlsym(handle, name.as_ptr() as *const c_char);
    if address.is_null() {
        return Err(format!(
            "{} is missing from the chain library",
            String::from_utf8_lossy(&name[..name.len() - 1])
        ));
    }
    Ok(address)
}

fn load() -> Result<Core, String> {
    unsafe {
        let handle = libc::dlopen(LIBRARY.as_ptr() as *const c_char, libc::RTLD_NOW);
        if handle.is_null() {
            let reason = libc::dlerror();
            let reason = if reason.is_null() {
                "not present".to_string()
            } else {
                CStr::from_ptr(reason).to_string_lossy().into_owned()
            };
            return Err(format!("chain library did not load: {reason}"));
        }

        // Fill in the host side of the contract before anything can call back.
        *(symbol(handle, b"free_string_func\0")? as *mut Option<FreeString>) =
            Some(host_free_string);
        *(symbol(handle, b"release_object_func\0")? as *mut Option<ReleaseObject>) =
            Some(host_release_object);
        *(symbol(handle, b"result_func\0")? as *mut Option<Result_>) = Some(host_result);
        *(symbol(handle, b"protect_func\0")? as *mut Option<Protect>) = Some(host_protect);
        *(symbol(handle, b"resolve_process_func\0")? as *mut Option<ResolveProcess>) =
            Some(host_resolve_process);

        Ok(Core {
            invoke_method: std::mem::transmute::<*mut c_void, InvokeMethod>(symbol(
                handle,
                b"invokeMethod\0",
            )?),
            start_tun: std::mem::transmute::<*mut c_void, StartTun>(symbol(handle, b"startTUN\0")?),
            stop_tun: std::mem::transmute::<*mut c_void, StopTun>(symbol(handle, b"stopTun\0")?),
            set_event_listener: std::mem::transmute::<*mut c_void, SetEventListener>(symbol(
                handle,
                b"setEventListener\0",
            )?),
        })
    }
}

fn core() -> Result<&'static Core, String> {
    match CORE.get_or_init(load) {
        Ok(core) => Ok(core),
        Err(reason) => Err(reason.clone()),
    }
}

pub fn available() -> bool {
    core().is_ok()
}

// ------------------------------------------------------------ host side ----

/// Frees a string we allocated, once Go has copied it.
unsafe extern "C" fn host_free_string(data: *mut c_char) {
    if !data.is_null() {
        drop(CString::from_raw(data));
    }
}

/// Go is finished with a callback handle.
unsafe extern "C" fn host_release_object(object: *mut c_void) {
    if !object.is_null() {
        drop(Box::from_raw(object as *mut Callback));
    }
}

/// A reply, or an event. The string belongs to Go and is freed when this
/// returns, so it is copied here rather than taken.
unsafe extern "C" fn host_result(callback: *mut c_void, data: *const c_char) {
    if callback.is_null() {
        return;
    }
    let payload = if data.is_null() {
        String::new()
    } else {
        CStr::from_ptr(data).to_string_lossy().into_owned()
    };

    match &*(callback as *const Callback) {
        Callback::Reply(sender) => {
            let _ = sender.send(payload);
        }
        Callback::Events => {
            let mut events = EVENTS.lock();
            if events.len() >= MAX_EVENTS {
                events.pop_front();
            }
            events.push_back(payload);
        }
    }
}

/// Keeps mihomo's own sockets out of the tunnel it is building.
unsafe extern "C" fn host_protect(_tun: *mut c_void, fd: c_int) {
    let protector = PROTECTOR.lock().clone();
    if let Some(protect) = protector {
        protect(fd);
    }
}

/// Rule matching by owning process. Deliberately not wired up: every rule we
/// render matches on destination, so answering nothing is honest where a stub
/// would quietly claim a process it never looked up.
unsafe extern "C" fn host_resolve_process(
    _tun: *mut c_void,
    _protocol: c_int,
    _source: *const c_char,
    _target: *const c_char,
    _uid: c_int,
) -> *mut c_char {
    std::ptr::null_mut()
}

// ----------------------------------------------------------------- api ----

/// Sends one action and waits for its reply.
///
/// `invokeMethod` answers on another goroutine, so this bridges async to sync
/// over a channel. The timeout matters: a reply that never arrives would
/// otherwise hold the calling thread for the life of the process.
pub fn invoke(params: &str) -> Result<String, String> {
    let core = core()?;
    let (tx, rx) = mpsc::channel::<String>();
    let callback = Box::into_raw(Box::new(Callback::Reply(tx))) as *mut c_void;
    let owned = CString::new(params).map_err(|_| "action contains a null byte".to_string())?;

    unsafe { (core.invoke_method)(callback, owned.into_raw()) };

    match rx.recv_timeout(ACTION_TIMEOUT) {
        Ok(reply) => Ok(reply),
        Err(RecvTimeoutError::Timeout) => Err("the chain did not answer in time".into()),
        // Go released the callback without replying.
        Err(RecvTimeoutError::Disconnected) => Err("the chain closed the reply channel".into()),
    }
}

/// Hands mihomo the tun. Ownership of the descriptor passes to Go.
///
/// The callback here is not a reply channel, unlike the one in [`invoke`]. Go
/// keeps it for the life of the tun and calls back through it for `protect` and
/// for process resolution, and releases it when the tun closes. So nothing is
/// waited on: `startTUN` returns `true` whether or not the interface came up --
/// it reports that the call was made, not that it worked -- and the only honest
/// proof is traffic arriving at the far end.
pub fn start_tun(fd: i32, stack: &str, address: &str, dns: &str) -> Result<(), String> {
    let core = core()?;
    let (tx, _rx) = mpsc::channel::<String>();
    let callback = Box::into_raw(Box::new(Callback::Reply(tx))) as *mut c_void;

    let stack = CString::new(stack).map_err(|_| "stack contains a null byte".to_string())?;
    let address = CString::new(address).map_err(|_| "address contains a null byte".to_string())?;
    let dns = CString::new(dns).map_err(|_| "dns contains a null byte".to_string())?;

    unsafe {
        (core.start_tun)(
            callback,
            fd,
            stack.into_raw(),
            address.into_raw(),
            dns.into_raw(),
        )
    };
    Ok(())
}

pub fn stop_tun() {
    if let Ok(core) = core() {
        unsafe { (core.stop_tun)() };
    }
}

/// Registers the event sink, once. mihomo's logs and its delay and connection
/// notices all arrive through it.
///
/// The handle is deliberately leaked: Go holds it for the life of the process
/// and only releases it when a new listener replaces it, which never happens
/// here.
pub fn listen_for_events() -> Result<(), String> {
    static REGISTERED: OnceCell<()> = OnceCell::new();
    let core = core()?;
    REGISTERED.get_or_init(|| {
        let callback = Box::into_raw(Box::new(Callback::Events)) as *mut c_void;
        unsafe { (core.set_event_listener)(callback) };
    });
    Ok(())
}

/// Takes everything buffered since the last call.
///
/// Draining rather than reading keeps the buffer from being re-reported, and
/// means a caller that stops asking cannot grow it without bound.
pub fn drain_events() -> Vec<String> {
    EVENTS.lock().drain(..).collect()
}
