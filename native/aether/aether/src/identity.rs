//! One record per Cloudflare device, and slots that point at them.
//!
//! The files this replaces each held a *copy* of a device, and a copy cannot be
//! told that the original changed. MASQUE enrolment is `PATCH /reg/{id}`: it
//! overwrites the key registration filled with the Curve25519 public key, so
//! the device stops being a WireGuard device — and the file that still held its
//! WireGuard half went on offering a key Cloudflare had thrown away, which
//! every endpoint answers with silence. 1.8.1 catches that by sweeping the
//! directory; this removes the shape that allows it, by giving a device exactly
//! one place to be.

use std::collections::BTreeMap;

use serde::{Deserialize, Serialize};

use crate::account::Identity;
use crate::config;
use crate::error::{AetherError, Result};

/// The on-disk format of this file, not of the identities inside it.
///
/// Separate from the export envelope's version on purpose: one describes a file
/// that never leaves the device, the other a file that does.
pub const STORE_VERSION: u32 = 1;

/// What Cloudflare holds in a device's `key` field, as far as this device knows.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum TunnelType {
    /// The Curve25519 public key registration put there. WireGuard works.
    ///
    /// The default, because it is what `POST /reg` leaves behind — so a record
    /// that says nothing about this came from a registration and nothing since.
    #[default]
    Wireguard,
    /// A secp256r1 SPKI enrolment put there. The WireGuard half is gone.
    Masque,
}

/// The roles the engine needs filled, each by a device.
///
/// Named rather than indexed so that a file written by a build with fewer of
/// them still reads, and so that adding one is not a silent renumbering.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub enum Slot {
    Wireguard,
    WireguardInner,
    Masque,
    MasqueInner,
}

impl Slot {
    pub const ALL: [Slot; 4] = [
        Slot::Wireguard,
        Slot::WireguardInner,
        Slot::Masque,
        Slot::MasqueInner,
    ];

    /// Whether this slot presents a WireGuard key or a MASQUE certificate.
    ///
    /// The distinction the whole file exists to keep: one device cannot serve
    /// both, because enrolling for the second destroys the first.
    pub fn family(self) -> Family {
        match self {
            Slot::Wireguard | Slot::WireguardInner => Family::Wireguard,
            Slot::Masque | Slot::MasqueInner => Family::Masque,
        }
    }

    pub fn key(self) -> &'static str {
        match self {
            Slot::Wireguard => "wireguard",
            Slot::WireguardInner => "wireguard_inner",
            Slot::Masque => "masque",
            Slot::MasqueInner => "masque_inner",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Family {
    Wireguard,
    Masque,
}

/// One Cloudflare device, as this install knows it.
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct Device {
    pub access_token: String,
    pub ipv4: String,
    pub ipv6: String,
    pub wg_private_key: String,
    pub wg_peer_public_key: String,
    #[serde(default)]
    pub client_id: String,
    #[serde(default)]
    pub organization: String,
    #[serde(default)]
    pub gateway_proxy: String,
    #[serde(default)]
    pub assigned_endpoint: String,
    #[serde(default)]
    pub registered_at: u64,

    /// What Cloudflare holds for this device now.
    #[serde(default)]
    pub tunnel_type: TunnelType,

    /// Set before an enrolment request goes out, cleared when its answer is
    /// stored.
    ///
    /// A record that still carries this was interrupted between the two, so
    /// what Cloudflare holds is genuinely unknown -- and a file cannot record a
    /// change that was never written, which is why this is written first
    /// instead. MASQUE may use such a device: enrolling twice costs a round
    /// trip. WireGuard may not, until the answer is confirmed.
    #[serde(default)]
    pub enrolment_pending_since: u64,

    #[serde(default)]
    pub cert_pem: String,
    #[serde(default)]
    pub key_pem: String,
    #[serde(default)]
    pub cert_issued_at: u64,

    /// When Cloudflare last answered 401, 404 or 410 for this device.
    ///
    /// Kept rather than overwritten, so a report can say the identity was
    /// refused instead of showing an install that merely looks new.
    #[serde(default)]
    pub refused_at: u64,
}

/// What this address has spent, and when it may spend again.
///
/// A ceiling of our own invention -- "N registrations a day" -- is a guess at
/// somebody else's policy, and will be wrong in both directions. What is not a
/// guess is what Cloudflare said: a Retry-After, a 429, a refusal. Those are
/// recorded here and a wait is derived from them.
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct RegistrationBudget {
    #[serde(default)]
    pub attempts: u32,
    #[serde(default)]
    pub last_attempt_at: u64,
    #[serde(default)]
    pub next_attempt_at: u64,
    #[serde(default)]
    pub last_reason: String,
}

/// How long to wait after the nth consecutive failed registration.
///
/// Doubling from half a minute and stopping at an hour. Long enough that a
/// phone retrying in a pocket does not keep an address refused; short enough
/// that somebody who moves to a network that works is not held back by a
/// failure on the last one.
const BACKOFF_STEPS_SECS: [u64; 7] = [30, 60, 120, 300, 600, 1_800, 3_600];

impl RegistrationBudget {
    /// Whether a registration may be attempted now, and the wait if not.
    pub fn may_attempt(&self, now: u64) -> std::result::Result<(), u64> {
        if self.next_attempt_at > now {
            return Err(self.next_attempt_at - now);
        }
        Ok(())
    }

    /// Records an attempt that failed, and when the next one may go.
    ///
    /// `retry_after` is Cloudflare's own answer where it gave one, which always
    /// wins over the ladder: it is the only party that knows what it will
    /// accept.
    pub fn failed(&mut self, now: u64, reason: &str, retry_after: Option<u64>) {
        self.attempts = self.attempts.saturating_add(1);
        self.last_attempt_at = now;
        self.last_reason = reason.to_string();
        let step =
            BACKOFF_STEPS_SECS[(self.attempts as usize - 1).min(BACKOFF_STEPS_SECS.len() - 1)];
        self.next_attempt_at = now + retry_after.unwrap_or(step).max(step);
    }

    /// Records a registration that was kept, which clears the debt.
    pub fn succeeded(&mut self, now: u64) {
        self.attempts = 0;
        self.last_attempt_at = now;
        self.next_attempt_at = 0;
        self.last_reason.clear();
    }
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
struct Persisted {
    version: u32,
    #[serde(default)]
    devices: BTreeMap<String, Device>,
    #[serde(default)]
    slots: BTreeMap<String, String>,
    #[serde(default)]
    registration: RegistrationBudget,
}

/// Every device this install holds, and which role each one fills.
#[derive(Debug, Clone, Default)]
pub struct Store {
    devices: BTreeMap<String, Device>,
    slots: BTreeMap<Slot, String>,
    pub registration: RegistrationBudget,
}

/// What repairing the invariants changed, for the log and for the tests.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct Repairs {
    /// Slots cleared because the device they named is gone.
    pub dangling: Vec<Slot>,
    /// WireGuard slots cleared because their device had been enrolled for
    /// MASQUE, so Cloudflare holds no WireGuard key for it.
    pub revoked: Vec<Slot>,
    /// Slots cleared because another family already claimed the same device.
    pub shared: Vec<Slot>,
}

impl Repairs {
    pub fn is_empty(&self) -> bool {
        self.dangling.is_empty() && self.revoked.is_empty() && self.shared.is_empty()
    }
}

impl Store {
    pub fn device(&self, id: &str) -> Option<&Device> {
        self.devices.get(id)
    }

    pub fn device_id(&self, slot: Slot) -> Option<&str> {
        self.slots.get(&slot).map(String::as_str)
    }

    /// The device filling `slot`, if one does and it is usable there.
    ///
    /// Usability is not the same question as presence, which is why this is not
    /// two map lookups at the call site. A device whose enrolment is in flight
    /// is fine for MASQUE and unknown for WireGuard, and the call site that
    /// forgets which of those it is holding is the call site that ships the
    /// next silent failure.
    pub fn usable(&self, slot: Slot) -> Option<&Device> {
        let device = self.devices.get(self.slots.get(&slot)?)?;
        if slot.family() == Family::Wireguard && !device.wireguard_is_trustworthy() {
            return None;
        }
        Some(device)
    }

    /// The device filling `slot` and its id, if one does and it is usable there.
    ///
    /// Owned, because every caller needs the store back afterwards to record
    /// what it did with what it found.
    pub fn take_usable(&self, slot: Slot) -> Option<(String, Device)> {
        let id = self.slots.get(&slot)?;
        let device = self.devices.get(id)?;
        if slot.family() == Family::Wireguard && !device.wireguard_is_trustworthy() {
            return None;
        }
        Some((id.clone(), device.clone()))
    }

    /// Points `slot` at `id`, which must already be a device here.
    pub fn assign(&mut self, slot: Slot, id: &str) -> Result<()> {
        if !self.devices.contains_key(id) {
            return Err(AetherError::Other(format!(
                "cannot fill the {} slot with unknown device {id}",
                slot.key()
            )));
        }
        self.slots.insert(slot, id.to_string());
        Ok(())
    }

    pub fn clear(&mut self, slot: Slot) {
        self.slots.remove(&slot);
    }

    /// Adds or replaces a device, keeping what the caller did not say.
    pub fn put(&mut self, id: &str, device: Device) {
        self.devices.insert(id.to_string(), device);
    }

    pub fn device_mut(&mut self, id: &str) -> Option<&mut Device> {
        self.devices.get_mut(id)
    }

    pub fn ids(&self) -> impl Iterator<Item = &str> {
        self.devices.keys().map(String::as_str)
    }

    /// Brings the file back to something the invariants allow.
    ///
    /// Repaired rather than refused. This file is the only copy of credentials
    /// that cost a rate-limited registration, and throwing it away because one
    /// slot disagrees with one device would spend that allowance again for a
    /// problem the engine can simply correct.
    pub fn repair(&mut self) -> Repairs {
        let mut repairs = Repairs::default();

        for slot in Slot::ALL {
            let Some(id) = self.slots.get(&slot).cloned() else {
                continue;
            };

            let Some(device) = self.devices.get(&id) else {
                self.slots.remove(&slot);
                repairs.dangling.push(slot);
                continue;
            };

            if slot.family() == Family::Wireguard && device.tunnel_type == TunnelType::Masque {
                self.slots.remove(&slot);
                repairs.revoked.push(slot);
            }
        }

        // Invariant 2, after the rest: one device may not serve both families.
        // Whichever family the device's own tunnel_type agrees with keeps it;
        // that is the one Cloudflare would actually answer.
        for slot in Slot::ALL {
            let Some(id) = self.slots.get(&slot).cloned() else {
                continue;
            };
            let clash = Slot::ALL.into_iter().any(|other| {
                other != slot
                    && other.family() != slot.family()
                    && self.slots.get(&other).is_some_and(|held| *held == id)
            });
            if !clash {
                continue;
            }
            let device_is_masque = self
                .devices
                .get(&id)
                .is_some_and(|device| device.tunnel_type == TunnelType::Masque);
            let keeps_it = if device_is_masque {
                slot.family() == Family::Masque
            } else {
                slot.family() == Family::Wireguard
            };
            if !keeps_it {
                self.slots.remove(&slot);
                repairs.shared.push(slot);
            }
        }

        repairs
    }

    fn from_persisted(persisted: Persisted) -> Self {
        let mut slots = BTreeMap::new();
        for slot in Slot::ALL {
            if let Some(id) = persisted.slots.get(slot.key()) {
                if !id.is_empty() {
                    slots.insert(slot, id.clone());
                }
            }
        }
        Self {
            devices: persisted.devices,
            slots,
            registration: persisted.registration,
        }
    }

    fn to_persisted(&self) -> Persisted {
        Persisted {
            version: STORE_VERSION,
            devices: self.devices.clone(),
            slots: self
                .slots
                .iter()
                .map(|(slot, id)| (slot.key().to_string(), id.clone()))
                .collect(),
            registration: self.registration.clone(),
        }
    }

    pub fn to_text(&self) -> Result<String> {
        toml::to_string_pretty(&self.to_persisted())
            .map_err(|e| AetherError::Other(format!("identity store encode: {e}")))
    }

    pub fn parse(text: &str) -> Result<Self> {
        let persisted: Persisted = toml::from_str(text)
            .map_err(|e| AetherError::Other(format!("identity store parse: {e}")))?;
        if persisted.version == 0 || persisted.version > STORE_VERSION {
            return Err(AetherError::Other(format!(
                "this identity store was written by a different version of WhiteAesther \
                 (format {}, this build reads {STORE_VERSION})",
                persisted.version
            )));
        }
        Ok(Self::from_persisted(persisted))
    }
}

impl Device {
    /// Whether this device's WireGuard key is one Cloudflare would recognise.
    ///
    /// Three ways it is not, and all three used to be invisible: the key was
    /// overwritten by an enrolment; an enrolment was sent and its answer never
    /// came back, so it may have been; or Cloudflare has refused the device
    /// outright. Each of those presents to the endpoint search as silence from
    /// every address it tries, which reads exactly like a network that drops
    /// UDP -- so the search spends its whole budget and then blames the
    /// network.
    pub fn wireguard_is_trustworthy(&self) -> bool {
        self.tunnel_type == TunnelType::Wireguard
            && self.enrolment_pending_since == 0
            && self.refused_at == 0
    }

    /// Whether this device carries a MASQUE certificate worth presenting.
    pub fn has_certificate(&self) -> bool {
        !self.cert_pem.is_empty() && !self.key_pem.is_empty()
    }
}

/// Reads `identity` into a device record.
///
/// Goes through [`config::PersistedIdentity`] so the encoding of the keys is
/// decided in exactly one place: two spellings of the same base64 is the kind
/// of difference that only shows up as a handshake nobody can explain.
pub fn device_from(identity: &Identity, registered_at: u64) -> Device {
    let persisted = config::PersistedIdentity::from(identity);
    Device {
        access_token: persisted.access_token,
        ipv4: persisted.ipv4,
        ipv6: persisted.ipv6,
        wg_private_key: persisted.wg_private_key,
        wg_peer_public_key: persisted.wg_peer_public_key,
        client_id: persisted.client_id,
        organization: persisted.organization,
        gateway_proxy: persisted.gateway_proxy,
        assigned_endpoint: persisted.assigned_endpoint,
        registered_at,
        // A certificate on an identity read off the old files means the device
        // was enrolled, whatever else the file says -- that is the repair.
        tunnel_type: if persisted.cert_pem.is_empty() {
            TunnelType::Wireguard
        } else {
            TunnelType::Masque
        },
        enrolment_pending_since: 0,
        cert_pem: persisted.cert_pem,
        key_pem: persisted.key_pem,
        cert_issued_at: persisted.cert_issued_at,
        refused_at: 0,
    }
}

/// Turns a device record back into the identity the engine dials with.
pub fn identity_from(id: &str, device: &Device) -> Result<Identity> {
    let persisted = config::PersistedIdentity {
        device_id: id.to_string(),
        access_token: device.access_token.clone(),
        cert_pem: device.cert_pem.clone(),
        key_pem: device.key_pem.clone(),
        cert_issued_at: device.cert_issued_at,
        ipv4: device.ipv4.clone(),
        ipv6: device.ipv6.clone(),
        wg_private_key: device.wg_private_key.clone(),
        wg_peer_public_key: device.wg_peer_public_key.clone(),
        client_id: device.client_id.clone(),
        organization: device.organization.clone(),
        gateway_proxy: device.gateway_proxy.clone(),
        assigned_endpoint: device.assigned_endpoint.clone(),
    };
    Identity::try_from(persisted)
}

/// The store after a load, and what loading it had to change.
pub struct Loaded {
    pub store: Store,
    pub repairs: Repairs,
    /// True when this run built the store out of the files that predate it.
    pub migrated: bool,
}

/// Builds a store from the files that predate it.
///
/// Deduplicated by device id, which is the whole point: the same device in two
/// files becomes one record, and a certificate found in *any* of its copies
/// means Cloudflare enrolled it and its WireGuard key is gone. [`Store::repair`]
/// then clears the WireGuard slot pointing at it, which is the repair for every
/// install 1.8.0 broke -- reached by reading files rather than by asking the
/// user for anything.
///
/// Pure, and therefore repeatable: the same files give the same store however
/// many times this runs, which is what makes retrying a failed write safe.
pub fn migrate_from_legacy(legacy: &[(Slot, String)]) -> Store {
    let mut store = Store::default();

    for (slot, path) in legacy {
        let Some(identity) = config::peek(path) else {
            continue;
        };
        let id = identity.device_id.clone();
        let incoming = device_from(&identity, 0);

        match store.devices.get_mut(&id) {
            // Seen already, in another file. One record, and the copy that
            // knows about an enrolment decides what Cloudflare holds.
            Some(existing) => {
                if incoming.tunnel_type == TunnelType::Masque {
                    existing.tunnel_type = TunnelType::Masque;
                    if existing.cert_pem.is_empty() {
                        existing.cert_pem = incoming.cert_pem;
                        existing.key_pem = incoming.key_pem;
                        existing.cert_issued_at = incoming.cert_issued_at;
                    }
                }
            }
            None => {
                store.devices.insert(id.clone(), incoming);
            }
        }

        store.slots.insert(*slot, id);
    }

    store
}

/// Adopts what the identity files say wherever they disagree with the store.
///
/// The store answers for reads, but for one release the older files are written
/// beside it so that going back to an earlier build still works. Going back and
/// then forward again is what this exists for: the earlier build provisioned
/// into a file, knowing nothing about the store, which would otherwise still be
/// naming the device from before it. Whoever wrote last is right.
///
/// Runs before [`Store::repair`], never instead of it — a device an earlier
/// build adopted is checked against the invariants like any other, rather than
/// smuggled past them by the file it arrived in.
fn reconcile_with_legacy(store: &mut Store, legacy: &[(Slot, String)]) -> Vec<Slot> {
    let mut adopted = Vec::new();

    for (slot, path) in legacy {
        let Some(identity) = config::peek(path) else {
            continue;
        };
        if store
            .slots
            .get(slot)
            .is_some_and(|held| *held == identity.device_id)
        {
            continue;
        }

        let id = identity.device_id.clone();
        let incoming = device_from(&identity, 0);
        match store.devices.get_mut(&id) {
            Some(existing) => {
                // Only ever towards knowing more. A file that shows an enrolment
                // the store had not recorded is news; one that does not is
                // silence, and silence is not evidence that a key came back.
                if incoming.tunnel_type == TunnelType::Masque {
                    existing.tunnel_type = TunnelType::Masque;
                }
            }
            None => {
                store.devices.insert(id.clone(), incoming);
            }
        }
        store.slots.insert(*slot, id);
        adopted.push(*slot);
    }

    adopted
}

/// Reads the store, building it from the older files the first time.
///
/// A store that will not parse is set aside rather than trusted, and the older
/// files are read instead -- they are still written alongside it for exactly
/// this reason, and for the user who goes back to an earlier build.
pub fn load(store_path: &str, legacy: &[(Slot, String)]) -> Result<Loaded> {
    let existing =
        std::fs::read_to_string(store_path)
            .ok()
            .and_then(|text| match Store::parse(&text) {
                Ok(store) => Some(store),
                Err(error) => {
                    log::warn!(
                        "[-] the identity store at {store_path} could not be read ({error}); \
                     rebuilding it from the identity files beside it"
                    );
                    let aside = format!("{store_path}.corrupt");
                    let _ = std::fs::rename(store_path, &aside);
                    None
                }
            });

    let migrated = existing.is_none();
    let mut store = match existing {
        Some(store) => store,
        None => migrate_from_legacy(legacy),
    };

    // Migration has just read these files; only a store that was already here
    // can have fallen behind them.
    let adopted = if migrated {
        Vec::new()
    } else {
        reconcile_with_legacy(&mut store, legacy)
    };
    for slot in &adopted {
        log::info!(
            "[+] the {} slot was filled by another build; taking its device into the store",
            slot.key()
        );
    }

    let repairs = store.repair();
    if !repairs.is_empty() {
        for slot in &repairs.revoked {
            log::info!(
                "[!] the device filling the {} slot was enrolled for MASQUE, which revoked its \
                 WireGuard key; an account of its own will be provisioned",
                slot.key()
            );
        }
        for slot in &repairs.shared {
            log::info!(
                "[!] the {} slot shared a device with the other protocol family and has given it \
                 up; enrolling one device for both is what revokes its WireGuard key",
                slot.key()
            );
        }
        for slot in &repairs.dangling {
            log::info!(
                "[!] the {} slot named a device this install no longer holds",
                slot.key()
            );
        }
    }

    if migrated || !repairs.is_empty() || !adopted.is_empty() {
        // Written, then read back before anything is built on it. A migration
        // that does not survive its own file is discarded and the older files
        // go on being used, so failing here costs nothing -- and because
        // migrate_from_legacy is pure, the next run simply tries again.
        match save(store_path, &store).and_then(|()| {
            let text = std::fs::read_to_string(store_path)?;
            Store::parse(&text)
        }) {
            Ok(_) => {
                if migrated {
                    log::info!(
                        "[+] migrated {} device(s) into the identity store at {store_path}",
                        store.devices.len()
                    );
                }
            }
            Err(error) => {
                log::warn!(
                    "[-] the identity store could not be written ({error}); carrying on with the \
                     identity files and leaving the store for the next run"
                );
                let _ = std::fs::remove_file(store_path);
            }
        }
    }

    Ok(Loaded {
        store,
        repairs,
        migrated,
    })
}

pub fn save(store_path: &str, store: &Store) -> Result<()> {
    config::write_private(store_path, &store.to_text()?)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn wireguard_device(token: &str) -> Device {
        Device {
            access_token: token.to_string(),
            ipv4: "172.16.0.2".into(),
            ipv6: "2606:4700:110::1".into(),
            wg_private_key: "BwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwc=".into(),
            wg_peer_public_key: "CQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQk=".into(),
            registered_at: 1_789_000_000,
            ..Device::default()
        }
    }

    fn masque_device(token: &str) -> Device {
        Device {
            tunnel_type: TunnelType::Masque,
            cert_pem: "-----BEGIN CERTIFICATE-----".into(),
            key_pem: "-----BEGIN PRIVATE KEY-----".into(),
            cert_issued_at: 1_789_000_000,
            ..wireguard_device(token)
        }
    }

    #[test]
    fn a_store_survives_a_round_trip_through_its_file() {
        let mut store = Store::default();
        store.put("dev-wg", wireguard_device("token-wg"));
        store.put("dev-masque", masque_device("token-masque"));
        store.assign(Slot::Wireguard, "dev-wg").unwrap();
        store.assign(Slot::Masque, "dev-masque").unwrap();
        store.registration.failed(1_789_000_100, "429", Some(90));

        let restored = Store::parse(&store.to_text().unwrap()).unwrap();

        assert_eq!(Some("dev-wg"), restored.device_id(Slot::Wireguard));
        assert_eq!(Some("dev-masque"), restored.device_id(Slot::Masque));
        assert_eq!(None, restored.device_id(Slot::MasqueInner));
        assert_eq!(
            TunnelType::Masque,
            restored.device("dev-masque").unwrap().tunnel_type,
        );
        assert_eq!(1_789_000_190, restored.registration.next_attempt_at);
    }

    /// Invariant 3, which is the bug that broke 1.8 expressed as data.
    ///
    /// Under the old files this was a device in two places and only one of them
    /// knowing it had been enrolled. Here there is one device, it says what
    /// Cloudflare holds, and a WireGuard slot cannot be filled from it.
    #[test]
    fn a_masque_device_cannot_fill_a_wireguard_slot() {
        let mut store = Store::default();
        store.put("dev-shared", masque_device("token"));
        store.assign(Slot::Wireguard, "dev-shared").unwrap();

        let repairs = store.repair();

        assert_eq!(vec![Slot::Wireguard], repairs.revoked);
        assert_eq!(None, store.device_id(Slot::Wireguard));
        assert!(
            store.device("dev-shared").is_some(),
            "the device itself is still worth keeping for masque",
        );
    }

    /// Invariant 4: an enrolment whose answer never arrived.
    ///
    /// The window 1.8.1 could not close, because no file can record a change
    /// that was never written. This one is written before the request.
    #[test]
    fn a_device_with_an_enrolment_in_flight_is_not_offered_to_wireguard() {
        let mut store = Store::default();
        let mut device = wireguard_device("token");
        device.enrolment_pending_since = 1_789_000_000;
        store.put("dev-pending", device);
        store.assign(Slot::Wireguard, "dev-pending").unwrap();
        store.assign(Slot::Masque, "dev-pending").unwrap();

        assert!(
            store.usable(Slot::Wireguard).is_none(),
            "a key that may already have been overwritten is not a key to dial with",
        );
        assert!(
            store.usable(Slot::Masque).is_some(),
            "masque may enrol again; it costs a round trip, not a registration",
        );
    }

    /// Invariant 2: the adoption that started all of this, now unrepresentable.
    #[test]
    fn one_device_cannot_serve_both_families() {
        let mut store = Store::default();
        store.put("dev-one", masque_device("token"));
        store.assign(Slot::Wireguard, "dev-one").unwrap();
        store.assign(Slot::Masque, "dev-one").unwrap();

        let repairs = store.repair();

        assert_eq!(None, store.device_id(Slot::Wireguard));
        assert_eq!(Some("dev-one"), store.device_id(Slot::Masque));
        assert!(
            repairs.revoked.contains(&Slot::Wireguard) || repairs.shared.contains(&Slot::Wireguard),
            "the wireguard slot had to give the device up: {repairs:?}",
        );
    }

    /// The same clash, where the device is still a WireGuard one.
    ///
    /// Nothing has been enrolled yet, so the family that matches what
    /// Cloudflare holds keeps it and MASQUE is sent to register its own --
    /// rather than MASQUE enrolling this one and repeating the whole failure.
    #[test]
    fn an_unenrolled_shared_device_stays_with_wireguard() {
        let mut store = Store::default();
        store.put("dev-one", wireguard_device("token"));
        store.assign(Slot::Wireguard, "dev-one").unwrap();
        store.assign(Slot::Masque, "dev-one").unwrap();

        let repairs = store.repair();

        assert_eq!(Some("dev-one"), store.device_id(Slot::Wireguard));
        assert_eq!(None, store.device_id(Slot::Masque));
        assert_eq!(vec![Slot::Masque], repairs.shared);
    }

    #[test]
    fn a_slot_pointing_at_nothing_is_cleared_rather_than_carried() {
        let mut store = Store::default();
        store.slots.insert(Slot::Masque, "dev-gone".into());

        let repairs = store.repair();

        assert_eq!(vec![Slot::Masque], repairs.dangling);
        assert_eq!(None, store.device_id(Slot::Masque));
    }

    #[test]
    fn a_refused_device_is_kept_but_not_dialled() {
        let mut device = wireguard_device("token");
        device.refused_at = 1_789_000_000;
        let mut store = Store::default();
        store.put("dev-refused", device);
        store.assign(Slot::Wireguard, "dev-refused").unwrap();

        assert!(store.usable(Slot::Wireguard).is_none());
        assert!(
            store.device("dev-refused").is_some(),
            "a refusal is worth reporting, which means it is worth keeping",
        );
    }

    #[test]
    fn a_store_from_a_future_format_is_refused_by_name() {
        let error = Store::parse("version = 99\n").unwrap_err().to_string();
        assert!(error.contains("different version"), "{error}");
    }

    #[test]
    fn a_slot_cannot_be_filled_with_a_device_that_is_not_here() {
        let mut store = Store::default();
        assert!(store.assign(Slot::Wireguard, "nobody").is_err());
    }

    /// Cloudflare's own answer wins over the ladder, and never shortens it.
    #[test]
    fn the_wait_after_a_failure_honours_what_cloudflare_asked_for() {
        let mut budget = RegistrationBudget::default();
        assert!(budget.may_attempt(1_000).is_ok());

        budget.failed(1_000, "429", Some(600));
        assert_eq!(Err(600), budget.may_attempt(1_000));
        assert!(budget.may_attempt(1_600).is_ok());

        // A Retry-After shorter than the ladder does not shorten it: the ladder
        // is there for the failures that came with no answer at all.
        let mut budget = RegistrationBudget::default();
        budget.failed(1_000, "timeout", Some(1));
        assert_eq!(Err(BACKOFF_STEPS_SECS[0]), budget.may_attempt(1_000));
    }

    #[test]
    fn the_wait_grows_with_repeated_failures_and_stops_growing() {
        let mut budget = RegistrationBudget::default();
        let mut waits = Vec::new();
        for _ in 0..10 {
            budget.failed(0, "no route to the api", None);
            waits.push(budget.next_attempt_at);
        }

        assert!(
            waits.windows(2).all(|pair| pair[1] >= pair[0]),
            "the wait has to grow: {waits:?}",
        );
        assert_eq!(
            Some(&BACKOFF_STEPS_SECS[BACKOFF_STEPS_SECS.len() - 1]),
            waits.last(),
            "and then stop, so a phone that moves network is not held for a day",
        );
    }

    #[test]
    fn a_registration_that_is_kept_clears_the_debt() {
        let mut budget = RegistrationBudget::default();
        budget.failed(1_000, "429", Some(3_600));
        budget.succeeded(2_000);

        assert!(budget.may_attempt(2_000).is_ok());
        assert_eq!(0, budget.attempts);
        assert!(budget.last_reason.is_empty());
    }

    fn scratch(name: &str) -> std::path::PathBuf {
        let dir = std::env::temp_dir().join(format!(
            "aether-store-{name}-{}-{:?}",
            std::process::id(),
            std::thread::current().id()
        ));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).expect("scratch directory");
        dir
    }

    fn legacy_identity(device: &str, with_certificate: bool) -> Identity {
        Identity {
            device_id: device.into(),
            access_token: "token".into(),
            cert_pem: if with_certificate {
                b"-----BEGIN CERTIFICATE-----".to_vec()
            } else {
                Vec::new()
            },
            key_pem: if with_certificate {
                b"-----BEGIN PRIVATE KEY-----".to_vec()
            } else {
                Vec::new()
            },
            cert_issued_at: if with_certificate { 1_789_000_000 } else { 0 },
            ipv4: "172.16.0.2".into(),
            ipv6: "2606:4700:110::1".into(),
            wg_private_key: [7u8; 32],
            wg_peer_public_key: [9u8; 32],
            client_id: [1, 2, 3],
            organization: String::new(),
            gateway_proxy: String::new(),
            assigned_endpoint: String::new(),
            refused: false,
        }
    }

    /// The install 1.8.0 broke, migrated.
    ///
    /// One device in two files: WireGuard's copy looks pristine, and MASQUE's
    /// carries the certificate that proves the enrolment which revoked the
    /// WireGuard key. The store holds it once, knows it is a MASQUE device, and
    /// leaves the WireGuard slot empty so an account of its own is provisioned.
    /// Nothing is asked of the user, and nothing is deleted.
    #[test]
    fn migrating_an_install_broken_by_an_earlier_build_repairs_it() {
        let dir = scratch("repair");
        let warp = dir.join("aether.toml");
        let masque = dir.join("aether-masque.toml");
        config::save(
            warp.to_str().unwrap(),
            &legacy_identity("dev-shared", false),
        )
        .unwrap();
        config::save(
            masque.to_str().unwrap(),
            &legacy_identity("dev-shared", true),
        )
        .unwrap();

        let legacy = vec![
            (Slot::Wireguard, warp.to_str().unwrap().to_string()),
            (Slot::Masque, masque.to_str().unwrap().to_string()),
        ];
        let store_path = dir.join("identities.toml");
        let loaded = load(store_path.to_str().unwrap(), &legacy).unwrap();

        assert!(loaded.migrated);
        assert_eq!(
            1,
            loaded.store.ids().count(),
            "one Cloudflare device is one record, however many files held it",
        );
        assert_eq!(Some("dev-shared"), loaded.store.device_id(Slot::Masque));
        assert_eq!(
            None,
            loaded.store.device_id(Slot::Wireguard),
            "the wireguard slot kept a key Cloudflare had thrown away",
        );
        assert_eq!(
            TunnelType::Masque,
            loaded.store.device("dev-shared").unwrap().tunnel_type,
        );

        // Reversible: the files it was built from are all still there.
        assert!(
            warp.exists() && masque.exists(),
            "a legacy file was removed"
        );

        let _ = std::fs::remove_dir_all(&dir);
    }

    /// Running the migration again must not change the answer.
    ///
    /// It is retried whenever the store could not be written, so a migration
    /// that drifted would turn a failed write into a different install every
    /// time -- and the second run reads a store that already exists, which must
    /// also leave the legacy files alone.
    #[test]
    fn migration_is_idempotent() {
        let dir = scratch("idempotent");
        let warp = dir.join("aether.toml");
        let masque = dir.join("aether-masque.toml");
        config::save(warp.to_str().unwrap(), &legacy_identity("dev-wg", false)).unwrap();
        config::save(
            masque.to_str().unwrap(),
            &legacy_identity("dev-masque", true),
        )
        .unwrap();

        let legacy = vec![
            (Slot::Wireguard, warp.to_str().unwrap().to_string()),
            (Slot::Masque, masque.to_str().unwrap().to_string()),
        ];

        let first = migrate_from_legacy(&legacy);
        let second = migrate_from_legacy(&legacy);
        assert_eq!(first.to_text().unwrap(), second.to_text().unwrap());

        let store_path = dir.join("identities.toml");
        let once = load(store_path.to_str().unwrap(), &legacy).unwrap();
        let twice = load(store_path.to_str().unwrap(), &legacy).unwrap();

        assert!(once.migrated && !twice.migrated, "the store was rebuilt");
        assert_eq!(
            once.store.to_text().unwrap(),
            twice.store.to_text().unwrap(),
        );
        assert_eq!(Some("dev-wg"), twice.store.device_id(Slot::Wireguard));
        assert_eq!(Some("dev-masque"), twice.store.device_id(Slot::Masque));

        let _ = std::fs::remove_dir_all(&dir);
    }

    /// A store that will not parse falls back to the files beside it.
    ///
    /// Which is why those files are still written for a release: a store is one
    /// file holding every registration this install owns, and one unreadable
    /// file must not be able to cost all of them.
    #[test]
    fn an_unreadable_store_falls_back_to_the_identity_files() {
        let dir = scratch("corrupt-store");
        let warp = dir.join("aether.toml");
        config::save(warp.to_str().unwrap(), &legacy_identity("dev-wg", false)).unwrap();
        let store_path = dir.join("identities.toml");
        std::fs::write(&store_path, "this is not = a store [[[").unwrap();

        let legacy = vec![(Slot::Wireguard, warp.to_str().unwrap().to_string())];
        let loaded = load(store_path.to_str().unwrap(), &legacy).unwrap();

        assert_eq!(Some("dev-wg"), loaded.store.device_id(Slot::Wireguard));
        assert!(
            dir.join("identities.toml.corrupt").exists(),
            "the damaged store should be kept aside, not deleted",
        );

        let _ = std::fs::remove_dir_all(&dir);
    }

    /// A build that knows nothing about the store provisioned into the file.
    ///
    /// Which is what going back to 1.8.1 and forward again looks like. The
    /// store would otherwise still be naming the device from before, and the
    /// registration the earlier build bought would be abandoned.
    #[test]
    fn a_store_that_fell_behind_the_files_adopts_what_they_say() {
        let dir = scratch("reconcile");
        let warp = dir.join("aether.toml");
        let store_path = dir.join("identities.toml");
        let legacy = vec![(Slot::Wireguard, warp.to_str().unwrap().to_string())];

        config::save(warp.to_str().unwrap(), &legacy_identity("dev-first", false)).unwrap();
        let first = load(store_path.to_str().unwrap(), &legacy).unwrap();
        assert_eq!(Some("dev-first"), first.store.device_id(Slot::Wireguard));

        config::save(
            warp.to_str().unwrap(),
            &legacy_identity("dev-second", false),
        )
        .unwrap();
        let second = load(store_path.to_str().unwrap(), &legacy).unwrap();

        assert!(!second.migrated, "the store was still there to be read");
        assert_eq!(
            Some("dev-second"),
            second.store.device_id(Slot::Wireguard),
            "whoever wrote the file last is the one holding the registration",
        );

        let _ = std::fs::remove_dir_all(&dir);
    }

    /// Adopting a file does not put its device beyond the invariants.
    ///
    /// 1.8.1 marks a WireGuard file whose device was enrolled by writing the
    /// certificate into it. Reconciliation has to take that mark as news and
    /// then still leave the slot empty -- adopting first and checking second is
    /// the order that matters.
    #[test]
    fn reconciliation_does_not_smuggle_a_revoked_device_past_the_invariants() {
        let dir = scratch("reconcile-revoked");
        let warp = dir.join("aether.toml");
        let store_path = dir.join("identities.toml");
        let legacy = vec![(Slot::Wireguard, warp.to_str().unwrap().to_string())];

        config::save(warp.to_str().unwrap(), &legacy_identity("dev-a", false)).unwrap();
        load(store_path.to_str().unwrap(), &legacy).unwrap();

        config::save(warp.to_str().unwrap(), &legacy_identity("dev-b", true)).unwrap();
        let loaded = load(store_path.to_str().unwrap(), &legacy).unwrap();

        assert_eq!(
            TunnelType::Masque,
            loaded.store.device("dev-b").unwrap().tunnel_type,
            "the mark in the file is what says the key was overwritten",
        );
        assert_eq!(
            None,
            loaded.store.device_id(Slot::Wireguard),
            "a device with no wireguard key left cannot fill a wireguard slot",
        );

        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn an_install_with_nothing_yet_migrates_to_an_empty_store() {
        let dir = scratch("empty");
        let legacy = vec![(
            Slot::Wireguard,
            dir.join("aether.toml").to_str().unwrap().to_string(),
        )];
        let loaded = load(dir.join("identities.toml").to_str().unwrap(), &legacy).unwrap();

        assert_eq!(0, loaded.store.ids().count());
        assert_eq!(None, loaded.store.device_id(Slot::Wireguard));

        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn an_identity_survives_the_trip_through_a_device_record() {
        let identity = Identity {
            device_id: "dev-one".into(),
            access_token: "token".into(),
            cert_pem: b"-----BEGIN CERTIFICATE-----".to_vec(),
            key_pem: b"-----BEGIN PRIVATE KEY-----".to_vec(),
            cert_issued_at: 1_789_000_000,
            ipv4: "172.16.0.2".into(),
            ipv6: "2606:4700:110::1".into(),
            wg_private_key: [7u8; 32],
            wg_peer_public_key: [9u8; 32],
            client_id: [1, 2, 3],
            organization: "example".into(),
            gateway_proxy: "172.16.0.1:2480".into(),
            assigned_endpoint: "162.159.197.2".into(),
            refused: false,
        };

        let device = device_from(&identity, 1_789_000_000);
        assert_eq!(
            TunnelType::Masque,
            device.tunnel_type,
            "a certificate on the old files means the device was enrolled",
        );

        let back = identity_from("dev-one", &device).unwrap();
        assert_eq!(identity.device_id, back.device_id);
        assert_eq!(identity.wg_private_key, back.wg_private_key);
        assert_eq!(identity.wg_peer_public_key, back.wg_peer_public_key);
        assert_eq!(identity.client_id, back.client_id);
        assert_eq!(identity.cert_pem, back.cert_pem);
        assert_eq!(identity.assigned_endpoint, back.assigned_endpoint);
    }
}
