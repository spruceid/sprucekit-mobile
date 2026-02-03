use std::sync::Arc;

use oid4vci::oauth2::{AuthorizationCode, RedirectUrl};
use tokio::sync::RwLock;

use crate::oid4vci::{AsyncHttpClient, CredentialToken, Oid4vciError, Oid4vciHttpClient};

#[derive(uniffi::Object)]
pub struct AuthorizationCodeRequired {
    inner: RwLock<Option<oid4vci::client::AuthorizationCodeRequired>>,
}

#[uniffi::export]
impl AuthorizationCodeRequired {
    pub async fn proceed(
        &self,
        http_client: Arc<dyn AsyncHttpClient>,
        redirect_url: String,
    ) -> Result<WaitingForAuthorizationCode, Oid4vciError> {
        let state = self
            .inner
            .write()
            .await
            .take()
            .ok_or(Oid4vciError::AlreadyProceeded)?;

        state
            .proceed_async(
                &Oid4vciHttpClient(http_client),
                RedirectUrl::new(redirect_url)?,
            )
            .await
            .map(Into::into)
            .map_err(Into::into)
    }
}

impl From<oid4vci::client::AuthorizationCodeRequired> for AuthorizationCodeRequired {
    fn from(value: oid4vci::client::AuthorizationCodeRequired) -> Self {
        Self {
            inner: RwLock::new(Some(value)),
        }
    }
}

#[derive(uniffi::Object)]
pub struct WaitingForAuthorizationCode {
    redirect_url: String,
    inner: RwLock<Option<oid4vci::client::WaitingForAuthorizationCode>>,
}

#[uniffi::export]
impl WaitingForAuthorizationCode {
    /// URL where the user agent needs to be redirected.
    pub fn redirect_url(&self) -> String {
        self.redirect_url.clone()
    }

    /// Proceed with the credential issuance by providing an authorization code.
    pub async fn proceed(
        &self,
        http_client: Arc<dyn AsyncHttpClient>,
        authorization_code: String,
    ) -> Result<CredentialToken, Oid4vciError> {
        let state = self
            .inner
            .write()
            .await
            .take()
            .ok_or(Oid4vciError::AlreadyProceeded)?;

        state
            .proceed_async(
                &Oid4vciHttpClient(http_client),
                AuthorizationCode::new(authorization_code),
            )
            .await
            .map(Into::into)
            .map_err(Into::into)
    }
}

impl From<oid4vci::client::WaitingForAuthorizationCode> for WaitingForAuthorizationCode {
    fn from(value: oid4vci::client::WaitingForAuthorizationCode) -> Self {
        Self {
            redirect_url: value.redirect_url().as_str().to_owned(),
            inner: RwLock::new(Some(value)),
        }
    }
}
