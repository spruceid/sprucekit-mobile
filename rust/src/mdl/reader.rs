use std::{
    collections::{BTreeMap, HashMap},
    sync::{Arc, Mutex},
};

use anyhow::{anyhow, Context, Result};
use isomdl::{
    definitions::{
        device_request,
        helpers::{non_empty_map, NonEmptyMap},
        x509::{
            self,
            trust_anchor::{PemTrustAnchor, TrustAnchorRegistry},
        },
    },
    presentation::{authentication::AuthenticationStatus as IsoMdlAuthenticationStatus, reader},
};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

#[derive(uniffi::Record)]
pub struct ReaderApduHandoverDriverInit {
    pub driver: Arc<ReaderApduHandoverDriver>,
    pub initial_apdu: Vec<u8>,
}

/// Create a new APDU handover driver for a reader.
///
/// Returns: the driver along with the initial APDU to send to the holder.
#[uniffi::export]
pub fn new_reader_apdu_handover_driver() -> ReaderApduHandoverDriverInit {
    let (driver, apdu) =
        isomdl::definitions::device_engagement::nfc::ReaderApduHandoverDriver::new();
    ReaderApduHandoverDriverInit {
        driver: Arc::new(ReaderApduHandoverDriver(Mutex::new(driver))),
        initial_apdu: apdu,
    }
}

#[derive(uniffi::Enum)]
pub enum ReaderApduProgress {
    InProgress(Vec<u8>),
    Done(Arc<ReaderHandover>),
}

#[derive(thiserror::Error, uniffi::Error, Debug, Clone)]
pub enum ReaderApduHandoverError {
    #[error("Generic error: {0}")]
    General(String),
}

impl From<anyhow::Error> for ReaderApduHandoverError {
    fn from(value: anyhow::Error) -> Self {
        Self::General(format!("{value:#?}"))
    }
}

#[derive(uniffi::Object, Debug)]
pub struct ReaderApduHandoverDriver(
    Mutex<isomdl::definitions::device_engagement::nfc::ReaderApduHandoverDriver>,
);

#[uniffi::export]
impl ReaderApduHandoverDriver {
    pub fn process_rapdu(
        &self,
        command: &[u8],
    ) -> Result<ReaderApduProgress, ReaderApduHandoverError> {
        if let Ok(mut handover) = self.0.lock() {
            Ok(
                match handover
                    .process_rapdu(command)
                    .context("response APDU processing failed")?
                {
                    isomdl::definitions::device_engagement::nfc::ReaderApduProgress::InProgress(
                        items,
                    ) => ReaderApduProgress::InProgress(items),
                    isomdl::definitions::device_engagement::nfc::ReaderApduProgress::Done(
                        carrier_info,
                    ) => ReaderApduProgress::Done(Arc::new(ReaderHandover(reader::Handover::NFC(
                        carrier_info,
                    )))),
                },
            )
        } else {
            Err(anyhow!(
                "failed to get reference to ReaderApduHandoverDriver in process_rapdu!"
            ))?
        }
    }

    /// Recommended delay before transmitting the command returned by the last
    /// [Self::process_rapdu] call, per TNEP's minimum waiting time. 0 when no
    /// wait is needed.
    pub fn recommended_delay_ms(&self) -> u32 {
        self.0.lock().map(|d| d.recommended_delay_ms()).unwrap_or(0)
    }
}

#[derive(thiserror::Error, uniffi::Error, Debug)]
pub enum MDLReaderSessionError {
    #[error("{value}")]
    Generic { value: String },
}

#[derive(uniffi::Object)]
pub struct MDLSessionManager(reader::SessionManager);

/// Connection details for connecting to an mdoc that is using BLE Central Client mode.
#[derive(uniffi::Record, Clone, Copy)]
pub struct CentralClientDetails {
    /// The UUID of the service that the mdoc is listening for.
    pub service_uuid: Uuid,
}

/// Connection details for connecting to an mdoc that is using BLE Peripheral Server mode.
#[derive(uniffi::Record)]
pub struct PeripheralServerDetails {
    /// The UUID of the service that the mdoc is advertising.
    pub service_uuid: Uuid,
    /// The Bluetooth device address of the peripheral server. If available, this can be used
    /// to more quickly identify the correct device to connect to.
    pub ble_device_address: Option<Vec<u8>>,
}

#[derive(uniffi::Enum)]
pub enum BleMode {
    CentralClient(CentralClientDetails),
    PeripheralServer(PeripheralServerDetails),
}

#[uniffi::export]
impl MDLSessionManager {
    pub fn ble_central_client_details(&self) -> Vec<CentralClientDetails> {
        self.0
            .ble_central_client_options()
            .map(|cc| CentralClientDetails {
                service_uuid: cc.uuid,
            })
            .collect()
    }

    pub fn ble_peripheral_server_details(&self) -> Vec<PeripheralServerDetails> {
        self.0
            .ble_peripheral_server_options()
            .map(|ps| PeripheralServerDetails {
                service_uuid: ps.uuid,
                ble_device_address: ps.ble_device_address.clone().map(Vec::from),
            })
            .collect()
    }

    pub fn preferred_ble_mode(&self) -> Option<BleMode> {
        match self.0.preferred_ble_mode() {
            None => None,
            Some(isomdl::definitions::device_engagement::BleMode::CentralClient(d)) => {
                Some(BleMode::CentralClient(CentralClientDetails {
                    service_uuid: d.uuid,
                }))
            }
            Some(isomdl::definitions::device_engagement::BleMode::PeripheralServer(d)) => {
                Some(BleMode::PeripheralServer(PeripheralServerDetails {
                    service_uuid: d.uuid,
                    ble_device_address: d.ble_device_address.map(Vec::from),
                }))
            }
        }
    }
}

impl std::fmt::Debug for MDLSessionManager {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "Debug for SessionManager not implemented")
    }
}

#[derive(uniffi::Record)]
pub struct MDLReaderSessionData {
    pub state: Arc<MDLSessionManager>,
    pub request: Vec<u8>,
    pub ble_ident: Vec<u8>,
}

#[derive(uniffi::Object)]
pub struct ReaderHandover(reader::Handover);

#[uniffi::export]
impl ReaderHandover {
    #[uniffi::constructor]
    pub fn new_qr(qr: String) -> Self {
        Self(reader::Handover::QR(qr))
    }
}

#[uniffi::export]
pub fn establish_session(
    handover: Arc<ReaderHandover>,
    requested_items: HashMap<String, HashMap<String, bool>>,
    trust_anchor_registry: Option<Vec<String>>,
) -> Result<MDLReaderSessionData, MDLReaderSessionError> {
    let namespaces: Result<BTreeMap<_, NonEmptyMap<_, _>>, non_empty_map::Error> = requested_items
        .into_iter()
        .map(|(doc_type, namespaces)| {
            let namespaces: BTreeMap<_, _> = namespaces.into_iter().collect();
            match namespaces.try_into() {
                Ok(n) => Ok((doc_type, n)),
                Err(e) => Err(e),
            }
        })
        .collect();
    let namespaces = namespaces.map_err(|e| MDLReaderSessionError::Generic {
        value: format!("Unable to build data elements: {e:?}"),
    })?;
    let namespaces: device_request::Namespaces =
        namespaces
            .try_into()
            .map_err(|e| MDLReaderSessionError::Generic {
                value: format!("Unable to build namespaces: {e:?}"),
            })?;

    let registry = TrustAnchorRegistry::from_pem_certificates(
        trust_anchor_registry
            .into_iter()
            .flat_map(|v| v.into_iter())
            .map(|certificate_pem| PemTrustAnchor {
                certificate_pem,
                purpose: x509::trust_anchor::TrustPurpose::Iaca,
            })
            .collect(),
    )
    .map_err(|e| MDLReaderSessionError::Generic {
        value: format!("unable to construct TrustAnchorRegistry: {e:?}"),
    })?;

    let (manager, request, ble_ident) =
        reader::SessionManager::establish_session(handover.0.clone(), namespaces, registry)
            .map_err(|e| MDLReaderSessionError::Generic {
                value: format!("unable to establish session: {e:?}"),
            })?;

    Ok(MDLReaderSessionData {
        state: Arc::new(MDLSessionManager(manager)),
        request,
        ble_ident: ble_ident.to_vec(),
    })
}

#[derive(thiserror::Error, uniffi::Error, Debug, PartialEq)]
pub enum MDLReaderResponseError {
    #[error("Invalid decryption")]
    InvalidDecryption,
    #[error("Invalid parsing")]
    InvalidParsing,
    #[error("Invalid issuer authentication")]
    InvalidIssuerAuthentication,
    #[error("Invalid device authentication")]
    InvalidDeviceAuthentication,
    #[error("{value}")]
    Generic { value: String },
}

// Currently, a lot of information is lost in `isomdl`. For example, bytes are
// converted to strings, but we could also imagine detecting images and having
// a specific enum variant for them.
#[derive(uniffi::Enum, Debug)]
pub enum MDocItem {
    Text(String),
    Bool(bool),
    Integer(i64),
    ItemMap(HashMap<String, MDocItem>),
    Array(Vec<MDocItem>),
}

impl From<serde_json::Value> for MDocItem {
    fn from(value: serde_json::Value) -> Self {
        match value {
            serde_json::Value::Null => unreachable!("No null allowed in namespaces"),
            serde_json::Value::Bool(b) => Self::Bool(b),
            serde_json::Value::Number(n) => {
                if let Some(i) = n.as_i64() {
                    Self::Integer(i)
                } else {
                    unreachable!("Only integers allowed in namespaces")
                }
            }
            serde_json::Value::String(s) => Self::Text(s),
            serde_json::Value::Array(a) => {
                Self::Array(a.iter().map(|o| Into::<Self>::into(o.clone())).collect())
            }
            serde_json::Value::Object(m) => Self::ItemMap(
                m.iter()
                    .map(|(k, v)| (k.clone(), Into::<Self>::into(v.clone())))
                    .collect(),
            ),
        }
    }
}

impl From<&MDocItem> for serde_json::Value {
    fn from(val: &MDocItem) -> Self {
        match val {
            MDocItem::Text(s) => Self::String(s.to_owned()),
            MDocItem::Bool(b) => Self::Bool(*b),
            MDocItem::Integer(i) => Self::Number(i.to_owned().into()),
            MDocItem::ItemMap(m) => {
                Self::Object(m.iter().map(|(k, v)| (k.clone(), v.into())).collect())
            }
            MDocItem::Array(a) => Self::Array(a.iter().map(|o| o.into()).collect()),
        }
    }
}

#[derive(Debug, Clone, PartialEq, uniffi::Enum)]
pub enum AuthenticationStatus {
    Valid,
    Invalid,
    Unchecked,
}

impl From<IsoMdlAuthenticationStatus> for AuthenticationStatus {
    fn from(internal: IsoMdlAuthenticationStatus) -> Self {
        match internal {
            IsoMdlAuthenticationStatus::Valid => AuthenticationStatus::Valid,
            IsoMdlAuthenticationStatus::Invalid => AuthenticationStatus::Invalid,
            IsoMdlAuthenticationStatus::Unchecked => AuthenticationStatus::Unchecked,
        }
    }
}
#[derive(uniffi::Record, Debug)]
pub struct MDLReaderResponseData {
    state: Arc<MDLSessionManager>,
    /// Contains the namespaces for the mDL directly, without top-level doc types
    verified_response: HashMap<String, HashMap<String, MDocItem>>,
    /// Document types (doctypes) from the presented credentials.
    pub doc_types: Vec<String>,
    /// Outcome of issuer authentication.
    pub issuer_authentication: AuthenticationStatus,
    /// Outcome of device authentication.
    pub device_authentication: AuthenticationStatus,
    /// Errors that occurred during response processing.
    pub errors: Option<String>,
}

#[derive(thiserror::Error, uniffi::Error, Debug)]
pub enum MDLReaderResponseSerializeError {
    #[error("{value}")]
    Generic { value: String },
}

fn verified_response_to_json(
    verified_response: &HashMap<String, HashMap<String, MDocItem>>,
) -> Result<serde_json::Value, MDLReaderResponseSerializeError> {
    serde_json::to_value(
        verified_response
            .iter()
            .map(|(k, v)| {
                (
                    k.clone(),
                    v.iter().map(|(k, v)| (k.clone(), v.into())).collect(),
                )
            })
            .collect::<HashMap<String, HashMap<String, serde_json::Value>>>(),
    )
    .map_err(|e| MDLReaderResponseSerializeError::Generic {
        value: e.to_string(),
    })
}

impl MDLReaderResponseData {
    pub fn verified_response_as_json(
        &self,
    ) -> Result<serde_json::Value, MDLReaderResponseSerializeError> {
        verified_response_to_json(&self.verified_response)
    }
}

#[uniffi::export]
pub fn verified_response_as_json_string(
    response: MDLReaderResponseData,
) -> Result<String, MDLReaderResponseSerializeError> {
    serde_json::to_string(&response.verified_response_as_json()?).map_err(|e| {
        MDLReaderResponseSerializeError::Generic {
            value: e.to_string(),
        }
    })
}

type VerifiedNamespaces = HashMap<String, HashMap<String, MDocItem>>;
type VerifiedNamespacesAndErrors = (VerifiedNamespaces, Option<String>);

fn verified_namespaces_and_errors(
    validated_response: &isomdl::presentation::authentication::ResponseAuthenticationOutcome,
) -> Result<VerifiedNamespacesAndErrors, MDLReaderResponseError> {
    let errors = if !validated_response.errors.is_empty() {
        Some(
            serde_json::to_string(&validated_response.errors).map_err(|e| {
                MDLReaderResponseError::Generic {
                    value: format!("Could not serialze errors: {e:?}"),
                }
            })?,
        )
    } else {
        None
    };
    let verified_response: Result<_, _> = validated_response
        .response
        .iter()
        .map(|(namespace, items)| {
            if let Some(items) = items.as_object() {
                let items = items
                    .iter()
                    .map(|(item, value)| (item.clone(), value.clone().into()))
                    .collect();
                Ok((namespace.to_string(), items))
            } else {
                Err(MDLReaderResponseError::Generic {
                    value: format!("Items not object, instead: {items:#?}"),
                })
            }
        })
        .collect();
    let verified_response = verified_response.map_err(|e| MDLReaderResponseError::Generic {
        value: format!("Unable to parse response: {e:?}"),
    })?;
    Ok((verified_response, errors))
}

#[uniffi::export]
pub fn handle_response(
    state: Arc<MDLSessionManager>,
    response: Vec<u8>,
) -> Result<MDLReaderResponseData, MDLReaderResponseError> {
    let mut state = state.0.clone();
    // blocking to avoid turning all functions async as revocation checks are currently unused due
    // to `()`
    let validated_response = super::block_on(state.handle_response(&response, &()));
    let (verified_response, errors) = verified_namespaces_and_errors(&validated_response)?;
    Ok(MDLReaderResponseData {
        state: Arc::new(MDLSessionManager(state)),
        verified_response,
        doc_types: validated_response.doc_types,
        issuer_authentication: AuthenticationStatus::from(validated_response.issuer_authentication),
        device_authentication: AuthenticationStatus::from(validated_response.device_authentication),
        errors,
    })
}

#[derive(Clone, Debug, Serialize, Deserialize)]
struct ProvidedSessionTranscript(ciborium::Value);

impl isomdl::definitions::session::SessionTranscript for ProvidedSessionTranscript {}

/// The result of verifying a `DeviceResponse` against an externally-supplied session transcript.
///
/// Mirrors [`MDLReaderResponseData`] but carries no reader [`MDLSessionManager`], since the
/// transcript is provided directly rather than established during BLE/NFC engagement.
#[derive(uniffi::Record, Debug)]
pub struct MDLDeviceResponseVerification {
    /// Contains the namespaces for the mDL directly, without top-level doc types.
    verified_response: HashMap<String, HashMap<String, MDocItem>>,
    /// Document types (doctypes) from the presented credentials.
    pub doc_types: Vec<String>,
    /// Outcome of issuer authentication.
    pub issuer_authentication: AuthenticationStatus,
    /// Outcome of device authentication.
    pub device_authentication: AuthenticationStatus,
    /// Errors that occurred during response processing.
    pub errors: Option<String>,
}

impl MDLDeviceResponseVerification {
    pub fn verified_response_as_json(
        &self,
    ) -> Result<serde_json::Value, MDLReaderResponseSerializeError> {
        verified_response_to_json(&self.verified_response)
    }
}

#[uniffi::export]
pub fn device_response_verification_as_json_string(
    response: MDLDeviceResponseVerification,
) -> Result<String, MDLReaderResponseSerializeError> {
    serde_json::to_string(&response.verified_response_as_json()?).map_err(|e| {
        MDLReaderResponseSerializeError::Generic {
            value: e.to_string(),
        }
    })
}

#[uniffi::export]
pub fn verify_device_response(
    device_response: Vec<u8>,
    session_transcript: Vec<u8>,
    ephemeral_reader_key: Vec<u8>,
    trust_anchor_registry: Option<Vec<String>>,
) -> Result<MDLDeviceResponseVerification, MDLReaderResponseError> {
    let device_response: isomdl::definitions::DeviceResponse =
        isomdl::cbor::from_slice(&device_response).map_err(|e| {
            MDLReaderResponseError::Generic {
                value: format!("unable to decode device response: {e:?}"),
            }
        })?;

    let session_transcript: ciborium::Value = isomdl::cbor::from_slice(&session_transcript)
        .map_err(|e| MDLReaderResponseError::Generic {
            value: format!("unable to decode session transcript: {e:?}"),
        })?;
    let session_transcript = ProvidedSessionTranscript(session_transcript);

    let registry = TrustAnchorRegistry::from_pem_certificates(
        trust_anchor_registry
            .into_iter()
            .flatten()
            .map(|certificate_pem| PemTrustAnchor {
                certificate_pem,
                purpose: x509::trust_anchor::TrustPurpose::Iaca,
            })
            .collect(),
    )
    .map_err(|e| MDLReaderResponseError::Generic {
        value: format!("unable to construct TrustAnchorRegistry: {e:?}"),
    })?;

    let (document, x5chain, namespaces) =
        reader::parse(&device_response).map_err(|e| MDLReaderResponseError::Generic {
            value: format!("unable to parse device response: {e:?}"),
        })?;

    let doc_types: Vec<String> = device_response
        .documents
        .as_ref()
        .map(|docs| docs.iter().map(|d| d.doc_type.clone()).collect())
        .unwrap_or_default();

    let validated_response =
        super::block_on(isomdl::presentation::reader_utils::validate_response(
            session_transcript,
            registry,
            x5chain,
            document.clone(),
            namespaces,
            doc_types,
            &(),
            if ephemeral_reader_key.is_empty() {
                [0u8; 32]
            } else {
                ephemeral_reader_key
                    .try_into()
                    .map_err(|e| MDLReaderResponseError::Generic {
                        value: format!("unable to parse ephemeral_reader_key: {e:?}"),
                    })?
            },
        ));

    let (verified_response, errors) = verified_namespaces_and_errors(&validated_response)?;
    Ok(MDLDeviceResponseVerification {
        verified_response,
        doc_types: validated_response.doc_types,
        issuer_authentication: AuthenticationStatus::from(validated_response.issuer_authentication),
        device_authentication: AuthenticationStatus::from(validated_response.device_authentication),
        errors,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Device authentication verifies a signature over a `DeviceAuthentication` structure that
    /// embeds the `SessionTranscript`. For verification to succeed against an externally-supplied
    /// transcript, [`ProvidedSessionTranscript`] must re-encode it byte-for-byte — so a transparent
    /// decode/encode round-trip of deterministic CBOR must be the identity.
    #[test]
    fn provided_session_transcript_roundtrips_cbor_verbatim() {
        // A representative, deterministically-encoded session-transcript-shaped value:
        // a 3-element array of [ #6.24(bstr), {1: 2}, "QR" ].
        let bytes: Vec<u8> = vec![
            0x83, // array(3)
            0xd8, 0x18, 0x43, 0x01, 0x02, 0x03, // 24(h'010203')
            0xa1, 0x01, 0x02, // map: {1: 2}
            0x62, 0x51, 0x52, // text: "QR"
        ];

        let transcript: ProvidedSessionTranscript =
            isomdl::cbor::from_slice(&bytes).expect("decode session transcript");
        let reencoded = isomdl::cbor::to_vec(&transcript).expect("encode session transcript");

        assert_eq!(
            bytes, reencoded,
            "session transcript must round-trip byte-for-byte"
        );
    }

    /// An invalid CBOR device response surfaces as an error rather than panicking.
    #[test]
    fn verify_device_response_rejects_malformed_cbor() {
        let result = verify_device_response(vec![0xff, 0xff, 0xff], vec![0x80], vec![], None);
        assert!(matches!(
            result,
            Err(MDLReaderResponseError::Generic { .. })
        ));
    }
}
