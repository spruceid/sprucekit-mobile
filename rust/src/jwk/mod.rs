use std::{
    fmt,
    hash::Hash,
    str::FromStr,
    sync::{PoisonError, RwLock, RwLockReadGuard, RwLockWriteGuard},
};

mod algorithm;

pub use algorithm::*;

#[derive(Debug, thiserror::Error, uniffi::Error)]
#[uniffi(flat_error)]
pub enum InvalidJwk {
    #[error("Invalid JWK syntax")]
    Syntax,
}

/// JSON Web Key.
///
/// The lock is `std`'s, not an async one: it guards plain data, and the
/// accessors are called synchronously from foreign code on arbitrary threads —
/// including threads inside a tokio runtime, where a tokio `blocking_read`
/// panics by design.
#[derive(Debug, uniffi::Object)]
#[uniffi::export(Display, Eq)]
pub struct Jwk(pub(crate) RwLock<ssi::JWK>);

impl Jwk {
    /// Read the key, recovering from lock poisoning instead of panicking.
    ///
    /// Poisoning only means another thread panicked while holding the guard.
    /// Every guarded operation on this type is a whole-value read or a single
    /// field assignment, so the key cannot be left torn and recovering is
    /// safe. Propagating instead would turn one unrelated panic into a crash
    /// of every later accessor at the FFI surface, where these are infallible.
    pub(crate) fn inner(&self) -> RwLockReadGuard<'_, ssi::JWK> {
        self.0.read().unwrap_or_else(PoisonError::into_inner)
    }

    /// Write the key; see [`Self::inner`] for why poisoning is recovered.
    fn inner_mut(&self) -> RwLockWriteGuard<'_, ssi::JWK> {
        self.0.write().unwrap_or_else(PoisonError::into_inner)
    }
}

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
        self.inner().key_id.clone()
    }

    /// Sets key identifier (`kid` parameter) value.
    pub fn set_kid(&self, kid: Option<String>) {
        self.inner_mut().key_id = kid
    }

    /// Returns a copy of this JWK.
    pub fn copy(&self) -> Self {
        Self(RwLock::new(self.inner().clone()))
    }
}

impl fmt::Display for Jwk {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        self.inner().fmt(f)
    }
}

impl PartialEq for Jwk {
    fn eq(&self, other: &Self) -> bool {
        self.inner().eq(&*other.inner())
    }
}

impl Eq for Jwk {}

impl Hash for Jwk {
    fn hash<H: std::hash::Hasher>(&self, state: &mut H) {
        self.inner().hash(state);
    }
}

#[uniffi::export]
pub fn jwk_from_public_p256(x: Vec<u8>, y: Vec<u8>) -> Jwk {
    let mut jwk = ssi::JWK::from(ssi::jwk::Params::EC(ssi::jwk::ECParams {
        curve: Some("P-256".to_owned()),
        x_coordinate: Some(ssi::jwk::Base64urlUInt(x)),
        y_coordinate: Some(ssi::jwk::Base64urlUInt(y)),
        ecc_private_key: None,
    }));
    jwk.algorithm = Some(ssi::jwk::Algorithm::ES256);
    jwk.into()
}

impl From<ssi::JWK> for Jwk {
    fn from(value: ssi::JWK) -> Self {
        Self(RwLock::new(value))
    }
}
