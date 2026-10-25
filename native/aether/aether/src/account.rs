use crate::apifront;
use base64::Engine;
use boring::asn1::Asn1Time;
use boring::bn::BigNum;
use boring::ec::{EcGroup, EcKey};
use boring::hash::MessageDigest;
use boring::nid::Nid;
use boring::pkey::PKey;
use boring::x509::{X509Builder, X509NameBuilder};
use rand::Rng;
use serde::{Deserialize, Serialize};

use crate::consts;
use crate::error::{AetherError, Result};

#[derive(Debug, Clone, Serialize)]
struct Registration {
    key: String,
    install_id: String,
    fcm_token: String,
    tos: String,
    model: String,
    serial_number: String,
    os_version: String,
    key_type: String,
    tunnel_type: String,
    locale: String,
}

#[derive(Debug, Clone, Serialize)]
struct TeamRegistration {
    key: String,
    install_id: String,
    fcm_token: String,
    tos: String,
    model: String,
    name: String,
    serial_number: String,
    locale: String,
}

fn team_registration_body(public_key: String, model: &str, locale: &str) -> TeamRegistration {
    let install_id = crate::zerotrust::generate_install_id();
    let fcm_token = crate::zerotrust::generate_fcm_token(&install_id);

    TeamRegistration {
        key: public_key,
        tos: tos_timestamp(),
        model: model.to_string(),
        name: install_id.clone(),
        serial_number: install_id.clone(),
        locale: locale.to_string(),
        install_id,
        fcm_token,
    }
}

#[derive(Debug, Clone, Serialize)]
struct DeviceUpdate {
    key: String,
    key_type: String,
    tunnel_type: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    name: Option<String>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct AccountData {
    pub id: String,
    #[serde(default)]
    pub token: String,
    #[serde(default)]
    pub config: Config,
    #[serde(default)]
    pub account: AccountInfo,
}

#[derive(Debug, Clone, Default, Deserialize)]
pub struct Config {
    #[serde(default)]
    pub interface: Interface,
    #[serde(default)]
    pub peers: Vec<Peer>,
    #[serde(default)]
    pub client_id: String,
    #[serde(default)]
    pub services: Services,
}

#[derive(Debug, Clone, Default, Deserialize)]
pub struct Services {
    #[serde(default)]
    pub http_proxy: String,
}

#[derive(Debug, Clone, Deserialize)]
pub struct Peer {
    pub public_key: String,
    #[serde(default)]
    pub endpoint: PeerEndpoint,
}

#[derive(Debug, Clone, Default, Deserialize)]
pub struct PeerEndpoint {
    #[serde(default)]
    pub v4: String,
    #[serde(default)]
    pub v6: String,
    #[serde(default)]
    pub host: String,
}

#[derive(Debug, Clone, Default, Deserialize)]
pub struct AccountInfo {
    #[serde(default)]
    pub id: String,
    #[serde(default)]
    pub account_type: String,
    #[serde(default)]
    pub organization: String,
}

#[derive(Debug, Clone, Default, Deserialize)]
pub struct Interface {
    #[serde(default)]
    pub addresses: Addresses,
}

#[derive(Debug, Clone, Default, Deserialize)]
pub struct Addresses {
    #[serde(default)]
    pub v4: String,
    #[serde(default)]
    pub v6: String,
}

#[derive(Debug, Clone)]
pub struct Identity {
    pub device_id: String,
    pub access_token: String,
    pub cert_pem: Vec<u8>,
    pub key_pem: Vec<u8>,
    pub cert_issued_at: u64,
    pub ipv4: String,
    pub ipv6: String,
    pub wg_private_key: [u8; 32],
    pub wg_peer_public_key: [u8; 32],
    pub client_id: [u8; 3],
    pub organization: String,
    pub gateway_proxy: String,
    pub assigned_endpoint: String,
    pub refused: bool,
}

pub struct MasqueKeyPair {
    pub key_pem: Vec<u8>,
    pub cert_pem: Vec<u8>,
    pub spki_der: Vec<u8>,
}

pub fn generate_masque_keypair() -> Result<MasqueKeyPair> {
    let group = EcGroup::from_curve_name(Nid::X9_62_PRIME256V1)
        .map_err(|e| AetherError::Tls(e.to_string()))?;
    let ec = EcKey::generate(&group).map_err(|e| AetherError::Tls(e.to_string()))?;
    let pkey = PKey::from_ec_key(ec).map_err(|e| AetherError::Tls(e.to_string()))?;

    let key_pem = pkey
        .private_key_to_pem_pkcs8()
        .map_err(|e| AetherError::Tls(e.to_string()))?;
    let spki_der = pkey
        .public_key_to_der()
        .map_err(|e| AetherError::Tls(e.to_string()))?;

    let mut builder = X509Builder::new().map_err(|e| AetherError::Tls(e.to_string()))?;
    builder
        .set_version(2)
        .map_err(|e| AetherError::Tls(e.to_string()))?;

    let serial = BigNum::from_u32(0)
        .and_then(|bn| bn.to_asn1_integer())
        .map_err(|e| AetherError::Tls(e.to_string()))?;
    builder
        .set_serial_number(&serial)
        .map_err(|e| AetherError::Tls(e.to_string()))?;

    let name = X509NameBuilder::new()
        .map_err(|e| AetherError::Tls(e.to_string()))?
        .build();
    builder
        .set_subject_name(&name)
        .map_err(|e| AetherError::Tls(e.to_string()))?;
    builder
        .set_issuer_name(&name)
        .map_err(|e| AetherError::Tls(e.to_string()))?;

    let not_before = Asn1Time::days_from_now(0).map_err(|e| AetherError::Tls(e.to_string()))?;
    let not_after = Asn1Time::days_from_now(MASQUE_CERT_LIFETIME_DAYS)
        .map_err(|e| AetherError::Tls(e.to_string()))?;
    builder
        .set_not_before(&not_before)
        .map_err(|e| AetherError::Tls(e.to_string()))?;
    builder
        .set_not_after(&not_after)
        .map_err(|e| AetherError::Tls(e.to_string()))?;

    builder
        .set_pubkey(&pkey)
        .map_err(|e| AetherError::Tls(e.to_string()))?;
    builder
        .sign(&pkey, MessageDigest::sha256())
        .map_err(|e| AetherError::Tls(e.to_string()))?;

    let cert_pem = builder
        .build()
        .to_pem()
        .map_err(|e| AetherError::Tls(e.to_string()))?;

    Ok(MasqueKeyPair {
        key_pem,
        cert_pem,
        spki_der,
    })
}

/// An identity with a MASQUE key pair of its own and nothing registered: enough to start a
/// MASQUE handshake with in a test, never to get through to WARP.
#[cfg(test)]
pub(crate) fn handshake_identity() -> Identity {
    let pair = generate_masque_keypair().expect("a MASQUE key pair");
    Identity {
        device_id: "device".to_string(),
        access_token: "token".to_string(),
        cert_pem: pair.cert_pem,
        key_pem: pair.key_pem,
        cert_issued_at: now_unix(),
        ipv4: "172.16.0.2".to_string(),
        ipv6: String::new(),
        wg_private_key: [0u8; 32],
        wg_peer_public_key: [0u8; 32],
        client_id: [0u8; 3],
        organization: String::new(),
        gateway_proxy: String::new(),
        assigned_endpoint: String::new(),
        refused: false,
    }
}

/// How long one attempt at a call to the WARP API may take.
const API_TIMEOUT: std::time::Duration = std::time::Duration::from_secs(20);

const API_ATTEMPTS: u32 = 5;
const API_BACKOFF_BASE_MS: u64 = 900;
const API_BACKOFF_CAP_MS: u64 = 15_000;
const API_RETRY_AFTER_CAP_SECS: u64 = 30;

fn backoff_delay(attempt: u32) -> std::time::Duration {
    let exponential = API_BACKOFF_BASE_MS.saturating_mul(1u64 << attempt.min(5));
    let capped = exponential.min(API_BACKOFF_CAP_MS);
    let jitter = rand::rng().next_u32() as u64 % (capped / 3 + 1);
    std::time::Duration::from_millis(capped / 2 + jitter)
}

fn retry_after(headers: &reqwest::header::HeaderMap) -> Option<std::time::Duration> {
    let raw = headers.get(reqwest::header::RETRY_AFTER)?.to_str().ok()?;
    let seconds = raw.trim().parse::<u64>().ok().or_else(|| {
        let date = chrono::DateTime::parse_from_rfc2822(raw.trim()).ok()?;
        Some((date.timestamp() - chrono::Utc::now().timestamp()).max(0) as u64)
    })?;
    Some(std::time::Duration::from_secs(seconds))
}

fn refuses_identity(status: reqwest::StatusCode) -> bool {
    matches!(
        status,
        reqwest::StatusCode::UNAUTHORIZED
            | reqwest::StatusCode::NOT_FOUND
            | reqwest::StatusCode::GONE
    )
}

fn worth_retrying(status: reqwest::StatusCode) -> bool {
    status == reqwest::StatusCode::TOO_MANY_REQUESTS
        || status == reqwest::StatusCode::REQUEST_TIMEOUT
        || status.is_server_error()
}

fn front_headers(bearer: Option<&str>, jwt: Option<&str>) -> Vec<(String, String)> {
    let mut headers = vec![
        (
            "Content-Type".to_string(),
            "application/json; charset=UTF-8".to_string(),
        ),
        ("User-Agent".to_string(), consts::UA_REGISTER.to_string()),
        (
            "CF-Client-Version".to_string(),
            consts::CF_CLIENT_VERSION.to_string(),
        ),
        ("Accept".to_string(), "application/json".to_string()),
    ];
    if let Some(token) = bearer {
        headers.push(("Authorization".to_string(), format!("Bearer {token}")));
    }
    if let Some(token) = jwt {
        headers.push(("CF-Access-Jwt-Assertion".to_string(), token.to_string()));
    }
    headers
}

/// The ECH key the calls to the WARP API offer for the rest of the run, by --ech, once
/// looked up; a key a server hands back takes its place.
static API_ECH: std::sync::Mutex<Option<Vec<u8>>> = std::sync::Mutex::new(None);

fn remember_api_ech(ech: Vec<u8>) {
    *API_ECH
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner) = Some(ech);
}

/// Forgets the key the calls to the WARP API offered, as a run of the core starts: a run of
/// the library after another looks up its own, by its own --ech, --ech-dns and --ech-domain.
pub fn forget_api_ech() {
    *API_ECH
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner) = None;
}

/// The key the calls to the WARP API offered last in this run, if any: the MASQUE session
/// starts with it rather than look one up again.
pub fn api_ech_in_use() -> Option<Vec<u8>> {
    API_ECH
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner)
        .clone()
}

/// The ECH key of the calls to the WARP API, by --ech, as the MASQUE handshakes take it: none
/// without the option; otherwise the key it gives, or with auto the one the lookup of
/// --ech-dns and --ech-domain finds, once for the run. With the option and no key BoringSSL
/// can offer, an error that says so: the API is not asked with its name in the clear.
async fn api_ech() -> Result<Option<Vec<u8>>> {
    let option = &crate::tls::API_ECH_OPTION;
    let setting = std::env::var(option.variable).ok();
    if setting.as_deref().is_none_or(str::is_empty) {
        return Ok(None);
    }
    // Android validates ECH before provisioning. Reuse that session's key
    // rather than making a second bootstrap lookup on the same restricted path.
    let remembered = api_ech_in_use().or_else(crate::tls::session_ech);
    if remembered.is_some() {
        return Ok(remembered);
    }
    let key = crate::tls::ech_key(option, setting.as_deref(), || {
        crate::dns::fetch_ech_config(&crate::dns::SESSION_ECH)
    })
    .await?;
    if let Some(key) = &key {
        remember_api_ech(key.clone());
    }
    Ok(key)
}

fn describe_rejection(status: reqwest::StatusCode, body: &str) -> String {
    let detail = extract_api_error(body).unwrap_or_else(|| {
        let trimmed = body.trim();
        if trimmed.is_empty() {
            "no details returned".to_string()
        } else if trimmed.chars().count() > 220 {
            format!("{}…", trimmed.chars().take(220).collect::<String>())
        } else {
            trimmed.to_string()
        }
    });

    let hint = match status.as_u16() {
        403 => {
            " (cloudflare refused this network; the address looks flagged, \
                try again later, switch network, or import an existing identity)"
        }
        429 => {
            " (too many registrations from this address; wait a few minutes \
                before trying again)"
        }
        _ => "",
    };

    format!("status {status}: {detail}{hint}")
}

fn extract_api_error(body: &str) -> Option<String> {
    let value: serde_json::Value = serde_json::from_str(body).ok()?;
    let errors = value.get("errors")?.as_array()?;
    let parts: Vec<String> = errors
        .iter()
        .map(|entry| {
            let message = entry
                .get("message")
                .and_then(|value| value.as_str())
                .unwrap_or("unknown");
            let code = entry.get("code").and_then(|value| value.as_i64());
            match code {
                Some(code) => format!("{message} (code {code})"),
                None => message.to_string(),
            }
        })
        .collect();

    if parts.is_empty() {
        None
    } else {
        Some(parts.join("; "))
    }
}

fn api_address() -> Result<Option<(String, u16)>> {
    let value = std::env::var("AETHER_API_ADDRESS")
        .ok()
        .filter(|value| !value.trim().is_empty())
        .or_else(|| std::env::var("AETHER_ENROLL_ADDRESS").ok())
        .unwrap_or_default();
    let value = value.trim();
    if value.is_empty() {
        return Ok(None);
    }
    crate::dns::host_and_port(value, 443)
        .map(Some)
        .ok_or_else(|| {
            AetherError::Api(format!(
                "--api-address: {value} is no IP address or domain name, with or without a port"
            ))
        })
}

/// The name `value`, given to `flag`, --api-host or --api-sni, gives the calls to the WARP
/// API: none when it is blank; else that domain name, without a trailing dot, as SNI has it
/// (RFC 6066). An address or anything else that is no domain name is refused, rather than
/// have the calls go by another name in its place.
fn api_name_of(flag: &str, value: &str) -> Result<Option<String>> {
    let value = value.trim();
    if value.is_empty() {
        return Ok(None);
    }
    let name = value.strip_suffix('.').unwrap_or(value);
    if name.ends_with('.')
        || name.parse::<std::net::IpAddr>().is_ok()
        || !crate::dns::valid_domain(name)
    {
        return Err(AetherError::Api(format!(
            "{flag}: {value} is no domain name"
        )));
    }
    Ok(Some(name.to_string()))
}

/// --api-host (AETHER_API_HOST): the HTTP host of the calls to the WARP API, of their Host or
/// :authority, see `api_name_of`.
fn api_host() -> Result<Option<String>> {
    api_name_of(
        "--api-host",
        &std::env::var("AETHER_API_HOST").unwrap_or_default(),
    )
}

/// --api-sni (AETHER_API_SNI): the server name of the ClientHello of the calls to the WARP API,
/// see `api_name_of`.
fn api_sni() -> Result<Option<String>> {
    api_name_of(
        "--api-sni",
        &std::env::var("AETHER_API_SNI").unwrap_or_default(),
    )
}

/// Checks --api-address, --api-sni and --api-host as the core starts: a value the calls to the
/// WARP API cannot use stops it, with its option named.
pub fn check_api_settings() -> Result<()> {
    api_address()?;
    api_sni()?;
    api_host()?;
    Ok(())
}

/// One way a call to the WARP API goes: the HTTP host it names, the server name of its
/// ClientHello, and where its connection goes.
#[derive(Debug, Clone, PartialEq, Eq)]
struct ApiRoute {
    host: String,
    sni: String,
    address: String,
    port: u16,
    front: bool,
}

/// The ways a call to the WARP API goes, in the order its attempts take them: by the HTTP host
/// `host`, of --api-host, by default `consts::API_HOST`; with the server name `sni`, of
/// --api-sni, by default that host; to `address`, of --api-address, by default that server name
/// on 443. Cloudflare refuses a call whose server name is not its host, so each follows the one
/// it defaults to. Without --api-host and --api-sni there is a second way, by
/// `consts::API_FALLBACK_HOST` alike, which an attempt the first way did not serve takes next;
/// once the API itself has answered one of the two in this run, `answered`, the calls go that
/// way first, with the other still behind it.
fn api_routes_of(
    host: Option<String>,
    sni: Option<String>,
    address: Option<(String, u16)>,
    answered: Option<&str>,
) -> Vec<ApiRoute> {
    let route = |host: String, sni: String| {
        let (address, port) = address.clone().unwrap_or_else(|| (sni.clone(), 443));
        ApiRoute {
            host,
            sni,
            address,
            port,
            front: false,
        }
    };
    if host.is_some() || sni.is_some() {
        let host = host.unwrap_or_else(|| consts::API_HOST.to_string());
        let sni = sni.unwrap_or_else(|| host.clone());
        return vec![route(host, sni)];
    }
    let mut names = [consts::API_HOST, consts::API_FALLBACK_HOST];
    if answered == Some(consts::API_FALLBACK_HOST) {
        names.reverse();
    }
    let mut routes: Vec<ApiRoute> = names
        .into_iter()
        .map(|name| route(name.to_string(), name.to_string()))
        .collect();
    if address.is_none() {
        let name = names[0];
        routes.extend(consts::API_FRONT_ADDRESSES.map(|edge| ApiRoute {
            host: name.to_string(),
            sni: name.to_string(),
            address: edge.to_string(),
            port: 443,
            front: true,
        }));
    }
    routes
}

/// The ways of the calls to the WARP API, as the options and this run have them, see
/// `api_routes_of`.
fn api_routes() -> Result<Vec<ApiRoute>> {
    // Preserve the legacy manually pinned route for saved Android configurations.
    if std::env::var("AETHER_API_ADDRESS")
        .unwrap_or_default()
        .trim()
        .is_empty()
        && !std::env::var("AETHER_ENROLL_ADDRESS")
            .unwrap_or_default()
            .trim()
            .is_empty()
        && api_host()?.is_none()
        && api_sni()?.is_none()
    {
        return Ok(api_routes_of(
            Some(consts::API_FALLBACK_HOST.to_string()),
            None,
            api_address()?,
            None,
        ));
    }
    let mut routes = api_routes_of(
        api_host()?,
        api_sni()?,
        api_address()?,
        answered_api_host().as_deref(),
    );
    if let Some(edge) = answered_api_edge() {
        if let Some(at) = routes
            .iter()
            .position(|route| route.front && route.address == edge)
        {
            routes.rotate_left(at);
        }
    }
    Ok(routes)
}

/// The name the WARP API itself answered last in this run when the calls go by the default
/// names: the later calls of the run go by it first, see `api_routes_of`.
static API_ANSWERED: std::sync::Mutex<Option<String>> = std::sync::Mutex::new(None);

fn answered_api_host() -> Option<String> {
    API_ANSWERED
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner)
        .clone()
}

fn remember_answered_api_host(host: &str) {
    *API_ANSWERED
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner) = Some(host.to_string());
}

/// Forgets which name of the WARP API answered, as a run of the core starts: a run of the
/// library after another tries both names again.
pub fn forget_answered_api_host() {
    *API_ANSWERED
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner) = None;
    remember_answered_api_edge(None);
}

static API_EDGE_ANSWERED: std::sync::Mutex<Option<String>> = std::sync::Mutex::new(None);

fn answered_api_edge() -> Option<String> {
    API_EDGE_ANSWERED
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner)
        .clone()
}

fn remember_answered_api_edge(edge: Option<&str>) {
    *API_EDGE_ANSWERED
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner) = edge.map(str::to_string);
}

/// Compatibility entry point used by older embedded callers.
pub fn check_enroll_address() -> Result<()> {
    check_api_settings()
}

#[cfg(test)]
fn enroll_address() -> Result<(String, u16)> {
    Ok(api_address()?.unwrap_or_else(|| (consts::API_HOST.to_string(), 443)))
}

fn is_api_envelope(body: &str) -> bool {
    serde_json::from_str::<serde_json::Value>(body).is_ok_and(|value| {
        value
            .get("success")
            .is_some_and(serde_json::Value::is_boolean)
            && value.get("errors").is_some_and(serde_json::Value::is_array)
    })
}

type ApiTls = tokio_boring::SslStream<crate::fragment::FragmentingStream<tokio::net::TcpStream>>;

/// Race authenticated handshakes across API names and edge routes. HTTP is sent only to
/// the winner. A connect failure can safely try another route; an ambiguous POST cannot.
async fn prepare_api<'a>(
    routes: &'a [ApiRoute],
    skipped: &[bool],
    method: &'a str,
    path: &'a str,
    headers: &'a [(String, String)],
    body: Option<&'a [u8]>,
    fingerprint: &crate::tls::Fingerprint,
    ech: Option<Vec<u8>>,
    adaptive: bool,
) -> Result<(usize, ApiTls, Option<Vec<u8>>)> {
    use futures::{FutureExt, StreamExt};
    let mut pending = futures::stream::FuturesUnordered::new();
    for (index, route) in routes
        .iter()
        .enumerate()
        .filter(|(index, _)| !skipped[*index])
    {
        let ech = ech.clone();
        pending.push(async move {
            // Let the last answering route lead; a blackholed route cannot hold up the others.
            if index > 0 {
                tokio::time::sleep(std::time::Duration::from_millis(150 * index as u64)).await;
            }
            let request = crate::https::Request {
                method, host: &route.host, port: 443,
                address: Some((&route.address, route.port)), sni: Some(&route.sni),
                path, headers, body,
            };
            if adaptive && !route.front {
                let session = apifront::prepare(&request, fingerprint, API_TIMEOUT).boxed().await?;
                return Ok((index, session.tls, session.ech));
            }
            if crate::egress::mark() != 0 && crate::upstream::configured().is_none()
                && route.address.parse::<std::net::IpAddr>().is_err()
            {
                return Err(AetherError::Api(
                    "--api-address must be an IP address when a socket mark is used without a proxy".into()));
            }
            let mut ech = ech;
            let tls = tokio::time::timeout(API_TIMEOUT, Box::pin(crate::https::open(
                &request, fingerprint, ech.as_mut(),
                crate::fragment::FragmentConfig::from_env(), None)))
                .await.map_err(|_| AetherError::Api("API TLS handshake timed out".into()))??;
            Ok((index, tls, ech))
        });
    }
    let mut failure = AetherError::Api("no authenticated route to the WARP API".into());
    while let Some(result) = pending.next().await {
        match result {
            Ok(session) => return Ok(session),
            Err(error) => failure = error,
        }
    }
    Err(failure)
}

async fn api_call(
    label: &str,
    method: &str,
    path: &str,
    body: Option<&[u8]>,
    bearer: Option<&str>,
    jwt: Option<&str>,
) -> Result<AccountData> {
    let routes = api_routes()?;
    api_call_by(&routes, label, method, path, body, bearer, jwt).await
}

async fn api_call_by(
    routes: &[ApiRoute],
    label: &str,
    method: &str,
    path: &str,
    body: Option<&[u8]>,
    bearer: Option<&str>,
    jwt: Option<&str>,
) -> Result<AccountData> {
    let adaptive = api_address()?.is_none()
        && api_host()?.is_none()
        && api_sni()?.is_none()
        && std::env::var("AETHER_ECH")
            .unwrap_or_default()
            .trim()
            .is_empty();
    let mut ech = api_ech().await?;
    let fingerprint = crate::tls::Fingerprint::configured();
    let headers = front_headers(bearer, jwt);
    let mut skipped = vec![false; routes.len()];
    let mut last_error = AetherError::Api(format!("{label}: no attempt was made"));
    let mut asked_to_wait = None;
    // A valid response pins the API route for retries within this call.
    let mut serving = None;
    for attempt in 0..API_ATTEMPTS {
        if attempt > 0 {
            tokio::time::sleep(backoff_delay(attempt - 1)).await;
        }
        let excluded: Vec<_> = skipped
            .iter()
            .enumerate()
            .map(|(i, skip)| *skip || serving.is_some_and(|chosen| chosen != i))
            .collect();
        let (index, tls, key) = prepare_api(
            routes,
            &excluded,
            method,
            path,
            &headers,
            body,
            &fingerprint,
            ech.clone(),
            adaptive,
        )
        .await?;
        ech = key;
        if let Some(key) = &ech {
            remember_api_ech(key.clone());
            crate::tls::adopt_ech_retry(key);
        }
        let route = &routes[index];
        let request = crate::https::Request {
            method,
            host: &route.host,
            port: 443,
            address: Some((&route.address, route.port)),
            sni: Some(&route.sni),
            path,
            headers: &headers,
            body,
        };
        // No retry after this await fails: Cloudflare may already have accepted the POST.
        let response = tokio::time::timeout(
            API_TIMEOUT,
            Box::pin(crate::https::send_on_session(tls, &request)),
        )
        .await
        .map_err(|_| {
            AetherError::Api(format!("{label}: API exchange timed out; outcome unknown"))
        })??;
        let status = reqwest::StatusCode::from_u16(response.status)
            .map_err(|_| AetherError::Api(format!("{label}: status {}", response.status)))?;
        let cooldown = retry_after(&response.headers);
        let text = String::from_utf8_lossy(&response.body);
        let from_api = is_api_envelope(&text);
        if status.is_success() {
            let account = serde_json::from_str::<AccountData>(&text).map_err(|error| {
                AetherError::Api(format!(
                    "{label} decode: {error} ({} byte answer)",
                    text.len()
                ))
            })?;
            remember_answered_api_host(&route.host);
            remember_answered_api_edge(route.front.then_some(route.address.as_str()));
            return Ok(account);
        }
        let mut described = format!("{label}: {}", describe_rejection(status, &text));
        if status == reqwest::StatusCode::FORBIDDEN && route.sni != route.host {
            described.push_str(" (the API HTTP host and TLS server name differ; Cloudflare requires them to match)");
        }
        if status == reqwest::StatusCode::TOO_MANY_REQUESTS
            || cooldown.is_some_and(|wait| wait.as_secs() > API_RETRY_AFTER_CAP_SECS)
        {
            return Err(AetherError::RateLimited {
                reason: described,
                retry_after: cooldown.map(|wait| wait.as_secs()),
            });
        }
        if !from_api && routes.len() > 1 {
            skipped[index] = true;
            serving = None;
            last_error = AetherError::Api(described);
            if skipped.iter().all(|skip| *skip) {
                return Err(last_error);
            }
            continue;
        }
        remember_answered_api_host(&route.host);
        remember_answered_api_edge(route.front.then_some(route.address.as_str()));
        serving = Some(index);
        last_error = if refuses_identity(status) {
            AetherError::IdentityRefused(described)
        } else {
            AetherError::Api(described)
        };
        if !worth_retrying(status) {
            return Err(last_error);
        }
        if let Some(wait) = cooldown {
            asked_to_wait = Some(wait.as_secs());
            if attempt + 1 < API_ATTEMPTS {
                tokio::time::sleep(wait).await;
            }
        }
    }
    match last_error {
        AetherError::Api(reason) => Err(AetherError::RateLimited {
            reason,
            retry_after: asked_to_wait,
        }),
        error => Err(error),
    }
}

fn generate_x25519_keypair() -> ([u8; 32], String) {
    let mut private = [0u8; 32];
    rand::rng().fill_bytes(&mut private);

    private[0] &= 248;
    private[31] &= 127;
    private[31] |= 64;

    let public = x25519_dalek::PublicKey::from(&x25519_dalek::StaticSecret::from(private));
    let public_b64 = base64::engine::general_purpose::STANDARD.encode(public.as_bytes());

    (private, public_b64)
}

fn random_android_serial() -> String {
    let mut s = [0u8; 8];
    rand::rng().fill_bytes(&mut s);
    hex::encode(s)
}

fn tos_timestamp() -> String {
    chrono::Local::now()
        .format("%Y-%m-%dT%H:%M:%S%.3f%:z")
        .to_string()
}

pub async fn register(
    model: &str,
    locale: &str,
    jwt: Option<&str>,
) -> Result<(AccountData, [u8; 32])> {
    let (wg_private, wg_public) = generate_x25519_keypair();

    let body = Registration {
        key: wg_public,
        install_id: String::new(),
        fcm_token: String::new(),
        tos: tos_timestamp(),
        model: model.to_string(),
        serial_number: random_android_serial(),
        os_version: String::new(),
        key_type: "curve25519".to_string(),
        tunnel_type: "wireguard".to_string(),
        locale: locale.to_string(),
    };

    let path = format!("/{}/reg", consts::API_VERSION);
    let encoded =
        serde_json::to_vec(&body).map_err(|e| AetherError::Api(format!("encode: {e}")))?;

    let account = api_call("registration", "POST", &path, Some(&encoded), None, jwt).await?;
    Ok((account, wg_private))
}

pub async fn enable_warp(device_id: &str, token: &str) -> Result<()> {
    let path = format!("/{}/reg/{}", consts::API_VERSION, device_id);
    let body = serde_json::to_vec(&serde_json::json!({ "warp_enabled": true }))
        .map_err(|e| AetherError::Api(format!("encode: {e}")))?;
    api_call(
        "enabling warp",
        "PATCH",
        &path,
        Some(&body),
        Some(token),
        None,
    )
    .await?;
    Ok(())
}

pub async fn enroll_key(
    device_id: &str,
    token: &str,
    spki_der: &[u8],
    name: Option<&str>,
) -> Result<AccountData> {
    let body = DeviceUpdate {
        key: base64::engine::general_purpose::STANDARD.encode(spki_der),
        key_type: consts::KEY_TYPE_MASQUE.to_string(),
        tunnel_type: consts::TUN_TYPE_MASQUE.to_string(),
        name: name.map(|s| s.to_string()),
    };

    let path = format!("/{}/reg/{}", consts::API_VERSION, device_id);
    let encoded =
        serde_json::to_vec(&body).map_err(|e| AetherError::Api(format!("encode: {e}")))?;

    api_call(
        "key enrollment",
        "PATCH",
        &path,
        Some(&encoded),
        Some(token),
        None,
    )
    .await
}

fn extract_wg_peer(reg: &AccountData) -> Result<[u8; 32]> {
    if reg.config.peers.is_empty() {
        return Err(AetherError::Api("no peers in registration response".into()));
    }
    let peer_b64 = &reg.config.peers[0].public_key;
    let decoded = base64::Engine::decode(&base64::engine::general_purpose::STANDARD, peer_b64)
        .map_err(|e| AetherError::Api(format!("decode peer pubkey: {e}")))?;
    if decoded.len() != 32 {
        return Err(AetherError::Api("invalid peer pubkey length".into()));
    }
    let mut arr = [0u8; 32];
    arr.copy_from_slice(&decoded);
    Ok(arr)
}

pub async fn register_with_team(
    model: &str,
    locale: &str,
    token: &str,
) -> Result<(AccountData, [u8; 32])> {
    let (wg_private, wg_public) = generate_x25519_keypair();
    let body = team_registration_body(wg_public, model, locale);

    let path = format!("/{}/reg", consts::API_VERSION);
    let encoded =
        serde_json::to_vec(&body).map_err(|e| AetherError::Api(format!("encode: {e}")))?;

    let account = api_call(
        "team registration",
        "POST",
        &path,
        Some(&encoded),
        None,
        Some(token),
    )
    .await?;
    Ok((account, wg_private))
}

pub async fn provision_team(
    model: &str,
    locale: &str,
    settings: &crate::zerotrust::TeamSettings,
) -> Result<Identity> {
    let token = crate::zerotrust::resolve_token(settings).await?;
    let (reg, wg_private) = register_with_team(model, locale, &token).await?;
    finish_provision(reg, wg_private)
}

pub async fn provision_wg(model: &str, locale: &str, jwt: Option<&str>) -> Result<Identity> {
    let (reg, wg_private) = register(model, locale, jwt).await?;
    finish_provision(reg, wg_private)
}

/// Activates a registered WARP device without replacing its WireGuard key.
pub async fn fetch_device(device_id: &str, token: &str) -> Result<AccountData> {
    let path = format!("/{}/reg/{}", consts::API_VERSION, device_id);

    api_call("device refresh", "GET", &path, None, Some(token), None).await
}

pub fn endpoint_from(reg: &AccountData) -> String {
    let raw = reg
        .config
        .peers
        .first()
        .map(|peer| peer.endpoint.v4.trim())
        .unwrap_or_default();

    match raw.rsplit_once(':') {
        Some((host, _)) if !host.is_empty() => host.to_string(),
        _ => raw.to_string(),
    }
}

pub async fn refresh_profile(identity: Identity) -> Identity {
    let reg = match fetch_device(&identity.device_id, &identity.access_token).await {
        Ok(reg) => reg,
        Err(AetherError::IdentityRefused(reason)) => {
            log::warn!(
                "[-] cloudflare no longer accepts the saved identity for device {}: {reason}",
                identity.device_id
            );
            log::warn!(
                "[-] the tunnel will handshake but carry no traffic until this identity is replaced"
            );
            return Identity {
                refused: true,
                ..identity
            };
        }
        Err(error) => {
            log::warn!("[!] could not reach the account api to check the identity: {error}");
            log::warn!("[!] carrying on with the saved profile; it may be out of date");
            return identity;
        }
    };

    let ipv4 = reg.config.interface.addresses.v4.trim().to_string();
    let ipv6 = reg.config.interface.addresses.v6.trim().to_string();
    let organization = reg.account.organization.trim().to_string();
    let gateway_proxy = reg.config.services.http_proxy.trim().to_string();
    let assigned_endpoint = endpoint_from(&reg);

    if !ipv4.is_empty() && ipv4 != identity.ipv4 {
        log::info!(
            "[+] the account moved this device from {} to {ipv4}; using the assigned address",
            identity.ipv4
        );
    }
    if !organization.is_empty() {
        log::info!(
            "[+] confirmed membership of organization {organization} (account type {})",
            reg.account.account_type
        );
    }
    if !gateway_proxy.is_empty() {
        log::debug!(
            "[zerotrust] the organization publishes a gateway http proxy at {gateway_proxy}"
        );
    }

    Identity {
        ipv4: if ipv4.is_empty() { identity.ipv4 } else { ipv4 },
        ipv6: if ipv6.is_empty() { identity.ipv6 } else { ipv6 },
        organization,
        gateway_proxy,
        assigned_endpoint,
        refused: false,
        ..identity
    }
}

fn finish_provision(reg: AccountData, wg_private: [u8; 32]) -> Result<Identity> {
    if reg.token.is_empty() {
        return Err(AetherError::Api("registration returned empty token".into()));
    }

    let wg_peer_public = extract_wg_peer(&reg)?;

    let mut client_id_arr = [0u8; 3];
    if !reg.config.client_id.is_empty() {
        log::debug!(
            "[account] received client_id from API: {:?}",
            reg.config.client_id
        );
        if let Ok(decoded) = base64::Engine::decode(
            &base64::engine::general_purpose::STANDARD,
            &reg.config.client_id,
        ) {
            if decoded.len() == 3 {
                client_id_arr.copy_from_slice(&decoded);
                log::debug!("[account] decoded client_id: {:02x?}", client_id_arr);
            } else {
                log::warn!(
                    "[account] client_id decoded but wrong length: {}",
                    decoded.len()
                );
            }
        } else {
            log::warn!("[account] failed to decode client_id base64");
        }
    } else {
        log::warn!("[account] API response has empty client_id, using zeros");
    }

    let organization = reg.account.organization.trim().to_string();
    let gateway_proxy = reg.config.services.http_proxy.trim().to_string();
    let assigned_endpoint = endpoint_from(&reg);

    Ok(Identity {
        device_id: reg.id,
        access_token: reg.token,
        cert_pem: Vec::new(),
        key_pem: Vec::new(),
        cert_issued_at: 0,
        ipv4: reg.config.interface.addresses.v4,
        ipv6: reg.config.interface.addresses.v6,
        wg_private_key: wg_private,
        wg_peer_public_key: wg_peer_public,
        client_id: client_id_arr,
        organization,
        gateway_proxy,
        assigned_endpoint,
        refused: false,
    })
}

pub const MASQUE_CERT_LIFETIME_DAYS: u32 = 365;
pub const MASQUE_CERT_LIFETIME_SECS: u64 = MASQUE_CERT_LIFETIME_DAYS as u64 * 86_400;
pub const MASQUE_CERT_RENEW_BEFORE_SECS: u64 = 7 * 86_400;

pub fn now_unix() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0)
}

pub fn masque_cert_expiring(issued_at: u64) -> bool {
    if issued_at == 0 {
        return true;
    }
    let now = now_unix();
    if now < issued_at {
        return true;
    }
    let age = now - issued_at;
    age + MASQUE_CERT_RENEW_BEFORE_SECS >= MASQUE_CERT_LIFETIME_SECS
}

pub struct MasqueEnrollment {
    pub cert_pem: Vec<u8>,
    pub key_pem: Vec<u8>,
    pub issued_at: u64,
    pub renewed: bool,
    pub profile: Option<AccountData>,
}

impl MasqueEnrollment {
    pub fn apply(self, identity: Identity) -> Identity {
        let mut identity = Identity {
            cert_pem: self.cert_pem,
            key_pem: self.key_pem,
            cert_issued_at: self.issued_at,
            ..identity
        };
        if let Some(profile) = self.profile {
            apply_interface_profile(&mut identity, &profile);
        }
        identity
    }
}

/// Enrollment and device refresh can move both the edge and tunnel addresses.
pub fn apply_interface_profile(identity: &mut Identity, profile: &AccountData) {
    let ipv4 = profile.config.interface.addresses.v4.trim();
    let ipv6 = profile.config.interface.addresses.v6.trim();
    if !ipv4.is_empty() {
        identity.ipv4 = ipv4.into();
    }
    if !ipv6.is_empty() {
        identity.ipv6 = ipv6.into();
    }
    let endpoint = endpoint_from(profile);
    if !endpoint.is_empty() {
        identity.assigned_endpoint = endpoint;
    }
}

pub async fn ensure_masque_enrolled(identity: &Identity) -> Result<MasqueEnrollment> {
    ensure_masque_enrolled_with_key(identity, None).await
}

pub(crate) async fn ensure_masque_enrolled_with_key(
    identity: &Identity,
    prepared: Option<MasqueKeyPair>,
) -> Result<MasqueEnrollment> {
    let usable = !identity.cert_pem.is_empty() && !identity.key_pem.is_empty();

    if prepared.is_none() && usable && !masque_cert_expiring(identity.cert_issued_at) {
        return Ok(MasqueEnrollment {
            cert_pem: identity.cert_pem.clone(),
            key_pem: identity.key_pem.clone(),
            issued_at: identity.cert_issued_at,
            renewed: false,
            profile: None,
        });
    }

    if usable {
        log::info!("[*] masque certificate is expiring, enrolling a fresh key");
    } else {
        log::info!("[+] enrolling MASQUE key for device {}", identity.device_id);
    }

    let resumable = prepared.is_some();
    let keypair = match prepared {
        Some(pair) => pair,
        None => generate_masque_keypair()?,
    };
    match enroll_key(
        &identity.device_id,
        &identity.access_token,
        &keypair.spki_der,
        None,
    )
    .await
    {
        Ok(profile) => {
            log::info!("[+] MASQUE key enrolled");
            Ok(MasqueEnrollment {
                cert_pem: keypair.cert_pem,
                key_pem: keypair.key_pem,
                issued_at: now_unix(),
                renewed: true,
                profile: Some(profile),
            })
        }
        Err(error) if !resumable && cert_still_usable(identity) => {
            log::warn!(
                "[!] key enrollment failed ({error}); keeping the certificate already on disk"
            );
            Ok(MasqueEnrollment {
                cert_pem: identity.cert_pem.clone(),
                key_pem: identity.key_pem.clone(),
                issued_at: identity.cert_issued_at,
                renewed: false,
                profile: None,
            })
        }
        Err(error) => Err(error),
    }
}

pub fn cert_still_usable(identity: &Identity) -> bool {
    if identity.cert_pem.is_empty() || identity.key_pem.is_empty() {
        return false;
    }
    if identity.cert_issued_at == 0 {
        return false;
    }
    let now = now_unix();
    if now < identity.cert_issued_at {
        return false;
    }
    now - identity.cert_issued_at < MASQUE_CERT_LIFETIME_SECS
}

impl Identity {
    pub fn private_key_bytes(&self) -> Result<[u8; 32]> {
        Ok(self.wg_private_key)
    }

    pub fn peer_public_key_bytes(&self) -> Result<[u8; 32]> {
        Ok(self.wg_peer_public_key)
    }

    pub fn has_masque_credentials(&self) -> bool {
        !self.cert_pem.is_empty()
            && !self.key_pem.is_empty()
            && !masque_cert_expiring(self.cert_issued_at)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A route that never completed a request is not put to it again.
    ///
    /// The direct route used to be retried five times whatever went wrong, each
    /// with a twenty-second ceiling. On a network that blackholes the API that
    /// is a hundred seconds -- measured on a phone in Iran, 18:13:26 to
    /// 18:15:08 -- spent before the camouflaged route, the only one that can
    /// work there, was allowed to start. Twice over for a fresh MASQUE
    /// identity, which registers and then enrols.
    ///
    /// The ladder is kept for answers that ask to be asked again; this is about
    /// the requests that never became answers at all.
    #[tokio::test]
    async fn a_route_that_never_completes_a_request_is_not_repeated() {
        let _setting = crate::upstream::hold_setting().await;
        let _options = crate::tls::hold_options().await;
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let address = listener.local_addr().unwrap();
        std::env::set_var("AETHER_ENROLL_ADDRESS", address.to_string());
        std::env::remove_var("AETHER_ECH");
        let edge = tokio::spawn(async move {
            let (tcp, _) = listener.accept().await.unwrap();
            drop(tcp);
            tokio::time::timeout(std::time::Duration::from_secs(1), listener.accept())
                .await
                .is_ok()
        });
        let outcome = tokio::time::timeout(
            std::time::Duration::from_secs(2),
            api_call("test call", "GET", "/", None, None, None),
        )
        .await;
        std::env::remove_var("AETHER_ENROLL_ADDRESS");
        assert!(
            outcome.unwrap().is_err(),
            "a reset connection is not a success"
        );
        assert!(
            !edge.await.unwrap(),
            "the same failed route was dialled again"
        );
    }

    #[test]
    fn a_long_rejection_in_any_script_is_cut_without_panicking() {
        let body = format!("{}{}", "x".repeat(219), "é".repeat(200));
        let described = describe_rejection(reqwest::StatusCode::BAD_REQUEST, &body);
        assert!(described.contains('…'));
    }

    #[test]
    fn the_certificate_lives_far_longer_than_a_day() {
        assert!(MASQUE_CERT_LIFETIME_DAYS >= 365);
        assert!(MASQUE_CERT_LIFETIME_SECS > 86_400 * 300);
    }

    #[test]
    fn a_missing_issue_time_counts_as_expiring() {
        assert!(masque_cert_expiring(0));
    }

    #[test]
    fn a_fresh_certificate_is_not_expiring() {
        assert!(!masque_cert_expiring(now_unix()));
    }

    #[test]
    fn a_certificate_older_than_a_day_is_still_valid() {
        assert!(!masque_cert_expiring(now_unix() - 86_400 * 2));
    }

    #[test]
    fn a_certificate_inside_the_renewal_window_is_expiring() {
        let issued = now_unix() - (MASQUE_CERT_LIFETIME_SECS - MASQUE_CERT_RENEW_BEFORE_SECS / 2);
        assert!(masque_cert_expiring(issued));
    }

    #[test]
    fn a_certificate_issued_in_the_future_counts_as_expiring() {
        assert!(masque_cert_expiring(now_unix() + 86_400));
    }

    fn sample_identity(cert_issued_at: u64, with_cert: bool) -> Identity {
        Identity {
            device_id: "device".to_string(),
            access_token: "token".to_string(),
            cert_pem: if with_cert {
                b"cert".to_vec()
            } else {
                Vec::new()
            },
            key_pem: if with_cert {
                b"key".to_vec()
            } else {
                Vec::new()
            },
            cert_issued_at,
            ipv4: "172.16.0.2".to_string(),
            ipv6: String::new(),
            wg_private_key: [0u8; 32],
            wg_peer_public_key: [0u8; 32],
            client_id: [0u8; 3],
            organization: String::new(),
            gateway_proxy: String::new(),
            assigned_endpoint: String::new(),
            refused: false,
        }
    }

    #[test]
    fn a_certificate_inside_its_lifetime_is_still_usable_offline() {
        let identity = sample_identity(now_unix() - 86_400 * 360, true);
        assert!(cert_still_usable(&identity));
    }

    #[test]
    fn a_certificate_past_its_lifetime_is_not_usable() {
        let identity = sample_identity(now_unix() - MASQUE_CERT_LIFETIME_SECS - 10, true);
        assert!(!cert_still_usable(&identity));
    }

    #[test]
    fn a_missing_certificate_is_never_usable() {
        let identity = sample_identity(now_unix(), false);
        assert!(!cert_still_usable(&identity));
    }

    #[test]
    fn a_rate_limited_rejection_explains_the_wait() {
        let message = describe_rejection(
            reqwest::StatusCode::TOO_MANY_REQUESTS,
            "{\"success\":false,\"errors\":[{\"code\":1015,\"message\":\"rate limited\"}]}",
        );
        assert!(message.contains("rate limited"));
        assert!(message.contains("code 1015"));
        assert!(message.contains("wait a few minutes"));
    }

    #[test]
    fn a_forbidden_rejection_mentions_the_flagged_address() {
        let message = describe_rejection(reqwest::StatusCode::FORBIDDEN, "");
        assert!(message.contains("flagged"));
    }

    #[test]
    fn only_transient_statuses_are_retried() {
        assert!(worth_retrying(reqwest::StatusCode::TOO_MANY_REQUESTS));
        assert!(worth_retrying(reqwest::StatusCode::BAD_GATEWAY));
        assert!(!worth_retrying(reqwest::StatusCode::FORBIDDEN));
        assert!(!worth_retrying(reqwest::StatusCode::BAD_REQUEST));
    }

    #[test]
    fn the_backoff_grows_and_stays_bounded() {
        let first = backoff_delay(0);
        let late = backoff_delay(6);
        assert!(first >= std::time::Duration::from_millis(API_BACKOFF_BASE_MS / 2));
        assert!(late <= std::time::Duration::from_millis(API_BACKOFF_CAP_MS * 2));
        assert!(late >= first);
    }

    #[test]
    fn a_retry_after_header_is_preserved_for_the_persistent_hold() {
        let mut headers = reqwest::header::HeaderMap::new();
        headers.insert(
            reqwest::header::RETRY_AFTER,
            reqwest::header::HeaderValue::from_static("120"),
        );
        assert_eq!(
            retry_after(&headers),
            Some(std::time::Duration::from_secs(120))
        );
    }

    #[tokio::test]
    async fn a_rate_limit_is_returned_once_with_its_full_server_hold() {
        use tokio::io::{AsyncReadExt, AsyncWriteExt};
        let _upstream = crate::upstream::hold_setting().await;
        let _options = crate::tls::hold_options().await;
        let (address, listener, acceptor) = crate::https::test_server(b"\x08http/1.1").await;
        std::env::set_var("AETHER_ENROLL_ADDRESS", address.to_string());
        std::env::remove_var("AETHER_ECH");
        let server = tokio::spawn(async move {
            let (tcp, _) = listener.accept().await.unwrap();
            let mut tls = tokio_boring::accept(&acceptor, tcp).await.unwrap();
            let mut request = Vec::new();
            while !request.windows(4).any(|bytes| bytes == b"\r\n\r\n") {
                let mut buffer = [0; 1024];
                let read = tls.read(&mut buffer).await.unwrap();
                assert!(read > 0);
                request.extend_from_slice(&buffer[..read]);
            }
            tls.write_all(b"HTTP/1.1 429 Too Many Requests\r\nRetry-After: 3600\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}").await.unwrap();
            tokio::time::timeout(std::time::Duration::from_millis(300), listener.accept())
                .await
                .is_ok()
        });
        let result = tokio::time::timeout(
            std::time::Duration::from_secs(3),
            api_call("registration", "POST", "/reg", Some(b"{}"), None, None),
        )
        .await;
        std::env::remove_var("AETHER_ENROLL_ADDRESS");
        let error = result
            .expect("a hold must not be slept out inside the API call")
            .unwrap_err();
        assert_eq!(error.retry_after(), Some(3600));
        assert!(
            !server.await.unwrap(),
            "a rate-limited registration was sent again"
        );
    }

    #[test]
    fn enrollment_applies_the_new_interface_and_edge_without_losing_credentials() {
        let mut old = handshake_identity();
        old.ipv4 = "172.16.0.2".into();
        old.assigned_endpoint = "162.159.192.2".into();
        let profile = serde_json::from_str(r#"{"id":"device","config":{"interface":{"addresses":{"v4":"172.16.1.4","v6":"2606:4700::4"}},"peers":[{"public_key":"","endpoint":{"v4":"162.159.198.2:2408"}}]}}"#).unwrap();
        let updated = MasqueEnrollment {
            cert_pem: old.cert_pem.clone(),
            key_pem: old.key_pem.clone(),
            issued_at: old.cert_issued_at,
            renewed: true,
            profile: Some(profile),
        }
        .apply(old);
        assert_eq!(updated.ipv4, "172.16.1.4");
        assert_eq!(updated.ipv6, "2606:4700::4");
        assert_eq!(updated.assigned_endpoint, "162.159.198.2");
        assert_eq!(updated.access_token, "token");
        assert!(updated.has_masque_credentials());
    }

    #[test]
    fn partial_enrollment_metadata_does_not_erase_a_saved_interface() {
        let old = handshake_identity();
        let ipv4 = old.ipv4.clone();
        let profile = serde_json::from_str(r#"{"id":"device"}"#).unwrap();
        let updated = MasqueEnrollment {
            cert_pem: old.cert_pem.clone(),
            key_pem: old.key_pem.clone(),
            issued_at: old.cert_issued_at,
            renewed: true,
            profile: Some(profile),
        }
        .apply(old);
        assert_eq!(updated.ipv4, ipv4);
    }

    #[test]
    fn a_missing_retry_after_header_is_ignored() {
        let headers = reqwest::header::HeaderMap::new();
        assert!(retry_after(&headers).is_none());
    }

    #[test]
    fn default_api_routes_include_both_names_and_edges_but_manual_names_stay_strict() {
        let routes = api_routes_of(None, None, None, None);
        assert_eq!(routes[0].host, "api.devices.cloudflare.com");
        assert_eq!(routes[1].host, "api.cloudflareclient.com");
        assert_eq!(routes.iter().filter(|route| route.front).count(), 2);
        assert!(routes.iter().all(|route| route.host == route.sni));
        let manual = api_routes_of(
            Some("api.example.test".into()),
            None,
            Some(("192.0.2.10".into(), 8443)),
            None,
        );
        assert_eq!(manual.len(), 1);
        assert_eq!(manual[0].address, "192.0.2.10");
        assert_eq!(manual[0].sni, "api.example.test");
    }

    async fn route_fixture(
        reply: Option<&'static str>,
    ) -> (
        ApiRoute,
        std::sync::Arc<std::sync::atomic::AtomicUsize>,
        tokio::task::JoinHandle<()>,
    ) {
        use tokio::io::{AsyncReadExt, AsyncWriteExt};
        let (address, listener, acceptor) = crate::https::test_server(b"\x08http/1.1").await;
        let count = std::sync::Arc::new(std::sync::atomic::AtomicUsize::new(0));
        let requests = count.clone();
        let task = tokio::spawn(async move {
            let mut jobs = tokio::task::JoinSet::new();
            loop {
                let (tcp, _) = listener.accept().await.unwrap();
                let acceptor = acceptor.clone();
                let count = count.clone();
                jobs.spawn(async move {
                    let Ok(mut tls) = tokio_boring::accept(&acceptor, tcp).await else {
                        return;
                    };
                    let read = async {
                        let mut header = Vec::new();
                        let mut byte = [0u8; 1];
                        while header.len() < 16384 {
                            if tls.read_exact(&mut byte).await.is_err() {
                                return false;
                            }
                            header.push(byte[0]);
                            if header.ends_with(b"\r\n\r\n") {
                                return true;
                            }
                        }
                        false
                    };
                    if !tokio::time::timeout(std::time::Duration::from_secs(2), read)
                        .await
                        .unwrap_or(false)
                    {
                        return;
                    }
                    count.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
                    if let Some(reply) = reply {
                        let _ = tls.write_all(reply.as_bytes()).await;
                    }
                });
            }
        });
        (
            ApiRoute {
                host: "api.example.test".into(),
                sni: "api.example.test".into(),
                address: address.ip().to_string(),
                port: address.port(),
                front: true,
            },
            requests,
            task,
        )
    }

    #[tokio::test]
    async fn an_edge_404_does_not_refuse_the_identity_when_another_api_route_works() {
        let _setting = crate::upstream::hold_setting().await;
        let _options = crate::tls::hold_options().await;
        let (bad, bad_count, bad_task) = route_fixture(Some(
            "HTTP/1.1 404 Not Found\r\nContent-Length: 4\r\nConnection: close\r\n\r\noops",
        ))
        .await;
        let (good, good_count, good_task) = route_fixture(Some(
            "HTTP/1.1 200 OK\r\nContent-Length: 15\r\nConnection: close\r\n\r\n{\"id\":\"device\"}",
        ))
        .await;
        let result =
            api_call_by(&[bad, good], "test", "GET", "/reg/device", None, None, None).await;
        bad_task.abort();
        good_task.abort();
        assert_eq!(result.unwrap().id, "device");
        assert_eq!(bad_count.load(std::sync::atomic::Ordering::SeqCst), 1);
        assert_eq!(good_count.load(std::sync::atomic::Ordering::SeqCst), 1);
        forget_answered_api_host();
    }

    #[tokio::test]
    async fn losing_an_api_reply_does_not_send_a_registration_down_the_other_route() {
        let _setting = crate::upstream::hold_setting().await;
        let _options = crate::tls::hold_options().await;
        let (first, sent, first_task) = route_fixture(None).await;
        let (other, other_sent, other_task) = route_fixture(Some(
            "HTTP/1.1 200 OK\r\nContent-Length: 15\r\nConnection: close\r\n\r\n{\"id\":\"device\"}",
        ))
        .await;
        let result = api_call_by(
            &[first, other],
            "registration",
            "POST",
            "/reg",
            Some(b"{}"),
            None,
            None,
        )
        .await;
        first_task.abort();
        other_task.abort();
        assert!(result.is_err());
        assert_eq!(sent.load(std::sync::atomic::Ordering::SeqCst), 1);
        assert_eq!(other_sent.load(std::sync::atomic::Ordering::SeqCst), 0);
    }

    #[test]
    fn an_enroll_address_is_an_ip_address_or_a_domain_name_with_or_without_a_port() {
        let address = |value: Option<&str>| {
            match value {
                Some(value) => std::env::set_var("AETHER_ENROLL_ADDRESS", value),
                None => std::env::remove_var("AETHER_ENROLL_ADDRESS"),
            }
            enroll_address().map_err(|e| e.to_string())
        };
        let at = |host: &str, port: u16| -> std::result::Result<(String, u16), String> {
            Ok((host.to_string(), port))
        };
        assert_eq!(address(None), at("api.devices.cloudflare.com", 443));
        assert_eq!(address(Some("  ")), at("api.devices.cloudflare.com", 443));
        assert_eq!(address(Some(" 188.114.97.6 ")), at("188.114.97.6", 443));
        assert_eq!(address(Some("188.114.97.6:443")), at("188.114.97.6", 443));
        assert_eq!(address(Some("188.114.97.6:2053")), at("188.114.97.6", 2053));
        assert_eq!(
            address(Some("[2606:4700::6810:1]")),
            at("2606:4700::6810:1", 443)
        );
        assert_eq!(
            address(Some("2606:4700::6810:1")),
            at("2606:4700::6810:1", 443)
        );
        assert_eq!(
            address(Some("[2606:4700::6810:1]:8443")),
            at("2606:4700::6810:1", 8443)
        );
        assert_eq!(
            address(Some("edge.example.com")),
            at("edge.example.com", 443)
        );
        assert_eq!(
            address(Some("edge.example.com:8443")),
            at("edge.example.com", 8443)
        );
        for refused in [
            "188.114.97.6:0",
            "188.114.97.6:65536",
            "edge.example.com:https",
            "https://edge.example.com",
            "edge example.com",
        ] {
            let said = address(Some(refused)).expect_err(refused);
            assert_eq!(
                said,
                format!(
                    "api: --api-address: {refused} is no IP address or domain name, with or without a port"
                )
            );
            assert!(check_enroll_address().is_err());
        }
        std::env::remove_var("AETHER_ENROLL_ADDRESS");
        assert!(check_enroll_address().is_ok());
    }

    #[test]
    fn a_run_starts_without_the_api_key_of_the_run_before() {
        // No other test calls the WARP API, so none sees the key change here.
        remember_api_ech(vec![1, 2, 3]);
        assert_eq!(api_ech_in_use(), Some(vec![1, 2, 3]));
        forget_api_ech();
        assert_eq!(api_ech_in_use(), None);
    }

    #[tokio::test]
    #[ignore = "registers a real warp device"]
    async fn the_api_registers_a_real_device() {
        let (_, public) = generate_x25519_keypair();
        let body = Registration {
            key: public,
            install_id: String::new(),
            fcm_token: String::new(),
            tos: tos_timestamp(),
            model: "PC".to_string(),
            serial_number: random_android_serial(),
            os_version: String::new(),
            key_type: "curve25519".to_string(),
            tunnel_type: "wireguard".to_string(),
            locale: "en_US".to_string(),
        };

        let encoded = serde_json::to_vec(&body).expect("encode");
        let path = format!("/{}/reg", consts::API_VERSION);

        let account = api_call("registration", "POST", &path, Some(&encoded), None, None)
            .await
            .expect("the API should register a device");

        println!(
            "device={} ipv4={}",
            account.id, account.config.interface.addresses.v4
        );
        assert!(!account.id.is_empty());
        assert!(!account.token.is_empty());
        assert!(!account.config.peers.is_empty());
    }

    #[test]
    fn a_generated_certificate_is_not_immediately_stale() {
        let pair = generate_masque_keypair().expect("keypair should be generated");
        assert!(!pair.cert_pem.is_empty());
        assert!(!pair.key_pem.is_empty());
        assert!(!masque_cert_expiring(now_unix()));
    }
}

#[cfg(test)]
mod identity_refusal_tests {
    use super::*;
    use reqwest::StatusCode;

    #[test]
    fn the_codes_that_mean_the_identity_is_gone() {
        for status in [
            StatusCode::UNAUTHORIZED,
            StatusCode::NOT_FOUND,
            StatusCode::GONE,
        ] {
            assert!(refuses_identity(status), "{status} means a dead identity");
        }
    }

    #[test]
    fn a_flagged_network_is_not_mistaken_for_a_dead_identity() {
        assert!(
            !refuses_identity(StatusCode::FORBIDDEN),
            "403 is cloudflare refusing the address, the identity may be fine"
        );
        assert!(
            !refuses_identity(StatusCode::TOO_MANY_REQUESTS),
            "429 is rate limiting, not a dead identity"
        );
    }

    #[test]
    fn a_server_or_transport_problem_is_not_a_dead_identity() {
        for status in [
            StatusCode::INTERNAL_SERVER_ERROR,
            StatusCode::BAD_GATEWAY,
            StatusCode::SERVICE_UNAVAILABLE,
            StatusCode::GATEWAY_TIMEOUT,
            StatusCode::REQUEST_TIMEOUT,
        ] {
            assert!(
                !refuses_identity(status),
                "{status} should be retried, not treated as a dead identity"
            );
        }
    }

    #[test]
    fn anything_worth_retrying_is_never_called_a_dead_identity() {
        for code in 400..600u16 {
            let status = StatusCode::from_u16(code).unwrap();
            assert!(
                !(worth_retrying(status) && refuses_identity(status)),
                "{status} cannot be both retryable and a dead identity"
            );
        }
    }
}
