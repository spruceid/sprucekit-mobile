use ssi::{claims::jws::JwsSigner, JWK};

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum RequestSignerError {
    #[error("Unsupported algorithm")]
    UnsupportedAlgorithm,
    #[error("Failed to sign the request")]
    SigningError,
}

#[uniffi::export(with_foreign)]
#[async_trait::async_trait]
pub trait RequestSignerInterface: Send + Sync + std::fmt::Debug {
    /// Return the algorithm used to sign the request
    fn alg(&self) -> Result<String, RequestSignerError>;

    /// Return the JWK public key
    fn jwk(&self) -> Result<String, RequestSignerError>;

    /// Sign the request
    async fn try_sign(&self, payload: Vec<u8>) -> Result<Vec<u8>, RequestSignerError>;
}

#[derive(Debug, Clone)]
pub(crate) struct ExampleRequestSigner {
    jwk: JWK,
}

impl Default for ExampleRequestSigner {
    fn default() -> Self {
        Self {
            jwk: JWK::generate_ed25519().unwrap(),
        }
    }
}

#[async_trait::async_trait]
impl RequestSignerInterface for ExampleRequestSigner {
    fn alg(&self) -> Result<String, RequestSignerError> {
        self.jwk
            .algorithm
            .map(|alg| alg.to_string())
            .ok_or(RequestSignerError::UnsupportedAlgorithm)
    }

    fn jwk(&self) -> Result<String, RequestSignerError> {
        serde_json::to_string(&self.jwk).map_err(|_| RequestSignerError::SigningError)
    }

    async fn try_sign(&self, payload: Vec<u8>) -> Result<Vec<u8>, RequestSignerError> {
        self.jwk
            .sign(&payload)
            .await
            .map(|sig| sig.as_bytes().to_vec())
            .map_err(|_| RequestSignerError::SigningError)
    }
}
