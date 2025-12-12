use super::Credential;
use crate::crypto::KeyAlias;
use crate::verifier::crypto::{CoseP256Verifier, Crypto};
use crate::verifier::helpers;
use crate::{trusted_roots, CborKeyMapper};
use crate::{CborValue, CredentialType};
use cose_rs::cwt::claim::ExpirationTime;
use cose_rs::{cwt::ClaimsSet, CoseSign1};
use num_bigint::BigUint;
use num_traits::Num;
use ssi::dids::{AnyDidMethod, VerificationMethodDIDResolver};
use ssi::jwk::JWKResolver;
use ssi::prelude::AnyJwkMethod;
use ssi::status::token_status_list::json::JsonStatusList;
use std::collections::HashMap;
use x509_cert::der::DecodePem;
use x509_cert::Certificate;

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
    payload: Vec<u8>,
    cwt: CoseSign1,
    claims: ClaimsSet,
    key_alias: Option<KeyAlias>,
}

#[uniffi::export]
impl Cwt {
    #[uniffi::constructor]
    pub fn new_from_bytes(payload: Vec<u8>) -> Result<Arc<Self>, CwtError> {
        let id = Uuid::new_v4();
        Ok(Self::from_bytes(id, payload)?.into())
    }

    #[uniffi::constructor]
    pub fn new_from_base10(payload: String) -> Result<Arc<Self>, CwtError> {
        let id = Uuid::new_v4();
        Ok(Self::from_base10(id, payload.as_bytes().to_vec())?.into())
    }

    /// The VdcCollection ID for this credential.
    pub fn id(&self) -> Uuid {
        self.id
    }

    /// Returns the claims as a map of string to CBOR values.
    pub fn claims(&self) -> HashMap<String, CborValue> {
        Self::claims_set_to_hash_map(self.claims.clone())
    }

    /// Returns the claims as a JSON encoded map
    pub fn claims_json(&self) -> Result<String, CwtError> {
        let json_map: HashMap<String, serde_json::Value> =
            Self::claims_set_to_hash_map(self.claims.clone())
                .into_iter()
                .map(|(k, v)| (k, serde_json::Value::from(v)))
                .collect();

        serde_json::to_string(&json_map).map_err(|e| CwtError::ClaimsRetrieval(e.to_string()))
    }

    pub fn r#type(&self) -> CredentialType {
        CredentialType("cwt".to_string())
    }

    /// Return the key alias for the creden tial
    pub fn key_alias(&self) -> Option<KeyAlias> {
        self.key_alias.clone()
    }
}

#[uniffi::export(async_runtime = "tokio")]
impl Cwt {
    pub async fn verify(&self, crypto: &dyn Crypto) -> Result<(), CwtError> {
        self.validate(crypto).await
    }

    // Will verify against a known trusted certificate
    pub async fn verify_with_certs(
        &self,
        crypto: &dyn Crypto,
        trusted_certs_pem: Vec<String>,
    ) -> Result<(), CwtError> {
        self.validate_with_certs(crypto, trusted_certs_pem).await
    }

    /// Checks the revocation status of this CWT credential.
    ///
    /// This method extracts status list information from a specified CBOR claim field,
    /// fetches the status list from the URI specified in that claim, decodes the
    /// compressed bit string, and returns the status value at the credential's index.
    ///
    /// # Returns
    ///
    /// Returns a status code as `i16`:
    /// - `0`  - VALID: The credential is currently valid and has not been revoked
    /// - `1`  - INVALID: The credential is no longer valid
    /// - `2`  - SUSPENDED: The credential is revoked
    /// - `-1` - UNKNOWN: The credential does not contain a known status information
    ///
    /// # Errors
    ///
    /// Returns an error if:
    /// - The status list structure is malformed (missing `idx` or `uri` fields)
    /// - The status list cannot be fetched from the URI
    /// - The status list response cannot be parsed or decoded
    /// - The credential's index is out of bounds for the status list
    ///
    pub async fn status(&self) -> Result<i16, CwtError> {
        const STATUS_CLAIM_KEY: &str = "65535";
        const STATUS_FIELD_NAME: &str = "status_list";

        // Extract the outer CBOR claim containing status metadata
        let Some(outer_claim_cbor) = self.claims().get(STATUS_CLAIM_KEY).cloned() else {
            // Credential does not have status information
            return Ok(-1);
        };

        // Extract the outer claim map
        let CborValue::ItemMap(outer_claim_map) = outer_claim_cbor else {
            return Err(CwtError::MalformedClaim(
                STATUS_CLAIM_KEY.to_string(),
                "value".to_string(),
                "expected map".to_string(),
            ));
        };

        // Extract the status list field
        let status_list_cbor = outer_claim_map
            .get(STATUS_FIELD_NAME)
            .cloned()
            .ok_or_else(|| CwtError::MissingClaim(STATUS_FIELD_NAME.to_string()))?;

        // Extract the status list map
        let CborValue::ItemMap(status_list_map) = status_list_cbor else {
            return Err(CwtError::MalformedClaim(
                STATUS_FIELD_NAME.to_string(),
                "value".to_string(),
                "expected map".to_string(),
            ));
        };

        // Extract idx (credential's index in the status list)
        let idx_cbor = status_list_map
            .get("idx")
            .cloned()
            .ok_or(CwtError::MissingClaim("idx".to_string()))?;

        let idx = match idx_cbor {
            CborValue::Integer(i) => {
                let idx_i128: i128 = i.as_ref().clone().into();
                idx_i128 as usize
            }
            _ => {
                return Err(CwtError::MalformedClaim(
                    STATUS_FIELD_NAME.to_string(),
                    "idx".to_string(),
                    "expected integer".to_string(),
                ))
            }
        };

        // Extract uri (where the status list is hosted)
        let uri_cbor = status_list_map
            .get("uri")
            .cloned()
            .ok_or(CwtError::MissingClaim("uri".to_string()))?;

        let uri = match uri_cbor {
            CborValue::Text(s) => s,
            _ => {
                return Err(CwtError::MalformedClaim(
                    STATUS_FIELD_NAME.to_string(),
                    "uri".to_string(),
                    "expected integer".to_string(),
                ))
            }
        };

        // Fetch the status list from the URI
        let response = reqwest::get(&uri).await.map_err(|e| {
            CwtError::StatusListFetch(format!("Failed to fetch from {}: {}", uri, e))
        })?;

        let response_body = response
            .text()
            .await
            .map_err(|e| CwtError::StatusListFetch(format!("Failed to read response: {}", e)))?;

        // Parse the json status list
        let json_status_list: JsonStatusList = serde_json::from_str(&response_body)
            .map_err(|e| CwtError::StatusListParse(format!("Failed to parse JSON: {}", e)))?;

        // Decode the compressed bit string
        let decoded_bit_string = json_status_list
            .decode(None)
            .map_err(|e| CwtError::StatusListDecode(format!("Failed to decode: {}", e)))?;

        // Get the status value at the credential's index
        let status_value = decoded_bit_string
            .get(idx)
            .ok_or(CwtError::StatusIndexOutOfBounds)?;

        Ok(status_value.into())
    }
}

impl Cwt {
    pub fn inner(&self) -> &CoseSign1 {
        &self.cwt
    }
}

impl Cwt {
    pub(crate) fn from_bytes(id: Uuid, bytes: Vec<u8>) -> Result<Self, CwtError> {
        let cwt: CoseSign1 =
            serde_cbor::from_slice(&bytes).map_err(|e| CwtError::CborDecoding(e.to_string()))?;

        let claims = cwt
            .claims_set()
            .map_err(|e| CwtError::ClaimsRetrieval(e.to_string()))?
            .ok_or(CwtError::EmptyPayload)?;

        Ok(Cwt {
            id,
            payload: bytes,
            cwt,
            claims,
            key_alias: None,
        })
    }

    pub(crate) fn from_base10(id: Uuid, payload: Vec<u8>) -> Result<Self, CwtError> {
        let raw_payload = payload.clone();
        let payload =
            String::from_utf8(payload).map_err(|e| CwtError::CwsPayloadDecode(e.to_string()))?;
        let base10_str = payload.strip_prefix('9').ok_or(CwtError::Base10Decode)?;
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
            .ok_or(CwtError::EmptyPayload)?;

        Ok(Cwt {
            id,
            payload: raw_payload,
            cwt,
            claims,
            key_alias: None,
        })
    }

    async fn validate_with_certs(
        &self,
        crypto: &dyn Crypto,
        trusted_certs_pem: Vec<String>,
    ) -> Result<(), CwtError> {
        self.validate_claims()?;

        let Ok(signer_certificate) = helpers::get_signer_certificate(&self.cwt) else {
            if let Some(CborValue::Text(issuer_did)) = self.claims().get("Issuer") {
                return self.validate_using_issuer_did(issuer_did).await;
            } else {
                return Err(CwtError::Trust(
                    "no signer certificate or issuer DID found".to_string(),
                ));
            }
        };

        let mut trusted_roots = trusted_roots::trusted_roots()
            .map_err(|e| CwtError::LoadRootCertificate(e.to_string()))?;

        // Allow trusted roots to be provided as an option.
        for trusted in trusted_certs_pem.iter() {
            let cert = Certificate::from_pem(trusted)
                .map_err(|e| CwtError::LoadRootCertificate(e.to_string()))?;

            trusted_roots.push(cert)
        }

        // We want to manually handle the Err to get all errors, so try_fold would not work
        #[allow(clippy::manual_try_fold)]
        trusted_roots
            .into_iter()
            .filter(|cert| {
                cert.tbs_certificate.subject == signer_certificate.tbs_certificate.issuer
            })
            .fold(Result::Err("\n".to_string()), |res, cert| match res {
                Ok(_) => Ok(()),
                Err(err) => match self.validate_certificate_chain(crypto, &cert, &signer_certificate) {
                    Ok(_) => Ok(()),
                    Err(e) => Err(format!("{err}\n--------------\n{e}")),
                },
            })
            .map_err(|err| {
                anyhow!(if err == "\n" {
                    format!("signer certificate was not issued by the root:\n\texpected:\n\t\t{}\n\tfound: None.", signer_certificate.tbs_certificate.issuer)
                } else {
                    err
                })
            })
                    .map_err(|e|CwtError::Trust(e.to_string()))
    }

    async fn validate(&self, crypto: &dyn Crypto) -> Result<(), CwtError> {
        self.validate_with_certs(crypto, Vec::with_capacity(0))
            .await
    }

    fn validate_certificate_chain(
        &self,
        crypto: &dyn Crypto,
        root_certificate: &CertificateInner,
        signer_certificate: &CertificateInner,
    ) -> Result<(), CwtError> {
        // Root validation.
        {
            helpers::check_validity(&root_certificate.tbs_certificate.validity)
                .map_err(|_| CwtError::RootCertificateExpired)?;

            let (key_usage, _crl_dp) = helpers::extract_extensions(root_certificate)
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

            let (key_usage, _crl_dp) = helpers::extract_extensions(signer_certificate)
                .map_err(|_| CwtError::UnableToExtractExtensionsFromSignerCertificate)?;

            if !key_usage.digital_signature() {
                return Err(CwtError::SignerCertificateInvalid(
                    "Certificate not for digital signature".to_string(),
                ));
            }

            // TODO: Check crl
        }

        // Validate that Signer issued CWT.
        let verifier = CoseP256Verifier {
            crypto,
            certificate_der: signer_certificate
                .to_der()
                .map_err(|_| CwtError::UnableToEncodeSignerCertificateAsDer)?,
        };

        match self.cwt.verify(&verifier, None, None) {
            VerificationResult::Success => Ok(()),
            VerificationResult::Failure(e) => {
                Err(CwtError::CwtSignatureVerification(e.to_string()))
            }
            VerificationResult::Error(e) => Err(CwtError::CwtSignatureVerification(e.to_string())),
        }
    }

    async fn validate_using_issuer_did(&self, issuer_did: &str) -> Result<(), CwtError> {
        let resolver: VerificationMethodDIDResolver<AnyDidMethod, AnyJwkMethod> =
            Default::default();
        let jwk = resolver
            .fetch_public_jwk(Some(issuer_did))
            .await
            .map_err(|e| CwtError::Trust(format!("Failed to resolve issuer DID: {e}")))?;
        let jwk_str = serde_json::to_string(&jwk).map_err(|e| {
            tracing::error!("Failed to serialize JWK: {e}");
            CwtError::Internal
        })?;
        let verifier: p256::ecdsa::VerifyingKey = p256::PublicKey::from_jwk_str(&jwk_str)
            .map_err(|e| {
                tracing::error!("Failed to parse JWK: {e}");
                CwtError::Internal
            })?
            .into();
        let verification_result = self
            .cwt
            .verify::<_, p256::ecdsa::Signature>(&verifier, None, None);
        match verification_result {
            VerificationResult::Success => Ok(()),
            VerificationResult::Failure(e) => {
                Err(CwtError::CwtSignatureVerification(e.to_string()))
            }
            VerificationResult::Error(e) => Err(CwtError::CwtSignatureVerification(e.to_string())),
        }
    }

    fn validate_claims(&self) -> Result<(), CwtError> {
        // Validate the expiration time claim
        if let Some(ExpirationTime(exp)) = self.claims.get_claim().map_err(|e| {
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
            cose_rs::cwt::Key::Text(v) => {
                let key_string = v.to_string();
                match key_string.trim().parse::<i128>() {
                    Ok(num) => CborKeyMapper::key_to_string(num),
                    Err(_) => key_string.to_string(),
                }
            }
            cose_rs::cwt::Key::Integer(v) => CborKeyMapper::key_to_string(*v),
        }
    }

    fn claims_set_to_hash_map(set: ClaimsSet) -> HashMap<String, CborValue> {
        set.iter()
            .map(|c| {
                (
                    Self::get_key_name(c.0),
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

    pub fn payload(&self) -> Vec<u8> {
        self.payload.clone()
    }
}

impl TryFrom<Credential> for Arc<Cwt> {
    type Error = CwtError;

    fn try_from(credential: Credential) -> Result<Self, Self::Error> {
        Cwt::from_base10(credential.id, credential.payload).map(|cwt| cwt.into())
    }
}

impl TryFrom<&Credential> for Arc<Cwt> {
    type Error = CwtError;

    fn try_from(credential: &Credential) -> Result<Self, Self::Error> {
        Cwt::from_base10(credential.id, credential.payload.clone()).map(|cwt| cwt.into())
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

    #[error("Failed to fetch status list: {0}")]
    StatusListFetch(String),
    #[error("Failed to parse status list: {0}")]
    StatusListParse(String),
    #[error("Failed to decode status list: {0}")]
    StatusListDecode(String),
    #[error("Status index out of bounds")]
    StatusIndexOutOfBounds,
}
