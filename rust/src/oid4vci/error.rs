use oid4vci::offer::CredentialOfferError;

#[derive(thiserror::Error, uniffi::Error, Debug)]
#[uniffi(flat_error)]
pub enum Oid4vciError {
    #[error("invalid URI")]
    InvalidUri,

    #[error("{0}")]
    CredentialOffer(#[from] CredentialOfferError),

    #[error("{authorization_request}")]
    PresentationRequired { authorization_request: String },

    #[error("{0}")]
    Client(#[from] oid4vci::client::ClientError),

    #[error("already proceeded")]
    AlreadyProceeded,

    #[error("proof signature failed: {0}")]
    SignatureFailed(#[from] ssi::claims::SignatureError),

    #[error("requested credential is undefined")]
    UndefinedCredential,

    #[error("invalid credential payload")]
    InvalidCredentialPayload,
}

impl From<oid4vci::oauth2::url::ParseError> for Oid4vciError {
    fn from(_value: oid4vci::oauth2::url::ParseError) -> Self {
        Self::InvalidUri
    }
}

impl Oid4vciError {
    pub(crate) fn client_other(message: impl Into<String>) -> Self {
        Self::Client(oid4vci::client::ClientError::Other(message.into()))
    }

    pub(crate) fn from_response_body(body: &[u8]) -> Option<Self> {
        let json = serde_json::from_slice::<serde_json::Value>(body).ok()?;
        let authorization_request = json.get("authorization_request")?;

        Some(Self::PresentationRequired {
            authorization_request: serde_json::to_string(authorization_request).ok()?,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::Oid4vciError;

    #[test]
    fn extracts_authorization_request_from_response_body() {
        let body = br#"{
            "error": "presentation_required",
            "authorization_request": {
                "response_type": "vp_token",
                "nonce": "abc123"
            }
        }"#;

        let error = Oid4vciError::from_response_body(body)
            .expect("presentation_required body should be detected");

        match error {
            Oid4vciError::PresentationRequired {
                authorization_request,
            } => {
                assert!(authorization_request.contains("\"response_type\":\"vp_token\""));
                assert!(authorization_request.contains("\"nonce\":\"abc123\""));
            }
            other => panic!("unexpected error variant: {other:?}"),
        }
    }
}

// use ssi::{
//     claims::data_integrity::DecodeError, claims::ProofValidationError, json_ld::FromContextMapError,
// };

// use crate::did::DidError;

// use super::HttpClientError;

// #[derive(thiserror::Error, uniffi::Error, Debug)]
// #[uniffi(flat_error)]
// pub enum Oid4vciError {
//     #[error("Serde error")]
//     SerdeJsonError(String),

//     #[error("HTTP request error: {_0}")]
//     RequestError(String),

//     #[error("Unsupported grant type")]
//     UnsupportedGrantType,

//     #[error("Invalid session: {_0}")]
//     InvalidSession(String),

//     #[error("Invalid parameter: {_0}")]
//     InvalidParameter(String),

//     #[error("Failed to acquire lock for {_0}")]
//     LockError(String),

//     #[error("{vp_request}")]
//     VpRequestRequired { vp_request: serde_json::Value },

//     #[error("ProofValidationError: {_0}")]
//     ProofValidationError(#[from] ProofValidationError),

//     #[error("DecodeError: {_0}")]
//     DecodeError(#[from] DecodeError),

//     #[error("{_0}")]
//     DidError(#[from] DidError),

//     #[error("{_0}")]
//     ContextMapError(#[from] FromContextMapError),

//     #[error("{_0}")]
//     Generic(String),
// }

// // TODO: some or all of these trait implementations can be converted to macros
// impl From<String> for Oid4vciError {
//     fn from(value: String) -> Self {
//         Self::Generic(value)
//     }
// }

// impl From<serde_json::Error> for Oid4vciError {
//     fn from(_: serde_json::Error) -> Self {
//         Oid4vciError::SerdeJsonError("".into())
//     }
// }

// impl<RE> From<RequestError<RE>> for Oid4vciError
// where
//     RE: std::error::Error + 'static,
// {
//     fn from(value: RequestError<RE>) -> Self {
//         if let RequestError::Response(_, ref body, _) = value {
//             let maybe_json = serde_json::from_slice::<serde_json::Value>(body);
//             if let Ok(serde_json::Value::Object(map)) = maybe_json {
//                 if let Some(vp_request) = map.get("authorization_request") {
//                     return Oid4vciError::VpRequestRequired {
//                         vp_request: vp_request.to_owned(),
//                     };
//                 }
//             }
//         }

//         if let RequestError::Parse(e) = &value {
//             Oid4vciError::RequestError(format!("{value}: {e}"))
//         } else {
//             Oid4vciError::RequestError(value.to_string())
//         }
//     }
// }

// impl From<oid4vci::client::Error> for Oid4vciError {
//     fn from(value: oid4vci::client::Error) -> Self {
//         Oid4vciError::RequestError(value.to_string())
//     }
// }

// impl From<HttpClientError> for Oid4vciError {
//     fn from(value: HttpClientError) -> Self {
//         Oid4vciError::RequestError(value.to_string())
//     }
// }
