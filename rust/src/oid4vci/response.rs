use crate::credential::RawCredential;

use super::Oid4vciError;

#[derive(uniffi::Enum)]
pub enum CredentialResponse {
    Immediate(ImmediateCredentialResponse),
    Deferred(DeferredCredentialResponse),
}

impl CredentialResponse {
    pub fn new(
        format: &oid4vci::profile::StandardFormat,
        value: oid4vci::response::CredentialResponse,
    ) -> Result<Self, Oid4vciError> {
        match value {
            oid4vci::response::CredentialResponse::Immediate(r) => {
                ImmediateCredentialResponse::new(format, r).map(Self::Immediate)
            }
            oid4vci::response::CredentialResponse::Deferred(r) => Ok(Self::Deferred(r.into())),
        }
    }
}

#[derive(uniffi::Record)]
pub struct ImmediateCredentialResponse {
    pub credentials: Vec<RawCredential>,
}

impl ImmediateCredentialResponse {
    fn new(
        format: &oid4vci::profile::StandardFormat,
        value: oid4vci::response::ImmediateCredentialResponse,
    ) -> Result<Self, Oid4vciError> {
        Ok(Self {
            credentials: value
                .credentials
                .into_iter()
                .map(|value| RawCredential::from_oid4vci(format, value))
                .collect::<Result<_, _>>()?,
        })
    }
}

#[derive(uniffi::Record)]
pub struct DeferredCredentialResponse {
    pub transaction_id: String,
    pub interval: u64,
}

impl From<oid4vci::response::DeferredCredentialResponse> for DeferredCredentialResponse {
    fn from(value: oid4vci::response::DeferredCredentialResponse) -> Self {
        Self {
            transaction_id: value.transaction_id,
            interval: value.interval,
        }
    }
}
