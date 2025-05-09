//! CLI test wallet for the 18013-7 Annex B OpenID4VP profile.

pub mod build_response;
pub mod prepare_response;
pub mod requested_values;

use core::fmt;
use std::sync::Arc;

use anyhow::{bail, Context, Result};
use async_trait::async_trait;
use base64::prelude::*;
use build_response::build_response;
use openid4vp::{
    core::{
        authorization_request::{
            parameters::ResponseMode,
            verification::{verifier::P256Verifier, RequestVerifier},
            AuthorizationRequestObject,
        },
        metadata::WalletMetadata,
        presentation_definition::PresentationDefinition,
        util::ReqwestClient,
    },
    wallet::Wallet as OpenID4VPWallet,
};
use prepare_response::{prepare_response, Handover};
use requested_values::{parse_request, FieldId180137, RequestMatch180137};
use serde_json::json;
use ssi::crypto::rand::{thread_rng, Rng};
use url::Url;
use uuid::Uuid;

use crate::{credential::mdoc::Mdoc, crypto::KeyStore};

/// Handler for OpenID4VP requests according to the profile in ISO/IEC 18013-7 Annex B.
///
/// Notably this supports requests which use the URI scheme `mdoc-openid4vp://`.
#[derive(uniffi::Object, Clone)]
pub struct OID4VP180137 {
    credentials: Vec<Arc<Mdoc>>,
    http_client: ReqwestClient,
    keystore: Arc<dyn KeyStore>,
    metadata: WalletMetadata,
}

#[derive(uniffi::Object)]
pub struct InProgressRequest180137 {
    pub request: AuthorizationRequestObject,
    pub presentation_definition: PresentationDefinition,
    pub request_matches: Vec<Arc<RequestMatch180137>>,
    pub handler: OID4VP180137,
}

#[derive(Debug, uniffi::Record)]
pub struct ApprovedResponse180137 {
    pub credential_id: Uuid,
    pub approved_fields: Vec<FieldId180137>,
}

#[derive(Debug, uniffi::Error)]
pub enum OID4VP180137Error {
    Initialization(String),
    InvalidRequest(String),
    ResponseProcessing(String),
}

impl fmt::Display for OID4VP180137Error {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(
            f,
            "{}",
            match self {
                OID4VP180137Error::Initialization(s) => s,
                OID4VP180137Error::InvalidRequest(s) => s,
                OID4VP180137Error::ResponseProcessing(s) => s,
            }
        )
    }
}

impl OID4VP180137Error {
    fn initialization(error: anyhow::Error) -> Self {
        Self::Initialization(format!("{error:#}"))
    }

    fn invalid_request(error: anyhow::Error) -> Self {
        Self::InvalidRequest(format!("{error:#}"))
    }

    fn response_processing(error: anyhow::Error) -> Self {
        Self::ResponseProcessing(format!("{error:#}"))
    }
}

#[uniffi::export(async_runtime = "tokio")]
impl OID4VP180137 {
    #[uniffi::constructor]
    pub fn new(
        credentials: Vec<Arc<Mdoc>>,
        keystore: Arc<dyn KeyStore>,
    ) -> Result<Self, OID4VP180137Error> {
        Ok(Self {
            credentials,
            keystore,
            http_client: openid4vp::core::util::ReqwestClient::new()
                .map_err(OID4VP180137Error::initialization)?,
            metadata: default_metadata(),
        })
    }

    pub async fn process_request(
        &self,
        url: Url,
    ) -> Result<InProgressRequest180137, OID4VP180137Error> {
        self.process_request_inner(url)
            .await
            .map_err(OID4VP180137Error::invalid_request)
    }
}

impl OID4VP180137 {
    async fn process_request_inner(&self, url: Url) -> Result<InProgressRequest180137> {
        let request = self
            .validate_request(url)
            .await
            .context("failed to validate the request")?;

        if request.response_mode() != &ResponseMode::DirectPostJwt {
            bail!("cannot respond to {} with a JWE", request.response_mode())
        }

        let presentation_definition = request
            .resolve_presentation_definition(self.http_client())
            .await
            .context("failed to resolve the presentation definition")?
            .context("request object does not contain a presentation definition")?
            .into_parsed();

        let request_matches = parse_request(
            &presentation_definition,
            self.credentials.iter().map(|c| c.as_ref()),
        );

        Ok(InProgressRequest180137 {
            request,
            presentation_definition,
            request_matches,
            handler: self.clone(),
        })
    }
}

#[uniffi::export(async_runtime = "tokio")]
impl InProgressRequest180137 {
    pub async fn respond(
        &self,
        approved_response: ApprovedResponse180137,
    ) -> Result<Option<Url>, OID4VP180137Error> {
        self.respond_inner(approved_response)
            .await
            .map_err(OID4VP180137Error::response_processing)
    }

    pub fn matches(&self) -> Vec<Arc<RequestMatch180137>> {
        self.request_matches.clone()
    }
}

impl InProgressRequest180137 {
    async fn respond_inner(
        &self,
        approved_response: ApprovedResponse180137,
    ) -> Result<Option<Url>> {
        let credential = self
            .handler
            .credentials
            .iter()
            .find(|credential| credential.id() == approved_response.credential_id)
            .context("selected credential not found")?;

        let approved_fields = approved_response.approved_fields;
        let request_match = self
            .request_matches
            .iter()
            .find(|request_match| request_match.credential_id == approved_response.credential_id)
            .context("selected credential not found")?;

        request_match.requested_fields.iter()
            .filter(|field| field.required)
            .filter(|field| !approved_fields.contains(&field.id))
            .for_each(|field| log::warn!("required field '{}' was not approved, this may result in an error from the verifier", field.displayable_name));

        let field_map = request_match.field_map.clone();
        let mdoc_generated_nonce = generate_nonce();

        let handover = Handover::new(&self.request, mdoc_generated_nonce.clone())
            .context("failed to generate handover")?;
        let device_response = prepare_response(
            self.handler.keystore.clone(),
            credential,
            approved_fields,
            &request_match.missing_fields,
            field_map,
            handover,
        )?;

        let response = build_response(
            &self.request,
            &self.presentation_definition,
            device_response,
            mdoc_generated_nonce,
        )?;

        self.handler
            .submit_response(self.request.clone(), response)
            .await
    }
}

impl OpenID4VPWallet for OID4VP180137 {
    type HttpClient = ReqwestClient;

    fn metadata(&self) -> &WalletMetadata {
        &self.metadata
    }

    fn http_client(&self) -> &Self::HttpClient {
        &self.http_client
    }
}

#[async_trait]
impl RequestVerifier for OID4VP180137 {
    async fn x509_san_dns(
        &self,
        decoded_request: &AuthorizationRequestObject,
        request_jwt: Option<String>,
    ) -> Result<()> {
        let request_jwt =
            request_jwt.context("request JWT is required for x509_san_dns verification")?;
        openid4vp::core::authorization_request::verification::x509_san::validate::<P256Verifier>(
            openid4vp::verifier::client::X509SanVariant::Dns,
            &self.metadata,
            decoded_request,
            request_jwt,
            None,
        )
    }
}

pub fn generate_nonce() -> String {
    let nonce_bytes = thread_rng().gen::<[u8; 16]>();
    BASE64_URL_SAFE_NO_PAD.encode(nonce_bytes)
}

fn default_metadata() -> WalletMetadata {
    let metadata_json = json!({
        "issuer": "https://self-issued.me/v2",
        "authorization_endpoint": "mdoc-openid4vp://",
        "response_types_supported": [
            "vp_token"
        ],
        "vp_formats_supported": {
            "mso_mdoc": {}
        },
        "client_id_schemes_supported": [
            "x509_san_dns"
        ],
        "authorization_encryption_alg_values_supported": [
            "ECDH-ES"
        ],
        "authorization_encryption_enc_values_supported": [
            "A256GCM"
        ],
        // Missing from the default wallet metadata in the specification, but necessary to support signed authorization requests.
        "request_object_signing_alg_values_supported": ["ES256"]
    });

    // Unwrap safety: unit tested.
    serde_json::from_value(metadata_json).unwrap()
}

#[cfg(test)]
mod test {

    #[test]
    fn default_metadata() {
        super::default_metadata();
    }
}
