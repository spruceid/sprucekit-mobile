use super::CredentialFormat;

/// Raw Credential, not registered in the wallet.
#[derive(uniffi::Record)]
pub struct RawCredential {
    /// Credential format.
    pub format: CredentialFormat,

    /// Credential payload.
    pub payload: Vec<u8>,
}
