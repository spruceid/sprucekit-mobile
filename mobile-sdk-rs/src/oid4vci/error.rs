use oid4vci::credential::RequestError;
use ssi::{
    claims::data_integrity::DecodeError, claims::ProofValidationError, json_ld::FromContextMapError,
};

use crate::did::DidError;

use super::HttpClientError;

#[derive(thiserror::Error, uniffi::Error, Debug)]
#[uniffi(flat_error)]
pub enum Oid4vciError {
    #[error("Serde error")]
    SerdeJsonError(String),

    #[error("HTTP request error: {_0}")]
    RequestError(String),

    #[error("Unsupported grant type")]
    UnsupportedGrantType,

    #[error("Invalid session: {_0}")]
    InvalidSession(String),

    #[error("Invalid parameter: {_0}")]
    InvalidParameter(String),

    #[error("Failed to acquire lock for {_0}")]
    LockError(String),

    #[error("{vp_request}")]
    VpRequestRequired { vp_request: serde_json::Value },

    #[error("ProofValidationError: {_0}")]
    ProofValidationError(#[from] ProofValidationError),

    #[error("DecodeError: {_0}")]
    DecodeError(#[from] DecodeError),

    #[error("{_0}")]
    DidError(#[from] DidError),

    #[error("{_0}")]
    ContextMapError(#[from] FromContextMapError),

    #[error("{_0}")]
    Generic(String),
}

// TODO: some or all of these trait implementations can be converted to macros
impl From<String> for Oid4vciError {
    fn from(value: String) -> Self {
        Self::Generic(value)
    }
}

impl From<serde_json::Error> for Oid4vciError {
    fn from(_: serde_json::Error) -> Self {
        Oid4vciError::SerdeJsonError("".into())
    }
}

impl<RE> From<RequestError<RE>> for Oid4vciError
where
    RE: std::error::Error + 'static,
{
    fn from(value: RequestError<RE>) -> Self {
        if let RequestError::Response(_, ref body, _) = value {
            let maybe_json = serde_json::from_slice::<serde_json::Value>(body);
            if let Ok(serde_json::Value::Object(map)) = maybe_json {
                if let Some(vp_request) = map.get("authorization_request") {
                    return Oid4vciError::VpRequestRequired {
                        vp_request: vp_request.to_owned(),
                    };
                }
            }
        }

        if let RequestError::Parse(e) = &value {
            Oid4vciError::RequestError(format!("{value}: {e}"))
        } else {
            Oid4vciError::RequestError(value.to_string())
        }
    }
}

impl From<oid4vci::client::Error> for Oid4vciError {
    fn from(value: oid4vci::client::Error) -> Self {
        Oid4vciError::RequestError(value.to_string())
    }
}

impl From<HttpClientError> for Oid4vciError {
    fn from(value: HttpClientError) -> Self {
        Oid4vciError::RequestError(value.to_string())
    }
}
