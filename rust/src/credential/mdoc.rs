use std::{
    collections::{BTreeMap, HashMap},
    sync::Arc,
};

use base64::prelude::*;
use isomdl::{
    definitions::{helpers::Tag24, IssuerSigned, Mso},
    presentation::{device::Document, Stringify},
};
use uuid::Uuid;

use crate::{crypto::KeyAlias, CredentialType};

use super::{Credential, CredentialFormat};

uniffi::custom_newtype!(Namespace, String);
#[derive(Debug, Clone, Hash, PartialEq, Eq, PartialOrd, Ord)]
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

#[derive(uniffi::Object, Debug, Clone)]
pub struct Mdoc {
    inner: Document,
    key_alias: KeyAlias,
}

#[uniffi::export]
impl Mdoc {
    #[uniffi::constructor]
    /// Construct a new MDoc from base64url-encoded IssuerSigned.
    pub fn new_from_base64url_encoded_issuer_signed(
        base64url_encoded_issuer_signed: String,
        key_alias: KeyAlias,
    ) -> Result<Arc<Self>, MdocInitError> {
        let issuer_signed = isomdl::cbor::from_slice(
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
        Ok(Arc::new(Self { inner, key_alias }))
    }

    #[uniffi::constructor]
    /// Construct a SpruceKit MDoc from a cbor-encoded
    /// [spruceid/isomdl `Document`](https://github.com/spruceid/isomdl/blob/main/src/presentation/device.rs#L145-L152)
    pub fn from_cbor_encoded_document(
        cbor_encoded_document: Vec<u8>,
        key_alias: KeyAlias,
    ) -> Result<Arc<Self>, MdocInitError> {
        let inner = isomdl::cbor::from_slice(&cbor_encoded_document)
            .map_err(|e| MdocInitError::DocumentCborDecoding(e.to_string()))?;
        Ok(Arc::new(Self { inner, key_alias }))
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
    pub fn details(&self) -> HashMap<Namespace, Vec<Element>> {
        self.document()
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
                            let identifier = element.element_identifier;
                            let mut value = to_json_for_display(&element.element_value)
                                .and_then(|v| serde_json::to_string_pretty(&v).ok());
                            tracing::debug!("{identifier}: {value:?}");
                            if identifier == "portrait" {
                                if let Some(s) = value {
                                    value =
                                        Some(s.replace("application/octet-stream", "image/jpeg"));
                                }
                            }
                            Element { identifier, value }
                        })
                        .collect(),
                )
            })
            .collect()
    }

    pub fn key_alias(&self) -> KeyAlias {
        self.key_alias.clone()
    }
}

impl Mdoc {
    pub(crate) fn document(&self) -> &Document {
        &self.inner
    }

    pub(crate) fn new_from_parts(inner: Document, key_alias: KeyAlias) -> Self {
        Self { inner, key_alias }
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

        let mso: Tag24<Mso> = isomdl::cbor::from_slice(
            issuer_auth
                .payload
                .as_ref()
                .ok_or(MdocInitError::IssuerAuthPayloadMissing)?,
        )
        .map_err(|_| MdocInitError::IssuerAuthPayloadDecoding)?;

        Ok(Arc::new(Self {
            key_alias,
            inner: Document {
                id: Uuid::new_v4(),
                issuer_auth,
                namespaces,
                mso: mso.into_inner(),
            },
        }))
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
            payload: isomdl::cbor::to_vec(mdoc.document())
                .map_err(|_| MdocEncodingError::DocumentCborEncoding)?,
            key_alias: Some(mdoc.key_alias()),
        })
    }
}

#[derive(Debug, uniffi::Error, thiserror::Error)]
pub enum MdocInitError {
    #[error("failed to decode Document from CBOR: {0}")]
    DocumentCborDecoding(String),
    #[error("failed to decode base64url_encoded_issuer_signed from base64url-encoded bytes")]
    IssuerSignedBase64UrlDecoding,
    #[error("failed to decode IssuerSigned from CBOR")]
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

/// Convert a ciborium value to a serde_json value for display.
fn to_json_for_display(value: &ciborium::Value) -> Option<serde_json::Value> {
    /// Convert integer and text keys to strings for display.
    fn key_to_string_for_display(value: &ciborium::Value) -> Option<String> {
        match value {
            ciborium::Value::Integer(i) => Some(<i128>::from(*i).to_string()),
            ciborium::Value::Text(s) => Some(s.clone()),
            ciborium::Value::Float(f) => Some(f.to_string()),
            ciborium::Value::Bool(b) => Some(b.to_string()),
            ciborium::Value::Null => Some("null".to_string()),
            ciborium::Value::Tag(_, v) => key_to_string_for_display(v),
            _ => {
                tracing::warn!("unsupported key type: {:?}", value);
                None
            }
        }
    }

    match value {
        ciborium::Value::Integer(i) => Some(serde_json::Value::Number(i128::from(*i).into())),
        ciborium::Value::Text(s) => Some(serde_json::Value::String(s.clone())),
        ciborium::Value::Array(a) => Some(serde_json::Value::Array(
            a.iter().filter_map(to_json_for_display).collect::<Vec<_>>(),
        )),
        ciborium::Value::Map(m) => Some(serde_json::Value::Object(
            m.iter()
                .filter_map(|(k, v)| {
                    let key = key_to_string_for_display(k)?;
                    let value = to_json_for_display(v)?;
                    Some((key, value))
                })
                .collect(),
        )),
        ciborium::Value::Bytes(items) => Some(
            format!(
                "data:application/octet-stream;base64,{}",
                BASE64_STANDARD.encode(items)
            )
            .into(),
        ),
        ciborium::Value::Float(f) => {
            let Some(num) = serde_json::Number::from_f64(*f) else {
                tracing::warn!("failed to convert float to number: {}", f);
                return None;
            };
            Some(serde_json::Value::Number(num))
        }
        ciborium::Value::Bool(b) => Some(serde_json::Value::Bool(*b)),
        ciborium::Value::Null => Some(serde_json::Value::Null),
        ciborium::Value::Tag(_, value) => to_json_for_display(value),
        _ => {
            tracing::warn!("unsupported value type: {:?}", value);
            None
        }
    }
}
