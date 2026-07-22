use std::sync::Arc;

use serde::{Deserialize, Serialize};

uniffi::custom_newtype!(KeyAlias, String);
#[derive(Debug, Serialize, Deserialize, PartialEq, Clone)]
pub struct KeyAlias(pub String);

#[derive(Debug, uniffi::Error, thiserror::Error)]
pub enum CryptoError {
    #[error("{0}")]
    General(String),
}

impl CryptoError {
    pub fn general(e: impl ToString) -> Self {
        Self::General(e.to_string())
    }
}

impl From<anyhow::Error> for CryptoError {
    fn from(value: anyhow::Error) -> Self {
        Self::General(format!("{value:#}"))
    }
}

type Result<T, E = CryptoError> = ::std::result::Result<T, E>;

#[uniffi::export(with_foreign)]
/// An interface that can provide access to cryptographic keypairs from the native crypto API.
pub trait KeyStore: Send + Sync {
    /// Retrieve a cryptographic keypair by alias. The cryptographic key must be usable for
    /// creating digital signatures, and must not be usable for encryption.
    fn get_signing_key(&self, alias: KeyAlias) -> Result<Arc<dyn SigningKey>>;
}

#[uniffi::export(with_foreign)]
/// A cryptographic keypair that can be used for signing.
pub trait SigningKey: Send + Sync {
    /// Generates a public JWK for this key.
    fn jwk(&self) -> Result<String>;
    /// Produces a signature of unknown encoding.
    fn sign(&self, payload: Vec<u8>) -> Result<Vec<u8>>;
}
