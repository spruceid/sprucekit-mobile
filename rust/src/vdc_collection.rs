use std::sync::Arc;

use crate::common::*;
use crate::credential::Credential;
use crate::storage_manager::*;

use futures::StreamExt;
use thiserror::Error;
use tracing::info;
use uuid::Uuid;

/// Internal prefix for credential keys.
const KEY_PREFIX: &str = "Credential.";

#[derive(uniffi::Object)]
/// Verifiable Digital Credential Collection
///
/// This is the main interface to credentials.
#[derive(Debug)]
pub struct VdcCollection {
    storage: Arc<dyn StorageManagerInterface>,
}

#[derive(Error, Debug, uniffi::Error)]
pub enum VdcCollectionError {
    /// Attempt to convert the credential to a serialized form suitable for writing to storage failed.
    #[error("Failed to Serialize Value")]
    SerializeFailed,

    /// Attempting to convert the credential to a deserialized form suitable for runtime use failed.
    #[error("Failed to Deserialize Value")]
    DeserializeFailed,

    /// Attempting to write the credential to storage failed.
    #[error("Failed to Write to Storage")]
    StoreFailed(StorageManagerError),

    /// Attempting to read the credential from storage failed.
    #[error("Failed to Read from Storage")]
    LoadFailed(StorageManagerError),

    /// Attempting to delete a credential from storage failed.
    #[error("Failed to Delete from Storage")]
    DeleteFailed(StorageManagerError),
}

#[uniffi::export]
impl VdcCollection {
    #[uniffi::constructor]
    /// Create a new credential set.
    pub fn new(engine: Arc<dyn StorageManagerInterface>) -> VdcCollection {
        VdcCollection { storage: engine }
    }

    /// Add a credential to the set.
    pub async fn add(&self, credential: &Credential) -> Result<(), VdcCollectionError> {
        let val = match serde_cbor::to_vec(&credential) {
            Ok(x) => x,
            Err(_) => return Err(VdcCollectionError::SerializeFailed),
        };

        match self
            .storage
            .add(Self::id_to_key(credential.id), Value(val))
            .await
        {
            Ok(()) => Ok(()),
            Err(e) => Err(VdcCollectionError::StoreFailed(e)),
        }
    }

    /// Get a credential from the store.
    pub async fn get(&self, id: Uuid) -> Result<Option<Credential>, VdcCollectionError> {
        let raw = match self.storage.get(Self::id_to_key(id)).await {
            Ok(Some(x)) => x,
            Ok(None) => return Ok(None),
            Err(e) => return Err(VdcCollectionError::LoadFailed(e)),
        };

        match serde_cbor::de::from_slice(&raw.0) {
            Ok(Some(x)) => Ok(Some(x)),
            _ => Err(VdcCollectionError::DeserializeFailed),
        }
    }

    /// Remove a credential from the store.
    pub async fn delete(&self, id: Uuid) -> Result<(), VdcCollectionError> {
        match self.storage.remove(Self::id_to_key(id)).await {
            Ok(_) => Ok(()),
            Err(e) => Err(VdcCollectionError::DeleteFailed(e)),
        }
    }

    /// Get a list of all the credentials.
    pub async fn all_entries(&self) -> Result<Vec<Uuid>, VdcCollectionError> {
        self.storage
            .list()
            .await
            .map(|list| list.iter().filter_map(Self::key_to_id).collect())
            .map_err(VdcCollectionError::LoadFailed)
    }

    /// Get a list of all the credentials that match a specified type.
    pub async fn all_entries_by_type(
        &self,
        ctype: &CredentialType,
    ) -> Result<Vec<Uuid>, VdcCollectionError> {
        let all_entries = self.all_entries().await?;
        Ok(futures::stream::iter(all_entries.into_iter())
            .filter_map(|id| async move { self.get(id).await.ok().flatten() })
            .collect::<Vec<Credential>>()
            .await
            .iter()
            .filter(|cred| &cred.r#type == ctype)
            .map(|cred| cred.id)
            .collect::<Vec<Uuid>>())
    }

    /// Dump the contents of the credential set to the logger.
    pub async fn dump(&self) {
        match self.all_entries().await {
            Ok(list) => {
                for key in list {
                    if let Ok(x) = self.get(key).await {
                        info!("{:?}", x);
                    }
                }
            }
            Err(e) => info!("Unable to get list: {:?}", e),
        }
    }
}

impl VdcCollection {
    /// Convert a UUID to a storage key.
    fn id_to_key(id: Uuid) -> Key {
        Key(format!("{}{}", KEY_PREFIX, id))
    }

    /// Convert a string ref to a storage key.
    ///
    /// Returns `None` if it's not the right format.
    fn key_to_id(key: &Key) -> Option<Uuid> {
        key.strip_prefix(KEY_PREFIX)
            .map(|id| Uuid::parse_str(&id))
            .transpose()
            .ok()
            .flatten()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{credential::CredentialFormat, local_store::*};

    #[tokio::test]
    async fn test_vdc() {
        let smi: Arc<dyn StorageManagerInterface> = Arc::new(LocalStore::new());
        let vdc = VdcCollection::new(smi);
        for id in vdc.all_entries().await.unwrap() {
            vdc.delete(id).await.unwrap();
        }
        let payload_1: Vec<u8> = "Some random collection of bytes. ⚛".into();
        let payload_2: Vec<u8> = "Some other random collection of bytes. 📯".into();
        let payload_3: Vec<u8> = "Some third random collection of bytes. λ".into();

        let credential_1 = Credential {
            id: Uuid::new_v4(),
            format: CredentialFormat::MsoMdoc,
            r#type: CredentialType("org.iso.18013.5.1.mDL".into()),
            payload: payload_1.clone(),
            key_alias: None,
        };

        let credential_2 = Credential {
            id: Uuid::new_v4(),
            format: CredentialFormat::MsoMdoc,
            r#type: CredentialType("org.iso.18013.5.1.mDL".into()),
            payload: payload_2.clone(),
            key_alias: None,
        };

        let credential_3 = Credential {
            id: Uuid::new_v4(),
            format: CredentialFormat::MsoMdoc,
            r#type: CredentialType("org.iso.18013.5.1.mDL".into()),
            payload: payload_3.clone(),
            key_alias: None,
        };

        vdc.add(&credential_1)
            .await
            .expect("Unable to add the first value.");

        vdc.add(&credential_2)
            .await
            .expect("Unable to add the second value.");

        vdc.add(&credential_3)
            .await
            .expect("Unable to add the third value.");

        vdc.get(credential_2.id)
            .await
            .expect("Failed to get the second value");
        vdc.get(credential_1.id)
            .await
            .expect("Failed to get the first value");
        vdc.get(credential_3.id)
            .await
            .expect("Failed to get the third value");

        assert!(vdc.all_entries().await.unwrap().len() == 3);

        vdc.delete(credential_2.id)
            .await
            .expect("Failed to delete the second value.");

        assert!(vdc.all_entries().await.unwrap().len() == 2);

        vdc.delete(credential_1.id)
            .await
            .expect("Failed to delete the first value.");
        vdc.delete(credential_3.id)
            .await
            .expect("Failed to delete the third value.");

        assert!(vdc.all_entries().await.unwrap().is_empty());
    }
}
