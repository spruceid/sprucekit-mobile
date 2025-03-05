use std::{cmp::Ordering, collections::HashMap, ops::Deref, sync::Arc};

use itertools::Itertools;
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

#[derive(uniffi::Object, Debug, Clone)]
pub struct CborTag {
    id: u64,
    value: Box<CborValue>,
}

#[uniffi::export]
impl CborTag {
    pub fn id(&self) -> u64 {
        self.id
    }

    pub fn value(&self) -> CborValue {
        *self.value.clone()
    }
}

impl From<(u64, serde_cbor::Value)> for CborTag {
    fn from(value: (u64, serde_cbor::Value)) -> Self {
        Self {
            id: value.0,
            value: Box::new(value.1.into()),
        }
    }
}

impl ToString for CborValue {
    fn to_string(&self) -> String {
        match self {
            CborValue::Null => "".to_string(),
            CborValue::Bool(v) => v.to_string(),
            CborValue::Integer(cbor_integer) => cbor_integer.to_text(),
            CborValue::Float(v) => v.to_string(),
            CborValue::Bytes(items) => items.iter().map(|i| i.to_string()).join(","),
            CborValue::Text(v) => v.to_string(),
            CborValue::Array(cbor_values) => cbor_values.iter().map(|i| i.to_string()).join(","),
            CborValue::ItemMap(hash_map) => serde_json::to_string(
                &hash_map
                    .iter()
                    .map(|(k, v)| (k, v.to_string()))
                    .collect::<HashMap<_, _>>(),
            )
            .unwrap_or("{}".to_string()),
            CborValue::Tag(cbor_tag) => cbor_tag.value().to_string(),
        }
    }
}

#[derive(uniffi::Object, Debug, Clone)]
pub struct CborInteger {
    bytes: Vec<u8>,
}

#[uniffi::export]
impl CborInteger {
    pub fn lower_bytes(&self) -> u64 {
        self.bytes[8..16]
            .iter()
            .rev()
            .enumerate()
            .fold(0, |acc, (i, value)| acc | ((*value as u64) << (i * 8)))
    }

    pub fn upper_bytes(&self) -> u64 {
        self.bytes[0..8]
            .iter()
            .rev()
            .enumerate()
            .fold(0, |acc, (i, value)| acc | ((*value as u64) << (i * 8)))
    }

    pub fn to_text(&self) -> String {
        let lower = self.lower_bytes();
        let upper = self.upper_bytes();

        // Safety: we are doing all the operations from splitting to joining
        unsafe { std::mem::transmute::<u128, i128>(((upper as u128) << 64) | (lower as u128)) }
            .to_string()
    }
}

impl From<i128> for CborInteger {
    fn from(value: i128) -> Self {
        Self {
            bytes: vec![
                (value >> 120) as u8,
                (value >> 112) as u8,
                (value >> 104) as u8,
                (value >> 96) as u8,
                (value >> 88) as u8,
                (value >> 80) as u8,
                (value >> 72) as u8,
                (value >> 64) as u8,
                (value >> 56) as u8,
                (value >> 48) as u8,
                (value >> 40) as u8,
                (value >> 32) as u8,
                (value >> 24) as u8,
                (value >> 16) as u8,
                (value >> 8) as u8,
                (value) as u8,
            ],
        }
    }
}

impl From<CborInteger> for i128 {
    fn from(value: CborInteger) -> Self {
        i128::from_be_bytes(value.bytes.try_into().unwrap_or([0; 16]))
    }
}

#[derive(uniffi::Enum, Debug, Clone)]
pub enum CborValue {
    Null,
    Bool(bool),
    Integer(Arc<CborInteger>),
    Float(f64),
    Bytes(Vec<u8>),
    Text(String),
    Array(Vec<CborValue>),
    ItemMap(HashMap<String, CborValue>),
    Tag(Arc<CborTag>),
}

impl From<serde_cbor::Value> for CborValue {
    fn from(value: serde_cbor::Value) -> Self {
        match value {
            serde_cbor::Value::Null => Self::Null,
            serde_cbor::Value::Bool(b) => Self::Bool(b),
            serde_cbor::Value::Integer(v) => Self::Integer(Arc::new(v.into())),
            serde_cbor::Value::Float(v) => Self::Float(v),
            serde_cbor::Value::Bytes(b) => Self::Bytes(b),
            serde_cbor::Value::Text(s) => Self::Text(s),
            serde_cbor::Value::Array(a) => {
                Self::Array(a.iter().map(|o| Into::<Self>::into(o.clone())).collect())
            }
            serde_cbor::Value::Map(m) => Self::ItemMap(
                m.into_iter()
                    .map(|(k, v)| {
                        (
                            match k {
                                serde_cbor::Value::Null => "".to_string(),
                                serde_cbor::Value::Bool(v) => v.to_string(),
                                serde_cbor::Value::Integer(v) => v.to_string(),
                                serde_cbor::Value::Float(v) => v.to_string(),
                                serde_cbor::Value::Bytes(items) => {
                                    String::from_utf8(items.to_vec()).unwrap_or("".to_string())
                                }
                                serde_cbor::Value::Text(v) => v.to_string(),
                                serde_cbor::Value::Array(values) => {
                                    values.iter().map(|i| format!("{:?}", i)).collect()
                                }
                                serde_cbor::Value::Map(btree_map) => {
                                    btree_map.iter().map(|t| format!("{:?}", t)).collect()
                                }
                                serde_cbor::Value::Tag(_, value) => format!("{:?}", *value),
                                _ => todo!(),
                            },
                            v.into(),
                        )
                    })
                    .collect::<HashMap<_, CborValue>>(),
            ),
            serde_cbor::Value::Tag(id, value) => Self::Tag(Arc::new((id, *value).into())),
            _ => Self::Null,
        }
    }
}

impl PartialEq for CborValue {
    fn eq(&self, other: &CborValue) -> bool {
        self.cmp(other) == Ordering::Equal
    }
}

impl Eq for CborValue {}

impl PartialOrd for CborValue {
    fn partial_cmp(&self, other: &CborValue) -> Option<Ordering> {
        Some(self.cmp(other))
    }
}

impl Ord for CborValue {
    fn cmp(&self, other: &CborValue) -> Ordering {
        use self::CborValue::*;
        if self.major_type() != other.major_type() {
            return self.major_type().cmp(&other.major_type());
        }
        match (self, other) {
            (Null, Null) => Ordering::Equal,
            (Bool(a), Bool(b)) => a.cmp(b),
            (Integer(a), Integer(b)) => {
                i128::from(a.deref().clone()).cmp(&i128::from(b.deref().clone()))
            }
            (Float(a), Float(b)) => a.partial_cmp(b).unwrap_or(Ordering::Equal),
            (Bytes(a), Bytes(b)) => a.cmp(b),
            (Text(a), Text(b)) => a.cmp(b),
            (Array(a), Array(b)) => a.iter().cmp(b.iter()),
            (ItemMap(a), ItemMap(b)) => a.len().cmp(&b.len()).then_with(|| a.iter().cmp(b.iter())),
            (Tag(a), Tag(b)) => a.id.cmp(&b.id).then_with(|| a.value.cmp(&b.value)),
            _ => unreachable!("major_type comparison should have caught this case"),
        }
    }
}

impl CborValue {
    fn major_type(&self) -> u8 {
        use self::CborValue::*;
        match self {
            Null => 7,
            Bool(_) => 7,
            Integer(v) => {
                if i128::from(v.as_ref().clone()) >= 0 {
                    0
                } else {
                    1
                }
            }
            Tag(_) => 6,
            Float(_) => 7,
            Bytes(_) => 2,
            Text(_) => 3,
            Array(_) => 4,
            ItemMap(_) => 5,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_cbor_integer_from_i128() {
        let test_cases = vec![
            0i128,
            1i128,
            -1i128,
            i128::MAX,
            i128::MIN,
            123456789i128,
            -123456789i128,
        ];

        for value in test_cases {
            let cbor_int = CborInteger::from(value);
            assert_eq!(cbor_int.to_text(), value.to_string());
        }
    }

    #[test]
    fn test_cbor_integer_byte_manipulation() {
        // Using full i128 value: 0x0123456789ABCDEFFEDCBA9876543210
        let value: i128 = 0x0123456789ABCDEF_FEDCBA9876543210_i128;
        let cbor_int = CborInteger::from(value);

        // Test lower_bytes (least significant 8 bytes)
        assert_eq!(cbor_int.lower_bytes(), 0xFEDCBA9876543210_u64);

        // Test upper_bytes (most significant 8 bytes)
        assert_eq!(cbor_int.upper_bytes(), 0x0123456789ABCDEF_u64);
    }

    #[test]
    fn test_cbor_integer_zero() {
        let zero = CborInteger::from(0i128);
        assert_eq!(zero.lower_bytes(), 0);
        assert_eq!(zero.upper_bytes(), 0);
        assert_eq!(zero.to_text(), "0");
    }

    #[test]
    fn test_cbor_integer_negative() {
        let negative = CborInteger::from(-42i128);
        assert_eq!(negative.to_text(), "-42");
    }

    #[test]
    fn test_cbor_integer_byte_length() {
        let value = CborInteger::from(0i128);
        assert_eq!(
            value.bytes.len(),
            16,
            "CborInteger should always have 16 bytes"
        );
    }

    #[test]
    fn test_cbor_value_ordering() {
        // Test major type ordering
        assert!(CborValue::Integer(Arc::new(0i128.into())) < CborValue::Bytes(vec![1]));
        assert!(CborValue::Text(String::from("a")) < CborValue::Array(vec![]));
        assert!(CborValue::Array(vec![]) < CborValue::ItemMap(HashMap::new()));

        // Test integer comparison
        assert!(
            CborValue::Integer(Arc::new(1i128.into())) < CborValue::Integer(Arc::new(2i128.into()))
        );
        assert_eq!(
            CborValue::Integer(Arc::new(1i128.into())),
            CborValue::Integer(Arc::new(1i128.into()))
        );

        // Test sequence ordering
        assert!(CborValue::Bytes(vec![1]) < CborValue::Bytes(vec![1, 2]));
        assert!(CborValue::Text("a".into()) < CborValue::Text("b".into()));
    }

    #[test]
    fn test_cbor_value_to_string() {
        // Test null
        assert_eq!(CborValue::Null.to_string(), "");

        // Test boolean
        assert_eq!(CborValue::Bool(true).to_string(), "true");
        assert_eq!(CborValue::Bool(false).to_string(), "false");

        // Test integer
        assert_eq!(
            CborValue::Integer(Arc::new(42i128.into())).to_string(),
            "42"
        );
        assert_eq!(
            CborValue::Integer(Arc::new((-42i128).into())).to_string(),
            "-42"
        );

        // Test float
        assert_eq!(CborValue::Float(3.14).to_string(), "3.14");

        // Test bytes
        assert_eq!(CborValue::Bytes(vec![65, 66, 67]).to_string(), "65,66,67");

        // Test text
        assert_eq!(CborValue::Text("hello".into()).to_string(), "hello");

        // Test array
        assert_eq!(
            CborValue::Array(vec![
                CborValue::Integer(Arc::new(1i128.into())),
                CborValue::Text("two".into())
            ])
            .to_string(),
            "1,two"
        );

        // Test map
        let mut map = HashMap::new();
        map.insert("key".to_string(), CborValue::Text("value".into()));
        assert_eq!(CborValue::ItemMap(map).to_string(), r#"{"key":"value"}"#);

        // Test tag
        let tag = CborTag {
            id: 1,
            value: Box::new(CborValue::Text("tagged".into())),
        };
        assert_eq!(CborValue::Tag(Arc::new(tag)).to_string(), "tagged");
    }
}
