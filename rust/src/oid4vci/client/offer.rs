#[derive(uniffi::Object)]
pub struct ResolvedCredentialOffer(pub(crate) oid4vci::client::ResolvedCredentialOffer);

/// Grant type of an OID4VCI credential offer.
///
/// Determines the authorization ceremony required before a credential can be
/// issued: none, a transaction code (PIN), or a full authorization-code flow.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum GrantType {
    PreAuthCodeNoTxCode,
    PreAuthCodeWithTxCode,
    AuthorizationCode,
}

#[uniffi::export]
impl ResolvedCredentialOffer {
    pub fn credential_issuer(&self) -> String {
        self.0.issuer_metadata.credential_issuer.as_str().to_owned()
    }

    pub fn issuer_display_name(&self) -> Option<String> {
        self.0
            .issuer_metadata
            .display
            .first()
            .and_then(|d| d.name.clone())
    }

    pub fn credential_configuration_ids(&self) -> Vec<String> {
        self.0
            .params
            .credential_configuration_ids
            .iter()
            .map(|id| id.to_string())
            .collect()
    }

    pub fn grant_type(&self) -> GrantType {
        let grants = &self.0.params.grants;
        if grants.authorization_code.is_some() {
            GrantType::AuthorizationCode
        } else if let Some(pre_auth) = grants.pre_authorized_code.as_ref() {
            if pre_auth.tx_code.is_some() {
                GrantType::PreAuthCodeWithTxCode
            } else {
                GrantType::PreAuthCodeNoTxCode
            }
        } else {
            GrantType::PreAuthCodeNoTxCode
        }
    }
}

impl From<oid4vci::client::ResolvedCredentialOffer> for ResolvedCredentialOffer {
    fn from(value: oid4vci::client::ResolvedCredentialOffer) -> Self {
        Self(value)
    }
}
