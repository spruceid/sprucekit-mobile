use super::error::OID4VPError;
use super::presentation::{PresentationError, PresentationOptions, PresentationSigner};
use crate::credential::{Credential, ParsedCredential, PresentableCredential};

use std::collections::HashMap;
use std::fmt::Debug;
use std::sync::{Arc, RwLock};

use base64::{engine::general_purpose::URL_SAFE, Engine as _};
use itertools::Itertools;
use openid4vp::core::authorization_request::AuthorizationRequestObject;
use openid4vp::core::presentation_definition::PresentationDefinition;
use openid4vp::core::presentation_submission::{DescriptorMap, PresentationSubmission};
use openid4vp::core::response::parameters::VpToken;
use openid4vp::core::response::{AuthorizationResponse, UnencodedAuthorizationResponse};
use uuid::Uuid;

/// Type alias for mapping input descriptor ids to matching credentials
/// stored in the VDC collection. This mapping is used to provide a
/// shared state between native code and the rust code, to select
/// the appropriate credentials for a given input descriptor.
pub type InputDescriptorCredentialMap = HashMap<String, Vec<Credential>>;

/// A clonable and thread-safe reference to the input descriptor credential map.
pub type InputDescriptorCredentialMapRef = Arc<RwLock<InputDescriptorCredentialMap>>;

/// A clonable and thread-safe reference to the selected credential map.
pub type SelectedCredentialMapRef = Arc<RwLock<HashMap<String, Vec<Uuid>>>>;

#[derive(uniffi::Error, thiserror::Error, Debug)]
pub enum PermissionRequestError {
    /// Permission denied for requested presentation.
    #[error("Permission denied for requested presentation.")]
    PermissionDenied,

    /// No credentials found matching the presentation definition.
    #[error("No credentials found matching the presentation definition.")]
    NoCredentialsFound,

    /// Credential not found for input descriptor id.
    #[error("Credential not found for input descriptor id: {0}")]
    CredentialNotFound(String),

    /// Input descriptor not found for input descriptor id.
    #[error("Input descriptor not found for input descriptor id: {0}")]
    InputDescriptorNotFound(String),

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

    #[error("limit_disclosure required")]
    LimitDisclosure,

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
    pub(crate) input_descriptor_id: String,
    // the `raw_field` represents the actual field
    // being selected by the input descriptor JSON path
    // selector.
    pub(crate) raw_fields: Vec<serde_json::Value>,
}

impl From<openid4vp::core::input_descriptor::RequestedField<'_>> for RequestedField {
    fn from(value: openid4vp::core::input_descriptor::RequestedField) -> Self {
        Self {
            id: value.id,
            name: value.name,
            path: value.path.into_iter().map(|v| URL_SAFE.encode(v)).join(","),
            required: value.required,
            retained: value.retained,
            purpose: value.purpose,
            input_descriptor_id: value.input_descriptor_id,
            raw_fields: value
                .raw_fields
                .into_iter()
                .map(ToOwned::to_owned)
                .collect(),
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

    /// Return the input descriptor id the requested field belongs to
    pub fn input_descriptor_id(&self) -> String {
        self.input_descriptor_id.clone()
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

#[derive(Debug, Clone, uniffi::Object)]
pub struct PermissionRequest {
    pub(crate) definition: PresentationDefinition,
    pub(crate) credentials: Vec<Arc<PresentableCredential>>,
    pub(crate) request: AuthorizationRequestObject,
    pub(crate) signer: Arc<Box<dyn PresentationSigner>>,
    pub(crate) context_map: Option<HashMap<String, String>>,
}

impl PermissionRequest {
    pub fn new(
        definition: PresentationDefinition,
        credentials: Vec<Arc<PresentableCredential>>,
        request: AuthorizationRequestObject,
        signer: Arc<Box<dyn PresentationSigner>>,
        context_map: Option<HashMap<String, String>>,
    ) -> Arc<Self> {
        Arc::new(Self {
            definition,
            credentials,
            request,
            signer,
            context_map,
        })
    }
}

#[uniffi::export(async_runtime = "tokio")]
impl PermissionRequest {
    /// Return the filtered list of credentials that matched
    /// the presentation definition.
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
        .requested_fields(&self.definition)
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
    ///
    /// NOTE: `should_strip_quotes` is a non-normative setting to determine
    /// the behavior of removing extra quotations around a JSON
    /// string encoded vp_token, e.g. "'[{ @context: [...] }]'" -> '[{ @context: [...] }]'
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
                self.definition.credential_types_hint().join(", "),
            )
            .into());
        }

        // Ensure that there are selected fields for all credentials.
        if selected_fields.len() != selected_credentials.len() {
            return Err(PermissionRequestError::InvalidSelectedCredential(
                "Selected credentials length must match selected fields length".to_string(),
                self.definition.credential_types_hint().join(", "),
            )
            .into());
        }

        let selected_credentials = selected_credentials
            .iter()
            .zip(selected_fields)
            .map(|(sc, sf)| {
                // If limit disclosure is `required` drop connection
                if sc.limit_disclosure {
                    return Err(PermissionRequestError::LimitDisclosure);
                }
                Ok(PresentableCredential {
                    inner: sc.inner.clone(),
                    limit_disclosure: sc.limit_disclosure,
                    selected_fields: Some(sf),
                }
                .into())
            })
            .collect::<Result<Vec<_>, _>>()?;

        // Set options for constructing a verifiable presentation.
        let options = PresentationOptions {
            request: &self.request,
            signer: self.signer.clone(),
            context_map: self.context_map.clone(),
            response_options: &response_options,
        };

        let token_items = futures::future::try_join_all(
            selected_credentials
                .iter()
                .map(|cred: &Arc<_>| cred.as_vp_token(&options)),
        )
        .await?;

        let vp_token = VpToken(token_items);

        Ok(Arc::new(PermissionResponse {
            selected_credentials,
            presentation_definition: self.definition.clone(),
            authorization_request: self.request.clone(),
            vp_token,
            options: response_options,
        }))
    }

    /// Return the purpose of the presentation request.
    pub fn purpose(&self) -> Option<String> {
        self.definition.purpose().map(ToOwned::to_owned)
    }
}

/// Non-normative response options used to provide configurable interface
/// for handling variations in the processing of the verifiable presentation
/// payloads in various external verifiers.
#[derive(Debug, Clone, Default, uniffi::Record)]
pub struct ResponseOptions {
    /// This is an non-normative setting to determine
    /// the behavior of removing extra quotations around a JSON
    /// string encoded vp_token, e.g. "'[{ @context: [...] }]'" -> '[{ @context: [...] }]'
    pub should_strip_quotes: bool,
    /// Boolean option of whether to use `array_or_value` serialization options
    /// for the verifiable presentation.
    ///
    /// This is provided as an option to force serializing a single verifiable
    /// credential as a member of an array, versus as a singular option, per
    /// implementation.
    ///
    /// NOTE: This may be removed in the future as the oid4vp specification becomes
    /// more solidified around `vp_token` presentation.
    ///
    /// These options are provided as configurable parameters to maintain backwards
    /// compatibility with verifier implementation versions.
    pub force_array_serialization: bool,
    /// Remove the `$.vp` path prefix for the descriptor map for the verifiable credential.
    /// This is non-normative option, e.g. `$.vp` -> `$`
    pub remove_vp_path_prefix: bool,
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
    pub presentation_definition: PresentationDefinition,
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
    // Construct a DescriptorMap for the presentation submission based on the
    // credentials returned from the VDC collection.
    pub fn create_descriptor_map(&self) -> Result<Vec<DescriptorMap>, OID4VPError> {
        self.presentation_definition
            .input_descriptors()
            // TODO: It is possible for an input descriptor to have multiple credentials,
            // in which case, it may be expected that the descriptor map will have a nested
            // path. When creating a descriptor map, it may be better to use a mapping of input descriptor
            // id to a list of credentials, whereby each descriptor id is mapped to a descriptor map,
            // with a nested path for each credential it maps onto.
            //
            // Currently, each selected credential is provided its own descriptor map associated with
            // the corresponding input descriptor. It is assumed that each input descriptor corresponds
            // to a single verifiable credential.
            .iter()
            .zip(self.selected_credentials.iter())
            .enumerate()
            .map(|(idx, (descriptor, cred))| {
                // NOTE: If the iterator only includes a single credential, then
                // do not provide an index for the descriptor map.
                //
                // This will inform the descriptor map to use the credential as a
                // root path, instead of a indexed path.
                if idx == 0 && idx == self.presentation_definition.input_descriptors().len() - 1 {
                    return cred.create_descriptor_map(
                        self.options.clone(),
                        descriptor.id.clone(),
                        None,
                    );
                }

                cred.create_descriptor_map(self.options.clone(), descriptor.id.clone(), Some(idx))
            })
            .collect()
    }

    /// Return the authorization response object.
    pub fn authorization_response(&self) -> Result<AuthorizationResponse, OID4VPError> {
        Ok(AuthorizationResponse::Unencoded(
            UnencodedAuthorizationResponse {
                presentation_submission: self.create_presentation_submission()?,
                vp_token: self.vp_token.clone(),
                state: self
                    .authorization_request
                    .state()
                    .transpose()
                    .map_err(|e| OID4VPError::ResponseSubmission(format!("{e:?}")))?,
                should_strip_quotes: self.options.should_strip_quotes,
            },
        ))
    }

    /// Create a presentation submission based on the selected credentials returned in the permission response.
    fn create_presentation_submission(&self) -> Result<PresentationSubmission, OID4VPError> {
        Ok(PresentationSubmission::new(
            Uuid::new_v4(),
            self.presentation_definition.id().clone(),
            self.create_descriptor_map()?,
        ))
    }
}
