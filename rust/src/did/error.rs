#[derive(thiserror::Error, uniffi::Error, Debug)]
#[uniffi(flat_error)]
pub enum DidError {
    #[error("{_0}")]
    SerializationError(#[from] serde_json::Error),

    #[error("{_0}")]
    GenerateError(#[from] ssi::jwk::ToMulticodecError),

    #[error("{_0}")]
    ResolutionError(#[from] ssi::dids::resolution::Error),

    #[error("DID document is missing a verification method")]
    MissingVerificationMethod,
}
