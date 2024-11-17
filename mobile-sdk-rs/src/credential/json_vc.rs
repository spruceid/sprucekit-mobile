use super::{Credential, CredentialEncodingError, CredentialFormat, VcdmVersion};
use crate::{
    oid4vp::{
        error::OID4VPError,
        presentation::{CredentialPresentation, PresentationOptions},
    },
    CredentialType, KeyAlias,
};

use std::{collections::BTreeMap, sync::Arc};

use openid4vp::core::{
    credential_format::ClaimFormatDesignation, presentation_submission::DescriptorMap,
    response::parameters::VpTokenItem,
};
use serde_json::Value as Json;
use ssi::{
    claims::vc::{
        syntax::IdOr,
        v1::{Credential as _, JsonPresentation as JsonPresentationV1},
        v2::{syntax::JsonPresentation as JsonPresentationV2, Credential as _},
        AnySpecializedJsonCredential,
    },
    json_ld::iref::UriBuf,
    prelude::{AnyJsonCredential, DataIntegrityDocument},
};
use uuid::Uuid;

#[derive(uniffi::Object, Debug, Clone)]
/// A verifiable credential secured as JSON.
pub struct JsonVc {
    id: Uuid,
    raw: Json,
    credential_string: String,
    parsed: AnyJsonCredential,
    key_alias: Option<KeyAlias>,
}

#[uniffi::export]
impl JsonVc {
    #[uniffi::constructor]
    /// Construct a new credential from UTF-8 encoded JSON.
    pub fn new_from_json(utf8_json_string: String) -> Result<Arc<Self>, JsonVcInitError> {
        let id = Uuid::new_v4();
        let json = serde_json::from_str(&utf8_json_string)
            .map_err(|_| JsonVcInitError::JsonStringDecoding)?;
        Self::from_json(id, json, None)
    }

    #[uniffi::constructor]
    /// Construct a new credential from UTF-8 encoded JSON.
    pub fn new_from_json_with_key(
        utf8_json_string: String,
        key_alias: KeyAlias,
    ) -> Result<Arc<Self>, JsonVcInitError> {
        let id = Uuid::new_v4();
        let json = serde_json::from_str(&utf8_json_string)
            .map_err(|_| JsonVcInitError::JsonStringDecoding)?;
        Self::from_json(id, json, Some(key_alias))
    }

    /// The keypair identified in the credential for use in a verifiable presentation.
    pub fn key_alias(&self) -> Option<KeyAlias> {
        self.key_alias.clone()
    }

    /// The local ID of this credential.
    pub fn id(&self) -> Uuid {
        self.id
    }

    /// The version of the Verifiable Credential Data Model that this credential conforms to.
    pub fn vcdm_version(&self) -> VcdmVersion {
        match &self.parsed {
            ssi::claims::vc::AnySpecializedJsonCredential::V1(_) => VcdmVersion::V1,
            ssi::claims::vc::AnySpecializedJsonCredential::V2(_) => VcdmVersion::V2,
        }
    }

    /// Access the W3C VCDM credential as a JSON encoded UTF-8 string.
    pub fn credential_as_json_encoded_utf8_string(&self) -> String {
        self.credential_string.clone()
    }

    /// The type of this credential. Note that if there is more than one type (i.e. `types()`
    /// returns more than one value), then the types will be concatenated with a "+".
    pub fn r#type(&self) -> CredentialType {
        CredentialType(self.types().join("+"))
    }

    /// The types of the credential from the VCDM, excluding the base `VerifiableCredential` type.
    pub fn types(&self) -> Vec<String> {
        match &self.parsed {
            ssi::claims::vc::AnySpecializedJsonCredential::V1(vc) => vc.additional_types().to_vec(),
            ssi::claims::vc::AnySpecializedJsonCredential::V2(vc) => vc.additional_types().to_vec(),
        }
    }
}

impl JsonVc {
    pub(crate) fn to_json_bytes(&self) -> Result<Vec<u8>, JsonVcEncodingError> {
        serde_json::to_vec(&self.raw).map_err(|_| JsonVcEncodingError::JsonBytesEncoding)
    }

    fn from_json_bytes(
        id: Uuid,
        raw: Vec<u8>,
        key_alias: Option<KeyAlias>,
    ) -> Result<Arc<Self>, JsonVcInitError> {
        let json = serde_json::from_slice(&raw).map_err(|_| JsonVcInitError::JsonBytesDecoding)?;
        Self::from_json(id, json, key_alias)
    }

    fn from_json(
        id: Uuid,
        json: Json,
        key_alias: Option<KeyAlias>,
    ) -> Result<Arc<Self>, JsonVcInitError> {
        let raw = json;

        let parsed =
            serde_json::from_value(raw.clone()).map_err(|_| JsonVcInitError::CredentialDecoding)?;

        let credential_string = serde_json::to_string(&parsed)
            .map_err(|_| JsonVcInitError::CredentialStringEncoding)?;

        Ok(Arc::new(Self {
            id,
            raw,
            credential_string,
            parsed,
            key_alias,
        }))
    }

    pub fn format() -> CredentialFormat {
        CredentialFormat::LdpVc
    }
}

impl CredentialPresentation for JsonVc {
    type Credential = Json;
    type CredentialFormat = ClaimFormatDesignation;
    type PresentationFormat = ClaimFormatDesignation;

    fn credential(&self) -> &Self::Credential {
        &self.raw
    }

    fn presentation_format(&self) -> Self::PresentationFormat {
        ClaimFormatDesignation::LdpVp
    }

    fn credential_format(&self) -> Self::CredentialFormat {
        ClaimFormatDesignation::LdpVc
    }

    fn create_descriptor_map(
        &self,
        input_descriptor_id: impl Into<String>,
        index: Option<usize>,
    ) -> Result<DescriptorMap, OID4VPError> {
        let path = match index {
            Some(idx) => format!("$.verifiableCredential[{idx}]"),
            None => "$.verifiableCredential".into(),
        }
        .parse()
        .map_err(|e| OID4VPError::JsonPathParse(format!("{e:?}")))?;

        let id = input_descriptor_id.into();

        Ok(
            DescriptorMap::new(id.clone(), self.presentation_format(), "$".parse().unwrap())
                .set_path_nested(DescriptorMap::new(id, self.credential_format(), path)),
        )
    }

    /// Return the credential as a VpToken
    async fn as_vp_token_item<'a>(
        &self,
        options: &'a PresentationOptions<'a>,
    ) -> Result<VpTokenItem, OID4VPError> {
        let id = UriBuf::new(format!("urn:uuid:{}", Uuid::new_v4()).as_bytes().to_vec())
            .map_err(|e| CredentialEncodingError::VpToken(format!("Error parsing ID: {e:?}")))?;

        // Check the signer supports the requested vp format crypto suite.
        options.supports_cryptosuite(ClaimFormatDesignation::LdpVp)?;

        let vp_token_item = match &self.parsed {
            AnySpecializedJsonCredential::V1(cred_v1) => {
                let holder_id: UriBuf = options.signer.did().parse().map_err(|e| {
                    CredentialEncodingError::VpToken(format!("Error parsing DID: {e:?}"))
                })?;

                let presentation_v1 = JsonPresentationV1::new(
                    Some(id.clone()),
                    Some(holder_id.clone()),
                    vec![cred_v1.clone()],
                );

                // let json = serde_json::to_value(&presentation_v1).map_err(|e| {
                //     CredentialEncodingError::VpToken(format!("Error encoding VP: {e:?}"))
                // })?;

                let credentials = serde_json::to_value(vec![cred_v1.clone()]).map_err(|e| {
                    CredentialEncodingError::VpToken(format!("Error encoding VP: {e:?}"))
                })?;

                let mut properties = BTreeMap::new();
                properties.insert("id".to_string(), json_syntax::Value::from(id.to_string()));
                properties.insert(
                    "holder".to_string(),
                    json_syntax::Value::from(holder_id.to_string()),
                );
                properties.insert(
                    "verifiableCredential".to_string(),
                    json_syntax::Value::from_serde_json(credentials),
                );

                let data_integrity = options
                    .sign_data_integrity_doc(DataIntegrityDocument {
                        context: Some(presentation_v1.context.as_ref().clone()),
                        types: ssi::OneOrMany::Many(
                            presentation_v1.types.to_json_ld_types().into(),
                        ),
                        properties: presentation_v1.additional_properties,
                    })
                    .await?;

                println!("data_integrity: {:?}", data_integrity);

                VpTokenItem::from(data_integrity)
            }
            AnySpecializedJsonCredential::V2(cred_v2) => {
                let holder_id = IdOr::Id(options.signer.did().parse().map_err(|e| {
                    CredentialEncodingError::VpToken(format!("Error parsing DID: {e:?}"))
                })?);

                let presentation_v2 =
                    JsonPresentationV2::new(Some(id), vec![holder_id], vec![cred_v2.clone()]);

                let data_integrity = options
                    .sign_data_integrity_doc(DataIntegrityDocument {
                        context: Some(presentation_v2.context.as_ref().clone()),
                        types: ssi::OneOrMany::Many(
                            presentation_v2.types.to_json_ld_types().into(),
                        ),
                        properties: presentation_v2.additional_properties.clone(),
                    })
                    .await?;

                println!("data_integrity: {:?}", data_integrity);

                VpTokenItem::from(data_integrity)
            }
        };

        Ok(vp_token_item)
    }
}

impl TryFrom<Credential> for Arc<JsonVc> {
    type Error = JsonVcInitError;

    fn try_from(credential: Credential) -> Result<Self, Self::Error> {
        JsonVc::from_json_bytes(credential.id, credential.payload, credential.key_alias)
    }
}

#[derive(Debug, uniffi::Error, thiserror::Error)]
pub enum JsonVcInitError {
    #[error("failed to decode a W3C VCDM (v1 or v2) Credential from JSON")]
    CredentialDecoding,
    #[error("failed to encode the credential as a UTF-8 string")]
    CredentialStringEncoding,
    #[error("failed to decode JSON from bytes")]
    JsonBytesDecoding,
    #[error("failed to decode JSON from a UTF-8 string")]
    JsonStringDecoding,
}

#[derive(Debug, uniffi::Error, thiserror::Error)]
pub enum JsonVcEncodingError {
    #[error("failed to encode JSON as bytes")]
    JsonBytesEncoding,
}
