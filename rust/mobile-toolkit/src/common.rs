uniffi::custom_newtype!(Key, String);

#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub struct Key(pub String);

impl Key {
    /// Create a new key with a prefix
    pub fn with_prefix(prefix: &str, key: &str) -> Self {
        Self(format!("{prefix}{key}"))
    }

    /// Strip the prefix from the key, returning the key without the prefix
    pub fn strip_prefix(&self, prefix: &str) -> Option<String> {
        self.0.split_once(prefix).map(|(_, after)| after.to_owned())
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

#[derive(Debug, Clone, PartialEq)]
pub struct Value(pub Vec<u8>);
