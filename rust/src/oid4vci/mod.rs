use std::{collections::HashMap, sync::Arc};

use base64::{prelude::BASE64_URL_SAFE_NO_PAD, Engine};
use either::Either;
use oid4vci::{
    client,
    credential::ResponseEnum,
    credential_offer::CredentialOffer,
    metadata::{authorization_server::GrantType, AuthorizationServerMetadata, MetadataDiscovery},
    oauth2::{ClientId, RedirectUrl, TokenResponse as ITokenResponse},
    profiles::{
        core::{
            self,
            profiles::{ldp_vc, CoreProfilesCredentialConfiguration},
        },
        custom::{self, profiles::CustomProfilesCredentialConfiguration},
        metadata::CredentialIssuerMetadata as ICredentialIssuerMetadata,
        CredentialResponseType, ProfilesCredentialRequest, ProfilesCredentialRequestWithFormat,
    },
    proof_of_possession::Proof,
    types::{CredentialOfferRequest, IssuerUrl, PreAuthorizedCode},
};
use ssi::{
    claims::{
        jwt::ToDecodedJwt, vc::v1::data_integrity::any_credential_from_json_str,
        VerificationParameters,
    },
    dids::{AnyDidMethod, DIDResolver},
};
use url::Url;

pub use context_loader::context_loader_from_map;
pub use error::*;
pub use http_client::*;
pub use metadata::*;
pub use options::*;
pub use session::*;
pub use wrapper::*;

use crate::credential::CredentialFormat;

mod context_loader;
mod error;
mod http_client;
mod metadata;
mod options;
mod session;
mod wrapper;

#[uniffi::export(async_runtime = "tokio")]
pub async fn oid4vci_initiate_with_offer(
    credential_offer: String,
    client_id: String,
    redirect_url: String,
    http_client: Arc<IHttpClient>,
) -> Result<Oid4vciSession, Oid4vciError> {
    let credential_offer = Url::parse(&credential_offer).map_err(|_| {
        Oid4vciError::InvalidParameter("invalid credential_offer: failed to parse url".into())
    })?;

    let credential_offer = CredentialOffer::from_request(
        CredentialOfferRequest::from_url_checked(credential_offer).map_err(|_| {
            Oid4vciError::InvalidParameter("invalid credential_offer: failed to parse offer".into())
        })?,
    )
    .map_err(|_| {
        Oid4vciError::InvalidParameter("invalid credential_offer: failed to decode offer".into())
    })?;

    let credential_offer = match &http_client.0 {
        Either::Left(sync_client) => credential_offer.resolve(sync_client),
        Either::Right(async_client) => credential_offer.resolve_async(async_client).await,
    }
    .map_err(|_| {
        Oid4vciError::InvalidParameter("invalid credential_offer: failed to resolve offer".into())
    })?;

    let base_url = credential_offer.issuer();

    let issuer_metadata = match &http_client.0 {
        Either::Left(sync_client) => ICredentialIssuerMetadata::discover(base_url, sync_client),
        Either::Right(async_client) => {
            ICredentialIssuerMetadata::discover_async(base_url, async_client).await
        }
    }
    .map_err(|e| Oid4vciError::RequestError(e.to_string()))?;

    let grants = credential_offer.grants().map(|g| g.to_owned());

    let authorization_metadata =
        if let Some(grant) = credential_offer.pre_authorized_code_grant() {
            // TODO: maybe offer a way for the wallet to pick grant ordering
            // when multiple options are present

            let authorization_server = grant.authorization_server();

            match &http_client.0 {
                Either::Left(sync_client) => {
                    AuthorizationServerMetadata::discover_from_credential_issuer_metadata(
                        sync_client,
                        &issuer_metadata,
                        Some(&GrantType::PreAuthorizedCode),
                        authorization_server,
                    )
                }
                Either::Right(async_client) => {
                    AuthorizationServerMetadata::discover_from_credential_issuer_metadata_async(
                        async_client,
                        &issuer_metadata,
                        Some(&GrantType::PreAuthorizedCode),
                        authorization_server,
                    )
                    .await
                }
            }
        } else {
            // TODO: if grants isn't present in the credential offer
            // we must determine the grant type by using the metadata.
            // Potentially defer to caller using a ForeignTrait after
            // obtaining `grant_types_supported` from authorization server
            // metadata. Future solution must keep in mind that the
            // `authorization_servers` field is an array, so multiple
            // grant options from different servers may be available.
            todo!("determine grant type with metadata")
        }
        .map_err(|_| {
            Oid4vciError::RequestError("failed to discover authorization server metadata".into())
        })?;

    let credential_requests: Vec<ProfilesCredentialRequest> = issuer_metadata
        .credential_configurations_supported()
        .iter()
        .filter(|config| {
            credential_offer
                .credential_configuration_ids()
                .contains(config.id())
        })
        .map(|config| match config.profile_specific_fields() {
            oid4vci::profiles::ProfilesCredentialConfiguration::Core(
                core_profiles_credential_configuration,
            ) => match core_profiles_credential_configuration {
                CoreProfilesCredentialConfiguration::LdpVc(config) => {
                    let credential_definition =
                        ldp_vc::authorization_detail::CredentialDefinition::default()
                            .set_context(config.credential_definition().context().clone())
                            .set_type(config.credential_definition().r#type().clone());
                    ProfilesCredentialRequestWithFormat::Core(
                        core::profiles::CredentialRequestWithFormat::LdpVc(
                            core::profiles::ldp_vc::CredentialRequestWithFormat::new(
                                credential_definition,
                            ),
                        ),
                    )
                }
                CoreProfilesCredentialConfiguration::JwtVcJsonLd(config) => {
                    let credential_definition =
                        ldp_vc::authorization_detail::CredentialDefinition::default()
                            .set_context(config.credential_definition().context().clone())
                            .set_type(config.credential_definition().r#type().clone());
                    ProfilesCredentialRequestWithFormat::Core(
                        core::profiles::CredentialRequestWithFormat::JwtVcJsonLd(
                            core::profiles::jwt_vc_json_ld::CredentialRequestWithFormat::new(
                                credential_definition,
                            ),
                        ),
                    )
                }
                CoreProfilesCredentialConfiguration::MsoMdoc(config) => {
                    ProfilesCredentialRequestWithFormat::Core(
                        core::profiles::CredentialRequestWithFormat::MsoMdoc(
                            core::profiles::mso_mdoc::CredentialRequestWithFormat::new(
                                config.doctype().to_string(),
                            ),
                        ),
                    )
                }
                x => unimplemented!("{x:?}"),
            },
            oid4vci::profiles::ProfilesCredentialConfiguration::Custom(
                custom_profiles_credential_configuration,
            ) => match custom_profiles_credential_configuration {
                CustomProfilesCredentialConfiguration::VcSdJwt(config) => {
                    let claims = config.claims().cloned();
                    ProfilesCredentialRequestWithFormat::Custom(
                        custom::profiles::CredentialRequestWithFormat::VcSdJwt(
                            custom::profiles::vc_sd_jwt::CredentialRequestWithFormat::new(
                                config.vct().clone(),
                                claims,
                            ),
                        ),
                    )
                }
            },
        })
        .map(|req| match req {
            ProfilesCredentialRequestWithFormat::Core(inner) => ProfilesCredentialRequest::Core(
                core::profiles::CoreProfilesCredentialRequest::WithFormat {
                    inner,
                    _credential_identifier: (),
                },
            ),
            ProfilesCredentialRequestWithFormat::Custom(inner) => {
                ProfilesCredentialRequest::Custom(
                    custom::profiles::CustomProfilesCredentialRequest::WithFormat {
                        inner,
                        _credential_identifier: (),
                    },
                )
            }
        })
        .collect();

    log::trace!("Credential requests: {:#?}", credential_requests);

    let client = client::Client::from_issuer_metadata(
        ClientId::new(client_id),
        RedirectUrl::new(redirect_url).unwrap(),
        issuer_metadata.clone(),
        authorization_metadata,
    );

    let mut session = Oid4vciSession::new(client.into());
    session.set_metadata(issuer_metadata.into());
    session.set_credential_requests(credential_requests)?;
    session.set_grants(grants)?;

    Ok(session)
}

#[uniffi::export(async_runtime = "tokio")]
pub async fn oid4vci_initiate(
    base_url: String,
    client_id: String,
    redirect_url: String,
    http_client: Arc<IHttpClient>,
) -> Result<Oid4vciSession, Oid4vciError> {
    let base_url = IssuerUrl::new(base_url)
        .map_err(|e| e.to_string())
        .map_err(Oid4vciError::from)?;

    let issuer_metadata = match &http_client.0 {
        Either::Left(sync_client) => ICredentialIssuerMetadata::discover(&base_url, sync_client),
        Either::Right(async_client) => {
            ICredentialIssuerMetadata::discover_async(&base_url, async_client).await
        }
    }
    .map_err(|_| {
        Oid4vciError::RequestError("failed to discover credential issuer metadata".into())
    })?;

    let authorization_metadata = match &http_client.0 {
        Either::Left(sync_client) => AuthorizationServerMetadata::discover(&base_url, sync_client),
        Either::Right(async_client) => {
            AuthorizationServerMetadata::discover_async(&base_url, async_client).await
        }
    }
    .map_err(|_| {
        Oid4vciError::RequestError("failed to discover authorization server metadata".into())
    })?;

    let client = client::Client::from_issuer_metadata(
        ClientId::new(client_id),
        RedirectUrl::new(redirect_url).unwrap(),
        issuer_metadata.clone(),
        authorization_metadata,
    );

    let mut session = Oid4vciSession::new(client.into());
    session.set_metadata(issuer_metadata.into());

    Ok(session)
}

#[uniffi::export(async_runtime = "tokio")]
pub async fn oid4vci_exchange_token(
    session: Arc<Oid4vciSession>,
    http_client: Arc<IHttpClient>,
) -> Result<Option<String>, Oid4vciError> {
    // TODO: refactor with `try {}` once it stabilizes.
    let code = (|| -> Result<PreAuthorizedCode, Oid4vciError> {
        if let Some(pre_auth) = session.get_grants()?.pre_authorized_code() {
            return Ok(pre_auth.pre_authorized_code().clone());
        }

        Err(Oid4vciError::UnsupportedGrantType)
    })()?;

    let token_response = match &http_client.0 {
        Either::Left(sync_client) => session
            .get_client()
            .exchange_pre_authorized_code(code)
            .set_anonymous_client()
            .request(sync_client),
        Either::Right(async_client) => {
            session
                .get_client()
                .exchange_pre_authorized_code(code)
                .set_anonymous_client()
                .request_async(async_client)
                .await
        }
    }
    .map_err(|_| Oid4vciError::RequestError("failed to exchange code".into()))?;

    let nonce = token_response
        .extra_fields()
        .c_nonce
        .clone()
        .map(|v| v.secret().to_owned());

    session.set_token_response(token_response.into())?;

    Ok(nonce)
}

#[uniffi::export(async_runtime = "tokio")]
pub async fn oid4vci_exchange_credential(
    session: Arc<Oid4vciSession>,
    proofs_of_possession: Vec<String>,
    options: Oid4vciExchangeOptions,
    context_map: Option<HashMap<String, String>>,
    http_client: Arc<IHttpClient>,
) -> Result<Vec<CredentialResponse>, Oid4vciError> {
    log::trace!("oid4vci_exchange_credential");

    log::trace!("session.get_credential_requests");
    let credential_requests = session.get_credential_requests()?.clone();

    log::trace!("credential_requests.is_empty");
    if credential_requests.is_empty() {
        return Err(Oid4vciError::InvalidSession(
            "credential_requests unset".to_string(),
        ));
    }

    log::trace!("compare length proofs_of_possession vs credential_requests");
    if proofs_of_possession.len() != credential_requests.len() {
        return Err(Oid4vciError::InvalidParameter(
            "invalid number of proofs received, must match credential request count".into(),
        ));
    }

    let credential_responses = if credential_requests.len() == 1 {
        log::trace!("processing single request");

        log::trace!("build request");
        let request = session
            .get_client()
            .request_credential(
                session.get_token_response()?.access_token().clone(),
                credential_requests.first().unwrap().to_owned(),
            )
            .set_proof(Some(Proof::Jwt {
                jwt: proofs_of_possession.first().unwrap().to_owned(),
            }));

        log::trace!("execute with http client");
        let response = match &http_client.0 {
            Either::Left(sync_client) => request.request(sync_client),
            Either::Right(async_client) => request.request_async(async_client).await,
        }?;

        log::trace!("match response kind");
        match response.response_kind() {
            ResponseEnum::Immediate { credential } => vec![credential.to_owned()],
            ResponseEnum::ImmediateMany { credentials } => credentials.to_owned(),
            ResponseEnum::Deferred { .. } => todo!(),
        }
    } else {
        log::trace!("processing muliple requests");

        log::trace!("build request");
        let request = session
            .get_client()
            .batch_request_credential(
                session.get_token_response()?.access_token().clone(),
                credential_requests.to_vec(),
            )?
            .set_proofs::<Oid4vciError>(
                proofs_of_possession
                    .into_iter()
                    .map(|p| Proof::Jwt { jwt: p })
                    .collect(),
            )?;

        log::trace!("execute with http client");
        let response = match &http_client.0 {
            Either::Left(sync_client) => request.request(sync_client)?,
            Either::Right(async_client) => request.request_async(async_client).await?,
        };

        log::trace!("map match response kind");
        response
            .credential_responses()
            .iter()
            .flat_map(|r| match r {
                ResponseEnum::Immediate { credential } => vec![credential.to_owned()],
                ResponseEnum::ImmediateMany { credentials } => credentials.to_owned(),
                ResponseEnum::Deferred { .. } => todo!(),
            })
            .collect()
    };

    if !options.verify_after_exchange.unwrap_or(false) {
        futures::future::try_join_all(credential_responses.into_iter().map(
            |credential_response| async {
                use oid4vci::profiles::core::profiles::CoreProfilesCredentialResponseType::*;

                match credential_response {
                    CredentialResponseType::Core(core_response) => match *core_response {
                        JwtVcJson(response) => {
                            log::trace!("processing a JwtVcJson");
                            let ret = response.as_bytes().to_vec();

                            Ok(CredentialResponse {
                                format: CredentialFormat::JwtVcJson,
                                payload: ret,
                            })
                        }
                        JwtVcJsonLd(response) => {
                            log::trace!("processing a JwtVcJsonLd");
                            let ret = serde_json::to_vec(&response)?;
                            Ok(CredentialResponse {
                                format: CredentialFormat::JwtVcJsonLd,
                                payload: ret,
                            })
                        }
                        LdpVc(response) => {
                            log::trace!("processing an LdpVc");
                            let ret = serde_json::to_vec(&response)?;
                            Ok(CredentialResponse {
                                format: CredentialFormat::LdpVc,
                                payload: ret,
                            })
                        }
                        MsoMdoc(response) => {
                            log::trace!("processing an MsoMdoc");
                            let ret = isomdl::cbor::to_vec(&response.0)
                                .map_err(|e| Oid4vciError::Generic(e.to_string()))?;
                            Ok(CredentialResponse {
                                format: CredentialFormat::MsoMdoc,
                                payload: BASE64_URL_SAFE_NO_PAD.encode(&ret).as_bytes().to_vec(),
                            })
                        }
                    },
                    CredentialResponseType::Custom(custom_response) => match custom_response {
                        custom::profiles::CustomProfilesCredentialResponseType::VcSdJwt(
                            response,
                        ) => {
                            log::trace!("processing a VcSdJwt");
                            let ret = response.as_bytes().to_vec();

                            Ok(CredentialResponse {
                                format: CredentialFormat::VCDM2SdJwt,
                                payload: ret,
                            })
                        }
                    },
                }
            },
        ))
        .await
    } else {
        log::trace!("create vm_resolver");
        let vm_resolver = AnyDidMethod::default().into_vm_resolver();
        log::trace!("create verification params");
        let params = match context_map {
            Some(context_map) => VerificationParameters::from_resolver(vm_resolver)
                .with_json_ld_loader(context_loader_from_map(context_map)?),
            None => VerificationParameters::from_resolver(vm_resolver),
        };

        log::trace!("verify and convert http response into credential response");
        futures::future::try_join_all(credential_responses.into_iter().map(
            |credential_response| async {
                use oid4vci::profiles::core::profiles::CoreProfilesCredentialResponseType::*;

                match credential_response {
                    CredentialResponseType::Core(core_response) => match *core_response {
                        JwtVcJson(response) => {
                            log::trace!("processing a JwtVcJson");
                            let ret = response.as_bytes().to_vec();

                            Ok(CredentialResponse {
                                format: CredentialFormat::JwtVcJson,
                                payload: response.verify_jwt(&params).await.map(|_| ret)?,
                            })
                        }
                        JwtVcJsonLd(response) => {
                            log::trace!("processing a JwtVcJsonLd");
                            let vc = serde_json::to_string(&response)?;
                            let ret = serde_json::to_vec(&response)?;
                            Ok(CredentialResponse {
                                format: CredentialFormat::JwtVcJsonLd,
                                payload: any_credential_from_json_str(&vc)?
                                    .verify(&params)
                                    .await
                                    .map(|_| ret)?,
                            })
                        }
                        LdpVc(response) => {
                            log::trace!("processing an LdpVc");
                            // let vc: AnyDataIntegrity<AnyJsonCredential> =
                            //     serde_json::from_value(response)?;
                            let ret = serde_json::to_vec(&response)?;
                            Ok(CredentialResponse {
                                format: CredentialFormat::LdpVc,
                                payload: response.verify(&params).await.map(|_| ret)?,
                            })
                        }
                        // TODO: Implement version with verify
                        MsoMdoc(_) => todo!(),
                    },
                    CredentialResponseType::Custom(custom_response) => match custom_response {
                        custom::profiles::CustomProfilesCredentialResponseType::VcSdJwt(
                            response,
                        ) => {
                            log::trace!("processing a VcSdJwt");
                            let rt = tokio::runtime::Runtime::new().unwrap();
                            let ret = response.as_bytes().to_vec();

                            Ok(CredentialResponse {
                                format: CredentialFormat::VCDM2SdJwt,
                                payload: rt.block_on(async {
                                    response.decode_verify_concealed(&params).await.map(|_| ret)
                                })?,
                            })
                        }
                    },
                }
            },
        ))
        .await
    }
}
