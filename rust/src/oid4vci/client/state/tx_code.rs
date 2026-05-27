use std::sync::Arc;

use tokio::sync::RwLock;

use crate::oid4vci::{AsyncHttpClient, CredentialToken, Oid4vciError, Oid4vciHttpClient};

#[derive(uniffi::Object)]
pub struct TxCodeRequired {
    inner: RwLock<Option<oid4vci::client::TxCodeRequired>>,
}

#[uniffi::export]
impl TxCodeRequired {
    pub async fn proceed(
        &self,
        http_client: Arc<dyn AsyncHttpClient>,
        tx_code: String,
    ) -> Result<Arc<CredentialToken>, Oid4vciError> {
        let state = self
            .inner
            .write()
            .await
            .take()
            .ok_or(Oid4vciError::AlreadyProceeded)?;

        state
            .proceed_async(&Oid4vciHttpClient(http_client), &tx_code)
            .await
            .map(|t| Arc::new(t.into()))
            .map_err(Into::into)
    }
}

impl From<oid4vci::client::TxCodeRequired> for TxCodeRequired {
    fn from(value: oid4vci::client::TxCodeRequired) -> Self {
        Self {
            inner: RwLock::new(Some(value)),
        }
    }
}
