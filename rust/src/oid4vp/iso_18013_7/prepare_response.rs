use std::{
    collections::{BTreeMap, BTreeSet},
    fmt::Debug,
    sync::Arc,
};

use anyhow::{bail, Context, Result};
use ciborium::Value as Cbor;
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

/// Values to return in DeviceSigned, by namespace and element identifier.
pub type DeviceSignedValues = BTreeMap<String, BTreeMap<String, Cbor>>;

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

    handover_from_components(&client_id, &nonce, &response_uri, jwk_thumbprint)
}

pub fn handover_from_components(
    client_id: &str,
    nonce: &str,
    response_uri: &str,
    jwk_thumbprint: Option<&[u8; 32]>,
) -> Result<Handover> {
    tracing::debug!(
        "Creating OID4VP 1.0 Handover - client_id: {}, nonce: {}, jwk_thumbprint: {:?}, response_uri: {}",
        client_id, nonce, jwk_thumbprint.is_some(), response_uri
    );

    ISO180137Handover::new(
        client_id,
        nonce,
        jwk_thumbprint.map(|t| t.as_slice()),
        response_uri,
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
/// * `revealed_namespaces` - Pre-selected namespaces and elements to reveal, or
///   `None` to reveal no issuer-signed data element
/// * `errors` - Optional map of namespace -> element -> error for missing fields
/// * `device_namespaces` - Elements returned in DeviceSigned
/// * `handover` - The handover structure for the SessionTranscript
pub fn build_device_response<H: Serialize + DeserializeOwned + Debug>(
    key_store: Arc<dyn KeyStore>,
    credential: &Mdoc,
    revealed_namespaces: Option<NonEmptyMap<String, NonEmptyVec<Tag24<IssuerSignedItem>>>>,
    errors: Option<NonEmptyMap<String, NonEmptyMap<String, DocumentErrorCode>>>,
    device_namespaces: DeviceNamespaces,
    handover: H,
) -> Result<DeviceResponse> {
    let mdoc = credential.document();

    let device_namespaces =
        Tag24::new(device_namespaces).context("failed to encode device namespaces as CBOR")?;

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
            namespaces: revealed_namespaces,
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
///
/// Missing fields with a value in `device_signed` are returned in DeviceSigned,
/// the others as `DataNotReturned` errors.
pub fn prepare_response<H: Serialize + DeserializeOwned + Debug>(
    key_store: Arc<dyn KeyStore>,
    credential: &Mdoc,
    approved_fields: Vec<FieldId180137>,
    missing_fields: &BTreeMap<String, BTreeSet<String>>,
    device_signed: &DeviceSignedValues,
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

    let mut device_namespaces = DeviceNamespaces::new();
    let mut errors: BTreeMap<String, NonEmptyMap<String, DocumentErrorCode>> = BTreeMap::new();
    for (namespace, element_identifiers) in missing_fields {
        for element_identifier in element_identifiers {
            let device_value = device_signed
                .get(namespace)
                .and_then(|elements| elements.get(element_identifier));
            if let Some(value) = device_value {
                tracing::info!("device signing field: {namespace} {element_identifier}");
                if let Some(elems) = device_namespaces.get_mut(namespace) {
                    elems.insert(element_identifier.clone(), value.clone());
                } else {
                    let element_map = NonEmptyMap::new(element_identifier.clone(), value.clone());
                    device_namespaces.insert(namespace.clone(), element_map);
                }
            } else if let Some(elems) = errors.get_mut(namespace) {
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
    }

    build_device_response(
        key_store,
        credential,
        Some(revealed_namespaces),
        NonEmptyMap::maybe_new(errors),
        device_namespaces,
        handover,
    )
}

#[cfg(test)]
mod tests {
    use std::{collections::BTreeMap, sync::Arc};

    use ciborium::Value as Cbor;
    use isomdl::definitions::device_response::DocumentErrorCode;
    use openid4vp::core::dcql_query::DcqlQuery;
    use serde_json::json;

    use super::{handover_from_components, prepare_response, DeviceSignedValues};
    use crate::{
        crypto::{KeyAlias, RustTestKeyManager},
        oid4vp::iso_18013_7::requested_values::parse_request,
    };

    const NAMESPACE: &str = "org.example.wallet";

    #[tokio::test]
    async fn requested_device_signed_values_are_returned_in_device_signed() {
        let key_manager = Arc::new(RustTestKeyManager::default());
        let key_alias = KeyAlias("".to_string());
        key_manager
            .generate_p256_signing_key(key_alias.clone())
            .await
            .unwrap();
        let credential =
            crate::mdl::util::generate_test_mdl(key_manager.clone(), key_alias).unwrap();

        let dcql_query: DcqlQuery = serde_json::from_value(json!({
            "credentials": [{
                "id": "mDL",
                "format": "mso_mdoc",
                "meta": { "doctype_value": "org.iso.18013.5.1.mDL" },
                "claims": [
                    { "path": ["org.iso.18013.5.1", "family_name"] },
                    { "path": [NAMESPACE, "verified"] },
                    { "path": [NAMESPACE, "score"] },
                    { "path": [NAMESPACE, "method"] }
                ]
            }]
        }))
        .unwrap();
        let request_match = parse_request(&dcql_query, [&credential].into_iter()).remove(0);

        // `unrequested` is not requested and `method` has no value.
        let device_signed: DeviceSignedValues = BTreeMap::from([(
            NAMESPACE.to_string(),
            BTreeMap::from([
                ("verified".to_string(), Cbor::Bool(true)),
                ("score".to_string(), Cbor::Float(0.61)),
                ("unrequested".to_string(), Cbor::Float(0.93)),
            ]),
        )]);

        let response = prepare_response(
            key_manager,
            &credential,
            request_match
                .requested_fields
                .iter()
                .map(|field| field.id.clone())
                .collect(),
            &request_match.missing_fields,
            &device_signed,
            request_match.field_map.clone(),
            handover_from_components("client", "nonce", "https://example.com/response", None)
                .unwrap(),
        )
        .unwrap();

        let document = &response.documents.unwrap()[0];
        let device_namespaces = document.device_signed.namespaces.as_ref();
        let namespace = &device_namespaces[NAMESPACE];
        assert_eq!(namespace.len(), 2);
        assert_eq!(namespace["verified"], Cbor::Bool(true));
        assert_eq!(namespace["score"], Cbor::Float(0.61));

        let errors = document.errors.as_ref().unwrap();
        assert_eq!(errors[NAMESPACE].len(), 1);
        assert_eq!(
            errors[NAMESPACE]["method"],
            DocumentErrorCode::DataNotReturned
        );
    }
}
