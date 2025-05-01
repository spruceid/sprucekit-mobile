use anyhow::{bail, Context, Result};
use josekit::{jwk::Jwk, jwt::JwtPayload};
use openid4vp::core::{
    authorization_request::{parameters::ResponseMode, AuthorizationRequestObject},
    object::ParsingErrorContext,
};
use serde_json::{json, Value as Json};

use crate::oid4vp::iso_18013_7::build_response::{
    build_jwe, get_jwk_from_client_metadata, get_state_from_request,
};

pub enum Responder {
    Json {
        state: Option<String>,
    },
    Jwe {
        alg: String,
        enc: String,
        state: Option<String>,
        verifier_jwk: Jwk,
    },
}

impl Responder {
    pub fn new(request: &AuthorizationRequestObject) -> Result<Self> {
        let state = get_state_from_request(request)?;
        match request.response_mode() {
            ResponseMode::DcApi => Ok(Self::Json { state }),
            ResponseMode::DcApiJwt => {
                let client_metadata = request.client_metadata().parsing_error()?;
                let verifier_jwk = get_jwk_from_client_metadata(&client_metadata)?;
                let alg = client_metadata
                    .authorization_encrypted_response_alg()
                    .parsing_error()?
                    .0;
                if alg != "ECDH-ES" {
                    bail!("unsupported encryption alg: {alg}")
                }

                let enc = client_metadata
                    .authorization_encrypted_response_enc()
                    .parsing_error()?
                    .0;
                if enc != "A128GCM" {
                    bail!("unsupported encryption scheme: {enc}")
                }

                Ok(Self::Jwe {
                    alg,
                    enc,
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
            .context("failed to serialize response"),
            Self::Jwe {
                alg,
                enc,
                state,
                verifier_jwk,
            } => {
                let mut payload = JwtPayload::new();
                if let Some(state) = state {
                    payload
                        .set_claim("state", Some(Json::String(state.clone())))
                        .context("failed to set state claim")?;
                }
                payload
                    .set_claim("vp_token", Some(vp_token))
                    .context("failed to set vp_token claim")?;

                build_jwe(verifier_jwk, &payload, alg, enc, "", "")
            }
        }
    }
}
