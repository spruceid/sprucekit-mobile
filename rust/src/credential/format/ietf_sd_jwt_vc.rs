//! This implements support for SD-JWT-based Verifiable Digital Credentials as defined in
//! [draft-ietf-oauth-sd-jwt-vc 14](https://datatracker.ietf.org/doc/draft-ietf-oauth-sd-jwt-vc/14/).
use crate::{
    credential::{Credential, CredentialFormat},
    crypto::KeyAlias,
    oid4vp::{
        error::OID4VPError,
        permission_request::RequestedField,
        presentation::{CredentialPresentation, PresentationOptions},
    },
    CredentialType,
};

use core::str;
use std::sync::Arc;

use base64::{engine::general_purpose::URL_SAFE, Engine as _};
use openid4vp::core::{
    credential_format::ClaimFormatDesignation, dcql_query::DcqlCredentialQuery,
    response::parameters::VpTokenItem,
};
use ssi::{
    claims::{
        jws::{JwsSigner, JwsSignerInfo},
        jwt::AnyClaims,
        sd_jwt::{KbJwtPayload, SdAlg, SdJwtBuf},
        SignatureError,
    },
    JsonPointerBuf,
};
use uuid::Uuid;

pub const FORMAT_DC_SD_JWT: &str = "dc+sd-jwt";

/// IETF SD-JWT VC credential.
#[derive(Debug, uniffi::Object)]
pub struct IetfSdJwtVc {
    pub(crate) id: Uuid,
    pub(crate) key_alias: Option<KeyAlias>,
    /// The revealed claims from the SD-JWT
    pub(crate) claims: serde_json::Value,
    /// The raw SD-JWT buffer
    pub(crate) inner: SdJwtBuf,
}

impl IetfSdJwtVc {
    /// Return the revealed claims as a JSON value.
    pub fn revealed_claims_as_json(&self) -> Result<serde_json::Value, IetfSdJwtVcError> {
        Ok(self.claims.clone())
    }

    /// Get the issuer claim.
    pub fn issuer(&self) -> Option<String> {
        self.claims
            .get("iss")
            .and_then(|v| v.as_str())
            .map(|s| s.to_string())
    }
}

#[uniffi::export]
impl IetfSdJwtVc {
    /// Create a new IetfSdJwtVc instance from a compact SD-JWT string.
    #[uniffi::constructor]
    pub fn new_from_compact_sd_jwt(input: String) -> Result<Arc<Self>, IetfSdJwtVcError> {
        let inner: SdJwtBuf =
            SdJwtBuf::new(input).map_err(|e| IetfSdJwtVcError::InvalidSdJwt(format!("{e:?}")))?;

        let mut sd_jwt = IetfSdJwtVc::try_from(inner)?;
        sd_jwt.key_alias = None;

        Ok(Arc::new(sd_jwt))
    }

    /// Create a new IetfSdJwtVc instance from a compact SD-JWT string with a provided key alias.
    #[uniffi::constructor]
    pub fn new_from_compact_sd_jwt_with_key(
        input: String,
        key_alias: KeyAlias,
    ) -> Result<Arc<Self>, IetfSdJwtVcError> {
        let inner: SdJwtBuf =
            SdJwtBuf::new(input).map_err(|e| IetfSdJwtVcError::InvalidSdJwt(format!("{e:?}")))?;

        let mut sd_jwt = IetfSdJwtVc::try_from(inner)?;
        sd_jwt.key_alias = Some(key_alias);

        Ok(Arc::new(sd_jwt))
    }

    #[uniffi::constructor]
    pub fn from_compact_sd_jwt_with_id_and_key(
        id: Uuid,
        input: String,
        key_alias: KeyAlias,
    ) -> Result<Arc<Self>, IetfSdJwtVcError> {
        let inner: SdJwtBuf =
            SdJwtBuf::new(input).map_err(|e| IetfSdJwtVcError::InvalidSdJwt(format!("{e:?}")))?;

        let mut sd_jwt = IetfSdJwtVc::try_from((id, inner))?;
        sd_jwt.key_alias = Some(key_alias);

        Ok(Arc::new(sd_jwt))
    }

    /// Return the ID for the IetfSdJwtVc instance.
    pub fn id(&self) -> Uuid {
        self.id
    }

    /// Return the key alias for the credential
    pub fn key_alias(&self) -> Option<KeyAlias> {
        self.key_alias.clone()
    }

    /// Return the Verifiable Credential Type (vct) claim.
    pub fn vct(&self) -> String {
        self.claims
            .get("vct")
            .and_then(|v| v.as_str())
            .expect("vct is validated during construction")
            .to_string()
    }

    /// The type of this credential based on the vct claim.
    pub fn r#type(&self) -> CredentialType {
        CredentialType(self.vct())
    }

    /// Return the revealed claims as a UTF-8 encoded JSON string.
    pub fn revealed_claims_as_json_string(&self) -> Result<String, IetfSdJwtVcError> {
        serde_json::to_string(&self.claims)
            .map_err(|e| IetfSdJwtVcError::Serialization(format!("{e:?}")))
    }
}

impl IetfSdJwtVc {
    /// Get all credential claims as JSON.
    pub fn credential_claims(&self) -> std::collections::HashMap<String, serde_json::Value> {
        if let Some(obj) = self.claims.as_object() {
            obj.iter().map(|(k, v)| (k.clone(), v.clone())).collect()
        } else {
            std::collections::HashMap::new()
        }
    }
}

impl IetfSdJwtVc {
    /// Check if the credential satisfies a DCQL credential query.
    pub fn satisfies_dcql_query(&self, credential_query: &DcqlCredentialQuery) -> bool {
        // Check format
        if credential_query.format() != &ClaimFormatDesignation::DcSdJwt {
            return false;
        }

        // Check vct if specified in meta
        if let Some(vct_values) = credential_query
            .meta()
            .get("vct_values")
            .and_then(|v| v.as_array())
        {
            let credential_vct = self.vct();

            let matches = vct_values.iter().any(|expected_vct| {
                expected_vct
                    .as_str()
                    .map(|s| s == credential_vct)
                    .unwrap_or(false)
            });

            if !matches {
                return false;
            }
        }

        true
    }

    /// Return the requested fields for the credential, according to a DCQL credential query.
    pub fn requested_fields_dcql(
        &self,
        credential_query: &DcqlCredentialQuery,
    ) -> Vec<Arc<RequestedField>> {
        use openid4vp::core::dcql_query::DcqlCredentialClaimsQueryPath;

        let claims = credential_query.claims();
        let Some(claims) = claims else {
            return vec![];
        };

        claims
            .iter()
            .flat_map(|claim_query| {
                let path = claim_query.path();
                let path_strings: Vec<String> = path
                    .iter()
                    .filter_map(|p| match p {
                        DcqlCredentialClaimsQueryPath::String(s) => Some(s.clone()),
                        DcqlCredentialClaimsQueryPath::Integer(n) => Some(n.to_string()),
                        DcqlCredentialClaimsQueryPath::Null => None, // Skip null (wildcard) elements
                    })
                    .collect();

                // Try to get the value at this path
                let value = self.get_value_at_path(path);

                Some(Arc::new(RequestedField::from_dcql_claims_with_name(
                    credential_query.id().to_string(),
                    path_strings.clone(),
                    value.map(|v| vec![v]).unwrap_or_default(),
                    Some(path_strings.join(".")),
                )))
            })
            .collect()
    }

    fn get_value_at_path(
        &self,
        path: &[openid4vp::core::dcql_query::DcqlCredentialClaimsQueryPath],
    ) -> Option<serde_json::Value> {
        use openid4vp::core::dcql_query::DcqlCredentialClaimsQueryPath;

        let mut current = &self.claims;
        for segment in path {
            match segment {
                DcqlCredentialClaimsQueryPath::String(key) => {
                    current = current.get(key)?;
                }
                DcqlCredentialClaimsQueryPath::Integer(index) => {
                    current = current.get(*index)?;
                }
                DcqlCredentialClaimsQueryPath::Null => {
                    // Null represents a wildcard; we can't traverse wildcards directly
                    return None;
                }
            }
        }
        Some(current.clone())
    }
}

/// Adapter to use a [`PresentationSigner`] as a [`JwsSigner`] for KB-JWT signing.
struct PresentationJwsSigner<'a> {
    signer: &'a dyn crate::oid4vp::presentation::PresentationSigner,
}

impl JwsSigner for PresentationJwsSigner<'_> {
    async fn fetch_info(&self) -> Result<JwsSignerInfo, SignatureError> {
        let algorithm = self
            .signer
            .algorithm()
            .try_into()
            .map_err(|e| SignatureError::other(format!("unsupported algorithm: {e:?}")))?;
        Ok(JwsSignerInfo {
            algorithm,
            key_id: None,
        })
    }

    async fn sign_bytes(&self, signing_bytes: &[u8]) -> Result<Vec<u8>, SignatureError> {
        let signature = self
            .signer
            .sign(signing_bytes.to_vec())
            .await
            .map_err(|e| SignatureError::other(format!("{e:?}")))?;

        // The native signer (iOS SecKey) may return DER-encoded signatures.
        // JWS requires raw fixed-width R||S encoding for ECDSA.
        crate::crypto::CryptoCurveUtils::secp256r1()
            .ensure_raw_fixed_width_signature_encoding(signature)
            .ok_or_else(|| SignatureError::other("failed to encode signature as raw R||S"))
    }
}

impl CredentialPresentation for IetfSdJwtVc {
    type Credential = serde_json::Value;
    type CredentialFormat = ClaimFormatDesignation;
    type PresentationFormat = ClaimFormatDesignation;

    fn credential(&self) -> &Self::Credential {
        &self.claims
    }

    fn presentation_format(&self) -> Self::PresentationFormat {
        ClaimFormatDesignation::DcSdJwt
    }

    fn credential_format(&self) -> Self::CredentialFormat {
        ClaimFormatDesignation::DcSdJwt
    }

    /// Return the credential as a VpToken with Key Binding JWT.
    async fn as_vp_token_item<'a>(
        &self,
        options: &'a PresentationOptions<'a>,
        selected_fields: Option<Vec<String>>,
    ) -> Result<VpTokenItem, OID4VPError> {
        // Build the SD-JWT with selective disclosure filtering.
        let mut sd_jwt = if let Some(selected_fields) = selected_fields {
            let selected_fields_pointers = selected_fields
                .into_iter()
                .map(|sfield| {
                    let segments: Vec<String> = sfield
                        .split(',')
                        .map(|segment| {
                            let bytes = URL_SAFE
                                .decode(segment)
                                .map_err(|e| OID4VPError::JsonPathParse(e.to_string()))?;
                            str::from_utf8(&bytes)
                                .map(|s| s.to_string())
                                .map_err(|e| OID4VPError::JsonPathParse(e.to_string()))
                        })
                        .collect::<Result<Vec<_>, _>>()?;

                    let pointer = format!("/{}", segments.join("/"));
                    JsonPointerBuf::new(pointer)
                        .map_err(|e| OID4VPError::JsonPathToPointer(e.to_string()))
                })
                .collect::<Result<Vec<_>, _>>()?;

            self.inner
                .decode_reveal::<AnyClaims>()
                .map_err(|e| OID4VPError::VpTokenParse(e.to_string()))?
                .retaining(&selected_fields_pointers)
                .into_encoded()
        } else {
            self.inner.clone()
        };

        // Create and attach Key Binding JWT (KB-JWT).
        let aud = options
            .audience()
            .ok_or_else(|| {
                OID4VPError::VpTokenCreate("missing client_id for KB-JWT audience".into())
            })?
            .clone();
        let nonce = options.nonce().clone();

        let kb_payload = KbJwtPayload::new(aud, nonce, SdAlg::Sha256, &sd_jwt);

        let jws_signer = PresentationJwsSigner {
            signer: options.signer.as_ref().as_ref(),
        };

        let kb_jwt = jws_signer
            .sign(kb_payload)
            .await
            .map_err(|e| OID4VPError::VpTokenCreate(format!("KB-JWT signing failed: {e:?}")))?;

        sd_jwt.set_kb(&kb_jwt);

        Ok(VpTokenItem::String(sd_jwt.as_str().to_string()))
    }
}

impl TryFrom<SdJwtBuf> for IetfSdJwtVc {
    type Error = IetfSdJwtVcError;

    fn try_from(value: SdJwtBuf) -> Result<Self, Self::Error> {
        let revealed = value
            .decode_reveal::<AnyClaims>()
            .map_err(|e| IetfSdJwtVcError::SdJwtDecoding(format!("{e:?}")))?;

        let claims = serde_json::to_value(revealed.claims())
            .map_err(|e| IetfSdJwtVcError::Serialization(format!("{e:?}")))?;

        if claims.get("vct").and_then(|v| v.as_str()).is_none() {
            return Err(IetfSdJwtVcError::MissingClaim("vct".to_string()));
        }

        Ok(IetfSdJwtVc {
            id: Uuid::new_v4(),
            key_alias: None,
            inner: value,
            claims,
        })
    }
}

impl TryFrom<(Uuid, SdJwtBuf)> for IetfSdJwtVc {
    type Error = IetfSdJwtVcError;

    fn try_from(value: (Uuid, SdJwtBuf)) -> Result<Self, Self::Error> {
        let revealed = value
            .1
            .decode_reveal::<AnyClaims>()
            .map_err(|e| IetfSdJwtVcError::SdJwtDecoding(format!("{e:?}")))?;

        let claims = serde_json::to_value(revealed.claims())
            .map_err(|e| IetfSdJwtVcError::Serialization(format!("{e:?}")))?;

        if claims.get("vct").and_then(|v| v.as_str()).is_none() {
            return Err(IetfSdJwtVcError::MissingClaim("vct".to_string()));
        }

        Ok(IetfSdJwtVc {
            id: value.0,
            key_alias: None,
            inner: value.1,
            claims,
        })
    }
}

impl TryFrom<&Credential> for IetfSdJwtVc {
    type Error = IetfSdJwtVcError;

    fn try_from(value: &Credential) -> Result<IetfSdJwtVc, IetfSdJwtVcError> {
        let inner = SdJwtBuf::new(value.payload.clone())
            .map_err(|_| IetfSdJwtVcError::InvalidSdJwt(Default::default()))?;

        let mut sd_jwt = IetfSdJwtVc::try_from(inner)?;
        sd_jwt.id = value.id;
        sd_jwt.key_alias = value.key_alias.clone();

        Ok(sd_jwt)
    }
}

impl TryFrom<Credential> for Arc<IetfSdJwtVc> {
    type Error = IetfSdJwtVcError;

    fn try_from(value: Credential) -> Result<Arc<IetfSdJwtVc>, IetfSdJwtVcError> {
        Ok(Arc::new(IetfSdJwtVc::try_from(&value)?))
    }
}

impl TryFrom<Arc<IetfSdJwtVc>> for Credential {
    type Error = IetfSdJwtVcError;

    fn try_from(value: Arc<IetfSdJwtVc>) -> Result<Self, Self::Error> {
        Ok(Credential {
            id: value.id,
            format: CredentialFormat::DcSdJwt,
            r#type: value.r#type(),
            payload: value.inner.as_bytes().into(),
            key_alias: value.key_alias.clone(),
        })
    }
}

#[derive(Debug, uniffi::Error, thiserror::Error)]
pub enum IetfSdJwtVcError {
    #[error("failed to initialize IETF SD-JWT VC: {0}")]
    InitError(String),
    #[error("failed to decode SD-JWT: {0}")]
    SdJwtDecoding(String),
    #[error("invalid SD-JWT: {0}")]
    InvalidSdJwt(String),
    #[error("serialization error: {0}")]
    Serialization(String),
    #[error("failed to encode credential: {0}")]
    CredentialEncoding(String),
    #[error("missing required claim: {0}")]
    MissingClaim(String),
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_format_identifier() {
        assert_eq!(FORMAT_DC_SD_JWT, "dc+sd-jwt");
    }

    #[test]
    fn test_parse_dc_sd_jwt_credential() {
        let credential = include_str!("../../../tests/examples/dc+sd-jwt.jwt");

        // Verify raw SD-JWT parsing and claim revelation.
        let buf = SdJwtBuf::new(credential.to_string()).expect("SdJwtBuf::new should succeed");
        let revealed = buf
            .decode_reveal::<AnyClaims>()
            .expect("decode_reveal should succeed");
        let claims = revealed.claims();
        assert!(
            claims.private.get("vct").is_some(),
            "revealed claims should contain vct"
        );

        // Verify IetfSdJwtVc construction and accessors.
        let vc = IetfSdJwtVc::new_from_compact_sd_jwt(credential.to_string())
            .expect("new_from_compact_sd_jwt should succeed");

        assert_eq!(vc.vct(), "eu.europa.ec.eudi.hiid.1");
        // This credential conveys issuer via x5c header, so iss claim is absent.
        assert_eq!(vc.issuer(), None);

        // Verify revealed claims include selectively disclosed fields.
        let json = vc
            .revealed_claims_as_json()
            .expect("revealed_claims_as_json should succeed");
        assert_eq!(
            json.get("health_insurance_id").and_then(|v| v.as_str()),
            Some("A123456780101575519DE")
        );
        assert_eq!(
            json.get("affiliation_country").and_then(|v| v.as_str()),
            Some("DE")
        );
        assert_eq!(
            json.get("issuing_country").and_then(|v| v.as_str()),
            Some("DE")
        );
    }
}
