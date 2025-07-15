use super::{
    status::{BitStringStatusListResolver, Status, StatusListError},
    Credential, CredentialEncodingError, CredentialFormat, VcdmVersion,
};
use crate::{
    crypto::KeyAlias,
    oid4vp::{
        error::OID4VPError,
        presentation::{CredentialPresentation, PresentationOptions},
        ResponseOptions,
    },
    CredentialType,
};

use std::sync::Arc;

use openid4vp::{
    core::{
        credential_format::ClaimFormatDesignation, presentation_submission::DescriptorMap,
        response::parameters::VpTokenItem,
    },
    JsonPath,
};
use serde_json::Value as Json;
use ssi::status::bitstring_status_list::BitstringStatusListEntry;
use ssi::{
    claims::vc::{
        syntax::{IdOr, NonEmptyObject, NonEmptyVec},
        v1::{Credential as _, JsonPresentation as JsonPresentationV1},
        v2::{
            syntax::JsonPresentation as JsonPresentationV2, Credential as _,
            JsonCredential as JsonCredentialV2,
        },
    },
    json_ld::iref::UriBuf,
    prelude::{AnyJsonCredential, AnyJsonPresentation},
};
use uuid::Uuid;

const ACCEPTED_CRYPTOSUITES: &[&str] = &["ecdsa-rdfc-2019"];

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

#[derive(uniffi::Object, Debug, Clone)]
/// A verifiable credential secured as JSON.
pub struct JsonVc {
    id: Uuid,
    pub(crate) raw: Json,
    credential_string: String,
    parsed: AnyJsonCredential,
    key_alias: Option<KeyAlias>,
}

#[uniffi::export(async_runtime = "tokio")]
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

    /// Returns the status of the credential, resolving the value in the status list,
    /// along with the purpose of the status.
    pub async fn status(&self) -> Result<Status, StatusListError> {
        self.status_list_value().await
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
        _options: ResponseOptions,
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
            DescriptorMap::new(id.clone(), self.presentation_format(), JsonPath::default())
                .set_path_nested(DescriptorMap::new(id, self.credential_format(), path)),
        )
    }

    /// Return the credential as a VpToken
    async fn as_vp_token_item<'a>(
        &self,
        options: &'a PresentationOptions<'a>,
        _selected_fields: Option<Vec<String>>,
        _limit_disclosure: bool,
    ) -> Result<VpTokenItem, OID4VPError> {
        let id = UriBuf::new(format!("urn:uuid:{}", Uuid::new_v4()).as_bytes().to_vec())
            .map_err(|e| CredentialEncodingError::VpToken(format!("Error parsing ID: {e:?}")))?;

        // Check the signer supports the requested vp format crypto suite.
        options.supports_security_method(ClaimFormatDesignation::LdpVp)?;

        let unsigned_presentation = match self.parsed.clone() {
            AnyJsonCredential::V1(cred_v1) => {
                let holder_id: UriBuf = options.signer.did().parse().map_err(|e| {
                    CredentialEncodingError::VpToken(format!("Error parsing DID: {e:?}"))
                })?;

                let unsigned_presentation_v1 =
                    JsonPresentationV1::new(Some(id.clone()), Some(holder_id), vec![cred_v1]);

                AnyJsonPresentation::V1(unsigned_presentation_v1)
            }
            AnyJsonCredential::V2(cred_v2) => {
                // Convert inner type of `Object` -> `NonEmptyObject`.
                let mut cred_v2 = try_map_subjects(cred_v2, NonEmptyObject::try_from_object)
                    .map_err(|e| OID4VPError::EmptyCredentialSubject(format!("{e:?}")))?;

                // TODO: Handle transformation of the selective disclosure.
                // SKIP: Remove SD proof from the credential before adding it to the presentation.
                if let Some(p) = cred_v2
                    .extra_properties
                    .get_mut("proof")
                    .and_then(|p| p.as_array_mut())
                {
                    *p = p
                        .iter_mut()
                        .flat_map(|p| p.as_object())
                        .filter(|obj| {
                            while let Some(cryptosuite) = obj.get("cryptosuite").next() {
                                if let Some(suite) = cryptosuite.as_string() {
                                    // Check if the cryptosuite is supported.
                                    // NOTE: we're filtering proofs for only supported
                                    // cryptosuites, e.g., `ecdsa-rdfc-2019`
                                    return ACCEPTED_CRYPTOSUITES.contains(&suite);
                                }
                            }
                            true
                        })
                        .map(|p| p.clone().into())
                        .collect::<Vec<_>>();
                }

                let holder_id = IdOr::Id(options.signer.did().parse().map_err(|e| {
                    CredentialEncodingError::VpToken(format!("Error parsing DID: {e:?}"))
                })?);

                let unsigned_presentation_v2 =
                    JsonPresentationV2::new(Some(id), vec![holder_id], vec![cred_v2]);

                AnyJsonPresentation::V2(unsigned_presentation_v2)
            }
        };

        let signed_presentation = options.sign_presentation(unsigned_presentation).await?;

        Ok(VpTokenItem::from(signed_presentation))
    }
}

impl BitStringStatusListResolver for JsonVc {
    fn status_list_entry(&self) -> Result<BitstringStatusListEntry, StatusListError> {
        let value = match &self.parsed {
            AnyJsonCredential::V1(credential) => credential
                .credential_status
                .first()
                .map(serde_json::to_value),
            AnyJsonCredential::V2(credential) => credential
                .credential_status
                .first()
                .map(serde_json::to_value),
        }
        .ok_or(StatusListError::Resolution(
            "Credential status not found in credential".into(),
        ))?
        .map_err(|e| StatusListError::Resolution(format!("{e:?}")))?;

        let entry = serde_json::from_value(value).map_err(|e| {
            StatusListError::Resolution(format!("Failed to parse credential status: {e:?}"))
        })?;

        Ok(entry)
    }

    // NOTE: The remaining methods are default implemented in the trait.
}

impl TryFrom<Credential> for Arc<JsonVc> {
    type Error = JsonVcInitError;

    fn try_from(credential: Credential) -> Result<Self, Self::Error> {
        JsonVc::from_json_bytes(credential.id, credential.payload, credential.key_alias)
    }
}

// NOTE: This is an temporary solution to convert an inner type of a credential,
// i.e. `Object` -> `NonEmptyObject`.
//
// This should be removed once fixed in ssi crate.
fn try_map_subjects<T, U, E: std::fmt::Debug>(
    cred: JsonCredentialV2<T>,
    f: impl FnMut(T) -> Result<U, E>,
) -> Result<JsonCredentialV2<U>, OID4VPError> {
    Ok(JsonCredentialV2 {
        name: cred.name,
        description: cred.description,
        context: cred.context,
        id: cred.id,
        types: cred.types,
        credential_subjects: NonEmptyVec::try_from_vec(
            cred.credential_subjects
                .into_iter()
                .map(f)
                .collect::<Result<_, _>>()
                .map_err(|e| OID4VPError::EmptyCredentialSubject(format!("{e:?}")))?,
        )
        .map_err(|e| OID4VPError::EmptyCredentialSubject(format!("{e:?}")))?,
        issuer: cred.issuer,
        valid_from: cred.valid_from,
        valid_until: cred.valid_until,
        credential_status: cred.credential_status,
        terms_of_use: cred.terms_of_use,
        evidence: cred.evidence,
        credential_schema: cred.credential_schema,
        refresh_services: cred.refresh_services,
        extra_properties: cred.extra_properties,
    })
}
