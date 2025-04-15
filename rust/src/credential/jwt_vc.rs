use super::{Credential, CredentialEncodingError, CredentialFormat, VcdmVersion};
use crate::{
    crypto::KeyAlias,
    oid4vp::{
        error::OID4VPError,
        presentation::{CredentialPresentation, PresentationOptions},
        PresentationError, ResponseOptions,
    },
    CredentialType,
};

use std::sync::Arc;

use base64::prelude::*;
use openid4vp::core::{
    credential_format::ClaimFormatDesignation, presentation_submission::DescriptorMap,
    response::parameters::VpTokenItem,
};
use ssi::{
    claims::{
        jws::Header,
        jwt::IntoDecodedJwt,
        vc::v1::{Credential as _, JsonCredential, JsonPresentation},
        JwsString,
    },
    json_ld::iref::UriBuf,
};
use uuid::Uuid;

#[derive(uniffi::Object, Debug, Clone)]
/// A verifiable credential secured as a JWT.
pub struct JwtVc {
    id: Uuid,
    pub(crate) jws: JwsString,
    credential: JsonCredential,
    credential_string: String,
    header_json_string: String,
    payload_json_string: String,
    payload_json: serde_json::Value,
    key_alias: Option<KeyAlias>,
}

#[uniffi::export]
impl JwtVc {
    #[uniffi::constructor]
    /// Construct a new credential from a compact JWS (of the form
    /// `<base64-encoded-header>.<base64-encoded-payload>.<base64-encoded-signature>`),
    /// without an associated keypair.
    pub fn new_from_compact_jws(jws: String) -> Result<Arc<Self>, JwtVcInitError> {
        let id = Uuid::new_v4();
        Self::from_compact_jws(id, jws, None)
    }

    #[uniffi::constructor]
    /// Construct a new credential from a compact JWS (of the form
    /// `<base64-encoded-header>.<base64-encoded-payload>.<base64-encoded-signature>`),
    /// with an associated keypair.
    pub fn new_from_compact_jws_with_key(
        jws: String,
        key_alias: KeyAlias,
    ) -> Result<Arc<Self>, JwtVcInitError> {
        let id = Uuid::new_v4();
        Self::from_compact_jws(id, jws, Some(key_alias))
    }

    /// The VdcCollection ID for this credential.
    pub fn id(&self) -> Uuid {
        self.id
    }

    /// The version of the Verifiable Credential Data Model that this credential conforms to.
    pub fn vcdm_version(&self) -> VcdmVersion {
        VcdmVersion::V1
    }

    /// The type of this credential. Note that if there is more than one type (i.e. `types()`
    /// returns more than one value), then the types will be concatenated with a "+".
    pub fn r#type(&self) -> CredentialType {
        CredentialType(self.types().join("+"))
    }

    /// The types of the credential from the VCDM, excluding the base `VerifiableCredential` type.
    pub fn types(&self) -> Vec<String> {
        self.credential.additional_types().to_vec()
    }

    /// Access the W3C VCDM credential as a JSON encoded UTF-8 string.
    pub fn credential_as_json_encoded_utf8_string(&self) -> String {
        self.credential_string.clone()
    }

    /// Access the JWS header as a JSON encoded UTF-8 string.
    pub fn jws_header_as_json_encoded_utf8_string(&self) -> String {
        self.header_json_string.clone()
    }

    /// Access the JWS payload as a JSON encoded UTF-8 string.
    pub fn jws_payload_as_json_encoded_utf8_string(&self) -> String {
        self.payload_json_string.clone()
    }

    /// The keypair identified in the credential for use in a verifiable presentation.
    pub fn key_alias(&self) -> Option<KeyAlias> {
        self.key_alias.clone()
    }
}

impl JwtVc {
    pub(crate) fn to_compact_jws_bytes(&self) -> Vec<u8> {
        self.jws.as_bytes().to_vec()
    }

    pub(crate) fn from_compact_jws_bytes(
        id: Uuid,
        raw: Vec<u8>,
        key_alias: Option<KeyAlias>,
    ) -> Result<Arc<Self>, JwtVcInitError> {
        let jws = String::from_utf8(raw).map_err(|_| JwtVcInitError::JwsBytesDecoding)?;
        Self::from_compact_jws(id, jws, key_alias)
    }

    fn from_compact_jws(
        id: Uuid,
        jws: String,
        key_alias: Option<KeyAlias>,
    ) -> Result<Arc<Self>, JwtVcInitError> {
        let jws = JwsString::from_string(jws).map_err(|_| JwtVcInitError::CompactJwsDecoding)?;
        let header_json_string =
            Self::convert_to_json_string(jws.header()).ok_or(JwtVcInitError::HeaderDecoding)?;
        let payload_json_string =
            Self::convert_to_json_string(jws.payload()).ok_or(JwtVcInitError::PayloadDecoding)?;
        let credential = serde_json::from_value(
            jws.clone()
                .into_decoded_jwt()
                .map_err(|_| JwtVcInitError::JwtDecoding)?
                .signing_bytes
                .payload
                .registered
                .remove::<ssi::claims::jwt::VerifiableCredential>()
                .ok_or(JwtVcInitError::CredentialClaimMissing)?
                .0
                .into(),
        )
        .map_err(|_| JwtVcInitError::CredentialClaimDecoding)?;
        let credential_string = serde_json::to_string(&credential)
            .map_err(|_| JwtVcInitError::CredentialStringEncoding)?;

        let payload_json = serde_json::from_str(&payload_json_string)
            .map_err(|_| JwtVcInitError::PayloadDecoding)?;

        Ok(Arc::new(Self {
            id,
            jws,
            credential,
            credential_string,
            header_json_string,
            payload_json_string,
            payload_json,
            key_alias,
        }))
    }

    fn convert_to_json_string(base64_encoded_bytes: &[u8]) -> Option<String> {
        String::from_utf8(BASE64_STANDARD_NO_PAD.decode(base64_encoded_bytes).ok()?).ok()
    }

    /// Return the internal `AnyJsonCredential` type
    pub fn credential(&self) -> &JsonCredential {
        &self.credential
    }

    pub fn format() -> CredentialFormat {
        CredentialFormat::JwtVcJson
    }
}

impl CredentialPresentation for JwtVc {
    type Credential = serde_json::Value;
    type CredentialFormat = ClaimFormatDesignation;
    type PresentationFormat = ClaimFormatDesignation;

    fn credential(&self) -> &Self::Credential {
        &self.payload_json
    }

    fn presentation_format(&self) -> Self::PresentationFormat {
        ClaimFormatDesignation::JwtVpJson
    }

    fn credential_format(&self) -> Self::CredentialFormat {
        ClaimFormatDesignation::JwtVcJson
    }

    /// Return the credential as a VpToken
    async fn as_vp_token_item<'a>(
        &self,
        options: &'a PresentationOptions<'a>,
        _selected_fields: Option<Vec<String>>,
        _limit_disclosure: bool,
    ) -> Result<VpTokenItem, OID4VPError> {
        let vm = options.verification_method_id().await?.to_string();
        let holder_id = options.signer.did();

        let subject = self
            .credential()
            .credential_subjects
            .iter()
            .flat_map(|obj| obj.get("id"))
            .find(|id| id.as_str() == Some(&holder_id));

        if subject.is_none() {
            return Err(OID4VPError::VpTokenCreate(
                "supplied verificationMethod does not match the subject of the jwt-vc".into(),
            ));
        }

        let mut vp = serde_json::to_value(JsonPresentation::new(
            UriBuf::new(format!("urn:uuid:{}", Uuid::new_v4()).as_bytes().to_vec()).ok(),
            holder_id.parse().ok(),
            vec![self.jws.clone()],
        ))
        .map_err(|e| OID4VPError::VpTokenCreate(format!("{e:?}")))?;

        // TODO: consider upstreaming this option to SSI library.
        // Currently handling it here as a configurable option
        // for backwards compatbility.
        if options.response_options.force_array_serialization {
            if let Some(vc) = vp.get_mut("verifiableCredential") {
                if vc.is_object() || vc.is_string() {
                    let vc_obj = vc.take();
                    *vc = serde_json::Value::Array(vec![vc_obj]);
                }
            }
        }

        let iat = time::OffsetDateTime::now_utc().unix_timestamp();
        let exp = iat + 3600;

        let iss = options.issuer();
        let aud = options.audience();
        let nonce = options.nonce();
        let subject = options.subject();

        let key_id = Some(vm);
        let algorithm = options.signer.algorithm().try_into().map_err(|e| {
            CredentialEncodingError::VpToken(format!("Invalid Signing Algorithm: {e:?}"))
        })?;

        let header = Header {
            // NOTE: The algorithm should match the signing
            // algorithm of the key used to sign the vp token.
            algorithm,
            key_id,
            ..Default::default()
        };

        let header_b64: String = serde_json::to_vec(&header)
            .map(|b| BASE64_URL_SAFE_NO_PAD.encode(b))
            .map_err(|e| CredentialEncodingError::VpToken(format!("{e:?}")))?;

        let claims = serde_json::json!({
            "iat": iat,
            "exp": exp,
            "iss": iss,
            "sub": subject,
            "aud": aud,
            "nonce": nonce,
            "vp": vp,
        });

        let body_b64 = serde_json::to_vec(&claims)
            .map(|b| BASE64_URL_SAFE_NO_PAD.encode(b))
            .map_err(|e| CredentialEncodingError::VpToken(format!("{e:?}")))?;

        let unsigned_vp_token_jwt = format!("{header_b64}.{body_b64}");

        // Sign the `vp_token` if a `signer` is provided in the `VpTokenOptions`.
        let signature = options
            .signer
            .sign(unsigned_vp_token_jwt.as_bytes().to_vec())
            .await
            .map_err(|e| CredentialEncodingError::VpToken(format!("{e:?}")))?;

        let signature = options
            .curve_utils()
            .map(|utils| utils.ensure_raw_fixed_width_signature_encoding(signature))?
            .ok_or(OID4VPError::Presentation(PresentationError::Signing(
                "Unsupported signature encoding.".into(),
            )))?;

        let signature_b64 = BASE64_URL_SAFE_NO_PAD.encode(&signature);

        Ok(VpTokenItem::String(format!(
            "{unsigned_vp_token_jwt}.{signature_b64}"
        )))
    }

    fn create_descriptor_map(
        &self,
        options: ResponseOptions,
        input_descriptor_id: impl Into<String>,
        index: Option<usize>,
    ) -> Result<DescriptorMap, OID4VPError> {
        let id = input_descriptor_id.into();
        let vp_path = if options.remove_vp_path_prefix {
            "$"
        } else {
            "$.vp"
        }
        .parse()
        .map_err(|e| OID4VPError::JsonPathParse(format!("{e:?}")))?;

        let cred_path = match index {
            Some(idx) => format!("$.verifiableCredential[{idx}]"),
            None => {
                if options.force_array_serialization {
                    "$.verifiableCredential[0]".into()
                } else {
                    "$.verifiableCredential".into()
                }
            }
        }
        .parse()
        .map_err(|e| OID4VPError::JsonPathParse(format!("{e:?}")))?;

        Ok(
            DescriptorMap::new(id.clone(), self.presentation_format(), vp_path)
                .set_path_nested(DescriptorMap::new(id, self.credential_format(), cred_path)),
        )
    }
}

impl TryFrom<Credential> for Arc<JwtVc> {
    type Error = JwtVcInitError;

    fn try_from(credential: Credential) -> Result<Self, Self::Error> {
        JwtVc::from_compact_jws_bytes(credential.id, credential.payload, credential.key_alias)
    }
}

impl TryFrom<&Credential> for Arc<JwtVc> {
    type Error = JwtVcInitError;

    fn try_from(credential: &Credential) -> Result<Self, Self::Error> {
        JwtVc::from_compact_jws_bytes(
            credential.id,
            credential.payload.clone(),
            credential.key_alias.clone(),
        )
    }
}

#[derive(Debug, uniffi::Error, thiserror::Error)]
pub enum JwtVcInitError {
    #[error("failed to decode string as a JWS of the form <base64-encoded-header>.<base64-encoded-payload>.<base64-encoded-signature>")]
    CompactJwsDecoding,
    #[error("failed to decode claim 'vc' as a W3C VCDM v1 or v2 credential")]
    CredentialClaimDecoding,
    #[error("'vc' is missing from the JWT claims")]
    CredentialClaimMissing,
    #[error("failed to encode the credential as a UTF-8 string")]
    CredentialStringEncoding,
    #[error("failed to decode JWS bytes as UTF-8")]
    JwsBytesDecoding,
    #[error("failed to decode JWS as a JWT")]
    JwtDecoding,
    #[error("failed to decode JWT header as base64-encoded JSON")]
    HeaderDecoding,
    #[error("failed to decode JWT payload as base64-encoded JSON")]
    PayloadDecoding,
}
