pub use mobile_toolkit::storage_manager::{StorageManagerError, StorageManagerInterface};

#[cfg(test)]
pub mod test {
    use async_trait::async_trait;
    use mobile_toolkit::{
        storage_manager::{StorageManagerError, StorageManagerInterface},
        Key, Value,
    };

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
