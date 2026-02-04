use anyhow::{bail, Context, Result};
use openid4vp::core::{
    authorization_request::{parameters::ResponseMode, AuthorizationRequestObject},
    iso_18013_7::compute_jwk_thumbprint,
    jwe::{find_encryption_jwk, JweBuilder, DEFAULT_ENC},
    object::ParsingErrorContext,
};
use serde_json::{json, Value as Json};

use crate::oid4vp::iso_18013_7::build_response::get_state_from_request;

pub enum Responder {
    Json {
        state: Option<String>,
    },
    Jwe {
        alg: String,
        enc: String,
        kid: Option<String>,
        state: Option<String>,
        verifier_jwk: Json,
    },
}

impl Responder {
    pub fn new(request: &AuthorizationRequestObject) -> Result<Self> {
        let state = get_state_from_request(request)?;
        match request.response_mode() {
            ResponseMode::DcApi => Ok(Self::Json { state }),
            ResponseMode::DcApiJwt => {
                let client_metadata = request.client_metadata().parsing_error()?;

                // Per OID4VP v1.0 §8.3, alg comes from the JWK's `alg` field
                let jwks = client_metadata.jwks().parsing_error()?;
                let keys: Vec<_> = jwks.keys.iter().collect();
                let jwk_info = find_encryption_jwk(keys.into_iter())
                    .context("no suitable encryption key found in client metadata")?;

                // Convert to JSON for storage
                let verifier_jwk: Json =
                    serde_json::to_value(&jwk_info.jwk).context("failed to serialize JWK")?;

                let alg = jwk_info.alg.clone();
                if alg != "ECDH-ES" {
                    bail!("unsupported encryption alg: {alg}")
                }

                // Per OID4VP v1.0 §8.3, enc comes from encrypted_response_enc_values_supported (default: A128GCM)
                let enc = client_metadata
                    .encrypted_response_enc_values_supported()
                    .parsing_error()?
                    .0
                    .first()
                    .cloned()
                    .unwrap_or_else(|| DEFAULT_ENC.to_string());
                if enc != DEFAULT_ENC {
                    bail!("unsupported encryption scheme: {enc}")
                }

                Ok(Self::Jwe {
                    alg,
                    enc,
                    kid: jwk_info.kid.clone(),
                    state,
                    verifier_jwk,
                })
            }
            mode => bail!("unsupported response mode: {mode:?}"),
        }
    }

    pub fn response(&self, vp_token: Json) -> Result<String> {
        match self {
            Self::Json { state } => {
                let mut object = json!({
                    "vp_token": vp_token,
                });
                if let Some(state) = state {
                    object
                        .as_object_mut()
                        .context("response is not an object")?
                        .insert("state".to_string(), Json::String(state.clone()));
                }
                serde_json::to_string(&object).context("failed to serialize response")
            }
            Self::Jwe {
                alg,
                enc,
                kid,
                state,
                verifier_jwk,
            } => {
                // Build the payload
                let mut payload = json!({
                    "vp_token": vp_token
                });
                if let Some(state) = state {
                    payload["state"] = Json::String(state.clone());
                }

                let mut builder = JweBuilder::new()
                    .payload(payload)
                    .recipient_key_json(verifier_jwk)
                    .context("invalid recipient JWK")?
                    .alg(alg)
                    .enc(enc);

                if let Some(kid) = kid {
                    builder = builder.kid(kid);
                }

                builder.build().context("failed to build JWE")
            }
        }
    }

    /// Get the JWK thumbprint for the verifier's encryption key.
    pub fn jwk_thumbprint(&self) -> Option<[u8; 32]> {
        match self {
            Self::Json { .. } => None,
            Self::Jwe { verifier_jwk, .. } => compute_jwk_thumbprint(verifier_jwk).ok(),
        }
    }
}
