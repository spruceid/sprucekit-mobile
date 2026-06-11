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
    #[error("an offered credential failed cryptographic proof verification")]
    InvalidCredentialProof,
}

// Handle unexpected errors when calling a foreign callback.
impl From<uniffi::UnexpectedUniFFICallbackError> for VcalmError {
    fn from(value: uniffi::UnexpectedUniFFICallbackError) -> Self {
        VcalmError::UnexpectedUniFFICallbackError(value.reason)
    }
}
