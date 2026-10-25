use std::collections::HashSet;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};
use std::sync::{
    atomic::{AtomicBool, Ordering},
    Arc,
};
use std::time::{Duration, Instant};

use futures::stream::StreamExt;
use rand::RngExt;

use crate::error::{AetherError, Result};
use crate::noize::NoizeConfig;
use crate::quic;

pub const MASQUE_DOCUMENTED_CIDRS_V4: &[&str] = &["162.159.197.0/24", "162.159.198.0/24"];

pub const MASQUE_DOH_CIDRS_V4: &[&str] = &["162.159.36.0/24", "162.159.46.0/24"];

pub const MASQUE_CIDRS_V4: &[&str] = &[
    "162.159.196.0/24",
    "162.159.195.0/24",
    "162.159.192.0/24",
    "162.159.193.0/24",
    "162.159.204.0/24",
    "162.159.197.0/24",
    "162.159.198.0/24",
    // Where Cloudflare serves MASQUE to WARP+, as 162.159.198.0/24 to WARP and
    // 162.159.197.0/24 to Zero Trust.
    "162.159.199.0/24",
    "172.65.251.0/24",
    "188.114.96.0/24",
    "188.114.97.0/24",
    "188.114.98.0/24",
    "188.114.99.0/24",
    "162.159.36.0/24",
    "162.159.46.0/24",
];

/// The dedicated WARP ranges above are where an endpoint is most likely to be,
/// but they are not the only place one lives: Cloudflare anycasts the MASQUE
/// endpoint across its edge, and 104.25.202.52 -- squarely in the CDN range and
/// in none of the ranges above -- was confirmed working from an Iranian network
/// the scan had reported as empty. These are searched after the dedicated
/// ranges, on 443 only, since that is the port the edge serves.
pub const MASQUE_CDN_CIDRS_V4: &[&str] = &[
    "104.16.0.0/16",
    "104.17.0.0/16",
    "104.18.0.0/16",
    "104.19.0.0/16",
    "104.24.0.0/16",
    "104.25.0.0/16",
    "104.26.0.0/16",
    "104.27.0.0/16",
    "172.64.0.0/16",
    "172.66.0.0/16",
    "172.67.0.0/16",
    "103.21.244.0/22",
    "103.22.200.0/22",
    "141.101.64.0/18",
    "190.93.240.0/20",
    "198.41.128.0/17",
];

pub const MASQUE_SEEDS: &[&str] = &[
    "162.159.196.1",
    "162.159.195.1",
    "162.159.192.1",
    "162.159.197.3",
    "162.159.197.1",
    "162.159.198.2",
    "162.159.198.1",
    "162.159.193.1",
    // The MASQUE gateways of WARP+ and of Zero Trust, as 162.159.198.2 is WARP's.
    "162.159.199.2",
    "162.159.197.2",
];

/// The addresses the IPv4 scan tries without a team, each on every MASQUE port: those of
/// 162.159.198.0/24 and then those of 162.159.199.0/24 that completed a TCP handshake on port
/// 443, 75 and 222 of their 256, each range fastest first as measured. With a team, the scan
/// tries `MASQUE_SEEDS` and sweeps the ranges instead.
pub const MASQUE_GATEWAYS_V4: &[&str] = &[
    "162.159.198.183",
    "162.159.198.206",
    "162.159.198.226",
    "162.159.198.125",
    "162.159.198.72",
    "162.159.198.89",
    "162.159.198.198",
    "162.159.198.131",
    "162.159.198.245",
    "162.159.198.114",
    "162.159.198.31",
    "162.159.198.60",
    "162.159.198.4",
    "162.159.198.65",
    "162.159.198.33",
    "162.159.198.86",
    "162.159.198.244",
    "162.159.198.123",
    "162.159.198.233",
    "162.159.198.69",
    "162.159.198.255",
    "162.159.198.176",
    "162.159.198.186",
    "162.159.198.63",
    "162.159.198.37",
    "162.159.198.169",
    "162.159.198.147",
    "162.159.198.120",
    "162.159.198.135",
    "162.159.198.54",
    "162.159.198.234",
    "162.159.198.109",
    "162.159.198.175",
    "162.159.198.231",
    "162.159.198.219",
    "162.159.198.216",
    "162.159.198.64",
    "162.159.198.250",
    "162.159.198.184",
    "162.159.198.74",
    "162.159.198.241",
    "162.159.198.213",
    "162.159.198.232",
    "162.159.198.75",
    "162.159.198.77",
    "162.159.198.172",
    "162.159.198.145",
    "162.159.198.36",
    "162.159.198.124",
    "162.159.198.80",
    "162.159.198.192",
    "162.159.198.82",
    "162.159.198.137",
    "162.159.198.141",
    "162.159.198.48",
    "162.159.198.71",
    "162.159.198.227",
    "162.159.198.207",
    "162.159.198.49",
    "162.159.198.178",
    "162.159.198.197",
    "162.159.198.203",
    "162.159.198.204",
    "162.159.198.205",
    "162.159.198.195",
    "162.159.198.230",
    "162.159.198.202",
    "162.159.198.53",
    "162.159.198.79",
    "162.159.198.58",
    "162.159.198.35",
    "162.159.198.239",
    "162.159.198.116",
    "162.159.198.127",
    "162.159.198.240",
    "162.159.199.87",
    "162.159.199.117",
    "162.159.199.48",
    "162.159.199.217",
    "162.159.199.65",
    "162.159.199.198",
    "162.159.199.47",
    "162.159.199.103",
    "162.159.199.133",
    "162.159.199.151",
    "162.159.199.195",
    "162.159.199.73",
    "162.159.199.156",
    "162.159.199.247",
    "162.159.199.127",
    "162.159.199.158",
    "162.159.199.208",
    "162.159.199.82",
    "162.159.199.213",
    "162.159.199.70",
    "162.159.199.243",
    "162.159.199.204",
    "162.159.199.154",
    "162.159.199.240",
    "162.159.199.236",
    "162.159.199.126",
    "162.159.199.186",
    "162.159.199.188",
    "162.159.199.187",
    "162.159.199.124",
    "162.159.199.43",
    "162.159.199.102",
    "162.159.199.60",
    "162.159.199.219",
    "162.159.199.163",
    "162.159.199.193",
    "162.159.199.135",
    "162.159.199.110",
    "162.159.199.94",
    "162.159.199.136",
    "162.159.199.233",
    "162.159.199.77",
    "162.159.199.241",
    "162.159.199.223",
    "162.159.199.120",
    "162.159.199.38",
    "162.159.199.75",
    "162.159.199.57",
    "162.159.199.118",
    "162.159.199.50",
    "162.159.199.76",
    "162.159.199.58",
    "162.159.199.145",
    "162.159.199.81",
    "162.159.199.54",
    "162.159.199.234",
    "162.159.199.157",
    "162.159.199.121",
    "162.159.199.246",
    "162.159.199.182",
    "162.159.199.1",
    "162.159.199.251",
    "162.159.199.35",
    "162.159.199.179",
    "162.159.199.147",
    "162.159.199.137",
    "162.159.199.49",
    "162.159.199.138",
    "162.159.199.116",
    "162.159.199.172",
    "162.159.199.42",
    "162.159.199.160",
    "162.159.199.88",
    "162.159.199.212",
    "162.159.199.174",
    "162.159.199.79",
    "162.159.199.105",
    "162.159.199.130",
    "162.159.199.252",
    "162.159.199.238",
    "162.159.199.248",
    "162.159.199.125",
    "162.159.199.62",
    "162.159.199.34",
    "162.159.199.239",
    "162.159.199.84",
    "162.159.199.104",
    "162.159.199.196",
    "162.159.199.72",
    "162.159.199.97",
    "162.159.199.74",
    "162.159.199.229",
    "162.159.199.159",
    "162.159.199.192",
    "162.159.199.201",
    "162.159.199.44",
    "162.159.199.222",
    "162.159.199.142",
    "162.159.199.78",
    "162.159.199.235",
    "162.159.199.168",
    "162.159.199.162",
    "162.159.199.39",
    "162.159.199.209",
    "162.159.199.111",
    "162.159.199.139",
    "162.159.199.113",
    "162.159.199.215",
    "162.159.199.245",
    "162.159.199.86",
    "162.159.199.46",
    "162.159.199.180",
    "162.159.199.66",
    "162.159.199.173",
    "162.159.199.53",
    "162.159.199.216",
    "162.159.199.237",
    "162.159.199.114",
    "162.159.199.109",
    "162.159.199.214",
    "162.159.199.190",
    "162.159.199.106",
    "162.159.199.100",
    "162.159.199.92",
    "162.159.199.96",
    "162.159.199.61",
    "162.159.199.108",
    "162.159.199.64",
    "162.159.199.101",
    "162.159.199.140",
    "162.159.199.123",
    "162.159.199.112",
    "162.159.199.199",
    "162.159.199.225",
    "162.159.199.83",
    "162.159.199.69",
    "162.159.199.90",
    "162.159.199.153",
    "162.159.199.115",
    "162.159.199.149",
    "162.159.199.36",
    "162.159.199.224",
    "162.159.199.155",
    "162.159.199.37",
    "162.159.199.52",
    "162.159.199.63",
    "162.159.199.164",
    "162.159.199.253",
    "162.159.199.141",
    "162.159.199.242",
    "162.159.199.89",
    "162.159.199.67",
    "162.159.199.185",
    "162.159.199.98",
    "162.159.199.167",
    "162.159.199.0",
    "162.159.199.176",
    "162.159.199.189",
    "162.159.199.218",
    "162.159.199.211",
    "162.159.199.221",
    "162.159.199.220",
    "162.159.199.231",
    "162.159.199.230",
    "162.159.199.206",
    "162.159.199.175",
    "162.159.199.184",
    "162.159.199.203",
    "162.159.199.255",
    "162.159.199.171",
    "162.159.199.45",
    "162.159.199.228",
    "162.159.199.177",
    "162.159.199.165",
    "162.159.199.122",
    "162.159.199.99",
    "162.159.199.183",
    "162.159.199.161",
    "162.159.199.144",
    "162.159.199.119",
    "162.159.199.128",
    "162.159.199.146",
    "162.159.199.148",
    "162.159.199.68",
    "162.159.199.56",
    "162.159.199.95",
    "162.159.199.55",
    "162.159.199.197",
    "162.159.199.41",
    "162.159.199.194",
    "162.159.199.226",
    "162.159.199.134",
    "162.159.199.249",
    "162.159.199.232",
    "162.159.199.227",
    "162.159.199.191",
    "162.159.199.150",
    "162.159.199.170",
    "162.159.199.202",
    "162.159.199.169",
    "162.159.199.207",
    "162.159.199.166",
    "162.159.199.250",
    "162.159.199.210",
    "162.159.199.181",
    "162.159.199.132",
    "162.159.199.129",
    "162.159.199.107",
    "162.159.199.71",
    "162.159.199.152",
    "162.159.199.91",
    "162.159.199.131",
    "162.159.199.205",
    "162.159.199.85",
    "162.159.199.59",
    "162.159.199.51",
    "162.159.199.143",
    "162.159.199.244",
    "162.159.199.254",
    "162.159.199.178",
    "162.159.199.200",
    "162.159.199.93",
];

pub const MASQUE_VERIFIED_GATEWAYS: &[&str] = &[
    "162.159.199.1",
    "162.159.199.2",
    "162.159.198.2",
    "162.159.198.1",
];

pub const MASQUE_ALT_PORTS: &[u16] = &[1701, 8095, 500, 4500];

pub const MASQUE_PORTS: &[u16] = &[443, 500, 1701, 4500, 4443, 8443, 8095];

pub const MASQUE_CIDRS_V6: &[&str] = &[
    "2606:4700:d0::/48",
    "2606:4700:102::/48",
    "2606:4700:d1::/48",
    // WARP's MASQUE over IPv6, where the WARP client's own gateway is 2606:4700:103::1.
    "2606:4700:103::/48",
];

pub const MASQUE_ZT_CIDRS_V4: &[&str] = &["162.159.197.0/24"];

pub const MASQUE_ZT_CIDRS_V6: &[&str] = &["2606:4700:102::/48"];

pub fn zero_trust_mode() -> bool {
    std::env::var("AETHER_TEAM")
        .map(|value| !value.trim().is_empty())
        .unwrap_or(false)
}

pub fn prioritize(all: &[&'static str], first: &[&'static str]) -> Vec<&'static str> {
    if !zero_trust_mode() {
        return all.to_vec();
    }

    let mut out: Vec<&'static str> = Vec::with_capacity(all.len());
    for entry in first {
        if all.contains(entry) {
            out.push(entry);
        }
    }
    for entry in all {
        if !out.contains(entry) {
            out.push(entry);
        }
    }
    out
}

pub fn masque_cidrs_v4() -> Vec<&'static str> {
    prioritize(MASQUE_CIDRS_V4, MASQUE_ZT_CIDRS_V4)
}

pub fn masque_cidrs_v6() -> Vec<&'static str> {
    prioritize(MASQUE_CIDRS_V6, MASQUE_ZT_CIDRS_V6)
}

/// The IPv6 gateways tried first. The scan's other IPv6 addresses carry an IPv4 address of the
/// ranges in their last 32 bits, so 2606:4700:103::1, the WARP client's own MASQUE gateway,
/// is here or nowhere.
pub const MASQUE_SEEDS_V6: &[&str] = &[
    "2606:4700:103::1",
    "2606:4700:d0::a29f:c602",
    "2606:4700:d1::a29f:c602",
    "2606:4700:d0::a29f:c601",
    "2606:4700:d0::a29f:c001",
];

/// The IPv6 gateway the scan tries first without a team, on every MASQUE port: WARP's own, the
/// one of `MASQUE_SEEDS_V6` in `MASQUE_WARP_CIDRS_V6`.
pub const MASQUE_GATEWAYS_V6: &[&str] = &["2606:4700:103::1"];

/// The IPv6 range the scan samples without a team: regular WARP's MASQUE connects on
/// 2606:4700:103::/48 and on no other range of `MASQUE_CIDRS_V6`. With a team, the scan tries
/// `MASQUE_SEEDS_V6` and samples every range instead.
pub const MASQUE_WARP_CIDRS_V6: &[&str] = &["2606:4700:103::/48"];

#[derive(Debug, Clone, Copy)]
pub struct ProbeResult {
    pub ip: IpAddr,
    pub port: u16,
    pub rtt: Duration,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum IpScan {
    V4,
    V6,
    Both,
}

impl IpScan {
    pub fn parse(s: &str) -> IpScan {
        match s.trim().to_lowercase().as_str() {
            "6" | "v6" | "ipv6" => IpScan::V6,
            "both" | "all" | "dual" => IpScan::Both,
            _ => IpScan::V4,
        }
    }

    pub fn label(&self) -> &'static str {
        match self {
            IpScan::V4 => "ipv4",
            IpScan::V6 => "ipv6",
            IpScan::Both => "dual-stack",
        }
    }

    pub fn want_v4(&self) -> bool {
        matches!(self, IpScan::V4 | IpScan::Both)
    }

    pub fn want_v6(&self) -> bool {
        matches!(self, IpScan::V6 | IpScan::Both)
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ScanMode {
    Turbo,
    Balanced,
    Thorough,
    Verified,
    Ironclad,
}

impl ScanMode {
    pub fn parse(s: &str) -> ScanMode {
        match s.trim().to_lowercase().as_str() {
            "turbo" | "fast" => ScanMode::Turbo,
            "thorough" | "deep" | "pro" => ScanMode::Thorough,
            "verified" | "proven" | "stealth" | "quiet" => ScanMode::Verified,
            "ironclad" | "real" | "verify" | "guaranteed" => ScanMode::Ironclad,
            _ => ScanMode::Balanced,
        }
    }

    pub fn label(&self) -> &'static str {
        match self {
            ScanMode::Turbo => "turbo",
            ScanMode::Balanced => "balanced",
            ScanMode::Thorough => "thorough",
            ScanMode::Verified => "verified",
            ScanMode::Ironclad => "ironclad",
        }
    }

    /// Concurrency and overall budget, for the cross-prober comparison in
    /// `lib.rs`. The two strategies are private and differently shaped; this is
    /// the pair that decides how much of the space gets searched.
    #[cfg(test)]
    pub(crate) fn strategy_for_test(&self) -> (usize, Duration) {
        let s = self.strategy();
        (s.concurrency, s.overall_deadline)
    }

    fn strategy(&self) -> Strategy {
        let mut strategy = match self {
            ScanMode::Turbo => Strategy {
                concurrency: 20,
                per_probe_timeout: Duration::from_millis(6000),
                overall_deadline: Duration::from_secs(45),
                quiet_after_first: Duration::from_secs(0),
                target_successes: 1,
                early_exit_first: true,
                full_subnet: false,
                sample_per_cidr: 64,
            },
            ScanMode::Balanced => Strategy {
                concurrency: 16,
                per_probe_timeout: Duration::from_millis(6000),
                overall_deadline: Duration::from_secs(120),
                quiet_after_first: Duration::from_secs(20),
                target_successes: 6,
                early_exit_first: false,
                full_subnet: false,
                sample_per_cidr: 140,
            },
            ScanMode::Thorough => Strategy {
                concurrency: 20,
                per_probe_timeout: Duration::from_millis(10000),
                overall_deadline: Duration::from_secs(300),
                quiet_after_first: Duration::from_secs(30),
                target_successes: 0,
                early_exit_first: false,
                full_subnet: true,
                sample_per_cidr: 0,
            },
            ScanMode::Verified => Strategy {
                concurrency: 16,
                per_probe_timeout: Duration::from_millis(5000),
                overall_deadline: Duration::from_secs(60),
                quiet_after_first: Duration::from_secs(8),
                target_successes: 4,
                early_exit_first: false,
                full_subnet: false,
                sample_per_cidr: 48,
            },
            ScanMode::Ironclad => Strategy {
                concurrency: 4,
                per_probe_timeout: Duration::from_millis(15000),
                overall_deadline: Duration::from_secs(180),
                quiet_after_first: Duration::from_secs(15),
                target_successes: 3,
                early_exit_first: false,
                full_subnet: false,
                sample_per_cidr: 140,
            },
        };
        if matches!(self, ScanMode::Turbo)
            && std::env::var("AETHER_CONNECT_FAST").as_deref() == Ok("1")
        {
            strategy.overall_deadline = Duration::from_secs(12);
            strategy.per_probe_timeout = Duration::from_secs(3);
            strategy.concurrency = 8;
        }
        strategy
    }
}

const IRONCLAD_TCPING_TIMEOUT: Duration = Duration::from_secs(10);

struct Strategy {
    concurrency: usize,
    per_probe_timeout: Duration,
    overall_deadline: Duration,
    quiet_after_first: Duration,
    target_successes: usize,
    early_exit_first: bool,
    full_subnet: bool,
    sample_per_cidr: usize,
}

#[derive(Clone)]
pub struct MasqueProbe {
    pub sni: String,
    pub authority: String,
    pub path: String,
    pub cert_pem: Arc<[u8]>,
    pub key_pem: Arc<[u8]>,
    pub ech_config_list: Option<Arc<[u8]>>,
    pub noize: NoizeConfig,
    pub ports: Vec<u16>,
    pub ip: IpScan,
    pub local_ipv4: Ipv4Addr,
}

pub async fn host_has_ipv6() -> bool {
    match crate::egress::udp_bind("[::]:0".parse().expect("a wildcard address")) {
        Ok(sock) => sock.connect("[2606:4700:d0::a29f:c001]:443").await.is_ok(),
        Err(_) => false,
    }
}

/// Why one MASQUE probe produced no gateway.
///
/// Classified rather than discarded. Every failure used to become `None` at
/// trace level, so three thousand probes failing for one reason and three
/// thousand failing because the network is hostile ended as the same sentence:
/// `no clean endpoint found`. That sentence blames the network, and in the
/// incident that produced this it was wrong every time.
///
/// Read off the error variant where the variant is enough, and off the message
/// only for the finer splits the variants do not carry. The types underneath --
/// quic, masque_h2, tls -- still speak in strings; narrowing that is a larger
/// change than this, and this is the part that decides what the engine does
/// next.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub enum ProbeFailure {
    /// Nothing came back before the deadline.
    NoAnswer,
    /// The address refused the connection outright.
    Refused,
    /// TLS completed and the certificate was not one we accept.
    PinMismatch,
    /// TLS itself would not complete.
    TlsRejected,
    /// The gateway answered the connect-ip request with a refusal.
    ConnectIpRefused,
    /// A tunnel opened and closed before carrying anything.
    NoDataPlane,
    /// The connection ended before CONNECT-IP was accepted.
    HandshakeClosed,
    AlpnRejected,
    EchRejected,
    ClientCertificateRejected,
    Other,
}

impl ProbeFailure {
    pub fn label(self) -> &'static str {
        match self {
            ProbeFailure::NoAnswer => "no answer",
            ProbeFailure::Refused => "refused",
            ProbeFailure::PinMismatch => "certificate not ours",
            ProbeFailure::TlsRejected => "tls refused",
            ProbeFailure::ConnectIpRefused => "connect-ip refused",
            ProbeFailure::NoDataPlane => "opened, carried nothing",
            ProbeFailure::HandshakeClosed => "closed before tunnel opened",
            ProbeFailure::AlpnRejected => "tunnel protocol not supported",
            ProbeFailure::EchRejected => "ECH refused",
            ProbeFailure::ClientCertificateRejected => "client certificate refused",
            ProbeFailure::Other => "other",
        }
    }

    /// Whether every address in the pool would fail this way.
    ///
    /// A certificate we do not accept, or a request the gateway refuses, is a
    /// fault in what we are sending -- not in which address we sent it to. No
    /// number of further probes changes it, and spending the budget to confirm
    /// that is how a fixable fault comes to look like censorship.
    pub fn is_common_mode(self) -> bool {
        matches!(
            self,
            ProbeFailure::PinMismatch | ProbeFailure::ConnectIpRefused
        )
    }

    fn of(error: &AetherError) -> Self {
        let text = error.to_string().to_ascii_lowercase();
        match error {
            AetherError::Ech(_) => ProbeFailure::EchRejected,
            _ if text.contains("code=0x178")
                || text.contains("no application protocol")
                || text.contains("h2 alpn") =>
            {
                ProbeFailure::AlpnRejected
            }
            _ if text.contains("this end closed it: code=0x12e") => ProbeFailure::PinMismatch,
            _ if text.contains("the gateway closed it:")
                && ["code=0x12a", "code=0x130", "code=0x174"]
                    .iter()
                    .any(|code| text.contains(code)) =>
            {
                ProbeFailure::ClientCertificateRejected
            }
            AetherError::Tls(_) if text.contains("application verification") => {
                ProbeFailure::PinMismatch
            }
            AetherError::Tls(_) => ProbeFailure::TlsRejected,
            AetherError::Io(e) if e.kind() == std::io::ErrorKind::ConnectionRefused => {
                ProbeFailure::Refused
            }
            AetherError::Io(e) if e.kind() == std::io::ErrorKind::TimedOut => {
                ProbeFailure::NoAnswer
            }
            _ if text.contains("connect-ip status") => ProbeFailure::ConnectIpRefused,
            _ if text.contains("before data-plane")
                || text.contains("stream closed before data") =>
            {
                ProbeFailure::NoDataPlane
            }
            _ if text.contains("closed before the tunnel carried anything") => {
                ProbeFailure::HandshakeClosed
            }
            _ if text.contains("timed out")
                || text.contains("timeout")
                || text.contains("deadline") =>
            {
                ProbeFailure::NoAnswer
            }
            _ if text.contains("refused") => ProbeFailure::Refused,
            _ => ProbeFailure::Other,
        }
    }
}

/// What a run of probes ran into, for the log and for the decision to stop.
#[derive(Debug, Default, Clone)]
pub struct FailureTally {
    counts: std::collections::BTreeMap<ProbeFailure, usize>,
    total: usize,
    examples: std::collections::BTreeMap<ProbeFailure, String>,
}

#[derive(Debug)]
struct ProbeError {
    kind: ProbeFailure,
    detail: String,
}

impl From<AetherError> for ProbeError {
    fn from(error: AetherError) -> Self {
        Self {
            kind: ProbeFailure::of(&error),
            detail: error.to_string(),
        }
    }
}

/// Probes to see before one repeated answer is believed to be about all of them.
const COMMON_MODE_SAMPLE: usize = 40;

/// The share of that sample one kind has to hold.
const COMMON_MODE_SHARE: f64 = 0.9;

impl FailureTally {
    fn record(&mut self, kind: ProbeFailure) {
        *self.counts.entry(kind).or_insert(0) += 1;
        self.total += 1;
    }

    fn record_error(&mut self, error: ProbeError) {
        self.record(error.kind);
        self.examples.entry(error.kind).or_insert_with(|| {
            error
                .detail
                .chars()
                .filter(|c| !c.is_control())
                .take(320)
                .collect()
        });
    }

    fn details(&self) -> String {
        let mut kinds: Vec<_> = self.counts.iter().collect();
        kinds.sort_by_key(|(_, count)| std::cmp::Reverse(**count));
        let mut text = self.summary();
        for (kind, _) in kinds.into_iter().take(3) {
            if let Some(example) = self.examples.get(kind) {
                text.push_str(&format!("; {} example: {example}", kind.label()));
            }
        }
        text
    }

    /// The kind that is answering for effectively all of them, if there is one.
    fn common_mode(&self) -> Option<(ProbeFailure, usize)> {
        if self.total < COMMON_MODE_SAMPLE {
            return None;
        }
        self.counts
            .iter()
            .find(|(kind, count)| {
                kind.is_common_mode() && **count as f64 >= self.total as f64 * COMMON_MODE_SHARE
            })
            .map(|(kind, count)| (*kind, *count))
    }

    pub fn summary(&self) -> String {
        if self.total == 0 {
            return "nothing was probed".to_string();
        }
        self.counts
            .iter()
            .map(|(kind, count)| format!("{}={count}", kind.label()))
            .collect::<Vec<_>>()
            .join(" ")
    }
}

pub async fn hunt_best_gateway(probe: &MasqueProbe, mode: ScanMode) -> Result<ProbeResult> {
    hunt_best_gateway_avoiding(probe, mode, &HashSet::new()).await
}

/// `hunt_best_gateway`, leaving out the addresses in `avoid` on every port: gateways a run gave
/// up on for a while.
pub async fn hunt_best_gateway_avoiding(
    probe: &MasqueProbe,
    mode: ScanMode,
    avoid: &HashSet<IpAddr>,
) -> Result<ProbeResult> {
    let mut st = mode.strategy();
    st.concurrency = crate::sysprofile::cap_concurrency(st.concurrency);
    let timeout = st.per_probe_timeout;
    let mut effective_ip = probe.ip;
    if probe.ip.want_v6() && !host_has_ipv6().await {
        if probe.ip.want_v4() {
            log::warn!("[-] host has no IPv6 route; falling back to IPv4-only scan");
            effective_ip = IpScan::V4;
        } else {
            log::warn!("[-] host has no IPv6 route; IPv6 scan needs native IPv6 connectivity");
            return Err(AetherError::NoCleanEndpoint);
        }
    }
    let candidates = leave_out(
        build_candidates(&st, &probe.ports, effective_ip, zero_trust_mode()),
        avoid,
    );
    if !avoid.is_empty() {
        log::info!(
            "[*] leaving {} gateway(s) out of this scan for now",
            avoid.len()
        );
    }

    log::info!(
        "[*] scan mode={} ip={} candidates={} ports={:?} concurrency={} per_probe={:?} budget={:?}",
        mode.label(),
        effective_ip.label(),
        candidates.len(),
        probe.ports,
        st.concurrency,
        st.per_probe_timeout,
        st.overall_deadline,
    );

    let ironclad = mode == ScanMode::Ironclad;

    let stream = futures::stream::iter(
        candidates
            .into_iter()
            .map(|(ip, port)| verify_one(probe, ip, port, timeout, ironclad)),
    )
    .buffer_unordered(st.concurrency);
    tokio::pin!(stream);

    let deadline = Instant::now() + st.overall_deadline;
    let mut best: Option<ProbeResult> = None;
    let mut found = 0usize;
    let mut quiet_until: Option<Instant> = None;
    let mut tally = FailureTally::default();

    loop {
        let effective = match quiet_until {
            Some(q) => q.min(deadline),
            None => deadline,
        };
        let remaining = effective.saturating_duration_since(Instant::now());
        if remaining.is_zero() {
            if best.is_some() {
                if quiet_until.is_some() {
                    log::info!("[+] no new gateways recently, finalizing selection");
                } else {
                    log::warn!("[-] scan deadline reached");
                }
            } else {
                log::warn!(
                    "[-] scan deadline reached with no gateway ({})",
                    tally.summary()
                );
            }
            break;
        }

        tokio::select! {
            item = stream.next() => {
                match item {
                    None => break,
                    Some(Err(why)) => {
                        tally.record(why);
                        // One answer repeated by everything is about what we are
                        // sending, not about where we sent it. Confirming that
                        // across the remaining candidates costs the whole budget
                        // and teaches nothing.
                        if best.is_none() {
                            if let Some((kind, count)) = tally.common_mode() {
                                log::warn!(
                                    "[-] stopping the scan: {count} of {} probes answered the same                                      way ({}), which is not about the address",
                                    tally.total,
                                    kind.label()
                                );
                                return Err(AetherError::Other(format!(
                                    "every gateway answered the same way ({}); this is not the                                      network",
                                    kind.label()
                                )));
                            }
                        }
                        continue;
                    }
                    Some(Ok(pr)) => {
                        log::info!("[+] candidate ok {}:{} rtt={:?}", pr.ip, pr.port, pr.rtt);
                        if st.early_exit_first {
                            return Ok(pr);
                        }
                        best = Some(match best {
                            Some(cur) if cur.rtt <= pr.rtt => cur,
                            _ => pr,
                        });
                        found += 1;

                        if st.target_successes > 0 && found >= st.target_successes && quiet_until.is_none() {
                            log::info!("[+] reached target of {} gateways, selecting best", st.target_successes);
                            if !st.quiet_after_first.is_zero() {
                                quiet_until = Some(Instant::now() + st.quiet_after_first);
                            } else {
                                break;
                            }
                        }
                    }
                }
            }
            _ = tokio::time::sleep(remaining) => {
                if best.is_some() {
                    if quiet_until.is_some() {
                        log::info!("[+] no new gateways recently, finalizing selection");
                    } else {
                        log::warn!("[-] scan deadline reached");
                    }
                } else {
                    log::warn!(
                        "[-] scan deadline reached with no gateway ({})",
                        tally.summary()
                    );
                }
                break;
            }
        }
    }

    match best {
        Some(pr) => {
            log::info!("[+] best gateway {}:{} rtt={:?}", pr.ip, pr.port, pr.rtt);
            Ok(pr)
        }
        None => {
            log::warn!("[-] nothing answered ({})", tally.summary());
            Err(AetherError::NoCleanEndpoint)
        }
    }
}

pub async fn scan_gateways(
    probe: &MasqueProbe,
    mode: ScanMode,
    limit: usize,
    cancelled: &AtomicBool,
) -> Result<Vec<ProbeResult>> {
    scan_gateways_observed(probe, mode, limit, cancelled, &[], &|_| {}).await
}

pub async fn scan_gateways_observed(
    probe: &MasqueProbe,
    mode: ScanMode,
    limit: usize,
    cancelled: &AtomicBool,
    preferred: &[SocketAddr],
    observer: crate::scan::ScanObserver<'_>,
) -> Result<Vec<ProbeResult>> {
    let mut st = mode.strategy();
    st.concurrency = crate::sysprofile::cap_concurrency(st.concurrency);
    let timeout = st.per_probe_timeout;
    let mut effective_ip = probe.ip;
    if probe.ip.want_v6() && !host_has_ipv6().await {
        if probe.ip.want_v4() {
            effective_ip = IpScan::V4;
        } else {
            return Err(AetherError::NoCleanEndpoint);
        }
    }
    let candidates = discovery_candidates(
        preferred,
        build_candidates(&st, &probe.ports, effective_ip, zero_trust_mode()),
        effective_ip,
    );
    let ironclad = mode == ScanMode::Ironclad;
    let started = Instant::now();
    let mut progress = crate::scan::ScanProgress::new(candidates.len());
    let mut failures = FailureTally::default();
    progress.publish(started, String::new(), observer);
    let stream = futures::stream::iter(
        candidates
            .into_iter()
            .map(|(ip, port)| verify_one_detailed(probe, ip, port, timeout, ironclad)),
    )
    .buffer_unordered(st.concurrency);
    tokio::pin!(stream);

    let limit = limit.clamp(1, 16);
    let deadline = Instant::now() + st.overall_deadline;
    let mut quiet_until: Option<Instant> = None;
    let mut results = Vec::new();

    loop {
        if cancelled.load(Ordering::Relaxed) {
            return Err(AetherError::Other("endpoint scan cancelled".into()));
        }
        let effective = quiet_until.map_or(deadline, |quiet| quiet.min(deadline));
        let remaining = effective.saturating_duration_since(Instant::now());
        if remaining.is_zero() {
            break;
        }

        tokio::select! {
            item = stream.next() => {
                match item {
                    None => break,
                    Some(Err(error)) => {
                        progress.completed += 1;
                        failures.record_error(error);
                        progress.publish(started, failures.details(), observer);
                        // Web/CDN addresses can reject the tunnel immediately while
                        // an assigned gateway is still handshaking. Their failures
                        // must not cancel that pending candidate.
                        continue;
                    },
                    Some(Ok(result)) => {
                        progress.completed += 1;
                        progress.hit(SocketAddr::new(result.ip, result.port), result.rtt);
                        progress.publish(started, failures.details(), observer);
                        if !results.iter().any(|existing: &ProbeResult| {
                            existing.ip == result.ip && existing.port == result.port
                        }) {
                            results.push(result);
                            results.sort_by_key(|entry| entry.rtt);
                            if results.len() >= limit {
                                break;
                            }
                        }
                        if st.quiet_after_first.is_zero() {
                            break;
                        }
                        quiet_until = Some(Instant::now() + st.quiet_after_first);
                    }
                }
            }
            _ = tokio::time::sleep(remaining) => break,
            _ = tokio::time::sleep(Duration::from_millis(100)) => {}
        }
    }

    if cancelled.load(Ordering::Relaxed) {
        return Err(AetherError::Other("endpoint scan cancelled".into()));
    }
    if results.is_empty() {
        Err(AetherError::Other(format!(
            "no working MASQUE endpoint: {}",
            failures.details()
        )))
    } else {
        Ok(results)
    }
}

/// Strong confirmation for a short list, using the exact framing and TLS options.
pub async fn verify_gateway_traffic(probe: &MasqueProbe, peer: SocketAddr) -> Result<Duration> {
    let params = crate::tunnelping::MasquePingParams {
        peer,
        sni: probe.sni.clone(),
        authority: probe.authority.clone(),
        path: probe.path.clone(),
        cert_pem: probe.cert_pem.to_vec(),
        key_pem: probe.key_pem.to_vec(),
        ech_config_list: probe.ech_config_list.as_ref().map(|a| a.to_vec()),
        noize: probe.noize.clone(),
        local_ipv4: probe.local_ipv4,
        local_ipv4_str: probe.local_ipv4.to_string(),
        local_ipv6_str: String::new(),
    };
    crate::tunnelping::masque_http_ping(&params, Duration::from_secs(15)).await
}

/// `candidates` without the addresses in `avoid`, whatever their port.
fn leave_out(candidates: Vec<(IpAddr, u16)>, avoid: &HashSet<IpAddr>) -> Vec<(IpAddr, u16)> {
    candidates
        .into_iter()
        .filter(|(ip, _)| !avoid.contains(ip))
        .collect()
}

async fn verify_one(
    probe: &MasqueProbe,
    ip: IpAddr,
    port: u16,
    timeout: Duration,
    ironclad: bool,
) -> std::result::Result<ProbeResult, ProbeFailure> {
    verify_one_detailed(probe, ip, port, timeout, ironclad)
        .await
        .map_err(|error| error.kind)
}

async fn verify_one_detailed(
    probe: &MasqueProbe,
    ip: IpAddr,
    port: u16,
    timeout: Duration,
    ironclad: bool,
) -> std::result::Result<ProbeResult, ProbeError> {
    if ironclad {
        let params = crate::tunnelping::MasquePingParams {
            peer: SocketAddr::new(ip, port),
            sni: probe.sni.clone(),
            authority: probe.authority.clone(),
            path: probe.path.clone(),
            cert_pem: probe.cert_pem.to_vec(),
            key_pem: probe.key_pem.to_vec(),
            ech_config_list: probe.ech_config_list.as_ref().map(|a| a.to_vec()),
            noize: probe.noize.clone(),
            local_ipv4: probe.local_ipv4,
            local_ipv4_str: probe.local_ipv4.to_string(),
            local_ipv6_str: String::new(),
        };
        return match crate::tunnelping::masque_http_ping(&params, IRONCLAD_TCPING_TIMEOUT).await {
            Ok(rtt) => {
                log::info!(
                    "[+] ironclad verified {ip}:{port} real http round trip rtt={:?}",
                    rtt
                );
                Ok(ProbeResult { ip, port, rtt })
            }
            Err(e) => {
                log::debug!("[-] ironclad {ip}:{port} failed real http check: {e}");
                Err(ProbeError::from(e))
            }
        };
    }

    if crate::masque_h2::enabled() {
        let cfg = crate::masque_h2::H2TunnelConfig {
            peer: SocketAddr::new(ip, port),
            sni: probe.sni.clone(),
            authority: probe.authority.clone(),
            path: probe.path.clone(),
            cert_pem: probe.cert_pem.to_vec(),
            key_pem: probe.key_pem.to_vec(),
            local_ipv4: probe.local_ipv4,
            quiet: true,
            pin_endpoint: true,
            expected_pins: crate::consts::masque_pins()
                .iter()
                .map(|p| p.to_vec())
                .collect(),
            ech_config_list: probe.ech_config_list.as_ref().map(|a| a.to_vec()),
        };
        return match crate::masque_h2::verify_h2(&cfg, timeout).await {
            Ok(rtt) => Ok(ProbeResult { ip, port, rtt }),
            Err(e) => {
                log::debug!("h2 probe {ip}:{port} -> {e}");
                Err(ProbeError::from(e))
            }
        };
    }

    let vp = quic::VerifyParams {
        peer: SocketAddr::new(ip, port),
        sni: probe.sni.clone(),
        authority: probe.authority.clone(),
        path: probe.path.clone(),
        cert_pem: probe.cert_pem.to_vec(),
        key_pem: probe.key_pem.to_vec(),
        ech_config_list: probe.ech_config_list.as_ref().map(|a| a.to_vec()),
        noize: probe.noize.clone(),
        timeout,
        local_ipv4: probe.local_ipv4,
    };

    match quic::verify_masque(&vp).await {
        Ok(rtt) => Ok(ProbeResult { ip, port, rtt }),
        Err(e) => {
            log::debug!("probe {ip}:{port} -> {e}");
            Err(ProbeError::from(e))
        }
    }
}

fn build_candidates(st: &Strategy, ports: &[u16], ip: IpScan, team: bool) -> Vec<(IpAddr, u16)> {
    let primary = ports.first().copied().unwrap_or(443);
    let mut out: Vec<(IpAddr, u16)> = Vec::new();
    let mut seen: HashSet<(IpAddr, u16)> = HashSet::new();

    // Regular WARP's IPv4 scan is its gateways alone; a team's is the seeds, then the ranges.
    let (v4_seeds, ranges) = if team {
        (MASQUE_SEEDS, masque_cidrs_v4())
    } else {
        (MASQUE_GATEWAYS_V4, Vec::new())
    };
    // Over IPv6, regular WARP's is its gateway, then its one range; a team's, the seeds, then
    // every range.
    let (v6_seeds, ranges6) = if team {
        (MASQUE_SEEDS_V6, masque_cidrs_v6())
    } else {
        (MASQUE_GATEWAYS_V6, MASQUE_WARP_CIDRS_V6.to_vec())
    };
    let seeds: Vec<Ipv4Addr> = v4_seeds.iter().filter_map(|s| s.parse().ok()).collect();
    let seeds6: Vec<Ipv6Addr> = v6_seeds.iter().filter_map(|s| s.parse().ok()).collect();

    if ip.want_v4() {
        for a in &seeds {
            if seen.insert((IpAddr::V4(*a), primary)) {
                out.push((IpAddr::V4(*a), primary));
            }
        }
        let cidr_hosts: Vec<Vec<Ipv4Addr>> = ranges
            .iter()
            .map(|c| {
                if st.full_subnet {
                    enumerate_cidr_v4(c)
                } else {
                    sample_cidr_v4(c, st.sample_per_cidr)
                }
            })
            .collect();
        let max_len = cidr_hosts.iter().map(|v| v.len()).max().unwrap_or(0);
        for i in 0..max_len {
            for hosts in &cidr_hosts {
                if let Some(a) = hosts.get(i) {
                    if seen.insert((IpAddr::V4(*a), primary)) {
                        out.push((IpAddr::V4(*a), primary));
                    }
                }
            }
        }

        // Edge ranges last, so a network where the dedicated ranges answer is
        // unaffected and only one that finds nothing there pays for the wider
        // sweep. Sampled more thinly, because they are far larger and a smaller
        // share of them terminates MASQUE.
        if st.sample_per_cidr > 0 {
            let per = (st.sample_per_cidr / 4).max(8);
            let cdn: Vec<Vec<Ipv4Addr>> = MASQUE_CDN_CIDRS_V4
                .iter()
                .map(|c| sample_cidr_v4(c, per))
                .collect();
            let cdn_max = cdn.iter().map(|v| v.len()).max().unwrap_or(0);
            for i in 0..cdn_max {
                for hosts in &cdn {
                    if let Some(a) = hosts.get(i) {
                        if seen.insert((IpAddr::V4(*a), 443)) {
                            out.push((IpAddr::V4(*a), 443));
                        }
                    }
                }
            }
        }
    }

    if ip.want_v6() {
        for a in &seeds6 {
            if seen.insert((IpAddr::V6(*a), primary)) {
                out.push((IpAddr::V6(*a), primary));
            }
        }
        let per = if st.sample_per_cidr == 0 {
            96
        } else {
            st.sample_per_cidr
        };
        let cidr6: Vec<Vec<Ipv6Addr>> = ranges6
            .iter()
            .map(|c| sample_cidr_v6(c, per, MASQUE_CIDRS_V4))
            .collect();
        let max6 = cidr6.iter().map(|v| v.len()).max().unwrap_or(0);
        for i in 0..max6 {
            for hosts in &cidr6 {
                if let Some(a) = hosts.get(i) {
                    if seen.insert((IpAddr::V6(*a), primary)) {
                        out.push((IpAddr::V6(*a), primary));
                    }
                }
            }
        }
    }

    if ip.want_v4() {
        for a in &seeds {
            for &port in ports {
                if port != primary && seen.insert((IpAddr::V4(*a), port)) {
                    out.push((IpAddr::V4(*a), port));
                }
            }
        }
    }
    if ip.want_v6() {
        for a in &seeds6 {
            for &port in ports {
                if port != primary && seen.insert((IpAddr::V6(*a), port)) {
                    out.push((IpAddr::V6(*a), port));
                }
            }
        }
    }

    fair_candidates(out)
}

// Give IPv6 and alternate ports a turn before the deadline, even when IPv4
// contributes thousands of candidates. Preserve priority within each lane.
fn fair_candidates(candidates: Vec<(IpAddr, u16)>) -> Vec<(IpAddr, u16)> {
    type Lane = ((bool, u16), std::collections::VecDeque<(IpAddr, u16)>);
    let mut lanes: Vec<Lane> = Vec::new();
    for candidate in candidates {
        let key = (candidate.0.is_ipv6(), candidate.1);
        if let Some((_, lane)) = lanes.iter_mut().find(|(k, _)| *k == key) {
            lane.push_back(candidate);
        } else {
            lanes.push((key, std::collections::VecDeque::from([candidate])));
        }
    }
    let mut ordered = Vec::new();
    loop {
        let before = ordered.len();
        for (_, lane) in &mut lanes {
            if let Some(candidate) = lane.pop_front() {
                ordered.push(candidate);
            }
        }
        if before == ordered.len() {
            return ordered;
        }
    }
}

fn discovery_candidates(
    preferred: &[SocketAddr],
    pool: Vec<(IpAddr, u16)>,
    ip: IpScan,
) -> Vec<(IpAddr, u16)> {
    let mut seen = HashSet::new();
    preferred
        .iter()
        .filter(|peer| {
            if peer.is_ipv4() {
                ip.want_v4()
            } else {
                ip.want_v6()
            }
        })
        .map(|peer| (peer.ip(), peer.port()))
        .chain(pool)
        .filter(|candidate| seen.insert(*candidate))
        .collect()
}

#[cfg(test)]
mod discovery_fairness_tests {
    use super::*;

    #[test]
    fn assigned_candidates_are_first_deduplicated_and_respect_the_ip_family() {
        let v4: SocketAddr = "162.159.192.2:443".parse().unwrap();
        let v6: SocketAddr = "[2606:4700:d0::1]:443".parse().unwrap();
        let pool = vec![
            (v4.ip(), v4.port()),
            ("162.159.197.9".parse().unwrap(), 443),
        ];
        let result = discovery_candidates(&[v6, v4, v4], pool, IpScan::V4);
        assert_eq!(result.len(), 2);
        assert_eq!(result[0], (v4.ip(), v4.port()));
        assert!(result.iter().all(|(ip, _)| ip.is_ipv4()));
    }

    #[test]
    fn ipv6_and_alternate_ports_are_not_starved_by_a_large_ipv4_pool() {
        let mut input: Vec<_> = (1..=200)
            .map(|last| (IpAddr::V4(Ipv4Addr::new(162, 159, 197, last)), 443))
            .collect();
        let v6: IpAddr = "2606:4700:d0::1".parse().unwrap();
        let alternate = (input[0].0, 8443);
        input.push((v6, 443));
        input.push(alternate);
        let output = fair_candidates(input.clone());
        assert!(output[..3].contains(&(v6, 443)));
        assert!(output[..3].contains(&alternate));
        assert_eq!(output.len(), input.len());
        let input_set: HashSet<_> = input.into_iter().collect();
        assert_eq!(output.iter().copied().collect::<HashSet<_>>(), input_set);
        // Dedicated seed priority within a lane survives interleaving.
        let primary: Vec<_> = output
            .into_iter()
            .filter(|(ip, port)| ip.is_ipv4() && *port == 443)
            .collect();
        assert_eq!(primary[0].0, IpAddr::V4(Ipv4Addr::new(162, 159, 197, 1)));
        assert_eq!(
            primary[199].0,
            IpAddr::V4(Ipv4Addr::new(162, 159, 197, 200))
        );
    }
}

fn parse_cidr_v4(cidr: &str) -> Option<(u32, u8)> {
    let (ip, prefix) = cidr.split_once('/')?;
    Some((
        u32::from(ip.parse::<Ipv4Addr>().ok()?),
        prefix.parse().ok()?,
    ))
}

fn enumerate_cidr_v4(cidr: &str) -> Vec<Ipv4Addr> {
    let (base, prefix) = match parse_cidr_v4(cidr) {
        Some(v) => v,
        None => return Vec::new(),
    };
    let host_bits = 32u32.saturating_sub(prefix as u32);
    if host_bits == 0 {
        return vec![Ipv4Addr::from(base)];
    }
    if host_bits > 12 {
        return Vec::new();
    }
    let size = 1u32 << host_bits;
    (1..size.saturating_sub(1))
        .map(|off| Ipv4Addr::from(base + off))
        .collect()
}

fn sample_cidr_v4(cidr: &str, n: usize) -> Vec<Ipv4Addr> {
    let (base, prefix) = match parse_cidr_v4(cidr) {
        Some(v) => v,
        None => return Vec::new(),
    };
    let host_bits = 32u32.saturating_sub(prefix as u32);
    let size = if host_bits >= 32 {
        u32::MAX
    } else {
        1u32 << host_bits
    };
    if size <= 2 {
        return vec![Ipv4Addr::from(base)];
    }

    let usable = size - 2;
    let want = (n as u32).min(usable);
    let mut rng = rand::rng();
    let mut chosen: HashSet<u32> = HashSet::with_capacity(want as usize);
    let mut out = Vec::with_capacity(want as usize);

    while (out.len() as u32) < want {
        let off = 1 + rng.random_range(0..usable);
        if chosen.insert(off) {
            out.push(Ipv4Addr::from(base + off));
        }
    }

    out
}

fn parse_cidr_v6(cidr: &str) -> Option<(u128, u8)> {
    let (ip, prefix) = cidr.split_once('/')?;
    Some((
        u128::from(ip.parse::<Ipv6Addr>().ok()?),
        prefix.parse().ok()?,
    ))
}

fn sample_cidr_v6(cidr: &str, n: usize, v4_cidrs: &[&str]) -> Vec<Ipv6Addr> {
    let (base, prefix) = match parse_cidr_v6(cidr) {
        Some(v) => v,
        None => return Vec::new(),
    };
    if 128u32.saturating_sub(prefix as u32) == 0 {
        return vec![Ipv6Addr::from(base)];
    }

    let v4: Vec<(u32, u8)> = v4_cidrs.iter().filter_map(|c| parse_cidr_v4(c)).collect();
    let mut rng = rand::rng();
    let mut out = Vec::with_capacity(n);
    for _ in 0..n {
        let embedded = if v4.is_empty() {
            rng.random::<u32>() as u128
        } else {
            let (b, p) = v4[rng.random_range(0..v4.len())];
            let host_bits = 32u32.saturating_sub(p as u32);
            let host = if host_bits == 0 {
                0
            } else {
                rng.random::<u32>() & ((1u32 << host_bits) - 1)
            };
            (b | host) as u128
        };
        out.push(Ipv6Addr::from(base | embedded));
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_gateway_left_out_is_probed_on_no_port() {
        let left: IpAddr = "162.159.198.1".parse().unwrap();
        let other: IpAddr = "162.159.198.2".parse().unwrap();
        let avoid: HashSet<IpAddr> = [left].into_iter().collect();
        assert_eq!(
            leave_out(vec![(left, 443), (other, 443), (left, 4500)], &avoid),
            vec![(other, 443)]
        );
        assert_eq!(
            leave_out(vec![(left, 443)], &HashSet::new()),
            vec![(left, 443)]
        );
    }

    #[test]
    fn the_documented_zero_trust_masque_ingress_range_is_scanned() {
        assert!(MASQUE_CIDRS_V4.contains(&"162.159.197.0/24"));
        assert!(MASQUE_CIDRS_V6.contains(&"2606:4700:102::/48"));
    }

    #[test]
    fn with_a_team_the_masque_gateways_of_warp_and_warp_plus_are_scanned() {
        // WARP's MASQUE over IPv4 and IPv6, and WARP+'s, as the WARP client's own log of 2024
        // and Xray's notes have them: 162.159.198.0/24 with 2606:4700:103::1, and
        // 162.159.199.0/24, beside Zero Trust's 162.159.197.0/24.
        assert!(MASQUE_CIDRS_V4.contains(&"162.159.198.0/24"));
        assert!(MASQUE_CIDRS_V4.contains(&"162.159.199.0/24"));
        assert!(MASQUE_CIDRS_V6.contains(&"2606:4700:103::/48"));
        let st = ScanMode::Thorough.strategy();
        let v4 = build_candidates(&st, MASQUE_PORTS, IpScan::V4, true);
        for host in 1..=254u8 {
            let ip = IpAddr::V4(Ipv4Addr::new(162, 159, 199, host));
            assert!(v4.contains(&(ip, 443)), "{ip}");
        }
        // The gateways of WARP+ and Zero Trust on every port, as WARP's.
        for gateway in ["162.159.199.2", "162.159.197.2", "162.159.198.2"] {
            let ip: IpAddr = gateway.parse().expect("an address");
            for &port in MASQUE_PORTS {
                assert!(v4.contains(&(ip, port)), "{ip}:{port}");
            }
        }
        // 2606:4700:103::1 first among the IPv6 ones, and on every port; the others of its range
        // carry an IPv4 address of the ranges in their last 32 bits, which ::1 cannot be.
        let v6 = build_candidates(&st, MASQUE_PORTS, IpScan::V6, true);
        let gateway: IpAddr = "2606:4700:103::1".parse().expect("an address");
        assert_eq!(v6.first(), Some(&(gateway, 443)));
        for &port in MASQUE_PORTS {
            assert!(v6.contains(&(gateway, port)), "{port}");
        }
        assert!(v6.iter().any(|(ip, _)| match ip {
            IpAddr::V6(address) =>
                address.segments()[..3] == [0x2606, 0x4700, 0x103] && *ip != gateway,
            IpAddr::V4(_) => false,
        }));
    }

    #[test]
    fn without_a_team_every_known_gateway_has_every_port_before_optional_cdn_expansion() {
        let gateways: Vec<IpAddr> = MASQUE_GATEWAYS_V4
            .iter()
            .map(|gateway| gateway.parse().expect("an address"))
            .collect();
        let (first, others) = MASQUE_PORTS.split_first().expect("a port");
        let mut expected: Vec<(IpAddr, u16)> = gateways.iter().map(|ip| (*ip, *first)).collect();
        for ip in &gateways {
            for &port in others {
                expected.push((*ip, port));
            }
        }
        for mode in [
            ScanMode::Turbo,
            ScanMode::Balanced,
            ScanMode::Thorough,
            ScanMode::Verified,
            ScanMode::Ironclad,
        ] {
            let v4 = build_candidates(&mode.strategy(), MASQUE_PORTS, IpScan::V4, false);
            assert_eq!(v4.first(), expected.first(), "{}", mode.label());
            assert!(
                expected.iter().all(|peer| v4.contains(peer)),
                "{}",
                mode.label()
            );
            assert!(v4
                .iter()
                .filter(|(ip, _)| !gateways.contains(ip))
                .all(|(_, port)| *port == 443));
            assert_eq!(v4.iter().collect::<HashSet<_>>().len(), v4.len());
        }
    }

    #[test]
    fn the_masque_gateways_are_distinct_addresses_of_162_159_198_and_199() {
        let mut seen = HashSet::new();
        for gateway in MASQUE_GATEWAYS_V4 {
            let address: Ipv4Addr = gateway.parse().expect("an address");
            assert!(
                matches!(address.octets(), [162, 159, 198 | 199, _]),
                "{gateway}"
            );
            assert!(seen.insert(address), "{gateway} is listed twice");
        }
    }

    #[test]
    fn without_a_team_the_ipv6_scan_tries_warps_range_alone() {
        // 2606:4700:103::1 first and on every port, then addresses of 2606:4700:103::/48 alone,
        // on the first port.
        let (first, _) = MASQUE_PORTS.split_first().expect("a port");
        let gateway: IpAddr = "2606:4700:103::1".parse().expect("an address");
        let st = ScanMode::Thorough.strategy();
        let v6 = build_candidates(&st, MASQUE_PORTS, IpScan::V6, false);
        assert_eq!(v6.first(), Some(&(gateway, *first)));
        for &port in MASQUE_PORTS {
            assert!(v6.contains(&(gateway, port)), "{port}");
        }
        assert!(
            v6.len() > MASQUE_PORTS.len(),
            "the range is sampled as well"
        );
        for (ip, port) in &v6 {
            assert!(
                matches!(ip, IpAddr::V6(a) if a.segments()[..3] == [0x2606, 0x4700, 0x103]),
                "{ip}"
            );
            assert!(*ip == gateway || port == first, "{ip}:{port}");
        }
    }

    #[test]
    fn the_dns_over_https_ranges_are_swept_last_because_they_never_serve_masque() {
        let tail = &MASQUE_CIDRS_V4[MASQUE_CIDRS_V4.len() - MASQUE_DOH_CIDRS_V4.len()..];
        for entry in MASQUE_DOH_CIDRS_V4 {
            assert!(tail.contains(entry), "{entry} should be at the end");
        }
    }

    #[test]
    fn the_documented_default_masque_port_leads_the_sweep() {
        assert_eq!(MASQUE_PORTS.first(), Some(&443));
    }

    #[test]
    fn the_documented_masque_fallback_ports_keep_their_documented_order() {
        assert_eq!(MASQUE_PORTS, &[443, 500, 1701, 4500, 4443, 8443, 8095]);
    }

    #[test]
    fn without_a_team_the_range_order_is_left_alone() {
        std::env::remove_var("AETHER_TEAM");
        assert_eq!(
            prioritize(MASQUE_CIDRS_V4, MASQUE_ZT_CIDRS_V4),
            MASQUE_CIDRS_V4.to_vec()
        );
    }

    #[test]
    fn prioritize_moves_the_wanted_entries_to_the_front_without_losing_any() {
        let all = ["a", "b", "c", "d"];
        let out = {
            let mut out: Vec<&'static str> = vec!["c"];
            for entry in all {
                if !out.contains(&entry) {
                    out.push(entry);
                }
            }
            out
        };
        assert_eq!(out, vec!["c", "a", "b", "d"]);
        assert_eq!(out.len(), all.len());
    }

    /// Everything answering the same way is not a statement about the network.
    ///
    /// The whole incident in miniature: a certificate we do not accept, or a
    /// request the gateway refuses, fails identically at every address. Spending
    /// the budget to confirm that across three thousand of them is how a
    /// fixable fault comes to look like censorship.
    #[test]
    fn one_answer_repeated_by_everything_stops_the_scan() {
        let mut tally = FailureTally::default();
        for _ in 0..COMMON_MODE_SAMPLE {
            tally.record(ProbeFailure::PinMismatch);
        }
        assert_eq!(
            Some((ProbeFailure::PinMismatch, COMMON_MODE_SAMPLE)),
            tally.common_mode(),
        );
    }

    /// A hostile network is not a common-mode fault, and must not stop the scan.
    ///
    /// Silence and refusal are exactly what a blocked address looks like, and
    /// the next address may well answer. Only a fault in what we send counts.
    #[test]
    fn a_network_that_drops_everything_does_not_stop_the_scan() {
        let mut tally = FailureTally::default();
        for _ in 0..COMMON_MODE_SAMPLE * 4 {
            tally.record(ProbeFailure::NoAnswer);
        }
        assert_eq!(None, tally.common_mode());

        let mut refused = FailureTally::default();
        for _ in 0..COMMON_MODE_SAMPLE * 4 {
            refused.record(ProbeFailure::Refused);
        }
        assert_eq!(None, refused.common_mode());
    }

    /// Too few probes is not evidence about the rest of them.
    #[test]
    fn a_handful_of_probes_is_not_enough_to_conclude_anything() {
        let mut tally = FailureTally::default();
        for _ in 0..COMMON_MODE_SAMPLE - 1 {
            tally.record(ProbeFailure::ConnectIpRefused);
        }
        assert_eq!(None, tally.common_mode());
        tally.record(ProbeFailure::ConnectIpRefused);
        assert!(tally.common_mode().is_some());
    }

    /// A mixture is a network, not a fault in what we send.
    #[test]
    fn a_mixture_of_answers_is_not_a_common_mode_fault() {
        let mut tally = FailureTally::default();
        for index in 0..COMMON_MODE_SAMPLE * 2 {
            tally.record(if index % 2 == 0 {
                ProbeFailure::PinMismatch
            } else {
                ProbeFailure::NoAnswer
            });
        }
        assert_eq!(None, tally.common_mode());
    }

    /// The failures the incident actually produced, named from the real errors.
    #[test]
    fn the_answers_this_incident_produced_are_classified() {
        let cases = [
            (
                AetherError::Tls(
                    "h2 tls handshake: TLS handshake failed: cert verification failed -                      application verification failure"
                        .into(),
                ),
                ProbeFailure::PinMismatch,
            ),
            (
                AetherError::Masque("h2 connect-ip status 400; cf-ray=-".into()),
                ProbeFailure::ConnectIpRefused,
            ),
            (
                AetherError::Other("closed before data-plane confirmation".into()),
                ProbeFailure::NoDataPlane,
            ),
            (
                AetherError::Tls("TLS handshake failed: handshake timed out".into()),
                ProbeFailure::TlsRejected,
            ),
        ];
        for (error, expected) in cases {
            assert_eq!(expected, ProbeFailure::of(&error), "{error}");
        }
    }

    #[test]
    fn a_pre_tunnel_tls_close_is_never_called_a_working_tunnel_without_traffic() {
        let cases = [
            ("closed before the tunnel carried anything; the gateway closed it: code=0x178 (tls: no application protocol)", ProbeFailure::AlpnRejected),
            ("closed before the tunnel carried anything; this end closed it: code=0x12e (tls: certificate unknown)", ProbeFailure::PinMismatch),
            ("closed before the tunnel carried anything; the gateway closed it: code=0x130 (tls: unknown certificate authority)", ProbeFailure::ClientCertificateRejected),
            ("closed before the tunnel carried anything; neither end said why", ProbeFailure::HandshakeClosed),
            ("CONNECT-IP accepted but closed before data-plane confirmation", ProbeFailure::NoDataPlane),
            ("h2 stream closed before data", ProbeFailure::NoDataPlane),
            ("h2 ALPN: gateway selected no protocol instead of h2", ProbeFailure::AlpnRejected),
            ("verify timeout", ProbeFailure::NoAnswer),
        ];
        for (message, expected) in cases {
            assert_eq!(
                ProbeFailure::of(&AetherError::Masque(message.into())),
                expected,
                "{message}"
            );
        }
        assert_eq!(
            ProbeFailure::of(&AetherError::Ech("the handshake went without ECH".into())),
            ProbeFailure::EchRejected
        );
    }

    #[test]
    fn scan_details_preserve_a_bounded_example_of_the_actual_failure() {
        let mut tally = FailureTally::default();
        let message = "closed before the tunnel carried anything; the gateway closed it: code=0x178 (tls: no application protocol)";
        tally.record_error(ProbeError::from(AetherError::Masque(message.into())));
        tally.record_error(ProbeError::from(AetherError::Masque(message.into())));
        let details = tally.details();
        assert!(details.contains("tunnel protocol not supported=2"));
        assert!(details.contains("code=0x178"));
        assert_eq!(details.matches("example:").count(), 1);
        tally.record_error(ProbeError::from(AetherError::Other("é\n".repeat(2000))));
        assert!(tally
            .examples
            .values()
            .all(|example| example.chars().count() <= 320));
        assert!(!tally.details().contains('\n'));
    }

    /// The summary names every kind it saw, so a report can be read.
    #[test]
    fn the_summary_says_what_was_seen() {
        let mut tally = FailureTally::default();
        tally.record(ProbeFailure::NoAnswer);
        tally.record(ProbeFailure::NoAnswer);
        tally.record(ProbeFailure::PinMismatch);
        let summary = tally.summary();
        assert!(summary.contains("no answer=2"), "{summary}");
        assert!(summary.contains("certificate not ours=1"), "{summary}");
    }

    #[test]
    fn the_documented_masque_fallback_ports_are_all_covered() {
        for port in [443u16, 500, 1701, 4443, 4500, 8443, 8095] {
            assert!(
                MASQUE_PORTS.contains(&port),
                "documented fallback port {port} should be scanned"
            );
        }
    }

    async fn quic_answers(peer: SocketAddr, timeout: Duration) -> Option<Duration> {
        let bind = if peer.is_ipv4() {
            "0.0.0.0:0"
        } else {
            "[::]:0"
        };
        let sock = tokio::net::UdpSocket::bind(bind).await.ok()?;
        sock.connect(peer).await.ok()?;
        let local = sock.local_addr().ok()?;

        let mut config = quiche::Config::new(quiche::PROTOCOL_VERSION).ok()?;
        config.set_application_protos(&[b"h3"]).ok()?;
        config.verify_peer(false);
        config.set_max_idle_timeout(timeout.as_millis() as u64);
        config.set_initial_max_data(1_000_000);
        config.set_initial_max_stream_data_bidi_local(100_000);
        config.set_initial_max_streams_bidi(4);

        let mut scid = [0u8; 16];
        rand::rng().fill(&mut scid[..]);
        let scid = quiche::ConnectionId::from_ref(&scid);

        // The name the core's MASQUE handshakes send.
        let sni = quic::default_sni();
        let mut conn = quiche::connect(Some(sni), &scid, local, peer, &mut config).ok()?;

        let mut out = [0u8; 1350];
        let (written, _) = conn.send(&mut out).ok()?;

        let started = Instant::now();
        sock.send(&out[..written]).await.ok()?;

        let mut buf = [0u8; 1500];
        match tokio::time::timeout(timeout, sock.recv(&mut buf)).await {
            Ok(Ok(read)) if read > 0 => Some(started.elapsed()),
            _ => None,
        }
    }

    async fn tcp_answers(peer: SocketAddr, timeout: Duration) -> Option<Duration> {
        let started = Instant::now();
        match tokio::time::timeout(timeout, tokio::net::TcpStream::connect(peer)).await {
            Ok(Ok(_)) => Some(started.elapsed()),
            _ => None,
        }
    }

    async fn first_answer(
        targets: &[SocketAddr],
        timeout: Duration,
        attempts: u32,
        udp: bool,
    ) -> Option<(SocketAddr, Duration)> {
        for _ in 0..attempts {
            let probes = targets.iter().copied().map(|peer| async move {
                let rtt = match udp {
                    true => quic_answers(peer, timeout).await,
                    false => tcp_answers(peer, timeout).await,
                };
                (peer, rtt)
            });

            let results: Vec<(SocketAddr, Option<Duration>)> = futures::stream::iter(probes)
                .buffer_unordered(targets.len().max(1))
                .collect()
                .await;

            if let Some((peer, Some(rtt))) = results.into_iter().find(|(_, rtt)| rtt.is_some()) {
                return Some((peer, rtt));
            }
        }
        None
    }

    fn hosts_of(cidr: &str, tails: &[u8]) -> Vec<Ipv4Addr> {
        let base: Ipv4Addr = cidr.split('/').next().unwrap().parse().expect("cidr base");
        let octets = base.octets();
        tails
            .iter()
            .map(|tail| Ipv4Addr::new(octets[0], octets[1], octets[2], *tail))
            .collect()
    }

    #[tokio::test(flavor = "multi_thread")]
    #[ignore = "probes the live cloudflare edge from this network to see which masque ranges answer"]
    async fn report_which_masque_ranges_answer_on_this_network() {
        const TAILS: &[u8] = &[1, 2, 3];

        let timeout = Duration::from_millis(
            std::env::var("AETHER_PROBE_TIMEOUT_MS")
                .ok()
                .and_then(|raw| raw.trim().parse::<u64>().ok())
                .filter(|ms| *ms > 0)
                .unwrap_or(10_000),
        );

        let attempts = std::env::var("AETHER_PROBE_ATTEMPTS")
            .ok()
            .and_then(|raw| raw.trim().parse::<u32>().ok())
            .filter(|n| *n > 0)
            .unwrap_or(2);

        let ports: Vec<u16> = match std::env::var("AETHER_PROBE_PORTS") {
            Ok(raw) => raw
                .split(',')
                .filter_map(|p| p.trim().parse::<u16>().ok())
                .collect(),
            Err(_) => vec![443],
        };
        let ports = if ports.is_empty() { vec![443] } else { ports };

        println!();
        println!("probing the masque ranges from this network");
        println!("  udp timeout {timeout:?}, {attempts} attempt(s), hosts {TAILS:?}");
        println!("  udp ports {ports:?}");
        println!("  override: AETHER_PROBE_TIMEOUT_MS, AETHER_PROBE_ATTEMPTS, AETHER_PROBE_PORTS");
        println!();

        let control: Vec<SocketAddr> = vec![
            "1.1.1.1:443".parse().unwrap(),
            "8.8.8.8:443".parse().unwrap(),
        ];

        let control_quic = first_answer(&control, timeout, attempts, true).await;
        let control_tcp = first_answer(&control, timeout, attempts, false).await;

        println!("control targets (public resolvers, not cloudflare warp edges)");
        match control_quic {
            Some((peer, rtt)) => println!("  quic/udp 443 works: {peer} in {}ms", rtt.as_millis()),
            None => println!("  quic/udp 443 got no answer at all"),
        }
        match control_tcp {
            Some((peer, rtt)) => println!("  tcp 443 works:      {peer} in {}ms", rtt.as_millis()),
            None => println!("  tcp 443 got no answer at all"),
        }
        println!();

        let mut udp_ok = Vec::new();
        let mut tcp_only = Vec::new();
        let mut silent = Vec::new();

        for cidr in MASQUE_CIDRS_V4 {
            let note = if MASQUE_DOCUMENTED_CIDRS_V4.contains(cidr) {
                " [documented]"
            } else if MASQUE_DOH_CIDRS_V4.contains(cidr) {
                " [dns-over-https]"
            } else {
                ""
            };

            let hosts = hosts_of(cidr, TAILS);

            let mut udp_hit: Option<(SocketAddr, Duration)> = None;
            for port in &ports {
                let targets: Vec<SocketAddr> = hosts
                    .iter()
                    .map(|ip| SocketAddr::new(IpAddr::V4(*ip), *port))
                    .collect();
                udp_hit = first_answer(&targets, timeout, attempts, true).await;
                if udp_hit.is_some() {
                    break;
                }
            }

            let tcp_targets: Vec<SocketAddr> = hosts
                .iter()
                .map(|ip| SocketAddr::new(IpAddr::V4(*ip), 443))
                .collect();
            let tcp_hit = first_answer(&tcp_targets, timeout, attempts, false).await;

            match (udp_hit, tcp_hit) {
                (Some((peer, rtt)), _) => {
                    println!("  UDP OK    {cidr}{note}  {peer} in {}ms", rtt.as_millis());
                    udp_ok.push(*cidr);
                }
                (None, Some((peer, rtt))) => {
                    println!(
                        "  TCP ONLY  {cidr}{note}  {peer} in {}ms, udp stayed silent",
                        rtt.as_millis()
                    );
                    tcp_only.push(*cidr);
                }
                (None, None) => {
                    println!("  SILENT    {cidr}{note}");
                    silent.push(*cidr);
                }
            }
        }

        println!();
        println!("masque over quic works on ({}):", udp_ok.len());
        for cidr in &udp_ok {
            println!("  {cidr}");
        }
        println!();
        println!("reachable over tcp only ({}):", tcp_only.len());
        for cidr in &tcp_only {
            println!("  {cidr}");
        }
        println!();
        println!("no answer on either ({}):", silent.len());
        for cidr in &silent {
            println!("  {cidr}");
        }

        println!();
        println!("verdict");
        if !udp_ok.is_empty() {
            println!("  put these ranges first in MASQUE_CIDRS_V4: {udp_ok:?}");
        } else if control_quic.is_none() {
            println!("  this network answers no quic at all, not even a public resolver,");
            println!("  so udp 443 is blocked here rather than these ranges being blocked.");
            println!("  reordering MASQUE_CIDRS_V4 cannot help; masque needs its http/2");
            println!("  fallback over tcp 443 (--masque-http2) on this network.");
        } else {
            println!("  quic works to other hosts but every warp range stayed silent,");
            println!("  so these ranges really are filtered here.");
            if !tcp_only.is_empty() {
                println!("  the tcp-only ranges above can still carry masque over http/2.");
            }
        }
        println!();
    }

    #[test]
    fn every_masque_prefix_and_seed_parses() {
        for entry in MASQUE_CIDRS_V4 {
            let (addr, bits) = entry.split_once('/').expect("cidr");
            assert!(addr.parse::<Ipv4Addr>().is_ok(), "{entry}");
            assert!(bits.parse::<u8>().is_ok(), "{entry}");
        }
        for entry in MASQUE_CIDRS_V6 {
            let (addr, bits) = entry.split_once('/').expect("cidr");
            assert!(addr.parse::<Ipv6Addr>().is_ok(), "{entry}");
            assert!(bits.parse::<u8>().is_ok(), "{entry}");
        }
        for seed in MASQUE_SEEDS {
            assert!(seed.parse::<Ipv4Addr>().is_ok(), "{seed}");
        }
        for seed in MASQUE_SEEDS_V6 {
            assert!(seed.parse::<Ipv6Addr>().is_ok(), "{seed}");
        }
    }

    #[tokio::test]
    async fn the_ironclad_check_offers_the_ech_key_of_its_scan() {
        let _setting = crate::upstream::hold_setting().await;
        // The check builds its TLS with the fingerprint the options give.
        let _options = crate::tls::hold_options().await;
        let identity = crate::account::handshake_identity();
        let peer = tokio::net::UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let address = peer.local_addr().unwrap();
        let probe = |ech: Option<Vec<u8>>| MasqueProbe {
            sni: crate::consts::CONNECT_SNI.to_string(),
            authority: quic::default_authority().to_string(),
            path: quic::default_path().to_string(),
            cert_pem: Arc::from(identity.cert_pem.clone()),
            key_pem: Arc::from(identity.key_pem.clone()),
            ech_config_list: ech.map(Arc::from),
            noize: crate::noize::from_profile("off"),
            ports: vec![address.port()],
            ip: IpScan::V4,
            local_ipv4: Ipv4Addr::new(172, 16, 0, 2),
        };
        let timeout = Duration::from_secs(5);

        // Given a key BoringSSL offers nothing from (its one config is of another version), the
        // check sends no ClientHello; the version bait may go out before it.
        let unusable = probe(Some(vec![0, 6, 0xfe, 0x0c, 0, 2, 0, 0]));
        let checked = verify_one(&unusable, address.ip(), address.port(), timeout, true).await;
        assert!(checked.is_err());
        assert!(!quic::hears_a_client_hello(&peer, Duration::from_millis(300)).await);

        // Without a key, the same check does send one, with the server name in the clear.
        let plain = probe(None);
        let check = tokio::spawn(async move {
            verify_one(&plain, address.ip(), address.port(), timeout, true).await
        });
        assert!(quic::hears_a_client_hello(&peer, Duration::from_secs(4)).await);
        check.abort();
    }
}
