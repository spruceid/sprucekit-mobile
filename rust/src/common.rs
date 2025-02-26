use serde::{Deserialize, Serialize};
use ssi::{claims::data_integrity::CryptosuiteString, crypto::Algorithm};
use uniffi::deps::anyhow;
use url::Url;
use uuid::Uuid;

uniffi::custom_newtype!(CredentialType, String);
#[derive(Debug, Serialize, Deserialize, PartialEq, Clone)]
pub struct CredentialType(pub String);

impl From<String> for CredentialType {
    fn from(s: String) -> Self {
        Self(s)
    }
}

impl From<CredentialType> for String {
    fn from(cred_type: CredentialType) -> Self {
        cred_type.0
    }
}

uniffi::custom_type!(Uuid, String, {
    remote,
    try_lift: |uuid| Ok(uuid.parse()?),
    lower: |uuid| uuid.to_string(),
});

uniffi::custom_type!(Url, String, {
    remote,
    try_lift: |url|  Ok(Url::parse(&url)?),
    lower: |url| url.to_string(),
});

uniffi::custom_newtype!(Key, String);

#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub struct Key(pub String);

impl Key {
    /// Create a new key with a prefix
    pub fn with_prefix(prefix: &str, key: &str) -> Self {
        Self(format!("{}{}", prefix, key))
    }

    /// Strip the prefix from the key, returning the key without the prefix
    pub fn strip_prefix(&self, prefix: &str) -> Option<String> {
        self.0.strip_prefix(prefix).map(ToOwned::to_owned)
    }
}

impl From<Key> for String {
    fn from(key: Key) -> Self {
        key.0
    }
}

impl From<String> for Key {
    fn from(key: String) -> Self {
        Self(key)
    }
}

impl From<&str> for Key {
    fn from(key: &str) -> Self {
        Self(key.to_string())
    }
}

uniffi::custom_newtype!(Value, Vec<u8>);

#[derive(Debug, PartialEq)]
pub struct Value(pub Vec<u8>);

uniffi::custom_type!(Algorithm, String, {
    remote,
    try_lift: |alg| {
match alg.as_ref() {
    "ES256" => Ok(Algorithm::ES256),
    "ES256K" => Ok(Algorithm::ES256K),
    _ => anyhow::bail!("unsupported uniffi custom type for Algorithm mapping: {alg}"),
}
    },
    lower: |alg| alg.to_string(),
});

uniffi::custom_type!(CryptosuiteString, String, {
    remote,
    try_lift: |suite| {
        CryptosuiteString::new(suite)
            .map_err(|e| uniffi::deps::anyhow::anyhow!("failed to create cryptosuite: {e:?}"))
    },
    lower: |suite| suite.to_string(),
});
