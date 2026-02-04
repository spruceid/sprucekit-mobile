/// Credential or configuration identifier.
#[derive(uniffi::Enum)]
pub enum CredentialOrConfigurationId {
    Credential(String),
    Configuration(String),
}

impl From<CredentialOrConfigurationId> for oid4vci::request::CredentialOrConfigurationId {
    fn from(value: CredentialOrConfigurationId) -> Self {
        match value {
            CredentialOrConfigurationId::Credential(id) => Self::Credential(id),
            CredentialOrConfigurationId::Configuration(id) => Self::Configuration(id),
        }
    }
}

impl From<oid4vci::request::CredentialOrConfigurationId> for CredentialOrConfigurationId {
    fn from(value: oid4vci::request::CredentialOrConfigurationId) -> Self {
        match value {
            oid4vci::request::CredentialOrConfigurationId::Credential(id) => Self::Credential(id),
            oid4vci::request::CredentialOrConfigurationId::Configuration(id) => {
                Self::Configuration(id)
            }
        }
    }
}
