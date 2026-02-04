use ssi::claims::jws::InvalidJws;

mod signature;

pub use signature::*;

/// JSON Web Signature.
#[derive(Debug, PartialEq, Eq)]
pub struct Jws(ssi::claims::jws::JwsBuf);

impl From<ssi::claims::jws::JwsBuf> for Jws {
    fn from(value: ssi::claims::jws::JwsBuf) -> Self {
        Self(value)
    }
}

impl From<Jws> for ssi::claims::jws::JwsBuf {
    fn from(value: Jws) -> Self {
        value.0
    }
}

impl From<Jws> for String {
    fn from(value: Jws) -> Self {
        value.0.into_string()
    }
}

impl TryFrom<String> for Jws {
    type Error = InvalidJws;

    fn try_from(value: String) -> Result<Self, InvalidJws> {
        ssi::claims::jws::JwsBuf::try_from(value).map(Self)
    }
}

uniffi::custom_type!(Jws, String);
