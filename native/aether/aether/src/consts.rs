pub const API_URL: &str = "https://api.cloudflareclient.com";
pub const API_VERSION: &str = "v0a4471";

pub const CONNECT_SNI: &str = "consumer-masque.cloudflareclient.com";
pub const L4_CONNECT_SNI: &str = "consumer-masque-proxy.cloudflareclient.com";
pub const CONNECT_URI: &str = "https://cloudflareaccess.com";

pub const ECH_PUBLIC_NAME: &str = "cloudflare-ech.com";

pub const DEFAULT_MODEL: &str = "PC";
pub const DEFAULT_LOCALE: &str = "en_US";

pub const KEY_TYPE_MASQUE: &str = "secp256r1";
pub const TUN_TYPE_MASQUE: &str = "masque";

pub const UA_REGISTER: &str = "WARP for Android";
pub const CF_CLIENT_VERSION: &str = "a-6.35-4471";

pub const ALPN_H3: &[u8] = b"h3";

pub const CF_CONNECT_PROTOCOL: &str = "cf-connect-ip";

pub const H3_DATAGRAM_00: u64 = 0x276;

pub const CONNECT_IP_CONTEXT_ID: u64 = 0;

pub const CDN_ANYCAST_POOL: &[&str] = &[
    "104.16.0.0",
    "104.17.0.0",
    "104.18.0.0",
    "104.19.0.0",
    "104.20.0.0",
    "104.21.0.0",
    "104.22.0.0",
    "104.24.0.0",
    "104.25.0.0",
    "104.26.0.0",
    "104.27.0.0",
    "104.28.0.0",
    "172.64.0.0",
    "172.65.0.0",
    "172.66.0.0",
    "172.67.0.0",
    "188.114.96.0",
    "188.114.97.0",
    "188.114.98.0",
    "188.114.99.0",
];

pub const QUIC_PORT: u16 = 443;

/// One certificate this build will accept from a MASQUE edge.
///
/// A pin is a promise that only one key is trusted, and the price of that
/// promise is that nothing can change it without a new binary. Cloudflare sends
/// the leaf alone — no intermediate, no root, and no AIA to fetch one from — so
/// there is no stabler anchor available to pin instead. The leaf is all there
/// is.
///
/// Which means a rotation takes MASQUE from every user at once, on every
/// network, and the remedy is shipping an APK to people who by then cannot
/// connect to download it. Nothing here prevents that. What this does is stop us
/// being told about it by users: `expires` is checked by a test, so the build
/// goes red months before the certificate does.
pub struct MasquePin {
    /// What the certificate is, for the message when it stops matching.
    pub label: &'static str,
    /// SHA-256 of the SubjectPublicKeyInfo. Raw 32 bytes.
    pub spki: &'static [u8],
    /// `notAfter`, as a unix time. Zero where it is not known.
    ///
    /// Not the moment it breaks — Cloudflare may replace a certificate at any
    /// point before this — but the latest it can still be valid, and therefore
    /// the deadline this build has.
    pub expires: u64,
}

/// The certificates a MASQUE edge may present.
///
/// Verification accepts any of them: the edges serve different certificates by
/// SNI and some are self-signed, which is why the TLS library is told to verify
/// nothing and this list does the work instead.
pub const MASQUE_PIN_SET: &[MasquePin] = &[
    MasquePin {
        // CN=masque.cloudflareclient.com, issued by Cloudflare's own
        // "2024-02-27 Self-Signed Root". This is what a real gateway presents,
        // measured against a live edge rather than taken on trust.
        label: "masque.cloudflareclient.com",
        spki: b"\xeb\x59\x1b\x36\xab\x26\xba\x61\x7e\x98\x37\x19\x18\xc1\x0b\xcd\xea\xe3\x74\x2d\xb6\xe7\x65\x43\xf9\x4b\xe5\x24\xdc\xe1\xd5\x55",
        // 2027-02-26T10:45:06Z
        expires: 1_803_991_506,
    },
    MasquePin {
        // Carried from an earlier build, recorded then as cloudflareaccess.com
        // signed by Google Trust Services. No live edge has been seen
        // presenting it since. Kept rather than removed: an unused pin costs a
        // comparison, and a wrongly removed one costs every user who reaches
        // whatever still serves it.
        label: "cloudflareaccess.com (not seen on a live edge)",
        spki: b"\x3f\xbb\x1d\x74\x52\xd3\x2b\x38\x81\xeb\x4b\x5d\x48\x42\x14\x45\xb6\xb9\xd8\xf5\x22\x59\x59\xf0\x33\x53\x2d\x50\x26\x37\xb0\x40",
        expires: 0,
    },
];

/// The pins as the TLS layer wants them.
pub fn masque_pins() -> Vec<&'static [u8]> {
    MASQUE_PIN_SET.iter().map(|pin| pin.spki).collect()
}

/// How long before a pin expires the build should start failing.
///
/// Long enough to notice, decide, build, release, and have people install it —
/// on connections that are slow and intermittent, which is most of them. A
/// warning that arrives a fortnight ahead is not a warning.
pub const PIN_RENEWAL_NOTICE_DAYS: u64 = 100;

#[cfg(test)]
mod tests {
    use super::*;

    fn now_unix() -> u64 {
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_secs())
            .unwrap_or(0)
    }

    /// The build fails before the certificate does.
    ///
    /// This is a deadline, and it is meant to be. Cloudflare sends the leaf
    /// alone, with no root or intermediate to pin instead, so when it is
    /// replaced every MASQUE connection stops at once — on every network, for
    /// every user, with no setting that helps. The remedy is a new binary, and
    /// it has to reach people whose connection has just stopped working.
    ///
    /// So this turns that into a red build, months ahead, while shipping is
    /// still easy. When it fails: connect to a live edge, take the new SPKI
    /// hash and `notAfter`, update the pin, and release.
    ///
    ///     echo | openssl s_client -connect <edge>:443 \
    ///       -servername consumer-masque.cloudflareclient.com 2>/dev/null \
    ///       | openssl x509 -pubkey -noout \
    ///       | openssl pkey -pubin -outform der \
    ///       | openssl dgst -sha256
    #[test]
    fn a_pin_that_is_about_to_expire_fails_the_build() {
        let notice = PIN_RENEWAL_NOTICE_DAYS * 24 * 60 * 60;
        let now = now_unix();

        for pin in MASQUE_PIN_SET {
            if pin.expires == 0 {
                continue;
            }
            let left = pin.expires.saturating_sub(now);
            assert!(
                left > notice,
                "the pinned certificate for {} expires in {} days. When Cloudflare replaces it, \
                 MASQUE stops for every user at once and only a new release fixes it — which has \
                 to reach people whose connection has just stopped. Take the new pin and ship it \
                 now, while that is still easy.",
                pin.label,
                left / (24 * 60 * 60),
            );
        }
    }

    /// A pin has to be a SHA-256 hash, and each one has to be its own.
    #[test]
    fn every_pin_is_a_distinct_sha256() {
        let mut seen: Vec<&[u8]> = Vec::new();
        for pin in MASQUE_PIN_SET {
            assert_eq!(32, pin.spki.len(), "{} is not a SHA-256 hash", pin.label);
            assert!(
                !seen.contains(&pin.spki),
                "{} is in the set twice",
                pin.label
            );
            seen.push(pin.spki);
        }
        assert_eq!(MASQUE_PIN_SET.len(), masque_pins().len());
    }

    /// Nobody gets to ship an empty pin set.
    ///
    /// Verification falls back to accepting anything when the list is empty —
    /// which is right for the desktop paths that never pinned, and would be a
    /// silent removal of the only check MASQUE has if it happened here.
    #[test]
    fn the_pin_set_is_never_empty() {
        assert!(!MASQUE_PIN_SET.is_empty());
    }
}
