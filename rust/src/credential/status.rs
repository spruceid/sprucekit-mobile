use std::str::FromStr;

use reqwest::StatusCode;
use ssi::status::bitstring_status_list::{
    BitString, BitstringStatusListCredential, BitstringStatusListEntry,
    StatusMessage as BitStringStatusMessage, StatusPurpose as BitStringStatusPurpose,
};
use url::Url;

#[derive(Debug, uniffi::Error, thiserror::Error)]
pub enum StatusListError {
    #[error("Failed to resolve status list credential: {0}")]
    Resolution(String),
    #[error("Credential Format Not Supported for Status List")]
    UnsupportedCredentialFormat,
}

uniffi::custom_type!(BitStringStatusPurpose, String, {
    remote,
    try_lift: |purpose| {
let custom = BitStringStatusPurpose::from_str(&purpose)
    .map_err(|e| StatusListError::Resolution(format!("{e:?}")))?;
Ok(custom)
    },
    lower: |purpose| purpose.to_string(),
});

#[derive(uniffi::Record, Debug, Clone)]
pub struct StatusMessage {
    /// The value of the entry in the status list
    pub status: u8,
    /// Message that corresponds the the value.
    pub message: String,
}

impl From<BitStringStatusMessage> for StatusMessage {
    fn from(status_message: BitStringStatusMessage) -> Self {
        Self {
            status: status_message.status,
            message: status_message.message,
        }
    }
}

/// Status provides a value and purpose for a status,
///
/// The value is the raw value of the status at the entry list index,
/// and the purpose is the purpose of the credential, which is used
/// to interpret the value.
#[derive(Debug, uniffi::Object)]
pub struct Status {
    /// The raw value of the status at the entry list index,
    /// which depends on the purpose of the status for its
    /// meaning.
    pub(crate) value: u8,
    /// The purpose of the credential.
    pub(crate) purpose: BitStringStatusPurpose,
    /// List of status messages to include if the purpose is a message.
    pub status_messages: Vec<StatusMessage>,
}

#[uniffi::export]
impl Status {
    /// Return the purpose of the status.
    pub fn purpose(&self) -> BitStringStatusPurpose {
        self.purpose
    }

    /// Return whether the credential status is revoked.
    pub fn is_revoked(&self) -> bool {
        self.purpose == BitStringStatusPurpose::Revocation && self.value == 1
    }

    /// Return whether the credential status is suspended.
    pub fn is_suspended(&self) -> bool {
        self.purpose == BitStringStatusPurpose::Suspension && self.value == 1
    }

    /// Return whether the credential status has a message.
    pub fn is_message(&self) -> bool {
        self.purpose == BitStringStatusPurpose::Message
    }

    /// Return the message of the credential status.
    pub fn messages(&self) -> Vec<StatusMessage> {
        self.status_messages.clone()
    }
}

/// Interface for resolving the status of a credential
/// using a bitstring status list credential.
///
/// Only the `entry` method is required to be implemented.
#[async_trait::async_trait]
pub trait BitStringStatusListResolver {
    /// Returns the BitstringStatusListEntry of the credential.
    fn status_list_entry(&self) -> Result<BitstringStatusListEntry, StatusListError>;

    /// Resolves the status list as an `BitstringStatusList` type.
    async fn status_list_credential(
        &self,
    ) -> Result<BitstringStatusListCredential, StatusListError> {
        let entry = self.status_list_entry()?;
        let url: Url = entry
            .status_list_credential
            .parse()
            .map_err(|e| StatusListError::Resolution(format!("{e:?}")))?;

        let response = reqwest::get(url)
            .await
            .map_err(|e| StatusListError::Resolution(format!("{e:?}")))?;

        if response.status() != StatusCode::OK {
            return Err(StatusListError::Resolution(format!(
                "Failed to resolve status list credential: {}",
                response.status()
            )));
        }

        response
            .json()
            .await
            .map_err(|e| StatusListError::Resolution(format!("{e:?}")))
    }

    /// Returns the status of the credential, returning
    /// an object that provides the value in the status list,
    /// and the purpose of the status.
    async fn status_list_value(&self) -> Result<Status, StatusListError> {
        let entry = self.status_list_entry()?;
        let credential = self.status_list_credential().await?;
        let bit_string = credential
            .credential_subject
            .encoded_list
            .decode(None)
            .map(BitString::from_bytes)
            .map_err(|e| StatusListError::Resolution(format!("{e:?}")))?;

        let value = bit_string
            .get(entry.status_size, entry.status_list_index)
            .ok_or(StatusListError::Resolution(
                "No status found at index".to_string(),
            ))?;

        Ok(Status {
            value,
            purpose: credential.credential_subject.status_purpose,
            status_messages: entry.status_messages.into_iter().map(Into::into).collect(),
        })
    }
}
