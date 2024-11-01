use super::{Credential, CredentialFormat, VcdmVersion};
use crate::{oid4vp::permission_request::RequestedField, CredentialType, KeyAlias};

use std::sync::Arc;

use base64::prelude::*;
use openid4vp::core::{
    presentation_definition::PresentationDefinition, response::parameters::VpTokenItem,
};
use ssi::{
    claims::{
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
    jws: JwsString,
    credential: JsonCredential,
    credential_string: String,
    header_json_string: String,
    payload_json_string: String,
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
        Ok(Arc::new(Self {
            id,
            jws,
            credential,
            credential_string,
            header_json_string,
            payload_json_string,
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

    /// Check if the credential satisfies a presentation definition.
    pub fn check_presentation_definition(&self, definition: &PresentationDefinition) -> bool {
        // If the credential does not match the definition requested format,
        // then return false.
        if !definition.format().is_empty()
            && !definition.contains_format(CredentialFormat::JwtVcJson.to_string().as_str())
        {
            return false;
        }

        let Ok(json) = serde_json::to_value(&self.credential) else {
            // NOTE: if we cannot convert the credential to a JSON value, then we cannot
            // check the presentation definition, so we return false.
            //
            tracing::debug!(
                "failed to convert credential '{}' to json, so continuing to the next credential",
                self.id()
            );
            return false;
        };

        // Check the JSON-encoded credential against the definition.
        definition.is_credential_match(&json)
    }

    /// Returns the requested fields given a presentation definition.
    pub fn requested_fields(
        &self,
        definition: &PresentationDefinition,
    ) -> Vec<Arc<RequestedField>> {
        let Ok(json) = serde_json::to_value(&self.credential) else {
            // NOTE: if we cannot convert the credential to a JSON value, then we cannot
            // check the presentation definition, so we return false.
            log::debug!("credential could not be converted to JSON: {self:?}");
            return Vec::new();
        };

        definition
            .requested_fields(&json)
            .into_iter()
            .map(Into::into)
            .map(Arc::new)
            .collect()
    }

    /// Return the credential as a VpToken
    pub fn as_vp_token(&self) -> VpTokenItem {
        let id = UriBuf::new(format!("urn:uuid:{}", Uuid::new_v4()).as_bytes().to_vec()).ok();

        // TODO: determine how the holder ID should be set.
        let holder_id = None;

        // NOTE: JwtVc types are ALWAYS VCDM 1.1, therefore using the v1::syntax::JsonPresentation
        // type.
        VpTokenItem::from(JsonPresentation::new(
            id,
            holder_id,
            vec![self.credential.clone()],
        ))
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
