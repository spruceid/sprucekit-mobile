pub mod cwt;
pub mod json_vc;
pub mod jwt_vc;
pub mod mdoc;
pub mod status;
pub mod status_20240406;
pub mod vcdm2_sd_jwt;

use std::sync::Arc;

use crate::{
    crypto::KeyAlias,
    oid4vp::{
        error::OID4VPError,
        permission_request::RequestedField,
        presentation::{CredentialPresentation, PresentationError, PresentationOptions},
        ResponseOptions,
    },
    CredentialType,
};
use cwt::{Cwt, CwtError};
use json_vc::{JsonVc, JsonVcEncodingError, JsonVcInitError};
use jwt_vc::{JwtVc, JwtVcInitError};
use mdoc::{Mdoc, MdocEncodingError, MdocInitError};
use openid4vp::core::{
    presentation_definition::PresentationDefinition, presentation_submission::DescriptorMap,
    response::parameters::VpTokenItem,
};
use serde::{Deserialize, Serialize};
use status::BitStringStatusListResolver;
use status_20240406::BitStringStatusListResolver20240406;
use uuid::Uuid;
use vcdm2_sd_jwt::{SdJwtError, VCDM2SdJwt};

/// An unparsed credential, retrieved from storage.
#[derive(Debug, Clone, Serialize, Deserialize, uniffi::Record)]
pub struct Credential {
    /// The local ID of this credential.
    pub id: Uuid,
    /// The format of this credential.
    pub format: CredentialFormat,
    /// The type of this credential.
    pub r#type: CredentialType,
    /// The raw payload of this credential. The encoding depends on the format.
    pub payload: Vec<u8>,
    /// The alias of the key that is authorized to present this credential.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub key_alias: Option<KeyAlias>,
}

// Internal helper methods.
impl Credential {
    /// Convert the parsed credential into a specialized JSON credential.
    pub fn try_into_parsed(&self) -> Result<Arc<ParsedCredential>, CredentialDecodingError> {
        self.to_owned().try_into()
    }
}

/// A credential that has been parsed as a known variant.
#[derive(Debug, Clone, uniffi::Object)]
pub struct ParsedCredential {
    pub(crate) inner: ParsedCredentialInner,
}
/// A credential that has been parsed as a known variant.
#[derive(Debug, Clone, uniffi::Object)]
pub struct PresentableCredential {
    pub(crate) inner: ParsedCredentialInner,
    pub(crate) limit_disclosure: bool,
    pub(crate) selected_fields: Option<Vec<String>>,
}

/// A credential that has been parsed as a known variant.
#[derive(Debug, Clone)]
pub(crate) enum ParsedCredentialInner {
    MsoMdoc(Arc<Mdoc>),
    JwtVcJson(Arc<JwtVc>),
    JwtVcJsonLd(Arc<JwtVc>),
    VCDM2SdJwt(Arc<VCDM2SdJwt>),
    LdpVc(Arc<JsonVc>),
    Cwt(Arc<Cwt>),
    // More to come, for example:
    // SdJwt(...),
    // SdJwtJoseCose(...),
}

#[uniffi::export]
impl PresentableCredential {
    /// Converts to the primitive ParsedCredential type
    pub fn as_parsed_credential(&self) -> Arc<ParsedCredential> {
        Arc::new(ParsedCredential {
            inner: self.inner.clone(),
        })
    }

    /// Return if the credential supports selective disclosure
    /// For now only SdJwts are supported
    pub fn selective_disclosable(&self) -> bool {
        match &self.inner {
            ParsedCredentialInner::MsoMdoc(_) => false,
            ParsedCredentialInner::JwtVcJson(_) => false,
            ParsedCredentialInner::JwtVcJsonLd(_) => false,
            ParsedCredentialInner::VCDM2SdJwt(_) => true,
            ParsedCredentialInner::LdpVc(_) => false,
            ParsedCredentialInner::Cwt(_) => false,
        }
    }
}

#[uniffi::export]
impl ParsedCredential {
    /// This method attempts to parse the credential depending on the credential format type provided.
    #[uniffi::constructor]
    pub fn new_from_string_with_format(
        format: String,
        credential: String,
        key_alias: KeyAlias,
    ) -> Result<Arc<Self>, CredentialDecodingError> {
        let format = CredentialFormat::from(format);

        match format {
            CredentialFormat::MsoMdoc => {
                let mdoc = Mdoc::from_stringified_document(credential, key_alias)?;
                Ok(ParsedCredential::new_mso_mdoc(mdoc))
            }
            CredentialFormat::JwtVcJson => {
                let jwt_vc = JwtVc::new_from_compact_jws_with_key(credential, key_alias)?;
                Ok(ParsedCredential::new_jwt_vc_json(jwt_vc))
            }
            CredentialFormat::JwtVcJsonLd => {
                let jwt_vc = JwtVc::new_from_compact_jws_with_key(credential, key_alias)?;
                Ok(ParsedCredential::new_jwt_vc_json_ld(jwt_vc))
            }
            CredentialFormat::LdpVc => {
                let json_vc = JsonVc::new_from_json_with_key(credential, key_alias)?;
                Ok(ParsedCredential::new_ldp_vc(json_vc))
            }
            CredentialFormat::VCDM2SdJwt => {
                let sd_jwt = VCDM2SdJwt::new_from_compact_sd_jwt_with_key(credential, key_alias)?;
                Ok(ParsedCredential::new_sd_jwt(sd_jwt))
            }
            CredentialFormat::Cwt => {
                let cwt = Cwt::new_from_base10(credential)?;
                Ok(ParsedCredential::new_cwt(cwt))
            }
            CredentialFormat::Other(_) => Err(
                CredentialDecodingError::UnsupportedCredentialFormat(format.to_string()),
            ),
        }
    }

    #[uniffi::constructor]
    pub fn new_from_json(json_string: String) -> Result<Arc<Self>, CredentialDecodingError> {
        let credential: Credential = serde_json::from_str(&json_string)
            .map_err(|e| CredentialDecodingError::Serialization(format!("{e:?}")))?;
        credential.try_into_parsed()
    }

    #[uniffi::constructor]
    /// Construct a new `mso_mdoc` credential.
    pub fn new_mso_mdoc(mdoc: Arc<Mdoc>) -> Arc<Self> {
        Arc::new(Self {
            inner: ParsedCredentialInner::MsoMdoc(mdoc),
        })
    }

    #[uniffi::constructor]
    /// Construct a new `jwt_vc_json` credential.
    pub fn new_jwt_vc_json(jwt_vc: Arc<JwtVc>) -> Arc<Self> {
        Arc::new(Self {
            inner: ParsedCredentialInner::JwtVcJson(jwt_vc),
        })
    }

    #[uniffi::constructor]
    /// Construct a new `jwt_vc_json-ld` credential.
    pub fn new_jwt_vc_json_ld(jwt_vc: Arc<JwtVc>) -> Arc<Self> {
        Arc::new(Self {
            inner: ParsedCredentialInner::JwtVcJsonLd(jwt_vc),
        })
    }

    #[uniffi::constructor]
    /// Construct a new `ldp_vc` credential.
    pub fn new_ldp_vc(json_vc: Arc<JsonVc>) -> Arc<Self> {
        Arc::new(Self {
            inner: ParsedCredentialInner::LdpVc(json_vc),
        })
    }

    #[uniffi::constructor]
    /// Construct a new `sd_jwt_vc` credential.
    pub fn new_sd_jwt(sd_jwt_vc: Arc<VCDM2SdJwt>) -> Arc<Self> {
        Arc::new(Self {
            inner: ParsedCredentialInner::VCDM2SdJwt(sd_jwt_vc),
        })
    }

    #[uniffi::constructor]
    /// Construct a new `cwt` credential.
    pub fn new_cwt(cwt: Arc<Cwt>) -> Arc<Self> {
        Arc::new(Self {
            inner: ParsedCredentialInner::Cwt(cwt),
        })
    }

    #[uniffi::constructor]
    /// Parse a credential from the generic form retrieved from storage.
    pub fn parse_from_credential(
        credential: Credential,
    ) -> Result<Arc<Self>, CredentialDecodingError> {
        // NOTE: due to the Arc<Credential> type needed in the constructor,
        // given the uniffi::Object trait, we need to have an inner reference
        // to the credential that provided the type conversion, which avoids the
        // TryFrom<Arc<Credential>> that cannot be implemented given the compiler
        // constraints on foreign types.
        credential.try_into_parsed()
    }

    /// Convert a parsed credential into the generic form for storage.
    pub fn into_generic_form(&self) -> Result<Credential, CredentialEncodingError> {
        match &self.inner {
            ParsedCredentialInner::MsoMdoc(mdoc) => Ok(mdoc.clone().try_into()?),
            ParsedCredentialInner::JwtVcJson(vc) => Ok(Credential {
                id: vc.id(),
                format: CredentialFormat::JwtVcJson,
                r#type: vc.r#type(),
                payload: vc.to_compact_jws_bytes(),
                key_alias: vc.key_alias(),
            }),
            ParsedCredentialInner::VCDM2SdJwt(sd_jwt) => Ok(Credential {
                id: sd_jwt.id(),
                format: CredentialFormat::VCDM2SdJwt,
                r#type: sd_jwt.r#type(),
                payload: sd_jwt.inner.as_bytes().into(),
                key_alias: sd_jwt.key_alias(),
            }),
            ParsedCredentialInner::JwtVcJsonLd(vc) => Ok(Credential {
                id: vc.id(),
                format: CredentialFormat::JwtVcJsonLd,
                r#type: vc.r#type(),
                payload: vc.to_compact_jws_bytes(),
                key_alias: vc.key_alias(),
            }),
            ParsedCredentialInner::LdpVc(vc) => Ok(Credential {
                id: vc.id(),
                format: CredentialFormat::LdpVc,
                r#type: vc.r#type(),
                payload: vc.to_json_bytes()?,
                key_alias: vc.key_alias(),
            }),
            ParsedCredentialInner::Cwt(cwt) => Ok(Credential {
                id: cwt.id(),
                format: CredentialFormat::Cwt,
                r#type: cwt.r#type(),
                payload: cwt.payload(),
                key_alias: cwt.key_alias(),
            }),
        }
    }

    /// Return the format of the credential.
    pub fn format(&self) -> CredentialFormat {
        match &self.inner {
            ParsedCredentialInner::MsoMdoc(_) => CredentialFormat::MsoMdoc,
            ParsedCredentialInner::JwtVcJson(_) => CredentialFormat::JwtVcJson,
            ParsedCredentialInner::JwtVcJsonLd(_) => CredentialFormat::JwtVcJsonLd,
            ParsedCredentialInner::VCDM2SdJwt(_) => CredentialFormat::VCDM2SdJwt,
            ParsedCredentialInner::Cwt(_) => CredentialFormat::Cwt,
            ParsedCredentialInner::LdpVc(_) => CredentialFormat::LdpVc,
        }
    }

    /// Get the local ID for this credential.
    pub fn id(&self) -> Uuid {
        match &self.inner {
            ParsedCredentialInner::MsoMdoc(arc) => arc.id(),
            ParsedCredentialInner::JwtVcJson(arc) => arc.id(),
            ParsedCredentialInner::JwtVcJsonLd(arc) => arc.id(),
            ParsedCredentialInner::LdpVc(arc) => arc.id(),
            ParsedCredentialInner::VCDM2SdJwt(arc) => arc.id(),
            ParsedCredentialInner::Cwt(arc) => arc.id(),
        }
    }

    /// Get the key alias for this credential.
    pub fn key_alias(&self) -> Option<KeyAlias> {
        match &self.inner {
            ParsedCredentialInner::MsoMdoc(arc) => Some(arc.key_alias()),
            ParsedCredentialInner::JwtVcJson(arc) => arc.key_alias(),
            ParsedCredentialInner::JwtVcJsonLd(arc) => arc.key_alias(),
            ParsedCredentialInner::LdpVc(arc) => arc.key_alias(),
            ParsedCredentialInner::VCDM2SdJwt(arc) => arc.key_alias(),
            ParsedCredentialInner::Cwt(arc) => arc.key_alias(),
        }
    }

    /// Return the CredentialType from the parsed credential.
    pub fn r#type(&self) -> CredentialType {
        match &self.inner {
            ParsedCredentialInner::MsoMdoc(arc) => CredentialType(arc.doctype()),
            ParsedCredentialInner::JwtVcJson(arc) => arc.r#type(),
            ParsedCredentialInner::JwtVcJsonLd(arc) => arc.r#type(),
            ParsedCredentialInner::LdpVc(arc) => arc.r#type(),
            ParsedCredentialInner::VCDM2SdJwt(arc) => arc.r#type(),
            ParsedCredentialInner::Cwt(arc) => arc.r#type(),
        }
    }

    /// Return the credential as a JwtVc if it is of that format.
    pub fn as_jwt_vc(&self) -> Option<Arc<JwtVc>> {
        match &self.inner {
            ParsedCredentialInner::JwtVcJson(jwt_vc)
            | ParsedCredentialInner::JwtVcJsonLd(jwt_vc) => Some(jwt_vc.clone()),
            _ => None,
        }
    }

    /// Return the credential as a JsonVc if it is of that format.
    pub fn as_json_vc(&self) -> Option<Arc<JsonVc>> {
        match &self.inner {
            ParsedCredentialInner::LdpVc(ldp_vc) => Some(ldp_vc.clone()),
            _ => None,
        }
    }

    /// Return the credential as an Mdoc if it is of that format.
    pub fn as_mso_mdoc(&self) -> Option<Arc<Mdoc>> {
        match &self.inner {
            ParsedCredentialInner::MsoMdoc(mdoc) => Some(mdoc.clone()),
            _ => None,
        }
    }

    /// Return the credential as an SD-JWT, if it is of that format.
    pub fn as_sd_jwt(&self) -> Option<Arc<VCDM2SdJwt>> {
        match &self.inner {
            ParsedCredentialInner::VCDM2SdJwt(sd_jwt) => Some(sd_jwt.clone()),
            _ => None,
        }
    }

    /// Return the credential as an CWT, if it is of that format.
    pub fn as_cwt(&self) -> Option<Arc<Cwt>> {
        match &self.inner {
            ParsedCredentialInner::Cwt(cwt) => Some(cwt.clone()),
            _ => None,
        }
    }
}

impl PresentableCredential {
    /// Return a VP Token from the credential, given provided
    /// options for constructing the VP Token.
    pub async fn as_vp_token<'a>(
        &self,
        options: &'a PresentationOptions<'a>,
    ) -> Result<VpTokenItem, OID4VPError> {
        match &self.inner {
            ParsedCredentialInner::VCDM2SdJwt(sd_jwt) => {
                sd_jwt
                    .as_vp_token_item(options, self.selected_fields.clone(), self.limit_disclosure)
                    .await
            }
            ParsedCredentialInner::JwtVcJson(vc) | ParsedCredentialInner::JwtVcJsonLd(vc) => {
                vc.as_vp_token_item(options, None, false).await
            }
            ParsedCredentialInner::LdpVc(vc) => vc.as_vp_token_item(options, None, false).await,
            _ => Err(CredentialEncodingError::VpToken(format!(
                "Credential encoding for VP Token is not implemented for {:?}.",
                self.inner,
            ))
            .into()),
        }
    }

    /// Return the descriptor map with the associated format type of the inner credential.
    pub fn create_descriptor_map(
        self: &Arc<Self>,
        options: ResponseOptions,
        input_descriptor_id: impl Into<String>,
        index: Option<usize>,
    ) -> Result<DescriptorMap, OID4VPError> {
        match &self.inner {
            ParsedCredentialInner::VCDM2SdJwt(sd_jwt) => {
                sd_jwt.create_descriptor_map(options, input_descriptor_id, index)
            }
            ParsedCredentialInner::JwtVcJson(vc) => {
                vc.create_descriptor_map(options, input_descriptor_id, index)
            }
            ParsedCredentialInner::JwtVcJsonLd(vc) => {
                vc.create_descriptor_map(options, input_descriptor_id, index)
            }
            ParsedCredentialInner::LdpVc(vc) => {
                vc.create_descriptor_map(options, input_descriptor_id, index)
            }
            ParsedCredentialInner::MsoMdoc(_mdoc) => {
                unimplemented!("Mdoc create descriptor map not implemented")
            }
            ParsedCredentialInner::Cwt(_cwt) => {
                unimplemented!("Cwt create descriptor map not implemented")
            }
        }
    }
}

// Internal Parsed Credential methods
impl ParsedCredential {
    /// Check if the credential satisfies a presentation definition.
    pub fn satisfies_presentation_definition(&self, definition: &PresentationDefinition) -> bool {
        match &self.inner {
            ParsedCredentialInner::JwtVcJson(vc) => {
                vc.satisfies_presentation_definition(definition)
            }
            ParsedCredentialInner::JwtVcJsonLd(vc) => {
                vc.satisfies_presentation_definition(definition)
            }
            ParsedCredentialInner::LdpVc(vc) => vc.satisfies_presentation_definition(definition),
            ParsedCredentialInner::VCDM2SdJwt(sd_jwt) => {
                sd_jwt.satisfies_presentation_definition(definition)
            }
            ParsedCredentialInner::MsoMdoc(_mdoc) => false,
            ParsedCredentialInner::Cwt(_cwt) => false,
        }
    }

    /// Return the requested fields for the credential, accordinging to the presentation definition.
    pub fn requested_fields(
        &self,
        definition: &PresentationDefinition,
    ) -> Vec<Arc<RequestedField>> {
        match &self.inner {
            ParsedCredentialInner::VCDM2SdJwt(sd_jwt) => sd_jwt.requested_fields(definition),
            ParsedCredentialInner::JwtVcJson(vc) => vc.requested_fields(definition),
            ParsedCredentialInner::JwtVcJsonLd(vc) => vc.requested_fields(definition),
            ParsedCredentialInner::LdpVc(vc) => vc.requested_fields(definition),
            ParsedCredentialInner::Cwt(_cwt) => {
                unimplemented!("Cwt requested fields not implemented")
            }
            ParsedCredentialInner::MsoMdoc(_mdoc) => {
                unimplemented!("Mdoc requested fields not implemented")
            }
        }
    }
}

impl BitStringStatusListResolver for ParsedCredential {
    fn status_list_entry(
        &self,
    ) -> Result<ssi::status::bitstring_status_list::BitstringStatusListEntry, status::StatusListError>
    {
        match &self.inner {
            ParsedCredentialInner::LdpVc(cred) => cred.status_list_entry(),
            _ => Err(status::StatusListError::UnsupportedCredentialFormat),
        }
    }
}

impl BitStringStatusListResolver20240406 for ParsedCredential {
    fn status_list_entries(
        &self,
    ) -> Result<
        Vec<ssi::status::bitstring_status_list_20240406::BitstringStatusListEntry>,
        status::StatusListError,
    > {
        match &self.inner {
            ParsedCredentialInner::VCDM2SdJwt(cred) => cred.status_list_entries(),
            _ => Err(status::StatusListError::UnsupportedCredentialFormat),
        }
    }
}

impl TryFrom<Credential> for Arc<ParsedCredential> {
    type Error = CredentialDecodingError;

    fn try_from(credential: Credential) -> Result<Self, Self::Error> {
        match credential.format {
            CredentialFormat::MsoMdoc => Ok(ParsedCredential::new_mso_mdoc(credential.try_into()?)),
            CredentialFormat::JwtVcJson => {
                Ok(ParsedCredential::new_jwt_vc_json(credential.try_into()?))
            }
            CredentialFormat::JwtVcJsonLd => {
                Ok(ParsedCredential::new_jwt_vc_json_ld(credential.try_into()?))
            }
            CredentialFormat::VCDM2SdJwt => {
                Ok(ParsedCredential::new_sd_jwt(credential.try_into()?))
            }
            CredentialFormat::LdpVc => Ok(ParsedCredential::new_ldp_vc(credential.try_into()?)),
            _ => Err(CredentialDecodingError::UnsupportedCredentialFormat(
                credential.format.to_string(),
            )),
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, uniffi::Enum)]
pub enum VcdmVersion {
    V1,
    V2,
}

#[derive(Debug, uniffi::Error, thiserror::Error)]
pub enum CredentialEncodingError {
    #[error("MsoDoc encoding error: {0}")]
    MsoMdoc(#[from] MdocEncodingError),
    #[error("JsonVc encoding error: {0}")]
    JsonVc(#[from] JsonVcEncodingError),
    #[error("SD-JWT encoding error: {0}")]
    SdJwt(#[from] SdJwtError),
    #[error("VP Token encoding error: {0}")]
    VpToken(String),
    #[error(transparent)]
    Presentation(#[from] PresentationError),
}

#[derive(Debug, uniffi::Error, thiserror::Error)]
pub enum CredentialDecodingError {
    #[error("MsoDoc decoding error: {0}")]
    MsoMdoc(#[from] MdocInitError),
    #[error("JsonVc decoding error: {0}")]
    JsonVc(#[from] JsonVcInitError),
    #[error("JWT VC decoding error: {0}")]
    JwtVc(#[from] JwtVcInitError),
    #[error("SD JWT VC decoding error: {0}")]
    SdJwt(#[from] SdJwtError),
    #[error("Cwt decoding error: {0}")]
    Cwt(#[from] CwtError),
    #[error("Credential format is not yet supported for type: {0}")]
    UnsupportedCredentialFormat(String),
    #[error("Serialization error: {0}")]
    Serialization(String),
    #[error("Deserialization error: {0}")]
    Deserialization(String),
}

#[derive(Debug, uniffi::Error, thiserror::Error)]
pub enum CredentialPresentationError {
    #[error("Credential decoding error: {0}")]
    Decoding(String),
    #[error("JSON path selector error: {0}")]
    JsonPath(String),
}

/// The format of the credential.
#[derive(uniffi::Enum, PartialEq, Debug, Serialize, Deserialize, Clone)]
#[serde(rename_all = "snake_case")]
pub enum CredentialFormat {
    MsoMdoc,
    JwtVcJson,
    #[serde(rename = "jwt_vc_json-ld")]
    JwtVcJsonLd,
    LdpVc,
    #[serde(rename = "vcdm2_sd_jwt")]
    VCDM2SdJwt,
    Cwt,
    #[serde(untagged)]
    Other(String), // For ease of expansion.
}

impl std::fmt::Display for CredentialFormat {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            CredentialFormat::MsoMdoc => write!(f, "mso_mdoc"),
            CredentialFormat::JwtVcJson => write!(f, "jwt_vc_json"),
            CredentialFormat::JwtVcJsonLd => write!(f, "jwt_vc_json-ld"),
            CredentialFormat::LdpVc => write!(f, "ldp_vc"),
            CredentialFormat::VCDM2SdJwt => write!(f, "vcdm2_sd_jwt"),
            CredentialFormat::Cwt => write!(f, "cwt"),
            CredentialFormat::Other(s) => write!(f, "{s}"),
        }
    }
}

impl From<CredentialFormat> for String {
    fn from(format: CredentialFormat) -> Self {
        format.to_string()
    }
}

impl From<String> for CredentialFormat {
    fn from(value: String) -> Self {
        match value.as_str() {
            "mso_mdoc" => CredentialFormat::MsoMdoc,
            "jwt_vc_json" => CredentialFormat::JwtVcJson,
            "jwt_vc_json-ld" => CredentialFormat::JwtVcJsonLd,
            "ldp_vc" => CredentialFormat::LdpVc,
            "vcdm2_sd_jwt" => CredentialFormat::VCDM2SdJwt,
            _ => CredentialFormat::Other(value),
        }
    }
}

#[cfg(test)]
mod test {
    use super::*;

    #[rstest::rstest]
    #[case::mso_mdoc(r#""mso_mdoc""#, CredentialFormat::MsoMdoc)]
    #[case::jwt_vc_json(r#""jwt_vc_json""#, CredentialFormat::JwtVcJson)]
    #[case::jwt_vc_json_ld(r#""jwt_vc_json-ld""#, CredentialFormat::JwtVcJsonLd)]
    #[case::ldp_vc(r#""ldp_vc""#, CredentialFormat::LdpVc)]
    #[case::ldp_vc(r#""vcdm2_sd_jwt""#, CredentialFormat::VCDM2SdJwt)]
    #[case::cwt(r#""cwt""#, CredentialFormat::Cwt)]
    #[case::other(r#""something_else""#, CredentialFormat::Other("something_else".into()))]
    fn credential_format_roundtrips(#[case] expected: String, #[case] value: CredentialFormat) {
        let serialized = serde_json::to_string(&value).unwrap();
        assert_eq!(expected, serialized);

        let roundtripped: CredentialFormat = serde_json::from_str(&serialized).unwrap();

        assert_eq!(value, roundtripped);
    }

    #[test]
    fn credential_format_roundtrips_other() {
        let value = CredentialFormat::Other("mso_mdoc".into());

        let serialized = serde_json::to_string(&value).unwrap();
        assert_eq!(r#""mso_mdoc""#, serialized);

        let roundtripped: CredentialFormat = serde_json::from_str(&serialized).unwrap();

        assert_eq!(CredentialFormat::MsoMdoc, roundtripped);
    }
}
