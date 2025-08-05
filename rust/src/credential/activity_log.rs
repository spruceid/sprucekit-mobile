use std::{sync::Arc, time::SystemTime};

use serde::{Deserialize, Serialize};
use uuid::Uuid;

use crate::{storage_manager::StorageManagerInterface, Key};

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
}

#[derive(uniffi::Enum, Serialize, Deserialize)]
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
    from_date: Option<i64>,
    /// Timestamp of when the logs should be filtered to
    to_date: Option<i64>,
    /// Entry type to filter
    r#type: ActivityLogEntryType,
    /// Filter on an interaction actor (e.g., Issuer, Verifier)
    interacted_with: Option<String>,
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
    interaction_with: Option<String>,
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
        interaction_with: Option<String>,
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
    pub(crate) entries: Vec<ActivityLogEntry>,
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
        // TODO: load activity from storage, if none is found,
        // return a new activity log.
        let entries = Vec::new();

        Ok(Self {
            credential_id,
            storage,
            entries,
        })
    }

    pub fn entries(
        &self,
        filter: Option<ActivityLogFilterOptions>,
    ) -> Result<Vec<ActivityLogEntry>, Error> {
        unimplemented!()
    }

    pub fn add(&self, entry: ActivityLogEntry) -> Result<(), Error> {
        if entry.credential_id != self.credential_id {
            return Err(Error::InvalidCredentialId(
                entry.credential_id,
                self.credential_id,
            ));
        }

        unimplemented!("Implement the add activity function")
    }

    /// Save the activity log using the storage manager reference.
    pub fn save(&self) -> Result<(), Error> {
        unimplemented!("Implement the save activity function")
    }
}

impl ActivityLog {
    /// Convert a UUID to a storage key.
    fn id_to_key(credential_id: Uuid, entry_id: Uuid) -> Key {
        Key(format!("{KEY_PREFIX}{credential_id}.{entry_id}"))
    }

    /// Key to credential ID
    fn key_to_credential_id(key: &Key) -> Option<Uuid> {
        match key.strip_prefix(KEY_PREFIX) {
            None => None,
            Some(id) => id
                .split_once(".")
                .map(|(id, _)| Uuid::parse_str(&id))
                .transpose()
                .ok()
                .flatten(),
        }
    }

    /// Key to entry ID
    fn key_to_entry_id(key: &Key) -> Option<Uuid> {
        match key.strip_prefix(KEY_PREFIX) {
            None => None,
            Some(id) => id
                .split_once(".")
                .map(|(_, id)| Uuid::parse_str(&id))
                .transpose()
                .ok()
                .flatten(),
        }
    }
}
