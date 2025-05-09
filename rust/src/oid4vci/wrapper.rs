use std::{
    collections::HashMap,
    sync::{Arc, Mutex},
};

use super::{
    oid4vci_exchange_credential, oid4vci_exchange_token, oid4vci_get_metadata, oid4vci_initiate,
    oid4vci_initiate_with_offer, AsyncHttpClient, CredentialResponse, IHttpClient, Oid4vciError,
    Oid4vciExchangeOptions, Oid4vciMetadata, Oid4vciSession, SyncHttpClient,
};

#[derive(uniffi::Object)]
pub struct Oid4vci {
    http_client: Arc<IHttpClient>,
    session: Mutex<Option<Arc<Oid4vciSession>>>,
    context_map: Mutex<Option<HashMap<String, String>>>,
}

impl Oid4vci {
    fn context_map(&self) -> Result<Option<HashMap<String, String>>, Oid4vciError> {
        let context_map = self
            .context_map
            .lock()
            .map_err(|_| Oid4vciError::LockError("context_map".into()))?;

        Ok(context_map.clone())
    }

    fn session(&self) -> Result<Arc<Oid4vciSession>, Oid4vciError> {
        let session = self
            .session
            .lock()
            .map_err(|_| Oid4vciError::LockError("session".into()))?;

        let session: Arc<Oid4vciSession> = match session.as_ref() {
            Some(session) => session.clone(),
            None => return Err(Oid4vciError::InvalidSession("session unset".to_string())),
        };

        Ok(session)
    }

    fn set_session(&self, value: Oid4vciSession) -> Result<(), Oid4vciError> {
        let mut session = self
            .session
            .lock()
            .map_err(|_| Oid4vciError::LockError("session".into()))?;

        *session = match session.take() {
            Some(_) => {
                return Err(Oid4vciError::InvalidSession(
                    "session already set".to_string(),
                ))
            }
            None => Some(value.into()),
        };

        Ok(())
    }
}

#[uniffi::export(async_runtime = "tokio")]
impl Oid4vci {
    #[uniffi::constructor(name = "new")]
    fn new_default() -> Arc<Self> {
        Self::new_async()
    }

    #[uniffi::constructor(name = "new_with_default_sync_client")]
    fn new_sync() -> Arc<Self> {
        todo!("add reqwest default sync client")
    }

    #[uniffi::constructor(name = "new_with_default_async_client")]
    fn new_async() -> Arc<Self> {
        todo!("add reqwest default async client")
    }

    #[uniffi::constructor(name = "new_with_sync_client")]
    fn with_sync_client(client: Arc<dyn SyncHttpClient>) -> Arc<Self> {
        let http_client = Arc::new(client.into());
        Self {
            session: Mutex::new(None),
            context_map: Mutex::new(None),
            http_client,
        }
        .into()
    }

    #[uniffi::constructor(name = "new_with_async_client")]
    pub(crate) fn with_async_client(client: Arc<dyn AsyncHttpClient>) -> Arc<Self> {
        let http_client = Arc::new(client.into());
        Self {
            session: Mutex::new(None),
            context_map: Mutex::new(None),
            http_client,
        }
        .into()
    }

    pub fn set_context_map(&self, values: HashMap<String, String>) -> Result<(), Oid4vciError> {
        let mut context_map = self
            .context_map
            .lock()
            .map_err(|_| Oid4vciError::LockError("context_map".into()))?;

        *context_map = Some(values);

        Ok(())
    }

    fn clear_context_map(&self) -> Result<(), Oid4vciError> {
        let mut context_map = self
            .context_map
            .lock()
            .map_err(|_| Oid4vciError::LockError("context_map".into()))?;

        *context_map = None;

        Ok(())
    }

    pub fn get_metadata(&self) -> Result<Oid4vciMetadata, Oid4vciError> {
        oid4vci_get_metadata(self.session()?)
    }

    pub async fn initiate_with_offer(
        &self,
        credential_offer: String,
        client_id: String,
        redirect_url: String,
    ) -> Result<(), Oid4vciError> {
        let session = oid4vci_initiate_with_offer(
            credential_offer,
            client_id,
            redirect_url,
            self.http_client.clone(),
        )
        .await?;
        self.set_session(session)
    }

    async fn initiate(
        &self,
        base_url: String,
        client_id: String,
        redirect_url: String,
    ) -> Result<(), Oid4vciError> {
        let session =
            oid4vci_initiate(base_url, client_id, redirect_url, self.http_client.clone()).await?;
        self.set_session(session)
    }

    pub async fn exchange_token(&self) -> Result<Option<String>, Oid4vciError> {
        oid4vci_exchange_token(self.session()?, self.http_client.clone()).await
    }

    pub async fn exchange_credential(
        &self,
        proofs_of_possession: Vec<String>,
        options: Oid4vciExchangeOptions,
    ) -> Result<Vec<CredentialResponse>, Oid4vciError> {
        oid4vci_exchange_credential(
            self.session()?,
            proofs_of_possession,
            options,
            self.context_map()?,
            self.http_client.clone(),
        )
        .await
    }
}
