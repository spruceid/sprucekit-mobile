use openid4vp::core::authorization_request::AuthorizationRequestObject;
use openid4vp::core::presentation_definition::PresentationDefinition;
use openid4vp::core::presentation_submission::{DescriptorMap, PresentationSubmission};
use openid4vp::core::response::parameters::{VpToken, VpTokenItem};
use openid4vp::core::response::{AuthorizationResponse, UnencodedAuthorizationResponse};

use crate::common::*;
use crate::credential::{Credential, CredentialEncodingError, ParsedCredential};

use std::collections::HashMap;
use std::fmt::Debug;
use std::sync::{Arc, RwLock};

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

    /// RwLock error
    #[error("RwLock error.")]
    RwLockError,

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
}

#[derive(uniffi::Error, thiserror::Error, Debug)]
pub enum PermissionResponseError {
    #[error("Failed to parse JsonPath: {0}")]
    JsonPathParse(String),
    #[error(transparent)]
    CredentialEncoding(#[from] CredentialEncodingError),
}

#[derive(Debug, uniffi::Object)]
pub struct RequestedField {
    /// A unique ID for the requested field
    pub(crate) id: Uuid,
    pub(crate) name: Option<String>,
    pub(crate) required: bool,
    pub(crate) retained: bool,
    pub(crate) purpose: Option<String>,
    pub(crate) input_descriptor_id: String,
    // the `raw_field` represents the actual field
    // being selected by the input descriptor JSON path
    // selector.
    pub(crate) raw_fields: Vec<serde_json::Value>,
}

impl<'a> From<openid4vp::core::input_descriptor::RequestedField<'a>> for RequestedField {
    fn from(value: openid4vp::core::input_descriptor::RequestedField) -> Self {
        Self {
            id: value.id,
            name: value.name,
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

impl RequestedField {
    /// Return the unique ID for the request field.
    pub fn id(&self) -> Uuid {
        self.id
    }

    /// Return the input descriptor id the requested field belongs to
    pub fn input_descriptor_id(&self) -> &String {
        &self.input_descriptor_id
    }
}

/// Public methods for the RequestedField struct.
#[uniffi::export]
impl RequestedField {
    /// Return the field name
    pub fn name(&self) -> Option<String> {
        self.name.clone()
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
    definition: PresentationDefinition,
    credentials: Vec<Arc<ParsedCredential>>,
    request: AuthorizationRequestObject,
}

impl PermissionRequest {
    pub fn new(
        definition: PresentationDefinition,
        credentials: Vec<Arc<ParsedCredential>>,
        request: AuthorizationRequestObject,
    ) -> Arc<Self> {
        Arc::new(Self {
            definition,
            credentials,
            request,
        })
    }
}

#[uniffi::export]
impl PermissionRequest {
    /// Return the filtered list of credentials that matched
    /// the presentation definition.
    pub fn credentials(&self) -> Vec<Arc<ParsedCredential>> {
        self.credentials.clone()
    }

    /// Return the requested fields for a given credential.
    ///
    /// NOTE: This will return only the requested fields for a given credential.
    pub fn requested_fields(&self, credential: &Arc<ParsedCredential>) -> Vec<Arc<RequestedField>> {
        credential.requested_fields(&self.definition)
    }

    /// Construct a new permission response for the given credential.
    pub fn create_permission_response(
        &self,
        selected_credentials: Vec<Arc<ParsedCredential>>,
    ) -> Arc<PermissionResponse> {
        Arc::new(PermissionResponse {
            selected_credentials,
            presentation_definition: self.definition.clone(),
            authorization_request: self.request.clone(),
        })
    }

    /// Return the purpose of the presentation request.
    pub fn purpose(&self) -> Option<String> {
        self.definition.purpose().map(ToOwned::to_owned)
    }
}

/// This struct is used to represent the response to a permission request.
///
/// Use the [PermissionResponse::new] method to create a new instance of the PermissionResponse.
///
/// The Requested Fields are created by calling the [PermissionRequest::requested_fields] method, and then
/// explicitly setting the permission to true or false, based on the holder's decision.
#[derive(Debug, Clone, uniffi::Object)]
pub struct PermissionResponse {
    pub selected_credentials: Vec<Arc<ParsedCredential>>,
    pub presentation_definition: PresentationDefinition,
    pub authorization_request: AuthorizationRequestObject,
    // TODO: provide an optional internal mapping of `JsonPointer`s
    // for selective disclosure that are selected as part of the requested fields.
}

impl PermissionResponse {
    // Construct a DescriptorMap for the presentation submission based on the
    // credentials returned from the VDC collection.
    pub fn create_descriptor_map(&self) -> Result<Vec<DescriptorMap>, PermissionResponseError> {
        let is_singular = self.selected_credentials.len() == 1;

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
                let vc_path = if is_singular {
                    "$".to_string()
                } else {
                    format!("$[{idx}]")
                }
                .parse()
                .map_err(|e| PermissionResponseError::JsonPathParse(format!("{e:?}")))?;

                Ok(DescriptorMap::new(
                    descriptor.id.to_string(),
                    cred.format().to_string().as_str(),
                    vc_path,
                ))
            })
            .collect()
    }

    /// Create a VP token based on the selected credentials returned in the permission response.
    pub fn create_vp_token(&self) -> Result<VpToken, PermissionResponseError> {
        let tokens = self
            .selected_credentials
            .iter()
            .map(|cred| cred.as_vp_token())
            .collect::<Result<Vec<VpTokenItem>, CredentialEncodingError>>()?;

        Ok(VpToken(tokens))
    }

    /// Return the authorization response object.
    pub fn authorization_response(&self) -> Result<AuthorizationResponse, PermissionResponseError> {
        Ok(AuthorizationResponse::Unencoded(
            UnencodedAuthorizationResponse {
                vp_token: self.create_vp_token()?,
                presentation_submission: self.create_presentation_submission()?,
            },
        ))
    }

    /// Create a presentation submission based on the selected credentials returned in the permission response.
    pub fn create_presentation_submission(
        &self,
    ) -> Result<PresentationSubmission, PermissionResponseError> {
        Ok(PresentationSubmission::new(
            uuid::Uuid::new_v4(),
            self.presentation_definition.id().clone(),
            self.create_descriptor_map()?,
        ))
    }
}
