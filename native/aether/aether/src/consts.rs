pub const API_URL: &str = "https://api.cloudflareclient.com";
pub const API_HOST: &str = "api.devices.cloudflare.com";
pub const API_FALLBACK_HOST: &str = "api.cloudflareclient.com";
pub const API_FRONT_ADDRESSES: [&str; 2] = ["104.24.4.6", "104.18.0.6"];
pub const DEFAULT_MASQUE_SNI: &str = "www.cloudflare.com";
pub const API_VERSION: &str = "v0a4471";

// Keep the visible TLS name separate from the CONNECT authority. The MASQUE
// hostname is filtered on networks where ordinary Cloudflare HTTPS still works.
pub const CONNECT_SNI: &str = "www.cloudflare.com";
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
/// The scheduled health check reports upcoming expiry and newly observed keys.
/// The release workflow uses recorded validity and can verify a renewal live.
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
        label: "Cloudflare MASQUE (Aether 2.4 pin)",
        spki: b"\x2a\x38\xb6\x9b\x25\x66\x96\xc0\x9a\x11\xfb\xf1\x47\x4f\xe3\x90\x23\x63\x63\x3a\xb0\x0d\xd9\xc2\x97\xb2\xde\x64\x9b\x72\xd7\x2e",
        expires: 0,
    },
    MasquePin {
        // CN=masque.cloudflareclient.com, issued by Cloudflare's own
        // "2024-02-27 Self-Signed Root". This is what a real gateway presents,
        // measured against a live edge rather than taken on trust.
        label: "masque.cloudflareclient.com",
        spki: b"\xeb\x59\x1b\x36\xab\x26\xba\x61\x7e\x98\x37\x19\x18\xc1\x0b\xcd\xea\xe3\x74\x2d\xb6\xe7\x65\x43\xf9\x4b\xe5\x24\xdc\xe1\xd5\x55",
        // 2027-02-26T10:45:06Z
        expires: 1_803_638_706,
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

#[cfg(test)]
mod tests {
    use super::*;

    fn now_unix() -> u64 {
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_secs())
            .unwrap_or(0)
    }

    fn has_dated_unexpired_pin(now: u64) -> bool {
        MASQUE_PIN_SET.iter().any(|pin| pin.expires > now)
    }

    #[test]
    fn dated_pin_expires_at_the_recorded_time() {
        let last_expiry = MASQUE_PIN_SET.iter().map(|pin| pin.expires).max().unwrap();
        assert!(last_expiry > 0);
        assert!(has_dated_unexpired_pin(last_expiry - 1));
        assert!(!has_dated_unexpired_pin(last_expiry));
    }

    #[test]
    #[ignore = "run by the signed release workflow"]
    fn release_requires_unexpired_masque_pin() {
        assert!(
            has_dated_unexpired_pin(now_unix()),
            "the release requires an authenticated MASQUE pin with a current certificate"
        );
    }

    #[test]
    #[ignore = "requires live gateway fingerprints from a workflow"]
    fn live_masque_certificates_match_known_pins() {
        let value = std::env::var("MASQUE_OBSERVED_PINS").expect("missing gateway fingerprints");
        let mut observed = value.split_whitespace().peekable();
        assert!(
            observed.peek().is_some(),
            "no gateway fingerprints were collected"
        );
        let known: Vec<String> = MASQUE_PIN_SET
            .iter()
            .map(|pin| pin.spki.iter().map(|byte| format!("{byte:02x}")).collect())
            .collect();
        for fingerprint in observed {
            assert!(
                known.iter().any(|pin| pin == fingerprint),
                "MASQUE gateway presented an unpinned SPKI {}",
                fingerprint
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
