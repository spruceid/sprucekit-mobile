use crate::common::*;

use std::fmt::Debug;

use async_trait::async_trait;
use thiserror::Error;

/// Enum: StorageManagerError
///
/// Represents errors that may occur during storage management operations
#[derive(Error, Debug, uniffi::Error)]
pub enum StorageManagerError {
    /// This error happens when the key value could not be used with the underlying
    /// storage system on the device
    #[error("Invalid Lookup Key")]
    InvalidLookupKey,

    /// This error occurrs when we can retrieve a value, but could not decrypt it
    #[error("Could not decrypt retrieved value")]
    CouldNotDecryptValue,

    /// The underlying device has no more storage available
    #[error("Storage is full")]
    StorageFull,

    /// During storage manager initialization, it must create a new encryption key.  This
    /// error is raised when that key could not be created.
    #[error("Could not make storage encryption key")]
    CouldNotMakeKey,

    /// An internal problem occurred in the storage manager.
    #[error("Internal Error")]
    InternalError,
}

/// Interface: StorageManagerInterface
///
/// The StorageManagerInterface provides access to functions defined in Kotlin and Swift for
/// managing persistent storage on the device.
///
/// When dealing with UniFFI exported functions and objects, this will need to be Boxed as:
///     Box<dyn StorageManagerInterface>
///
/// We use the older callback_interface to keep the required version level of our Android API
/// low.
#[uniffi::export(with_foreign)]
#[async_trait]
pub trait StorageManagerInterface: Send + Sync + Debug {
    /// Function: add
    ///
    /// Adds a key-value pair to storage.  Should the key already exist, the value will be
    /// replaced
    ///
    /// Arguments:
    /// key - The key to add
    /// value - The value to add under the key.
    async fn add(&self, key: Key, value: Value) -> Result<(), StorageManagerError>;

    /// Function: get
    ///
    /// Callback function pointer to native (kotlin/swift) code for
    /// getting a key.
    async fn get(&self, key: Key) -> Result<Option<Value>, StorageManagerError>;

    /// Function: list
    ///
    /// Callback function pointer for listing available keys.
    async fn list(&self) -> Result<Vec<Key>, StorageManagerError>;

    /// Function: remove
    ///
    /// Callback function pointer to native (kotlin/swift) code for
    /// removing a key.  This referenced function MUST be idempotent.  In
    /// particular, it must treat removing a non-existent key as a normal and
    /// expected circumstance, simply returning () and not an error.
    async fn remove(&self, key: Key) -> Result<(), StorageManagerError>;
}

#[cfg(test)]
pub mod test {
    use super::*;
    use std::collections::HashMap;
    use tokio::sync::RwLock;

    /// Dummy Storage Implementation for testing
    #[derive(Default, Debug)]
    pub struct DummyStorage(pub(crate) RwLock<HashMap<Key, Value>>);

    #[async_trait]
    impl StorageManagerInterface for DummyStorage {
        async fn add(&self, key: Key, value: Value) -> Result<(), StorageManagerError> {
            let mut inner = self
                .0
                .try_write()
                .map_err(|_| StorageManagerError::InternalError)?;

            inner.insert(key, value);

            Ok(())
        }

        async fn get(&self, key: Key) -> Result<Option<Value>, StorageManagerError> {
            let inner = self
                .0
                .try_read()
                .map_err(|_| StorageManagerError::InternalError)?;

            Ok(inner.get(&key).map(ToOwned::to_owned))
        }

        async fn list(&self) -> Result<Vec<Key>, StorageManagerError> {
            let inner = self
                .0
                .try_read()
                .map_err(|_| StorageManagerError::InternalError)?;

            let keys = inner.keys().map(ToOwned::to_owned).collect();

            Ok(keys)
        }

        async fn remove(&self, key: Key) -> Result<(), StorageManagerError> {
            let mut inner = self
                .0
                .try_write()
                .map_err(|_| StorageManagerError::InternalError)?;

            inner.remove(&key);

            Ok(())
        }
    }

    /// Dummy Storage Implementation for testing with support for user namespaces
    #[derive(Debug)]
    pub struct NamespacedDummyStorage {
        pub namespace: String,
        pub inner: DummyStorage,
    }

    impl Default for NamespacedDummyStorage {
        fn default() -> Self {
            Self {
                namespace: "test_user_name".to_string(),
                inner: DummyStorage::default(),
            }
        }
    }

    impl NamespacedDummyStorage {
        fn namespaced_key(&self, key: &Key) -> Key {
            if key.0.starts_with(&self.namespace) {
                key.clone()
            } else {
                Key(format!("{}:{}", self.namespace, key.0))
            }
        }
    }

    #[async_trait::async_trait]
    impl StorageManagerInterface for NamespacedDummyStorage {
        async fn add(&self, key: Key, value: Value) -> Result<(), StorageManagerError> {
            self.inner.add(self.namespaced_key(&key), value).await
        }

        async fn get(&self, key: Key) -> Result<Option<Value>, StorageManagerError> {
            self.inner.get(self.namespaced_key(&key)).await
        }

        async fn list(&self) -> Result<Vec<Key>, StorageManagerError> {
            let keys = self.inner.list().await?;
            Ok(keys.into_iter().map(|k| self.namespaced_key(&k)).collect())
        }

        async fn remove(&self, key: Key) -> Result<(), StorageManagerError> {
            self.inner.remove(self.namespaced_key(&key)).await
        }
    }
}
