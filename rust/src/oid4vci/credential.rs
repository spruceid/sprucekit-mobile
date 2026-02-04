use oid4vci::profile::{StandardFormat, W3cVcFormat, FORMAT_DC_SD_JWT};

use crate::{
    credential::{vcdm2_sd_jwt::SPRUCE_FORMAT_VC_SD_JWT, CredentialFormat, RawCredential},
    oid4vci::Oid4vciError,
};

impl RawCredential {
    pub fn from_oid4vci(
        format: &StandardFormat,
        credential: oid4vci::Oid4vciCredential,
    ) -> Result<Self, Oid4vciError> {
        match format {
            StandardFormat::DcSdJwt => {
                // TODO add proper support for DC+SD-JWT.
                match credential.value {
                    serde_json::Value::String(dc_sd_jwt) => Ok(Self {
                        format: CredentialFormat::Other(FORMAT_DC_SD_JWT.to_owned()),
                        payload: dc_sd_jwt.into_bytes(),
                    }),
                    _ => Err(Oid4vciError::InvalidCredentialPayload),
                }
            }
            StandardFormat::W3c(format) => Ok(Self {
                format: match format {
                    W3cVcFormat::LdpVc => CredentialFormat::LdpVc,
                    W3cVcFormat::JwtVcJson => CredentialFormat::JwtVcJson,
                    W3cVcFormat::JwtVcJsonLd => CredentialFormat::JwtVcJsonLd,
                },
                payload: serde_json::to_vec(&credential.value).unwrap(),
            }),
            StandardFormat::MsoMdoc => match credential.value {
                serde_json::Value::String(base64_mso_mdoc) => Ok(Self {
                    format: CredentialFormat::MsoMdoc,
                    payload: base64_mso_mdoc.into_bytes(),
                }),
                _ => Err(Oid4vciError::InvalidCredentialPayload),
            },
            StandardFormat::Unknown(other) if other == SPRUCE_FORMAT_VC_SD_JWT => {
                match credential.value {
                    serde_json::Value::String(sd_jwt) => Ok(Self {
                        format: CredentialFormat::Other(other.clone()),
                        payload: sd_jwt.into_bytes(),
                    }),
                    _ => Err(Oid4vciError::InvalidCredentialPayload),
                }
            }
            StandardFormat::Unknown(other) => Ok(Self {
                format: CredentialFormat::Other(other.clone()),
                payload: match credential.value {
                    serde_json::Value::String(s) => s.into_bytes(),
                    value => serde_json::to_vec(&value).unwrap(),
                },
            }),
        }
    }
}
