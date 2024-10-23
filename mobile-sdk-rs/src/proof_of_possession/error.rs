#[derive(thiserror::Error, uniffi::Error, Debug)]
#[uniffi(flat_error)]
pub enum PopError {
    #[error("{_0}")]
    DidError(#[from] crate::did::DidError),

    #[error("{_0}")]
    UrlParseError(#[from] url::ParseError),

    #[error("{_0}")]
    DidUrlParseError(#[from] ssi::dids::InvalidDIDURL<String>),

    #[error("{_0}")]
    SerializationError(#[from] serde_json::Error),

    #[error("{_0}")]
    ConversionError(#[from] oid4vci::proof_of_possession::ConversionError),
}
