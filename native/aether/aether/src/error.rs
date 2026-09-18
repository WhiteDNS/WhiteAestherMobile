use thiserror::Error;

#[derive(Error, Debug)]
pub enum AetherError {
    #[error("io: {0}")]
    Io(#[from] std::io::Error),

    #[error("quic: {0}")]
    Quic(#[from] quiche::Error),

    #[error("h3: {0}")]
    H3(#[from] quiche::h3::Error),

    #[error("tls: {0}")]
    Tls(String),

    #[error("ech: {0}")]
    Ech(String),

    #[error("masque: {0}")]
    Masque(String),

    #[error("prober: no clean endpoint found")]
    NoCleanEndpoint,

    #[error("capsule: {0}")]
    Capsule(String),

    #[error("api: {0}")]
    Api(String),

    #[error("identity refused: {0}")]
    IdentityRefused(String),

    /// Cloudflare asked to be asked later, and may have said when.
    ///
    /// Distinct from [`AetherError::Api`] because the wait is a number a caller
    /// has to keep rather than a sentence it can only print: the next attempt
    /// is held back by it across process restarts, so it has to survive the
    /// trip out of here.
    #[error("api: {reason}")]
    RateLimited {
        reason: String,
        retry_after: Option<u64>,
    },

    /// Refused here, before the request went, because the wait has not run out.
    ///
    /// A registration costs an entry against a per-address limit, and an
    /// address that has spent it is refused for hours -- on every version of
    /// the app the user might fall back to. Saying so beats spending another.
    #[error("registration is on hold for another {wait}s: {reason}")]
    RegistrationOnHold { reason: String, wait: u64 },

    #[error("cancelled")]
    Cancelled,

    #[error("other: {0}")]
    Other(String),
}

impl AetherError {
    /// The wait Cloudflare asked for, where this error carries one.
    ///
    /// A method rather than a match at each call site, because the answer has
    /// to survive being wrapped: registration reports the direct route and the
    /// camouflaged one together, and the number has to come through that.
    pub fn retry_after(&self) -> Option<u64> {
        match self {
            AetherError::RateLimited { retry_after, .. } => *retry_after,
            _ => None,
        }
    }
}

pub type Result<T> = std::result::Result<T, AetherError>;
