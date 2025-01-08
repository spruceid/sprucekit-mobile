use super::{Credential, CredentialFormat, ParsedCredential, ParsedCredentialInner};
use crate::{
    oid4vp::{
        error::OID4VPError,
        presentation::{CredentialPresentation, PresentationOptions},
        RequestedField,
    },
    CredentialType, KeyAlias,
};

use std::sync::Arc;

use itertools::Itertools;
use openid4vp::{
    core::{
        credential_format::ClaimFormatDesignation, input_descriptor::ConstraintsLimitDisclosure,
        presentation_submission::DescriptorMap, response::parameters::VpTokenItem,
    },
    JsonPath,
};
use serde::Serialize;
use ssi::{
    claims::{
        jwt::AnyClaims,
        sd_jwt::{RevealedSdJwt, SdJwtBuf},
        vc::v2::{Credential as _, JsonCredential},
        vc_jose_cose::SdJwtVc,
    },
    prelude::AnyJsonCredential,
    JsonPointerBuf,
};
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

#[uniffi::export]
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
        options: &'a PresentationOptions<'a>,
        selected_fields: Option<Vec<String>>,
    ) -> Result<VpTokenItem, OID4VPError> {
        // TODO: need to provide the "filtered" (disclosed) fields of the
        // credential to be encoded into the VpToken.
        //
        // Currently, this is encoding the entire revealed SD-JWT,
        // without the selection of individual disclosed fields.
        //
        // We need to selectively disclosed fields.
        let input_descriptor_map = options.presentation_definition.input_descriptors_map();
        let requested_fields = self.requested_fields(&options.presentation_definition);

        // TODO: check if limit_disclosure is true somewhere and requires that
        // selected_fields be non-empty
        // requested_fields
        //     .group_by(/*input_descriptor_id*/)
        //     .map(/*input_descriptor.constraints.limit_disclosure*/)
        //     .any(/*required*/) && selected_fields.is_none()
        // if requested_fields
        //     .into_iter()
        //     .chunk_by(|rf| rf.input_descriptor_id.clone())
        //     .into_iter()
        //     .map(|(id, d)| {
        //         if match input_descriptor_map[&id.as_str()]
        //             .constraints
        //             .limit_disclosure()
        //         {
        //             Some(ConstraintsLimitDisclosure::Required) => true,
        //             _ => false,
        //         } {
        //             return d
        //                 .into_iter()
        //                 .any(|f| f.required && selected_fields.is_none());
        //         }
        //         false
        //     })
        //     .any(|e| e == true)
        // {
        //     return Err(OID4VPError::SelectiveDisclosureEmptySelection);
        // }

        // let disclosable_fields = inner_list_sd_fields(self)
        //     .map_err(|_| OID4VPError::SelectiveDisclosureInvalidFields)?;

        // let is_valid = if let Some(sfs) = selected_fields {
        //     let sfs = sfs
        //         .into_iter()
        //         .map(|sf| JsonPointerBuf::new(sf))
        //         .collect::<Result<Vec<_>, _>>()
        //         .map_err(|_| OID4VPError::SelectiveDisclosureInvalidFields)?;

        //     // TODO: use limit_disclosure in validation
        //     // match input_descriptor.constraints.limit_disclosure() {
        //     //     Some(ConstraintsLimitDisclosure::Required) => {}
        //     //     Some(ConstraintsLimitDisclosure::Preferred) => {}
        //     //     None => true,
        //     // }
        //     // When required, all selected fields must:
        //     //  - match disclosable
        //     //  - match requested
        //     //    - if optional, refer to SD
        //     //
        //     // When preferred, all selected fields must:
        //     //  - match disclosable
        //     //
        //     // Example:
        //     //  - limit_disclosure = preferred:
        //     //   - Entire credential _CAN_ be submitted
        //     //   - SD credential _CAN_ be submitted
        //     //
        //     //  - limit_disclosure = required:
        //     //   - Entire credential _CANNOT_ be submitted (only requested_fields)
        //     //   - SD credential _CAN_ be submitted

        //     // Iter over all selected fields
        //     // sfs.iter().all(|sf| {
        //     //     // TODO remove unwrap

        //     //     //Parse selected_field JsonPath
        //     //     let sfj =
        //     //         JsonPath::parse(&format!("$.{}", &sf.as_str().split("/").join("."))).unwrap();

        //     //     // Matches each selected field with available disclosable fields
        //     //     disclosable_fields.iter().any(|df| df == sf.as_str())
        //     //         // Checks if selected field is disclosable
        //     //         && requested_fields.iter().any(|rf| {
        //     //             let input_descriptor_id: String = rf.input_descriptor_id().to_owned();
        //     //             let input_descriptor =
        //     //                 input_descriptor_map.get(input_descriptor_id.as_str());
        //     //             if let Some(input_descriptor) = input_descriptor {
        //     //                 // Selected Field instance must match _SOME_ Requested Field
        //     //                 input_descriptor
        //     //                     .constraints
        //     //                     .fields()
        //     //                     .iter()
        //     //                     .any(|cf| cf.path.iter().any(|p| *p == sfj))
        //     //             } else {
        //     //                 false
        //     //             }
        //     //         })
        //     // })
        //     true
        // } else {
        //     true
        // };

        if true {
            // TODO: (limit_disclosure = Required) && selected_fields.is_none() => ERROR
            // TODO: (limit_disclosure = None || Preferred) && selected_fields.is_none()
            // selected_fields.into_iter().map(|sf| )
            let compact: &str = self.inner.as_ref();
            log::debug!("SF: {:?}", selected_fields);
            let vp_token = if let Some(sfs) = selected_fields {
                let json = self
                    .revealed_claims_as_json()
                    .map_err(|e| OID4VPError::JsonPathParse(e.to_string()))?;

                log::debug!("JSON: {:?}", json);

                let sfs = sfs.into_iter();
                let sfs = sfs.map(|sf| sf.split("|").next().unwrap().to_owned());
                log::debug!("1: {:?}", sfs);
                let sfs = sfs.map(|path| JsonPath::parse(&path).unwrap());
                log::debug!("2: {:?}", sfs);
                let sfs = sfs.map(|f| f.query_located(&json));
                log::debug!("3: {:?}", sfs);
                let sfs = sfs.map(|ln| {
                    log::debug!("3.1: {:?}", ln);
                    if ln.is_empty() {
                        return Err(unimplemented!());
                    }
                    // SAFETY: Empty check above
                    JsonPointerBuf::new(ln.first().unwrap().location().to_json_pointer())
                });
                log::debug!("4: {:?}", sfs);

                let rjp = sfs
                    .collect::<Result<Vec<_>, _>>()
                    .map_err(|e| OID4VPError::Debug(e.to_string()))?;

                log::debug!("5: {:?}", self.inner);
                let ret = self
                    .inner
                    .decode_reveal::<AnyClaims>()
                    .map_err(|e| OID4VPError::Debug(e.to_string()))?
                    .retaining(rjp.as_ref())
                    .into_encoded()
                    .as_str()
                    .to_string();
                log::debug!("6: {:?}", self.inner);
                ret
            } else {
                compact.to_string()
            };

            Ok(VpTokenItem::String(vp_token))
        } else {
            Err(unimplemented!())
        }
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

impl From<VCDM2SdJwt> for ParsedCredential {
    fn from(value: VCDM2SdJwt) -> Self {
        ParsedCredential {
            inner: ParsedCredentialInner::VCDM2SdJwt(Arc::new(value)),
            selected_fields: None,
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
        ParsedCredential::new_sd_jwt(value, None)
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

fn inner_list_sd_fields(input: &VCDM2SdJwt) -> Result<Vec<String>, SdJwtError> {
    let revealed_sd_jwt = SdJwtVc::decode_reveal_any(&input.inner)
        .map_err(|e| SdJwtError::SdJwtDecoding(format!("{e:?}")))?;

    Ok(revealed_sd_jwt
        .disclosures
        .iter()
        .map(|(p, d)| {
            match &d.desc {
                ssi::claims::sd_jwt::DisclosureDescription::ObjectEntry { key: _, value: _ } => {
                    p.to_string()
                }
                // TODO(w4ll3): idk
                ssi::claims::sd_jwt::DisclosureDescription::ArrayItem(_) => p.to_string(),
            }
        })
        .collect())
}

#[uniffi::export]
pub fn list_sd_fields(input: Arc<VCDM2SdJwt>) -> Result<Vec<String>, SdJwtError> {
    inner_list_sd_fields(&input)
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
