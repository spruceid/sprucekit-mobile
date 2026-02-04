use async_trait::async_trait;
use ssi::claims::jws::JwsSigner as _;

use crate::jwk::{Jwk, JwkAlgorithm};

#[uniffi::export(with_foreign)]
#[async_trait]
pub trait JwsSigner: Send + Sync {
    async fn fetch_info(&self) -> Result<JwsSignerInfo, JwsSignatureError>;

    async fn sign_bytes(&self, signing_bytes: Vec<u8>) -> Result<Vec<u8>, JwsSignatureError>;
}

#[uniffi::export]
#[async_trait]
impl JwsSigner for Jwk {
    async fn fetch_info(&self) -> Result<JwsSignerInfo, JwsSignatureError> {
        ssi::JWK::fetch_info(&*self.0.read().await)
            .await
            .map(Into::into)
            .map_err(Into::into)
    }

    async fn sign_bytes(&self, signing_bytes: Vec<u8>) -> Result<Vec<u8>, JwsSignatureError> {
        self.0
            .read()
            .await
            .sign_bytes(signing_bytes.as_slice())
            .await
            .map_err(Into::into)
    }
}

impl ssi::claims::jws::JwsSigner for dyn '_ + JwsSigner {
    #[allow(async_fn_in_trait)]
    async fn fetch_info(
        &self,
    ) -> Result<ssi::claims::jws::JwsSignerInfo, ssi::claims::SignatureError> {
        JwsSigner::fetch_info(self)
            .await
            .map(Into::into)
            .map_err(Into::into)
    }

    #[allow(async_fn_in_trait)]
    async fn sign_bytes(
        &self,
        signing_bytes: &[u8],
    ) -> Result<Vec<u8>, ssi::claims::SignatureError> {
        JwsSigner::sign_bytes(self, signing_bytes.to_owned())
            .await
            .map_err(Into::into)
    }
}

#[derive(uniffi::Record)]
pub struct JwsSignerInfo {
    pub key_id: Option<String>,
    pub algorithm: JwkAlgorithm,
}

impl From<JwsSignerInfo> for ssi::claims::jws::JwsSignerInfo {
    fn from(value: JwsSignerInfo) -> Self {
        Self {
            key_id: value.key_id,
            algorithm: value.algorithm.into(),
        }
    }
}

impl From<ssi::claims::jws::JwsSignerInfo> for JwsSignerInfo {
    fn from(value: ssi::claims::jws::JwsSignerInfo) -> Self {
        Self {
            key_id: value.key_id,
            algorithm: value.algorithm.into(),
        }
    }
}

#[derive(thiserror::Error, uniffi::Error, Debug)]
#[uniffi(flat_error)]
pub enum JwsSignatureError {
    #[error("missing signature algorithm")]
    MissingAlgorithm,

    #[error("algorithm mismatch")]
    AlgorithmMismatch,

    #[error("unsupported algorithm `{0}`")]
    UnsupportedAlgorithm(String),

    #[error("missing required public key")]
    MissingPublicKey,

    #[error("invalid public key")]
    InvalidPublicKey,

    #[error("proof configuration: {0}")]
    ProofConfiguration(String),

    #[error("claims: {0}")]
    Claims(String),

    #[error("missing required option `{0}`")]
    MissingRequiredOption(String),

    #[error("missing signer")]
    MissingSigner,

    #[error("invalid secret key")]
    InvalidSecretKey,

    #[error("invalid signature")]
    InvalidSignature,

    #[error("{0}")]
    Other(String),
}

impl From<JwsSignatureError> for ssi::claims::SignatureError {
    fn from(value: JwsSignatureError) -> Self {
        match value {
            JwsSignatureError::MissingAlgorithm => Self::MissingAlgorithm,
            JwsSignatureError::AlgorithmMismatch => Self::AlgorithmMismatch,
            JwsSignatureError::UnsupportedAlgorithm(alg) => Self::UnsupportedAlgorithm(alg),
            JwsSignatureError::MissingPublicKey => Self::MissingPublicKey,
            JwsSignatureError::InvalidPublicKey => Self::InvalidPublicKey,
            JwsSignatureError::ProofConfiguration(msg) => Self::ProofConfiguration(msg),
            JwsSignatureError::Claims(msg) => Self::Claims(msg),
            JwsSignatureError::MissingRequiredOption(opt) => Self::MissingRequiredOption(opt),
            JwsSignatureError::MissingSigner => Self::MissingSigner,
            JwsSignatureError::InvalidSecretKey => Self::InvalidSecretKey,
            JwsSignatureError::InvalidSignature => Self::InvalidSignature,
            JwsSignatureError::Other(msg) => Self::Other(msg),
        }
    }
}

impl From<ssi::claims::SignatureError> for JwsSignatureError {
    fn from(value: ssi::claims::SignatureError) -> Self {
        match value {
            ssi::claims::SignatureError::MissingAlgorithm => Self::MissingAlgorithm,
            ssi::claims::SignatureError::AlgorithmMismatch => Self::AlgorithmMismatch,
            ssi::claims::SignatureError::UnsupportedAlgorithm(alg) => {
                Self::UnsupportedAlgorithm(alg)
            }
            ssi::claims::SignatureError::MissingPublicKey => Self::MissingPublicKey,
            ssi::claims::SignatureError::InvalidPublicKey => Self::InvalidPublicKey,
            ssi::claims::SignatureError::ProofConfiguration(msg) => Self::ProofConfiguration(msg),
            ssi::claims::SignatureError::Claims(msg) => Self::Claims(msg),
            ssi::claims::SignatureError::MissingRequiredOption(opt) => {
                Self::MissingRequiredOption(opt)
            }
            ssi::claims::SignatureError::MissingSigner => Self::MissingSigner,
            ssi::claims::SignatureError::InvalidSecretKey => Self::InvalidSecretKey,
            ssi::claims::SignatureError::InvalidSignature => Self::InvalidSignature,
            ssi::claims::SignatureError::Other(msg) => Self::Other(msg),
        }
    }
}
