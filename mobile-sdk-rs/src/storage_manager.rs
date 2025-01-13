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
