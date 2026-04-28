#![allow(deprecated)]

use std::{fmt, sync::Arc};

use anyhow::{anyhow, bail, Context, Result};
use async_trait::async_trait;
use base64::prelude::*;
use isomdl::{cbor, definitions::helpers::ByteStr};
use josekit::{
    jwe::{alg::ecdh_es::EcdhEsJweEncrypter, JweHeader},
    jwk::Jwk,
    jwt::{encode_with_encrypter, JwtPayload},
};
use openid4vp::{
    core::{
        authorization_request::{
            parameters::ResponseMode,
            verification::{verifier::P256Verifier, x509_hash, RequestVerifier},
            AuthorizationRequest, AuthorizationRequestObject, RequestIndirection,
        },
        dcql_query::DcqlQuery,
        iso_18013_7::compute_jwk_thumbprint,
        metadata::WalletMetadata,
        object::{ParsingErrorContext, UntypedObject},
        util::{AsyncHttpClient, ReqwestClient},
    },
    wallet::Wallet as OpenId4vpWallet,
};
use openidvp_draft18::{
    core::{
        authorization_request::{
            parameters::{
                ClientIdScheme as Draft18ClientIdScheme, ClientMetadata as Draft18ClientMetadata,
                ResponseMode as Draft18ResponseMode,
            },
            verification::{self as draft18_verification, verifier::P256Verifier as Draft18P256},
            AuthorizationRequest as Draft18AuthorizationRequest,
            AuthorizationRequestObject as Draft18AuthorizationRequestObject,
            RequestIndirection as Draft18RequestIndirection,
        },
        credential_format::ClaimFormatDesignation as Draft18ClaimFormatDesignation,
        dcql_query::DcqlQuery as Draft18DcqlQuery,
        input_descriptor::InputDescriptor as Draft18InputDescriptor,
        metadata::WalletMetadata as Draft18WalletMetadata,
        object::UntypedObject as Draft18UntypedObject,
        presentation_definition::PresentationDefinition as Draft18PresentationDefinition,
        presentation_submission::{
            DescriptorMap as Draft18DescriptorMap,
            PresentationSubmission as Draft18PresentationSubmission,
        },
        response::{
            AuthorizationResponse as Draft18AuthorizationResponse,
            JwtAuthorizationResponse as Draft18JwtAuthorizationResponse,
        },
        util::ReqwestClient as Draft18ReqwestClient,
    },
    JsonPath as Draft18JsonPath,
    verifier::client::X509SanVariant,
    wallet::Wallet as Draft18Wallet,
};
use serde_json::{json, Value as Json};
use sha2::{Digest, Sha256};
use url::Url;

use super::{
    build_response::build_response,
    default_metadata,
    prepare_response::{handover_from_components, prepare_response, RawResponseUri},
    requested_values::{cbor_to_string, parse_request, FieldId180137, FieldMap, RequestMatch180137},
    ApprovedResponse180137,
};
use crate::{credential::mdoc::Mdoc, crypto::KeyStore};

#[deprecated(
    note = "Compatibility facade for legacy ISO 18013-7 OID4VP integrations only. Prefer the direct OID4VP v1 Annex B APIs for new integrations; this facade may be removed in a future release."
)]
#[derive(uniffi::Enum, Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum Oid4vp180137CompatibilityMode {
    #[default]
    Auto,
    V1,
    Draft18,
}

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum Oid4vp180137FacadeError {
    #[error("Unsupported OID4VP request shape.")]
    UnsupportedRequest,
    #[error("Failed to parse OID4VP request: {0}")]
    RequestParsing(String),
    #[error("Failed to validate OID4VP request: {0}")]
    InvalidRequest(String),
    #[error("Failed to build OID4VP response: {0}")]
    ResponseProcessing(String),
}

#[derive(Debug)]
enum Oid4vp180137SessionInner {
    V1(V1InProgressRequest180137),
    Draft18(Draft18InProgressRequest180137),
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum DetectedIsoRequestVersion {
    V1,
    Draft18,
}

#[derive(Debug, Clone)]
enum ResolvedIsoRequest {
    DirectJson {
        payload: Json,
    },
    Jwt {
        client_id: Option<String>,
        jwt: String,
        payload: Json,
    },
}

#[derive(Debug)]
struct V1InProgressRequest180137 {
    request: AuthorizationRequestObject,
    dcql_query: DcqlQuery,
    request_matches: Vec<Arc<RequestMatch180137>>,
}

#[derive(Debug)]
struct Draft18InProgressRequest180137 {
    request: Draft18AuthorizationRequestObject,
    request_kind: Draft18RequestKind,
    request_matches: Vec<Arc<RequestMatch180137>>,
}

#[derive(Debug, Clone)]
enum Draft18RequestKind {
    Dcql(Draft18DcqlQuery),
    PresentationDefinition(Draft18PresentationDefinition),
}

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
struct Draft18AnnexBHandover(ByteStr, ByteStr, String);

#[deprecated(
    note = "Compatibility facade for legacy ISO 18013-7 OID4VP integrations only. Prefer the direct OID4VP v1 Annex B APIs for new integrations; this facade may be removed in a future release."
)]
#[derive(uniffi::Object, Clone)]
pub struct Oid4vp180137Facade {
    credentials: Vec<Arc<Mdoc>>,
    keystore: Arc<dyn KeyStore>,
    v1_http_client: ReqwestClient,
    v1_metadata: WalletMetadata,
    draft18_http_client: Draft18ReqwestClient,
    draft18_metadata: Draft18WalletMetadata,
}

#[deprecated(
    note = "Compatibility facade for legacy ISO 18013-7 OID4VP integrations only. Prefer the direct OID4VP v1 Annex B APIs for new integrations; this facade may be removed in a future release."
)]
#[derive(uniffi::Object, Debug)]
pub struct Oid4vp180137Session {
    inner: Oid4vp180137SessionInner,
    handler: Oid4vp180137Facade,
}

impl fmt::Debug for Oid4vp180137Facade {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("Oid4vp180137Facade")
            .field("credentials", &self.credentials.len())
            .field("keystore", &"KeyStore")
            .finish()
    }
}

#[uniffi::export(async_runtime = "tokio")]
impl Oid4vp180137Facade {
    #[uniffi::constructor]
    pub fn new(
        credentials: Vec<Arc<Mdoc>>,
        keystore: Arc<dyn KeyStore>,
    ) -> Result<Arc<Self>, Oid4vp180137FacadeError> {
        Ok(Arc::new(Self {
            credentials,
            keystore,
            v1_http_client: ReqwestClient::new()
                .map_err(|e| Oid4vp180137FacadeError::InvalidRequest(format!("{e:#}")))?,
            v1_metadata: v1_facade_metadata(),
            draft18_http_client: Draft18ReqwestClient::new()
                .map_err(|e| Oid4vp180137FacadeError::InvalidRequest(format!("{e:#}")))?,
            draft18_metadata: draft18_default_metadata(),
        }))
    }

    pub async fn process_request(
        &self,
        request: String,
    ) -> Result<Arc<Oid4vp180137Session>, Oid4vp180137FacadeError> {
        self.process_request_with_compatibility_mode(request, Oid4vp180137CompatibilityMode::Auto)
            .await
    }

    pub async fn process_request_with_compatibility_mode(
        &self,
        request: String,
        compatibility_mode: Oid4vp180137CompatibilityMode,
    ) -> Result<Arc<Oid4vp180137Session>, Oid4vp180137FacadeError> {
        let inner = match compatibility_mode {
            Oid4vp180137CompatibilityMode::Auto => self
                .process_auto_request(&request)
                .await
                .map_err(invalid_request)?,
            Oid4vp180137CompatibilityMode::V1 => {
                Oid4vp180137SessionInner::V1(
                    self.process_v1_request(&request)
                        .await
                        .map_err(invalid_request)?,
                )
            }
            Oid4vp180137CompatibilityMode::Draft18 => {
                Oid4vp180137SessionInner::Draft18(
                    self.process_draft18_request(&request)
                        .await
                        .map_err(invalid_request)?,
                )
            }
        };

        Ok(Arc::new(Oid4vp180137Session {
            inner,
            handler: self.clone(),
        }))
    }
}

#[uniffi::export(async_runtime = "tokio")]
impl Oid4vp180137Session {
    pub async fn respond(
        &self,
        approved_response: ApprovedResponse180137,
    ) -> Result<Option<Url>, Oid4vp180137FacadeError> {
        match &self.inner {
            Oid4vp180137SessionInner::V1(request) => self
                .handler
                .respond_v1(request, approved_response)
                .await
                .map_err(response_processing),
            Oid4vp180137SessionInner::Draft18(request) => self
                .handler
                .respond_draft18(request, approved_response)
                .await
                .map_err(response_processing),
        }
    }

    pub fn requested_by(&self) -> Option<String> {
        match &self.inner {
            Oid4vp180137SessionInner::V1(request) => request.request.client_id().map(|id| id.0.clone()),
            Oid4vp180137SessionInner::Draft18(request) => {
                request.request.client_id().map(|id| id.0.clone())
            }
        }
    }

    pub fn matches(&self) -> Vec<Arc<RequestMatch180137>> {
        match &self.inner {
            Oid4vp180137SessionInner::V1(request) => request.request_matches.clone(),
            Oid4vp180137SessionInner::Draft18(request) => request.request_matches.clone(),
        }
    }
}

impl Oid4vp180137Facade {
    async fn process_auto_request(&self, request: &str) -> Result<Oid4vp180137SessionInner> {
        let resolved_request = self.resolve_request_once(request).await?;
        let version = detect_request_version(&resolved_request);
        tracing::info!("ISO OID4VP compatibility facade selected version: {:?}", version);

        match version {
            DetectedIsoRequestVersion::V1 => self
                .process_v1_request_resolved(&resolved_request)
                .await
                .map(Oid4vp180137SessionInner::V1)
                .context("v1 failed"),
            DetectedIsoRequestVersion::Draft18 => self
                .process_draft18_request_resolved(&resolved_request)
                .await
                .map(Oid4vp180137SessionInner::Draft18)
                .context("draft18 failed"),
        }
    }

    async fn process_v1_request(&self, request: &str) -> Result<V1InProgressRequest180137> {
        let request = resolve_v1_request(self, request).await?;
        self.process_v1_request_object(request)
    }

    async fn process_v1_request_resolved(
        &self,
        request: &ResolvedIsoRequest,
    ) -> Result<V1InProgressRequest180137> {
        let request = resolve_v1_request_from_resolved(self, request).await?;
        self.process_v1_request_object(request)
    }

    fn process_v1_request_object(
        &self,
        request: AuthorizationRequestObject,
    ) -> Result<V1InProgressRequest180137> {
        if request.response_mode() != &ResponseMode::DirectPostJwt {
            bail!("cannot respond to {} with a JWE", request.response_mode())
        }

        let dcql_query = request
            .dcql_query()
            .parsing_error()
            .context("failed to get DCQL query from request")?;

        let request_matches =
            parse_request(&dcql_query, self.credentials.iter().map(|credential| credential.as_ref()));

        Ok(V1InProgressRequest180137 {
            request,
            dcql_query,
            request_matches,
        })
    }

    async fn process_draft18_request(
        &self,
        request: &str,
    ) -> Result<Draft18InProgressRequest180137> {
        let request = resolve_draft18_request(self, request).await?;
        self.process_draft18_request_object(request).await
    }

    async fn process_draft18_request_resolved(
        &self,
        request: &ResolvedIsoRequest,
    ) -> Result<Draft18InProgressRequest180137> {
        let request = resolve_draft18_request_from_resolved(self, request).await?;
        self.process_draft18_request_object(request).await
    }

    async fn process_draft18_request_object(
        &self,
        request: Draft18AuthorizationRequestObject,
    ) -> Result<Draft18InProgressRequest180137> {
        if request.response_mode() != &Draft18ResponseMode::DirectPostJwt {
            bail!("cannot respond to {} with a JWE", request.response_mode())
        }

        let (request_kind, request_matches) = if let Some(dcql_query) =
            request.get::<Draft18DcqlQuery>().transpose()?
        {
            let current_dcql_query = convert_dcql_query(&dcql_query)?;
            let request_matches = parse_request(
                &current_dcql_query,
                self.credentials.iter().map(|credential| credential.as_ref()),
            );
            (Draft18RequestKind::Dcql(dcql_query), request_matches)
        } else {
            let presentation_definition = request
                .resolve_presentation_definition(Draft18Wallet::http_client(self))
                .await
                .context("failed to resolve presentation_definition")?
                .context("request object did not contain a presentation_definition")?
                .into_parsed();
            let request_matches = parse_presentation_definition(
                &presentation_definition,
                self.credentials.iter().map(|credential| credential.as_ref()),
            );

            (
                Draft18RequestKind::PresentationDefinition(presentation_definition),
                request_matches,
            )
        };

        Ok(Draft18InProgressRequest180137 {
            request,
            request_kind,
            request_matches,
        })
    }

    async fn resolve_request_once(&self, request: &str) -> Result<ResolvedIsoRequest> {
        if let Ok(url) = Url::parse(request) {
            return resolve_url_request_once(OpenId4vpWallet::http_client(self), url).await;
        }

        let payload = serde_json::from_str::<Json>(request)
            .map_err(|e| anyhow!("unable to parse authorization request object: {e}"))?;

        Ok(ResolvedIsoRequest::DirectJson { payload })
    }

    async fn respond_v1(
        &self,
        request: &V1InProgressRequest180137,
        approved_response: ApprovedResponse180137,
    ) -> Result<Option<Url>> {
        let (credential, request_match) = self.find_selected_credential_and_match(
            approved_response.credential_id,
            &request.request_matches,
        )?;

        warn_about_unapproved_required_fields(request_match, &approved_response.approved_fields);

        let handover = handover_from_components(
            request
                .request
                .client_id()
                .context("missing client_id")?
                .0
                .as_str(),
            request.request.nonce().to_string().as_str(),
            &request
                .request
                .get::<RawResponseUri>()
                .parsing_error()
                .context("missing response_uri")?
                .0,
            openid4vp::core::iso_18013_7::get_encryption_jwk_thumbprint(&request.request)
                .as_ref(),
        )
        .context("failed to generate handover")?;

        let device_response = prepare_response(
            self.keystore.clone(),
            credential,
            approved_response.approved_fields,
            &request_match.missing_fields,
            request_match.field_map.clone(),
            handover,
        )?;

        let response = build_response(&request.request, &request.dcql_query, device_response)?;

        submit_v1_response(self, request.request.clone(), response).await
    }

    async fn respond_draft18(
        &self,
        request: &Draft18InProgressRequest180137,
        approved_response: ApprovedResponse180137,
    ) -> Result<Option<Url>> {
        let (credential, request_match) = self.find_selected_credential_and_match(
            approved_response.credential_id,
            &request.request_matches,
        )?;

        warn_about_unapproved_required_fields(request_match, &approved_response.approved_fields);

        let client_metadata = request
            .request
            .client_metadata()
            .context("failed to resolve client_metadata")?;
        let response_uri = request
            .request
            .get::<Draft18RawResponseUri>()
            .draft18_parsing_error()
            .context("missing response_uri")?;

        let response = match &request.request_kind {
            Draft18RequestKind::Dcql(dcql_query) => {
                let jwk_thumbprint = draft18_jwk_thumbprint(&client_metadata)?;
                let handover = handover_from_components(
                    request
                        .request
                        .client_id()
                        .context("missing client_id")?
                        .0
                        .as_str(),
                    request.request.nonce().to_string().as_str(),
                    &response_uri.0,
                    jwk_thumbprint.as_ref(),
                )
                .context("failed to generate handover")?;

                let device_response = prepare_response(
                    self.keystore.clone(),
                    credential,
                    approved_response.approved_fields.clone(),
                    &request_match.missing_fields,
                    request_match.field_map.clone(),
                    handover,
                )?;

                build_draft18_dcql_response(&request.request, dcql_query, device_response)?
            }
            Draft18RequestKind::PresentationDefinition(presentation_definition) => {
                let mdoc_generated_nonce = generate_nonce();
                let handover = draft18_annex_b_handover(
                    request
                        .request
                        .client_id()
                        .context("missing client_id")?
                        .0
                        .as_str(),
                    &response_uri.0,
                    request.request.nonce().to_string().as_str(),
                    &mdoc_generated_nonce,
                )?;

                let device_response = prepare_response(
                    self.keystore.clone(),
                    credential,
                    approved_response.approved_fields,
                    &request_match.missing_fields,
                    request_match.field_map.clone(),
                    handover,
                )?;

                build_draft18_presentation_definition_response(
                    &request.request,
                    presentation_definition,
                    device_response,
                    &mdoc_generated_nonce,
                )?
            }
        };

        Draft18Wallet::submit_response(self, request.request.clone(), response).await
    }

    fn find_selected_credential_and_match<'a>(
        &'a self,
        credential_id: uuid::Uuid,
        request_matches: &'a [Arc<RequestMatch180137>],
    ) -> Result<(&'a Mdoc, &'a Arc<RequestMatch180137>)> {
        let credential = self
            .credentials
            .iter()
            .find(|credential| credential.id() == credential_id)
            .map(AsRef::as_ref)
            .context("selected credential not found")?;

        let request_match = request_matches
            .iter()
            .find(|request_match| request_match.credential_id == credential_id)
            .context("selected credential not found")?;

        Ok((credential, request_match))
    }
}

impl OpenId4vpWallet for Oid4vp180137Facade {
    type HttpClient = ReqwestClient;

    fn metadata(&self) -> &WalletMetadata {
        &self.v1_metadata
    }

    fn http_client(&self) -> &Self::HttpClient {
        &self.v1_http_client
    }
}

#[async_trait]
impl RequestVerifier for Oid4vp180137Facade {
    async fn redirect_uri(
        &self,
        _decoded_request: &AuthorizationRequestObject,
        request_jwt: Option<String>,
    ) -> Result<()> {
        if request_jwt.is_some() {
            bail!("redirect_uri requests must not use a signed request object")
        }

        Ok(())
    }

    async fn x509_san_dns(
        &self,
        decoded_request: &AuthorizationRequestObject,
        request_jwt: Option<String>,
    ) -> Result<()> {
        let request_jwt =
            request_jwt.context("request JWT is required for x509_san_dns verification")?;
        openid4vp::core::authorization_request::verification::x509_san::validate::<P256Verifier>(
            OpenId4vpWallet::metadata(self),
            decoded_request,
            request_jwt,
            None,
        )
    }

    async fn x509_hash(
        &self,
        decoded_request: &AuthorizationRequestObject,
        request_jwt: Option<String>,
    ) -> Result<()> {
        let request_jwt =
            request_jwt.context("request JWT is required for x509_hash verification")?;
        x509_hash::validate::<P256Verifier>(
            OpenId4vpWallet::metadata(self),
            decoded_request,
            request_jwt,
            None,
        )
    }
}

impl Draft18Wallet for Oid4vp180137Facade {
    type HttpClient = Draft18ReqwestClient;

    fn metadata(&self) -> &Draft18WalletMetadata {
        &self.draft18_metadata
    }

    fn http_client(&self) -> &Self::HttpClient {
        &self.draft18_http_client
    }
}

#[async_trait]
impl draft18_verification::RequestVerifier for Oid4vp180137Facade {
    async fn redirect_uri(
        &self,
        _decoded_request: &Draft18AuthorizationRequestObject,
        request_jwt: Option<String>,
    ) -> Result<()> {
        if request_jwt.is_some() {
            bail!("redirect_uri requests must not use a signed request object")
        }

        Ok(())
    }

    async fn x509_san_dns(
        &self,
        decoded_request: &Draft18AuthorizationRequestObject,
        request_jwt: Option<String>,
    ) -> Result<()> {
        let request_jwt =
            request_jwt.context("request JWT is required for x509_san_dns verification")?;
        openidvp_draft18::core::authorization_request::verification::x509_san::validate::<
            Draft18P256,
        >(
            X509SanVariant::Dns,
            Draft18Wallet::metadata(self),
            decoded_request,
            request_jwt,
            None,
        )
    }

    async fn x509_san_uri(
        &self,
        decoded_request: &Draft18AuthorizationRequestObject,
        request_jwt: Option<String>,
    ) -> Result<()> {
        let request_jwt =
            request_jwt.context("request JWT is required for x509_san_uri verification")?;
        openidvp_draft18::core::authorization_request::verification::x509_san::validate::<
            Draft18P256,
        >(
            X509SanVariant::Uri,
            Draft18Wallet::metadata(self),
            decoded_request,
            request_jwt,
            None,
        )
    }

    async fn other(
        &self,
        client_id_scheme: &str,
        decoded_request: &Draft18AuthorizationRequestObject,
        request_jwt: Option<String>,
    ) -> Result<()> {
        if client_id_scheme != "x509_hash" {
            bail!("'{client_id_scheme}' client verification not implemented")
        }

        let request_jwt =
            request_jwt.context("request JWT is required for x509_hash verification")?;
        let current_request = convert_request_object(decoded_request)?;
        x509_hash::validate::<P256Verifier>(&self.v1_metadata, &current_request, request_jwt, None)
    }
}

async fn resolve_v1_request(
    wallet: &Oid4vp180137Facade,
    request: &str,
) -> Result<AuthorizationRequestObject> {
    if let Ok(url) = Url::parse(request) {
        return OpenId4vpWallet::validate_request(wallet, url)
            .await
            .context("unable to validate authorization request");
    }

    let request_object = serde_json::from_str::<AuthorizationRequestObject>(request)
        .map_err(|e| anyhow!("unable to parse v1 authorization request object: {e}"))?;

    AuthorizationRequest {
        client_id: request_object.client_id().map(|id| id.0.clone()),
        request_indirection: RequestIndirection::Direct(
            serde_json::from_value::<UntypedObject>(serde_json::to_value(&request_object)?)?,
        ),
    }
    .validate(wallet)
    .await
    .context("unable to validate authorization request")
}

async fn resolve_v1_request_from_resolved(
    wallet: &Oid4vp180137Facade,
    request: &ResolvedIsoRequest,
) -> Result<AuthorizationRequestObject> {
    build_v1_authorization_request(request)?
        .validate(wallet)
        .await
        .context("unable to validate authorization request")
}

async fn resolve_draft18_request(
    wallet: &Oid4vp180137Facade,
    request: &str,
) -> Result<Draft18AuthorizationRequestObject> {
    if let Ok(url) = Url::parse(request) {
        return Draft18Wallet::validate_request(wallet, url)
            .await
            .context("unable to validate authorization request");
    }

    let request_object = serde_json::from_str::<Draft18AuthorizationRequestObject>(request)
        .map_err(|e| anyhow!("unable to parse draft18 authorization request object: {e}"))?;

    Draft18AuthorizationRequest {
        client_id: request_object.client_id().map(|id| id.0.clone()),
        request_indirection: Draft18RequestIndirection::Direct(
            serde_json::from_value::<Draft18UntypedObject>(serde_json::to_value(&request_object)?)?,
        ),
    }
    .validate(wallet)
    .await
    .context("unable to validate authorization request")
}

async fn resolve_draft18_request_from_resolved(
    wallet: &Oid4vp180137Facade,
    request: &ResolvedIsoRequest,
) -> Result<Draft18AuthorizationRequestObject> {
    build_draft18_authorization_request(request)?
        .validate(wallet)
        .await
        .context("unable to validate authorization request")
}

async fn submit_v1_response(
    wallet: &Oid4vp180137Facade,
    request: AuthorizationRequestObject,
    response: openid4vp::core::response::AuthorizationResponse,
) -> Result<Option<Url>> {
    OpenId4vpWallet::submit_response(wallet, request, response).await
}

fn build_draft18_dcql_response(
    request: &Draft18AuthorizationRequestObject,
    dcql_query: &Draft18DcqlQuery,
    device_response: isomdl::definitions::DeviceResponse,
) -> Result<Draft18AuthorizationResponse> {
    let device_response = BASE64_URL_SAFE_NO_PAD.encode(
        isomdl::cbor::to_vec(&device_response).context("failed to encode device response as CBOR")?,
    );

    let credential_query_id = dcql_query
        .credentials()
        .first()
        .map(|credential| credential.id().to_string())
        .unwrap_or_else(|| "mDL".to_string());

    let vp_token = json!({
        credential_query_id: [device_response]
    });

    let mut payload = json!({
        "vp_token": vp_token
    });

    if let Some(state) = request.state() {
        payload["state"] = json!(state?.0);
    }

    tracing::info!(
        "Submitting draft18 DCQL response payload: {}",
        serde_json::to_string(&payload).unwrap_or_else(|_| "<payload serialization failed>".into())
    );

    build_draft18_encrypted_response(request, payload)
}

fn build_draft18_presentation_definition_response(
    request: &Draft18AuthorizationRequestObject,
    presentation_definition: &Draft18PresentationDefinition,
    device_response: isomdl::definitions::DeviceResponse,
    mdoc_generated_nonce: &str,
) -> Result<Draft18AuthorizationResponse> {
    let device_response = BASE64_URL_SAFE_NO_PAD.encode(
        cbor::to_vec(&device_response).context("failed to encode device response as CBOR")?,
    );

    let presentation_submission = Draft18PresentationSubmission::new(
        uuid::Uuid::new_v4(),
        presentation_definition.id().clone(),
        presentation_definition
            .input_descriptors()
            .iter()
            .map(descriptor_map_for_input_descriptor)
            .collect::<Result<Vec<_>>>()?,
    );

    let mut payload = json!({
        "vp_token": device_response,
        "presentation_submission": Json::from(presentation_submission),
    });

    if let Some(state) = request.state() {
        payload["state"] = json!(state?.0);
    }

    let payload_keys = payload
        .as_object()
        .map(|obj| obj.keys().cloned().collect::<Vec<_>>())
        .unwrap_or_default();
    let presentation_submission_json = payload.get("presentation_submission").cloned();
    let vp_token_summary = payload
        .get("vp_token")
        .map(|vp_token| match vp_token {
            Json::String(value) => format!("string(len={})", value.len()),
            Json::Array(values) => format!("array(len={})", values.len()),
            Json::Object(map) => format!("object(keys={})", map.len()),
            _ => vp_token.to_string(),
        })
        .unwrap_or_else(|| "<missing>".into());

    tracing::info!(
        "Submitting draft18 presentation_definition response payload keys={:?} vp_token={} presentation_submission_present={} presentation_submission={}",
        payload_keys,
        vp_token_summary,
        presentation_submission_json.is_some(),
        presentation_submission_json
            .map(|value| serde_json::to_string(&value).unwrap_or_else(|_| "<serialization failed>".into()))
            .unwrap_or_else(|| "<missing>".into())
    );

    build_draft18_annex_b_encrypted_response(
        request,
        payload,
        mdoc_generated_nonce,
        request.nonce().to_string().as_str(),
    )
}

fn descriptor_map_for_input_descriptor(
    input_descriptor: &Draft18InputDescriptor,
) -> Result<Draft18DescriptorMap> {
    Ok(Draft18DescriptorMap::new(
        input_descriptor.id.clone(),
        Draft18ClaimFormatDesignation::MsoMDoc,
        "$"
            .parse::<Draft18JsonPath>()
            .context("failed to parse descriptor map path")?,
    ))
}

fn generate_nonce() -> String {
    let nonce_bytes = rand::random::<[u8; 16]>();
    BASE64_URL_SAFE_NO_PAD.encode(nonce_bytes)
}

fn draft18_annex_b_handover(
    client_id: &str,
    response_uri: &str,
    nonce: &str,
    mdoc_generated_nonce: &str,
) -> Result<Draft18AnnexBHandover> {
    let client_id_to_hash = Json::Array(vec![
        Json::String(client_id.to_string()),
        Json::String(mdoc_generated_nonce.to_string()),
    ]);
    let response_uri_to_hash = Json::Array(vec![
        Json::String(response_uri.to_string()),
        Json::String(mdoc_generated_nonce.to_string()),
    ]);

    let client_id_hash = Sha256::digest(cbor::to_vec(&client_id_to_hash)?).to_vec();
    let response_uri_hash = Sha256::digest(cbor::to_vec(&response_uri_to_hash)?).to_vec();

    Ok(Draft18AnnexBHandover(
        ByteStr::from(client_id_hash),
        ByteStr::from(response_uri_hash),
        nonce.to_string(),
    ))
}

fn build_draft18_encrypted_response(
    request: &Draft18AuthorizationRequestObject,
    payload: Json,
) -> Result<Draft18AuthorizationResponse> {
    let client_metadata = request
        .client_metadata()
        .context("failed to resolve client_metadata")?;
    let jwks = client_metadata.jwks().draft18_parsing_error()?;
    let keys: Vec<_> = jwks.keys.iter().collect();
    let jwk_info = openid4vp::core::jwe::find_encryption_jwk(keys.into_iter())
        .context("no suitable encryption key found in client metadata")?;

    let alg = client_metadata
        .authorization_encrypted_response_alg()
        .draft18_parsing_error()?
        .0
        .to_string();
    if alg != "ECDH-ES" {
        bail!("unsupported encryption alg: {alg}")
    }

    let enc = client_metadata
        .authorization_encrypted_response_enc()
        .draft18_parsing_error()?
        .0;

    let jwk_json: Json = serde_json::to_value(&jwk_info.jwk).context("failed to serialize JWK")?;
    let mut builder = openid4vp::core::jwe::JweBuilder::new()
        .payload(payload)
        .recipient_key_json(&jwk_json)
        .context("invalid recipient JWK")?
        .alg(alg)
        .enc(enc);

    if let Some(kid) = &jwk_info.kid {
        builder = builder.kid(kid);
    }

    let jwe = builder.build().context("failed to build JWE")?;

    Ok(Draft18AuthorizationResponse::Jwt(
        Draft18JwtAuthorizationResponse { response: jwe },
    ))
}

fn build_draft18_annex_b_encrypted_response(
    request: &Draft18AuthorizationRequestObject,
    payload: Json,
    apu: &str,
    apv: &str,
) -> Result<Draft18AuthorizationResponse> {
    let client_metadata = request
        .client_metadata()
        .context("failed to resolve client_metadata")?;
    let alg = client_metadata
        .authorization_encrypted_response_alg()
        .draft18_parsing_error()?
        .0;
    if alg != "ECDH-ES" {
        bail!("unsupported encryption alg: {alg}")
    }

    let enc = client_metadata
        .authorization_encrypted_response_enc()
        .draft18_parsing_error()?
        .0;
    if enc != "A256GCM" {
        bail!("unsupported encryption scheme: {enc}")
    }

    let jwk = draft18_get_encryption_jwk(&client_metadata)?;
    let mut jwt_payload = JwtPayload::new();
    let Json::Object(map) = payload else {
        bail!("payload must be a JSON object")
    };
    for (key, value) in map {
        jwt_payload.set_claim(&key, Some(value))?;
    }

    let mut jwe_header = JweHeader::new();
    jwe_header.set_token_type("JWT");
    jwe_header.set_content_encryption(enc);
    jwe_header.set_algorithm(alg);
    jwe_header.set_agreement_partyuinfo(apu);
    jwe_header.set_agreement_partyvinfo(apv);

    if let Some(kid) = jwk.key_id() {
        jwe_header.set_key_id(kid);
    }

    let encrypter: EcdhEsJweEncrypter<p256::NistP256> =
        josekit::jwe::ECDH_ES.encrypter_from_jwk(&jwk)?;
    let jwe = encode_with_encrypter(&jwt_payload, &jwe_header, &encrypter)?;

    Ok(Draft18AuthorizationResponse::Jwt(
        Draft18JwtAuthorizationResponse { response: jwe },
    ))
}

fn draft18_get_encryption_jwk(client_metadata: &Draft18ClientMetadata) -> Result<Jwk> {
    client_metadata
        .jwks()
        .draft18_parsing_error()?
        .keys
        .iter()
        .filter_map(|jwk| {
            let jwk = serde_json::from_value::<Jwk>(Json::Object(jwk.clone()));
            match jwk {
                Ok(jwk) => Some(jwk),
                Err(e) => {
                    tracing::warn!("unable to parse a JWK in keyset: {e}");
                    None
                }
            }
        })
        .find(|jwk| {
            let Some(crv) = jwk.curve() else {
                tracing::warn!("jwk in keyset was missing 'crv'");
                return false;
            };
            if let Some(use_) = jwk.key_use() {
                crv == "P-256" && use_ == "enc"
            } else {
                tracing::warn!("jwk in keyset was missing 'use'");
                crv == "P-256"
            }
        })
        .context("no 'P-256' keys for use 'enc' found in JWK keyset")
}

fn draft18_jwk_thumbprint(client_metadata: &Draft18ClientMetadata) -> Result<Option<[u8; 32]>> {
    let jwks = client_metadata.jwks().draft18_parsing_error()?;
    let keys: Vec<_> = jwks.keys.iter().collect();
    let jwk_info = openid4vp::core::jwe::find_encryption_jwk(keys.into_iter())
        .context("no suitable encryption key found in client metadata")?;
    let jwk_json: Json = serde_json::to_value(&jwk_info.jwk).context("failed to serialize JWK")?;
    Ok(Some(compute_jwk_thumbprint(&jwk_json)?))
}

fn convert_request_object(
    request: &Draft18AuthorizationRequestObject,
) -> Result<AuthorizationRequestObject> {
    serde_json::from_value(serde_json::to_value(request)?).context("failed to convert request object")
}

fn convert_dcql_query(query: &Draft18DcqlQuery) -> Result<DcqlQuery> {
    serde_json::from_value(serde_json::to_value(query)?).context("failed to convert DCQL query")
}

fn parse_presentation_definition<'l, C>(
    definition: &Draft18PresentationDefinition,
    credentials: C,
) -> Vec<Arc<RequestMatch180137>>
where
    C: Iterator<Item = &'l Mdoc>,
{
    credentials
        .filter_map(|credential| match find_presentation_definition_match(definition, credential) {
            Ok(m) => Some(Arc::new(m)),
            Err(e) => {
                tracing::info!("credential did not match draft18 presentation_definition: {e}");
                None
            }
        })
        .collect()
}

fn find_presentation_definition_match(
    definition: &Draft18PresentationDefinition,
    credential: &Mdoc,
) -> Result<RequestMatch180137> {
    let (credential_json, elements_map, field_map) = mdoc_json_and_fields(credential);

    let definition_accepts_mdoc = definition.contains_format(Draft18ClaimFormatDesignation::MsoMDoc)
        || definition.input_descriptors().iter().all(|descriptor| {
            descriptor.format.is_empty()
                || descriptor
                    .format
                    .contains_key(&Draft18ClaimFormatDesignation::MsoMDoc)
        });
    if !definition_accepts_mdoc {
        bail!("presentation_definition does not accept mso_mdoc")
    }

    if !definition.is_credential_match(&credential_json) {
        bail!("credential does not satisfy presentation_definition")
    }

    let mut requested_fields = std::collections::BTreeMap::new();

    for requested_field in definition.requested_fields(&credential_json) {
        let Some((element_identifier, field_id)) = requested_field
            .path
            .iter()
            .find_map(|path| locate_field_id(path, &elements_map))
        else {
            continue;
        };

        let displayable_name = requested_field
            .name
            .clone()
            .unwrap_or_else(|| displayable_name(&element_identifier));

        let displayable_value = field_map
            .get(field_id)
            .and_then(|value| cbor_to_string(&value.1.as_ref().element_value))
            .or_else(|| {
                requested_field
                    .raw_fields
                    .first()
                    .and_then(|value| stringify_json_value(value))
            });

        requested_fields
            .entry(field_id.0.clone())
            .and_modify(|field: &mut super::requested_values::RequestedField180137| {
                field.intent_to_retain |= requested_field.retained;
                field.required |= requested_field.required;
                if field.purpose.is_none() {
                    field.purpose = requested_field.purpose.clone();
                }
            })
            .or_insert_with(|| super::requested_values::RequestedField180137 {
                id: field_id.clone(),
                displayable_name,
                displayable_value,
                selectively_disclosable: true,
                intent_to_retain: requested_field.retained,
                required: requested_field.required,
                purpose: requested_field.purpose.clone(),
            });
    }

    Ok(RequestMatch180137 {
        credential_id: credential.id(),
        field_map,
        requested_fields: requested_fields.into_values().collect(),
        missing_fields: Default::default(),
    })
}

fn mdoc_json_and_fields(
    credential: &Mdoc,
) -> (
    Json,
    std::collections::BTreeMap<String, std::collections::BTreeMap<String, FieldId180137>>,
    FieldMap,
) {
    let mdoc = credential.document();
    let mut age_over_mapping = super::requested_values::calculate_age_over_mapping(&mdoc.namespaces);
    let mut field_map = FieldMap::new();
    let mut elements_map = std::collections::BTreeMap::new();
    let mut namespaces_json = serde_json::Map::new();

    for (namespace, elements) in mdoc.namespaces.iter() {
        let mut namespace_elements = std::collections::BTreeMap::new();
        let mut namespace_json = serde_json::Map::new();

        for (element_identifier, element_value) in elements.iter() {
            let field_id = FieldId180137(uuid::Uuid::new_v4().to_string());
            field_map.insert(field_id.clone(), (namespace.clone(), element_value.clone()));
            namespace_elements.insert(element_identifier.clone(), field_id.clone());

            if namespace == "org.iso.18013.5.1" {
                if let Some(virtual_element_ids) = age_over_mapping.remove(element_identifier) {
                    for virtual_element_id in virtual_element_ids {
                        namespace_elements.insert(virtual_element_id, field_id.clone());
                    }
                }
            }

            if let Some(value) = cbor_to_json_value(&element_value.as_ref().element_value) {
                namespace_json.insert(element_identifier.clone(), value);
            }
        }

        elements_map.insert(namespace.clone(), namespace_elements);
        namespaces_json.insert(namespace.clone(), Json::Object(namespace_json));
    }

    (Json::Object(namespaces_json), elements_map, field_map)
}

fn locate_field_id<'a>(
    path: &str,
    elements_map: &'a std::collections::BTreeMap<
        String,
        std::collections::BTreeMap<String, FieldId180137>,
    >,
) -> Option<(String, &'a FieldId180137)> {
    let (namespace, element_identifier) = parse_namespace_and_element_from_path(path)?;
    elements_map
        .get(&namespace)
        .and_then(|elements| elements.get(&element_identifier))
        .map(|field_id| (element_identifier, field_id))
}

fn parse_namespace_and_element_from_path(path: &str) -> Option<(String, String)> {
    let mut segments = Vec::new();
    let mut remainder = path;

    while let Some(start) = remainder.find("['") {
        let after_start = &remainder[start + 2..];
        let end = after_start.find("']")?;
        segments.push(after_start[..end].to_string());
        remainder = &after_start[end + 2..];
    }

    match segments.as_slice() {
        [namespace, element_identifier, ..] => {
            Some((namespace.clone(), element_identifier.clone()))
        }
        _ => None,
    }
}

fn displayable_name(element_identifier: &str) -> String {
    element_identifier
        .split('_')
        .map(|segment| {
            let Some(first_letter) = segment.chars().next() else {
                return segment.to_string();
            };
            format!("{}{}", first_letter.to_uppercase(), &segment[1..])
        })
        .collect::<Vec<_>>()
        .join(" ")
}

fn stringify_json_value(value: &Json) -> Option<String> {
    match value {
        Json::Null => Some("null".into()),
        Json::Bool(boolean) => Some(boolean.to_string()),
        Json::Number(number) => Some(number.to_string()),
        Json::String(string) => Some(string.clone()),
        Json::Array(_) | Json::Object(_) => serde_json::to_string(value).ok(),
    }
}

fn cbor_to_json_value(value: &ciborium::Value) -> Option<Json> {
    fn key_to_string(value: &ciborium::Value) -> Option<String> {
        match value {
            ciborium::Value::Integer(i) => Some(i128::from(*i).to_string()),
            ciborium::Value::Text(s) => Some(s.clone()),
            ciborium::Value::Float(f) => Some(f.to_string()),
            ciborium::Value::Bool(b) => Some(b.to_string()),
            ciborium::Value::Null => Some("null".to_string()),
            ciborium::Value::Tag(_, inner) => key_to_string(inner),
            _ => None,
        }
    }

    match value {
        ciborium::Value::Integer(i) => Some(Json::Number(i128::from(*i).into())),
        ciborium::Value::Text(s) => Some(Json::String(s.clone())),
        ciborium::Value::Array(values) => Some(Json::Array(
            values.iter().filter_map(cbor_to_json_value).collect(),
        )),
        ciborium::Value::Map(map) => Some(Json::Object(
            map.iter()
                .filter_map(|(key, value)| Some((key_to_string(key)?, cbor_to_json_value(value)?)))
                .collect(),
        )),
        ciborium::Value::Bytes(bytes) => Some(Json::String(format!(
            "data:application/octet-stream;base64,{}",
            BASE64_STANDARD.encode(bytes)
        ))),
        ciborium::Value::Float(f) => serde_json::Number::from_f64(*f).map(Json::Number),
        ciborium::Value::Bool(b) => Some(Json::Bool(*b)),
        ciborium::Value::Null => Some(Json::Null),
        ciborium::Value::Tag(_, inner) => cbor_to_json_value(inner),
        _ => None,
    }
}

fn warn_about_unapproved_required_fields(
    request_match: &RequestMatch180137,
    approved_fields: &[FieldId180137],
) {
    request_match
        .requested_fields
        .iter()
        .filter(|field| field.required)
        .filter(|field| !approved_fields.contains(&field.id))
        .for_each(|field| {
            log::warn!(
                "required field '{}' was not approved, this may result in an error from the verifier",
                field.displayable_name
            )
        });
}

fn invalid_request(error: anyhow::Error) -> Oid4vp180137FacadeError {
    Oid4vp180137FacadeError::InvalidRequest(format!("{error:#}"))
}

fn response_processing(error: anyhow::Error) -> Oid4vp180137FacadeError {
    Oid4vp180137FacadeError::ResponseProcessing(format!("{error:#}"))
}

fn build_v1_authorization_request(request: &ResolvedIsoRequest) -> Result<AuthorizationRequest> {
    match request {
        ResolvedIsoRequest::DirectJson { payload } => {
            let request_object = serde_json::from_value::<AuthorizationRequestObject>(payload.clone())
                .context("unable to parse v1 authorization request object")?;
            Ok(AuthorizationRequest {
                client_id: request_object.client_id().map(|id| id.0.clone()),
                request_indirection: RequestIndirection::Direct(
                    serde_json::from_value::<UntypedObject>(serde_json::to_value(&request_object)?)?,
                ),
            })
        }
        ResolvedIsoRequest::Jwt { client_id, jwt, .. } => Ok(AuthorizationRequest {
            client_id: client_id.clone(),
            request_indirection: RequestIndirection::ByValue {
                request: jwt.clone(),
            },
        }),
    }
}

fn build_draft18_authorization_request(
    request: &ResolvedIsoRequest,
) -> Result<Draft18AuthorizationRequest> {
    match request {
        ResolvedIsoRequest::DirectJson { payload } => {
            let request_object =
                serde_json::from_value::<Draft18AuthorizationRequestObject>(payload.clone())
                    .context("unable to parse draft18 authorization request object")?;
            Ok(Draft18AuthorizationRequest {
                client_id: request_object.client_id().map(|id| id.0.clone()),
                request_indirection: Draft18RequestIndirection::Direct(
                    serde_json::from_value::<Draft18UntypedObject>(serde_json::to_value(
                        &request_object,
                    )?)?,
                ),
            })
        }
        ResolvedIsoRequest::Jwt { client_id, jwt, .. } => Ok(Draft18AuthorizationRequest {
            client_id: client_id.clone(),
            request_indirection: Draft18RequestIndirection::ByValue {
                request: jwt.clone(),
            },
        }),
    }
}

async fn resolve_url_request_once<H: AsyncHttpClient>(
    http_client: &H,
    url: Url,
) -> Result<ResolvedIsoRequest> {
    let client_id = url
        .query_pairs()
        .find(|(name, _)| name.as_ref() == "client_id")
        .map(|(_, value)| value.into_owned());

    if let Some(request) = url
        .query_pairs()
        .find(|(name, _)| name.as_ref() == "request")
        .map(|(_, value)| value.into_owned())
    {
        return Ok(ResolvedIsoRequest::Jwt {
            client_id,
            payload: decode_request_jwt_payload(&request)?,
            jwt: request,
        });
    }

    let request_uri = url
        .query_pairs()
        .find(|(name, _)| name.as_ref() == "request_uri")
        .map(|(_, value)| value.into_owned())
        .context("missing request or request_uri in authorization request")?;

    let request = http::Request::builder()
        .method("GET")
        .uri(request_uri.clone())
        .body(vec![])
        .context("failed to build authorization request request")?;

    let response = http_client
        .execute(request)
        .await
        .context(format!(
            "failed to make authorization request request at {request_uri}"
        ))?;

    let status = response.status();
    let body = String::from_utf8(response.into_body()).with_context(|| {
        format!(
            "failed to parse authorization request response as UTF-8 from {request_uri} (status: {status})"
        )
    })?;

    if !status.is_success() {
        bail!("authorization request request was unsuccessful (status: {status}): {body}")
    }

    Ok(ResolvedIsoRequest::Jwt {
        client_id,
        payload: decode_request_jwt_payload(&body)?,
        jwt: body,
    })
}

fn decode_request_jwt_payload(jwt: &str) -> Result<Json> {
    let payload: Json = ssi::claims::jwt::decode_unverified(jwt)
        .context("unable to decode Authorization Request Object JWT")?;

    if !payload.is_object() {
        bail!("authorization request payload was not a JSON object")
    }

    Ok(payload)
}

fn detect_request_version(request: &ResolvedIsoRequest) -> DetectedIsoRequestVersion {
    let payload = match request {
        ResolvedIsoRequest::DirectJson { payload } => payload,
        ResolvedIsoRequest::Jwt { payload, .. } => payload,
    };

    if contains_key(payload, "presentation_definition")
        || contains_key(payload, "presentation_definition_uri")
        || contains_client_metadata_key(payload, "vp_formats")
        || contains_client_metadata_key(payload, "authorization_encrypted_response_alg")
        || contains_client_metadata_key(payload, "authorization_encrypted_response_enc")
    {
        return DetectedIsoRequestVersion::Draft18;
    }

    if contains_key(payload, "dcql_query")
        || contains_client_metadata_key(payload, "vp_formats_supported")
        || contains_client_metadata_key(payload, "encrypted_response_enc_values_supported")
    {
        return DetectedIsoRequestVersion::V1;
    }

    DetectedIsoRequestVersion::V1
}

fn contains_key(payload: &Json, key: &str) -> bool {
    payload
        .as_object()
        .map(|object| object.contains_key(key))
        .unwrap_or(false)
}

fn contains_client_metadata_key(payload: &Json, key: &str) -> bool {
    payload
        .as_object()
        .and_then(|object| object.get("client_metadata"))
        .and_then(Json::as_object)
        .map(|metadata| metadata.contains_key(key))
        .unwrap_or(false)
}

fn draft18_default_metadata() -> Draft18WalletMetadata {
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
            Draft18ClientIdScheme::REDIRECT_URI,
            Draft18ClientIdScheme::X509_SAN_DNS,
            Draft18ClientIdScheme::X509_SAN_URI,
            "x509_hash"
        ],
        "authorization_encryption_alg_values_supported": [
            "ECDH-ES"
        ],
        "authorization_encryption_enc_values_supported": [
            "A128GCM",
            "A256GCM"
        ],
        "request_object_signing_alg_values_supported": ["ES256"]
    });

    serde_json::from_value(metadata_json).unwrap()
}

fn v1_facade_metadata() -> WalletMetadata {
    let mut metadata_json = serde_json::to_value(default_metadata()).unwrap();
    let Json::Object(map) = &mut metadata_json else {
        unreachable!("wallet metadata should serialize as an object")
    };
    map.insert(
        "client_id_prefixes_supported".into(),
        json!(["redirect_uri", "x509_san_dns", "x509_hash"]),
    );

    serde_json::from_value(metadata_json).unwrap()
}

#[derive(Debug, Clone)]
struct Draft18RawResponseUri(pub String);

impl openidvp_draft18::core::object::TypedParameter for Draft18RawResponseUri {
    const KEY: &'static str = "response_uri";
}

impl TryFrom<Json> for Draft18RawResponseUri {
    type Error = anyhow::Error;

    fn try_from(value: Json) -> Result<Self, Self::Error> {
        let Json::String(uri) = value else {
            bail!("unexpected type")
        };

        Ok(Self(uri))
    }
}

impl From<Draft18RawResponseUri> for Json {
    fn from(value: Draft18RawResponseUri) -> Self {
        Json::String(value.0)
    }
}

trait Draft18ParsingExt<T> {
    fn draft18_parsing_error(self) -> Result<T>;
}

impl<T> Draft18ParsingExt<T> for Option<Result<T>> {
    fn draft18_parsing_error(self) -> Result<T> {
        self.context("missing parameter")?
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{
        crypto::{KeyAlias, RustTestKeyManager},
        mdl::util::generate_test_mdl,
    };
    use wiremock::{
        matchers::{method, path},
        Mock, MockServer, ResponseTemplate,
    };

    fn encryption_jwk() -> Json {
        let mut jwk: Json = serde_json::from_str(include_str!("../../../tests/examples/jwk.json")).unwrap();
        let Json::Object(map) = &mut jwk else {
            panic!("expected object")
        };
        map.insert("alg".into(), Json::String("ECDH-ES".into()));
        map.insert("use".into(), Json::String("enc".into()));
        jwk
    }

    fn dcql_query() -> Json {
        serde_json::from_str(include_str!("../../../tests/examples/18013_7_dcql_query.json")).unwrap()
    }

    fn v1_request_json() -> String {
        json!({
            "client_id": "redirect_uri:https://wallet.example/callback",
            "response_uri": "https://wallet.example/callback",
            "response_type": "vp_token",
            "response_mode": "direct_post.jwt",
            "state": "state-123",
            "nonce": "nonce-123",
            "client_metadata": {
                "jwks": { "keys": [encryption_jwk()] },
                "vp_formats_supported": { "mso_mdoc": {} },
                "encrypted_response_enc_values_supported": ["A256GCM"]
            },
            "dcql_query": dcql_query()
        })
        .to_string()
    }

    fn draft18_request_json() -> String {
        json!({
            "client_id": "redirect_uri:https://wallet.example/callback",
            "presentation_definition": {
                "id": "test-pd",
                "input_descriptors": [
                    {
                        "id": "org.iso.18013.5.1.mDL",
                        "format": {
                            "mso_mdoc": {
                                "alg": ["ES256"]
                            }
                        },
                        "constraints": {
                            "fields": [
                                {
                                    "path": ["$['org.iso.18013.5.1']['family_name']"]
                                },
                                {
                                    "path": ["$['org.iso.18013.5.1']['given_name']"]
                                }
                            ],
                            "limit_disclosure": "required"
                        }
                    }
                ]
            },
            "response_uri": "https://wallet.example/callback",
            "response_type": "vp_token",
            "response_mode": "direct_post.jwt",
            "state": "state-123",
            "nonce": "nonce-123",
            "client_metadata": {
                "jwks": { "keys": [encryption_jwk()] },
                "vp_formats": { "mso_mdoc": {} },
                "authorization_encrypted_response_alg": "ECDH-ES",
                "authorization_encrypted_response_enc": "A256GCM"
            }
        })
        .to_string()
    }

    fn detect_v1_request_json() -> String {
        json!({
            "client_id": "redirect_uri:https://wallet.example/callback",
            "response_uri": "https://wallet.example/callback",
            "response_type": "vp_token",
            "response_mode": "direct_post.jwt",
            "nonce": "nonce-123",
            "client_metadata": {
                "jwks": { "keys": [encryption_jwk()] },
                "vp_formats_supported": { "mso_mdoc": {} },
                "encrypted_response_enc_values_supported": ["A256GCM"]
            },
            "dcql_query": dcql_query()
        })
        .to_string()
    }

    fn request_jwt(payload: Json, typ: Option<&str>) -> String {
        use base64::prelude::*;

        let mut header = json!({
            "alg": "none"
        });
        if let Some(typ) = typ {
            header["typ"] = Json::String(typ.to_string());
        }

        format!(
            "{}.{}.",
            BASE64_URL_SAFE_NO_PAD.encode(serde_json::to_vec(&header).unwrap()),
            BASE64_URL_SAFE_NO_PAD.encode(serde_json::to_vec(&payload).unwrap())
        )
    }

    async fn test_facade() -> Arc<Oid4vp180137Facade> {
        let keystore = Arc::new(RustTestKeyManager::default());
        let key_alias = KeyAlias("test-mdl".into());
        keystore.generate_p256_signing_key(key_alias.clone()).await.unwrap();
        let credential = Arc::new(generate_test_mdl(keystore.clone(), key_alias).unwrap());

        Oid4vp180137Facade::new(vec![credential], keystore)
            .unwrap()
    }

    #[tokio::test]
    async fn processes_v1_json_request_in_auto_mode() {
        let facade = test_facade().await;
        let session = facade.process_request(v1_request_json()).await.unwrap();
        assert!(!session.matches().is_empty());
        assert_eq!(
            session.requested_by().as_deref(),
            Some("redirect_uri:https://wallet.example/callback")
        );
    }

    #[tokio::test]
    async fn processes_draft18_json_request_in_auto_mode() {
        let facade = test_facade().await;
        let session = facade.process_request(draft18_request_json()).await.unwrap();
        assert!(!session.matches().is_empty());
        assert_eq!(
            session.requested_by().as_deref(),
            Some("redirect_uri:https://wallet.example/callback")
        );
    }

    #[test]
    fn detects_v1_request_shape() {
        let resolved = ResolvedIsoRequest::DirectJson {
            payload: serde_json::from_str(&detect_v1_request_json()).unwrap(),
        };
        assert_eq!(detect_request_version(&resolved), DetectedIsoRequestVersion::V1);
    }

    #[test]
    fn detects_draft18_request_shape() {
        let resolved = ResolvedIsoRequest::DirectJson {
            payload: serde_json::from_str(&draft18_request_json()).unwrap(),
        };
        assert_eq!(
            detect_request_version(&resolved),
            DetectedIsoRequestVersion::Draft18
        );
    }

    #[tokio::test]
    async fn resolves_request_uri_once_for_v1_jwt() {
        let server = MockServer::start().await;
        let payload: Json = serde_json::from_str(&detect_v1_request_json()).unwrap();
        let jwt = request_jwt(payload, Some("oauth-authz-req+jwt"));

        Mock::given(method("GET"))
            .and(path("/request.jwt"))
            .respond_with(ResponseTemplate::new(200).set_body_string(jwt))
            .expect(1)
            .mount(&server)
            .await;

        let resolved = resolve_url_request_once(
            &ReqwestClient::new().unwrap(),
            Url::parse(&format!(
                "mdoc-openid4vp://?client_id=redirect_uri%3Ahttps%3A%2F%2Fwallet.example%2Fcallback&request_uri={}",
                urlencoding::encode(&format!("{}/request.jwt", server.uri()))
            ))
            .unwrap(),
        )
        .await
        .unwrap();

        assert_eq!(detect_request_version(&resolved), DetectedIsoRequestVersion::V1);
        server.verify().await;
    }

    #[tokio::test]
    async fn resolves_request_uri_once_for_draft18_jwt() {
        let server = MockServer::start().await;
        let payload: Json = serde_json::from_str(&draft18_request_json()).unwrap();
        let jwt = request_jwt(payload, None);

        Mock::given(method("GET"))
            .and(path("/request.jwt"))
            .respond_with(ResponseTemplate::new(200).set_body_string(jwt))
            .expect(1)
            .mount(&server)
            .await;

        let resolved = resolve_url_request_once(
            &ReqwestClient::new().unwrap(),
            Url::parse(&format!(
                "mdoc-openid4vp://?client_id=tools.vii.us01.mattr.global&client_id_scheme=x509_san_dns&request_uri={}",
                urlencoding::encode(&format!("{}/request.jwt", server.uri()))
            ))
            .unwrap(),
        )
        .await
        .unwrap();

        assert_eq!(
            detect_request_version(&resolved),
            DetectedIsoRequestVersion::Draft18
        );
        server.verify().await;
    }
}
