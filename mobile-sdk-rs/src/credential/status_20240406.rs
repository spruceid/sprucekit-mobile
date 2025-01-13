use super::status::{StatusListError, StatusMessage};
use crate::UniffiCustomTypeConverter;

use std::str::FromStr;

use reqwest::StatusCode;
use ssi::status::bitstring_status_list_20240406::{
    BitString, BitstringStatusListCredential, BitstringStatusListEntry,
    StatusMessage as BitStringStatusMessage, StatusPurpose, StatusSize,
};
use url::Url;

uniffi::custom_type!(StatusPurpose, String);
impl UniffiCustomTypeConverter for StatusPurpose {
    type Builtin = String;
    fn into_custom(purpose: Self::Builtin) -> uniffi::Result<Self> {
        let custom = StatusPurpose::from_str(&purpose)
            .map_err(|e| StatusListError::Resolution(format!("{e:?}")))?;

        Ok(custom)
    }
    fn from_custom(purpose: Self) -> Self::Builtin {
        purpose.to_string()
    }
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
pub struct Status20240406 {
    /// The raw value of the status at the entry list index,
    /// which depends on the purpose of the status for its
    /// meaning.
    pub(crate) value: u8,
    /// The purpose of the credential.
    pub(crate) purpose: StatusPurpose,
    /// List of status messages to include if the purpose is a message.
    pub status_messages: Vec<StatusMessage>,
}

#[uniffi::export]
impl Status20240406 {
    /// Return the purpose of the status.
    pub fn purpose(&self) -> StatusPurpose {
        self.purpose
    }

    /// Return whether the credential status is revoked.
    pub fn is_revoked(&self) -> bool {
        self.purpose == StatusPurpose::Revocation && self.value == 1
    }

    /// Return whether the credential status is suspended.
    pub fn is_suspended(&self) -> bool {
        self.purpose == StatusPurpose::Suspension && self.value == 1
    }

    /// Return whether the credential status has a message.
    pub fn is_message(&self) -> bool {
        self.purpose == StatusPurpose::Message
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
pub trait BitStringStatusListResolver20240406 {
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
    async fn status_list_value(&self) -> Result<Status20240406, StatusListError> {
        let entry = self.status_list_entry()?;
        let credential = self.status_list_credential().await?;

        let bit_string = credential
            .credential_subject
            .encoded_list
            .decode(None)
            .map(|bytes| BitString::from_bytes(StatusSize::default(), bytes))
            .map_err(|e| StatusListError::Resolution(format!("{e:?}")))?;

        let value = bit_string
            .get(entry.status_list_index)
            .ok_or(StatusListError::Resolution(
                "No status found at index".to_string(),
            ))?;

        Ok(Status20240406 {
            value,
            purpose: credential.credential_subject.status_purpose,
            status_messages: credential
                .credential_subject
                .status_message
                .into_iter()
                .map(Into::into)
                .collect(),
        })
    }
}
