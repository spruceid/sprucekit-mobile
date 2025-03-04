use super::Credential;
use crate::crypto::KeyAlias;
use crate::trusted_roots;
use crate::verifier::crypto::{CoseP256Verifier, Crypto};
use crate::verifier::helpers;
use crate::{CborValue, CredentialType};
use cose_rs::cwt::claim::ExpirationTime;
use cose_rs::{cwt::ClaimsSet, CoseSign1};
use num_bigint::BigUint;
use num_traits::Num;
use std::collections::HashMap;

use std::sync::Arc;
use time::{Date, OffsetDateTime};
use time_macros::format_description;
use uuid::Uuid;

use cose_rs::sign1::VerificationResult;
use uniffi::deps::anyhow::anyhow;
use x509_cert::{certificate::CertificateInner, der::Encode};

#[derive(uniffi::Object, Debug, Clone)]
pub struct Cwt {
    id: Uuid,
    claims: HashMap<String, CborValue>,
    payload: String,
    key_alias: Option<KeyAlias>,
}

#[uniffi::export]
impl Cwt {
    #[uniffi::constructor]
    /// Construct a new credential from a compact JWS (of the form
    /// `<base64-encoded-header>.<base64-encoded-payload>.<base64-encoded-signature>`),
    /// without an associated keypair.
    pub fn new_from_base10(payload: String) -> Result<Arc<Self>, CwtError> {
        let id = Uuid::new_v4();
        Self::from_base10(id, payload.as_bytes().to_vec()).map(|(_, cwt)| cwt)
    }

    pub fn verify(&self, crypto: Box<dyn Crypto>, payload: String) -> Result<(), CwtError> {
        self.validate(&crypto, payload)
    }

    /// The VdcCollection ID for this credential.
    pub fn id(&self) -> Uuid {
        self.id
    }

    pub fn payload(&self) -> String {
        self.payload.clone()
    }

    /// The version of the Verifiable Credential Data Model that this credential conforms to.
    pub fn claims(&self) -> HashMap<String, CborValue> {
        self.claims.clone()
    }

    pub fn r#type(&self) -> CredentialType {
        CredentialType("cwt".to_string())
    }

    /// Return the key alias for the creden tial
    pub fn key_alias(&self) -> Option<KeyAlias> {
        self.key_alias.clone()
    }
}

impl Cwt {
    pub(crate) fn from_base10(
        id: Uuid,
        payload: Vec<u8>,
    ) -> Result<(CoseSign1, Arc<Self>), CwtError> {
        let payload =
            String::from_utf8(payload).map_err(|e| CwtError::CwsPayloadDecode(e.to_string()))?;
        let base10_str = payload
            .strip_prefix('9')
            .ok_or_else(|| CwtError::Base10Decode)?;
        let compressed_cwt_bytes = BigUint::from_str_radix(base10_str, 10)
            .map_err(|_| CwtError::Base10Decode)?
            .to_bytes_be();

        let cwt_bytes = miniz_oxide::inflate::decompress_to_vec(&compressed_cwt_bytes)
            .map_err(|e| CwtError::Decompression(e.to_string()))?;

        let cwt: CoseSign1 = serde_cbor::from_slice(&cwt_bytes)
            .map_err(|e| CwtError::CborDecoding(e.to_string()))?;

        let claims = cwt
            .claims_set()
            .map_err(|e| CwtError::ClaimsRetrieval(e.to_string()))?
            .ok_or_else(|| CwtError::EmptyPayload)?;

        let claims = Self::claims_set_to_hash_map(claims);

        Ok((
            cwt,
            Cwt {
                id,
                claims,
                payload,
                key_alias: None,
            }
            .into(),
        ))
    }

    fn validate<C: Crypto>(&self, crypto: &C, cwt: String) -> Result<(), CwtError> {
        let (cwt, _) = Self::from_base10(self.id, cwt.as_bytes().to_vec())
            .map_err(|e| CwtError::CwsPayloadDecode(e.to_string()))?;
        let trusted_roots = trusted_roots::trusted_roots()
            .map_err(|e| CwtError::LoadRootCertificate(e.to_string()))?;
        let signer_certificate =
            helpers::get_signer_certificate(&cwt).map_err(|e| CwtError::Trust(e.to_string()))?;

        // We want to manually handle the Err to get all errors, so try_fold would not work
        #[allow(clippy::manual_try_fold)]
        trusted_roots
            .into_iter()
            .filter(|cert| {
                cert.tbs_certificate.subject == signer_certificate.tbs_certificate.issuer
            })
            .fold(Result::Err("\n".to_string()), |res, cert| match res {
                Ok(_) => Ok(()),
                Err(err) => match Self::validate_certificate_chain(crypto, &cwt, cert.clone()) {
                    Ok(_) => Ok(()),
                    Err(e) => Err(format!("{}\n--------------\n{}", err, e)),
                },
            })
            .map_err(|err| {
                anyhow!(if err == "\n" {
                    format!("signer certificate was not issued by the root:\n\texpected:\n\t\t{}\n\tfound: None.", signer_certificate.tbs_certificate.issuer)
                } else {
                    err
                })
            })
                    .map_err(|e|CwtError::Trust(e.to_string()))?;

        let claims = cwt
            .claims_set()
            .map_err(|e| CwtError::ClaimsRetrieval(e.to_string()))?
            .ok_or_else(|| CwtError::EmptyPayload)?;

        Self::validate_cwt(&claims)
    }

    fn validate_certificate_chain(
        crypto: &dyn Crypto,
        cwt: &CoseSign1,
        root_certificate: CertificateInner,
    ) -> Result<(), CwtError> {
        let signer_certificate = helpers::get_signer_certificate(cwt)
            .map_err(|e| CwtError::SignerCertificateInvalid(e.to_string()))?;

        // Root validation.
        {
            helpers::check_validity(&root_certificate.tbs_certificate.validity)
                .map_err(|_| CwtError::RootCertificateExpired)?;

            let (key_usage, _crl_dp) = helpers::extract_extensions(&root_certificate)
                .map_err(|_| CwtError::UnableToExtractExtensionsFromRootCertificate)?;

            if !key_usage.key_cert_sign() {
                return Err(CwtError::RootCertificateInvalid(
                    "Root certificate cannot be used for verifying certificate signatures"
                        .to_string(),
                ));
            }
            // TODO: Check crl
        }

        // Validate that Root issued Signer.
        let root_subject = &root_certificate.tbs_certificate.subject;
        let signer_issuer = &signer_certificate.tbs_certificate.issuer;
        if root_subject != signer_issuer {
            return Err(CwtError::SignerCertificateMismatch(
                root_subject.to_string(),
                signer_issuer.to_string(),
            ));
        }
        let signer_tbs_der = signer_certificate
            .tbs_certificate
            .to_der()
            .map_err(|_| CwtError::UnableToEncodeSignerCertificateAsDer)?;
        let signer_signature = signer_certificate.signature.raw_bytes().to_vec();
        crypto
            .p256_verify(
                root_certificate
                    .to_der()
                    .map_err(|_| CwtError::UnableToEncodeRootCertificateAsDer)?,
                signer_tbs_der,
                signer_signature,
            )
            .into_result()
            .map_err(|e| CwtError::CwtSignatureVerification(e.to_string()))?;

        // Signer validation.
        {
            helpers::check_validity(&signer_certificate.tbs_certificate.validity)
                .map_err(|_| CwtError::SignerCertificateExpired)?;

            // TODO: check certificate generation code and re-enable these checks
            // let (key_usage, _crl_dp) = helpers::extract_extensions(&signer_certificate)
            //     .map_err(|_| CwtError::UnableToExtractExtensionsFromSignerCertificate)?;

            // if !key_usage.digital_signature() {
            //     return Err(CwtError::SignerCertificateInvalid(
            //         "Certificate not for digital signature".to_string(),
            //     ));
            // }

            // TODO: Check crl
        }

        // Validate that Signer issued CWT.
        let verifier = CoseP256Verifier {
            crypto,
            certificate_der: signer_certificate
                .to_der()
                .map_err(|_| CwtError::UnableToEncodeSignerCertificateAsDer)?,
        };

        match cwt.verify(&verifier, None, None) {
            VerificationResult::Success => Ok(()),
            VerificationResult::Failure(e) => {
                Err(CwtError::CwtSignatureVerification(e.to_string()))
            }
            VerificationResult::Error(e) => Err(CwtError::CwtSignatureVerification(e.to_string())),
        }
    }

    fn validate_cwt(claims: &ClaimsSet) -> Result<(), CwtError> {
        // Validate the expiration time claim
        if let Some(ExpirationTime(exp)) = claims.get_claim().map_err(|e| {
            CwtError::MalformedClaim(
                "exp".to_string(),
                e.to_string(),
                "could not parse".to_string(),
            )
        })? {
            let exp: OffsetDateTime =
                exp.try_into()
                    .map_err(|e: cose_rs::cwt::numericdate_conversion::Error| {
                        CwtError::MalformedClaim(
                            "exp".to_string(),
                            e.to_string(),
                            "could not parse".to_string(),
                        )
                    })?;
            if exp < OffsetDateTime::now_utc() {
                return Err(CwtError::CwtExpired(exp.to_string()));
            }
        }
        Ok(())
    }

    fn get_key_name(key: &cose_rs::cwt::Key) -> String {
        match key {
            cose_rs::cwt::Key::Text(v) => v.to_string(),
            cose_rs::cwt::Key::Integer(v) => match *v {
                4 => "Expiration".to_string(),
                5 => "Not Before".to_string(),
                6 => "Issuance".to_string(),
                _ => v.to_string(),
            },
        }
    }

    fn claims_set_to_hash_map(set: ClaimsSet) -> HashMap<String, CborValue> {
        set.iter()
            .map(|c| {
                (
                    Self::get_key_name(&c.0),
                    match c.0 {
                        cose_rs::cwt::Key::Text(_) => CborValue::from(c.1.clone()),
                        cose_rs::cwt::Key::Integer(v) => {
                            if *v == 4 || *v == 5 || *v == 6 {
                                Self::parse_datestr(c.1)
                            } else {
                                CborValue::from(c.1.clone())
                            }
                        }
                    },
                )
            })
            .collect()
    }

    /// Parse date strings, handling both ISO format and Unix timestamps
    fn parse_datestr(value: &serde_cbor::Value) -> CborValue {
        match value {
            serde_cbor::Value::Float(timestamp) => {
                match OffsetDateTime::from_unix_timestamp_nanos(
                    (timestamp * 1_000_000_000.0) as i128,
                ) {
                    Ok(date) => CborValue::Text(
                        date.format(&format_description!("[year]-[month]-[day]"))
                            .unwrap_or_else(|_| timestamp.to_string()),
                    ),
                    Err(_) => CborValue::Text(timestamp.to_string()),
                }
            }
            _ => {
                let date_str = CborValue::from(value.clone()).to_string();
                let format = format_description!("[year]-[month]-[day]");
                Date::parse(&date_str, format)
                    .map(|date| CborValue::Text(date.to_string()))
                    .unwrap_or_else(|_| CborValue::Text(date_str))
            }
        }
    }
}

impl TryFrom<Credential> for Arc<Cwt> {
    type Error = CwtError;

    fn try_from(credential: Credential) -> Result<Self, Self::Error> {
        Cwt::from_base10(credential.id, credential.payload).map(|(_, cwt)| cwt)
    }
}

impl TryFrom<&Credential> for Arc<Cwt> {
    type Error = CwtError;

    fn try_from(credential: &Credential) -> Result<Self, Self::Error> {
        Cwt::from_base10(credential.id, credential.payload.clone()).map(|(_, cwt)| cwt)
    }
}

#[derive(Debug, uniffi::Error, thiserror::Error)]
pub enum CwtError {
    #[error("failed to decode string as a JWS of the form <base64-encoded-header>.<base64-encoded-payload>.<base64-encoded-signature>")]
    CompactJwsDecoding,
    #[error("Failed to convert payload to string. {0}")]
    CwsPayloadDecode(String),
    #[error("Payload did not begin with multibase prefix '9'")]
    Base10Decode,
    #[error("Unable to decompress the payload of the QR code. {0}")]
    Decompression(String),
    #[error("Unable to decode the credential: {0}")]
    CborDecoding(String),
    #[error("Unable to retrieve the claims from the credential: {0}")]
    ClaimsRetrieval(String),
    #[error("Credential does not have a payload")]
    EmptyPayload,
    #[error("User did not present the expected credential: expected {0}, received {1}")]
    IncorrectCredential(String, String),
    #[error("Credential is missing expected claim: {0}")]
    MissingClaim(String),
    #[error("Credential claim {0} is malformed: {1}: {2}")]
    MalformedClaim(String, String, String),
    #[error("Could not establish trust in the credential: {0}")]
    Trust(String),
    #[error("Expiration Date: {0}")]
    CwtExpired(String),
    #[error("Root certificates could not be loaded: {0}")]
    LoadRootCertificate(String),
    #[error("Internal Error")]
    Internal,
    #[error("Failed to verify the CWT signature: {0}")]
    CwtSignatureVerification(String),
    #[error("Signer certificate cannot be used for verifying signatures: {0}")]
    SignerCertificateInvalid(String),
    #[error("Signer certificate was not issued by the root: expected {0}, received {1}")]
    SignerCertificateMismatch(String, String),
    #[error("Root certificate cannot be used for verifying certificate signatures: {0}")]
    RootCertificateInvalid(String),
    #[error("Unable to encode signer certificate as der")]
    UnableToEncodeSignerCertificateAsDer,
    #[error("Unable to encode root certificate as der")]
    UnableToEncodeRootCertificateAsDer,
    #[error("Unable to extract extensions from signer certificate")]
    UnableToExtractExtensionsFromSignerCertificate,
    #[error("Root certificate expired")]
    RootCertificateExpired,
    #[error("Signer certificate expired")]
    SignerCertificateExpired,
    #[error("Unable to extract extensions from root certificate")]
    UnableToExtractExtensionsFromRootCertificate,
}
