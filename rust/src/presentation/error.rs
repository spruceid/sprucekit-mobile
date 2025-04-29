#[derive(thiserror::Error, uniffi::Error, Debug)]
#[uniffi(flat_error)]
pub enum PresentationBuilderError {
    #[error("DidError: {_0}")]
    DidError(#[from] crate::did::DidError),

    #[error("ParseError: {_0}")]
    UrlParseError(#[from] url::ParseError),

    #[error("InvalidDIDURL: {_0}")]
    DidUrlParseError(#[from] ssi::dids::InvalidDIDURL<String>),

    #[error("SerializationError: {_0}")]
    SerializationError(#[from] serde_json::Error),

    #[error("ConversionError: {_0}")]
    ConversionError(#[from] oid4vci::proof_of_possession::ConversionError),

    #[error("InvalidIri: {_0}")]
    IriBufError(#[from] ssi::json_ld::iref::InvalidIri<std::string::String>),

    #[error("InvalidUri: {_0}")]
    UriBufError(#[from] ssi::json_ld::iref::InvalidUri<std::string::String>),

    #[error("Context: {_0}")]
    Context(String),

    #[error("SignatureError: {_0}")]
    SignatureError(#[from] ssi::claims::SignatureError),

    #[error("Unable to pick signing suite for verification method")]
    SigningSuitePickError,

    #[error("Unsupported credential format for json-ld presentation")]
    UnsupportedCredentialFormat,
}
