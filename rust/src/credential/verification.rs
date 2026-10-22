use std::collections::HashMap;

use ssi::{
    claims::{jwt::ToDecodedJwt, sd_jwt::SdJwt, Jws, VerificationParameters},
    dids::{AnyDidMethod, DIDResolver},
    json_ld::{ContextLoader, FromContextMapError},
    prelude::{AnyJsonCredential, AnySuite, DataIntegrity},
};

use super::{CredentialFormat, RawCredential};

/// Verifies the signature of a raw credential.
///
/// Resolving the issuer's DID can make network requests (e.g. `did:web`),
/// which need a Tokio runtime, and JSON-LD canonicalization recurses deeply
/// enough to overflow a small thread stack. Both are handled here by running
/// the verification on a dedicated big-stack thread with its own runtime, so
/// callers need neither.
#[uniffi::export(async_runtime = "tokio")]
pub async fn verify_raw_credential(
    credential: &RawCredential,
    context_map: Option<HashMap<String, String>>,
) -> Result<Verification, VerificationError> {
    let credential = RawCredential {
        format: credential.format.clone(),
        payload: credential.payload.clone(),
    };

    crate::big_stack::run_async(move || async move {
        verify_raw_credential_on_runtime(&credential, context_map).await
    })
    .await
    .map_err(|e| {
        VerificationError::ProofValidation(ssi::claims::ProofValidationError::Other(format!(
            "verification worker failed: {e}"
        )))
    })?
}

/// [`verify_raw_credential`]'s body. Must run inside a Tokio runtime.
async fn verify_raw_credential_on_runtime(
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
            log::trace!("verifying a LdpVc (VCDM v1 or v2)");
            // Decode version-agnostically into AnyJsonCredential (V1|V2).
            let vc: DataIntegrity<AnyJsonCredential, AnySuite> =
                serde_json::from_slice(&credential.payload)
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

#[cfg(test)]
mod tests {
    use super::*;

    /// An `ldp_vc` whose proof names a `did:web` that can never resolve
    /// (`.invalid` is reserved by RFC 2606).
    const UNRESOLVABLE_DID_WEB_VC: &str = r#"{
        "@context": [
            "https://www.w3.org/ns/credentials/v2",
            {
                "WalletAuthorization": "https://example.invalid/schema/WalletAuthorization",
                "authorization": {
                    "@id": "https://example.invalid/schema/authorization",
                    "@type": "@vocab",
                    "@container": "@set"
                },
                "SSA": "https://example.invalid/schema/SSA"
            }
        ],
        "type": ["VerifiableCredential", "WalletAuthorization"],
        "name": "Wallet Authorization",
        "issuer": { "id": "did:web:example.invalid", "name": "Issuer" },
        "credentialSubject": { "authorization": ["SSA"] },
        "validFrom": "2026-08-27T14:38:06.174156Z",
        "validUntil": "2027-08-27T14:38:06.174156Z",
        "proof": {
            "type": "DataIntegrityProof",
            "cryptosuite": "ecdsa-rdfc-2019",
            "created": "2026-08-27T14:38:46.561Z",
            "proofPurpose": "assertionMethod",
            "verificationMethod": "did:web:example.invalid#key-1",
            "proofValue": "z5JSPDAnrtZN7pzDjz8DXZrNBWwAz6QJHh9zRrUv1SaopCoUTAKzsDhYFsScmwXRFRhwv7J8JeRFqkgkfEtsXFAWK"
        }
    }"#;

    /// Mobile apps poll exported futures on their own executors, with no Tokio
    /// runtime. Resolving a `did:web` goes through async `reqwest`, which
    /// panics outside one ("there is no reactor running"). Verification of
    /// this credential has to fail, but as an error, not a panic.
    #[test]
    fn verify_raw_credential_runs_without_a_caller_runtime() {
        let credential = RawCredential {
            format: CredentialFormat::LdpVc,
            payload: UNRESOLVABLE_DID_WEB_VC.as_bytes().to_vec(),
        };

        // `futures`' executor provides no Tokio runtime, like the Swift and
        // Kotlin executors that poll this function in the apps.
        let result = futures::executor::block_on(verify_raw_credential(&credential, None));

        assert!(
            result.as_ref().map(|v| !v.is_verified()).unwrap_or(true),
            "an unresolvable issuer must not verify"
        );
    }
}
