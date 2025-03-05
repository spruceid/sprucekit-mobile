pub mod holder;
pub mod reader;
pub mod util;

use ssi::{
    claims::vc::v1::{data_integrity::any_credential_from_json_str, ToJwtClaims},
    dids::{AnyDidMethod, DIDResolver},
};

#[derive(thiserror::Error, uniffi::Error, Debug)]
pub enum VCVerificationError {
    #[error("{value}")]
    Generic { value: String },
}

#[uniffi::export]
pub async fn verify_json_vc_string(json: String) -> Result<(), VCVerificationError> {
    use ssi::prelude::VerificationParameters;

    let vc = any_credential_from_json_str(&json).map_err(|e| VCVerificationError::Generic {
        value: e.to_string(),
    })?;

    let vm_resolver = AnyDidMethod::default().into_vm_resolver();
    let params = VerificationParameters::from_resolver(vm_resolver);

    vc.verify(&params)
        .await
        .map_err(|e| VCVerificationError::Generic {
            value: e.to_string(),
        })?
        .map_err(|e| VCVerificationError::Generic {
            value: e.to_string(),
        })
}

#[derive(thiserror::Error, uniffi::Error, Debug)]
pub enum VPError {
    #[error("verification failed")]
    Verification,
    #[error("failed signing jwt")]
    Signing,
    #[error("{value}")]
    Parsing { value: String },
    #[error("{value}")]
    Generic { value: String },
}

#[uniffi::export]
pub async fn vc_to_signed_vp(vc: String, key_str: String) -> Result<String, VPError> {
    use ssi::prelude::*;

    let vp = ssi::claims::vc::v1::JsonPresentation::new(None, None, vec![vc]);

    let mut key: ssi::jwk::JWK = serde_json::from_str(&key_str).map_err(|e| VPError::Parsing {
        value: e.to_string(),
    })?;
    let did = DIDJWK::generate_url(&key.to_public());
    key.key_id = Some(did.into());

    let jwt = vp
        .to_jwt_claims()
        .map_err(|e| VPError::Parsing {
            value: e.to_string(),
        })?
        .sign(&key)
        .await
        .map_err(|_| VPError::Signing)?;
    Ok(jwt.into_string())
}

#[uniffi::export]
pub async fn verify_jwt_vp(jwt_vp: String) -> Result<(), VPError> {
    use ssi::prelude::*;

    let jwt = JwsString::from_string(jwt_vp.to_string()).map_err(|e| VPError::Parsing {
        value: e.to_string(),
    })?;

    let vm_resolver: ssi::dids::VerificationMethodDIDResolver<AnyDidMethod, AnyMethod> =
        AnyDidMethod::default().into_vm_resolver();
    let params = VerificationParameters::from_resolver(vm_resolver);

    jwt.verify(params)
        .await
        .map_err(|e| VPError::Generic {
            value: format!("something went wrong: {e}"),
        })?
        .map_err(|_| VPError::Verification)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn verify_vc() {
        let json_vc = include_str!("../../tests/res/vc");
        verify_json_vc_string(json_vc.into()).await.unwrap()
    }

    #[tokio::test]
    async fn verify_vp() {
        let json_vc = include_str!("../../tests/res/vc");
        let key_str = include_str!("../../tests/res/ed25519-2020-10-18.json");
        let jwt_vp = vc_to_signed_vp(json_vc.to_string(), key_str.to_string())
            .await
            .unwrap();
        verify_jwt_vp(jwt_vp).await.unwrap()
    }
}
