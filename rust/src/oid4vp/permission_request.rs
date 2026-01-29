use super::error::OID4VPError;
use super::presentation::{PresentationError, PresentationOptions, PresentationSigner};
use crate::credential::{Credential, ParsedCredential, PresentableCredential};

use std::collections::HashMap;
use std::fmt::Debug;
use std::sync::{Arc, RwLock};

use base64::{engine::general_purpose::URL_SAFE, Engine as _};
use itertools::Itertools;
use openid4vp::core::authorization_request::parameters::ResponseMode;
use openid4vp::core::authorization_request::AuthorizationRequestObject;
use openid4vp::core::dcql_query::DcqlQuery;
use openid4vp::core::response::parameters::{VpToken, VpTokenItem};
use openid4vp::core::response::{AuthorizationResponse, UnencodedAuthorizationResponse};
use uuid::Uuid;

/// Type alias for mapping credential query ids to matching credentials
/// stored in the VDC collection. This mapping is used to provide a
/// shared state between native code and the rust code, to select
/// the appropriate credentials for a given credential query.
pub type CredentialQueryCredentialMap = HashMap<String, Vec<Credential>>;

/// A clonable and thread-safe reference to the credential query credential map.
pub type CredentialQueryCredentialMapRef = Arc<RwLock<CredentialQueryCredentialMap>>;

/// A clonable and thread-safe reference to the selected credential map.
pub type SelectedCredentialMapRef = Arc<RwLock<HashMap<String, Vec<Uuid>>>>;

#[derive(uniffi::Error, thiserror::Error, Debug)]
pub enum PermissionRequestError {
    /// Permission denied for requested presentation.
    #[error("Permission denied for requested presentation.")]
    PermissionDenied,

    /// No credentials found matching the DCQL query.
    #[error("No credentials found matching the DCQL query.")]
    NoCredentialsFound,

    /// Credential not found for credential query id.
    #[error("Credential not found for credential query id: {0}")]
    CredentialNotFound(String),

    /// Credential query not found for credential query id.
    #[error("Credential query not found for credential query id: {0}")]
    CredentialQueryNotFound(String),

    /// Invalid selected credential for requested field. Selected
    /// credential does not match optional credentials.
    #[error("Selected credential type, {0}, does not match requested credential types: {1}")]
    InvalidSelectedCredential(String, String),

    /// Credential Presentation Error
    ///
    /// failed to present the credential.
    #[error("Credential Presentation Error: {0}")]
    CredentialPresentation(String),

    #[error("Failed to obtain permission request read/write lock: {0}")]
    RwLock(String),

    #[error("Failed to cryptographically sign verifiable presentation: {0}")]
    PresentationSigning(String),

    #[error("Invalid or Missing Cryptographic Suite: {0}")]
    CryptographicSuite(String),

    #[error("Invalid Verification Method Identifier: {0}")]
    VerificationMethod(String),

    #[error(transparent)]
    Presentation(#[from] PresentationError),
}

#[derive(Debug, uniffi::Object)]
pub struct RequestedField {
    /// A unique ID for the requested field
    pub(crate) id: Uuid,
    pub(crate) name: Option<String>,
    pub(crate) path: String,
    pub(crate) required: bool,
    pub(crate) retained: bool,
    pub(crate) purpose: Option<String>,
    pub(crate) credential_query_id: String,
    // the `raw_field` represents the actual field
    // being selected by the DCQL claims query path
    pub(crate) raw_fields: Vec<serde_json::Value>,
}

impl RequestedField {
    /// Create a new RequestedField from DCQL claims query
    pub fn from_dcql_claims(
        credential_query_id: String,
        path: Vec<String>,
        raw_fields: Vec<serde_json::Value>,
    ) -> Self {
        Self {
            id: Uuid::new_v4(),
            name: None,
            path: path.into_iter().map(|v| URL_SAFE.encode(v)).join(","),
            required: true, // DCQL claims are required by default
            retained: false,
            purpose: None,
            credential_query_id,
            raw_fields,
        }
    }

    /// Create a new RequestedField from DCQL claims query with an explicit name
    pub fn from_dcql_claims_with_name(
        credential_query_id: String,
        path: Vec<String>,
        raw_fields: Vec<serde_json::Value>,
        name: Option<String>,
    ) -> Self {
        Self {
            id: Uuid::new_v4(),
            name,
            path: path.into_iter().map(|v| URL_SAFE.encode(v)).join(","),
            required: true, // DCQL claims are required by default
            retained: false,
            purpose: None,
            credential_query_id,
            raw_fields,
        }
    }
}

/// Public methods for the RequestedField struct.
#[uniffi::export]
impl RequestedField {
    /// Return the unique ID for the request field.
    pub fn id(&self) -> Uuid {
        self.id
    }

    /// Return the credential query id the requested field belongs to
    pub fn credential_query_id(&self) -> String {
        self.credential_query_id.clone()
    }

    /// Return the field name
    pub fn name(&self) -> Option<String> {
        self.name.clone()
    }

    /// Return the JsonPath of the field
    pub fn path(&self) -> String {
        self.path.clone()
    }

    /// Return the field required status
    pub fn required(&self) -> bool {
        self.required
    }

    /// Return the field retained status
    pub fn retained(&self) -> bool {
        self.retained
    }

    /// Return the purpose of the requested field.
    pub fn purpose(&self) -> Option<String> {
        self.purpose.clone()
    }

    /// Return the stringified JSON raw fields.
    pub fn raw_fields(&self) -> Vec<String> {
        self.raw_fields
            .iter()
            .filter_map(|value| serde_json::to_string(value).ok())
            .collect()
    }
}

/// A group of credentials that match a specific credential query.
///
/// This struct is used to group credentials by their credential_query_id,
/// allowing the UI to display credentials in sections based on what the
/// verifier is requesting.
#[derive(Debug, Clone, uniffi::Record)]
pub struct CredentialQueryGroup {
    /// The credential query ID from the DCQL query.
    pub credential_query_id: String,
    /// The list of credentials that match this credential query.
    pub credentials: Vec<Arc<PresentableCredential>>,
}

/// A requirement that the user must satisfy by selecting credentials.
///
/// When `credential_sets` is present in the DCQL query, each credential_set
/// becomes a requirement. When absent, each credential_query is a requirement.
///
/// Within a requirement, multiple credential queries may be alternatives (OR),
/// meaning the user only needs to select ONE credential to satisfy the requirement.
#[derive(Debug, Clone, uniffi::Record)]
pub struct CredentialRequirement {
    /// A display-friendly name for this requirement section.
    /// Derived from the credential query IDs.
    pub display_name: String,
    /// Whether this requirement is mandatory.
    pub required: bool,
    /// The credential query IDs that can satisfy this requirement (OR relationship).
    pub credential_query_ids: Vec<String>,
    /// All credentials that can satisfy this requirement.
    /// User should select ONE credential from this list.
    pub credentials: Vec<Arc<PresentableCredential>>,
}

#[derive(Clone, uniffi::Object)]
pub struct PermissionRequest {
    pub(crate) dcql_query: DcqlQuery,
    pub(crate) credentials: Vec<Arc<PresentableCredential>>,
    pub(crate) request: AuthorizationRequestObject,
    pub(crate) signer: Arc<Box<dyn PresentationSigner>>,
    pub(crate) context_map: Option<HashMap<String, String>>,
    pub(crate) keystore: Option<Arc<dyn crate::crypto::KeyStore>>,
}

impl std::fmt::Debug for PermissionRequest {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("PermissionRequest")
            .field("dcql_query", &self.dcql_query)
            .field("credentials", &self.credentials)
            .field("request", &self.request)
            .field("context_map", &self.context_map)
            .field("keystore", &self.keystore.as_ref().map(|_| "KeyStore"))
            .finish()
    }
}

impl PermissionRequest {
    pub fn new(
        dcql_query: DcqlQuery,
        credentials: Vec<Arc<PresentableCredential>>,
        request: AuthorizationRequestObject,
        signer: Arc<Box<dyn PresentationSigner>>,
        context_map: Option<HashMap<String, String>>,
        keystore: Option<Arc<dyn crate::crypto::KeyStore>>,
    ) -> Arc<Self> {
        Arc::new(Self {
            dcql_query,
            credentials,
            request,
            signer,
            context_map,
            keystore,
        })
    }
}

#[uniffi::export(async_runtime = "tokio")]
impl PermissionRequest {
    /// Return the filtered list of credentials that matched
    /// the DCQL query.
    pub fn credentials(&self) -> Vec<Arc<PresentableCredential>> {
        self.credentials.clone()
    }

    /// Return the requested fields for a given credential.
    ///
    /// NOTE: This will return only the requested fields for a given credential.
    pub fn requested_fields(
        &self,
        credential: &Arc<PresentableCredential>,
    ) -> Vec<Arc<RequestedField>> {
        ParsedCredential {
            inner: credential.inner.clone(),
        }
        .requested_fields_dcql(&self.dcql_query, &credential.credential_query_id)
    }

    /// Return the client ID for the authorization request.
    ///
    /// This can be used by the user interface to show who
    /// is requesting the presentation from the wallet holder.
    pub fn client_id(&self) -> Option<String> {
        self.request.client_id().map(|id| id.0.clone())
    }

    /// Return the domain name of the redirect URI.
    ///
    /// This can be used by the user interface to show where
    /// the presentation will be sent. It may also be used to show
    /// the domain name of the verifier as an alternative to the client_id.
    pub fn domain(&self) -> Option<String> {
        self.request.return_uri().domain().map(ToOwned::to_owned)
    }

    /// Construct a new permission response for the given credential.
    pub async fn create_permission_response(
        &self,
        selected_credentials: Vec<Arc<PresentableCredential>>,
        selected_fields: Vec<Vec<String>>,
        response_options: ResponseOptions,
    ) -> Result<Arc<PermissionResponse>, OID4VPError> {
        log::debug!("Creating Permission Response");

        // Ensure that the selected credentials are not empty.
        if selected_credentials.is_empty() {
            return Err(PermissionRequestError::InvalidSelectedCredential(
                "No selected credentials".to_string(),
                "DCQL query credentials".to_string(),
            )
            .into());
        }

        // Ensure that there are selected fields for all credentials.
        if selected_fields.len() != selected_credentials.len() {
            return Err(PermissionRequestError::InvalidSelectedCredential(
                "Selected credentials length must match selected fields length".to_string(),
                "DCQL query credentials".to_string(),
            )
            .into());
        }

        let selected_credentials: Vec<Arc<PresentableCredential>> = selected_credentials
            .iter()
            .zip(selected_fields)
            .map(|(sc, sf)| {
                Arc::new(PresentableCredential {
                    inner: sc.inner.clone(),
                    selected_fields: Some(sf),
                    credential_query_id: sc.credential_query_id.clone(),
                })
            })
            .collect();

        // Set options for constructing a verifiable presentation.
        let options = PresentationOptions {
            request: &self.request,
            signer: self.signer.clone(),
            context_map: self.context_map.clone(),
            response_options: &response_options,
            keystore: self.keystore.clone(),
        };

        let mut vp_token_map: HashMap<String, Vec<VpTokenItem>> = HashMap::new();

        for cred in &selected_credentials {
            let token_item = cred.as_vp_token(&options).await?;
            vp_token_map
                .entry(cred.credential_query_id.clone())
                .or_default()
                .push(token_item);
        }

        let vp_token = VpToken(vp_token_map);

        Ok(Arc::new(PermissionResponse {
            selected_credentials,
            dcql_query: self.dcql_query.clone(),
            authorization_request: self.request.clone(),
            vp_token,
            options: response_options,
        }))
    }

    /// Return the purpose of the presentation request.
    /// Note: In OID4VP v1.0, the purpose field is not part of the DCQL credential set query.
    /// This method is kept for API compatibility but always returns None.
    pub fn purpose(&self) -> Option<String> {
        // Purpose is not available in OID4VP v1.0 DCQL specification
        None
    }

    /// Return whether the DCQL query is requesting
    /// multiple credentials to satisfy the presentation.
    ///
    /// Will return true IFF multiple credential queries exist
    /// in the DCQL query.
    pub fn is_multi_credential_selection(&self) -> bool {
        self.dcql_query.credentials().len() > 1
    }

    /// Returns boolean whether the DCQL query
    /// matches multiple credentials of the same type that
    /// can satisfy the request.
    pub fn is_multi_credential_matching(&self) -> bool {
        // Group credentials by credential_query_id and check if any group has multiple credentials
        let mut query_counts: HashMap<&String, usize> = HashMap::new();
        for cred in &self.credentials {
            *query_counts.entry(&cred.credential_query_id).or_insert(0) += 1;
        }
        query_counts.values().any(|&count| count > 1)
    }

    /// Return credentials grouped by their credential_query_id.
    ///
    /// This method returns a list of `CredentialQueryGroup` structs, each containing
    /// a credential_query_id and the list of credentials that match that query.
    /// The groups are ordered according to the DCQL query's credential order.
    pub fn credentials_grouped_by_query(&self) -> Vec<CredentialQueryGroup> {
        // Get the credential query IDs in order from the DCQL query
        let query_ids: Vec<String> = self
            .dcql_query
            .credentials()
            .iter()
            .map(|c| c.id().to_string())
            .collect();

        // Group credentials by their credential_query_id
        let mut grouped: HashMap<String, Vec<Arc<PresentableCredential>>> = HashMap::new();
        for cred in &self.credentials {
            grouped
                .entry(cred.credential_query_id.clone())
                .or_default()
                .push(cred.clone());
        }

        // Return groups in the order defined by the DCQL query
        query_ids
            .into_iter()
            .filter_map(|query_id| {
                grouped
                    .remove(&query_id)
                    .map(|credentials| CredentialQueryGroup {
                        credential_query_id: query_id,
                        credentials,
                    })
            })
            .collect()
    }

    /// Return the list of credential query IDs from the DCQL query.
    ///
    /// This is useful for understanding how many distinct credential types
    /// are being requested by the verifier.
    pub fn credential_query_ids(&self) -> Vec<String> {
        self.dcql_query
            .credentials()
            .iter()
            .map(|c| c.id().to_string())
            .collect()
    }

    /// Return credential requirements that the user must satisfy.
    ///
    /// This method respects the DCQL query's `credential_sets` if present,
    /// grouping credential queries that are alternatives (OR relationship)
    /// into a single requirement.
    ///
    /// If `credential_sets` is absent, each credential query becomes its own requirement.
    ///
    /// The user should select ONE credential per requirement to satisfy the presentation.
    pub fn credential_requirements(&self) -> Vec<CredentialRequirement> {
        // Group credentials by their credential_query_id
        let mut creds_by_query: HashMap<String, Vec<Arc<PresentableCredential>>> = HashMap::new();
        for cred in &self.credentials {
            creds_by_query
                .entry(cred.credential_query_id.clone())
                .or_default()
                .push(cred.clone());
        }

        // Check if credential_sets is present
        if let Some(credential_sets) = self.dcql_query.credential_sets() {
            // credential_sets defines the requirements
            credential_sets
                .iter()
                .map(|cred_set| {
                    // Each option in options() is a Vec<String> of credential query IDs
                    // that can satisfy this requirement (OR between options)
                    let credential_query_ids: Vec<String> = cred_set
                        .options()
                        .iter()
                        .flat_map(|option| option.iter().cloned())
                        .collect();

                    // Collect all credentials that match any of these query IDs
                    let credentials: Vec<Arc<PresentableCredential>> = credential_query_ids
                        .iter()
                        .filter_map(|query_id| creds_by_query.get(query_id))
                        .flatten()
                        .cloned()
                        .collect();

                    // Generate display name from query IDs
                    let display_name = Self::format_display_name(&credential_query_ids);

                    CredentialRequirement {
                        display_name,
                        required: cred_set.is_required(),
                        credential_query_ids,
                        credentials,
                    }
                })
                .collect()
        } else {
            // No credential_sets: each credential query is its own requirement
            self.dcql_query
                .credentials()
                .iter()
                .map(|query| {
                    let query_id = query.id().to_string();
                    let credentials = creds_by_query.get(&query_id).cloned().unwrap_or_default();

                    let display_name = Self::format_display_name(std::slice::from_ref(&query_id));

                    CredentialRequirement {
                        display_name,
                        required: true,
                        credential_query_ids: vec![query_id],
                        credentials,
                    }
                })
                .collect()
        }
    }
}

impl PermissionRequest {
    /// Return the DCQL query associated with the authorization request.
    pub fn dcql_query(&self) -> &DcqlQuery {
        &self.dcql_query
    }

    /// Format credential query IDs into a human-readable display name.
    fn format_display_name(query_ids: &[String]) -> String {
        if query_ids.is_empty() {
            return "Credential".to_string();
        }

        // Join multiple IDs with " or "
        query_ids
            .iter()
            .map(|id| {
                // Convert camelCase/snake_case to Title Case
                let mut result = String::new();
                let mut prev_was_lower = false;
                for (i, c) in id.chars().enumerate() {
                    if c == '_' {
                        result.push(' ');
                        prev_was_lower = false;
                    } else if c.is_uppercase() && prev_was_lower {
                        result.push(' ');
                        result.push(c);
                        prev_was_lower = false;
                    } else if i == 0 {
                        result.push(c.to_ascii_uppercase());
                        prev_was_lower = c.is_lowercase();
                    } else {
                        result.push(c);
                        prev_was_lower = c.is_lowercase();
                    }
                }
                result
            })
            .collect::<Vec<_>>()
            .join(" or ")
    }
}

/// Response options used to provide configurable interface
/// for handling variations in the processing of the verifiable presentation
/// payloads.
#[derive(Debug, Clone, Default, uniffi::Record)]
pub struct ResponseOptions {
    /// Boolean option of whether to use `array_or_value` serialization options
    /// for the verifiable presentation.
    ///
    /// This is provided as an option to force serializing a single verifiable
    /// credential as a member of an array, versus as a singular option, per
    /// implementation.
    pub force_array_serialization: bool,
}

/// This struct is used to represent the response to a permission request.
///
/// Use the [PermissionResponse::new] method to create a new instance of the PermissionResponse.
///
/// The Requested Fields are created by calling the [PermissionRequest::requested_fields] method, and then
/// explicitly setting the permission to true or false, based on the holder's decision.
#[derive(Debug, Clone, uniffi::Object)]
pub struct PermissionResponse {
    // TODO: provide an optional internal mapping of `JsonPointer`s
    // for selective disclosure that are selected as part of the requested fields.
    pub selected_credentials: Vec<Arc<PresentableCredential>>,
    pub dcql_query: DcqlQuery,
    pub authorization_request: AuthorizationRequestObject,
    pub vp_token: VpToken,
    pub options: ResponseOptions,
}

#[uniffi::export]
impl PermissionResponse {
    /// Return the selected credentials for the permission response.
    pub fn selected_credentials(&self) -> Vec<Arc<PresentableCredential>> {
        self.selected_credentials.clone()
    }

    /// Return the signed (prepared) vp token as a JSON-encoded utf-8 string.
    ///
    /// This is helpful for debugging purposes, and is not intended to be used
    /// for submitting the response to the verifier.
    pub fn vp_token(&self) -> Result<String, OID4VPError> {
        serde_json::to_string(&self.vp_token).map_err(|e| OID4VPError::Token(format!("{e:?}")))
    }
}

impl PermissionResponse {
    /// Return the authorization response object.
    ///
    /// The response contains only `vp_token` and optional `state`.
    /// The `vp_token` is a HashMap mapping credential query IDs to arrays of presentations.
    ///
    /// For `direct_post.jwt` response mode, the response is encrypted as a JWE
    /// per OID4VP 1.0 spec §8.3.
    pub fn authorization_response(&self) -> Result<AuthorizationResponse, OID4VPError> {
        let state = self
            .authorization_request
            .state()
            .transpose()
            .map_err(|e| OID4VPError::ResponseSubmission(format!("{e:?}")))?;

        let response_mode = self.authorization_request.response_mode();

        // For DirectPostJwt response mode, build encrypted JWE per OID4VP 1.0 §8.3
        if matches!(response_mode, ResponseMode::DirectPostJwt) {
            return openid4vp::core::jwe::build_encrypted_response(
                &self.authorization_request,
                &self.vp_token,
                state.as_ref(),
            )
            .map_err(|e| OID4VPError::ResponseSubmission(format!("{e:?}")));
        }

        // Default: return unencoded response
        let response = match state {
            Some(s) => UnencodedAuthorizationResponse::with_state(self.vp_token.clone(), s),
            None => UnencodedAuthorizationResponse::new(self.vp_token.clone()),
        };

        Ok(AuthorizationResponse::Unencoded(response))
    }
}
