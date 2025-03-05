use std::sync::Arc;

use serde::{Deserialize, Serialize};

use super::{Oid4vciError, Oid4vciSession};

#[derive(uniffi::Object, Clone, Debug, Serialize, Deserialize)]
pub struct Oid4vciMetadata {
    issuer: String,
    credential_endpoint: String,
    authorization_servers: Option<Vec<String>>,
    batch_credential_endpoint: Option<String>,
    deferred_credential_endpoint: Option<String>,
    notification_endpoint: Option<String>,
}

// TODO: some or all of these getters/setters can be converted to macros
#[uniffi::export]
impl Oid4vciMetadata {
    pub fn to_json(&self) -> Result<String, Oid4vciError> {
        Ok(serde_json::to_string(self)?)
    }

    pub fn issuer(&self) -> String {
        self.issuer.to_owned()
    }

    pub fn credential_endpoint(&self) -> String {
        self.credential_endpoint.to_owned()
    }

    pub fn authorization_servers(&self) -> Option<Vec<String>> {
        self.authorization_servers.to_owned()
    }

    pub fn batch_credential_endpoint(&self) -> Option<String> {
        self.batch_credential_endpoint.to_owned()
    }

    pub fn deferred_credential_endpoint(&self) -> Option<String> {
        self.deferred_credential_endpoint.to_owned()
    }

    pub fn notification_endpoint(&self) -> Option<String> {
        self.notification_endpoint.to_owned()
    }
}

#[uniffi::export]
pub fn oid4vci_get_metadata(session: Arc<Oid4vciSession>) -> Result<Oid4vciMetadata, Oid4vciError> {
    let issuer = session
        .get_metadata()?
        .credential_issuer()
        .url()
        .to_string();

    let credential_endpoint = session
        .get_metadata()?
        .credential_endpoint()
        .url()
        .to_string();

    let authorization_servers = session
        .get_metadata()?
        .authorization_servers()
        .map(|v| v.iter().cloned().map(|v| v.url().to_string()).collect());

    let batch_credential_endpoint = session
        .get_metadata()?
        .batch_credential_endpoint()
        .map(|v| v.url().to_string());

    let deferred_credential_endpoint = session
        .get_metadata()?
        .deferred_credential_endpoint()
        .map(|v| v.url().to_string());

    let notification_endpoint = session
        .get_metadata()?
        .notification_endpoint()
        .map(|v| v.url().to_string());

    Ok(Oid4vciMetadata {
        issuer,
        credential_endpoint,
        authorization_servers,
        batch_credential_endpoint,
        deferred_credential_endpoint,
        notification_endpoint,
    })
}
