use super::status::{StatusListError, StatusMessage};

use std::str::FromStr;

use futures::stream::{self, StreamExt};
use reqwest::StatusCode;
use ssi::status::bitstring_status_list_20240406::{
    BitString, BitstringStatusListCredential, BitstringStatusListEntry,
    StatusMessage as BitStringStatusMessage, StatusPurpose as BitString20240406StatusPurpose,
    StatusSize,
};
use url::Url;

uniffi::custom_type!(BitString20240406StatusPurpose, String, {
    remote,
    try_lift: |purpose| {
let custom = BitString20240406StatusPurpose::from_str(&purpose)
    .map_err(|e| StatusListError::Resolution(format!("{e:?}")))?;
Ok(custom)
    },
    lower: |purpose| purpose.to_string(),
});

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
    pub(crate) purpose: BitString20240406StatusPurpose,
    /// List of status messages to include if the purpose is a message.
    pub status_messages: Vec<StatusMessage>,
}

#[uniffi::export]
impl Status20240406 {
    /// Return the purpose of the status.
    pub fn purpose(&self) -> BitString20240406StatusPurpose {
        self.purpose
    }

    /// Return whether the credential status is revoked.
    pub fn is_revoked(&self) -> bool {
        self.purpose == BitString20240406StatusPurpose::Revocation && self.value == 1
    }

    /// Return whether the credential status is suspended.
    pub fn is_suspended(&self) -> bool {
        self.purpose == BitString20240406StatusPurpose::Suspension && self.value == 1
    }

    /// Return whether the credential status has a message.
    pub fn is_message(&self) -> bool {
        self.purpose == BitString20240406StatusPurpose::Message
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
    fn status_list_entries(&self) -> Result<Vec<BitstringStatusListEntry>, StatusListError>;

    /// Resolves the status list as an `BitstringStatusList` type.
    async fn status_list_credentials(
        &self,
    ) -> Result<Vec<BitstringStatusListCredential>, StatusListError> {
        let entries = self.status_list_entries()?;
        stream::iter(entries)
            .map(|entry| async move {
                let url = entry
                    .status_list_credential
                    .parse::<Url>()
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
            })
            .buffer_unordered(3)
            .collect::<Vec<Result<BitstringStatusListCredential, StatusListError>>>()
            .await
            .into_iter()
            .collect()
    }

    /// Returns the status of the credential, returning
    /// an object that provides the value in the status list,
    /// and the purpose of the status.
    async fn status_list_values(&self) -> Result<Vec<Status20240406>, StatusListError> {
        let entries = self.status_list_entries()?;
        let credentials = self.status_list_credentials().await?;

        credentials
            .into_iter()
            .map(|credential| {
                let bit_string = credential
                    .credential_subject
                    .encoded_list
                    .decode(None)
                    // TODO: we had to hardcode the status_size to 8 to be able to find the right status.
                    // We must analyse what is happening and remove it to use the following line:
                    // .map(|bytes| BitString::from_bytes(credential.credential_subject.status_size, bytes))
                    .map(|bytes| {
                        BitString::from_bytes(StatusSize::try_from(8).unwrap_or_default(), bytes)
                    })
                    .map_err(|e| StatusListError::Resolution(format!("{e:?}")))?;

                let value = bit_string
                    .get(
                        entries
                            .first()
                            .ok_or(StatusListError::Resolution("No entry found".to_string()))?
                            .status_list_index,
                    )
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
            })
            .collect()
    }
}
