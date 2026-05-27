/// FFI mirror of [`oid4vci::offer::InputMode`].
///
/// uniffi requires a local enum with `#[derive(uniffi::Enum)]` to cross
/// the FFI boundary; semantics delegate to the upstream type via `From`.
#[derive(uniffi::Enum, Clone, Copy)]
pub enum InputMode {
    Numeric,
    Text,
}

impl From<oid4vci::offer::InputMode> for InputMode {
    fn from(value: oid4vci::offer::InputMode) -> Self {
        match value {
            oid4vci::offer::InputMode::Numeric => Self::Numeric,
            oid4vci::offer::InputMode::Text => Self::Text,
        }
    }
}

/// FFI mirror of [`oid4vci::offer::TxCodeDefinition`].
///
/// uniffi requires a local `Record` struct with `#[derive(uniffi::Record)]`
/// to cross the FFI boundary; conversion from the upstream type is delegated
/// to `From`. Per OID4VCI §4.1.1, `input_mode` defaults to `Numeric` when
/// absent upstream.
#[derive(uniffi::Record, Clone)]
pub struct TxCodeDefinition {
    pub input_mode: InputMode,
    pub length: Option<u32>,
    pub description: Option<String>,
}

impl From<&oid4vci::offer::TxCodeDefinition> for TxCodeDefinition {
    fn from(value: &oid4vci::offer::TxCodeDefinition) -> Self {
        Self {
            input_mode: value.input_mode.unwrap_or_default().into(),
            length: value.length.map(|l| l as u32),
            description: value.description.clone(),
        }
    }
}

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

    pub fn tx_code_definition(&self) -> Option<TxCodeDefinition> {
        self.0
            .params
            .grants
            .pre_authorized_code
            .as_ref()?
            .tx_code
            .as_ref()
            .map(Into::into)
    }
}

impl From<oid4vci::client::ResolvedCredentialOffer> for ResolvedCredentialOffer {
    fn from(value: oid4vci::client::ResolvedCredentialOffer) -> Self {
        Self(value)
    }
}
