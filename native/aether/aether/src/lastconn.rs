use serde::{Deserialize, Serialize};

/// What an endpoint was proven with.
///
/// An address on its own is not a memory of anything useful. The same gateway
/// answers over one framing and not the other, with a split ClientHello and not
/// without, under one obfuscation profile and not another -- so a cache holding
/// only the address offers a proof it never made, and the connect that leads
/// with it spends a check finding that out.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct Proof {
    /// `h2`, `h3`, `wg`, or whatever the run used.
    #[serde(default)]
    pub transport: String,
    /// The obfuscation profile in force.
    #[serde(default)]
    pub profile: String,
    #[serde(default)]
    pub fragment_tls: bool,
    #[serde(default)]
    pub ech: bool,
    /// Digest of TLS, ECH lookup and record-shaping settings. Empty in files
    /// written before 2.3, which remain worth one validation attempt.
    #[serde(default)]
    pub fingerprint: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct LastConnection {
    pub peer: String,
    /// Kept at the top level for the files earlier builds wrote.
    #[serde(default)]
    pub profile: String,
    #[serde(default)]
    pub proof: Proof,
    #[serde(default)]
    pub recent: Vec<RecentPeer>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RecentPeer {
    pub peer: String,
    pub proof: Proof,
}

pub const RECENT_CAP: usize = 8;

impl LastConnection {
    /// Whether this memory is about the attempt being made now.
    ///
    /// A file written before proofs were recorded has no transport, and is
    /// accepted: it is one quick check to find out, and refusing it would throw
    /// away every existing install's cache on the first connect after updating.
    /// Anything it does say has to agree.
    pub fn applies_to(&self, now: &Proof) -> bool {
        if self.proof.transport.is_empty() {
            return true;
        }
        self.proof.transport == now.transport
            && self.proof.fragment_tls == now.fragment_tls
            && self.proof.ech == now.ech
            && (self.proof.fingerprint.is_empty() || self.proof.fingerprint == now.fingerprint)
    }

    pub fn candidates(&self, proof: &Proof) -> Vec<Self> {
        let mut seen = std::collections::HashSet::new();
        std::iter::once(RecentPeer {
            peer: self.peer.clone(),
            proof: self.proof.clone(),
        })
        .chain(self.recent.iter().cloned())
        .map(|entry| Self {
            peer: entry.peer,
            profile: entry.proof.profile.clone(),
            proof: entry.proof,
            recent: Vec::new(),
        })
        .filter(|entry| entry.applies_to(proof) && seen.insert(entry.peer.clone()))
        .take(RECENT_CAP)
        .collect()
    }
}

pub fn load(path: &str) -> Option<LastConnection> {
    let text = std::fs::read_to_string(path).ok()?;
    let mut connection: LastConnection = toml::from_str(&text).ok()?;
    if connection.proof.profile.is_empty() {
        connection.proof.profile = connection.profile.clone();
    }
    Some(connection)
}

/// Records an endpoint that has carried traffic, and what carried it.
///
/// Called when a tunnel confirms end-to-end data, never when one is merely
/// chosen. It used to be written the moment an endpoint was picked -- before
/// anything had gone through it -- so an address that passed every check and
/// then failed to build a session was stored as the last good one, and the next
/// connect led with it.
pub fn save(path: &str, peer: &str, proof: &Proof) {
    let mut recent = vec![RecentPeer {
        peer: peer.to_string(),
        proof: proof.clone(),
    }];
    if let Some(previous) = load(path) {
        let entries = std::iter::once(RecentPeer {
            peer: previous.peer,
            proof: previous.proof,
        })
        .chain(previous.recent);
        for entry in entries {
            if recent.len() == RECENT_CAP {
                break;
            }
            if !recent
                .iter()
                .any(|kept| kept.peer == entry.peer && kept.proof == entry.proof)
            {
                recent.push(entry);
            }
        }
    }
    let conn = LastConnection {
        peer: peer.to_string(),
        profile: proof.profile.clone(),
        proof: proof.clone(),
        recent,
    };
    match toml::to_string_pretty(&conn) {
        Ok(text) => {
            if let Err(e) = std::fs::write(path, text) {
                log::debug!("[lastconn] failed to save {path}: {e}");
            }
        }
        Err(e) => log::debug!("[lastconn] failed to encode: {e}"),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn history_is_bounded_and_does_not_reuse_a_different_tls_profile() {
        let directory = std::env::temp_dir().join(format!(
            "aether-history-{}-{}",
            std::process::id(),
            rand::random::<u64>()
        ));
        std::fs::create_dir_all(&directory).unwrap();
        let path = directory.join("lastconn.toml");
        let path = path.to_str().unwrap();
        let mut shape = proof("h2", true, true);
        shape.fingerprint = "original".into();
        for n in 1..=12 {
            save(path, &format!("162.159.198.{n}:443"), &shape);
        }
        let cached = load(path).unwrap();
        let candidates = cached.candidates(&shape);
        assert_eq!(candidates.len(), RECENT_CAP);
        assert_eq!(candidates[0].peer, "162.159.198.12:443");
        assert_eq!(candidates[7].peer, "162.159.198.5:443");
        shape.fingerprint = "changed".into();
        assert!(cached.candidates(&shape).is_empty());
        save(path, "162.159.198.20:443", &shape);
        assert_eq!(load(path).unwrap().candidates(&shape).len(), 1);
        std::fs::remove_file(path).unwrap();
        std::fs::remove_dir(directory).unwrap();
    }

    fn proof(transport: &str, fragment_tls: bool, ech: bool) -> Proof {
        Proof {
            transport: transport.to_string(),
            profile: "firewall".to_string(),
            fragment_tls,
            ech,
            fingerprint: String::new(),
        }
    }

    fn remembered(proof: Proof) -> LastConnection {
        LastConnection {
            peer: "162.159.198.2:443".to_string(),
            profile: proof.profile.clone(),
            proof,
            recent: Vec::new(),
        }
    }

    /// An endpoint proven over one framing is not a proof about the other.
    ///
    /// The cache is consulted whatever the attempt is made with, so without
    /// this a gateway that answered over TCP is led with on a QUIC attempt --
    /// and the check that finds out costs five seconds at the front of every
    /// connect.
    #[test]
    fn a_memory_only_applies_to_the_shape_that_earned_it() {
        let over_h2 = remembered(proof("h2", false, false));

        assert!(over_h2.applies_to(&proof("h2", false, false)));
        assert!(!over_h2.applies_to(&proof("h3", false, false)));
        assert!(!over_h2.applies_to(&proof("h2", true, false)));
        assert!(!over_h2.applies_to(&proof("h2", false, true)));
    }

    /// A file from before proofs were recorded is still worth reading.
    ///
    /// Refusing it would throw away every existing install's cache on the first
    /// connect after updating, to save a single check.
    #[test]
    fn a_file_written_by_an_earlier_build_is_still_used() {
        let old = LastConnection {
            peer: "162.159.198.2:443".to_string(),
            profile: "firewall".to_string(),
            proof: Proof::default(),
            recent: Vec::new(),
        };
        assert!(old.applies_to(&proof("h3", true, true)));
    }

    /// The files earlier builds wrote still parse, and so do the ones this writes.
    #[test]
    fn both_shapes_of_file_round_trip() {
        let old: LastConnection =
            toml::from_str("peer = \"1.2.3.4:443\"\nprofile = \"gfw\"\n").expect("the old shape");
        assert_eq!("1.2.3.4:443", old.peer);
        assert_eq!("gfw", old.profile);
        assert_eq!(Proof::default(), old.proof);

        let written = toml::to_string_pretty(&remembered(proof("h3", false, true))).unwrap();
        let back: LastConnection = toml::from_str(&written).expect("the new shape");
        assert_eq!("h3", back.proof.transport);
        assert!(back.proof.ech);
        assert!(!back.proof.fragment_tls);
        // The top-level profile stays, so a build that only knows the old shape
        // still reads something true out of a file this one wrote.
        assert_eq!("firewall", back.profile);
    }
}
