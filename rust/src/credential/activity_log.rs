use std::{sync::Arc, time::SystemTime};

use crate::{storage_manager::StorageManagerInterface, Key, Value};

use futures::StreamExt;
use itertools::Itertools;
use serde::{Deserialize, Serialize};
use uuid::Uuid;

/// Entries are stored at the individual entry-level to
/// ensure that storage of a complete activity log does not
/// grow in size prohibitively. Keeping the storage at the
/// entry level flattens the activity log size acrossed
/// the entries in storage.
///
/// In practice, this means that the file identifier for the
/// activity log must include the unique credential ID that corresponds
/// to the activity log entry, and a unique identifier for the entry itself.
///
/// Ex Entry Key Identifier: `ActivityLogEntry.{credential_id}.{entry_id}`
pub const KEY_PREFIX: &str = "ActivityLogEntry.";

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum Error {
    #[error("Failed to find activity log for credential: {0}")]
    NotFound(String),
    #[error("Failed to create an activity log entry: {0}")]
    CreateActivityLogEntry(String),
    #[error("Failed to add activity log entry for credential id: {0}; expected: {0}")]
    InvalidCredentialId(Uuid, Uuid),
    #[error("Serialization failed for activity log entry: {0}")]
    ActivityLogEntrySerialization(String),
    #[error("Deserialization failed for activity log entry: {0}")]
    ActivityLogEntryDeserialization(String),
    #[error("Storage error occured for activity log entry: {0}")]
    Storage(String),
}

#[derive(uniffi::Enum, Serialize, Deserialize, PartialEq, Eq)]
pub enum ActivityLogEntryType {
    Provisioned,
    Shared,
    Refresh,
    Review,
    Deleted,
    // Add more as needed.
}

#[derive(uniffi::Record)]
pub struct ActivityLogFilterOptions {
    /// Timestamp of when the logs should be filtered from
    from_date: Option<u64>,
    /// Timestamp of when the logs should be filtered to
    to_date: Option<u64>,
    /// Entry type to filter
    r#type: Option<ActivityLogEntryType>,
    /// Filter on an interaction actor (e.g., Issuer, Verifier)
    interacted_with: Option<String>,
    /// Max items to be returned
    max_items: Option<u32>,
}

impl ActivityLogFilterOptions {
    fn should_filter_entry(&self, entry: &ActivityLogEntry, index: usize) -> bool {
        // Check date range filters
        if let Some(from_date) = self.from_date {
            if from_date > entry.date {
                return false;
            }
        }

        if let Some(to_date) = self.to_date {
            if to_date < entry.date {
                return false;
            }
        }

        // Check type filter
        if let Some(ref filter_type) = self.r#type {
            if *filter_type != entry.r#type {
                return false;
            }
        }

        // Check interaction actor filter
        if let Some(ref actor) = self.interacted_with {
            if *actor != entry.interaction_with {
                return false;
            }
        }

        // Check max items limit
        if let Some(max_items) = self.max_items {
            if index >= max_items as usize {
                return false;
            }
        }

        true
    }
}

#[derive(uniffi::Record, Serialize, Deserialize)]
pub struct ActivityLogEntry {
    /// Unique identifier for the entry
    id: Uuid,
    /// Unique identifier for the credential this activity log
    /// entry belongs to.
    credential_id: Uuid,
    /// Type of activity log entry
    r#type: ActivityLogEntryType,
    /// date encoded as a unix timestamp
    date: u64,
    /// Description shown the user of the interaction
    description: String,
    /// Interaction with denotes the name of the
    /// service, issuer, verifier, etc. that the activity
    /// corresponds to.
    ///
    /// For example, if ACME.gov was an issuer of a refresh
    /// notice, then the `interaction_with` would be `ACME.gov`.
    ///
    /// NOTE: it's not always the case the user/holder is interacting
    /// with an external actor, for example, if the user/holder deletes
    /// a credential, then that activity is self referential.
    ///
    // TODO: determine if there is a better name for this field.
    interaction_with: String,
    /// Optional Call-to-action URL (either external or internal)
    /// to route the user to the appropriate page to proceed with any
    /// follow up details.
    ///
    // TODO: determine if there is a better name for this field
    url: Option<String>,
}

#[uniffi::export]
impl ActivityLogEntry {
    #[uniffi::constructor]
    fn new(
        credential_id: Uuid,
        r#type: ActivityLogEntryType,
        description: String,
        interaction_with: String,
        url: Option<String>,
    ) -> Result<Self, Error> {
        let date = SystemTime::now()
            .duration_since(SystemTime::UNIX_EPOCH)
            .map_err(|e| Error::CreateActivityLogEntry(e.to_string()))?
            .as_secs();

        Ok(Self {
            id: Uuid::new_v4(),
            credential_id,
            r#type,
            date,
            description,
            interaction_with,
            url,
        })
    }

    #[uniffi::constructor]
    fn from_json_bytes(bytes: Vec<u8>) -> Result<Self, Error> {
        serde_json::from_slice(&bytes)
            .map_err(|e| Error::ActivityLogEntryDeserialization(e.to_string()))
    }

    #[uniffi::constructor]
    fn from_json_str(json_str: String) -> Result<Self, Error> {
        serde_json::from_str(&json_str)
            .map_err(|e| Error::ActivityLogEntryDeserialization(e.to_string()))
    }
}

impl ActivityLogEntry {
    /// Serializes the activity log as a byte-encoded JSON string
    fn to_json_bytes(&self) -> Result<Vec<u8>, Error> {
        serde_json::to_vec(self).map_err(|e| Error::ActivityLogEntrySerialization(e.to_string()))
    }

    /// Serializes the activity log as a JSON string
    fn to_json_string(&self) -> Result<String, Error> {
        serde_json::to_string(self).map_err(|e| Error::ActivityLogEntrySerialization(e.to_string()))
    }
}

/// Activity Log has a 1:1 relationship with a credential
/// and a credential ID is provided to the constructor, along
/// with a reference to the storage manager interface to lookup
/// the activity log details per the credential, returning this
/// class with its accessor methods.
#[derive(uniffi::Object)]
pub struct ActivityLog {
    pub(crate) credential_id: Uuid,
    pub(crate) storage: Arc<dyn StorageManagerInterface>,
}

#[uniffi::export]
impl ActivityLog {
    /// Load activity log for the credential id.
    ///
    /// Requires a storage manager interface for looking up
    /// the activity log details.
    ///
    /// Credential ID corresponds to the unique identifier of
    /// the credential in the storage, which can be accessed via
    /// the VDC collection or other storage mechanism.
    ///
    /// It is assumed the storage manager interface that is
    /// passed in, is the same as the VDC collection storage manager.
    ///
    // NOTE: That assumption may prove problematic, and we may wish to decouple
    // the storage drivers further.
    #[uniffi::constructor]
    pub async fn load(
        credential_id: Uuid,
        storage: Arc<dyn StorageManagerInterface>,
    ) -> Result<Self, Error> {
        Ok(Self {
            credential_id,
            storage,
        })
    }

    /// Returns a list of activity log entries matching the
    /// `credential_id` corresponding to the activity log.
    ///
    ///
    ///
    ///
    pub async fn entries(
        &self,
        filter: Option<ActivityLogFilterOptions>,
    ) -> Result<Vec<ActivityLogEntry>, Error> {
        let keys = self
            .storage
            .list()
            .await
            .map_err(|e| Error::Storage(e.to_string()))?
            .into_iter()
            .filter(|key| key.strip_prefix(KEY_PREFIX).is_some())
            .collect::<Vec<Key>>();

        let entries = futures::stream::iter(keys.into_iter())
            .filter_map(|key| async move { self.storage.get(key).await.ok().flatten() })
            .filter_map(|value| async move { ActivityLogEntry::try_from(value).ok() })
            .collect::<Vec<ActivityLogEntry>>()
            .await
            .into_iter()
            .filter(|entry| entry.credential_id == self.credential_id)
            // Sort by the date so the most recent activity is always first
            .sorted_by(|a, b| Ord::cmp(&b.date, &a.date))
            .enumerate()
            .filter(|(index, entry)| match filter.as_ref() {
                Some(opts) => opts.should_filter_entry(entry, *index),
                // Pass through all entries if no filter options are provided
                None => true,
            })
            .map(|(_, entry)| entry)
            .collect::<Vec<ActivityLogEntry>>();

        Ok(entries)
    }

    /// Adds and saved an activity log entry using the storage manager
    /// interface provided.
    pub async fn add(&self, entry: ActivityLogEntry) -> Result<(), Error> {
        if entry.credential_id != self.credential_id {
            return Err(Error::InvalidCredentialId(
                entry.credential_id,
                self.credential_id,
            ));
        }

        let key: Key = (&entry).into();
        let value: Value = (&entry).try_into()?;

        self.storage
            .add(key, value)
            .await
            .map_err(|e| Error::Storage(e.to_string()))
    }

    // Serialize an activity log entry as JSON string encoded bytes
    pub fn entry_as_json_bytes(&self, entry: ActivityLogEntry) -> Result<Vec<u8>, Error> {
        entry.to_json_bytes()
    }

    // Serialize an activity log entry as a JSON string
    pub fn entry_as_json_string(&self, entry: ActivityLogEntry) -> Result<String, Error> {
        entry.to_json_string()
    }
}

impl From<&ActivityLogEntry> for Key {
    fn from(entry: &ActivityLogEntry) -> Self {
        Key(format!("{KEY_PREFIX}{}.{}", entry.credential_id, entry.id))
    }
}

impl TryFrom<&ActivityLogEntry> for Value {
    type Error = Error;

    fn try_from(entry: &ActivityLogEntry) -> Result<Self, Self::Error> {
        entry.to_json_bytes().map(Value)
    }
}

impl TryFrom<Value> for ActivityLogEntry {
    type Error = Error;

    fn try_from(value: Value) -> Result<Self, Self::Error> {
        Self::from_json_bytes(value.0)
    }
}
