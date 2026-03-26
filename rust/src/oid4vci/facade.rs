use std::sync::Arc;

use super::{
    legacy, AsyncHttpClient, AuthorizationCodeRequired, CredentialOrConfigurationId,
    CredentialResponse, CredentialToken, CredentialTokenState, Oid4vciClient, Oid4vciError, Proofs,
    ResolvedCredentialOffer, TxCodeRequired, WaitingForAuthorizationCode,
};

#[derive(uniffi::Enum, Clone, Copy, Debug, PartialEq, Eq)]
pub enum Oid4vciCompatibilityMode {
    Auto,
    ForceV1,
    ForceLegacy,
}

#[derive(uniffi::Enum, Clone, Copy, Debug, PartialEq, Eq)]
pub enum Oid4vciVersion {
    V1,
    Legacy,
}

#[derive(uniffi::Object)]
pub struct Oid4vciFacadeClient {
    client_id: String,
    compatibility_mode: Oid4vciCompatibilityMode,
    v1_client: Oid4vciClient,
}

#[derive(uniffi::Object)]
pub struct Oid4vciFacadeResolvedOffer {
    preferred_version: Oid4vciVersion,
    inner: Oid4vciFacadeResolvedOfferInner,
}

enum Oid4vciFacadeResolvedOfferInner {
    V1(Arc<ResolvedCredentialOffer>),
    Legacy(legacy::LegacyResolvedOffer),
    Auto {
        current: Arc<ResolvedCredentialOffer>,
        legacy: legacy::LegacyResolvedOffer,
    },
}

#[derive(uniffi::Object)]
pub struct Oid4vciFacadeAuthorizationCodeRequired {
    client_id: String,
    fallback_legacy_offer: Option<legacy::LegacyResolvedOffer>,
    inner: AuthorizationCodeRequired,
}

#[derive(uniffi::Object)]
pub struct Oid4vciFacadeWaitingForAuthorizationCode {
    client_id: String,
    fallback_legacy_offer: Option<legacy::LegacyResolvedOffer>,
    inner: WaitingForAuthorizationCode,
}

#[derive(uniffi::Object)]
pub struct Oid4vciFacadeTxCodeRequired {
    client_id: String,
    fallback_legacy_offer: Option<legacy::LegacyResolvedOffer>,
    inner: TxCodeRequired,
}

#[derive(uniffi::Object)]
pub struct Oid4vciFacadeCredentialToken {
    version: Oid4vciVersion,
    inner: Oid4vciFacadeCredentialTokenInner,
}

enum Oid4vciFacadeCredentialTokenInner {
    V1 {
        client_id: String,
        token: Arc<CredentialToken>,
        fallback_legacy_offer: Option<legacy::LegacyResolvedOffer>,
    },
    Legacy(legacy::LegacyCredentialToken),
}

#[derive(uniffi::Enum)]
pub enum Oid4vciFacadeCredentialTokenState {
    RequiresAuthorizationCode(Arc<Oid4vciFacadeAuthorizationCodeRequired>),
    RequiresTxCode(Arc<Oid4vciFacadeTxCodeRequired>),
    Ready(Arc<Oid4vciFacadeCredentialToken>),
}

#[uniffi::export]
impl Oid4vciFacadeClient {
    #[uniffi::constructor]
    pub fn new(client_id: String, compatibility_mode: Oid4vciCompatibilityMode) -> Self {
        Self {
            v1_client: Oid4vciClient::new(client_id.clone()),
            client_id,
            compatibility_mode,
        }
    }

    pub fn compatibility_mode(&self) -> Oid4vciCompatibilityMode {
        self.compatibility_mode
    }

    pub async fn resolve_offer_url(
        &self,
        http_client: Arc<dyn AsyncHttpClient>,
        credential_offer_url: &str,
    ) -> Result<Oid4vciFacadeResolvedOffer, Oid4vciError> {
        match self.compatibility_mode {
            Oid4vciCompatibilityMode::ForceV1 => self
                .v1_client
                .resolve_offer_url(http_client, credential_offer_url)
                .await
                .map(|offer| Oid4vciFacadeResolvedOffer {
                    preferred_version: Oid4vciVersion::V1,
                    inner: Oid4vciFacadeResolvedOfferInner::V1(Arc::new(offer)),
                }),
            Oid4vciCompatibilityMode::ForceLegacy => {
                legacy::resolve_offer_url(http_client, credential_offer_url, &self.client_id)
                    .await
                    .map(|offer| Oid4vciFacadeResolvedOffer {
                        preferred_version: Oid4vciVersion::Legacy,
                        inner: Oid4vciFacadeResolvedOfferInner::Legacy(offer),
                    })
            }
            Oid4vciCompatibilityMode::Auto => {
                let current = self
                    .v1_client
                    .resolve_offer_url(http_client.clone(), credential_offer_url)
                    .await;
                let legacy =
                    legacy::resolve_offer_url(http_client, credential_offer_url, &self.client_id)
                        .await;

                match (current, legacy) {
                    (Ok(current), Ok(legacy)) => Ok(Oid4vciFacadeResolvedOffer {
                        preferred_version: Oid4vciVersion::V1,
                        inner: Oid4vciFacadeResolvedOfferInner::Auto {
                            current: Arc::new(current),
                            legacy,
                        },
                    }),
                    (Ok(current), Err(_)) => Ok(Oid4vciFacadeResolvedOffer {
                        preferred_version: Oid4vciVersion::V1,
                        inner: Oid4vciFacadeResolvedOfferInner::V1(Arc::new(current)),
                    }),
                    (Err(_), Ok(legacy)) => Ok(Oid4vciFacadeResolvedOffer {
                        preferred_version: Oid4vciVersion::Legacy,
                        inner: Oid4vciFacadeResolvedOfferInner::Legacy(legacy),
                    }),
                    (Err(err), Err(_)) => Err(err),
                }
            }
        }
    }

    pub async fn accept_offer(
        &self,
        http_client: Arc<dyn AsyncHttpClient>,
        credential_offer: Arc<Oid4vciFacadeResolvedOffer>,
    ) -> Result<Oid4vciFacadeCredentialTokenState, Oid4vciError> {
        match &credential_offer.inner {
            Oid4vciFacadeResolvedOfferInner::V1(offer) => wrap_v1_token_state(
                self.client_id.clone(),
                self.v1_client
                    .accept_offer(http_client, offer.clone())
                    .await,
                None,
            ),
            Oid4vciFacadeResolvedOfferInner::Legacy(offer) => {
                let token = legacy::accept_offer(http_client, offer).await?;
                Ok(Oid4vciFacadeCredentialTokenState::Ready(Arc::new(
                    Oid4vciFacadeCredentialToken {
                        version: Oid4vciVersion::Legacy,
                        inner: Oid4vciFacadeCredentialTokenInner::Legacy(token),
                    },
                )))
            }
            Oid4vciFacadeResolvedOfferInner::Auto { current, legacy } => wrap_v1_token_state(
                self.client_id.clone(),
                self.v1_client
                    .accept_offer(http_client, current.clone())
                    .await,
                Some(legacy.clone()),
            ),
        }
    }
}

#[uniffi::export]
impl Oid4vciFacadeResolvedOffer {
    pub fn version(&self) -> Oid4vciVersion {
        self.preferred_version
    }

    pub fn credential_issuer(&self) -> String {
        match &self.inner {
            Oid4vciFacadeResolvedOfferInner::V1(offer) => offer.credential_issuer(),
            Oid4vciFacadeResolvedOfferInner::Legacy(offer) => offer.credential_issuer().to_string(),
            Oid4vciFacadeResolvedOfferInner::Auto { current, .. } => current.credential_issuer(),
        }
    }
}

#[uniffi::export]
impl Oid4vciFacadeAuthorizationCodeRequired {
    pub async fn proceed(
        &self,
        http_client: Arc<dyn AsyncHttpClient>,
        redirect_url: String,
    ) -> Result<Oid4vciFacadeWaitingForAuthorizationCode, Oid4vciError> {
        self.inner
            .proceed(http_client, redirect_url)
            .await
            .map(|waiting| Oid4vciFacadeWaitingForAuthorizationCode {
                client_id: self.client_id.clone(),
                fallback_legacy_offer: self.fallback_legacy_offer.clone(),
                inner: waiting,
            })
    }
}

#[uniffi::export]
impl Oid4vciFacadeWaitingForAuthorizationCode {
    pub fn redirect_url(&self) -> String {
        self.inner.redirect_url()
    }

    pub async fn proceed(
        &self,
        http_client: Arc<dyn AsyncHttpClient>,
        authorization_code: String,
    ) -> Result<Oid4vciFacadeCredentialToken, Oid4vciError> {
        self.inner
            .proceed(http_client, authorization_code)
            .await
            .map(|token| Oid4vciFacadeCredentialToken {
                version: Oid4vciVersion::V1,
                inner: Oid4vciFacadeCredentialTokenInner::V1 {
                    client_id: self.client_id.clone(),
                    token: Arc::new(token),
                    fallback_legacy_offer: self.fallback_legacy_offer.clone(),
                },
            })
    }
}

#[uniffi::export]
impl Oid4vciFacadeTxCodeRequired {
    pub async fn proceed(
        self: Arc<Self>,
        http_client: Arc<dyn AsyncHttpClient>,
        tx_code: String,
    ) -> Result<Oid4vciFacadeCredentialToken, Oid4vciError> {
        let inner = Arc::into_inner(self).ok_or(Oid4vciError::AlreadyProceeded)?;
        inner
            .inner
            .proceed(http_client, &tx_code)
            .await
            .map(|token| Oid4vciFacadeCredentialToken {
                version: Oid4vciVersion::V1,
                inner: Oid4vciFacadeCredentialTokenInner::V1 {
                    client_id: inner.client_id,
                    token: Arc::new(token),
                    fallback_legacy_offer: inner.fallback_legacy_offer,
                },
            })
    }
}

#[uniffi::export]
impl Oid4vciFacadeCredentialToken {
    pub fn version(&self) -> Oid4vciVersion {
        self.version
    }

    pub fn default_credential_id(&self) -> Result<CredentialOrConfigurationId, Oid4vciError> {
        match &self.inner {
            Oid4vciFacadeCredentialTokenInner::V1 { token, .. } => token.default_credential_id(),
            Oid4vciFacadeCredentialTokenInner::Legacy(token) => token.default_credential_id(),
        }
    }

    pub async fn get_nonce(
        &self,
        http_client: Arc<dyn AsyncHttpClient>,
    ) -> Result<Option<String>, Oid4vciError> {
        match &self.inner {
            Oid4vciFacadeCredentialTokenInner::V1 { token, .. } => {
                token.get_nonce(http_client).await
            }
            Oid4vciFacadeCredentialTokenInner::Legacy(token) => Ok(token.nonce()),
        }
    }

    pub async fn exchange_credential(
        &self,
        http_client: Arc<dyn AsyncHttpClient>,
        credential: CredentialOrConfigurationId,
        proofs: Option<Proofs>,
    ) -> Result<CredentialResponse, Oid4vciError> {
        match &self.inner {
            Oid4vciFacadeCredentialTokenInner::Legacy(token) => {
                token
                    .exchange_credential(http_client, credential, proofs)
                    .await
            }
            Oid4vciFacadeCredentialTokenInner::V1 {
                client_id,
                token,
                fallback_legacy_offer,
            } => {
                let v1_result = Oid4vciClient::new(client_id.clone())
                    .exchange_credential(
                        http_client.clone(),
                        token,
                        credential.clone(),
                        proofs.clone(),
                    )
                    .await;

                match v1_result {
                    Ok(response) => Ok(response),
                    Err(error)
                        if fallback_legacy_offer.is_some()
                            && should_retry_legacy_exchange(&error) =>
                    {
                        let legacy_token = legacy::accept_offer(
                            http_client.clone(),
                            fallback_legacy_offer.as_ref().unwrap(),
                        )
                        .await?;
                        legacy_token
                            .exchange_credential(http_client, credential, proofs)
                            .await
                    }
                    Err(error) => Err(error),
                }
            }
        }
    }
}

fn wrap_v1_token_state(
    client_id: String,
    result: Result<CredentialTokenState, Oid4vciError>,
    fallback_legacy_offer: Option<legacy::LegacyResolvedOffer>,
) -> Result<Oid4vciFacadeCredentialTokenState, Oid4vciError> {
    result.map(|state| match state {
        CredentialTokenState::RequiresAuthorizationCode(state) => {
            Oid4vciFacadeCredentialTokenState::RequiresAuthorizationCode(Arc::new(
                Oid4vciFacadeAuthorizationCodeRequired {
                    client_id: client_id.clone(),
                    fallback_legacy_offer,
                    inner: Arc::into_inner(state)
                        .expect("newly created authorization code state should be uniquely owned"),
                },
            ))
        }
        CredentialTokenState::RequiresTxCode(state) => {
            Oid4vciFacadeCredentialTokenState::RequiresTxCode(Arc::new(
                Oid4vciFacadeTxCodeRequired {
                    client_id: client_id.clone(),
                    fallback_legacy_offer,
                    inner: Arc::into_inner(state)
                        .expect("newly created tx code state should be uniquely owned"),
                },
            ))
        }
        CredentialTokenState::Ready(token) => {
            Oid4vciFacadeCredentialTokenState::Ready(Arc::new(Oid4vciFacadeCredentialToken {
                version: Oid4vciVersion::V1,
                inner: Oid4vciFacadeCredentialTokenInner::V1 {
                    client_id,
                    token,
                    fallback_legacy_offer,
                },
            }))
        }
    })
}

fn should_retry_legacy_exchange(error: &Oid4vciError) -> bool {
    match error {
        Oid4vciError::PresentationRequired { .. } => false,
        Oid4vciError::Client(error) => error.to_string().contains("status code 400"),
        _ => false,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn retries_legacy_for_400_client_errors() {
        let error = Oid4vciError::client_other(
            "server at `https://issuer.example/credential` responded with status code 400 Bad Request",
        );

        assert!(should_retry_legacy_exchange(&error));
    }

    #[test]
    fn ignores_non_400_client_errors() {
        let error = Oid4vciError::client_other(
            "server at `https://issuer.example/credential` responded with status code 401 Unauthorized",
        );

        assert!(!should_retry_legacy_exchange(&error));
    }

    #[test]
    fn does_not_retry_presentation_required_errors() {
        let error = Oid4vciError::PresentationRequired {
            authorization_request: "{\"response_type\":\"vp_token\"}".to_string(),
        };

        assert!(!should_retry_legacy_exchange(&error));
    }
}
