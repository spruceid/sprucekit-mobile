use std::{
    collections::{BTreeMap, HashMap},
    sync::Arc,
};

use base64::prelude::*;
use isomdl::{
    definitions::{IssuerSigned, Mso},
    presentation::{device::Document, Stringify},
};
use openid4vp::{
    core::{credential_format::ClaimFormatDesignation, presentation_submission::DescriptorMap},
    JsonPath,
};
use serde::{ser::SerializeMap, Serialize, Serializer};
use uuid::Uuid;

use crate::{
    oid4vp::{
        error::OID4VPError,
        presentation::{CredentialPresentation, PresentationOptions},
    },
    CredentialType, KeyAlias,
};

use super::{Credential, CredentialFormat};

pub type DocumentDetails = HashMap<Namespace, Vec<Element>>;

uniffi::custom_newtype!(Namespace, String);
#[derive(Debug, Clone, Hash, PartialEq, Eq, PartialOrd, Ord, Serialize)]
#[serde(transparent)]
/// A namespace for mdoc data elements.
pub struct Namespace(String);

#[derive(Debug, Clone, uniffi::Record)]
/// Simple representation of an mdoc data element.
pub struct Element {
    /// Name of the data element.
    pub identifier: String,
    /// JSON representation of the data element, missing if the value cannot be represented as JSON.
    pub value: Option<String>,
}

// NOTE: Serializing to provide a JSON representation of the Element.
impl Serialize for Element {
    fn serialize<S>(&self, serializer: S) -> Result<S::Ok, S::Error>
    where
        S: Serializer,
    {
        let value = self
            .value
            .as_ref()
            .map(|s| serde_json::from_str::<serde_json::Value>(s))
            .transpose()
            .map_err(|e| {
                serde::ser::Error::custom(format!(
                    "Failed to parse JSON value for mDoc Element {}: {}",
                    self.identifier, e
                ))
            })?;

        let mut map = serializer.serialize_map(Some(1))?;
        map.serialize_entry(&self.identifier, &value)?;
        map.end()
    }
}

#[derive(uniffi::Object, Debug, Clone)]
pub struct Mdoc {
    inner: Document,
    key_alias: KeyAlias,
    details: DocumentDetails,
}

#[uniffi::export]
impl Mdoc {
    #[uniffi::constructor]
    /// Construct a new MDoc from base64url-encoded IssuerSigned.
    pub fn new_from_base64url_encoded_issuer_signed(
        base64url_encoded_issuer_signed: String,
        key_alias: KeyAlias,
    ) -> Result<Arc<Self>, MdocInitError> {
        let issuer_signed = serde_cbor::from_slice(
            &BASE64_URL_SAFE_NO_PAD
                .decode(base64url_encoded_issuer_signed)
                .map_err(|_| MdocInitError::IssuerSignedBase64UrlDecoding)?,
        )
        .map_err(|_| MdocInitError::IssuerSignedCborDecoding)?;
        Self::new_from_issuer_signed(key_alias, issuer_signed)
    }

    #[uniffi::constructor]
    /// Compatibility feature: construct an MDoc from a
    /// [stringified spruceid/isomdl `Document`](https://github.com/spruceid/isomdl/blob/main/src/presentation/mod.rs#L100)
    pub fn from_stringified_document(
        stringified_document: String,
        key_alias: KeyAlias,
    ) -> Result<Arc<Self>, MdocInitError> {
        let inner = Document::parse(stringified_document)
            .map_err(|_| MdocInitError::DocumentUtf8Decoding)?;

        let details = Self::document_details(&inner);

        Ok(Arc::new(Self {
            inner,
            key_alias,
            details,
        }))
    }

    #[uniffi::constructor]
    /// Construct a SpruceKit MDoc from a cbor-encoded
    /// [spruceid/isomdl `Document`](https://github.com/spruceid/isomdl/blob/main/src/presentation/device.rs#L145-L152)
    pub fn from_cbor_encoded_document(
        cbor_encoded_document: Vec<u8>,
        key_alias: KeyAlias,
    ) -> Result<Arc<Self>, MdocInitError> {
        let inner = serde_cbor::from_slice(&cbor_encoded_document)
            .map_err(|_| MdocInitError::DocumentCborDecoding)?;

        let details = Self::document_details(&inner);

        Ok(Arc::new(Self {
            inner,
            key_alias,
            details,
        }))
    }

    /// The local ID of this credential.
    pub fn id(&self) -> Uuid {
        self.inner.id
    }

    /// The document type of this mdoc, for example `org.iso.18013.5.1.mDL`.
    pub fn doctype(&self) -> String {
        self.inner.mso.doc_type.clone()
    }

    /// Simple representation of mdoc namespace and data elements for display in the UI.
    pub fn details(&self) -> DocumentDetails {
        // NOTE: Cloning due to the uniffi::Object bound on the return type.
        self.details.clone()
    }

    pub fn key_alias(&self) -> KeyAlias {
        self.key_alias.clone()
    }
}

impl Mdoc {
    pub(crate) fn document(&self) -> &Document {
        &self.inner
    }

    // Parse the document details from the presentation document.
    pub fn document_details(document: &Document) -> DocumentDetails {
        document
            .namespaces
            .clone()
            .into_inner()
            .into_iter()
            .map(|(namespace, elements)| {
                (
                    Namespace(namespace),
                    elements
                        .into_inner()
                        .into_values()
                        .map(|tagged| {
                            let element = tagged.into_inner();
                            Element {
                                identifier: element.element_identifier,
                                value: serde_json::to_string_pretty(&element.element_value).ok(),
                            }
                        })
                        .collect(),
                )
            })
            .collect()
    }

    fn new_from_issuer_signed(
        key_alias: KeyAlias,
        IssuerSigned {
            namespaces,
            issuer_auth,
        }: IssuerSigned,
    ) -> Result<Arc<Self>, MdocInitError> {
        let namespaces = namespaces
            .ok_or(MdocInitError::NamespacesMissing)?
            .into_inner()
            .into_iter()
            .map(|(k, v)| {
                let m = v
                    .into_inner()
                    .into_iter()
                    .map(|i| (i.as_ref().element_identifier.clone(), i))
                    .collect::<BTreeMap<_, _>>()
                    .try_into()
                    // Unwrap safety: safe to convert BTreeMap to NonEmptyMap since we're iterating over a NonEmptyVec.
                    .unwrap();
                (k, m)
            })
            .collect::<BTreeMap<_, _>>()
            .try_into()
            // Unwrap safety: safe to convert BTreeMap to NonEmptyMap since we're iterating over a NonEmptyMap.
            .unwrap();

        let mso: Mso = serde_cbor::from_slice(
            issuer_auth
                .payload()
                .as_ref()
                .ok_or(MdocInitError::IssuerAuthPayloadMissing)?,
        )
        .map_err(|_| MdocInitError::IssuerAuthPayloadDecoding)?;

        let inner = Document {
            id: Uuid::new_v4(),
            issuer_auth,
            namespaces,
            mso,
        };

        let details = Self::document_details(&inner);

        Ok(Arc::new(Self {
            key_alias,
            inner,
            details,
        }))
    }
}

impl CredentialPresentation for Mdoc {
    // NOTE: This is the parsed structure used to compare against the JSONPath expression
    // in the presentation definition constraint field filter.
    type Credential = DocumentDetails;

    type CredentialFormat = ClaimFormatDesignation;
    type PresentationFormat = ClaimFormatDesignation;

    fn credential(&self) -> &Self::Credential {
        &self.details
    }

    fn credential_format(&self) -> Self::CredentialFormat {
        ClaimFormatDesignation::MsoMDoc
    }

    fn presentation_format(&self) -> Self::PresentationFormat {
        ClaimFormatDesignation::MsoMDoc
    }

    // Return the credential as a VP Token Item.
    //
    // "The value for vp_token shall contain the base64url-encoded-
    // without-padding DeviceResponse data structure as defined in
    // ISO/IEC 18013-5."
    //
    // See: Section B.4.3.2 Authorization Response Parameters for 18013-7 requirements.
    async fn as_vp_token_item<'a>(
        &self,
        options: &'a PresentationOptions<'a>,
    ) -> Result<openid4vp::core::response::parameters::VpTokenItem, crate::oid4vp::error::OID4VPError>
    {
        unimplemented!()
    }

    // "The value for path shall be the static JSON String value $ if the VP Token
    // contains a single JSON String or JSON object."
    //
    // See: Section B.4.3.3 Presentation Submission for 18013-7 requirements.
    fn create_descriptor_map(
        &self,
        input_descriptor_id: impl Into<String>,
        index: Option<usize>,
    ) -> Result<openid4vp::core::presentation_submission::DescriptorMap, OID4VPError> {
        let path = match index {
            None => JsonPath::default(),
            Some(i) => format!("$[{i}]")
                .parse()
                .map_err(|e| OID4VPError::JsonPathParse(format!("{e:?}")))?,
        };

        Ok(DescriptorMap::new(
            input_descriptor_id,
            self.credential_format(),
            path,
        ))
    }
}

impl TryFrom<Credential> for Arc<Mdoc> {
    type Error = MdocInitError;

    fn try_from(credential: Credential) -> Result<Self, Self::Error> {
        Mdoc::from_cbor_encoded_document(
            credential.payload,
            credential.key_alias.ok_or(MdocInitError::KeyAliasMissing)?,
        )
    }
}

impl TryFrom<Arc<Mdoc>> for Credential {
    type Error = MdocEncodingError;

    fn try_from(mdoc: Arc<Mdoc>) -> Result<Self, Self::Error> {
        Ok(Credential {
            id: mdoc.id(),
            format: CredentialFormat::MsoMdoc,
            r#type: CredentialType(mdoc.doctype()),
            payload: serde_cbor::to_vec(mdoc.document())
                .map_err(|_| MdocEncodingError::DocumentCborEncoding)?,
            key_alias: Some(mdoc.key_alias()),
        })
    }
}

#[derive(Debug, uniffi::Error, thiserror::Error)]
pub enum MdocInitError {
    #[error("failed to decode Document from CBOR")]
    DocumentCborDecoding,
    #[error("failed to decode base64url_encoded_issuer_signed from base64url-encoded bytes")]
    IssuerSignedBase64UrlDecoding,
    #[error("failed to deocde IssuerSigned from CBOR")]
    IssuerSignedCborDecoding,
    #[error("IssuerAuth CoseSign1 has no payload")]
    IssuerAuthPayloadMissing,
    #[error("failed to decode IssuerAuth CoseSign1 payload as an MSO")]
    IssuerAuthPayloadDecoding,
    #[error("a key alias is required for an mdoc, and none was provided")]
    KeyAliasMissing,
    #[error("IssuerSigned did not contain namespaces")]
    NamespacesMissing,
    #[error("failed to decode Document from UTF-8 string")]
    DocumentUtf8Decoding,
}

#[derive(Debug, uniffi::Error, thiserror::Error)]
pub enum MdocEncodingError {
    #[error("failed to encode Document to CBOR")]
    DocumentCborEncoding,
}
