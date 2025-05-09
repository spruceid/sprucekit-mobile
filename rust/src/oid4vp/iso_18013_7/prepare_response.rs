use std::{collections::BTreeMap, fmt::Debug, sync::Arc};

use anyhow::{bail, Context, Result};
use ciborium::Value as Cbor;
use isomdl::{
    cbor,
    cose::sign1::PreparedCoseSign1,
    definitions::{
        device_response::DocumentErrorCode,
        device_signed::{DeviceAuthentication, DeviceNamespaces},
        helpers::{ByteStr, NonEmptyMap, NonEmptyVec, Tag24},
        session::SessionTranscript as SessionTranscriptTrait,
        DeviceResponse, DeviceSigned, Document, IssuerSigned, IssuerSignedItem,
    },
};
use openid4vp::core::{
    authorization_request::AuthorizationRequestObject,
    object::{ParsingErrorContext, TypedParameter},
};
use serde::{de::DeserializeOwned, Deserialize, Serialize};
use serde_json::Value as Json;
use sha2::{Digest, Sha256};
use ssi::claims::cose::coset::{self, CoseSign1Builder};

use crate::crypto::KeyStore;

use super::{
    requested_values::{FieldId180137, FieldMap},
    Mdoc,
};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Handover(ByteStr, ByteStr, String);

#[derive(Debug, Clone, Serialize, Deserialize)]
struct SessionTranscript<H>(Cbor, Cbor, H);

impl<H: Serialize + DeserializeOwned> SessionTranscriptTrait for SessionTranscript<H> {}

impl Handover {
    pub fn new(request: &AuthorizationRequestObject, mdoc_generated_nonce: String) -> Result<Self> {
        let client_id = request.client_id().context("missing client_id")?.0.clone();
        let response_uri = request.get::<RawResponseUri>().parsing_error()?.0;
        let nonce = request.nonce().to_string();
        let client_id_to_hash = Cbor::Array(vec![
            Cbor::Text(client_id),
            Cbor::Text(mdoc_generated_nonce.clone()),
        ]);
        tracing::debug!("client_id_to_hash CBOR: {client_id_to_hash:#?}");

        let client_id_to_hash_bytes = cbor::to_vec(&client_id_to_hash)?;
        tracing::debug!(
            "client_id_to_hash HEX: {}",
            hex::encode(&client_id_to_hash_bytes)
        );

        let response_uri_to_hash = Cbor::Array(vec![
            Cbor::Text(response_uri),
            Cbor::Text(mdoc_generated_nonce.clone()),
        ]);
        tracing::debug!("response_uri_to_hash CBOR: {response_uri_to_hash:#?}");

        let response_uri_to_hash_bytes = cbor::to_vec(&response_uri_to_hash)?;
        tracing::debug!(
            "response_uri_to_hash HEX: {}",
            hex::encode(&response_uri_to_hash_bytes)
        );

        let client_id_hash = Sha256::digest(client_id_to_hash_bytes).to_vec();
        tracing::debug!("client_id hash HEX: {}", hex::encode(&client_id_hash));

        let response_uri_hash = Sha256::digest(response_uri_to_hash_bytes).to_vec();
        tracing::debug!("response_uri hash HEX: {}", hex::encode(&response_uri_hash));

        let handover = Self(client_id_hash.into(), response_uri_hash.into(), nonce);

        Ok(handover)
    }
}

impl<H> SessionTranscript<H> {
    fn new(handover: H) -> Self {
        Self(Cbor::Null, Cbor::Null, handover)
    }
}

/// Unprocessed response_uri for use in the Handover. We don't use the default response uri type to
/// avoid signature errors that could be caused by URL normalisation through the Url type.
#[derive(Debug, Clone)]
pub struct RawResponseUri(pub String);

impl TypedParameter for RawResponseUri {
    const KEY: &'static str = "response_uri";
}

impl TryFrom<Json> for RawResponseUri {
    type Error = anyhow::Error;

    fn try_from(value: Json) -> std::result::Result<Self, Self::Error> {
        let Json::String(uri) = value else {
            bail!("unexpected type")
        };

        Ok(Self(uri))
    }
}

impl From<RawResponseUri> for Json {
    fn from(value: RawResponseUri) -> Self {
        Json::String(value.0)
    }
}

pub fn prepare_response<H: Serialize + DeserializeOwned + Debug>(
    key_store: Arc<dyn KeyStore>,
    credential: &Mdoc,
    approved_fields: Vec<FieldId180137>,
    missing_fields: &BTreeMap<String, String>,
    mut field_map: FieldMap,
    handover: H,
) -> Result<DeviceResponse> {
    let mdoc = credential.document();

    let mut revealed_namespaces: BTreeMap<String, NonEmptyVec<Tag24<IssuerSignedItem>>> =
        BTreeMap::new();

    for field in approved_fields {
        let (namespace, element) = field_map
            .remove(&field)
            .context(field.0)
            .context("missing approved field from field_map")?;

        tracing::info!(
            "revealing field: {namespace} {}",
            element.as_ref().element_identifier
        );

        if let Some(items) = revealed_namespaces.get_mut(&namespace) {
            items.push(element);
        } else {
            revealed_namespaces.insert(namespace, NonEmptyVec::new(element));
        }
    }

    let revealed_namespaces: NonEmptyMap<String, NonEmptyVec<Tag24<IssuerSignedItem>>> =
        NonEmptyMap::maybe_new(revealed_namespaces).context("no approved fields")?;

    let device_namespaces = Tag24::new(DeviceNamespaces::new())
        .context("failed to encode device namespaces as CBOR")?;

    let session_transcript = SessionTranscript::new(handover);

    let device_authentication_payload = Tag24::new(DeviceAuthentication::new(
        session_transcript,
        mdoc.mso.doc_type.clone(),
        device_namespaces.clone(),
    ))
    .context("failed to encode device auth payload as CBOR")?;

    tracing::debug!("device authentication payload: {device_authentication_payload:?}");

    let device_authentication_bytes = isomdl::cbor::to_vec(&device_authentication_payload)
        .context("failed to encode device auth payload as CBOR bytes")?;

    tracing::debug!("device authentication payload bytes: {device_authentication_bytes:?}");

    let header = coset::HeaderBuilder::new()
        .algorithm(coset::iana::Algorithm::ES256)
        .build();

    let cose_sign1_builder = CoseSign1Builder::new().protected(header);
    let prepared_cose_sign1 = PreparedCoseSign1::new(
        cose_sign1_builder,
        Some(&device_authentication_bytes),
        None,
        false,
    )
    .context("failed to prepare CoseSign1")?;

    let device_key = key_store
        .get_signing_key(credential.key_alias())
        .context("failed to retrieve DeviceKey from the keystore")?;

    let signature = device_key
        .sign(prepared_cose_sign1.signature_payload().to_vec())
        .context("failed to generate device_signature")?;

    let device_signature = prepared_cose_sign1.finalize(signature);

    let device_auth = isomdl::definitions::DeviceAuth::DeviceSignature(device_signature);

    let device_signed = DeviceSigned {
        namespaces: device_namespaces,
        device_auth,
    };

    let mut errors: BTreeMap<String, NonEmptyMap<String, DocumentErrorCode>> = BTreeMap::new();
    for (namespace, element_identifier) in missing_fields {
        if let Some(elems) = errors.get_mut(namespace) {
            elems.insert(
                element_identifier.clone(),
                DocumentErrorCode::DataNotReturned,
            );
        } else {
            let element_map = NonEmptyMap::new(
                element_identifier.clone(),
                DocumentErrorCode::DataNotReturned,
            );
            errors.insert(namespace.clone(), element_map);
        }
    }

    let document = Document {
        doc_type: mdoc.mso.doc_type.clone(),
        issuer_signed: IssuerSigned {
            issuer_auth: mdoc.issuer_auth.clone(),
            namespaces: Some(revealed_namespaces),
        },
        device_signed,
        errors: NonEmptyMap::maybe_new(errors),
    };

    let documents = NonEmptyVec::new(document);

    let response = DeviceResponse {
        version: "1.0".into(),
        documents: Some(documents),
        document_errors: None,
        status: isomdl::definitions::device_response::Status::OK,
    };

    Ok(response)
}
