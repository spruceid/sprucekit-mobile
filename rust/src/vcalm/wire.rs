use vcalm_rs::exchange as vc;
use vcalm_rs::holder as vh;

/// Render an optional JSON value as its compact text form.
fn json_text(value: Option<serde_json::Value>) -> Option<String> {
    value.map(|v| v.to_string())
}

/// A verifiable-presentation-request (§3.4).
#[derive(Debug, uniffi::Record)]
pub struct Vpr {
    /// The presentation query/queries.
    pub query: Vec<Query>,
    /// The replay-protection nonce the VP proof must bind (§3.4.3.2).
    pub challenge: Option<String>,
    /// The verifier domain the VP proof must bind (§3.4.3.2).
    pub domain: Option<String>,
    /// Top-level `acceptedCryptosuites` (§3.4.3.1).
    pub accepted_cryptosuites: Option<Vec<CryptosuiteEntry>>,
    /// Top-level `acceptedEnvelopes`.
    pub accepted_envelopes: Option<Vec<EnvelopeEntry>>,
    /// Interaction hints, carried opaquely, as JSON text.
    pub interact: Option<String>,
}

impl From<vc::Vpr> for Vpr {
    fn from(v: vc::Vpr) -> Self {
        Self {
            query: v.query.into_iter().map(Into::into).collect(),
            challenge: v.challenge,
            domain: v.domain,
            accepted_cryptosuites: v
                .accepted_cryptosuites
                .map(|e| e.into_iter().map(Into::into).collect()),
            accepted_envelopes: v
                .accepted_envelopes
                .map(|e| e.into_iter().map(Into::into).collect()),
            interact: json_text(v.interact),
        }
    }
}

/// A single presentation query inside a [`Vpr`].
#[derive(Debug, uniffi::Record)]
pub struct Query {
    /// The query type(s), e.g. `["QueryByExample"]`.
    pub r#type: Vec<String>,
    /// The QueryByExample payload(s) (§3.4.2).
    pub credential_query: Vec<CredentialQuery>,
    /// §3.4.5 logical-operations group. Queries sharing a value are ANDed.
    pub group: Option<String>,
    /// §3.4.3.1 `required`. Absent is treated as `true` at the use site.
    pub required: Option<bool>,
    /// §3.4.3 `acceptedMethods`.
    pub accepted_methods: Option<Vec<AcceptedMethodEntry>>,
    /// §3.4.3.1 `acceptedCryptosuites` at the QUERY level.
    pub accepted_cryptosuites: Option<Vec<CryptosuiteEntry>>,
}

impl From<vc::Query> for Query {
    fn from(v: vc::Query) -> Self {
        Self {
            r#type: v.r#type,
            credential_query: v.credential_query.into_iter().map(Into::into).collect(),
            group: v.group,
            required: v.required,
            accepted_methods: v
                .accepted_methods
                .map(|e| e.into_iter().map(Into::into).collect()),
            accepted_cryptosuites: v
                .accepted_cryptosuites
                .map(|e| e.into_iter().map(Into::into).collect()),
        }
    }
}

/// A QueryByExample payload (§3.4.2).
#[derive(Debug, uniffi::Record)]
pub struct CredentialQuery {
    /// Human-readable justification shown in the consent UI.
    pub reason: Option<String>,
    /// The example credential the response must match, as JSON text.
    pub example: Option<String>,
    pub accepted_issuers: Option<Vec<AcceptedIssuerEntry>>,
    pub trusted_issuer: Option<Vec<AcceptedIssuerEntry>>,
    pub accepted_cryptosuites: Option<Vec<CryptosuiteEntry>>,
    pub accepted_envelopes: Option<Vec<EnvelopeEntry>>,
}

impl From<vc::CredentialQuery> for CredentialQuery {
    fn from(v: vc::CredentialQuery) -> Self {
        Self {
            reason: v.reason,
            example: json_text(v.example),
            accepted_issuers: v
                .accepted_issuers
                .map(|e| e.into_iter().map(Into::into).collect()),
            trusted_issuer: v
                .trusted_issuer
                .map(|e| e.into_iter().map(Into::into).collect()),
            accepted_cryptosuites: v
                .accepted_cryptosuites
                .map(|e| e.into_iter().map(Into::into).collect()),
            accepted_envelopes: v
                .accepted_envelopes
                .map(|e| e.into_iter().map(Into::into).collect()),
        }
    }
}

/// An RFC 9457 problem-details document (§3.8).
#[derive(Debug, uniffi::Record)]
pub struct ProblemDetails {
    /// The problem `type` URI.
    pub problem_type: String,
    pub status: Option<u16>,
    pub title: Option<String>,
    pub detail: Option<String>,
    pub instance: Option<String>,
}

impl From<vc::ProblemDetails> for ProblemDetails {
    fn from(v: vc::ProblemDetails) -> Self {
        Self {
            problem_type: v.problem_type,
            status: v.status,
            title: v.title,
            detail: v.detail,
            instance: v.instance,
        }
    }
}

// --- offer preview / requested fields ---------------------------------------

/// One credential in the current Offer, previewed for display.
#[derive(Debug, uniffi::Record)]
pub struct VcalmOfferedCredential {
    pub issuer: Option<String>,
    pub types: Vec<String>,
    /// The `credentialSubject` as JSON text.
    pub credential_subject: Option<String>,
    /// Read-only validity hint, derived before the user accepts.
    pub validity: OfferedValidity,
    /// The full offered VC as JSON text, so the host can persist it in its own
    /// wallet store.
    pub raw_credential: String,
}

impl From<vh::VcalmOfferedCredential> for VcalmOfferedCredential {
    fn from(v: vh::VcalmOfferedCredential) -> Self {
        Self {
            issuer: v.issuer,
            types: v.types,
            credential_subject: v.credential_subject,
            validity: v.validity.into(),
            raw_credential: v.raw_credential,
        }
    }
}

/// One field named by a QueryByExample `example`. Informational only.
#[derive(Debug, uniffi::Record)]
pub struct VcalmRequestedField {
    pub query_index: u32,
    /// Dotted path, e.g. `credentialSubject.givenName`.
    pub path: String,
    /// The example value; an `""` leaf renders as `"any value"`.
    pub value: String,
    pub required: bool,
    pub purpose: Option<String>,
}

impl From<vh::VcalmRequestedField> for VcalmRequestedField {
    fn from(v: vh::VcalmRequestedField) -> Self {
        Self {
            query_index: v.query_index,
            path: v.path,
            value: v.value,
            required: v.required,
            purpose: v.purpose,
        }
    }
}

// --- enums -------------------------------------------------------------------

/// The outcome of one step of the exchange.
#[derive(Debug, uniffi::Enum)]
pub enum StepResult {
    /// The server requests a verifiable presentation.
    Request { vpr: Vpr },
    /// The server offered credential(s), optionally with a follow-on request
    /// and/or a combined terminal redirect (§3.6).
    Offer {
        /// The offered `verifiablePresentation` envelope, as JSON text.
        vcs: String,
        next_vpr: Option<Vpr>,
        redirect_url: Option<String>,
    },
    /// A terminal redirect target. Surfaced as data — NEVER auto-followed.
    Redirect { url: String },
    /// The exchange completed with no further action.
    Complete,
    /// The server returned an RFC 9457 problem (a surfaced 4xx, not an error).
    Problem { details: ProblemDetails },
}

impl From<vc::StepResult> for StepResult {
    fn from(v: vc::StepResult) -> Self {
        match v {
            vc::StepResult::Request { vpr } => Self::Request { vpr: vpr.into() },
            vc::StepResult::Offer {
                vcs,
                next_vpr,
                redirect_url,
            } => Self::Offer {
                vcs: vcs.to_string(),
                next_vpr: next_vpr.map(Into::into),
                redirect_url,
            },
            vc::StepResult::Redirect { url } => Self::Redirect { url },
            vc::StepResult::Complete => Self::Complete,
            vc::StepResult::Problem { details } => Self::Problem {
                details: details.into(),
            },
        }
    }
}

/// The validity hint for one previewed offered credential.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum OfferedValidity {
    Valid,
    TimeBounded,
    ProofInvalid,
    Enveloped,
    UnsupportedProof,
    Unverifiable,
}

impl From<vh::OfferedValidity> for OfferedValidity {
    fn from(v: vh::OfferedValidity) -> Self {
        match v {
            vh::OfferedValidity::Valid => Self::Valid,
            vh::OfferedValidity::TimeBounded => Self::TimeBounded,
            vh::OfferedValidity::ProofInvalid => Self::ProofInvalid,
            vh::OfferedValidity::Enveloped => Self::Enveloped,
            vh::OfferedValidity::UnsupportedProof => Self::UnsupportedProof,
            vh::OfferedValidity::Unverifiable => Self::Unverifiable,
        }
    }
}

// The four entry enums keep their UNNAMED first variant. UniFFI names an unnamed
// field `v1`, and the platform matches on it (`CryptosuiteEntry.Name.v1`), so
// converting these to named fields would break the released API.

/// An `acceptedCryptosuites` entry: a bare name or an object.
#[derive(Debug, uniffi::Enum)]
pub enum CryptosuiteEntry {
    Name(String),
    Object { cryptosuite: String },
}

impl From<vc::CryptosuiteEntry> for CryptosuiteEntry {
    fn from(v: vc::CryptosuiteEntry) -> Self {
        match v {
            vc::CryptosuiteEntry::Name(name) => Self::Name(name),
            vc::CryptosuiteEntry::Object { cryptosuite } => Self::Object { cryptosuite },
        }
    }
}

/// An `acceptedEnvelopes` entry: a bare name or an object.
#[derive(Debug, uniffi::Enum)]
pub enum EnvelopeEntry {
    Name(String),
    Object { media_type: String },
}

impl From<vc::EnvelopeEntry> for EnvelopeEntry {
    fn from(v: vc::EnvelopeEntry) -> Self {
        match v {
            vc::EnvelopeEntry::Name(name) => Self::Name(name),
            vc::EnvelopeEntry::Object { media_type } => Self::Object { media_type },
        }
    }
}

/// An `acceptedIssuers` / `trustedIssuer` entry: a bare id or an object.
#[derive(Debug, uniffi::Enum)]
pub enum AcceptedIssuerEntry {
    Id(String),
    Object {
        id: Option<String>,
        issuer: Option<String>,
        /// `recognizedIn`, as JSON text.
        recognized_in: Option<String>,
    },
}

impl From<vc::AcceptedIssuerEntry> for AcceptedIssuerEntry {
    fn from(v: vc::AcceptedIssuerEntry) -> Self {
        match v {
            vc::AcceptedIssuerEntry::Id(id) => Self::Id(id),
            vc::AcceptedIssuerEntry::Object {
                id,
                issuer,
                recognized_in,
            } => Self::Object {
                id,
                issuer,
                recognized_in: json_text(recognized_in),
            },
        }
    }
}

/// An `acceptedMethods` entry: a bare name or an object.
#[derive(Debug, uniffi::Enum)]
pub enum AcceptedMethodEntry {
    Name(String),
    Object { method: String },
}

impl From<vc::AcceptedMethodEntry> for AcceptedMethodEntry {
    fn from(v: vc::AcceptedMethodEntry) -> Self {
        match v {
            vc::AcceptedMethodEntry::Name(name) => Self::Name(name),
            vc::AcceptedMethodEntry::Object { method } => Self::Object { method },
        }
    }
}

/// Errors from a VCALM exchange.
#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum VcalmError {
    #[error("Network error: {0}")]
    Network(String),
    #[error("Failed to deserialize response: {0}")]
    Deserialization(String),
    #[error("Discovery response did not advertise a `vcapi` protocol")]
    NoVcapiProtocol,
    #[error("Malformed problem-details in {status} response")]
    MalformedProblemDetails { status: u16, body: String },
    #[error("Server returned error status {status}")]
    ServerError { status: u16, body: String },
    #[error("host capability failed: {0}")]
    Port(String),
    #[error("presentation failed: {0}")]
    Presentation(String),
    #[error("the offer carried no verifiable credentials")]
    NoOfferedCredentials,
    #[error("offered credential #{index} failed cryptographic proof verification")]
    InvalidCredentialProof { index: u32 },
    #[error("invalid session state: {0}")]
    SessionState(String),
    #[error("insecure URL rejected: {0}")]
    InsecureUrl(String),
    #[error("response body exceeded the {limit_bytes}-byte limit")]
    ResponseTooLarge { limit_bytes: u64 },
    #[error("VPR domain ({domain}) does not match the exchange channel host ({channel})")]
    DomainChannelMismatch { domain: String, channel: String },
    #[error("no supported cryptosuite among the VPR's acceptedCryptosuites: {accepted}")]
    NoAcceptedCryptosuite { accepted: String },
    #[error("no supported DID method among the VPR's acceptedMethods: {accepted}")]
    NoAcceptedDidMethod { accepted: String },
    #[error("selected credentials mix VCDM v1 and v2; present one data-model version at a time")]
    MixedCredentialVersions,
    #[error("credential carries no presentable proof (B.1 allowlist): {credential_types}")]
    NoPresentableProof { credential_types: String },
    #[error("selective-disclosure derive failed: {0}")]
    SdDeriveFailed(String),
    #[error("unsupported credential format: {0}")]
    UnsupportedCredentialFormat(String),
}

impl From<vcalm_rs::error::VcalmError> for VcalmError {
    fn from(e: vcalm_rs::error::VcalmError) -> Self {
        use vcalm_rs::error::VcalmError as E;
        match e {
            E::Network(v) => Self::Network(v),
            E::Deserialization(v) => Self::Deserialization(v),
            E::NoVcapiProtocol => Self::NoVcapiProtocol,
            E::MalformedProblemDetails { status, body } => {
                Self::MalformedProblemDetails { status, body }
            }
            E::ServerError { status, body } => Self::ServerError { status, body },
            E::Port(v) => Self::Port(v),
            E::Presentation(v) => Self::Presentation(v),
            E::NoOfferedCredentials => Self::NoOfferedCredentials,
            E::InvalidCredentialProof { index } => Self::InvalidCredentialProof { index },
            E::SessionState(v) => Self::SessionState(v),
            E::InsecureUrl(v) => Self::InsecureUrl(v),
            E::ResponseTooLarge { limit_bytes } => Self::ResponseTooLarge { limit_bytes },
            E::DomainChannelMismatch { domain, channel } => {
                Self::DomainChannelMismatch { domain, channel }
            }
            E::NoAcceptedCryptosuite { accepted } => Self::NoAcceptedCryptosuite { accepted },
            E::NoAcceptedDidMethod { accepted } => Self::NoAcceptedDidMethod { accepted },
            E::MixedCredentialVersions => Self::MixedCredentialVersions,
            E::NoPresentableProof { credential_types } => {
                Self::NoPresentableProof { credential_types }
            }
            E::SdDeriveFailed(v) => Self::SdDeriveFailed(v),
            E::UnsupportedCredentialFormat(v) => Self::UnsupportedCredentialFormat(v),
        }
    }
}
