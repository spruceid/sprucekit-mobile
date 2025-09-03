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
pub enum ActivityLogError {
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

#[derive(uniffi::Enum, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub enum ActivityLogEntryType {
    Request,
    Issued,
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

#[derive(uniffi::Object, Clone, Serialize, Deserialize)]
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
    /// Boolean option on whether this entry has been hidden
    ///
    /// This means that the UI should not show this entry,
    /// but still shown a line item, allowing the user to
    /// un-hide the entry, possibly using biometrics or
    /// PIN for user control.
    hidden: bool,
    /// Fields that have been shared. This will be an empty
    /// vector if there are no fields shared (i.e., when the
    /// activity type is not `Shared`)
    fields: Vec<String>,
}

#[uniffi::export]
impl ActivityLogEntry {
    #[uniffi::constructor]
    fn new(
        credential_id: Uuid,
        r#type: ActivityLogEntryType,
        description: String,
        interaction_with: String,
        fields: Option<Vec<String>>,
        url: Option<String>,
    ) -> Result<Self, ActivityLogError> {
        let date = SystemTime::now()
            .duration_since(SystemTime::UNIX_EPOCH)
            .map_err(|e| ActivityLogError::CreateActivityLogEntry(e.to_string()))?
            .as_secs();

        let fields = fields.unwrap_or_default();

        Ok(Self {
            id: Uuid::new_v4(),
            credential_id,
            r#type,
            date,
            description,
            interaction_with,
            fields,
            url,
            hidden: false,
        })
    }

    #[uniffi::constructor]
    fn from_json_bytes(bytes: Vec<u8>) -> Result<Self, ActivityLogError> {
        serde_json::from_slice(&bytes)
            .map_err(|e| ActivityLogError::ActivityLogEntryDeserialization(e.to_string()))
    }

    #[uniffi::constructor]
    fn from_json_str(json_str: String) -> Result<Self, ActivityLogError> {
        serde_json::from_str(&json_str)
            .map_err(|e| ActivityLogError::ActivityLogEntryDeserialization(e.to_string()))
    }

    // Getter Methods

    fn get_id(&self) -> Uuid {
        self.id
    }

    fn get_credential_id(&self) -> Uuid {
        self.credential_id
    }

    fn get_type(&self) -> ActivityLogEntryType {
        self.r#type.clone()
    }

    fn get_date(&self) -> u64 {
        self.date
    }

    fn get_description(&self) -> String {
        self.description.clone()
    }

    fn get_interaction_with(&self) -> String {
        self.interaction_with.clone()
    }

    fn get_fields(&self) -> Vec<String> {
        self.fields.clone()
    }

    fn get_url(&self) -> Option<String> {
        self.url.clone()
    }

    fn get_hidden(&self) -> bool {
        self.hidden
    }

    /// Serializes the activity log as a byte-encoded JSON string
    fn to_json_bytes(&self) -> Result<Vec<u8>, ActivityLogError> {
        serde_json::to_vec(self)
            .map_err(|e| ActivityLogError::ActivityLogEntrySerialization(e.to_string()))
    }

    /// Serializes the activity log as a JSON string
    fn to_json_string(&self) -> Result<String, ActivityLogError> {
        serde_json::to_string(self)
            .map_err(|e| ActivityLogError::ActivityLogEntrySerialization(e.to_string()))
    }
}

impl ActivityLogEntry {
    // Setter methods
    pub(crate) fn set_hidden(&mut self, should_hide: bool) {
        self.hidden = should_hide;
    }

    pub(crate) fn credential_and_entry_id_to_key(credential_id: Uuid, entry_id: Uuid) -> Key {
        Key(format!("{KEY_PREFIX}{credential_id}.{entry_id}"))
    }

    pub(crate) fn as_storage_key(&self) -> Key {
        ActivityLogEntry::credential_and_entry_id_to_key(self.credential_id, self.id)
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
    ) -> Result<Self, ActivityLogError> {
        Ok(Self {
            credential_id,
            storage,
        })
    }

    /// Adds and saved an activity log entry using the storage manager
    /// interface provided.
    pub async fn add(&self, entry: Arc<ActivityLogEntry>) -> Result<(), ActivityLogError> {
        if entry.credential_id != self.credential_id {
            return Err(ActivityLogError::InvalidCredentialId(
                entry.credential_id,
                self.credential_id,
            ));
        }

        let key: Key = entry.as_ref().into();
        let value: Value = entry.as_ref().try_into()?;

        self.storage
            .add(key, value)
            .await
            .map_err(|e| ActivityLogError::Storage(e.to_string()))?;

        Ok(())
    }

    pub async fn get(
        &self,
        entry_id: Uuid,
    ) -> Result<Option<Arc<ActivityLogEntry>>, ActivityLogError> {
        let key = ActivityLogEntry::credential_and_entry_id_to_key(self.credential_id, entry_id);

        let value = self
            .storage
            .get(key)
            .await
            .map_err(|e| ActivityLogError::Storage(e.to_string()))?
            .and_then(|value| value.try_into().ok())
            .map(|entry: ActivityLogEntry| Arc::new(entry));

        Ok(value)
    }

    pub async fn set_hidden(
        &self,
        entry_id: Uuid,
        should_hide: bool,
    ) -> Result<Arc<ActivityLogEntry>, ActivityLogError> {
        let entry = self.get(entry_id).await?;

        match entry {
            Some(arc_entry) => {
                // Extract the data from the Arc and create a new modified entry
                let mut new_entry = (*arc_entry).clone();
                new_entry.set_hidden(should_hide);

                // Create a new Arc with the modified entry and add it back
                let new_arc_entry = Arc::new(new_entry);
                self.add(new_arc_entry.clone()).await?;

                Ok(new_arc_entry)
            }
            None => Err(ActivityLogError::NotFound(format!(
                "Activity log entry for {entry_id} not found"
            ))),
        }
    }

    pub async fn remove(&self, entry_id: Uuid) -> Result<(), ActivityLogError> {
        let key = ActivityLogEntry::credential_and_entry_id_to_key(self.credential_id, entry_id);

        self.storage
            .remove(key)
            .await
            .map_err(|e| ActivityLogError::Storage(e.to_string()))?;

        Ok(())
    }

    /// Returns a list of activity log entries matching the
    /// `credential_id` corresponding to the activity log.
    pub async fn entries(
        &self,
        filter: Option<ActivityLogFilterOptions>,
    ) -> Result<Vec<Arc<ActivityLogEntry>>, ActivityLogError> {
        let entries = self
            .filter_entries(filter)
            .await?
            .into_iter()
            .map(Arc::new)
            .collect();

        Ok(entries)
    }

    /// Returns the optionally filtered activity log entries list as a JSON encoded string for export use.
    pub async fn export_entries(
        &self,
        filter: Option<ActivityLogFilterOptions>,
    ) -> Result<String, ActivityLogError> {
        let entries = self
            .filter_entries(filter)
            .await?
            .into_iter()
            .collect::<Vec<ActivityLogEntry>>();
        serde_json::to_string(&entries)
            .map_err(|e| ActivityLogError::ActivityLogEntrySerialization(e.to_string()))
    }
}

impl ActivityLog {
    /// Returns a list of activity log entries matching the
    /// `credential_id` corresponding to the activity log.
    pub async fn filter_entries(
        &self,
        filter: Option<ActivityLogFilterOptions>,
    ) -> Result<Vec<ActivityLogEntry>, ActivityLogError> {
        let keys = self
            .storage
            .list()
            .await
            .map_err(|e| ActivityLogError::Storage(e.to_string()))?
            .into_iter()
            .filter(|key: &Key| {
                key.0.split_once(KEY_PREFIX)
                    .map(|(_, rest)| !rest.is_empty())
                    .unwrap_or(false)
            })
            .collect::<Vec<Key>>();

        log::info!("Found Keys for Activity Log in storage: {keys:?}");

        if keys.is_empty() {
            return Ok(Vec::with_capacity(0));
        }

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
}

impl From<&ActivityLogEntry> for Key {
    fn from(entry: &ActivityLogEntry) -> Self {
        entry.as_storage_key()
    }
}

impl TryFrom<&ActivityLogEntry> for Value {
    type Error = ActivityLogError;

    fn try_from(entry: &ActivityLogEntry) -> Result<Self, Self::Error> {
        entry.to_json_bytes().map(Value)
    }
}

impl TryFrom<Value> for ActivityLogEntry {
    type Error = ActivityLogError;

    fn try_from(value: Value) -> Result<Self, Self::Error> {
        Self::from_json_bytes(value.0)
    }
}

#[cfg(test)]
mod test {
    use crate::storage_manager::test::{DummyStorage, NamespacedDummyStorage};

    use super::*;

    async fn run_activity_log_test(storage: Arc<dyn StorageManagerInterface>) -> Result<(), ActivityLogError> {
        let credential_id = Uuid::new_v4();

        // Load activity Log
        let activity_log = ActivityLog::load(credential_id, storage).await?;

        assert_eq!(
            activity_log.entries(None).await?.len(),
            0,
            "Storage Activity log should be empty"
        );

        let entry = Arc::new(ActivityLogEntry::new(
            credential_id,
            ActivityLogEntryType::Request,
            "requesting new credential issuance".into(),
            "ISSUING AUTHORITY".into(),
            None,
            Some("www.example.com".into()),
        )?);

        activity_log.add(entry.clone()).await?;

        assert_eq!(
            activity_log.entries(None).await?.len(),
            1,
            "Storage Activity log should contain an entry"
        );

        assert_eq!(entry.hidden, false, "Expect entry to NOT be hidden");

        let entry = activity_log.set_hidden(entry.get_id(), true).await?;

        assert_eq!(entry.hidden, true, "Expect entry to be hidden");

        activity_log.remove(entry.get_id()).await?;

        assert_eq!(
            activity_log.entries(None).await?.len(),
            0,
            "Storage Activity log should be empty"
        );

        Ok(())
    }

    #[tokio::test]
    async fn test_activity_log() -> Result<(), ActivityLogError> {
        let storage = Arc::new(DummyStorage::default());
        run_activity_log_test(storage).await
    }

    #[tokio::test]
    async fn test_namespaced_activity_log() -> Result<(), ActivityLogError> {
        let storage: Arc<NamespacedDummyStorage> = Arc::new(NamespacedDummyStorage::default());
        run_activity_log_test(storage).await
    }
}
