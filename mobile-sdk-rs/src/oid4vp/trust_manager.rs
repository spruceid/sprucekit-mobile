use crate::common::{Key, Value};
use crate::storage_manager::{StorageManagerError, StorageManagerInterface};

use std::sync::Arc;

/// Internal prefix for trusted did keys.
const KEY_PREFIX: &str = "TrustedDIDs.";
/// "true" as a byte array
const TRUE_BYTES: [u8; 4] = [116, 114, 117, 101];
/// "false" as a byte array
const FALSE_BYTES: [u8; 5] = [102, 97, 108, 115, 101];

#[derive(thiserror::Error, Debug, uniffi::Error)]
pub enum TrustManagerError {
    #[error("An unexpected foreign callback error occurred: {0}")]
    UnexpectedUniFFICallbackError(String),
    #[error(transparent)]
    Storage(#[from] StorageManagerError),
    #[error("The DID key cannot be added because it is blocked, key: {0}")]
    DIDBlocked(String),
}

// Handle unexpected errors when calling a foreign callback
impl From<uniffi::UnexpectedUniFFICallbackError> for TrustManagerError {
    fn from(value: uniffi::UnexpectedUniFFICallbackError) -> Self {
        TrustManagerError::UnexpectedUniFFICallbackError(value.reason)
    }
}

/// TrustManager is responsible for managing trusted DIDs for the wallet.
///
/// Use the [TrustManager::new] method to create a new instance of the trust manager.
///
/// The trust manager does not store a cached state of the trusted dids,
/// but instead accesses and modifies the trusted dids in the storage manager directly.
///
/// In the future, this might change in favor of faster reads.
#[derive(Debug, Clone, uniffi::Object)]
pub struct TrustManager {
    storage: Arc<dyn StorageManagerInterface>,
}
// NOTE: Adding a cache to the TrustManager would be a good idea to avoid
// repeated reads from the storage manager. That said, the current implementation
// would need some refactoring to ensure the cache is kept up to date with the
// storage manager. See the MetadataManager for an example of how this could be done.
//
// Given that the trust manager also supports checking for `blocked` DIDs, the cache
// would ultimately only support `trusted_dids` and not `blocked_dids`.

#[uniffi::export]
impl TrustManager {
    #[uniffi::constructor]
    pub fn new(storage: Arc<dyn StorageManagerInterface>) -> Arc<Self> {
        Arc::new(Self { storage })
    }

    /// Add a trusted DID to the wallet.
    ///
    /// This will internally set the trusted did to true.
    ///
    /// If the DID is already trusted, this will overwrite the existing value.
    ///
    /// # Arguments
    ///
    /// * `did_key` - The DID key to add to the wallet.
    /// * `storage` - The storage manager to use for storing the DID.
    ///
    /// # Errors
    ///
    /// Returns a [TrustManagerError] if the DID could not be
    /// added to the wallet due to a storage error or if the DID is blocked.
    ///
    pub fn add_did(&self, did_key: String) -> Result<(), TrustManagerError> {
        if self.is_blocked_key(&did_key)? {
            return Err(TrustManagerError::DIDBlocked(did_key));
        }

        // TODO: a simple boolean value is not enough to represent the
        // trust levels of a DID. Replace this will a bitfield/bitflags or similar
        // to represent the trust levels.

        self.storage
            .add(
                Key::with_prefix(KEY_PREFIX, &did_key),
                Value(TRUE_BYTES.into()),
            )
            .map_err(TrustManagerError::Storage)
    }

    /// Remove a trusted DID from the wallet storage.
    ///
    /// # Arguments
    ///
    /// * `did_key` - The DID key to remove from the wallet.
    /// * `storage` - The storage manager to use for removing the DID.
    ///
    /// # Errors
    ///
    /// Returns a [TrustManagerError] if the DID could not be
    /// removed from the wallet due to a storage error.
    ///
    ///
    pub fn remove_did(&self, did_key: String) -> Result<(), TrustManagerError> {
        self.storage
            .remove(Key::with_prefix(KEY_PREFIX, &did_key))
            .map_err(TrustManagerError::Storage)
    }

    /// Block a trusted DID from the wallet.
    ///
    /// This will internally set the trusted did to false, but will not delete the key.
    ///
    /// If the DID is already blocked, this will overwrite the existing value.
    ///
    /// The motivation for `blocking` a DID is to prevent a removed DID from being added back
    /// to the wallet. This is useful in cases where a DID is desired to be removed from the wallet,
    /// but should not be added back in the future.
    ///
    /// # Arguments
    ///
    /// * `did_key` - The DID key to block from the wallet.
    /// * `storage` - The storage manager to use for storing the DID.
    ///
    /// # Errors
    ///
    /// Returns a [TrustManagerError] if the DID could not be
    /// blocked from the wallet due to a storage error.
    pub fn block_did(&self, did_key: String) -> Result<(), TrustManagerError> {
        self.storage
            .add(
                Key::with_prefix(KEY_PREFIX, &did_key),
                Value(FALSE_BYTES.into()),
            )
            .map_err(TrustManagerError::Storage)
    }

    /// Unblock a DID from the wallet, only if it is blocked.
    ///
    /// This will internally set the trusted did to true, unblocking
    /// the DID key.
    ///
    /// If the DID is not blocked, this will be a no-op.
    ///
    /// # Arguments
    ///
    /// * `did_key` - The DID key to unblock from the wallet.
    /// * `storage` - The storage manager to use for storing the DID.
    ///
    /// # Errors
    ///
    /// Returns a [TrustManagerError] if the DID could not be
    /// unblocked from the wallet due to a storage error.
    pub fn unblock_did(&self, did_key: String) -> Result<(), TrustManagerError> {
        if !self.is_blocked_key(&did_key)? {
            return Ok(()); // Noop if the key is not blocked.
        }

        self.storage
            .add(
                Key::with_prefix(KEY_PREFIX, &did_key),
                Value(TRUE_BYTES.into()),
            )
            .map_err(TrustManagerError::Storage)
    }

    /// Get the list of trusted DIDs from the wallet.
    ///
    /// This will return a list of DIDs that are trusted in the wallet.
    ///
    /// # Arguments
    ///
    /// * `storage` - The storage manager to use for storing the DIDs.
    ///
    /// # Errors
    ///
    /// Returns a [TrustManagerError] if the DIDs could not be
    /// retrieved from the wallet due to a storage error.
    pub fn get_trusted_dids(&self) -> Result<Vec<String>, TrustManagerError> {
        let list = self
            .storage
            .list()
            .map_err(TrustManagerError::Storage)?
            .into_iter()
            .filter_map(|id| id.strip_prefix(KEY_PREFIX))
            .filter_map(|key| match self.is_trusted_key(&key) {
                Ok(true) => Some(key),
                _ => None,
            })
            .collect::<Vec<String>>();

        Ok(list)
    }

    /// Get the list of blocked DIDs from the wallet.
    ///
    /// This will return a list of DIDs that are blocked in the wallet.
    ///
    /// # Arguments
    ///
    /// * `storage` - The storage manager to use for storing the DIDs.
    ///
    /// # Errors
    ///
    /// Returns a [TrustManagerError] if the blocked DIDs could not be
    /// retrieved from the wallet due to a storage error.
    pub fn get_blocked_dids(&self) -> Result<Vec<String>, TrustManagerError> {
        let list = self
            .storage
            .list()
            .map_err(TrustManagerError::Storage)?
            .into_iter()
            .filter_map(|id| id.strip_prefix(KEY_PREFIX))
            .filter_map(|key| match self.is_blocked_key(&key) {
                Ok(true) => Some(key),
                _ => None,
            })
            .collect::<Vec<String>>();

        Ok(list)
    }

    /// Check if a DID is trusted.
    ///
    /// Explicitly checks if a DID is trusted.
    ///
    /// # Arguments
    ///
    /// * `did_key` - The DID key to check if it is trusted.
    /// * `storage` - The storage manager to use for storing the DID.
    ///
    /// # Errors
    ///
    /// Returns a [TrustManagerError] if the DID could not be
    /// checked if it is trusted due to a storage error.
    pub fn is_trusted_did(&self, did_key: String) -> Result<bool, TrustManagerError> {
        self.is_trusted_key(&did_key)
    }

    /// Check if a DID is blocked.
    ///
    /// Explicitly checks if a DID is blocked.
    ///
    /// # Arguments
    ///
    /// * `did_key` - The DID key to check if it is blocked.
    /// * `storage` - The storage manager to use for storing the DID.
    ///
    /// # Errors
    ///
    /// Returns a [TrustManagerError] if the DID could not be
    /// checked if it is blocked due to a storage error.
    pub fn is_blocked_did(&self, did_key: String) -> Result<bool, TrustManagerError> {
        self.is_blocked_key(&did_key)
    }
}

impl TrustManager {
    /// Internal method to check if a key is trusted.
    fn is_trusted_key(&self, key: &str) -> Result<bool, TrustManagerError> {
        match self.storage.get(Key::with_prefix(KEY_PREFIX, key)) {
            Ok(Some(val)) => Ok(val == Value(TRUE_BYTES.into())),
            Ok(None) => Ok(false),
            Err(e) => Err(TrustManagerError::Storage(e)),
        }
    }

    /// Internal method to check if a key is blocked.
    ///
    /// This is used internally to check if a key is blocked.
    fn is_blocked_key(&self, key: &str) -> Result<bool, TrustManagerError> {
        match self.storage.get(Key::with_prefix(KEY_PREFIX, key)) {
            Ok(Some(val)) => Ok(val == Value(FALSE_BYTES.into())),
            Ok(None) => Ok(false),
            Err(e) => Err(TrustManagerError::Storage(e)),
        }
    }
}
