use openid4vp::core::authorization_request::AuthorizationRequestObject;
use openid4vp::core::presentation_definition::PresentationDefinition;

use crate::common::*;
use crate::credential::{Credential, ParsedCredential};

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

#[derive(Debug, uniffi::Object)]
pub struct RequestedField {
    /// A unique ID for the requested field
    pub(crate) id: Uuid,
    pub(crate) name: String,
    pub(crate) required: bool,
    pub(crate) retained: bool,
    pub(crate) purpose: Option<String>,
    pub(crate) constraint_field_id: Option<String>,
    // the `raw_field` represents the actual field
    // being selected by the input descriptor JSON path
    // selector.
    pub(crate) raw_fields: Option<serde_json::Value>,
}

impl From<openid4vp::core::input_descriptor::RequestedField> for RequestedField {
    fn from(value: openid4vp::core::input_descriptor::RequestedField) -> Self {
        Self {
            id: value.id,
            name: value.name,
            required: value.required,
            retained: value.retained,
            purpose: value.purpose,
            constraint_field_id: value.constraint_field_id,
            raw_fields: value.raw_fields,
        }
    }
}

impl RequestedField {
    /// Construct a new requested field given the required parameters. This method is exposed as
    /// public, however, it is likely that the `from_definition` method will be used to construct
    /// requested fields from a presentation definition.
    ///
    /// See [RequestedField::from_definition] to return a vector of requested fields
    /// according to a presentation definition.
    pub fn new(
        name: String,
        required: bool,
        retained: bool,
        purpose: Option<String>,
        constraint_field_id: Option<String>,
        raw_fields: Option<serde_json::Value>,
    ) -> Arc<Self> {
        Arc::new(Self {
            id: Uuid::new_v4(),
            name,
            required,
            retained,
            purpose,
            constraint_field_id,
            raw_fields,
        })
    }

    /// Return the unique ID for the request field.
    pub fn id(&self) -> Uuid {
        self.id
    }

    /// Return the constraint field id the requested field belongs to
    pub fn constraint_field_id(&self) -> Option<String> {
        self.constraint_field_id.clone()
    }

    /// Return the stringified JSON raw fields.
    pub fn raw_fields(&self) -> Option<String> {
        self.raw_fields
            .as_ref()
            .and_then(|value| serde_json::to_string(value).ok())
    }
}

/// Public methods for the RequestedField struct.
#[uniffi::export]
impl RequestedField {
    /// Return the field name
    pub fn name(&self) -> String {
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
        selected_credential: Arc<ParsedCredential>,
    ) -> Arc<PermissionResponse> {
        Arc::new(PermissionResponse {
            selected_credential,
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
    pub selected_credential: Arc<ParsedCredential>,
    pub presentation_definition: PresentationDefinition,
    pub authorization_request: AuthorizationRequestObject,
    // TODO: provide an optional internal mapping of `JsonPointer`s
    // for selective disclosure that are selected as part of the requested fields.
}
