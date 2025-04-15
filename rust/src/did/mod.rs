use ssi::dids::{document::DIDVerificationMethod, DIDBuf, DIDResolver};

pub use error::*;

mod error;

#[derive(Debug, uniffi::Enum)]
pub enum DidMethod {
    Jwk,
    Key,
}

impl DidMethod {
    pub fn did_from_jwk(&self, jwk: &str) -> Result<DIDBuf, DidError> {
        let key: ssi::jwk::JWK = serde_json::from_str(jwk)?;
        let did = match &self {
            DidMethod::Jwk => ssi::dids::DIDJWK::generate(&key),
            DidMethod::Key => ssi::dids::DIDKey::generate(&key)?,
        };
        Ok(did)
    }

    pub async fn vm_from_jwk(&self, jwk: &str) -> Result<DIDVerificationMethod, DidError> {
        let key: ssi::jwk::JWK = serde_json::from_str(jwk)?;
        let vm = match &self {
            DidMethod::Jwk => {
                let did = ssi::dids::DIDJWK::generate(&key);
                ssi::dids::DIDJWK
                    .resolve_into_any_verification_method(did.as_did())
                    .await?
                    // There will always be a verification method in `did:jwk`
                    .ok_or(DidError::MissingVerificationMethod)
            }
            DidMethod::Key => {
                let did = ssi::dids::DIDKey::generate(&key)?;
                ssi::dids::DIDKey
                    .resolve_into_any_verification_method(did.as_did())
                    .await?
                    // There will always be a verification method in `did:key`
                    .ok_or(DidError::MissingVerificationMethod)
            }
        }?;
        Ok(vm)
    }
}

#[derive(Debug, uniffi::Object)]
pub struct DidMethodUtils {
    inner: DidMethod,
}

#[uniffi::export]
impl DidMethodUtils {
    #[uniffi::constructor]
    pub fn new(method: DidMethod) -> Self {
        Self { inner: method }
    }

    pub fn did_from_jwk(&self, jwk: &str) -> Result<String, DidError> {
        self.inner.did_from_jwk(jwk).map(|d| d.to_string())
    }

    pub async fn vm_from_jwk(&self, jwk: &str) -> Result<String, DidError> {
        self.inner
            .vm_from_jwk(jwk)
            .await
            .map(|vm| vm.id.to_string())
    }
}
