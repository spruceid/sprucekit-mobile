use std::sync::Arc;

use oid4vci::{client::Oid4vciClient as _, oauth2::ClientId, CredentialOffer};

use crate::oid4vci::Oid4vciHttpClient;

use super::{
    AsyncHttpClient, CredentialOrConfigurationId, CredentialResponse, Oid4vciError, Proofs,
};

mod offer;
mod state;
mod token;

pub use offer::*;
pub use state::*;
pub use token::*;

/// OID4VCI client.
#[derive(uniffi::Object)]
pub struct Oid4vciClient(oid4vci::client::SimpleOid4vciClient);

#[uniffi::export]
impl Oid4vciClient {
    #[uniffi::constructor]
    pub fn new(client_id: String) -> Self {
        Self(oid4vci::client::SimpleOid4vciClient::new(ClientId::new(
            client_id,
        )))
    }

    /// Process the given credential offer.
    pub async fn resolve_offer_url(
        &self,
        http_client: Arc<dyn AsyncHttpClient>,
        credential_offer_url: &str,
    ) -> Result<ResolvedCredentialOffer, Oid4vciError> {
        let credential_offer: CredentialOffer = credential_offer_url.parse()?;

        self.0
            .resolve_offer_async(&Oid4vciHttpClient(http_client), credential_offer)
            .await
            .map(Into::into)
            .map_err(Into::into)
    }

    /// Process the given credential offer.
    pub async fn accept_offer(
        &self,
        http_client: Arc<dyn AsyncHttpClient>,
        credential_offer: Arc<ResolvedCredentialOffer>,
    ) -> Result<CredentialTokenState, Oid4vciError> {
        self.0
            .accept_offer_async(&Oid4vciHttpClient(http_client), credential_offer.0.clone())
            .await
            .map(Into::into)
            .map_err(Into::into)
    }

    /// Exchange a Credential Token against one or more Credentials.
    pub async fn exchange_credential(
        &self,
        http_client: Arc<dyn AsyncHttpClient>,
        token: &CredentialToken,
        credential: CredentialOrConfigurationId,
        proofs: Option<Proofs>,
    ) -> Result<CredentialResponse, Oid4vciError> {
        let credential = credential.into();
        let format = token
            .0
            .credential_format(&credential)
            .ok_or(Oid4vciError::UndefinedCredential)?;

        CredentialResponse::new(
            &format,
            self.0
                .exchange_credential_async(
                    &Oid4vciHttpClient(http_client),
                    &token.0,
                    credential,
                    proofs.map(Into::into),
                )
                .await?,
        )
    }
}
