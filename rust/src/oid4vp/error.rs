// use super::request_signer::RequestSignerError;

use crate::credential::CredentialEncodingError;

use super::{permission_request::PermissionRequestError, presentation::PresentationError};

/// The [OID4VPError] enum represents the errors that can occur
/// when using the oid4vp foreign library.
#[derive(thiserror::Error, Debug, uniffi::Error)]
pub enum OID4VPError {
    #[error("An unexpected foreign callback error occurred: {0}")]
    UnexpectedUniFFICallbackError(String),
    #[error("Failed to validate the OID4VP Request: {0}")]
    RequestValidation(String),
    #[error("Failed to resolve the presentation definition: {0}")]
    PresentationDefinitionResolution(String),
    #[error("Failed to create verifiable presentation token: {0}")]
    Token(String),
    #[error("Unsupported Response Mode for OID4VP Request: {0}")]
    UnsupportedResponseMode(String),
    #[error("Failed to submit OID4VP response: {0}")]
    ResponseSubmission(String),
    #[error("Credential callback error: {0}")]
    CredentialCallback(String),
    #[error("Failed to create presentation submission: {0}")]
    PresentationSubmissionCreation(String),
    #[error("Failed to parse DID url: {0}")]
    InvalidDIDUrl(String),
    #[error("Failed to generate DID key URL: {0}")]
    DIDKeyGenerateUrl(String),
    #[error("Failed to parse JSON syntax: {0}")]
    JsonSyntaxParse(String),
    #[error(transparent)]
    VdcCollection(#[from] crate::vdc_collection::VdcCollectionError),
    #[error("HTTP Client Initialization Error: {0}")]
    HttpClientInitialization(String),
    #[error("Signing algorithm not found: {0}")]
    SigningAlgorithmNotFound(String),
    #[error("Unsupported Client ID Scheme: {0}")]
    InvalidClientIdScheme(String),
    #[error("Invalid Descriptor Not Found")]
    InputDescriptorNotFound,
    #[error("Failed to parse credential into verifiable presentation token: {0}")]
    VpTokenParse(String),
    #[error("Failed to create verifiable presentation token: {0}")]
    VpTokenCreate(String),
    #[error("JWK Parse Error: {0}")]
    JwkParse(String),
    #[error("VDC collection is not initialized")]
    VdcCollectionNotInitialized,
    #[error("Failed to find a current authorization request for permission response")]
    AuthorizationRequestNotFound,
    #[error("Request signer not found")]
    RequestSignerNotFound,
    #[error("Failed to initialize metadata: {0}")]
    MetadataInitialization(String),
    #[error(transparent)]
    PermissionRequest(#[from] PermissionRequestError),
    #[error(transparent)]
    Presentation(#[from] PresentationError),
    #[error(transparent)]
    CredentialEncoding(#[from] CredentialEncodingError),
    #[error("Failed to parse JsonPath: {0}")]
    JsonPathParse(String),
    #[error("Failed to resolve JsonPath: {0}")]
    JsonPathResolve(String),
    #[error("Unable to convert JsonPath: {0} to JsonPointer")]
    JsonPathToPointer(String),
    #[error("Limit disclosure: {0}")]
    LimitDisclosure(String),
    #[error("Empty Credential Subject. Failed to convert `Object` to `NonEmptyObject`: {0}")]
    EmptyCredentialSubject(String),
    #[error("Invalid fields selected for selective disclosure")]
    SelectiveDisclosureInvalidFields,
    #[error("Selected fields cannot be empty")]
    SelectiveDisclosureEmptySelection,
    #[error("Failed to initialize metadata: {0}")]
    Debug(String),
}

// Handle unexpected errors when calling a foreign callback
impl From<uniffi::UnexpectedUniFFICallbackError> for OID4VPError {
    fn from(value: uniffi::UnexpectedUniFFICallbackError) -> Self {
        OID4VPError::UnexpectedUniFFICallbackError(value.reason)
    }
}
