use std::{cmp::Ordering, collections::HashMap, ops::Deref, sync::Arc};

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

impl std::fmt::Display for CborValue {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            CborValue::Null => write!(f, ""),
            CborValue::Bool(v) => write!(f, "{}", v),
            CborValue::Integer(cbor_integer) => write!(f, "{}", cbor_integer.to_text()),
            CborValue::Float(v) => write!(f, "{}", v),
            CborValue::Bytes(items) => items.iter().enumerate().try_fold((), |_, (i, item)| {
                if i > 0 {
                    write!(f, ",")?;
                }
                write!(f, "{}", item)
            }),
            CborValue::Text(v) => write!(f, "{}", v),
            CborValue::Array(cbor_values) => {
                cbor_values
                    .iter()
                    .enumerate()
                    .try_fold((), |_, (i, value)| {
                        if i > 0 {
                            write!(f, ",")?;
                        }
                        write!(f, "{}", value)
                    })
            }
            CborValue::ItemMap(hash_map) => {
                write!(f, "{{")?;
                hash_map.iter().enumerate().try_fold((), |_, (i, (k, v))| {
                    if i > 0 {
                        write!(f, ",")?;
                    }
                    write!(f, r#""{}":"{}""#, k, v)
                })?;
                write!(f, "}}")
            }
            CborValue::Tag(cbor_tag) => write!(f, "{}", cbor_tag.value()),
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
                    .map(|(k, v)| (CborValue::from(k).to_string(), v.into()))
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

// CBOR key constants - generic names for reusability
pub mod cbor_keys {
    // Standard CBOR claims
    pub const ISSUER: i128 = 1;
    pub const EXPIRES: i128 = 4;
    pub const NOT_BEFORE: i128 = 5;
    pub const ISSUED: i128 = 6;

    // Identity/Personal Info (70001-70010)
    pub const FULL_NAME: i128 = -70001;
    pub const EMAIL: i128 = -70002;
    pub const COMPANY: i128 = -70003;

    // Birth Certificate fields (70011-70020)
    pub const BIRTH_CERT_NUMBER: i128 = -70011;
    pub const GIVEN_NAMES: i128 = -70012;
    pub const FAMILY_NAME: i128 = -70013;
    pub const BIRTH_DATE: i128 = -70014;
    pub const SEX: i128 = -70015;
    pub const BIRTH_LOCALITY: i128 = -70016;
    pub const COUNTY_FIPS_CODE: i128 = -70017;
    pub const MOTHER: i128 = -70018;
    pub const FATHER: i128 = -70019;
    pub const REGISTRATION_DATE: i128 = -70020;
}

/// Bidirectional mapping between CBOR keys and human-readable strings
pub struct CborKeyMapper;

impl CborKeyMapper {
    /// Convert CBOR integer key to human-readable string
    pub fn key_to_string(key: i128) -> String {
        match key {
            // Standard CBOR claims
            cbor_keys::ISSUER => "Issuer".to_string(),
            cbor_keys::EXPIRES => "Expires".to_string(),
            cbor_keys::NOT_BEFORE => "Not Before".to_string(),
            cbor_keys::ISSUED => "Issued".to_string(),

            // Identity/Personal Info
            cbor_keys::FULL_NAME => "Full Name".to_string(),
            cbor_keys::EMAIL => "Email".to_string(),
            cbor_keys::COMPANY => "Company".to_string(),

            // Birth Certificate fields
            cbor_keys::BIRTH_CERT_NUMBER => "birthCertificateNumber".to_string(),
            cbor_keys::GIVEN_NAMES => "Given Names".to_string(),
            cbor_keys::FAMILY_NAME => "Family Name".to_string(),
            cbor_keys::BIRTH_DATE => "Birth Date".to_string(),
            cbor_keys::SEX => "Sex".to_string(),
            cbor_keys::BIRTH_LOCALITY => "Birth Locality".to_string(),
            cbor_keys::COUNTY_FIPS_CODE => "County FIPS Code".to_string(),
            cbor_keys::MOTHER => "Mother".to_string(),
            cbor_keys::FATHER => "Father".to_string(),
            cbor_keys::REGISTRATION_DATE => "Registration Date".to_string(),

            _ => key.to_string(),
        }
    }

    /// Convert human-readable string to CBOR integer key (if exists)
    pub fn string_to_key(key_str: &str) -> Option<i128> {
        match key_str {
            // Standard CBOR claims
            "Expires" => Some(cbor_keys::EXPIRES),
            "Not Before" => Some(cbor_keys::NOT_BEFORE),
            "Issued" => Some(cbor_keys::ISSUED),

            // Identity/Personal Info
            "Full Name" => Some(cbor_keys::FULL_NAME),
            "Email" => Some(cbor_keys::EMAIL),
            "Company" => Some(cbor_keys::COMPANY),

            // Birth Certificate fields
            "Birth Certificate Number" => Some(cbor_keys::BIRTH_CERT_NUMBER),
            "Given Names" => Some(cbor_keys::GIVEN_NAMES),
            "Family Name" => Some(cbor_keys::FAMILY_NAME),
            "Birth Date" => Some(cbor_keys::BIRTH_DATE),
            "Sex" => Some(cbor_keys::SEX),
            "Birth Locality" => Some(cbor_keys::BIRTH_LOCALITY),
            "County FIPS Code" => Some(cbor_keys::COUNTY_FIPS_CODE),
            "Mother" => Some(cbor_keys::MOTHER),
            "Father" => Some(cbor_keys::FATHER),
            "Registration Date" => Some(cbor_keys::REGISTRATION_DATE),

            _ => None,
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

    #[test]
    fn test_cbor_key_mapping_bidirectional() {
        // Test key to string
        assert_eq!(
            CborKeyMapper::key_to_string(cbor_keys::FULL_NAME),
            "Full Name"
        );
        assert_eq!(CborKeyMapper::key_to_string(cbor_keys::EMAIL), "Email");
        assert_eq!(
            CborKeyMapper::key_to_string(cbor_keys::BIRTH_DATE),
            "Birth Date"
        );

        // Test string to key
        assert_eq!(
            CborKeyMapper::string_to_key("Full Name"),
            Some(cbor_keys::FULL_NAME)
        );
        assert_eq!(
            CborKeyMapper::string_to_key("Email"),
            Some(cbor_keys::EMAIL)
        );
        assert_eq!(
            CborKeyMapper::string_to_key("Birth Date"),
            Some(cbor_keys::BIRTH_DATE)
        );

        // Test unknown key
        assert_eq!(CborKeyMapper::string_to_key("Unknown"), None);
        assert_eq!(CborKeyMapper::key_to_string(999), "999");
    }

    #[test]
    fn test_standard_cbor_keys() {
        // Test standard CBOR keys
        assert_eq!(CborKeyMapper::key_to_string(cbor_keys::EXPIRES), "Expires");
        assert_eq!(
            CborKeyMapper::key_to_string(cbor_keys::NOT_BEFORE),
            "Not Before"
        );
        assert_eq!(CborKeyMapper::key_to_string(cbor_keys::ISSUED), "Issued");

        // Test reverse mapping
        assert_eq!(
            CborKeyMapper::string_to_key("Expires"),
            Some(cbor_keys::EXPIRES)
        );
        assert_eq!(
            CborKeyMapper::string_to_key("Not Before"),
            Some(cbor_keys::NOT_BEFORE)
        );
        assert_eq!(
            CborKeyMapper::string_to_key("Issued"),
            Some(cbor_keys::ISSUED)
        );

        // Verify the actual values match CBOR spec
        assert_eq!(cbor_keys::EXPIRES, 4);
        assert_eq!(cbor_keys::NOT_BEFORE, 5);
        assert_eq!(cbor_keys::ISSUED, 6);
    }
}
