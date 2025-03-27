use cbor_ld::EncodeError;
use json_syntax::Parse;
use ssi::json_ld::{InvalidIri, IriBuf, NoLoader, RemoteDocument};
use std::{collections::HashMap, str::FromStr};

#[derive(Debug, uniffi::Error, thiserror::Error)]
pub enum CborLdEncodingError {
    #[error("JsonLD parsing error: {0}")]
    JsonParse(String),

    #[error("CborLD encode error: {0}")]
    CborEncode(String),
}

impl From<InvalidIri<String>> for CborLdEncodingError {
    fn from(value: InvalidIri<String>) -> Self {
        Self::CborEncode(format!("ssi::json_ld::InvalidIri: {value}"))
    }
}

impl From<EncodeError> for CborLdEncodingError {
    fn from(value: EncodeError) -> Self {
        Self::CborEncode(format!("cbor_ld::EncodeError: {value}"))
    }
}

impl From<ssi::json_ld::syntax::parse::Error> for CborLdEncodingError {
    fn from(value: ssi::json_ld::syntax::parse::Error) -> Self {
        Self::JsonParse(format!("json_ld::syntax::parse::Error: {value}",))
    }
}

#[uniffi::export]
pub async fn cbor_ld_encode_to_bytes(
    credential_str: String,
    loader: Option<HashMap<String, String>>,
) -> Result<Vec<u8>, CborLdEncodingError> {
    let credential = cbor_ld::JsonValue::from_str(&credential_str)?;

    let cborld = if let Some(map) = loader {
        let loader = map
            .into_iter()
            .map(
                |(k, v)| match (IriBuf::new(k), json_syntax::Value::parse_str(&v)) {
                    (Ok(k), Ok((v, _))) => Ok((
                        k.to_owned(),
                        RemoteDocument::new(
                            Some(k),
                            Some("application/ld+json".parse().unwrap()),
                            v,
                        ),
                    )),
                    (Err(e), _) => Err(e.into()),
                    (_, Err(e)) => Err(e.into()),
                },
            )
            .collect::<Result<HashMap<IriBuf, RemoteDocument<IriBuf>>, CborLdEncodingError>>()?;

        cbor_ld::encode_to_bytes(&credential, loader).await?
    } else {
        cbor_ld::encode_to_bytes(&credential, NoLoader).await?
    };

    Ok(cborld)
}
