use std::{fmt, sync::Arc};

use serde::{Deserialize, Serialize};

uniffi::custom_newtype!(KeyAlias, String);
#[derive(Debug, Serialize, Deserialize, PartialEq, Clone)]
pub struct KeyAlias(pub String);

#[derive(Debug, uniffi::Error)]
pub enum CryptoError {
    General(String),
}

impl From<anyhow::Error> for CryptoError {
    fn from(value: anyhow::Error) -> Self {
        Self::General(format!("{value:#}"))
    }
}

impl std::error::Error for CryptoError {}

impl fmt::Display for CryptoError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        let Self::General(cause) = self;
        write!(f, "{}", cause)
    }
}

type Result<T, E = CryptoError> = ::std::result::Result<T, E>;

#[uniffi::export(with_foreign)]
/// An interface that can provide access to cryptographic keypairs from the native crypto API.
pub trait KeyManager: Send + Sync {
    /// Retrieve a cryptographic keypair by alias. The cryptographic key must be usable for
    /// creating digital signatures, and must not be usable for encryption.
    fn get_signing_key(&self, alias: KeyAlias) -> Result<Arc<dyn SigningKey>>;
}

#[uniffi::export(with_foreign)]
/// A cryptographic keypair that can be used for signing.
pub trait SigningKey: Send + Sync {
    /// Generates a public JWK for this key.
    fn jwk(&self) -> Result<String>;
    /// Produces a raw (not DER encoded) signature.
    fn sign(&self, payload: Vec<u8>) -> Result<Vec<u8>>;
}

#[cfg(test)]
pub(crate) use test::*;

#[cfg(test)]
mod test {
    use crate::{local_store::LocalStore, storage_manager::StorageManagerInterface, Key, Value};
    use anyhow::Context;

    use super::*;

    #[derive(Debug, Default, Clone)]
    pub(crate) struct RustTestKeyManager(LocalStore);

    impl RustTestKeyManager {
        pub async fn generate_p256_signing_key(&self, alias: KeyAlias) -> Result<()> {
            let key = Key(alias.0);
            if self
                .0
                .get(key.clone())
                .await
                .context("storage error")?
                .is_some()
            {
                return Ok(());
            }

            let jwk_string =
                p256::SecretKey::random(&mut ssi::crypto::rand::thread_rng()).to_jwk_string();

            self.0
                .add(key, Value(jwk_string.as_bytes().to_vec()))
                .await
                .context("storage error")?;

            Ok(())
        }
    }

    impl KeyManager for RustTestKeyManager {
        fn get_signing_key(&self, alias: KeyAlias) -> Result<Arc<dyn SigningKey>> {
            let key = Key(alias.0);

            let fut = self.0.get(key.clone());

            let outcome = futures::executor::block_on(fut);

            let Value(jwk_bytes) = outcome.context("storage error")?.context("key not found")?;

            let jwk_str = String::from_utf8_lossy(&jwk_bytes);

            let sk = p256::SecretKey::from_jwk_str(&jwk_str).context("key could not be parsed")?;

            Ok(Arc::new(RustTestSigningKey(sk)))
        }
    }

    pub(crate) struct RustTestSigningKey(p256::SecretKey);

    impl SigningKey for RustTestSigningKey {
        fn jwk(&self) -> Result<String> {
            Ok(self.0.public_key().to_jwk_string())
        }

        fn sign(&self, payload: Vec<u8>) -> Result<Vec<u8>> {
            use p256::ecdsa::signature::Signer;
            let signature: p256::ecdsa::Signature =
                p256::ecdsa::SigningKey::from(&self.0).sign(&payload);
            Ok(signature.to_vec())
        }
    }
}
