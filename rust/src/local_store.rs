use async_trait::async_trait;

use crate::common::*;
use crate::storage_manager::*;

use std::collections::HashMap;
use std::sync::{Arc, Mutex};

/// A version of secure storage for debugging purposes, and as a minimal interface example.  Do not
/// use in production!  This encrypts nothing, uses a path relative to the current working directory,
/// and is generally cavalier about errors it encounters along the way.
#[derive(Debug, Default, Clone)]
pub struct LocalStore {
    store: Arc<Mutex<HashMap<Key, Value>>>,
}

impl LocalStore {
    pub fn new() -> Self {
        Self::default()
    }
}

#[async_trait]
impl StorageManagerInterface for LocalStore {
    /// Add a key/value pair to storage.
    async fn add(&self, key: Key, value: Value) -> Result<(), StorageManagerError> {
        let mut store = self.store.lock().unwrap();

        store.insert(key, value);

        Ok(())
    }

    /// Retrieve the value associated with a key.
    async fn get(&self, key: Key) -> Result<Option<Value>, StorageManagerError> {
        let store = self.store.lock().unwrap();

        match store.get(&key) {
            Some(x) => Ok(Some(Value(x.0.clone()))),
            None => Ok(None),
        }
    }

    /// List the available key/value pairs.
    async fn list(&self) -> Result<Vec<Key>, StorageManagerError> {
        let store = self.store.lock().unwrap();

        Ok(store.keys().map(|x| x.to_owned()).collect())
    }

    /// Delete a given key/value pair from storage.
    async fn remove(&self, key: Key) -> Result<(), StorageManagerError> {
        let mut store = self.store.lock().unwrap();

        _ = store.remove(&key);

        Ok(())
    }
}
