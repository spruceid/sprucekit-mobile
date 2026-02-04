#[derive(uniffi::Object)]
pub struct ResolvedCredentialOffer(pub(crate) oid4vci::client::ResolvedCredentialOffer);

#[uniffi::export]
impl ResolvedCredentialOffer {
    pub fn credential_issuer(&self) -> String {
        self.0.issuer_metadata.credential_issuer.as_str().to_owned()
    }
}

impl From<oid4vci::client::ResolvedCredentialOffer> for ResolvedCredentialOffer {
    fn from(value: oid4vci::client::ResolvedCredentialOffer) -> Self {
        Self(value)
    }
}
