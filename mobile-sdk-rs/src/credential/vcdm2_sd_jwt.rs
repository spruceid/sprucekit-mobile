use super::{
    status::{BitStringStatusListResolver, Status, StatusListError},
    Credential, CredentialFormat, ParsedCredential, ParsedCredentialInner,
};
use crate::{
    oid4vp::{
        error::OID4VPError,
        presentation::{CredentialPresentation, PresentationOptions},
    },
    CredentialType, KeyAlias,
};

use std::sync::Arc;

use openid4vp::{
    core::{
        credential_format::ClaimFormatDesignation, presentation_submission::DescriptorMap,
        response::parameters::VpTokenItem,
    },
    JsonPath,
};
use reqwest::StatusCode;
use ssi::status::bitstring_status_list::{BitstringStatusListCredential, BitstringStatusListEntry};
use ssi::{
    claims::{
        sd_jwt::SdJwtBuf,
        vc::v2::{Credential as _, JsonCredential},
        vc_jose_cose::SdJwtVc,
    },
    prelude::AnyJsonCredential,
};
use url::Url;
use uuid::Uuid;

#[derive(Debug, uniffi::Object)]
pub struct VCDM2SdJwt {
    pub(crate) id: Uuid,
    pub(crate) key_alias: Option<KeyAlias>,
    pub(crate) credential: JsonCredential,
    pub(crate) inner: SdJwtBuf,
}

// Internal utility methods for decoding a SdJwt.
impl VCDM2SdJwt {
    /// Return the revealed claims as a JSON value.
    pub fn revealed_claims_as_json(&self) -> Result<serde_json::Value, SdJwtError> {
        serde_json::to_value(&self.credential)
            .map_err(|e| SdJwtError::Serialization(format!("{e:?}")))
    }

    /// The types of the credential from the VCDM, excluding the base `VerifiableCredential` type.
    pub fn types(&self) -> Vec<String> {
        self.credential.additional_types().to_vec()
    }

    /// Returns the SD-JWT credential as an AnyCredential type.
    pub fn credential(&self) -> Result<AnyJsonCredential, SdJwtError> {
        // NOTE: Due to the type constraints on AnyJsonCredential, we're
        // reserializing the type into a V2 credential.
        serde_json::to_value(&self.credential)
            .map_err(|e| SdJwtError::Serialization(format!("{e:?}")))
            .and_then(|v| {
                serde_json::from_value(v).map_err(|e| SdJwtError::Serialization(format!("{e:?}")))
            })
    }

    fn format() -> CredentialFormat {
        CredentialFormat::VCDM2SdJwt
    }
}

#[uniffi::export(async_runtime = "tokio")]
impl VCDM2SdJwt {
    /// Create a new SdJwt instance from a compact SD-JWS string.
    #[uniffi::constructor]
    pub fn new_from_compact_sd_jwt(input: String) -> Result<Arc<Self>, SdJwtError> {
        let inner: SdJwtBuf =
            SdJwtBuf::new(input).map_err(|e| SdJwtError::InvalidSdJwt(format!("{e:?}")))?;

        let mut sd_jwt = VCDM2SdJwt::try_from(inner)?;
        sd_jwt.key_alias = None;

        Ok(Arc::new(sd_jwt))
    }

    /// Create a new SdJwt instance from a compact SD-JWS string with a provided key alias.
    #[uniffi::constructor]
    pub fn new_from_compact_sd_jwt_with_key(
        input: String,
        key_alias: KeyAlias,
    ) -> Result<Arc<Self>, SdJwtError> {
        let inner: SdJwtBuf =
            SdJwtBuf::new(input).map_err(|e| SdJwtError::InvalidSdJwt(format!("{e:?}")))?;

        let mut sd_jwt = VCDM2SdJwt::try_from(inner)?;
        sd_jwt.key_alias = Some(key_alias);

        Ok(Arc::new(sd_jwt))
    }

    /// Return the ID for the SdJwt instance.
    pub fn id(&self) -> Uuid {
        self.id
    }

    /// Return the key alias for the credential
    pub fn key_alias(&self) -> Option<KeyAlias> {
        self.key_alias.clone()
    }

    /// The type of this credential. Note that if there is more than one type (i.e. `types()`
    /// returns more than one value), then the types will be concatenated with a "+".
    pub fn r#type(&self) -> CredentialType {
        CredentialType(self.types().join("+"))
    }

    /// Return the revealed claims as a UTF-8 encoded JSON string.
    pub fn revealed_claims_as_json_string(&self) -> Result<String, SdJwtError> {
        serde_json::to_string(&self.credential)
            .map_err(|e| SdJwtError::Serialization(format!("{e:?}")))
    }

    /// Returns the status of the credential, resolving the value in the status list,
    /// along with the purpose of the status.
    pub async fn status(&self) -> Result<Status, StatusListError> {
        self.status_list_value().await
    }
}

impl CredentialPresentation for VCDM2SdJwt {
    type Credential = ssi::claims::vc::v2::SpecializedJsonCredential;
    type CredentialFormat = ClaimFormatDesignation;
    type PresentationFormat = ClaimFormatDesignation;

    fn credential(&self) -> &Self::Credential {
        &self.credential
    }

    fn presentation_format(&self) -> Self::PresentationFormat {
        ClaimFormatDesignation::Other(Self::format().to_string())
    }

    fn credential_format(&self) -> Self::CredentialFormat {
        ClaimFormatDesignation::Other(Self::format().to_string())
    }

    /// Return the credential as a VpToken
    async fn as_vp_token_item<'a>(
        &self,
        _options: &'a PresentationOptions<'a>,
    ) -> Result<VpTokenItem, OID4VPError> {
        // TODO: need to provide the "filtered" (disclosed) fields of the
        // credential to be encoded into the VpToken.
        //
        // Currently, this is encoding the entire revealed SD-JWT,
        // without the selection of individual disclosed fields.
        //
        // We need to selectively disclosed fields.
        let compact: &str = self.inner.as_ref();
        Ok(VpTokenItem::String(compact.to_string()))
    }

    fn create_descriptor_map(
        &self,
        input_descriptor_id: impl Into<String>,
        index: Option<usize>,
    ) -> Result<DescriptorMap, OID4VPError> {
        let path = match index {
            None => JsonPath::default(),
            Some(i) => format!("$[{i}]")
                .parse()
                .map_err(|e| OID4VPError::JsonPathParse(format!("{e:?}")))?,
        };

        Ok(DescriptorMap::new(
            input_descriptor_id,
            self.credential_format(),
            path,
        ))
    }
}

#[async_trait::async_trait]
impl BitStringStatusListResolver for VCDM2SdJwt {
    fn status_list_entry(&self) -> Result<BitstringStatusListEntry, StatusListError> {
        let value = match &self
            .credential()
            .map_err(|e| StatusListError::Resolution(format!("{e:?}")))?
        {
            AnyJsonCredential::V1(credential) => credential
                .credential_status
                .first()
                .map(serde_json::to_value),
            AnyJsonCredential::V2(credential) => credential
                .credential_status
                .first()
                .map(serde_json::to_value),
        }
        .ok_or(StatusListError::Resolution(
            "Credential status not found in credential".into(),
        ))?
        .map_err(|e| StatusListError::Resolution(format!("{e:?}")))?;

        let entry = serde_json::from_value(value).map_err(|e| {
            StatusListError::Resolution(format!("Failed to parse credential status: {e:?}"))
        })?;

        Ok(entry)
    }

    async fn status_list_credential(
        &self,
    ) -> Result<BitstringStatusListCredential, StatusListError> {
        let entry = self.status_list_entry()?;
        let url: Url = entry
            .status_list_credential
            .parse()
            .map_err(|e| StatusListError::Resolution(format!("{e:?}")))?;

        let response = reqwest::get(url)
            .await
            .map_err(|e| StatusListError::Resolution(format!("{e:?}")))?;

        if response.status() != StatusCode::OK {
            return Err(StatusListError::Resolution(format!(
                "Failed to resolve status list credential: {}",
                response.status()
            )));
        }

        let sd_jwt_buf = SdJwtBuf::new(
            response
                .text()
                .await
                .map_err(|e| StatusListError::Resolution(format!("{e:?}")))?,
        )
        .map_err(|e| StatusListError::Resolution(format!("{e:?}")))?;

        let credential = sd_jwt_buf
            .decode_reveal_any()
            .map_err(|e| StatusListError::Resolution(format!("{e:?}")))?
            .into_claims()
            .private;

        serde_json::from_value(
            serde_json::to_value(credential)
                .map_err(|e| StatusListError::Resolution(format!("{e:?}")))?,
        )
        .map_err(|e| StatusListError::Resolution(format!("{e:?}")))
    }

    // NOTE: The remaining methods are default implemented in the trait.
}

impl From<VCDM2SdJwt> for ParsedCredential {
    fn from(value: VCDM2SdJwt) -> Self {
        ParsedCredential {
            inner: ParsedCredentialInner::VCDM2SdJwt(Arc::new(value)),
        }
    }
}

impl TryFrom<VCDM2SdJwt> for Credential {
    type Error = SdJwtError;

    fn try_from(value: VCDM2SdJwt) -> Result<Self, Self::Error> {
        ParsedCredential::from(value)
            .into_generic_form()
            .map_err(|e| SdJwtError::CredentialEncoding(format!("{e:?}")))
    }
}

impl TryFrom<Arc<VCDM2SdJwt>> for Credential {
    type Error = SdJwtError;

    fn try_from(value: Arc<VCDM2SdJwt>) -> Result<Self, Self::Error> {
        ParsedCredential::new_sd_jwt(value)
            .into_generic_form()
            .map_err(|e| SdJwtError::CredentialEncoding(format!("{e:?}")))
    }
}

impl TryFrom<&Credential> for VCDM2SdJwt {
    type Error = SdJwtError;

    fn try_from(value: &Credential) -> Result<VCDM2SdJwt, SdJwtError> {
        let inner = SdJwtBuf::new(value.payload.clone())
            .map_err(|_| SdJwtError::InvalidSdJwt(Default::default()))?;

        let mut sd_jwt = VCDM2SdJwt::try_from(inner)?;
        // Set the ID and key alias from the credential.
        sd_jwt.id = value.id;
        sd_jwt.key_alias = value.key_alias.clone();

        Ok(sd_jwt)
    }
}

impl TryFrom<Credential> for Arc<VCDM2SdJwt> {
    type Error = SdJwtError;

    fn try_from(value: Credential) -> Result<Arc<VCDM2SdJwt>, SdJwtError> {
        Ok(Arc::new(VCDM2SdJwt::try_from(&value)?))
    }
}

impl TryFrom<SdJwtBuf> for VCDM2SdJwt {
    type Error = SdJwtError;

    fn try_from(value: SdJwtBuf) -> Result<Self, Self::Error> {
        let SdJwtVc(credential) = SdJwtVc::decode_reveal_any(&value)
            .map_err(|e| SdJwtError::SdJwtDecoding(format!("{e:?}")))?
            .into_claims()
            .private;

        Ok(VCDM2SdJwt {
            id: Uuid::new_v4(),
            key_alias: None,
            inner: value,
            credential,
        })
    }
}

#[uniffi::export]
pub fn decode_reveal_sd_jwt(input: String) -> Result<String, SdJwtError> {
    let jwt: SdJwtBuf =
        SdJwtBuf::new(input).map_err(|e| SdJwtError::InvalidSdJwt(format!("{e:?}")))?;
    let SdJwtVc(vc) = SdJwtVc::decode_reveal_any(&jwt)
        .map_err(|e| SdJwtError::SdJwtDecoding(format!("{e:?}")))?
        .into_claims()
        .private;
    serde_json::to_string(&vc).map_err(|e| SdJwtError::Serialization(format!("{e:?}")))
}

#[derive(Debug, uniffi::Error, thiserror::Error)]
pub enum SdJwtError {
    #[error("failed to initialize SD-JWT: {0}")]
    SdJwtVcInitError(String),
    #[error("failed to decode SD-JWT as a JWT: {0}")]
    SdJwtDecoding(String),
    #[error("invalid SD-JWT: {0}")]
    InvalidSdJwt(String),
    #[error("serialization error: {0}")]
    Serialization(String),
    #[error("failed to encode SD-JWT: {0}")]
    CredentialEncoding(String),
    #[error("'vc' is missing from the SD-JWT decoded claims")]
    CredentialClaimMissing,
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;

    use ssi::{claims::sd_jwt::SdAlg, json_pointer, JWK};

    #[test]
    fn test_decode_static() {
        // Example SD-JWT input (you should replace this with a real SD-JWT string for a proper test)
        let sd_jwt_input = include_str!("../../tests/examples/sd_vc.jwt");

        // Call the function with the SD-JWT input
        let output =
            decode_reveal_sd_jwt(sd_jwt_input.to_string()).expect("failed to decode SD-JWT");

        // Check the output JSON string structure
        assert!(output.contains("\"identityHash\":\"john.smith@spruce.com\""));
        assert!(output.contains("\"awardedDate\":\"2024-10-23T09:34:30+0000\""));
    }

    pub async fn generate_sd_jwt() -> SdJwtBuf {
        // Define the key (this is a private key; for testing purposes you can use this inline or generate one)
        let jwk: JWK = JWK::generate_ed25519().expect("unable to generate sd-jwt");

        // Create the JWT claims
        let registeredclaims = serde_json::json!({"@context": [
            "https://www.w3.org/ns/credentials/v2",
            "https://purl.imsglobal.org/spec/ob/v3p0/context-3.0.3.json"
          ],
          "awardedDate": "2024-09-23T18:12:12+0000",
          "credentialSubject": {
            "identity": [
              {
                "hashed": false,
                "identityHash": "John Smith",
                "identityType": "name",
                "salt": "not-used",
                "type": "IdentityObject"
              },
              {
                "hashed": false,
                "identityHash": "john.smith@example.com",
                "identityType": "emailAddress",
                "salt": "not-used",
                "type": "IdentityObject"
              }
            ],
            "achievement": {
              "name": "Team Membership",
              "type": "Achievement"
            }
          },
          "issuer": {
            "id": "did:jwk:eyJhbGciOiJFUzI1NiIsImNydiI6IlAtMjU2Iiwia3R5IjoiRUMiLCJ4IjoibWJUM2dqOWFvOGNuS280M0prcVRPUmNJQVI4MFgwTUFXQWNGYzZvR1JMYyIsInkiOiJiOFVOY0hDMmFHQ3J1STZ0QlRWSVY0dW5ZWEVyS0M4ZDRnRTFGZ0s0Q05JIn0#0",
            "name": "Workforce Development Council",
            "type": "Profile"
          },
          "name": "TeamMembership",
          "type": ["VerifiableCredential", "OpenBadgeCredential"]
        });

        let claims: SdJwtVc = serde_json::from_value(registeredclaims).unwrap();
        let my_pointer = json_pointer!("/credentialSubject/identity/0");

        claims
            .conceal_and_sign(SdAlg::Sha256, &[my_pointer], &jwk)
            .await
            .unwrap()
    }

    #[tokio::test]
    async fn test_sd_jwt() -> Result<(), SdJwtError> {
        let input = generate_sd_jwt().await;

        assert!(VCDM2SdJwt::new_from_compact_sd_jwt(input.to_string()).is_ok());

        Ok(())
    }

    #[tokio::test]
    async fn test_decode_gen() -> Result<(), SdJwtError> {
        // Example SD-JWT input (you should replace this with a real SD-JWT string for a proper test)
        let sd_jwt_input = generate_sd_jwt().await;

        // Call the function with the SD-JWT input
        let output =
            decode_reveal_sd_jwt(sd_jwt_input.to_string()).expect("failed to decode SD-JWT");

        // Check the output JSON string structure
        assert!(output.contains("\"identityHash\":\"john.smith@example.com\""));
        assert!(output.contains("\"identityHash\":\"John Smith\""));

        Ok(())
    }
}
