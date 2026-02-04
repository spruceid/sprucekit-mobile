use std::{sync::Arc, time::Duration};

use oid4vci::iref::UriBuf;

use crate::jws::{Jws, JwsSigner};

use super::super::Oid4vciError;

/// Creates a JWT proof.
#[uniffi::export]
pub async fn create_jwt_proof(
    issuer: Option<String>,
    audience: String,
    expire_in_secs: Option<u64>,
    nonce: Option<String>,
    signer: Arc<dyn JwsSigner>,
) -> Result<Jws, Oid4vciError> {
    let audience = UriBuf::new(audience.into_bytes()).map_err(|_| Oid4vciError::InvalidUri)?;

    oid4vci::proof::jwt::create_jwt_proof(
        issuer,
        audience,
        expire_in_secs.map(Duration::from_secs),
        nonce,
        &*signer,
    )
    .await
    .map(Into::into)
    .map_err(Into::into)
}
