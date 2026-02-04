use std::sync::Arc;

use crate::oid4vci::{
    AsyncHttpClient, CredentialOrConfigurationId, Oid4vciError, Oid4vciHttpClient,
};

#[derive(uniffi::Object)]
pub struct CredentialToken(pub(crate) oid4vci::client::CredentialToken);

#[uniffi::export]
impl CredentialToken {
    pub fn default_credential_id(&self) -> Result<CredentialOrConfigurationId, Oid4vciError> {
        self.0
            .default_credential_id()
            .map(Into::into)
            .map_err(Into::into)
    }

    pub async fn get_nonce(
        &self,
        http_client: Arc<dyn AsyncHttpClient>,
    ) -> Result<Option<String>, Oid4vciError> {
        self.0
            .get_nonce_async(&Oid4vciHttpClient(http_client))
            .await
            .map_err(Into::into)
    }
}

impl From<oid4vci::client::CredentialToken> for CredentialToken {
    fn from(value: oid4vci::client::CredentialToken) -> Self {
        Self(value)
    }
}
