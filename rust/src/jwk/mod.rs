use std::{fmt, hash::Hash, str::FromStr};

use tokio::sync::RwLock;

mod algorithm;

pub use algorithm::*;

#[derive(Debug, thiserror::Error, uniffi::Error)]
#[uniffi(flat_error)]
pub enum InvalidJwk {
    #[error("Invalid JWK syntax")]
    Syntax,
}

/// JSON Web Key.
#[derive(Debug, uniffi::Object)]
#[uniffi::export(Display, Eq)]
pub struct Jwk(pub(crate) RwLock<ssi::JWK>);

#[uniffi::export]
impl Jwk {
    #[uniffi::constructor]
    pub fn from_string(json: &str) -> Result<Self, InvalidJwk> {
        ssi::JWK::from_str(json)
            .map(RwLock::new)
            .map(Self)
            .map_err(|_| InvalidJwk::Syntax)
    }

    /// Returns the key identifier (`kid` parameter) value.
    pub fn get_kid(&self) -> Option<String> {
        self.0.blocking_read().key_id.clone()
    }

    /// Sets key identifier (`kid` parameter) value.
    pub fn set_kid(&self, kid: Option<String>) {
        self.0.blocking_write().key_id = kid
    }
}

impl fmt::Display for Jwk {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        self.0.blocking_read().fmt(f)
    }
}

impl PartialEq for Jwk {
    fn eq(&self, other: &Self) -> bool {
        self.0.blocking_read().eq(&*other.0.blocking_read())
    }
}

impl Eq for Jwk {}

impl Hash for Jwk {
    fn hash<H: std::hash::Hasher>(&self, state: &mut H) {
        self.0.blocking_read().hash(state);
    }
}

#[uniffi::export]
pub fn jwk_from_public_p256(x: Vec<u8>, y: Vec<u8>) -> Jwk {
    ssi::JWK::from(ssi::jwk::Params::EC(ssi::jwk::ECParams {
        curve: Some("P-256".to_owned()),
        x_coordinate: Some(ssi::jwk::Base64urlUInt(x)),
        y_coordinate: Some(ssi::jwk::Base64urlUInt(y)),
        ecc_private_key: None,
    }))
    .into()
}

impl From<ssi::JWK> for Jwk {
    fn from(value: ssi::JWK) -> Self {
        Self(RwLock::new(value))
    }
}
