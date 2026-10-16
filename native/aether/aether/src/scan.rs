//! Per-request discovery telemetry. Observers must return quickly and never dial.
use std::net::SocketAddr;
use std::time::{Duration, Instant};

#[derive(Clone, Debug, Default, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ScanProgress {
    pub completed: usize,
    pub total: usize,
    pub elapsed_millis: u64,
    pub failures: String,
    pub results: Vec<ScanHit>,
}

#[derive(Clone, Debug, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ScanHit {
    pub peer: SocketAddr,
    pub rtt_ms: u64,
}

pub type ScanObserver<'a> = &'a (dyn Fn(ScanProgress) + Send + Sync);

impl ScanProgress {
    pub fn new(total: usize) -> Self {
        Self {
            total,
            ..Self::default()
        }
    }

    pub fn hit(&mut self, peer: SocketAddr, rtt: Duration) {
        if !self.results.iter().any(|hit| hit.peer == peer) {
            self.results.push(ScanHit {
                peer,
                rtt_ms: rtt.as_millis().min(u64::MAX as u128) as u64,
            });
        }
    }

    pub fn publish(&mut self, started: Instant, failures: String, observer: ScanObserver<'_>) {
        self.elapsed_millis = started.elapsed().as_millis().min(u64::MAX as u128) as u64;
        self.failures = failures;
        observer(self.clone());
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn snapshots_keep_distinct_ports_and_use_the_android_wire_format() {
        let mut progress = ScanProgress::new(100);
        let peer = "162.159.197.3:443".parse().unwrap();
        progress.hit(peer, Duration::from_millis(42));
        progress.hit(peer, Duration::from_millis(43));
        progress.hit(
            "162.159.197.3:8443".parse().unwrap(),
            Duration::from_millis(55),
        );
        assert_eq!(progress.results.len(), 2);
        let json = serde_json::to_value(&progress).unwrap();
        assert_eq!(json["total"], 100);
        assert_eq!(json["results"][0]["peer"], "162.159.197.3:443");
        assert_eq!(json["results"][0]["rttMs"], 42);
    }
}
