use std::sync::Arc;

use serde_json::Value;
use ssi::claims::data_integrity::CryptosuiteString;
use ssi::crypto::Algorithm;
use uuid::Uuid;

use vcalm_rs::ports::{
    NewCredential, PortError, StorableFormat, StoredCredential, VcalmCredentialStore, VcalmSigner,
};

use crate::credential::{json_vc::JsonVc, Credential, ParsedCredential};
use crate::oid4vp::presentation::PresentationSigner;
use crate::vdc_collection::VdcCollection;

pub(crate) type SdkCredential = Arc<ParsedCredential>;

#[async_trait::async_trait]
impl VcalmCredentialStore for VdcCollection {
    type Credential = SdkCredential;

    async fn list_ids(&self) -> Result<Vec<Uuid>, PortError> {
        self.all_entries()
            .await
            .map_err(|e| PortError::Storage(e.to_string()))
    }

    async fn get(&self, id: Uuid) -> Result<Option<StoredCredential<Self::Credential>>, PortError> {
        let Some(credential) = VdcCollection::get(self, id)
            .await
            .map_err(|e| PortError::Storage(e.to_string()))?
        else {
            return Ok(None);
        };

        let parsed = credential
            .try_into_parsed()
            .map_err(|e| PortError::Decode(e.to_string()))?;

        // A non-JSON-LD credential (mdoc, SD-JWT) gets a `Null` body
        let body = parsed
            .as_json_vc()
            .map(|json_vc| json_vc.raw.clone())
            .unwrap_or(Value::Null);

        Ok(Some(StoredCredential {
            id,
            body,
            host: parsed,
        }))
    }

    async fn add(&self, credential: NewCredential) -> Result<(), PortError> {
        match credential.format {
            StorableFormat::LdpVc => {}
        }

        let json_vc = JsonVc::new_from_json(credential.body.to_string())
            .map_err(|e| PortError::Decode(e.to_string()))?;
        let generic = ParsedCredential::new_ldp_vc(json_vc)
            .into_generic_form()
            .map_err(|e| PortError::Decode(e.to_string()))?;

        let stored = Credential {
            id: credential.id,
            key_alias: None,
            ..generic
        };

        VdcCollection::add(self, &stored)
            .await
            .map_err(|e| PortError::Storage(e.to_string()))
    }
}

/// Bridges `PresentationSigner` onto VCALM's signer port.
#[derive(Debug)]
pub(crate) struct VcalmSignerAdapter(pub(crate) Arc<Box<dyn PresentationSigner>>);

#[async_trait::async_trait]
impl VcalmSigner for VcalmSignerAdapter {
    async fn sign(&self, payload: Vec<u8>) -> Result<Vec<u8>, PortError> {
        self.0
            .sign(payload)
            .await
            .map_err(|e| PortError::Signing(e.to_string()))
    }

    fn algorithm(&self) -> Algorithm {
        self.0.algorithm()
    }

    async fn verification_method(&self) -> String {
        self.0.verification_method().await
    }

    fn did(&self) -> String {
        self.0.did()
    }

    fn cryptosuite(&self) -> CryptosuiteString {
        self.0.cryptosuite()
    }

    fn jwk(&self) -> String {
        self.0.jwk()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::local_store::LocalStore;
    use serde_json::json;
    use vcalm_rs::holder::VcalmHolder;

    fn ldp_vc(id: &str, given_name: &str) -> Value {
        json!({
            "@context": [
                "https://www.w3.org/ns/credentials/v2",
                { "givenName": "https://schema.org/givenName" }
            ],
            "id": id,
            "type": ["VerifiableCredential"],
            "issuer": "https://issuer.example/",
            "credentialSubject": { "givenName": given_name }
        })
    }

    #[tokio::test]
    async fn vdc_collection_round_trips_through_the_port() {
        let vdc = Arc::new(VdcCollection::new(Arc::new(LocalStore::new())));
        let id = Uuid::new_v4();

        VcalmCredentialStore::add(
            vdc.as_ref(),
            NewCredential {
                id,
                body: ldp_vc("urn:uuid:adapter-1", "Jane"),
                format: StorableFormat::LdpVc,
            },
        )
        .await
        .expect("store via the port");

        let ids = VcalmCredentialStore::list_ids(vdc.as_ref())
            .await
            .expect("list");
        assert_eq!(ids, vec![id], "the stable id is preserved verbatim");

        let got = VcalmCredentialStore::get(vdc.as_ref(), id)
            .await
            .expect("get")
            .expect("present");
        assert_eq!(got.body["credentialSubject"]["givenName"], json!("Jane"));
        assert!(got.host.as_json_vc().is_some(), "host type survives intact");
    }

    /// Re-adding under the same id overwrites — the contract `accept_offer`'s
    /// idempotency depends on.
    #[tokio::test]
    async fn add_is_idempotent_on_duplicate_id() {
        let vdc = Arc::new(VdcCollection::new(Arc::new(LocalStore::new())));
        let id = Uuid::new_v4();

        for name in ["First", "Second"] {
            VcalmCredentialStore::add(
                vdc.as_ref(),
                NewCredential {
                    id,
                    body: ldp_vc("urn:uuid:adapter-idem", name),
                    format: StorableFormat::LdpVc,
                },
            )
            .await
            .expect("store");
        }

        assert_eq!(vdc.all_entries().await.unwrap().len(), 1);
        let got = VcalmCredentialStore::get(vdc.as_ref(), id)
            .await
            .unwrap()
            .unwrap();
        assert_eq!(got.body["credentialSubject"]["givenName"], json!("Second"));
    }

    /// End to end: a holder built from the real `VdcCollection`, the real
    /// signer, and VCALM's default engine.
    #[tokio::test]
    async fn holder_constructs_over_the_real_sdk_types() {
        let vdc = Arc::new(VdcCollection::new(Arc::new(LocalStore::new())));
        let signer: Box<dyn PresentationSigner> = Box::new(crate::tests::load_signer());
        let signer = Arc::new(VcalmSignerAdapter(Arc::new(signer)));

        let holder: Arc<VcalmHolder<SdkCredential>> = VcalmHolder::new_session(
            vdc,
            vec![],
            signer,
            Some(crate::context::default_ld_json_context()),
        )
        .await
        .expect("holder over real SDK types");

        // No VPR yet ⇒ empty, not an error.
        assert!(holder.matched_credentials().await.unwrap().is_empty());
    }
}
