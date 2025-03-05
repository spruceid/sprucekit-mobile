//! A module to handle presentation of an ISO 18013-5 mobile driving license (mDL).
//!
//! This module manages the session for presentation of an mDL, including generating
//! the QR code and BLE parameters, as well as managing the types of information requested
//! by the reader.
//!
//! The presentation process is begun by calling [`initialize_mdl_presentation`] and
//! passing the id of the mdoc to be used as well as a UUID that the client
//! will use for the BLE central client:
//!

use crate::credential::mdoc::Mdoc;
use crate::{storage_manager::StorageManagerInterface, vdc_collection::VdcCollection};
use std::ops::DerefMut;
use std::{
    collections::HashMap,
    sync::{Arc, Mutex},
};

use isomdl::definitions::x509::trust_anchor::TrustAnchorRegistry;
use isomdl::{
    definitions::{
        device_engagement::{CentralClientMode, DeviceRetrievalMethods},
        helpers::NonEmptyMap,
        session, BleOptions, DeviceRetrievalMethod, SessionEstablishment,
    },
    presentation::device::{self, SessionManagerInit},
};
use uuid::Uuid;

/// Begin the mDL presentation process for the holder when the desired
/// Mdoc is already stored in a [VdcCollection].
///
/// Initializes the presentation session for an ISO 18013-5 mDL and stores
/// the session state object in the device storage_manager.
///
/// Arguments:
/// mdoc_id: unique identifier for the credential to present, to be looked up
///          in the VDC collection
/// uuid:    the Bluetooth Low Energy Client Central Mode UUID to be used
///
/// Returns:
/// A Result, with the `Ok` containing a tuple consisting of an enum representing
/// the state of the presentation, a String containing the QR code URI, and a
/// String containing the BLE ident.
///
#[uniffi::export]
pub async fn initialize_mdl_presentation(
    mdoc_id: Uuid,
    uuid: Uuid,
    storage_manager: Arc<dyn StorageManagerInterface>,
) -> Result<MdlPresentationSession, SessionError> {
    let vdc_collection = VdcCollection::new(storage_manager);

    let document = vdc_collection
        .get(mdoc_id)
        .await
        .map_err(|_| SessionError::Generic {
            value: "Error in VDC Collection".to_string(),
        })?
        .ok_or(SessionError::Generic {
            value: "No credential with that ID in the VDC collection.".to_string(),
        })?;

    let mdoc: Arc<Mdoc> = document.try_into().map_err(|e| SessionError::Generic {
        value: format!("Error retrieving MDoc from storage: {e:}"),
    })?;
    let drms = DeviceRetrievalMethods::new(DeviceRetrievalMethod::BLE(BleOptions {
        peripheral_server_mode: None,
        central_client_mode: Some(CentralClientMode { uuid }),
    }));
    let session = SessionManagerInit::initialise(
        NonEmptyMap::new("org.iso.18013.5.1.mDL".into(), mdoc.document().clone()),
        Some(drms),
        None,
    )
    .map_err(|e| SessionError::Generic {
        value: format!("Could not initialize session: {e:?}"),
    })?;
    let ble_ident = session
        .ble_ident()
        .map_err(|e| SessionError::Generic {
            value: format!("Couldn't get BLE identification: {e:?}").to_string(),
        })?
        .to_vec();
    let (engaged_state, qr_code_uri) =
        session.qr_engagement().map_err(|e| SessionError::Generic {
            value: format!("Could not generate qr engagement: {e:?}"),
        })?;
    Ok(MdlPresentationSession {
        engaged: Mutex::new(engaged_state),
        in_process: Mutex::new(None),
        qr_code_uri,
        ble_ident,
    })
}

/// Begin the mDL presentation process for the holder by passing in the credential
/// to be presented in the form of an [Mdoc] object.
///
/// Initializes the presentation session for an ISO 18013-5 mDL and stores
/// the session state object in the device storage_manager.
///
/// Arguments:
/// mdoc: the Mdoc to be presented, as an [Mdoc] object
/// uuid: the Bluetooth Low Energy Client Central Mode UUID to be used
///
/// Returns:
/// A Result, with the `Ok` containing a tuple consisting of an enum representing
/// the state of the presentation, a String containing the QR code URI, and a
/// String containing the BLE ident.
///
#[uniffi::export]
pub fn initialize_mdl_presentation_from_bytes(
    mdoc: Arc<Mdoc>,
    uuid: Uuid,
) -> Result<MdlPresentationSession, SessionError> {
    let drms = DeviceRetrievalMethods::new(DeviceRetrievalMethod::BLE(BleOptions {
        peripheral_server_mode: None,
        central_client_mode: Some(CentralClientMode { uuid }),
    }));
    let session = SessionManagerInit::initialise(
        NonEmptyMap::new("org.iso.18013.5.1.mDL".into(), mdoc.document().clone()),
        Some(drms),
        None,
    )
    .map_err(|e| SessionError::Generic {
        value: format!("Could not initialize session: {e:?}"),
    })?;
    let ble_ident = session
        .ble_ident()
        .map_err(|e| SessionError::Generic {
            value: format!("Couldn't get BLE identification: {e:?}").to_string(),
        })?
        .to_vec();
    let (engaged_state, qr_code_uri) =
        session.qr_engagement().map_err(|e| SessionError::Generic {
            value: format!("Could not generate qr engagement: {e:?}"),
        })?;
    Ok(MdlPresentationSession {
        engaged: Mutex::new(engaged_state),
        in_process: Mutex::new(None),
        qr_code_uri,
        ble_ident,
    })
}

#[derive(uniffi::Object)]
pub struct MdlPresentationSession {
    engaged: Mutex<device::SessionManagerEngaged>,
    in_process: Mutex<Option<InProcessRecord>>,
    pub qr_code_uri: String,
    pub ble_ident: Vec<u8>,
}

#[derive(uniffi::Object, Clone)]
struct InProcessRecord {
    session: device::SessionManager,
    items_request: device::RequestedItems,
}

#[uniffi::export]
impl MdlPresentationSession {
    /// Handle a request from a reader that is seeking information from the mDL holder.
    ///
    /// Takes the raw bytes received from the reader by the holder over the transmission
    /// technology. Returns a Vector of information items requested by the reader, or an
    /// error.
    pub fn handle_request(&self, request: Vec<u8>) -> Result<Vec<ItemsRequest>, RequestError> {
        let (session_manager, items_requests) = {
            let session_establishment: SessionEstablishment = isomdl::cbor::from_slice(&request)
                .map_err(|e| RequestError::Generic {
                    value: format!("Could not deserialize request: {e:?}"),
                })?;
            self.engaged
                .lock()
                .map_err(|_| RequestError::Generic {
                    value: "Could not lock mutex".to_string(),
                })?
                .clone()
                .process_session_establishment(
                    session_establishment,
                    TrustAnchorRegistry::default(),
                )
                .map_err(|e| RequestError::Generic {
                    value: format!("Could not process process session establishment: {e:?}"),
                })?
        };

        let mut in_process = self.in_process.lock().map_err(|_| RequestError::Generic {
            value: "Could not lock mutex".to_string(),
        })?;
        *in_process = Some(InProcessRecord {
            session: session_manager,
            items_request: items_requests.items_request.clone(),
        });

        Ok(items_requests
            .items_request
            .into_iter()
            .map(|req| ItemsRequest {
                doc_type: req.doc_type,
                namespaces: req
                    .namespaces
                    .into_inner()
                    .into_iter()
                    .map(|(ns, es)| {
                        let items_request = es.into_inner().into_iter().collect();
                        (ns, items_request)
                    })
                    .collect(),
            })
            .collect())
    }

    /// Constructs the response to be sent from the holder to the reader containing
    /// the items of information the user has consented to share.
    ///
    /// Takes a HashMap of items the user has authorized the app to share, as well
    /// as the id of a key stored in the key manager to be used to sign the response.
    /// Returns a byte array containing the signed response to be returned to the
    /// reader.
    pub fn generate_response(
        &self,
        permitted_items: HashMap<String, HashMap<String, Vec<String>>>,
    ) -> Result<Vec<u8>, SignatureError> {
        let permitted = permitted_items
            .into_iter()
            .map(|(doc_type, namespaces)| {
                let ns = namespaces.into_iter().collect();
                (doc_type, ns)
            })
            .collect();
        if let Some(ref mut in_process) = self.in_process.lock().unwrap().deref_mut() {
            in_process
                .session
                .prepare_response(&in_process.items_request, permitted);
            Ok(in_process
                .session
                .get_next_signature_payload()
                .map(|(_, payload)| payload)
                .ok_or(SignatureError::Generic {
                    value: "Failed to get next signature payload".to_string(),
                })?
                .to_vec())
        } else {
            Err(SignatureError::Generic {
                value: "Could not get lock on session".to_string(),
            })
        }
    }

    pub fn submit_response(&self, signature: Vec<u8>) -> Result<Vec<u8>, SignatureError> {
        let signature = p256::ecdsa::Signature::from_slice(&signature).map_err(|e| {
            SignatureError::InvalidSignature {
                value: e.to_string(),
            }
        })?;
        if let Some(ref mut in_process) = self.in_process.lock().unwrap().deref_mut() {
            in_process
                .session
                .submit_next_signature(signature.to_bytes().to_vec())
                .map_err(|e| SignatureError::Generic {
                    value: format!("Could not submit next signature: {e:?}"),
                })?;
            in_process
                .session
                .retrieve_response()
                .ok_or(SignatureError::TooManyDocuments)
        } else {
            Err(SignatureError::Generic {
                value: "Could not get lock on session".to_string(),
            })
        }
    }

    /// Terminates the mDL exchange session.
    ///
    /// Returns the termination message to be transmitted to the reader.
    pub fn terminate_session(&self) -> Result<Vec<u8>, TerminationError> {
        let msg = session::SessionData {
            data: None,
            status: Some(session::Status::SessionTermination),
        };
        let msg_bytes = isomdl::cbor::to_vec(&msg).map_err(|e| TerminationError::Generic {
            value: format!("Could not serialize message bytes: {e:?}"),
        })?;
        Ok(msg_bytes)
    }

    /// Returns the generated QR code
    pub fn get_qr_code_uri(&self) -> String {
        self.qr_code_uri.clone()
    }

    /// Returns the BLE identification
    pub fn get_ble_ident(&self) -> Vec<u8> {
        self.ble_ident.clone()
    }
}

#[derive(thiserror::Error, uniffi::Error, Debug)]
pub enum SessionError {
    #[error("{value}")]
    Generic { value: String },
}

#[derive(thiserror::Error, uniffi::Error, Debug)]
pub enum RequestError {
    #[error("{value}")]
    Generic { value: String },
}

#[derive(uniffi::Record, Clone)]
pub struct ItemsRequest {
    doc_type: String,
    namespaces: HashMap<String, HashMap<String, bool>>,
}

#[derive(thiserror::Error, uniffi::Error, Debug)]
pub enum ResponseError {
    #[error("no signature payload received from session manager")]
    MissingSignature,
    #[error("{value}")]
    Generic { value: String },
}

#[derive(thiserror::Error, uniffi::Error, Debug)]
pub enum SignatureError {
    #[error("Invalid DER signature: {value}")]
    InvalidSignature { value: String },
    #[error("there were more documents to sign, but we only expected to sign 1!")]
    TooManyDocuments,
    #[error("{value}")]
    Generic { value: String },
}

#[derive(thiserror::Error, uniffi::Error, Debug)]
pub enum TerminationError {
    #[error("{value}")]
    Generic { value: String },
}

#[derive(thiserror::Error, uniffi::Error, Debug)]
pub enum KeyTransformationError {
    #[error("{value}")]
    ToPKCS8 { value: String },
    #[error("{value}")]
    FromPKCS8 { value: String },
    #[error("{value}")]
    FromSEC1 { value: String },
    #[error("{value}")]
    ToSEC1 { value: String },
}

#[cfg(test)]
mod tests {
    use std::collections::BTreeMap;

    use isomdl::{
        definitions::{
            device_request::{self, DataElements},
            x509::trust_anchor::{PemTrustAnchor, TrustAnchorRegistry, TrustPurpose},
        },
        presentation::reader,
    };

    use crate::{
        crypto::{KeyAlias, KeyStore, RustTestKeyManager},
        local_store,
    };

    use super::*;

    #[test_log::test(tokio::test)]
    async fn end_to_end_ble_presentment_holder() {
        let key_alias = KeyAlias(Uuid::new_v4().to_string());
        let key_manager = Arc::new(RustTestKeyManager::default());
        key_manager
            .generate_p256_signing_key(key_alias.clone())
            .await
            .unwrap();
        let mdl = Arc::new(
            crate::mdl::util::generate_test_mdl(key_manager.clone(), key_alias.clone()).unwrap(),
        )
        .try_into()
        .unwrap();

        let smi = Arc::new(local_store::LocalStore::new());

        let vdc_collection = VdcCollection::new(smi.clone());
        vdc_collection.add(&mdl).await.unwrap();

        let presentation_session = initialize_mdl_presentation(mdl.id, Uuid::new_v4(), smi.clone())
            .await
            .unwrap();
        let namespaces: device_request::Namespaces = [(
            "org.iso.18013.5.1".to_string(),
            [
                ("given_name".to_string(), true),
                ("family_name".to_string(), false),
            ]
            .into_iter()
            .collect::<BTreeMap<String, bool>>()
            .try_into()
            .unwrap(),
        )]
        .into_iter()
        .collect::<BTreeMap<String, DataElements>>()
        .try_into()
        .unwrap();
        let trust_anchor = TrustAnchorRegistry::from_pem_certificates(vec![PemTrustAnchor {
            certificate_pem: include_str!("../../tests/res/mdl/utrecht-certificate.pem")
                .to_string(),
            purpose: TrustPurpose::Iaca,
        }])
        .unwrap();
        let (mut reader_session_manager, request, _ble_ident) =
            reader::SessionManager::establish_session(
                presentation_session.qr_code_uri.clone(),
                namespaces.clone(),
                trust_anchor,
            )
            .unwrap();
        let _request_data = presentation_session.handle_request(request).unwrap();
        let permitted_items = [(
            "org.iso.18013.5.1.mDL".to_string(),
            [(
                "org.iso.18013.5.1".to_string(),
                vec!["given_name".to_string()],
            )]
            .into_iter()
            .collect(),
        )]
        .into_iter()
        .collect();
        let signing_payload = presentation_session
            .generate_response(permitted_items)
            .unwrap();
        let key = key_manager.get_signing_key(key_alias).unwrap();
        let signature = key.sign(signing_payload).unwrap();
        let response = presentation_session.submit_response(signature).unwrap();
        let res = reader_session_manager.handle_response(&response);
        vdc_collection.delete(mdl.id).await.unwrap();
        assert_eq!(res.errors, BTreeMap::new());
    }

    #[test_log::test(tokio::test)]
    async fn end_to_end_ble_presentment_holder_reader() {
        let key_alias = KeyAlias(Uuid::new_v4().to_string());
        let key_manager = Arc::new(RustTestKeyManager::default());
        key_manager
            .generate_p256_signing_key(key_alias.clone())
            .await
            .unwrap();
        let mdl = Arc::new(
            crate::mdl::util::generate_test_mdl(key_manager.clone(), key_alias.clone()).unwrap(),
        )
        .try_into()
        .unwrap();

        let smi = Arc::new(local_store::LocalStore::new());

        let vdc_collection = VdcCollection::new(smi.clone());
        vdc_collection.add(&mdl).await.unwrap();

        let presentation_session = initialize_mdl_presentation(mdl.id, Uuid::new_v4(), smi.clone())
            .await
            .unwrap();
        let namespaces = [(
            "org.iso.18013.5.1".to_string(),
            [
                ("given_name".to_string(), true),
                ("family_name".to_string(), false),
            ]
            .into_iter()
            .collect(),
        )]
        .into_iter()
        .collect();
        let reader_session_data = crate::reader::establish_session(
            presentation_session.qr_code_uri.clone(),
            namespaces,
            Some(vec![include_str!(
                "../../tests/res/mdl/utrecht-certificate.pem"
            )
            .to_string()]),
        )
        .unwrap();
        let _request_data = presentation_session
            .handle_request(reader_session_data.request)
            .unwrap();
        let permitted_items = [(
            "org.iso.18013.5.1.mDL".to_string(),
            [(
                "org.iso.18013.5.1".to_string(),
                vec!["given_name".to_string()],
            )]
            .into_iter()
            .collect(),
        )]
        .into_iter()
        .collect();
        let signing_payload = presentation_session
            .generate_response(permitted_items)
            .unwrap();
        let key = key_manager.get_signing_key(key_alias).unwrap();
        let signature = key.sign(signing_payload).unwrap();
        let response = presentation_session.submit_response(signature).unwrap();
        let res = crate::reader::handle_response(reader_session_data.state, response).unwrap();
        assert_eq!(res.errors, None);

        vdc_collection.delete(mdl.id).await.unwrap();
    }
}
