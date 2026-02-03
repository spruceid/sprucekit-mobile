use std::sync::Arc;

use super::CredentialToken;

mod auth_code;
mod tx_code;

pub use auth_code::*;
pub use tx_code::*;

#[derive(uniffi::Enum)]
pub enum CredentialTokenState {
    RequiresAuthorizationCode(Arc<AuthorizationCodeRequired>),
    RequiresTxCode(Arc<TxCodeRequired>),
    Ready(Arc<CredentialToken>),
}

impl From<oid4vci::client::CredentialTokenState> for CredentialTokenState {
    fn from(value: oid4vci::client::CredentialTokenState) -> Self {
        match value {
            oid4vci::client::CredentialTokenState::RequiresAuthorizationCode(state) => {
                Self::RequiresAuthorizationCode(Arc::new(state.into()))
            }
            oid4vci::client::CredentialTokenState::RequiresTxCode(state) => {
                Self::RequiresTxCode(Arc::new(state.into()))
            }
            oid4vci::client::CredentialTokenState::Ready(token) => {
                Self::Ready(Arc::new(token.into()))
            }
        }
    }
}
