use std::sync::Arc;

use crate::storage_manager::StorageManagerInterface;

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum Error {
    #[error("Failed to find activity log for credential: {0}")]
    NotFound(String),
}

#[derive(uniffi::Enum)]
pub enum ActivityLogType {
    Provisioned,
    Shared,
    Refresh,
    Review,
    Deleted,
    // Add more as needed.
}

#[derive(uniffi::Record)]
pub struct ActivityLogEntry {
    r#type: ActivityLogType,
    /// date encoded as a unix timestamp
    date: i64,
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

/// Activity Log has a 1:1 relationship with a credential
/// and a credential ID is provided to the constructor, along
/// with a reference to the storage manager interface to lookup
/// the activity log details per the credential, returning this
/// class with its accessor methods.
#[derive(uniffi::Object)]
pub struct ActivityLog {
    pub(crate) credential_id: String,
    pub(crate) storage: Arc<dyn StorageManagerInterface>,
    pub(crate) entries: Vec<ActivityLogEntry>,
}

#[uniffi::export]
impl ActivityLog {
    /// Load activity log for the credential id.
    ///
    /// Requires a storage manager interface for looking up
    /// the activity log details.
    #[uniffi::constructor]
    pub async fn load(
        credential_id: String,
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

    pub fn add(&self, entry: ActivityLogEntry) -> Result<(), Error> {
        unimplemented!("Implement the add activity function")
    }

    /// Save the activity log using the storage manager reference.
    pub fn save(&self) -> Result<(), Error> {
        unimplemented!("Implement the save activity function")
    }
}
