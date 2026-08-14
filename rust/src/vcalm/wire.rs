/// FFI wrappers for VCALM's wire types.
use std::sync::Arc;

use vcalm_rs::exchange as vc;
use vcalm_rs::holder as vh;

/// Render an optional JSON value as its compact text form.
fn json_text(value: Option<serde_json::Value>) -> Option<String> {
    value.map(|v| v.to_string())
}

/// A verifiable-presentation-request (§3.4).
#[derive(Debug, uniffi::Object)]
pub struct Vpr(pub(crate) vc::Vpr);

#[uniffi::export]
impl Vpr {
    /// The presentation query/queries.
    pub fn query(&self) -> Vec<Arc<Query>> {
        self.0
            .query
            .iter()
            .cloned()
            .map(|q| Arc::new(Query(q)))
            .collect()
    }

    /// The replay-protection nonce the VP proof must bind (§3.4.3.2).
    pub fn challenge(&self) -> Option<String> {
        self.0.challenge.clone()
    }

    /// The verifier domain the VP proof must bind (§3.4.3.2).
    pub fn domain(&self) -> Option<String> {
        self.0.domain.clone()
    }

    /// Top-level `acceptedCryptosuites` (§3.4.3.1).
    pub fn accepted_cryptosuites(&self) -> Option<Vec<CryptosuiteEntry>> {
        self.0
            .accepted_cryptosuites
            .clone()
            .map(|e| e.into_iter().map(Into::into).collect())
    }

    /// Top-level `acceptedEnvelopes`.
    pub fn accepted_envelopes(&self) -> Option<Vec<EnvelopeEntry>> {
        self.0
            .accepted_envelopes
            .clone()
            .map(|e| e.into_iter().map(Into::into).collect())
    }

    /// Interaction hints, carried opaquely, as JSON text.
    pub fn interact(&self) -> Option<String> {
        json_text(self.0.interact.clone())
    }
}

/// A single presentation query inside a [`Vpr`].
#[derive(Debug, uniffi::Object)]
pub struct Query(pub(crate) vc::Query);

#[uniffi::export]
impl Query {
    /// The query type(s), e.g. `["QueryByExample"]`.
    pub fn r#type(&self) -> Vec<String> {
        self.0.r#type.clone()
    }

    /// The QueryByExample payload(s) (§3.4.2).
    pub fn credential_query(&self) -> Vec<Arc<CredentialQuery>> {
        self.0
            .credential_query
            .iter()
            .cloned()
            .map(|c| Arc::new(CredentialQuery(c)))
            .collect()
    }

    /// §3.4.5 logical-operations group. Queries sharing a value are ANDed.
    pub fn group(&self) -> Option<String> {
        self.0.group.clone()
    }

    /// §3.4.3.1 `required`. Absent is treated as `true` at the use site.
    pub fn required(&self) -> Option<bool> {
        self.0.required
    }

    /// §3.4.3 `acceptedMethods`.
    pub fn accepted_methods(&self) -> Option<Vec<AcceptedMethodEntry>> {
        self.0
            .accepted_methods
            .clone()
            .map(|e| e.into_iter().map(Into::into).collect())
    }

    /// §3.4.3.1 `acceptedCryptosuites` at the QUERY level.
    pub fn accepted_cryptosuites(&self) -> Option<Vec<CryptosuiteEntry>> {
        self.0
            .accepted_cryptosuites
            .clone()
            .map(|e| e.into_iter().map(Into::into).collect())
    }
}

/// A QueryByExample payload (§3.4.2).
#[derive(Debug, uniffi::Object)]
pub struct CredentialQuery(pub(crate) vc::CredentialQuery);

#[uniffi::export]
impl CredentialQuery {
    /// Human-readable justification shown in the consent UI.
    pub fn reason(&self) -> Option<String> {
        self.0.reason.clone()
    }

    /// The example credential the response must match, as JSON text.
    pub fn example(&self) -> Option<String> {
        json_text(self.0.example.clone())
    }

    pub fn accepted_issuers(&self) -> Option<Vec<AcceptedIssuerEntry>> {
        self.0
            .accepted_issuers
            .clone()
            .map(|e| e.into_iter().map(Into::into).collect())
    }

    pub fn trusted_issuer(&self) -> Option<Vec<AcceptedIssuerEntry>> {
        self.0
            .trusted_issuer
            .clone()
            .map(|e| e.into_iter().map(Into::into).collect())
    }

    pub fn accepted_cryptosuites(&self) -> Option<Vec<CryptosuiteEntry>> {
        self.0
            .accepted_cryptosuites
            .clone()
            .map(|e| e.into_iter().map(Into::into).collect())
    }

    pub fn accepted_envelopes(&self) -> Option<Vec<EnvelopeEntry>> {
        self.0
            .accepted_envelopes
            .clone()
            .map(|e| e.into_iter().map(Into::into).collect())
    }
}

/// An RFC 9457 problem-details document (§3.8).
#[derive(Debug, uniffi::Object)]
pub struct ProblemDetails(pub(crate) vc::ProblemDetails);

#[uniffi::export]
impl ProblemDetails {
    /// The problem `type` URI.
    pub fn problem_type(&self) -> String {
        self.0.problem_type.clone()
    }
    pub fn status(&self) -> Option<u16> {
        self.0.status
    }
    pub fn title(&self) -> Option<String> {
        self.0.title.clone()
    }
    pub fn detail(&self) -> Option<String> {
        self.0.detail.clone()
    }
    pub fn instance(&self) -> Option<String> {
        self.0.instance.clone()
    }
}

/// One credential in the current Offer, previewed for display.
#[derive(Debug, uniffi::Object)]
pub struct VcalmOfferedCredential(pub(crate) vh::VcalmOfferedCredential);

#[uniffi::export]
impl VcalmOfferedCredential {
    pub fn issuer(&self) -> Option<String> {
        self.0.issuer.clone()
    }
    pub fn types(&self) -> Vec<String> {
        self.0.types.clone()
    }
    /// The `credentialSubject` as JSON text.
    pub fn credential_subject(&self) -> Option<String> {
        self.0.credential_subject.clone()
    }
    /// Read-only validity hint, derived before the user accepts.
    pub fn validity(&self) -> OfferedValidity {
        self.0.validity.into()
    }
    /// The full offered VC as JSON text, so the host can persist it in its own
    /// wallet store.
    pub fn raw_credential(&self) -> String {
        self.0.raw_credential.clone()
    }
}

/// One field named by a QueryByExample `example`. Informational only.
#[derive(Debug, uniffi::Object)]
pub struct VcalmRequestedField(pub(crate) vh::VcalmRequestedField);

#[uniffi::export]
impl VcalmRequestedField {
    pub fn query_index(&self) -> u32 {
        self.0.query_index
    }
    /// Dotted path, e.g. `credentialSubject.givenName`.
    pub fn path(&self) -> String {
        self.0.path.clone()
    }
    /// The example value; an `""` leaf renders as `"any value"`.
    pub fn value(&self) -> String {
        self.0.value.clone()
    }
    pub fn required(&self) -> bool {
        self.0.required
    }
    pub fn purpose(&self) -> Option<String> {
        self.0.purpose.clone()
    }
}

// --- enums (re-declared shells) ---------------------------------------------

/// The outcome of one step of the exchange.
#[derive(Debug, uniffi::Enum)]
pub enum StepResult {
    /// The server requests a verifiable presentation.
    Request { vpr: Arc<Vpr> },
    /// The server offered credential(s), optionally with a follow-on request
    /// and/or a combined terminal redirect (§3.6).
    Offer {
        /// The offered `verifiablePresentation` envelope, as JSON text.
        vcs: String,
        next_vpr: Option<Arc<Vpr>>,
        redirect_url: Option<String>,
    },
    /// A terminal redirect target. Surfaced as data — NEVER auto-followed.
    Redirect { url: String },
    /// The exchange completed with no further action.
    Complete,
    /// The server returned an RFC 9457 problem (a surfaced 4xx, not an error).
    Problem { details: Arc<ProblemDetails> },
}

impl From<vc::StepResult> for StepResult {
    fn from(v: vc::StepResult) -> Self {
        match v {
            vc::StepResult::Request { vpr } => Self::Request {
                vpr: Arc::new(Vpr(vpr)),
            },
            vc::StepResult::Offer {
                vcs,
                next_vpr,
                redirect_url,
            } => Self::Offer {
                vcs: vcs.to_string(),
                next_vpr: next_vpr.map(|v| Arc::new(Vpr(v))),
                redirect_url,
            },
            vc::StepResult::Redirect { url } => Self::Redirect { url },
            vc::StepResult::Complete => Self::Complete,
            vc::StepResult::Problem { details } => Self::Problem {
                details: Arc::new(ProblemDetails(details)),
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

/// An `acceptedCryptosuites` entry: a bare name or an object.
#[derive(Debug, Clone, uniffi::Enum)]
pub enum CryptosuiteEntry {
    Name { name: String },
    Object { cryptosuite: String },
}

impl From<vc::CryptosuiteEntry> for CryptosuiteEntry {
    fn from(v: vc::CryptosuiteEntry) -> Self {
        match v {
            vc::CryptosuiteEntry::Name(name) => Self::Name { name },
            vc::CryptosuiteEntry::Object { cryptosuite } => Self::Object { cryptosuite },
        }
    }
}

/// An `acceptedEnvelopes` entry: a bare name or an object.
#[derive(Debug, Clone, uniffi::Enum)]
pub enum EnvelopeEntry {
    Name { name: String },
    Object { media_type: String },
}

impl From<vc::EnvelopeEntry> for EnvelopeEntry {
    fn from(v: vc::EnvelopeEntry) -> Self {
        match v {
            vc::EnvelopeEntry::Name(name) => Self::Name { name },
            vc::EnvelopeEntry::Object { media_type } => Self::Object { media_type },
        }
    }
}

/// An `acceptedIssuers` / `trustedIssuer` entry: a bare id or an object.
#[derive(Debug, Clone, uniffi::Enum)]
pub enum AcceptedIssuerEntry {
    Id {
        id: String,
    },
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
            vc::AcceptedIssuerEntry::Id(id) => Self::Id { id },
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
#[derive(Debug, Clone, uniffi::Enum)]
pub enum AcceptedMethodEntry {
    Name { name: String },
    Object { method: String },
}

/// Errors from a VCALM exchange.
#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum VcalmError {
    #[error("An unexpected foreign callback error occurred: {0}")]
    UnexpectedUniFFICallbackError(String),
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
            E::UnexpectedUniFFICallbackError(v) => Self::UnexpectedUniFFICallbackError(v),
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

impl From<uniffi::UnexpectedUniFFICallbackError> for VcalmError {
    fn from(value: uniffi::UnexpectedUniFFICallbackError) -> Self {
        Self::UnexpectedUniFFICallbackError(value.reason)
    }
}

impl From<vc::AcceptedMethodEntry> for AcceptedMethodEntry {
    fn from(v: vc::AcceptedMethodEntry) -> Self {
        match v {
            vc::AcceptedMethodEntry::Name(name) => Self::Name { name },
            vc::AcceptedMethodEntry::Object { method } => Self::Object { method },
        }
    }
}
