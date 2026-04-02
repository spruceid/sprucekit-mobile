use std::sync::Arc;

use base64::{prelude::BASE64_URL_SAFE_NO_PAD, Engine};
use oid4vci_legacy::{
    client,
    credential::{RequestError, ResponseEnum},
    credential_offer::{CredentialOffer, CredentialOfferGrants},
    metadata::{authorization_server::GrantType, AuthorizationServerMetadata, MetadataDiscovery},
    oauth2::{ClientId, RedirectUrl, TokenResponse as _},
    profiles::{
        core::profiles::{
            jwt_vc_json, ldp_vc, mso_mdoc, CoreProfilesCredentialConfiguration,
            CoreProfilesCredentialRequest, CredentialRequestWithFormat,
        },
        custom::profiles::{
            vc_sd_jwt, CredentialRequestWithFormat as CustomCredentialRequestWithFormat,
            CustomProfilesCredentialConfiguration, CustomProfilesCredentialRequest,
        },
        CredentialResponseType, ProfilesCredentialRequest, ProfilesCredentialRequestWithFormat,
    },
    proof_of_possession::Proof,
    types::{CredentialConfigurationId, CredentialOfferRequest, PreAuthorizedCode},
};
use url::Url;

use crate::{
    credential::{CredentialFormat, RawCredential},
    oid4vci::{
        AsyncHttpClient, CredentialOrConfigurationId, CredentialResponse,
        DeferredCredentialResponse, ImmediateCredentialResponse, Oid4vciError, Oid4vciHttpClient,
        Proofs,
    },
};

#[derive(Clone)]
pub(crate) struct LegacyResolvedOffer {
    pub issuer: String,
    client: Arc<oid4vci_legacy::profiles::client::Client>,
    grants: Option<CredentialOfferGrants>,
    credential_ids: Vec<CredentialOrConfigurationId>,
    credential_requests: Vec<ProfilesCredentialRequest>,
}

#[derive(Clone)]
pub(crate) struct LegacyCredentialToken {
    credential_ids: Vec<CredentialOrConfigurationId>,
    credential_requests: Vec<ProfilesCredentialRequest>,
    client: Arc<oid4vci_legacy::profiles::client::Client>,
    token_response: oid4vci_legacy::token::Response,
    nonce: Option<String>,
}

pub(crate) async fn resolve_offer_url(
    http_client: Arc<dyn AsyncHttpClient>,
    credential_offer_url: &str,
    client_id: &str,
) -> Result<LegacyResolvedOffer, Oid4vciError> {
    let credential_offer_url = Url::parse(credential_offer_url)
        .map_err(|_| Oid4vciError::client_other("invalid credential offer url"))?;

    let credential_offer = CredentialOffer::from_request(
        CredentialOfferRequest::from_url_checked(credential_offer_url)
            .map_err(|_| Oid4vciError::client_other("failed to parse credential offer"))?,
    )
    .map_err(|_| Oid4vciError::client_other("failed to decode credential offer"))?
    .resolve_async(&Oid4vciHttpClient(http_client.clone()))
    .await
    .map_err(|err| Oid4vciError::client_other(err.to_string()))?;

    let issuer_metadata =
        oid4vci_legacy::profiles::metadata::CredentialIssuerMetadata::discover_async(
            credential_offer.issuer(),
            &Oid4vciHttpClient(http_client.clone()),
        )
        .await
        .map_err(|err| Oid4vciError::client_other(err.to_string()))?;

    let authorization_metadata = if let Some(grant) = credential_offer.pre_authorized_code_grant() {
        AuthorizationServerMetadata::discover_from_credential_issuer_metadata_async(
            &Oid4vciHttpClient(http_client),
            &issuer_metadata,
            Some(&GrantType::PreAuthorizedCode),
            grant.authorization_server(),
        )
        .await
        .map_err(|err| Oid4vciError::client_other(err.to_string()))?
    } else {
        return Err(Oid4vciError::client_other(
            "legacy oid4vci facade only supports pre-authorized code offers",
        ));
    };

    let (credential_ids, credential_requests): (Vec<_>, Vec<_>) = issuer_metadata
        .credential_configurations_supported()
        .iter()
        .filter(|config| {
            credential_offer
                .credential_configuration_ids()
                .contains(config.id())
        })
        .map(|config| {
            let credential_id = CredentialConfigurationId::new(config.id().to_string());
            let request = legacy_request_from_configuration(config, credential_id.clone());
            (
                CredentialOrConfigurationId::Configuration(credential_id.to_string()),
                request,
            )
        })
        .collect();

    if credential_requests.is_empty() {
        return Err(Oid4vciError::UndefinedCredential);
    }

    let client = client::Client::from_issuer_metadata(
        ClientId::new(client_id.to_string()),
        RedirectUrl::new("openid4vci://legacy".to_string())
            .map_err(|err| Oid4vciError::client_other(err.to_string()))?,
        issuer_metadata.clone(),
        authorization_metadata,
    );

    Ok(LegacyResolvedOffer {
        issuer: issuer_metadata.credential_issuer().url().to_string(),
        client: Arc::new(client),
        grants: credential_offer.grants().cloned(),
        credential_ids,
        credential_requests,
    })
}

pub(crate) async fn accept_offer(
    http_client: Arc<dyn AsyncHttpClient>,
    offer: &LegacyResolvedOffer,
) -> Result<LegacyCredentialToken, Oid4vciError> {
    let code = offer
        .grants
        .as_ref()
        .and_then(CredentialOfferGrants::pre_authorized_code)
        .map(|grant| grant.pre_authorized_code().clone())
        .ok_or_else(|| {
            Oid4vciError::client_other(
                "legacy oid4vci facade only supports pre-authorized code grants",
            )
        })?;

    exchange_pre_authorized_code(
        http_client,
        offer.client.clone(),
        code,
        offer.credential_ids.clone(),
        offer.credential_requests.clone(),
    )
    .await
}

async fn exchange_pre_authorized_code(
    http_client: Arc<dyn AsyncHttpClient>,
    client: Arc<oid4vci_legacy::profiles::client::Client>,
    code: PreAuthorizedCode,
    credential_ids: Vec<CredentialOrConfigurationId>,
    credential_requests: Vec<ProfilesCredentialRequest>,
) -> Result<LegacyCredentialToken, Oid4vciError> {
    let token_response = client
        .exchange_pre_authorized_code(code)
        .set_anonymous_client()
        .request_async(&Oid4vciHttpClient(http_client))
        .await
        .map_err(|err| Oid4vciError::client_other(err.to_string()))?;

    let nonce = token_response
        .extra_fields()
        .c_nonce
        .clone()
        .map(|nonce| nonce.secret().to_string());

    Ok(LegacyCredentialToken {
        credential_ids,
        credential_requests,
        client,
        token_response,
        nonce,
    })
}

impl LegacyResolvedOffer {
    pub(crate) fn credential_issuer(&self) -> &str {
        &self.issuer
    }
}

impl LegacyCredentialToken {
    pub(crate) fn default_credential_id(
        &self,
    ) -> Result<CredentialOrConfigurationId, Oid4vciError> {
        self.credential_ids
            .first()
            .cloned()
            .ok_or(Oid4vciError::UndefinedCredential)
    }

    pub(crate) fn nonce(&self) -> Option<String> {
        self.nonce.clone()
    }

    pub(crate) async fn exchange_credential(
        &self,
        http_client: Arc<dyn AsyncHttpClient>,
        credential: CredentialOrConfigurationId,
        proofs: Option<Proofs>,
    ) -> Result<CredentialResponse, Oid4vciError> {
        let proof = match proofs {
            Some(Proofs::Jwt(mut jwts)) => {
                if jwts.len() != 1 {
                    return Err(Oid4vciError::client_other(
                        "legacy oid4vci only supports a single jwt proof",
                    ));
                }

                Proof::Jwt {
                    jwt: jwts.remove(0).into(),
                }
            }
            None => {
                return Err(Oid4vciError::client_other(
                    "legacy oid4vci requires a proof for credential exchange",
                ))
            }
        };

        let request = self.select_request(&credential)?.set_proof(Some(proof));

        let response = request
            .request_async(&Oid4vciHttpClient(http_client))
            .await
            .map_err(legacy_request_error_to_oid4vci_error)?;

        response_enum_to_credential_response(response.response_kind())
    }

    fn select_request(
        &self,
        credential: &CredentialOrConfigurationId,
    ) -> Result<oid4vci_legacy::credential::RequestBuilder<ProfilesCredentialRequest>, Oid4vciError>
    {
        let index = self
            .credential_ids
            .iter()
            .position(|candidate| candidate_matches(candidate, credential))
            .ok_or(Oid4vciError::UndefinedCredential)?;

        Ok(self.client.request_credential(
            self.token_response.access_token().clone(),
            self.credential_requests[index].clone(),
        ))
    }
}

fn candidate_matches(
    candidate: &CredentialOrConfigurationId,
    selected: &CredentialOrConfigurationId,
) -> bool {
    match (candidate, selected) {
        (
            CredentialOrConfigurationId::Configuration(candidate),
            CredentialOrConfigurationId::Configuration(selected),
        ) => candidate == selected,
        (
            CredentialOrConfigurationId::Configuration(candidate),
            CredentialOrConfigurationId::Credential(selected),
        )
        | (
            CredentialOrConfigurationId::Credential(candidate),
            CredentialOrConfigurationId::Configuration(selected),
        )
        | (
            CredentialOrConfigurationId::Credential(candidate),
            CredentialOrConfigurationId::Credential(selected),
        ) => candidate == selected,
    }
}

fn legacy_request_from_configuration(
    config: &oid4vci_legacy::metadata::credential_issuer::CredentialConfiguration<
        oid4vci_legacy::profiles::ProfilesCredentialConfiguration,
    >,
    _credential_id: CredentialConfigurationId,
) -> ProfilesCredentialRequest {
    match config.profile_specific_fields() {
        oid4vci_legacy::profiles::ProfilesCredentialConfiguration::Core(core_config) => {
            let request = match core_config {
                CoreProfilesCredentialConfiguration::JwtVcJson(config) => {
                    let credential_definition =
                        jwt_vc_json::authorization_detail::CredentialDefinition::default()
                            .set_type(config.credential_definition().r#type().clone());

                    ProfilesCredentialRequestWithFormat::Core(
                        CredentialRequestWithFormat::JwtVcJson(
                            jwt_vc_json::CredentialRequestWithFormat::new(credential_definition),
                        ),
                    )
                }
                CoreProfilesCredentialConfiguration::JwtVcJsonLd(config) => {
                    let credential_definition =
                        ldp_vc::authorization_detail::CredentialDefinition::default()
                            .set_context(config.credential_definition().context().clone())
                            .set_type(config.credential_definition().r#type().clone());

                    ProfilesCredentialRequestWithFormat::Core(
                        CredentialRequestWithFormat::JwtVcJsonLd(
                            oid4vci_legacy::profiles::core::profiles::jwt_vc_json_ld::CredentialRequestWithFormat::new(credential_definition),
                        ),
                    )
                }
                CoreProfilesCredentialConfiguration::LdpVc(config) => {
                    let credential_definition =
                        ldp_vc::authorization_detail::CredentialDefinition::default()
                            .set_context(config.credential_definition().context().clone())
                            .set_type(config.credential_definition().r#type().clone());

                    ProfilesCredentialRequestWithFormat::Core(CredentialRequestWithFormat::LdpVc(
                        ldp_vc::CredentialRequestWithFormat::new(credential_definition),
                    ))
                }
                CoreProfilesCredentialConfiguration::MsoMdoc(config) => {
                    ProfilesCredentialRequestWithFormat::Core(CredentialRequestWithFormat::MsoMdoc(
                        mso_mdoc::CredentialRequestWithFormat::new(config.doctype().to_string())
                            .set_claims(config.claims().clone()),
                    ))
                }
            };

            match request {
                ProfilesCredentialRequestWithFormat::Core(inner) => {
                    ProfilesCredentialRequest::Core(CoreProfilesCredentialRequest::WithFormat {
                        inner,
                        _credential_identifier: (),
                    })
                }
                ProfilesCredentialRequestWithFormat::Custom(_) => unreachable!(),
            }
        }
        oid4vci_legacy::profiles::ProfilesCredentialConfiguration::Custom(custom_config) => {
            let request = match custom_config {
                CustomProfilesCredentialConfiguration::VcSdJwt(config) => {
                    let claims = config.claims().cloned();

                    ProfilesCredentialRequestWithFormat::Custom(
                        CustomCredentialRequestWithFormat::VcSdJwt(
                            vc_sd_jwt::CredentialRequestWithFormat::new(
                                config.vct().to_string(),
                                claims,
                            ),
                        ),
                    )
                }
            };

            match request {
                ProfilesCredentialRequestWithFormat::Custom(inner) => {
                    ProfilesCredentialRequest::Custom(CustomProfilesCredentialRequest::WithFormat {
                        inner,
                        _credential_identifier: (),
                    })
                }
                ProfilesCredentialRequestWithFormat::Core(_) => unreachable!(),
            }
        }
    }
}

fn response_enum_to_credential_response(
    response: &ResponseEnum<oid4vci_legacy::profiles::ProfilesCredentialResponse>,
) -> Result<CredentialResponse, Oid4vciError> {
    match response {
        ResponseEnum::Immediate { credential } => {
            Ok(CredentialResponse::Immediate(ImmediateCredentialResponse {
                credentials: vec![raw_credential_from_legacy(credential.clone())?],
            }))
        }
        ResponseEnum::ImmediateMany { credentials } => {
            Ok(CredentialResponse::Immediate(ImmediateCredentialResponse {
                credentials: credentials
                    .iter()
                    .cloned()
                    .map(raw_credential_from_legacy)
                    .collect::<Result<_, _>>()?,
            }))
        }
        ResponseEnum::Deferred { transaction_id } => {
            Ok(CredentialResponse::Deferred(DeferredCredentialResponse {
                transaction_id: transaction_id.clone().unwrap_or_default(),
                interval: 0,
            }))
        }
    }
}

fn legacy_request_error_to_oid4vci_error<RE>(error: RequestError<RE>) -> Oid4vciError
where
    RE: std::error::Error + 'static,
{
    match error {
        RequestError::Response(status, body, message) => {
            if let Some(error) = Oid4vciError::from_response_body(&body) {
                return error;
            }

            let body = match serde_json::from_slice::<serde_json::Value>(&body) {
                Ok(json) => serde_json::to_string_pretty(&json)
                    .unwrap_or_else(|_| String::from_utf8_lossy(&body).into_owned()),
                Err(_) => String::from_utf8_lossy(&body).into_owned(),
            };

            Oid4vciError::client_other(format!(
                "legacy oid4vci server responded with status code {status}: {message}\nresponse body:\n{body}"
            ))
        }
        other => Oid4vciError::client_other(other.to_string()),
    }
}

fn raw_credential_from_legacy(
    credential: CredentialResponseType,
) -> Result<RawCredential, Oid4vciError> {
    use oid4vci_legacy::profiles::core::profiles::CoreProfilesCredentialResponseType::*;

    match credential {
        CredentialResponseType::Core(core_response) => match *core_response {
            JwtVcJson(response) => Ok(RawCredential {
                format: CredentialFormat::JwtVcJson,
                payload: response.as_bytes().to_vec(),
            }),
            JwtVcJsonLd(response) => Ok(RawCredential {
                format: CredentialFormat::JwtVcJsonLd,
                payload: serde_json::to_vec(&response)
                    .map_err(|err| Oid4vciError::client_other(err.to_string()))?,
            }),
            LdpVc(response) => Ok(RawCredential {
                format: CredentialFormat::LdpVc,
                payload: serde_json::to_vec(&response)
                    .map_err(|err| Oid4vciError::client_other(err.to_string()))?,
            }),
            MsoMdoc(response) => {
                let bytes = isomdl::cbor::to_vec(&response.0)
                    .map_err(|err| Oid4vciError::client_other(err.to_string()))?;
                Ok(RawCredential {
                    format: CredentialFormat::MsoMdoc,
                    payload: BASE64_URL_SAFE_NO_PAD.encode(bytes).into_bytes(),
                })
            }
        },
        CredentialResponseType::Custom(custom_response) => match custom_response {
            oid4vci_legacy::profiles::custom::profiles::CustomProfilesCredentialResponseType::VcSdJwt(response) => {
                Ok(RawCredential {
                    format: CredentialFormat::VCDM2SdJwt,
                    payload: response.as_bytes().to_vec(),
                })
            }
        },
    }
}
