use std::{collections::BTreeMap, fmt::Debug, sync::Arc};

use anyhow::{bail, Context, Result};
use isomdl::{
    cose::sign1::PreparedCoseSign1,
    definitions::{
        device_response::DocumentErrorCode,
        device_signed::{DeviceAuthentication, DeviceNamespaces},
        helpers::{NonEmptyMap, NonEmptyVec, Tag24},
        session::SessionTranscript as SessionTranscriptTrait,
        DeviceResponse, DeviceSigned, Document, IssuerSigned, IssuerSignedItem,
    },
};
use openid4vp::core::{
    authorization_request::AuthorizationRequestObject,
    iso_18013_7::{Handover as ISO180137Handover, SessionTranscript},
    object::{ParsingErrorContext, TypedParameter},
};
use serde::{de::DeserializeOwned, Serialize};
use serde_json::Value as Json;
use ssi::claims::cose::coset::{self, CoseSign1Builder};

use crate::crypto::KeyStore;

use super::{
    requested_values::{FieldId180137, FieldMap},
    Mdoc,
};

/// Re-export the library's Handover type for convenience.
pub use openid4vp::core::iso_18013_7::Handover;

/// Wrapper around the library's SessionTranscript to implement isomdl's SessionTranscript trait.
#[derive(Debug, Clone)]
pub struct OID4VPSessionTranscript<H>(SessionTranscript<H>);

impl<H> OID4VPSessionTranscript<H> {
    pub fn new(handover: H) -> Self {
        Self(SessionTranscript::new(handover))
    }
}

impl<H: Serialize> serde::Serialize for OID4VPSessionTranscript<H> {
    fn serialize<S>(&self, serializer: S) -> std::result::Result<S::Ok, S::Error>
    where
        S: serde::Serializer,
    {
        self.0.serialize(serializer)
    }
}

impl<'de, H: serde::Deserialize<'de>> serde::Deserialize<'de> for OID4VPSessionTranscript<H> {
    fn deserialize<D>(deserializer: D) -> std::result::Result<Self, D::Error>
    where
        D: serde::Deserializer<'de>,
    {
        SessionTranscript::<H>::deserialize(deserializer).map(Self)
    }
}

impl<H: Serialize + DeserializeOwned> SessionTranscriptTrait for OID4VPSessionTranscript<H> {}

/// Creates a Handover from an AuthorizationRequestObject.
///
/// This is a convenience function that extracts the required values from the request
/// and creates a Handover according to OID4VP 1.0 §B.2.6.1 (Invocation via Redirects).
///
/// # Arguments
///
/// * `request` - The authorization request object
/// * `jwk_thumbprint` - Optional SHA-256 JWK Thumbprint of the verifier's encryption key (32 bytes).
///   Should be provided when using `direct_post.jwt` response mode, None for unencrypted responses.
///   Use `compute_jwk_thumbprint` from the library to compute this value.
pub fn handover_from_request(
    request: &AuthorizationRequestObject,
    jwk_thumbprint: Option<&[u8; 32]>,
) -> Result<Handover> {
    let client_id = request.client_id().context("missing client_id")?.0.clone();
    let nonce = request.nonce().to_string();
    let response_uri = request.get::<RawResponseUri>().parsing_error()?.0;

    tracing::debug!(
        "Creating OID4VP 1.0 Handover - client_id: {}, nonce: {}, jwk_thumbprint: {:?}, response_uri: {}",
        client_id, nonce, jwk_thumbprint.is_some(), response_uri
    );

    ISO180137Handover::new(
        &client_id,
        &nonce,
        jwk_thumbprint.map(|t| t.as_slice()),
        &response_uri,
    )
    .context("failed to create Handover")
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

/// Core function to build a DeviceResponse with device authentication.
///
/// It handles DeviceAuthentication signing and DeviceResponse construction.
///
/// # Arguments
///
/// * `key_store` - KeyStore to retrieve the device signing key
/// * `credential` - The mdoc credential being presented
/// * `revealed_namespaces` - Pre-selected namespaces and elements to reveal
/// * `errors` - Optional map of namespace -> element -> error for missing fields
/// * `handover` - The handover structure for the SessionTranscript
pub fn build_device_response<H: Serialize + DeserializeOwned + Debug>(
    key_store: Arc<dyn KeyStore>,
    credential: &Mdoc,
    revealed_namespaces: NonEmptyMap<String, NonEmptyVec<Tag24<IssuerSignedItem>>>,
    errors: Option<NonEmptyMap<String, NonEmptyMap<String, DocumentErrorCode>>>,
    handover: H,
) -> Result<DeviceResponse> {
    let mdoc = credential.document();

    let device_namespaces = Tag24::new(DeviceNamespaces::new())
        .context("failed to encode device namespaces as CBOR")?;

    let session_transcript = OID4VPSessionTranscript::new(handover);

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

    // COSE requires raw (r||s) format signatures. Native keystores (iOS/Android) may return
    // DER-encoded signatures. This conversion is idempotent - raw signatures pass through unchanged.
    let signature = crate::crypto::CryptoCurveUtils::secp256r1()
        .ensure_raw_fixed_width_signature_encoding(signature)
        .context("failed to convert signature to raw format for COSE")?;

    let device_signature = prepared_cose_sign1.finalize(signature);

    let device_auth = isomdl::definitions::DeviceAuth::DeviceSignature(device_signature);

    let device_signed = DeviceSigned {
        namespaces: device_namespaces,
        device_auth,
    };

    let document = Document {
        doc_type: mdoc.mso.doc_type.clone(),
        issuer_signed: IssuerSigned {
            issuer_auth: mdoc.issuer_auth.clone(),
            namespaces: Some(revealed_namespaces),
        },
        device_signed,
        errors,
    };

    let documents = NonEmptyVec::new(document);

    Ok(DeviceResponse {
        version: "1.0".into(),
        documents: Some(documents),
        document_errors: None,
        status: isomdl::definitions::device_response::Status::OK,
    })
}

/// Prepares a DeviceResponse for ISO 18013-7 Annex B flow.
///
/// This function handles field selection based on FieldId180137 and FieldMap,
/// then delegates to `build_device_response` for the core signing logic.
pub fn prepare_response<H: Serialize + DeserializeOwned + Debug>(
    key_store: Arc<dyn KeyStore>,
    credential: &Mdoc,
    approved_fields: Vec<FieldId180137>,
    missing_fields: &BTreeMap<String, String>,
    mut field_map: FieldMap,
    handover: H,
) -> Result<DeviceResponse> {
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

    // Build errors map for missing fields
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

    build_device_response(
        key_store,
        credential,
        revealed_namespaces,
        NonEmptyMap::maybe_new(errors),
        handover,
    )
}
