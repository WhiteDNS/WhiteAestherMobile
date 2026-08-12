use serde::{Deserialize, Serialize};
use std::{
    collections::VecDeque,
    io::{BufRead, BufReader, Read},
    net::{IpAddr, SocketAddr},
    path::{Path, PathBuf},
    process::{Child, Command, Stdio},
    sync::{Arc, Mutex, MutexGuard},
    thread,
    time::{Duration, SystemTime, UNIX_EPOCH},
};
use tauri::{AppHandle, Emitter, Manager, State};

const MAX_LOGS: usize = 1_000;

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct CoreProfile {
    pub name: String,
    pub protocol: String,
    pub masque_transport: String,
    pub scan_mode: String,
    pub ip_family: String,
    pub socks_address: String,
    pub quick_reconnect: bool,
    pub validate_secs: u64,
    pub startup_secs: u64,
    pub reconnect_secs: u64,
    pub dns: Vec<String>,
    pub fragment_client_hello: bool,
    pub fragment_size: String,
    pub fragment_delay: String,
    pub data_check: bool,
    pub h2_peer: Option<String>,
    pub ech: Option<String>,
    pub tls_groups: Option<String>,
    pub performance_profile: String,
    pub keepalive_secs: u16,
    pub noize: String,
    pub profile_retry: bool,
    pub log_level: String,
    pub peer: Option<String>,
    pub wg_peer: Option<String>,
    pub core_path: Option<String>,
    pub route_block: String,
    pub route_direct: String,
    pub routes_file: Option<String>,
    pub team: Option<String>,
    pub access_client_id: Option<String>,
    pub access_client_secret: Option<String>,
    pub access_email: Option<String>,
    pub access_token: Option<String>,
    pub gateway: bool,
}

impl Default for CoreProfile {
    fn default() -> Self {
        Self {
            name: "Adaptive · Iran".into(),
            protocol: "masque".into(),
            masque_transport: "h2".into(),
            scan_mode: "balanced".into(),
            ip_family: "both".into(),
            socks_address: "127.0.0.1:1819".into(),
            quick_reconnect: true,
            validate_secs: 10,
            startup_secs: 30,
            reconnect_secs: 2,
            dns: vec!["1.1.1.1".into(), "1.0.0.1".into()],
            fragment_client_hello: true,
            fragment_size: "16-32".into(),
            fragment_delay: "2-10".into(),
            data_check: true,
            h2_peer: None,
            ech: None,
            tls_groups: None,
            performance_profile: "auto".into(),
            keepalive_secs: 5,
            noize: "balanced".into(),
            profile_retry: true,
            log_level: "info".into(),
            peer: None,
            wg_peer: None,
            core_path: None,
            route_block: String::new(),
            route_direct: String::new(),
            routes_file: None,
            team: None,
            access_client_id: None,
            access_client_secret: None,
            access_email: None,
            access_token: None,
            gateway: false,
        }
    }
}

impl CoreProfile {
    fn validate(&self) -> Result<(), String> {
        require_one_of("protocol", &self.protocol, &["masque", "wg", "gool"])?;
        require_one_of("MASQUE transport", &self.masque_transport, &["h2", "h3"])?;
        require_one_of(
            "scan mode",
            &self.scan_mode,
            &["turbo", "balanced", "thorough", "stealth", "ironclad"],
        )?;
        require_one_of("IP family", &self.ip_family, &["v4", "v6", "both"])?;
        require_one_of(
            "log level",
            &self.log_level,
            &["error", "warn", "info", "debug", "trace"],
        )?;
        require_one_of(
            "Noize profile",
            &self.noize,
            &["off", "light", "firewall", "balanced", "gfw", "aggressive"],
        )?;
        require_one_of(
            "performance profile",
            &self.performance_profile,
            &["auto", "low", "medium", "high"],
        )?;

        self.socks_address
            .parse::<SocketAddr>()
            .map_err(|_| "SOCKS address must be a valid IP:port".to_string())?;
        if !(1..=120).contains(&self.validate_secs) {
            return Err("validation deadline must be between 1 and 120 seconds".into());
        }
        if !(5..=300).contains(&self.startup_secs) {
            return Err("startup deadline must be between 5 and 300 seconds".into());
        }
        if self.reconnect_secs > 120 {
            return Err("reconnect delay must not exceed 120 seconds".into());
        }
        if !(1..=300).contains(&self.keepalive_secs) {
            return Err("WireGuard keepalive must be between 1 and 300 seconds".into());
        }
        if self.dns.is_empty() || self.dns.len() > 8 {
            return Err("one to eight DNS resolvers are required".into());
        }
        for resolver in &self.dns {
            resolver
                .parse::<IpAddr>()
                .map_err(|_| format!("invalid DNS resolver: {resolver}"))?;
        }
        validate_range("fragment size", &self.fragment_size, 1_500)?;
        validate_range("fragment delay", &self.fragment_delay, 10_000)?;
        validate_peer("peer", self.peer.as_deref())?;
        validate_peer("WireGuard peer", self.wg_peer.as_deref())?;
        validate_peer("HTTP/2 peer", self.h2_peer.as_deref())?;
        validate_optional_text("ECH configuration", self.ech.as_deref(), 32_768)?;
        validate_optional_text("TLS groups", self.tls_groups.as_deref(), 4_096)?;
        validate_text("block rules", &self.route_block, 64_000)?;
        validate_text("direct rules", &self.route_direct, 64_000)?;
        validate_optional_text("routes file", self.routes_file.as_deref(), 4_096)?;
        validate_optional_text("team", self.team.as_deref(), 253)?;
        validate_optional_text("Access client ID", self.access_client_id.as_deref(), 4_096)?;
        validate_optional_text(
            "Access client secret",
            self.access_client_secret.as_deref(),
            4_096,
        )?;
        validate_optional_text("Access email", self.access_email.as_deref(), 320)?;
        validate_optional_text("Access token", self.access_token.as_deref(), 32_768)?;
        Ok(())
    }

    fn args(&self, identity_path: &Path) -> Vec<String> {
        let mut args = vec![
            format!("--{}", self.protocol),
            "--scan".into(),
            self.scan_mode.clone(),
            "--ip".into(),
            self.ip_family.clone(),
            "--bind".into(),
            self.socks_address.clone(),
            "--validate-secs".into(),
            self.validate_secs.to_string(),
            "--startup-secs".into(),
            self.startup_secs.to_string(),
            "--reconnect-secs".into(),
            self.reconnect_secs.to_string(),
            "--dns".into(),
            self.dns.join(","),
            "--noize".into(),
            self.noize.clone(),
            "--keepalive".into(),
            self.keepalive_secs.to_string(),
            "--log-level".into(),
            self.log_level.clone(),
            "--config".into(),
            identity_path.to_string_lossy().into_owned(),
        ];

        if self.protocol == "masque" && self.masque_transport == "h2" {
            args.push("--h2".into());
        }
        if !self.data_check {
            args.push("--no-data-check".into());
        }
        args.push(if self.quick_reconnect {
            "--quick-reconnect".into()
        } else {
            "--no-quick-reconnect".into()
        });
        if self.fragment_client_hello && self.protocol == "masque" && self.masque_transport == "h2"
        {
            args.extend([
                "--fragment".into(),
                "--fragment-size".into(),
                self.fragment_size.clone(),
                "--fragment-delay".into(),
                self.fragment_delay.clone(),
            ]);
        }
        if !self.profile_retry {
            args.push("--no-profile-retry".into());
        }
        if let Some(peer) = non_empty(self.peer.as_deref()) {
            args.extend(["--peer".into(), peer.into()]);
        }
        if let Some(peer) = non_empty(self.wg_peer.as_deref()) {
            args.extend(["--wg-peer".into(), peer.into()]);
        }
        if let Some(peer) = non_empty(self.h2_peer.as_deref()) {
            args.extend(["--h2-peer".into(), peer.into()]);
        }
        if let Some(ech) = non_empty(self.ech.as_deref()).filter(|value| *value != "off") {
            args.extend(["--ech".into(), ech.into()]);
        }
        if let Some(groups) = non_empty(self.tls_groups.as_deref()) {
            args.extend(["--tls-groups".into(), groups.into()]);
        }
        if self.performance_profile != "auto" {
            args.extend(["--perf".into(), self.performance_profile.clone()]);
        }
        if !self.route_block.trim().is_empty() {
            args.extend(["--route-block".into(), self.route_block.clone()]);
        }
        if !self.route_direct.trim().is_empty() {
            args.extend(["--route-direct".into(), self.route_direct.clone()]);
        }
        if let Some(path) = non_empty(self.routes_file.as_deref()) {
            args.extend(["--routes".into(), path.into()]);
        }
        if self.gateway {
            args.push("--gateway".into());
        }
        args
    }
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CoreSnapshot {
    pub state: String,
    pub pid: Option<u32>,
    pub core_path: Option<String>,
    pub version: Option<String>,
    pub transport: Option<String>,
    pub endpoint: Option<String>,
    pub socks_address: String,
    pub latency_ms: Option<f64>,
    pub started_at: Option<u64>,
    pub last_error: Option<String>,
}

impl Default for CoreSnapshot {
    fn default() -> Self {
        Self {
            state: "idle".into(),
            pid: None,
            core_path: None,
            version: None,
            transport: None,
            endpoint: None,
            socks_address: "127.0.0.1:1819".into(),
            latency_ms: None,
            started_at: None,
            last_error: None,
        }
    }
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CoreLogEvent {
    pub timestamp: u64,
    pub stream: String,
    pub level: String,
    pub message: String,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CoreProbe {
    pub available: bool,
    pub path: Option<String>,
    pub version: Option<String>,
    pub message: String,
}

struct SupervisorInner {
    child: Mutex<Option<Child>>,
    snapshot: Mutex<CoreSnapshot>,
    logs: Mutex<VecDeque<CoreLogEvent>>,
}

#[derive(Clone)]
pub struct CoreSupervisor {
    inner: Arc<SupervisorInner>,
}

impl CoreSupervisor {
    pub fn new() -> Self {
        Self {
            inner: Arc::new(SupervisorInner {
                child: Mutex::new(None),
                snapshot: Mutex::new(CoreSnapshot::default()),
                logs: Mutex::new(VecDeque::with_capacity(MAX_LOGS)),
            }),
        }
    }

    pub fn shutdown(&self) {
        let _ = stop_inner(&self.inner, None);
    }
}

#[tauri::command]
pub fn runtime_info() -> serde_json::Value {
    serde_json::json!({
        "os": std::env::consts::OS,
        "arch": std::env::consts::ARCH,
    })
}

#[tauri::command]
pub fn probe_core(app: AppHandle, profile: Option<CoreProfile>) -> CoreProbe {
    let requested = profile.and_then(|value| value.core_path);
    match resolve_core_path(&app, requested.as_deref()) {
        Ok(path) => match core_version(&path) {
            Ok(version) => CoreProbe {
                available: true,
                path: Some(path.to_string_lossy().into_owned()),
                version: Some(version.clone()),
                message: format!("{version} is ready"),
            },
            Err(error) => CoreProbe {
                available: false,
                path: Some(path.to_string_lossy().into_owned()),
                version: None,
                message: error,
            },
        },
        Err(error) => CoreProbe {
            available: false,
            path: None,
            version: None,
            message: error,
        },
    }
}

#[tauri::command]
pub fn start_core(
    app: AppHandle,
    supervisor: State<'_, CoreSupervisor>,
    profile: CoreProfile,
) -> Result<CoreSnapshot, String> {
    profile.validate()?;
    if lock(&supervisor.inner.child).is_some() {
        return Err("Aether core is already running".into());
    }

    let core_path = resolve_core_path(&app, profile.core_path.as_deref())?;
    let version = core_version(&core_path)?;
    let config_dir = app
        .path()
        .app_config_dir()
        .map_err(|error| format!("cannot resolve app config directory: {error}"))?;
    let identity_dir = config_dir.join("identity");
    std::fs::create_dir_all(&identity_dir)
        .map_err(|error| format!("cannot create identity directory: {error}"))?;
    let identity_path = identity_dir.join("aether.toml");

    let mut command = Command::new(&core_path);
    command
        .args(profile.args(&identity_path))
        .current_dir(&config_dir)
        .env_remove("RUST_LOG")
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());
    set_optional_env(&mut command, "AETHER_TEAM", profile.team.as_deref());
    set_optional_env(
        &mut command,
        "AETHER_ACCESS_CLIENT_ID",
        profile.access_client_id.as_deref(),
    );
    set_optional_env(
        &mut command,
        "AETHER_ACCESS_CLIENT_SECRET",
        profile.access_client_secret.as_deref(),
    );
    set_optional_env(
        &mut command,
        "AETHER_ACCESS_EMAIL",
        profile.access_email.as_deref(),
    );
    set_optional_env(
        &mut command,
        "AETHER_ACCESS_TOKEN",
        profile.access_token.as_deref(),
    );
    hide_console_window(&mut command);

    let mut child = command
        .spawn()
        .map_err(|error| format!("failed to start Aether core: {error}"))?;
    let pid = child.id();
    let stdout = child.stdout.take();
    let stderr = child.stderr.take();

    {
        let mut snapshot = lock(&supervisor.inner.snapshot);
        *snapshot = CoreSnapshot {
            state: "starting".into(),
            pid: Some(pid),
            core_path: Some(core_path.to_string_lossy().into_owned()),
            version: Some(version),
            transport: Some(transport_label(&profile).into()),
            endpoint: None,
            socks_address: profile.socks_address.clone(),
            latency_ms: None,
            started_at: Some(now_millis()),
            last_error: None,
        };
        emit_snapshot(&app, &snapshot);
    }
    *lock(&supervisor.inner.child) = Some(child);

    if let Some(stdout) = stdout {
        spawn_log_reader(app.clone(), supervisor.inner.clone(), stdout, "stdout");
    }
    if let Some(stderr) = stderr {
        spawn_log_reader(app.clone(), supervisor.inner.clone(), stderr, "stderr");
    }
    spawn_exit_monitor(app.clone(), supervisor.inner.clone());

    Ok(lock(&supervisor.inner.snapshot).clone())
}

#[tauri::command]
pub fn stop_core(
    app: AppHandle,
    supervisor: State<'_, CoreSupervisor>,
) -> Result<CoreSnapshot, String> {
    stop_inner(&supervisor.inner, Some(&app))?;
    Ok(lock(&supervisor.inner.snapshot).clone())
}

#[tauri::command]
pub fn core_status(supervisor: State<'_, CoreSupervisor>) -> CoreSnapshot {
    lock(&supervisor.inner.snapshot).clone()
}

#[tauri::command]
pub fn core_logs(supervisor: State<'_, CoreSupervisor>) -> Vec<CoreLogEvent> {
    lock(&supervisor.inner.logs).iter().cloned().collect()
}

#[tauri::command]
pub fn save_profile(app: AppHandle, profile: CoreProfile) -> Result<CoreProfile, String> {
    profile.validate()?;
    let mut stored = profile.clone();
    stored.access_client_secret = None;
    stored.access_token = None;
    let path = profile_path(&app)?;
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent)
            .map_err(|error| format!("cannot create profile directory: {error}"))?;
    }
    let bytes = serde_json::to_vec_pretty(&stored)
        .map_err(|error| format!("cannot serialize profile: {error}"))?;
    std::fs::write(&path, bytes).map_err(|error| format!("cannot save profile: {error}"))?;
    Ok(profile)
}

#[tauri::command]
pub fn load_profile(app: AppHandle) -> Result<CoreProfile, String> {
    let path = profile_path(&app)?;
    if !path.exists() {
        return Ok(CoreProfile::default());
    }
    let bytes = std::fs::read(&path).map_err(|error| format!("cannot read profile: {error}"))?;
    let profile: CoreProfile =
        serde_json::from_slice(&bytes).map_err(|error| format!("profile is invalid: {error}"))?;
    profile.validate()?;
    Ok(profile)
}

fn spawn_log_reader<R: Read + Send + 'static>(
    app: AppHandle,
    inner: Arc<SupervisorInner>,
    reader: R,
    stream: &'static str,
) {
    thread::spawn(move || {
        for line in BufReader::new(reader).lines().map_while(Result::ok) {
            record_log(&app, &inner, stream, line);
        }
    });
}

fn spawn_exit_monitor(app: AppHandle, inner: Arc<SupervisorInner>) {
    thread::spawn(move || loop {
        thread::sleep(Duration::from_millis(350));
        let exit = {
            let mut guard = lock(&inner.child);
            let Some(child) = guard.as_mut() else {
                return;
            };
            match child.try_wait() {
                Ok(Some(status)) => {
                    guard.take();
                    Some(Ok(status))
                }
                Ok(None) => None,
                Err(error) => {
                    guard.take();
                    Some(Err(error.to_string()))
                }
            }
        };

        let Some(exit) = exit else { continue };
        let mut snapshot = lock(&inner.snapshot);
        snapshot.pid = None;
        match exit {
            Ok(status) if status.success() => snapshot.state = "stopped".into(),
            Ok(status) => {
                snapshot.state = "error".into();
                snapshot.last_error = Some(format!("Aether core exited with {status}"));
            }
            Err(error) => {
                snapshot.state = "error".into();
                snapshot.last_error = Some(format!("cannot monitor Aether core: {error}"));
            }
        }
        emit_snapshot(&app, &snapshot);
        return;
    });
}

fn record_log(app: &AppHandle, inner: &SupervisorInner, stream: &str, message: String) {
    let event = CoreLogEvent {
        timestamp: now_millis(),
        stream: stream.into(),
        level: log_level(&message).into(),
        message: message.trim().to_string(),
    };
    {
        let mut logs = lock(&inner.logs);
        if logs.len() == MAX_LOGS {
            logs.pop_front();
        }
        logs.push_back(event.clone());
    }
    let _ = app.emit("core-log", &event);

    let mut snapshot = lock(&inner.snapshot);
    apply_log_to_snapshot(&event.message, &mut snapshot);
    emit_snapshot(app, &snapshot);
}

fn apply_log_to_snapshot(message: &str, snapshot: &mut CoreSnapshot) {
    if message.contains("hunting for a working") || message.contains("verifying cached") {
        snapshot.state = "scanning".into();
    }
    if message.contains("MASQUE transport: HTTP/2") {
        snapshot.transport = Some("masque-h2".into());
        snapshot.state = "connecting".into();
    } else if message.contains("MASQUE transport: HTTP/3") {
        snapshot.transport = Some("masque-h3".into());
        snapshot.state = "connecting".into();
    } else if message.contains("validating WireGuard tunnel") {
        snapshot.transport = Some("wireguard".into());
        snapshot.state = "connecting".into();
    }
    if let Some(endpoint) = parse_endpoint(message) {
        snapshot.endpoint = Some(endpoint);
    }
    if let Some(latency) = parse_latency_ms(message) {
        snapshot.latency_ms = Some(latency);
    }
    if message.contains("socks5 server listening on") || message.contains("socks5 listening on") {
        snapshot.state = "connected".into();
        if let Some(address) = message.split("listening on ").nth(1) {
            snapshot.socks_address = address
                .split_whitespace()
                .next()
                .unwrap_or(address)
                .to_string();
        }
    }
    if message.contains("reconnecting") {
        snapshot.state = "reconnecting".into();
    }
    if (message.contains(" ERROR ") || message.starts_with("ERROR"))
        && !message.contains("reconnecting")
    {
        snapshot.last_error = Some(strip_logger_prefix(message));
    }
}

fn parse_endpoint(message: &str) -> Option<String> {
    const MARKERS: [&str; 5] = [
        "selected MASQUE gateway ",
        "selected WireGuard endpoint ",
        "using cloudflare edge ",
        "cached gateway ",
        "cached endpoint ",
    ];
    for marker in MARKERS {
        let Some(rest) = message.split(marker).nth(1) else {
            continue;
        };
        let candidate = rest
            .split_whitespace()
            .next()?
            .trim_end_matches(|c| c == ',' || c == ')');
        if candidate.parse::<SocketAddr>().is_ok() {
            return Some(candidate.into());
        }
    }
    None
}

fn parse_latency_ms(message: &str) -> Option<f64> {
    let rest = message.split("rtt ").nth(1)?;
    let token = rest
        .split_whitespace()
        .next()?
        .trim_end_matches(|c| c == ')' || c == ',');
    if let Some(value) = token.strip_suffix("ms") {
        return value.parse().ok();
    }
    if let Some(value) = token.strip_suffix('s') {
        return value.parse::<f64>().ok().map(|seconds| seconds * 1_000.0);
    }
    None
}

fn stop_inner(inner: &SupervisorInner, app: Option<&AppHandle>) -> Result<(), String> {
    let mut child = lock(&inner.child).take();
    if let Some(child) = child.as_mut() {
        child
            .kill()
            .map_err(|error| format!("failed to stop Aether core: {error}"))?;
        let _ = child.wait();
    }
    let mut snapshot = lock(&inner.snapshot);
    snapshot.state = "idle".into();
    snapshot.pid = None;
    snapshot.transport = None;
    snapshot.endpoint = None;
    snapshot.latency_ms = None;
    snapshot.started_at = None;
    snapshot.last_error = None;
    if let Some(app) = app {
        emit_snapshot(app, &snapshot);
    }
    Ok(())
}

fn resolve_core_path(app: &AppHandle, requested: Option<&str>) -> Result<PathBuf, String> {
    let mut candidates = Vec::new();
    if let Some(path) = non_empty(requested) {
        candidates.push(PathBuf::from(path));
    }
    if let Ok(path) = std::env::var("WHITEAESTHER_CORE_PATH") {
        if !path.trim().is_empty() {
            candidates.push(PathBuf::from(path));
        }
    }
    if let Ok(resource_dir) = app.path().resource_dir() {
        candidates.push(resource_dir.join(core_filename()));
        candidates.push(resource_dir.join("binaries").join(core_filename()));
    }
    if let Ok(current_exe) = std::env::current_exe() {
        if let Some(parent) = current_exe.parent() {
            candidates.push(parent.join(core_filename()));
        }
    }
    if let Ok(current_dir) = std::env::current_dir() {
        candidates.push(current_dir.join(core_filename()));
        candidates.push(
            current_dir
                .join("..")
                .join("Aether")
                .join("aether")
                .join("target")
                .join("debug")
                .join(core_filename()),
        );
    }

    for candidate in candidates {
        if !candidate.is_file() {
            continue;
        }
        let canonical = candidate
            .canonicalize()
            .map_err(|error| format!("cannot resolve core path: {error}"))?;
        let filename = canonical
            .file_stem()
            .and_then(|value| value.to_str())
            .unwrap_or_default()
            .to_ascii_lowercase();
        if filename == "aether" || filename.starts_with("aether-") {
            return Ok(canonical);
        }
    }
    Err("Aether core was not found. Choose the aether executable in Preferences or set WHITEAESTHER_CORE_PATH.".into())
}

fn core_version(path: &Path) -> Result<String, String> {
    let output = Command::new(path)
        .arg("--version")
        .stdin(Stdio::null())
        .output()
        .map_err(|error| format!("cannot run Aether core: {error}"))?;
    if !output.status.success() {
        return Err(format!(
            "Aether version check failed with {}",
            output.status
        ));
    }
    let version = String::from_utf8_lossy(&output.stdout).trim().to_string();
    if !version.to_ascii_lowercase().starts_with("aether ") {
        return Err("the selected executable is not an Aether core".into());
    }
    Ok(version)
}

fn profile_path(app: &AppHandle) -> Result<PathBuf, String> {
    app.path()
        .app_config_dir()
        .map(|path| path.join("profiles").join("default.json"))
        .map_err(|error| format!("cannot resolve app config directory: {error}"))
}

fn transport_label(profile: &CoreProfile) -> &'static str {
    match (profile.protocol.as_str(), profile.masque_transport.as_str()) {
        ("masque", "h2") => "masque-h2",
        ("masque", _) => "masque-h3",
        ("wg", _) => "wireguard",
        _ => "warp-in-warp",
    }
}

fn validate_peer(label: &str, value: Option<&str>) -> Result<(), String> {
    if let Some(value) = non_empty(value) {
        value
            .parse::<SocketAddr>()
            .map_err(|_| format!("{label} must be a valid IP:port"))?;
    }
    Ok(())
}

fn validate_range(label: &str, value: &str, maximum: u64) -> Result<(), String> {
    let values: Vec<&str> = value.split('-').collect();
    if values.is_empty() || values.len() > 2 {
        return Err(format!("{label} must be a number or range"));
    }
    let mut parsed = Vec::new();
    for item in values {
        let number = item
            .parse::<u64>()
            .map_err(|_| format!("{label} must contain only positive numbers"))?;
        if number > maximum {
            return Err(format!("{label} must not exceed {maximum}"));
        }
        parsed.push(number);
    }
    if parsed.len() == 2 && parsed[0] > parsed[1] {
        return Err(format!("{label} range must be ascending"));
    }
    Ok(())
}

fn validate_optional_text(label: &str, value: Option<&str>, maximum: usize) -> Result<(), String> {
    if let Some(value) = non_empty(value) {
        validate_text(label, value, maximum)?;
    }
    Ok(())
}

fn validate_text(label: &str, value: &str, maximum: usize) -> Result<(), String> {
    if value.len() > maximum {
        return Err(format!("{label} is too long"));
    }
    if value.contains('\0') {
        return Err(format!("{label} contains an invalid character"));
    }
    Ok(())
}

fn set_optional_env(command: &mut Command, key: &str, value: Option<&str>) {
    if let Some(value) = non_empty(value) {
        command.env(key, value);
    }
}

fn require_one_of(label: &str, value: &str, options: &[&str]) -> Result<(), String> {
    if options.contains(&value) {
        Ok(())
    } else {
        Err(format!("unsupported {label}: {value}"))
    }
}

fn non_empty(value: Option<&str>) -> Option<&str> {
    value.map(str::trim).filter(|value| !value.is_empty())
}

fn core_filename() -> &'static str {
    if cfg!(windows) {
        "aether.exe"
    } else {
        "aether"
    }
}

fn log_level(message: &str) -> &'static str {
    if message.contains(" ERROR ") || message.starts_with("ERROR") {
        "error"
    } else if message.contains(" WARN ") || message.starts_with("WARN") {
        "warn"
    } else if message.contains(" DEBUG ") || message.starts_with("DEBUG") {
        "debug"
    } else if message.contains(" TRACE ") || message.starts_with("TRACE") {
        "trace"
    } else {
        "info"
    }
}

fn strip_logger_prefix(message: &str) -> String {
    message
        .split(" - ")
        .last()
        .unwrap_or(message)
        .trim()
        .to_string()
}

fn emit_snapshot(app: &AppHandle, snapshot: &CoreSnapshot) {
    let _ = app.emit("core-status", snapshot);
}

fn now_millis() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}

fn lock<T>(mutex: &Mutex<T>) -> MutexGuard<'_, T> {
    mutex
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
}

#[cfg(windows)]
fn hide_console_window(command: &mut Command) {
    use std::os::windows::process::CommandExt;
    command.creation_flags(0x08000000);
}

#[cfg(not(windows))]
fn hide_console_window(_command: &mut Command) {}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn default_profile_is_valid_and_non_interactive() {
        let profile = CoreProfile::default();
        profile.validate().unwrap();
        let args = profile.args(Path::new("identity.toml"));
        assert!(args.windows(2).any(|pair| pair == ["--masque", "--scan"]));
        assert!(args.contains(&"--h2".to_string()));
        assert!(args.contains(&"--dual".to_string()) == false);
        assert!(args.windows(2).any(|pair| pair == ["--ip", "both"]));
        assert!(args.contains(&"--quick-reconnect".to_string()));
    }

    #[test]
    fn log_parser_only_connects_after_socks_listener() {
        let mut snapshot = CoreSnapshot::default();
        apply_log_to_snapshot(
            "[+] selected MASQUE gateway 162.159.192.18:443 (rtt 84.5ms)",
            &mut snapshot,
        );
        assert_eq!(snapshot.endpoint.as_deref(), Some("162.159.192.18:443"));
        assert_eq!(snapshot.latency_ms, Some(84.5));
        assert_ne!(snapshot.state, "connected");
        apply_log_to_snapshot(
            "[+] socks5 server listening on 127.0.0.1:1819",
            &mut snapshot,
        );
        assert_eq!(snapshot.state, "connected");
    }

    #[test]
    fn invalid_values_are_rejected() {
        let mut profile = CoreProfile::default();
        profile.socks_address = "localhost".into();
        assert!(profile.validate().is_err());
        profile = CoreProfile::default();
        profile.fragment_size = "32-16".into();
        assert!(profile.validate().is_err());
    }
}
