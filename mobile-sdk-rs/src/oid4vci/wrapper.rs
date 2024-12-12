use std::{
    collections::HashMap,
    sync::{Arc, Mutex},
};

use super::{
    oid4vci_exchange_credential, oid4vci_exchange_token, oid4vci_get_metadata, oid4vci_initiate,
    oid4vci_initiate_with_offer, AsyncHttpClient, CredentialResponse, IHttpClient, Oid4vciError,
    Oid4vciMetadata, Oid4vciSession, SyncHttpClient,
};

#[derive(uniffi::Object)]
pub struct Oid4vci {
    http_client: Arc<IHttpClient>,
    session: Mutex<Option<Arc<Oid4vciSession>>>,
    context_map: Mutex<Option<HashMap<String, String>>>,
}

impl Oid4vci {
    fn context_map(&self) -> Result<Option<HashMap<String, String>>, Oid4vciError> {
        let context_map = self
            .context_map
            .lock()
            .map_err(|_| Oid4vciError::LockError("context_map".into()))?;

        Ok(context_map.clone())
    }

    fn session(&self) -> Result<Arc<Oid4vciSession>, Oid4vciError> {
        let session = self
            .session
            .lock()
            .map_err(|_| Oid4vciError::LockError("session".into()))?;

        let session: Arc<Oid4vciSession> = match session.as_ref() {
            Some(session) => session.clone(),
            None => return Err(Oid4vciError::InvalidSession("session unset".to_string())),
        };

        Ok(session)
    }

    fn set_session(&self, value: Oid4vciSession) -> Result<(), Oid4vciError> {
        let mut session = self
            .session
            .lock()
            .map_err(|_| Oid4vciError::LockError("session".into()))?;

        *session = match session.take() {
            Some(_) => {
                return Err(Oid4vciError::InvalidSession(
                    "session already set".to_string(),
                ))
            }
            None => Some(value.into()),
        };

        Ok(())
    }
}

#[uniffi::export]
impl Oid4vci {
    #[uniffi::constructor(name = "new")]
    fn new_default() -> Arc<Self> {
        Self::new_async()
    }

    #[uniffi::constructor(name = "new_with_default_sync_client")]
    fn new_sync() -> Arc<Self> {
        todo!("add reqwest default sync client")
    }

    #[uniffi::constructor(name = "new_with_default_async_client")]
    fn new_async() -> Arc<Self> {
        todo!("add reqwest default async client")
    }

    #[uniffi::constructor(name = "new_with_sync_client")]
    fn with_sync_client(client: Arc<dyn SyncHttpClient>) -> Arc<Self> {
        let http_client = Arc::new(client.into());
        Self {
            session: Mutex::new(None),
            context_map: Mutex::new(None),
            http_client,
        }
        .into()
    }

    #[uniffi::constructor(name = "new_with_async_client")]
    pub(crate) fn with_async_client(client: Arc<dyn AsyncHttpClient>) -> Arc<Self> {
        let http_client = Arc::new(client.into());
        Self {
            session: Mutex::new(None),
            context_map: Mutex::new(None),
            http_client,
        }
        .into()
    }

    pub fn set_context_map(&self, values: HashMap<String, String>) -> Result<(), Oid4vciError> {
        let mut context_map = self
            .context_map
            .lock()
            .map_err(|_| Oid4vciError::LockError("context_map".into()))?;

        *context_map = Some(values);

        Ok(())
    }

    fn clear_context_map(&self) -> Result<(), Oid4vciError> {
        let mut context_map = self
            .context_map
            .lock()
            .map_err(|_| Oid4vciError::LockError("context_map".into()))?;

        *context_map = None;

        Ok(())
    }

    fn initiate_logger(&self) {
        #[cfg(target_os = "android")]
        android_logger::init_once(
            android_logger::Config::default()
                .with_max_level(log::LevelFilter::Trace)
                .with_tag("MOBILE_SDK_RS"),
        );
    }

    pub fn get_metadata(&self) -> Result<Oid4vciMetadata, Oid4vciError> {
        oid4vci_get_metadata(self.session()?)
    }

    pub async fn initiate_with_offer(
        &self,
        credential_offer: String,
        client_id: String,
        redirect_url: String,
    ) -> Result<(), Oid4vciError> {
        let session = oid4vci_initiate_with_offer(
            credential_offer,
            client_id,
            redirect_url,
            self.http_client.clone(),
        )
        .await?;
        self.set_session(session)
    }

    async fn initiate(
        &self,
        base_url: String,
        client_id: String,
        redirect_url: String,
    ) -> Result<(), Oid4vciError> {
        let session =
            oid4vci_initiate(base_url, client_id, redirect_url, self.http_client.clone()).await?;
        self.set_session(session)
    }

    pub async fn exchange_token(&self) -> Result<Option<String>, Oid4vciError> {
        oid4vci_exchange_token(self.session()?, self.http_client.clone()).await
    }

    pub async fn exchange_credential(
        &self,
        proofs_of_possession: Vec<String>,
    ) -> Result<Vec<CredentialResponse>, Oid4vciError> {
        oid4vci_exchange_credential(
            self.session()?,
            proofs_of_possession,
            self.context_map()?,
            self.http_client.clone(),
        )
        .await
    }
}

#[cfg(test)]
mod tests {
    use std::str::FromStr;

    use oid4vci::{
        proof_of_possession::{
            ProofOfPossession, ProofOfPossessionController, ProofOfPossessionParams,
        },
        types::Nonce,
    };
    use ssi::{
        claims::{jwt::RegisteredClaims, JwsPayload},
        dids::DIDURLBuf,
        JWK,
    };
    use url::Url;

    use crate::did;

    use super::*;

    #[tokio::test]
    async fn potato() {
        let credential_offer_request = "openid-credential-offer://?credential_offer_uri=https%3A%2F%2Fqa.veresexchanger.dev%2Fexchangers%2Fz1A68iKqcX2HbQGQfVSfFnjkM%2Fexchanges%2Fz19jAriNqTgb3tf62dtwvYcyV%2Fopenid%2Fcredential-offer".to_string();
        let client = oid4vci::oauth2::reqwest::Client::new();
        let client: Arc<dyn AsyncHttpClient> = Arc::new(client);
        let wrapper = Oid4vci::with_async_client(client.into());
        wrapper
            .initiate_with_offer(
                credential_offer_request,
                "did:example:1234".to_string(),
                "test://".to_string(),
            )
            .await
            .unwrap();
        let nonce = wrapper.exchange_token().await.unwrap();
        let jwk = include_str!("../../test.jwk").to_string();
        let key: JWK = serde_json::from_str(&jwk).unwrap();

        let did_method = did::DidMethod::Jwk;
        let audience = wrapper.get_metadata().unwrap().issuer();
        let nonce = nonce;

        let issuer = did_method.did_from_jwk(&jwk).unwrap();
        let vm = did_method.vm_from_jwk(&jwk).await.unwrap();

        let pop_params = ProofOfPossessionParams {
            audience: Url::from_str(&audience).unwrap(),
            issuer,
            controller: ProofOfPossessionController {
                vm: Some(DIDURLBuf::from_string(vm).unwrap()),
                jwk: JWK::from_str(&jwk).unwrap(),
            },
            nonce: nonce.map(Nonce::new),
        };

        let vc = ProofOfPossession::generate(
            &pop_params,
            None.map(time::Duration::seconds)
                .unwrap_or(time::Duration::minutes(5)),
        );
        let vc: RegisteredClaims =
            serde_json::from_str(&serde_json::to_string(&vc.body).unwrap()).unwrap();
        let jwt = vc.sign(&key).await.unwrap();
        let jwt = jwt.to_string();

        let pairs = vec![
            (
                "https://w3id.org/vdl/aamva/v1",
                r#"{
  "@context": {
    "@protected": true,
    "aamva_aka_family_name_v2": "https://w3id.org/vdl/aamva#akaFamilyNameV2",
    "aamva_aka_given_name_v2": "https://w3id.org/vdl/aamva#akaGivenNameV2",
    "aamva_aka_suffix": "https://w3id.org/vdl/aamva#akaSuffix",
    "aamva_cdl_indicator": {
      "@id": "https://w3id.org/vdl/aamva#cdlIndicator",
      "@type": "http://www.w3.org/2001/XMLSchema#unsignedInt"
    },
    "aamva_dhs_compliance": "https://w3id.org/vdl/aamva#dhsCompliance",
    "aamva_dhs_compliance_text": "https://w3id.org/vdl/aamva#dhsCompliance_text",
    "aamva_dhs_temporary_lawful_status": {
      "@id": "https://w3id.org/vdl/aamva#dhsTemporaryLawfulStatus",
      "@type": "http://www.w3.org/2001/XMLSchema#unsignedInt"
    },
    "aamva_domestic_driving_privileges": {
      "@id": "https://w3id.org/vdl/aamva#domesticDrivingPrivileges",
      "@type": "@json"
    },
    "aamva_edl_credential": {
      "@id": "https://w3id.org/vdl/aamva#edlCredential",
      "@type": "http://www.w3.org/2001/XMLSchema#unsignedInt"
    },
    "aamva_family_name_truncation": "https://w3id.org/vdl/aamva#familyNameTruncation",
    "aamva_given_name_truncation": "https://w3id.org/vdl/aamva#givenNameTruncation",
    "aamva_hazmat_endorsement_expiration_date": {
      "@id": "https://w3id.org/vdl/aamva#hazmatEndorsementExpirationDate",
      "@type": "http://www.w3.org/2001/XMLSchema#dateTime"
    },
    "aamva_name_suffix": "https://w3id.org/vdl/aamva#nameSuffix",
    "aamva_organ_donor": {
      "@id": "https://w3id.org/vdl/aamva#organDonor",
      "@type": "http://www.w3.org/2001/XMLSchema#unsignedInt"
    },
    "aamva_race_ethnicity": "https://w3id.org/vdl/aamva#raceEthnicity",
    "aamva_resident_county": "https://w3id.org/vdl/aamva#residentCounty",
    "aamva_sex": {
      "@id": "https://w3id.org/vdl/aamva#sex",
      "@type": "http://www.w3.org/2001/XMLSchema#unsignedInt"
    },
    "aamva_veteran": {
      "@id": "https://w3id.org/vdl/aamva#veteran",
      "@type": "http://www.w3.org/2001/XMLSchema#unsignedInt"
    },
    "aamva_weight_range": {
      "@id": "https://w3id.org/vdl/aamva#weightRange",
      "@type": "http://www.w3.org/2001/XMLSchema#unsignedInt"
    }
  }
}"#,
            ),
            (
                "https://examples.vcplayground.org/contexts/shim-render-method-term/v1.json",
                r#"{
  "@context": {
    "@protected": true,
    "renderMethod": {
      "@id": "https://www.w3.org/2018/credentials#renderMethod",
      "@type": "@id"
    }
  }
}"#,
            ),
            (
                "https://w3id.org/vc/render-method/v2rc1",
                r#"{
  "@context": {
    "@protected": true,
    "id": "@id",
    "type": "@type",
    "SvgRenderingTemplate2023": {
      "@id": "https://w3id.org/vc/render-method#SvgRenderingTemplate2023",
      "@context": {
        "@protected": true,
        "id": "@id",
        "type": "@type",
        "css3MediaQuery": {
          "@id": "https://w3id.org/vc/render-method#css3MediaQuery"
        },
        "digestMultibase": {
          "@id": "https://w3id.org/security#digestMultibase",
          "@type": "https://w3id.org/security#multibase"
        },
        "name": "https://schema.org/name"
      }
    },
    "SvgRenderingTemplate2024": {
      "@id": "https://w3id.org/vc/render-method#SvgRenderingTemplate2024",
      "@context": {
        "@protected": true,
        "id": "@id",
        "type": "@type",
        "digestMultibase": {
          "@id": "https://w3id.org/security#digestMultibase",
          "@type": "https://w3id.org/security#multibase"
        },
        "mediaQuery": "https://w3id.org/vc/render-method#mediaQuery",
        "mediaType": "https://schema.org/encodingFormat",
        "name": "https://schema.org/name",
        "template": "https://w3id.org/vc/render-method#template"
      }
    }
  }
}"#,
            ),
        ]
        .into_iter()
        .map(|(a, b)| (a.to_string(), b.to_string()))
        .collect();
        wrapper.set_context_map(pairs).unwrap();

        let creds = wrapper
            .exchange_credential(vec![jwt], Oid4vciExchangeOptions::default())
            .await
            .unwrap();
        for i in creds {
            println!("format: {}", i.format);
            println!(
                "payload: {}",
                serde_json::from_slice::<serde_json::Value>(&i.payload).unwrap()
            );
        }
        assert!(false);
    }
}
