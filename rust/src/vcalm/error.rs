/// Errors that can occur while driving a VCALM `vcapi` exchange.
#[derive(thiserror::Error, Debug, uniffi::Error)]
pub enum VcalmError {
    /// An unexpected foreign-callback error occurred across the FFI boundary.
    #[error("An unexpected foreign callback error occurred: {0}")]
    UnexpectedUniFFICallbackError(String),

    /// A transport-level failure (stringified `reqwest::Error`).
    #[error("Network error: {0}")]
    Network(String),

    /// A response body failed to deserialize (stringified `serde_json::Error`).
    #[error("Failed to deserialize response: {0}")]
    Deserialization(String),

    /// The discovery response's `protocols` map lacked a `vcapi` key.
    #[error("Discovery response did not advertise a `vcapi` protocol")]
    NoVcapiProtocol,

    /// A 4xx response whose body failed to parse as RFC 9457 problem-details.
    #[error("Malformed problem-details in {status} response")]
    MalformedProblemDetails { status: u16, body: String },

    /// A 5xx (or otherwise non-2xx/4xx) server response.
    #[error("Server returned error status {status}")]
    ServerError { status: u16, body: String },

    /// Errors bubbling up from the VDC collection.
    #[error(transparent)]
    VdcCollection(#[from] crate::vdc_collection::VdcCollectionError),

    /// Presentation/signing errors.
    #[error(transparent)]
    Presentation(#[from] crate::oid4vp::presentation::PresentationError),

    /// A credential's cryptographic verification machinery failed to run.
    #[error(transparent)]
    Verification(#[from] crate::credential::VerificationError),

    /// A credential ran the verification machinery but was judged invalid
    /// (claims or proof).
    #[error(transparent)]
    InvalidCredentialDetail(#[from] crate::credential::InvalidCredential),

    /// A received credential failed to decode into a parsed credential.
    #[error(transparent)]
    CredentialDecoding(#[from] crate::credential::CredentialDecodingError),

    /// A parsed credential failed to re-encode into its storable generic form.
    #[error(transparent)]
    CredentialEncoding(#[from] crate::credential::CredentialEncodingError),

    /// The offered presentation carried no verifiable credentials.
    #[error("the offer carried no verifiable credentials")]
    NoOfferedCredentials,

    /// An offered credential failed cryptographic proof verification.
    /// `index` is the credential's position in the offer.
    #[error("offered credential #{index} failed cryptographic proof verification")]
    InvalidCredentialProof { index: u32 },

    /// A session method was called in the wrong state (no active exchange, no
    /// pending offer, no storage configured, …).
    #[error("invalid session state: {0}")]
    SessionState(String),

    /// A non-HTTPS (or non-HTTP-scheme) URL was rejected (§3.7.1 / B.2). Plain
    /// `http` is only accepted for loopback hosts (local development).
    #[error("insecure URL rejected: {0}")]
    InsecureUrl(String),

    /// A response body exceeded the configured size cap (B.4).
    #[error("response body exceeded the {limit_bytes}-byte limit")]
    ResponseTooLarge { limit_bytes: u64 },

    /// §3.4.3.2: the VPR `domain` does not match the exchange channel host.
    /// Refused before signing; the caller may explicitly override.
    #[error("VPR domain ({domain}) does not match the exchange channel host ({channel})")]
    DomainChannelMismatch { domain: String, channel: String },

    /// §3.4.3.1: the VPR's `acceptedCryptosuites` lists no suite this holder
    /// can produce.
    #[error("no supported cryptosuite among the VPR's acceptedCryptosuites: {accepted}")]
    NoAcceptedCryptosuite { accepted: String },

    /// §3.4.3.2: every DIDAuthentication query's `acceptedMethods` excludes the
    /// holder's `did:key`.
    #[error("no supported DID method among the VPR's acceptedMethods: {accepted}")]
    NoAcceptedDidMethod { accepted: String },

    /// The selected credentials mix VCDM v1 and v2 data models, which cannot be
    /// embedded in a single presentation. Select same-version credentials.
    #[error("selected credentials mix VCDM v1 and v2; present one data-model version at a time")]
    MixedCredentialVersions,

    /// The selected credentials resolve to distinct per-credential signing keys.
    /// A single VCALM VP is signed with one holder key, so only the first
    /// credential's binding would verify; present credentials that share a
    /// signing key (or one credential at a time).
    #[error("selected credentials resolve to distinct per-credential signing keys; a single VP is signed with one holder key")]
    MixedCredentialKeys,

    /// A selected credential carries no proof that is safe to present (B.1
    /// allowlist) — e.g. only an SD/bbs base proof.
    #[error("credential carries no presentable proof (B.1 allowlist): {credential_types}")]
    NoPresentableProof { credential_types: String },

    /// Selective-disclosure derivation failed for a credential the VPR asked to
    /// SD-derive. NOT silently downgraded to full disclosure.
    #[error("selective-disclosure derive failed: {0}")]
    SdDeriveFailed(String),

    /// A credential format/proof type this holder cannot process yet
    /// (e.g. `EnvelopedVerifiableCredential`, a `bbs-2023` base proof).
    #[error("unsupported credential format: {0}")]
    UnsupportedCredentialFormat(String),
}

// Handle unexpected errors when calling a foreign callback.
impl From<uniffi::UnexpectedUniFFICallbackError> for VcalmError {
    fn from(value: uniffi::UnexpectedUniFFICallbackError) -> Self {
        VcalmError::UnexpectedUniFFICallbackError(value.reason)
    }
}
