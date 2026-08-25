use std::collections::HashMap;
use std::sync::Arc;

use vcalm_rs::ports::StoredCredential;

use crate::credential::ParsedCredential;
use crate::oid4vp::presentation::PresentationSigner;
use crate::vcalm_adapters::{json_ld_body, SdkCredential, VcalmSignerAdapter};
use crate::vdc_collection::VdcCollection;

pub mod wire;

pub use wire::{
    AcceptedIssuerEntry, AcceptedMethodEntry, CredentialQuery, CryptosuiteEntry, EnvelopeEntry,
    OfferedValidity, ProblemDetails, Query, StepResult, VcalmError, VcalmOfferedCredential,
    VcalmRequestedField, Vpr,
};

/// Project a host-provided wallet credential into the shape VCALM carries.
fn to_stored(credential: Arc<ParsedCredential>) -> StoredCredential<SdkCredential> {
    StoredCredential {
        id: credential.id(),
        body: json_ld_body(&credential),
        host: credential,
    }
}

#[derive(uniffi::Object)]
pub struct VcalmHolder(Arc<vcalm_rs::holder::VcalmHolder<SdkCredential>>);

#[uniffi::export(async_runtime = "tokio")]
impl VcalmHolder {
    #[uniffi::constructor]
    pub async fn new_session(
        vdc_collection: Arc<VdcCollection>,
        trusted_dids: Vec<String>,
        signer: Box<dyn PresentationSigner>,
        key_id: String,
        context_map: Option<HashMap<String, String>>,
    ) -> Result<Arc<Self>, VcalmError> {
        let context_map = Some(match context_map {
            Some(map) if !map.is_empty() => map,
            _ => crate::context::default_ld_json_context(),
        });

        let inner = vcalm_rs::holder::VcalmHolder::new_session(
            vdc_collection,
            trusted_dids,
            Arc::new(VcalmSignerAdapter {
                signer: Arc::new(signer),
                key_id,
            }),
            context_map,
        )
        .await?;

        Ok(Arc::new(Self(inner)))
    }

    /// Seed the QBE matcher with credentials from the host app's wallet packs.
    pub async fn provide_credentials(&self, credentials: Vec<Arc<ParsedCredential>>) {
        self.0
            .provide_credentials(credentials.into_iter().map(to_stored).collect())
            .await
    }

    /// Begin a `vcapi` exchange.
    pub async fn start_exchange(
        self: Arc<Self>,
        input: String,
        auth_header: Option<String>,
    ) -> Result<StepResult, VcalmError> {
        Ok(self
            .0
            .clone()
            .start_exchange(input, auth_header)
            .await?
            .into())
    }

    pub async fn matched_credentials(&self) -> Result<Vec<VcalmMatchedCredentials>, VcalmError> {
        Ok(self
            .0
            .matched_credentials()
            .await?
            .into_iter()
            .map(|m| VcalmMatchedCredentials {
                query_index: m.query_index,
                credentials: m
                    .credentials
                    .into_iter()
                    .map(|c| VcalmMatchedCredential {
                        credential: c.credential.host,
                        selective_disclosure: c.selective_disclosure,
                    })
                    .collect(),
            })
            .collect())
    }

    pub async fn requested_fields(&self) -> Result<Vec<VcalmRequestedField>, VcalmError> {
        Ok(self
            .0
            .requested_fields()
            .await?
            .into_iter()
            .map(Into::into)
            .collect())
    }

    /// Build, sign and POST a verifiable presentation.
    pub async fn submit_presentation(
        self: Arc<Self>,
        selected_credentials: Vec<Arc<ParsedCredential>>,
        allow_domain_mismatch: bool,
    ) -> Result<StepResult, VcalmError> {
        Ok(self
            .0
            .clone()
            .submit_presentation(
                selected_credentials.into_iter().map(to_stored).collect(),
                allow_domain_mismatch,
            )
            .await?
            .into())
    }

    pub async fn accept_offer(self: Arc<Self>) -> Result<StepResult, VcalmError> {
        Ok(self.0.clone().accept_offer().await?.into())
    }

    pub async fn reject_offer(self: Arc<Self>) -> Result<StepResult, VcalmError> {
        Ok(self.0.clone().reject_offer().await?.into())
    }

    pub async fn offered_credentials(&self) -> Result<Vec<VcalmOfferedCredential>, VcalmError> {
        Ok(self
            .0
            .offered_credentials()
            .await?
            .into_iter()
            .map(Into::into)
            .collect())
    }
}

/// The credentials matching one QueryByExample query in the current VPR.
#[derive(uniffi::Record)]
pub struct VcalmMatchedCredentials {
    pub query_index: u32,
    pub credentials: Vec<VcalmMatchedCredential>,
}

#[derive(uniffi::Record)]
pub struct VcalmMatchedCredential {
    pub credential: Arc<ParsedCredential>,
    pub selective_disclosure: bool,
}
