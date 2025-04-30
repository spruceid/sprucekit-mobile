mod build_response;
mod prepare_response;
mod requested_values;

use std::{fmt, sync::Arc};

use anyhow::{bail, Context, Result};
use async_trait::async_trait;
use build_response::Responder;
use openid4vp::{
    core::{
        authorization_request::{
            parameters::ExpectedOrigins,
            verification::{verifier::P256Verifier, x509_san, RequestVerifier},
            AuthorizationRequest, AuthorizationRequestObject,
        },
        dcql_query::DcqlQuery,
        metadata::WalletMetadata,
        object::ParsingErrorContext,
        util::ReqwestClient,
    },
    verifier::client::X509SanVariant,
    wallet::Wallet,
};
use prepare_response::{vp_token, Handover};
use requested_values::find_match;
use serde_json::json;
use ssi::{claims::JwsBuf, jwk::Algorithm};

use crate::{credential::mdoc::Mdoc, crypto::KeyStore};

use super::iso_18013_7::{
    prepare_response::prepare_response,
    requested_values::{FieldId180137, RequestMatch180137},
};

#[derive(uniffi::Object)]
pub struct InProgressRequestDcApi {
    dcql_credential_id: String,
    mdoc: Arc<Mdoc>,
    origin: String,
    responder: Responder,
    request: AuthorizationRequest,
    request_object: AuthorizationRequestObject,
    request_match: RequestMatch180137,
    wallet_activity: WalletActivity,
}

struct WalletActivity {
    http_client: ReqwestClient,
    origin: String,
    wallet_metadata: WalletMetadata,
}

impl Wallet for WalletActivity {
    type HttpClient = ReqwestClient;

    fn http_client(&self) -> &Self::HttpClient {
        &self.http_client
    }

    fn metadata(&self) -> &WalletMetadata {
        &self.wallet_metadata
    }
}

impl WalletActivity {
    fn check_expected_origins(&self, request: &AuthorizationRequestObject) -> Result<()> {
        let expected_origins: ExpectedOrigins = request.get().parsing_error()?;
        // This occurs if the request has been forwarded by an attacker, or if the verifier is misconfigured.
        if !expected_origins.0.contains(&self.origin) {
            bail!("expected origin not found in request");
        }
        Ok(())
    }

    async fn effective_client_id(&self, request: &AuthorizationRequest) -> Result<String> {
        let (aro, jws) = request.resolve_request(self.http_client()).await?;
        if let Some(jws) = jws {
            let jws = JwsBuf::new(jws).context("failed to decode JWS")?;
            let jwt = jws.into_decoded().context("failed to decode JWT")?;
            if jwt.header().algorithm == Algorithm::None {
                return Ok(format!("web-origin:{}", self.origin));
            }
        } else {
            return Ok(format!("web-origin:{}", self.origin));
        }

        Ok(aro
            .client_id()
            .context("signed request missing client_id")?
            .0
            .clone())
    }
}

#[async_trait]
impl RequestVerifier for WalletActivity {
    async fn none(
        &self,
        _decoded_request: &AuthorizationRequestObject,
        _request_jwt: Option<String>,
    ) -> Result<()> {
        Ok(())
    }

    async fn x509_san_dns(
        &self,
        decoded_request: &AuthorizationRequestObject,
        request_jwt: Option<String>,
    ) -> Result<()> {
        let request_jwt =
            request_jwt.context("request JWT is required for x509_san_dns verification")?;
        self.check_expected_origins(decoded_request)?;
        // TODO: Add trusted roots and implement chain verification in openid4vp.
        x509_san::validate::<P256Verifier>(
            X509SanVariant::Dns,
            self.metadata(),
            decoded_request,
            request_jwt,
            None,
        )
    }

    async fn x509_san_uri(
        &self,
        decoded_request: &AuthorizationRequestObject,
        request_jwt: Option<String>,
    ) -> Result<()> {
        let request_jwt =
            request_jwt.context("request JWT is required for x509_san_uri verification")?;
        self.check_expected_origins(decoded_request)?;
        // TODO: Add trusted roots and implement chain verification in openid4vp.
        x509_san::validate::<P256Verifier>(
            X509SanVariant::Uri,
            self.metadata(),
            decoded_request,
            request_jwt,
            None,
        )
    }
}

/// Handle a DC API request.
///
/// Supports OpenID4VP Draft 24 using DCQL for mDL only.
#[uniffi::export(async_runtime = "tokio")]
pub async fn handle_dc_api_request(
    dcql_credential_id: String,
    mdoc: Arc<Mdoc>,
    origin: String,
    request_json: String,
) -> Result<InProgressRequestDcApi, DcApiError> {
    let wallet_activity = WalletActivity {
        http_client: ReqwestClient::new().map_err(DcApiError::internal_error)?,
        origin: origin.clone(),
        wallet_metadata: default_metadata(),
    };

    let request: AuthorizationRequest = serde_json::from_str(&request_json)
        .context(request_json)
        .context("failed to parse the request")
        .map_err(DcApiError::invalid_request)?;

    let request_object = request
        .clone()
        .validate(&wallet_activity)
        .await
        .context("the request is could not be verified")
        .map_err(DcApiError::invalid_request)?;

    let responder = Responder::new(&request_object)
        .context("could not build a responder for the request")
        .map_err(DcApiError::invalid_request)?;

    let query: DcqlQuery = request_object
        .get()
        .parsing_error()
        .map_err(DcApiError::invalid_request)?;

    let credential_query = query
        .credentials()
        .iter()
        .find(|c| c.id() == dcql_credential_id)
        .context("requested credential not found")
        .map_err(DcApiError::invalid_request)?;

    let request_match = find_match(credential_query, &mdoc)
        .context("the selected credential does not match the request")
        .map_err(DcApiError::invalid_request)?;

    Ok(InProgressRequestDcApi {
        dcql_credential_id,
        mdoc,
        origin,
        responder,
        request,
        request_object,
        request_match,
        wallet_activity,
    })
}

#[uniffi::export]
impl InProgressRequestDcApi {
    pub fn get_match(&self) -> RequestMatch180137 {
        self.request_match.clone()
    }

    pub fn get_origin(&self) -> String {
        self.origin.clone()
    }

    /// Generate a response for the request.
    ///
    /// The response is either a JWE or a serialized JSON Object.
    pub async fn respond(
        &self,
        keystore: Arc<dyn KeyStore>,
        approved_fields: Vec<FieldId180137>,
    ) -> Result<String, DcApiError> {
        let handover = Handover::new(
            self.origin.clone(),
            self.wallet_activity
                .effective_client_id(&self.request)
                .await
                .context("failed to determine the effective client id")
                .map_err(DcApiError::invalid_request)?,
            self.request_object.nonce().to_string(),
        )
        .context("failed to create a handover")
        .map_err(DcApiError::internal_error)?;

        let device_response = prepare_response(
            keystore,
            &self.mdoc,
            approved_fields,
            &self.request_match.missing_fields,
            self.request_match.field_map.clone(),
            handover,
        )
        .context("failed to prepare the device response")
        .map_err(DcApiError::internal_error)?;

        let vp_token = vp_token(self.dcql_credential_id.clone(), device_response)
            .context("failed to create a VP token")
            .map_err(DcApiError::internal_error)?;

        self.responder
            .response(vp_token)
            .context("failed to create a response")
            .map_err(DcApiError::internal_error)
    }
}

#[derive(Debug, uniffi::Error)]
pub enum DcApiError {
    InvalidRequest(String),
    InternalError(String),
}

impl DcApiError {
    fn invalid_request<E: fmt::Display>(error: E) -> Self {
        Self::InvalidRequest(format!("{error:#}"))
    }

    fn internal_error<E: fmt::Display>(error: E) -> Self {
        Self::InternalError(format!("{error:#}"))
    }

    fn inner(&self) -> &str {
        match self {
            DcApiError::InvalidRequest(s) => s,
            DcApiError::InternalError(s) => s,
        }
    }

    fn name(&self) -> &str {
        match self {
            DcApiError::InvalidRequest(_) => "InvalidRequest",
            DcApiError::InternalError(_) => "InternalError",
        }
    }
}

impl fmt::Display for DcApiError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}: {}", self.name(), self.inner())
    }
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
            "x509_san_dns",
            "x509_san_uri"
        ],
        "authorization_encryption_alg_values_supported": [
            "ECDH-ES"
        ],
        "authorization_encryption_enc_values_supported": [
            "A128GCM"
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
        let metadata = super::default_metadata();
        assert_eq!(
            metadata.authorization_endpoint().0.as_str(),
            "mdoc-openid4vp://"
        );
    }
}
