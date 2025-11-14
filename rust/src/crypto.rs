use std::sync::Arc;

use anyhow::anyhow;
use isomdl::{
    cose::sign1::PreparedCoseSign1,
    definitions::{
        helpers::Tag24,
        traits::ToCbor,
        x509::{x5chain::X5CHAIN_COSE_HEADER_LABEL, X5Chain},
        CoseKey, EC2Curve, EC2Y,
    },
};
use serde::{Deserialize, Serialize};
use ssi::claims::cose::coset;

uniffi::custom_newtype!(KeyAlias, String);
#[derive(Debug, Serialize, Deserialize, PartialEq, Clone)]
pub struct KeyAlias(pub String);

#[derive(Debug, uniffi::Error, thiserror::Error)]
pub enum CryptoError {
    #[error("{0}")]
    General(String),
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

#[derive(uniffi::Object)]
/// Utility functions for cryptographic curves
pub struct CryptoCurveUtils(Curve);

enum Curve {
    SecP256R1,
}

#[uniffi::export]
impl CryptoCurveUtils {
    #[uniffi::constructor]
    /// Utils for the secp256r1 (aka P-256) curve.
    pub fn secp256r1() -> Self {
        Self(Curve::SecP256R1)
    }

    /// Returns null if the original signature encoding is not recognized.
    pub fn ensure_raw_fixed_width_signature_encoding(&self, bytes: Vec<u8>) -> Option<Vec<u8>> {
        match self.0 {
            Curve::SecP256R1 => {
                use p256::ecdsa::Signature;
                match (Signature::from_slice(&bytes), Signature::from_der(&bytes)) {
                    (Ok(s), _) | (_, Ok(s)) => Some(s.to_vec()),
                    _ => None,
                }
            }
        }
    }
}

#[derive(uniffi::Enum)]
pub enum X509CertChainOpts {
    PEM(Vec<Vec<u8>>),
    // CBOR encoded App Attest Data from Apple App Attest Service.
    // TODO: This will need to be parsed into a Rust struct that can
    // decode the x5c field from the CBOR mapping.
    AppleAppAttestData(Vec<u8>),
    None,
}

/// This method accepts raw bytes to be signed and included in a
/// COSE_Sign1 message.
///
/// NOTE: The payload must be encoded to the desired format (e.g., CBOR bytes) BEFORE
/// being passed into this method.
#[uniffi::export]
pub fn cose_sign1(
    signer: Arc<dyn SigningKey>,
    payload: Vec<u8>,
    // x509_cert_pem: Option<Vec<Vec<u8>>>,
    x509_chain_opts: X509CertChainOpts,
) -> Result<Vec<u8>> {
    let mut header = coset::HeaderBuilder::new().algorithm(coset::iana::Algorithm::ES256);

    let mut cose_sign1_builder = coset::CoseSign1Builder::new();

    match x509_chain_opts {
        X509CertChainOpts::PEM(certificates) => {
            let mut x5chain_builder = X5Chain::builder();

            for cert in certificates.iter() {
                x5chain_builder = x5chain_builder.with_der_certificate(cert).map_err(|e| {
                    CryptoError::General(format!(
                        "Failed to construct x5chain with certificate: {e:?}"
                    ))
                })?;
            }

            let x5chain = x5chain_builder
                .build()
                .map_err(|e| CryptoError::General(format!("Failed to build x5chain: {e:?}")))?;

            header = header.value(X5CHAIN_COSE_HEADER_LABEL, x5chain.into_cbor());
        }
        _ => {
            unimplemented!("Implement Apple app attest parsing and header building")
        }
    }

    cose_sign1_builder = cose_sign1_builder
        .protected(header.build())
        .payload(payload);

    let prepared_cose_sign1 = PreparedCoseSign1::new(cose_sign1_builder, None, None, false)
        .map_err(|e| CryptoError::General(format!("failed to prepare CoseSign1: {e:?}")))?;

    let signature = signer
        .sign(prepared_cose_sign1.signature_payload().to_vec())
        .map_err(|e| CryptoError::General(format!("failed to sign cose_sign1 object: {e:?}")))?;

    let value = prepared_cose_sign1.finalize(signature);

    let data = isomdl::cbor::to_vec(&value).map_err(|e| {
        CryptoError::General(format!("failed to serialized cose_sign1 object: {e:?}"))
    })?;

    Ok(data)
}

/// Returns a cose key based on the p-256 curve.
/// Return cose key value is returned as a CBOR-encoded byte array.
#[uniffi::export]
pub fn cose_key_ec2_p256_public_key(x: Vec<u8>, y: Vec<u8>, _kid: Vec<u8>) -> Result<Vec<u8>> {
    let device_key = CoseKey::EC2 {
        crv: EC2Curve::P256,
        x,
        y: EC2Y::Value(y),
    };

    let bytes = device_key
        .to_cbor_bytes()
        .map_err(|e| anyhow!("failed serialize cose key to cbor bytes: {e:?}"))?;

    Ok(bytes)
}

/// Returns the raw bytes as CBOR encoded bytes
///
/// If `tag_payload` is true, it will tag the bytes as a Tag24
/// item
#[uniffi::export]
pub fn encode_to_cbor_bytes(payload: Vec<u8>, tag_payload: bool) -> Result<Vec<u8>> {
    if tag_payload {
        Tag24::new(payload)
            .map_err(|e| {
                CryptoError::General(format!("Failed to construct CBOR Tag24 data item: {e:?}"))
            })?
            .to_cbor_bytes()
            .map_err(|e| {
                CryptoError::General(format!("Failed to serialize Tag24 to CBOR bytes: {e:?}"))
            })
    } else {
        isomdl::cbor::to_vec(&payload)
            .map_err(|e| CryptoError::General(format!("Failed to encode payload as CBOR: {e:?}")))
    }
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

    impl KeyStore for RustTestKeyManager {
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
