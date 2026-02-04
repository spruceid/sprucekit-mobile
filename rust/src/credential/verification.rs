use std::collections::HashMap;

use ssi::{
    claims::{
        jwt::ToDecodedJwt, sd_jwt::SdJwt, vc::v1::data_integrity::any_credential_from_json_slice,
        Jws, VerificationParameters,
    },
    dids::{AnyDidMethod, DIDResolver},
    json_ld::{ContextLoader, FromContextMapError},
};

use super::{CredentialFormat, RawCredential};

/// Verifies the signature of a raw credential.
#[uniffi::export]
pub async fn verify_raw_credential(
    credential: &RawCredential,
    context_map: Option<HashMap<String, String>>,
) -> Result<Verification, VerificationError> {
    let vm_resolver = AnyDidMethod::default().into_vm_resolver();
    let mut params = VerificationParameters::from_resolver(vm_resolver);

    if let Some(map) = context_map {
        params = params.with_json_ld_loader(
            ContextLoader::empty()
                .with_static_loader()
                .with_context_map_from(map)?,
        )
    }

    match &credential.format {
        CredentialFormat::JwtVcJson => {
            log::trace!("verifying a JwtVcJson");
            let jwt = Jws::new(&credential.payload)
                .map_err(|_| VerificationError::InvalidCredentialPayload)?;
            jwt.verify_jwt(&params)
                .await
                .map(Into::into)
                .map_err(Into::into)
        }
        CredentialFormat::JwtVcJsonLd => {
            log::trace!("verifying a JwtVcJsonLd");
            let jwt = Jws::new(&credential.payload)
                .map_err(|_| VerificationError::InvalidCredentialPayload)?;
            jwt.verify_jwt(&params)
                .await
                .map(Into::into)
                .map_err(Into::into)
        }
        CredentialFormat::LdpVc => {
            log::trace!("verifying a LdpVc");
            let vc = any_credential_from_json_slice(&credential.payload)
                .map_err(|_| VerificationError::InvalidCredentialPayload)?;
            vc.verify(&params).await.map(Into::into).map_err(Into::into)
        }
        CredentialFormat::VCDM2SdJwt => {
            log::trace!("verifying a VcSdJwt");
            let sd_jwt = SdJwt::new(&credential.payload)
                .map_err(|_| VerificationError::InvalidCredentialPayload)?;
            sd_jwt
                .decode_verify_concealed(&params)
                .await
                .map(|(_, v)| v.into())
                .map_err(Into::into)
        }
        _ => Err(VerificationError::UnsupportedFormat),
    }
}

#[derive(Debug, thiserror::Error, uniffi::Error)]
#[uniffi(flat_error)]
pub enum VerificationError {
    #[error("unsupported credential format")]
    UnsupportedFormat,

    #[error("invalid context map: {0}")]
    InvalidContextMap(#[from] FromContextMapError),

    #[error("invalid credential payload")]
    InvalidCredentialPayload,

    #[error("unable to verify: {0}")]
    ProofValidation(#[from] ssi::claims::ProofValidationError),
}

/// Invalid credential (claims or signature).
#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum InvalidCredential {
    #[error("invalid credential claims: {0}")]
    Claims(InvalidClaims),

    #[error("invalid credential signature")]
    Proof,
}

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum InvalidClaims {
    #[error("missing issuance date")]
    MissingIssuanceDate,

    /// Validity period starts in the future.
    #[error("premature")]
    Premature,

    /// Validity period ends in the past.
    #[error("expired")]
    Expired,

    /// Uncommon validation error.
    #[error("{0}")]
    Other(String),
}

#[derive(uniffi::Object)]
pub struct Verification(ssi::claims::Verification);

#[uniffi::export]
impl Verification {
    pub fn is_verified(&self) -> bool {
        self.0.is_ok()
    }

    pub fn expect_verified(&self) -> Result<(), InvalidCredential> {
        match &self.0 {
            Ok(()) => Ok(()),
            Err(ssi::claims::Invalid::Claims(e)) => {
                let e = match e {
                    ssi::claims::InvalidClaims::MissingIssuanceDate => {
                        InvalidClaims::MissingIssuanceDate
                    }
                    ssi::claims::InvalidClaims::Expired { .. } => InvalidClaims::Expired,
                    ssi::claims::InvalidClaims::Premature { .. } => InvalidClaims::Premature,
                    ssi::claims::InvalidClaims::Other(e) => InvalidClaims::Other(e.clone()),
                };

                Err(InvalidCredential::Claims(e))
            }
            Err(ssi::claims::Invalid::Proof(_)) => Err(InvalidCredential::Proof),
        }
    }
}

impl From<ssi::claims::Verification> for Verification {
    fn from(value: ssi::claims::Verification) -> Self {
        Self(value)
    }
}
