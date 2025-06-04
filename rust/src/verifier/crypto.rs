use uniffi::deps::anyhow::{anyhow, Context};
use x509_cert::der::{asn1, Encode};

#[uniffi::export(with_foreign)]
pub trait Crypto: Send + Sync {
    fn p256_verify(
        &self,
        certificate_der: Vec<u8>,
        payload: Vec<u8>,
        signature: Vec<u8>,
    ) -> VerificationResult;
}

impl Crypto for Box<dyn Crypto> {
    fn p256_verify(
        &self,
        certificate_der: Vec<u8>,
        payload: Vec<u8>,
        signature: Vec<u8>,
    ) -> VerificationResult {
        Crypto::p256_verify(self.as_ref(), certificate_der, payload, signature)
    }
}

#[derive(Debug, uniffi::Enum)]
pub enum VerificationResult {
    Success,
    Failure { cause: String },
}

impl VerificationResult {
    pub fn into_result(self) -> Result<(), String> {
        match self {
            VerificationResult::Success => Ok(()),
            VerificationResult::Failure { cause } => Err(cause),
        }
    }
}

/// A verifier for CoseSign objects with ECDSA + P-256 signatures.
pub struct CoseP256Verifier<'a> {
    pub crypto: &'a dyn Crypto,
    pub certificate_der: Vec<u8>,
}

/// A CoseSign ECDSA + P-256 signature.
pub struct CoseP256Signature {
    r: [u8; 32],
    s: [u8; 32],
}

impl TryFrom<&[u8]> for CoseP256Signature {
    type Error = signature::Error;

    fn try_from(value: &[u8]) -> Result<Self, Self::Error> {
        let (r, s) = value.split_at(32);
        Ok(Self {
            r: r.try_into().map_err(|e| {
                Self::Error::from_source(anyhow!("failed to parse 'r' parameter from slice: {e}"))
            })?,
            s: s.try_into().map_err(|e| {
                Self::Error::from_source(anyhow!("failed to parse 's' parameter from slice: {e}"))
            })?,
        })
    }
}

impl cose_rs::algorithm::SignatureAlgorithm for CoseP256Verifier<'_> {
    fn algorithm(&self) -> cose_rs::algorithm::Algorithm {
        cose_rs::algorithm::Algorithm::ES256
    }
}

impl signature::Verifier<CoseP256Signature> for CoseP256Verifier<'_> {
    fn verify(&self, msg: &[u8], signature: &CoseP256Signature) -> Result<(), signature::Error> {
        // Construct DER signature.
        let mut seq: asn1::SequenceOf<asn1::Uint, 2> = asn1::SequenceOf::new();
        seq.add(
            asn1::Uint::new(&signature.r)
                .context("unable to construct integer from signature parameter 'r'")
                .map_err(signature::Error::from_source)?,
        )
        .context("unable to add signature parameter 'r' to the sequence")
        .map_err(signature::Error::from_source)?;
        seq.add(
            asn1::Uint::new(&signature.s)
                .context("unable to construct integer from signature parameter 's'")
                .map_err(signature::Error::from_source)?,
        )
        .context("unable to add signature parameter 's' to the sequence")
        .map_err(signature::Error::from_source)?;

        let der_signature = seq
            .to_der()
            .context("unable to encode DER sequence")
            .map_err(signature::Error::from_source)?;

        self.crypto
            .p256_verify(self.certificate_der.clone(), msg.to_vec(), der_signature)
            .into_result()
            .map_err(signature::Error::from_source)
    }
}
