use std::{
    collections::{BTreeMap, HashMap},
    sync::Arc,
};

use base64::prelude::*;
use isomdl::{
    cbor,
    definitions::{
        helpers::{NonEmptyMap, NonEmptyVec, Tag24},
        issuer_signed_dehydrated::{IssuerSignedDehydrated, NameSpacedData},
        IssuerSigned, IssuerSignedItem, Mso,
    },
    presentation::{device::Document, Stringify},
};
use openid4vp::core::{
    credential_format::ClaimFormatDesignation, dcql_query::DcqlCredentialQuery,
    iso_18013_7::get_encryption_jwk_thumbprint, response::parameters::VpTokenItem,
};
use time::format_description::well_known::Iso8601;
use uuid::Uuid;

use crate::{
    credential::{
        activity_log::{self, ActivityLog},
        {Credential, CredentialEncodingError, CredentialFormat},
    },
    crypto::KeyAlias,
    oid4vp::{
        error::OID4VPError,
        iso_18013_7::prepare_response::{build_device_response, handover_from_request},
        permission_request::RequestedField,
        presentation::PresentationOptions,
    },
    storage_manager::StorageManagerInterface,
    CredentialType,
};

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
    /// Construct a new MDoc from IssuerSigned CBOR bytes.
    ///
    /// Provisioned data represents the element values in the issuer signed namespaces.
    /// If provisioned data exists, it will update the issuer signed namespace values
    /// with the provisioned data.
    pub fn new_from_cbor_encoded_issuer_signed_dehydrated(
        cbor_encoded_issuer_signed_dehydrated: Vec<u8>,
        namespaced_data: Vec<u8>,
        key_alias: KeyAlias,
    ) -> Result<Arc<Self>, MdocInitError> {
        let issuer_signed_dehdrated: IssuerSignedDehydrated =
            isomdl::cbor::from_slice(&cbor_encoded_issuer_signed_dehydrated)
                .map_err(|_| MdocInitError::IssuerSignedCborDecoding)?;

        let namespace_data: NameSpacedData = isomdl::cbor::from_slice(&namespaced_data)
            .map_err(|e| MdocInitError::ProvisionedDataCborDecoding(e.to_string()))?;

        let issuer_signed = issuer_signed_dehdrated
            .combine_namespaced_data(&namespace_data)
            .map_err(|e| MdocInitError::ProvisionedDataCborDecoding(e.to_string()))?;

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

    pub fn invalidation_date(&self) -> Result<String, MdocDateError> {
        self.inner
            .mso
            .validity_info
            .valid_until
            .format(&Iso8601::DEFAULT)
            .map_err(|e| MdocDateError::Formatting(format!("{e:?}")))
    }

    pub async fn activity_log(
        &self,
        storage: Arc<dyn StorageManagerInterface>,
    ) -> Result<ActivityLog, activity_log::ActivityLogError> {
        let credential_id = self.document().id;
        ActivityLog::load(credential_id, storage).await
    }
}

impl Mdoc {
    pub(crate) fn document(&self) -> &Document {
        &self.inner
    }

    pub(crate) fn new_from_parts(inner: Document, key_alias: KeyAlias) -> Self {
        Self { inner, key_alias }
    }

    /// Check if the mdoc satisfies a DCQL credential query.
    /// Used for OID4VP 1.0 flow with mso_mdoc format.
    pub fn satisfies_dcql_query(&self, credential_query: &DcqlCredentialQuery) -> bool {
        if *credential_query.format() != ClaimFormatDesignation::MsoMDoc {
            return false;
        }

        // Check if doctype matches (if specified in meta)
        let meta = credential_query.meta();
        if let Some(doctype_value) = meta.get("doctype_value") {
            if let Some(expected_doctype) = doctype_value.as_str() {
                if self.doctype() != expected_doctype {
                    return false;
                }
            }
        }

        true
    }

    /// Return the requested fields for the credential, according to the DCQL credential query.
    /// Used for OID4VP 1.0 flow with mso_mdoc format.
    pub fn requested_fields_dcql(
        &self,
        credential_query: &DcqlCredentialQuery,
    ) -> Vec<Arc<RequestedField>> {
        log::debug!(
            "mdoc requested_fields_dcql - credential_query id: {}, format: {:?}, claims present: {}",
            credential_query.id(),
            credential_query.format(),
            credential_query.claims().is_some()
        );

        let Some(claims) = credential_query.claims() else {
            log::debug!(
                "mdoc requested_fields_dcql - no claims in credential_query, returning empty"
            );
            return vec![];
        };

        log::debug!("mdoc requested_fields_dcql - found {} claims", claims.len());

        claims
            .iter()
            .map(|claim| {
                let path: Vec<String> = claim
                    .path()
                    .iter()
                    .filter_map(|p| match p {
                        openid4vp::core::dcql_query::DcqlCredentialClaimsQueryPath::String(s) => {
                            Some(s.clone())
                        }
                        openid4vp::core::dcql_query::DcqlCredentialClaimsQueryPath::Integer(i) => {
                            Some(i.to_string())
                        }
                        openid4vp::core::dcql_query::DcqlCredentialClaimsQueryPath::Null => None,
                    })
                    .collect();

                let name = path.last().cloned();

                Arc::new(RequestedField::from_dcql_claims_with_name(
                    credential_query.id().to_string(),
                    path,
                    vec![],
                    name,
                ))
            })
            .collect()
    }

    /// Generate a VP Token item for OID4VP presentation.
    /// This creates a DeviceResponse with the selected fields and signs it.
    pub async fn as_vp_token_item<'a>(
        &self,
        options: &'a PresentationOptions<'a>,
        selected_fields: Option<Vec<String>>,
    ) -> Result<VpTokenItem, OID4VPError> {
        let keystore = options.keystore.clone().ok_or_else(|| {
            OID4VPError::CredentialEncoding(CredentialEncodingError::VpToken(
                "KeyStore is required for mdoc presentation".into(),
            ))
        })?;

        let mdoc = self.document();

        // Build the revealed namespaces based on selected fields
        let mut revealed_namespaces: BTreeMap<String, NonEmptyVec<Tag24<IssuerSignedItem>>> =
            BTreeMap::new();

        // If selected_fields is None, reveal all fields
        // If selected_fields is Some, only reveal those fields
        for (namespace, elements) in mdoc.namespaces.clone().into_inner() {
            for element in elements.into_inner().into_values() {
                let element_id = element.as_ref().element_identifier.clone();

                // Check if this field should be included
                let should_include = match &selected_fields {
                    None => true, // No selection means include all
                    Some(fields) => {
                        // Field path format is "namespace,element_id" base64 encoded
                        // Check if any selected field matches
                        fields.iter().any(|f| {
                            // Decode the path and check
                            let parts: Vec<&str> = f.split(',').collect();
                            if parts.len() >= 2 {
                                // Decode base64
                                let decoded_namespace = base64::engine::general_purpose::URL_SAFE
                                    .decode(parts[0])
                                    .ok()
                                    .and_then(|b| String::from_utf8(b).ok());
                                let decoded_element = base64::engine::general_purpose::URL_SAFE
                                    .decode(parts[1])
                                    .ok()
                                    .and_then(|b| String::from_utf8(b).ok());

                                decoded_namespace.as_deref() == Some(&namespace)
                                    && decoded_element.as_deref() == Some(&element_id)
                            } else {
                                false
                            }
                        })
                    }
                };

                if should_include {
                    log::debug!("Including mdoc field: {}.{}", namespace, element_id);
                    if let Some(items) = revealed_namespaces.get_mut(&namespace) {
                        items.push(element);
                    } else {
                        revealed_namespaces.insert(namespace.clone(), NonEmptyVec::new(element));
                    }
                }
            }
        }

        let revealed_namespaces: NonEmptyMap<String, NonEmptyVec<Tag24<IssuerSignedItem>>> =
            NonEmptyMap::maybe_new(revealed_namespaces).ok_or_else(|| {
                OID4VPError::CredentialEncoding(CredentialEncodingError::VpToken(
                    "No fields selected for mdoc presentation".into(),
                ))
            })?;

        // Create Handover per OID4VP 1.0 §B.2.6.1 (Invocation via Redirects)
        let jwk_thumbprint = get_encryption_jwk_thumbprint(options.request);
        let handover =
            handover_from_request(options.request, jwk_thumbprint.as_ref()).map_err(|e| {
                CredentialEncodingError::VpToken(format!("Failed to create Handover: {e}"))
            })?;

        // Build and sign the DeviceResponse
        let device_response =
            build_device_response(keystore, self, revealed_namespaces, None, handover).map_err(
                |e| {
                    CredentialEncodingError::VpToken(format!(
                        "Failed to build device response: {e}"
                    ))
                },
            )?;

        // Encode as base64url
        let device_response_bytes = cbor::to_vec(&device_response).map_err(|e| {
            CredentialEncodingError::VpToken(format!("Failed to encode device response: {e}"))
        })?;

        let device_response_b64 = BASE64_URL_SAFE_NO_PAD.encode(&device_response_bytes);

        Ok(VpTokenItem::from(device_response_b64))
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
    #[error("failed to decode ProvisionedData from CBOR: {0}")]
    ProvisionedDataCborDecoding(String),
    #[error("failed to populate ProvisionedData")]
    ProvisionedDataPopulation,
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

#[derive(Debug, uniffi::Error, thiserror::Error)]
pub enum MdocDateError {
    #[error("failed to encode date as ISO 8601: {0}")]
    Formatting(String),
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

#[cfg(test)]
mod tests {
    use base64::{prelude::BASE64_STANDARD, Engine};

    use crate::{credential::mdoc::Mdoc, crypto::KeyAlias};

    #[test]
    fn test_cbor_auth_data_parsing() {
        const B64_AUTH_DATA: &str = include_str!("../../../tests/examples/auth_data.txt");
        const B64_PROVISIONED_DATA: &str =
            include_str!("../../../tests/examples/provision_data.txt");

        let decoded_auth_data = BASE64_STANDARD
            .decode(B64_AUTH_DATA)
            .expect("failed to decode b64 auth data");

        let decoded_provisioned_data = BASE64_STANDARD
            .decode(B64_PROVISIONED_DATA)
            .expect("failed to decode b64 provisioned data");

        let mdoc = Mdoc::new_from_cbor_encoded_issuer_signed_dehydrated(
            decoded_auth_data,
            decoded_provisioned_data,
            KeyAlias("default".into()),
        )
        .expect("failed to create mdoc");

        println!("Mdoc: {mdoc:?}")
    }
}
