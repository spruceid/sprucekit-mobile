use futures::lock::Mutex;
use oid4vci::{credential_offer::CredentialOfferGrants, profiles::metadata, token};

use crate::credential::CredentialFormat;

use super::Oid4vciError;

#[derive(uniffi::Object)]
pub struct Oid4vciSession {
    client: Client,
    metadata: Option<CredentialIssuerMetadata>,
    token_response: Mutex<Option<TokenResponse>>,
    credential_request: Mutex<Option<CredentialRequest>>,
    grants: Mutex<Option<Grants>>,
}

// TODO: some or all of these getters/setters can be converted to macros
impl Oid4vciSession {
    pub fn new(client: Client) -> Self {
        Self {
            client,
            metadata: None,
            token_response: None.into(),
            credential_request: None.into(),
            grants: None.into(),
        }
    }

    pub fn get_client(&self) -> &oid4vci::profiles::client::Client {
        &self.client.0
    }

    pub fn get_metadata(&self) -> Result<&metadata::CredentialIssuerMetadata, Oid4vciError> {
        self.metadata
            .as_ref()
            .map(|w| &w.0)
            .ok_or(Oid4vciError::InvalidSession("metadata unset".into()))
    }

    pub fn set_metadata(&mut self, metadata: CredentialIssuerMetadata) {
        self.metadata = Some(metadata);
    }

    pub fn get_token_response(&self) -> Result<token::Response, Oid4vciError> {
        self.token_response
            .try_lock()
            .ok_or(Oid4vciError::LockError("token_response".into()))?
            .as_ref()
            .map(|w| w.0.clone())
            .ok_or(Oid4vciError::InvalidSession("token_response unset".into()))
    }

    pub fn set_token_response(&self, token_response: TokenResponse) -> Result<(), Oid4vciError> {
        *(self
            .token_response
            .try_lock()
            .ok_or(Oid4vciError::LockError("token_response".into()))?) = Some(token_response);

        Ok(())
    }

    pub fn get_credential_requests(
        &self,
    ) -> Result<Vec<oid4vci::profiles::ProfilesCredentialRequest>, Oid4vciError> {
        self.credential_request
            .try_lock()
            .ok_or(Oid4vciError::LockError("credential_request".into()))?
            .as_ref()
            .map(|w| w.0.clone())
            .ok_or(Oid4vciError::InvalidSession(
                "credential_request unset".into(),
            ))
    }

    pub fn set_credential_request(
        &self,
        credential_request: oid4vci::profiles::ProfilesCredentialRequest,
    ) -> Result<(), Oid4vciError> {
        *(self
            .credential_request
            .try_lock()
            .ok_or(Oid4vciError::LockError("credential_request".into()))?) =
            Some(vec![credential_request].into());

        Ok(())
    }

    pub fn set_credential_requests(
        &self,
        credential_requests: Vec<oid4vci::profiles::ProfilesCredentialRequest>,
    ) -> Result<(), Oid4vciError> {
        *(self
            .credential_request
            .try_lock()
            .ok_or(Oid4vciError::LockError("credential_request".into()))?) =
            Some(credential_requests.into());

        Ok(())
    }

    pub fn get_grants(&self) -> Result<CredentialOfferGrants, Oid4vciError> {
        self.grants
            .try_lock()
            .ok_or(Oid4vciError::LockError("grants".into()))?
            .as_ref()
            .map(|w| w.0.clone())
            .ok_or(Oid4vciError::InvalidSession("grants unset".into()))
    }

    pub fn set_grants(&self, grants: Option<CredentialOfferGrants>) -> Result<(), Oid4vciError> {
        *(self
            .grants
            .try_lock()
            .ok_or(Oid4vciError::LockError("grants".into()))?) = grants.map(|g| g.into());

        Ok(())
    }
}

macro_rules! wrap_external_type {
    ($wrap_me:ty, $as:ident) => {
        #[derive(uniffi::Object)]
        pub struct $as($wrap_me);
        impl From<$wrap_me> for $as {
            fn from(value: $wrap_me) -> $as {
                $as(value)
            }
        }
    };
}

wrap_external_type!(oid4vci::profiles::client::Client, Client);
wrap_external_type!(metadata::CredentialIssuerMetadata, CredentialIssuerMetadata);
wrap_external_type!(
    Vec<oid4vci::profiles::ProfilesCredentialRequest>,
    CredentialRequest
);
wrap_external_type!(token::Response, TokenResponse);
wrap_external_type!(CredentialOfferGrants, Grants);

impl From<oid4vci::profiles::ProfilesCredentialRequest> for CredentialRequest {
    fn from(value: oid4vci::profiles::ProfilesCredentialRequest) -> Self {
        CredentialRequest(vec![value])
    }
}

#[derive(Debug, uniffi::Record)]
pub struct CredentialResponse {
    pub format: CredentialFormat,
    pub payload: Vec<u8>,
}
