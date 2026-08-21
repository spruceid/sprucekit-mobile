use std::{
    collections::{BTreeMap, HashMap},
    sync::{Arc, Mutex},
};

use anyhow::{anyhow, Context, Result};
use isomdl::{
    definitions::{
        device_request::{self, ItemsRequest},
        helpers::{non_empty_map, NonEmptyMap, NonEmptyVec},
        x509::{
            self,
            trust_anchor::{PemTrustAnchor, TrustAnchorRegistry},
            validation::ValidationOptions,
        },
    },
    presentation::{
        authentication::{DocumentError, ResponseValidationOutcome},
        reader,
        reader_utils::{self, ReaderValidationConfig},
    },
};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

use super::profile::{self, MdocCertificateProfiles};

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

/// Establish a reader session and build the request for the given document type.
///
/// Arguments:
/// handover: the engagement handover, from a scanned QR code or an NFC exchange.
/// requested_items: the data elements to request, keyed by namespace then element identifier,
///                  with the value indicating intent to retain.
/// trust_anchor_registry: PEM-encoded IACA certificates to validate the issuer against.
/// doc_type: the document type to request. Required: there is no sensible default, and a request
///           carrying the wrong doctype is answered with nothing rather than an error, so the
///           caller must say what it is asking for.
#[uniffi::export]
pub fn establish_session(
    handover: Arc<ReaderHandover>,
    requested_items: HashMap<String, HashMap<String, bool>>,
    trust_anchor_registry: Option<Vec<String>>,
    doc_type: String,
) -> Result<MDLReaderSessionData, MDLReaderSessionError> {
    let namespaces: Result<BTreeMap<_, NonEmptyMap<_, _>>, non_empty_map::Error> = requested_items
        .into_iter()
        .map(|(namespace, elements)| {
            let elements: BTreeMap<_, _> = elements.into_iter().collect();
            match elements.try_into() {
                Ok(n) => Ok((namespace, n)),
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

    let registry =
        build_registry(trust_anchor_registry).map_err(|e| MDLReaderSessionError::Generic {
            value: format!("unable to construct TrustAnchorRegistry: {e:?}"),
        })?;

    let items_requests = NonEmptyVec::new(ItemsRequest::new(doc_type, namespaces));

    let (manager, request, ble_ident) =
        reader::SessionManager::establish_session(handover.0.clone(), items_requests, registry)
            .map_err(|e| MDLReaderSessionError::Generic {
                value: format!("unable to establish session: {e:?}"),
            })?;

    Ok(MDLReaderSessionData {
        state: Arc::new(MDLSessionManager(manager)),
        request,
        ble_ident: ble_ident.to_vec(),
    })
}

fn build_registry(
    trust_anchor_registry: Option<Vec<String>>,
) -> Result<TrustAnchorRegistry, anyhow::Error> {
    let registry = TrustAnchorRegistry::from_pem_certificates(
        trust_anchor_registry
            .into_iter()
            .flat_map(|v| v.into_iter())
            .map(|certificate_pem| PemTrustAnchor {
                certificate_pem,
                purpose: x509::trust_anchor::TrustPurpose::Iaca,
            })
            .collect(),
    )?;

    Ok(registry)
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

/// Collapse a per-document validation outcome into the two statuses this FFI surface reports.
///
/// Fails closed: `Valid` is returned only for a document that passed *every* check, which is what
/// landing in `documents` means. It is deliberately not derived from `errors` being empty --
/// `errors` collects only the reasons nothing could be evaluated, so a response whose documents
/// each failed their own checks has an empty `errors` and a populated `failed`, and reading that as
/// success would render an invalid credential as verified.
///
/// Unrequested doctypes never reach `documents`: `isomdl` rejects them without validating. Since
/// [`establish_session`] asks for exactly one doctype, a non-empty `documents` means that doctype
/// arrived and validated.
fn authentication_statuses(
    outcome: &ResponseValidationOutcome,
) -> (AuthenticationStatus, AuthenticationStatus) {
    if !outcome.documents.is_empty() {
        return (AuthenticationStatus::Valid, AuthenticationStatus::Valid);
    }

    let Some(failed) = outcome.failed.first() else {
        // Nothing was evaluated at all -- no documents, or every one rejected as unrequested.
        return (
            AuthenticationStatus::Unchecked,
            AuthenticationStatus::Unchecked,
        );
    };

    // `isomdl` populates `mso` only once issuer authentication has succeeded, so its presence is
    // the authoritative signal that the chain and issuer signature were good and the document
    // failed later (an expired MSO, a doctype disagreeing with the label, device authentication).
    let issuer = if failed.mso.is_some() {
        AuthenticationStatus::Valid
    } else {
        AuthenticationStatus::Invalid
    };

    // Only an explicit device authentication failure is reported as `Invalid`. Anything else leaves
    // it `Unchecked`: when issuer authentication fails, device authentication is unperformable
    // rather than failed, and for other failures we cannot show it succeeded.
    let device = if failed
        .errors
        .iter()
        .any(|error| matches!(error, DocumentError::DeviceAuthentication { .. }))
    {
        AuthenticationStatus::Invalid
    } else {
        AuthenticationStatus::Unchecked
    };

    (issuer, device)
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

/// The verified namespaces, the doctypes to display, and any errors, from a validation outcome.
///
/// Only documents that passed every check contribute elements: showing a holder's claimed values as
/// if they were verified would be worse than showing nothing. Doctypes are taken from the
/// signature-verified MSO for documents that passed, and from the holder's unauthenticated label for
/// documents that failed -- the latter only so the UI can name what it could not verify, alongside
/// the authentication statuses that say it did not.
/// Everything that went wrong with a response, flattened into one diagnostic payload.
///
/// The per-document reasons matter as much as the response-level ones: a document that fails on
/// its own contributes only a bare
/// [`DocumentsFailed`](isomdl::presentation::authentication::ResponseError::DocumentsFailed) to
/// the response-level list, so reporting only that leaves "issuer authentication invalid" with no
/// explanation. Misconfigured certificate profiles land here, as `no_profile_configured`.
#[derive(Serialize, Default)]
struct ResponseErrors {
    /// Reasons the response as a whole is not wholly good.
    response: Vec<String>,
    /// Per-document failures, keyed by the doctype the holder claimed. Unauthenticated, since a
    /// document that failed has no verified doctype to key on.
    documents: BTreeMap<String, Vec<String>>,
    /// Doctypes that arrived without being asked for. Dropped without validation, so they are
    /// reported rather than silently discarded.
    unrequested: Vec<String>,
}

impl ResponseErrors {
    /// `None` when there is nothing at all to report, so the field stays absent rather than
    /// carrying an empty structure that reads as a problem.
    fn into_json(self) -> Result<Option<String>, serde_json::Error> {
        if self.response.is_empty() && self.documents.is_empty() && self.unrequested.is_empty() {
            return Ok(None);
        }
        serde_json::to_string(&self).map(Some)
    }
}

impl From<&ResponseValidationOutcome> for ResponseErrors {
    fn from(outcome: &ResponseValidationOutcome) -> Self {
        let mut documents: BTreeMap<String, Vec<String>> = BTreeMap::new();
        for failed in &outcome.failed {
            documents
                .entry(failed.claimed_doc_type.clone())
                .or_default()
                .extend(failed.errors.iter().map(ToString::to_string));
        }
        Self {
            response: outcome.errors.iter().map(ToString::to_string).collect(),
            documents,
            unrequested: outcome
                .rejected
                .iter()
                .map(|rejected| rejected.claimed_doc_type.clone())
                .collect(),
        }
    }
}

fn verified_namespaces_and_errors(
    outcome: &ResponseValidationOutcome,
) -> Result<(VerifiedNamespaces, Vec<String>, Option<String>), MDLReaderResponseError> {
    let errors =
        ResponseErrors::from(outcome)
            .into_json()
            .map_err(|e| MDLReaderResponseError::Generic {
                value: format!("Could not serialize errors: {e:?}"),
            })?;

    let mut verified_response = VerifiedNamespaces::new();
    for document in &outcome.documents {
        for (namespace, elements) in &document.namespaces {
            let items: HashMap<String, MDocItem> = elements
                .iter()
                .map(|(identifier, value)| (identifier.clone(), value.clone().into()))
                .collect();
            verified_response
                .entry(namespace.clone())
                .or_default()
                .extend(items);
        }
    }

    let doc_types = outcome
        .documents
        .iter()
        .map(|document| document.doc_type.clone())
        .chain(
            outcome
                .failed
                .iter()
                .map(|failed| failed.claimed_doc_type.clone()),
        )
        .collect();

    Ok((verified_response, doc_types, errors))
}

/// Arguments:
/// state: the session established by [`establish_session`]
/// response: the cbor encoded `DeviceResponse` received from the holder
/// certificate_profiles: certificate rules per doctype. `None` validates every doctype under the
///                       ISO/IEC 18013-5 mDL profile, which is what every credential this SDK
///                       generates is signed under, including the 23220-4 Photo ID. Supplying a
///                       map also means a doctype absent from it is refused rather than validated
///                       under a guess.
#[uniffi::export(default(certificate_profiles = None))]
pub fn handle_response(
    state: Arc<MDLSessionManager>,
    response: Vec<u8>,
    certificate_profiles: Option<HashMap<String, MdocCertificateProfiles>>,
) -> Result<MDLReaderResponseData, MDLReaderResponseError> {
    let mut state = state.0.clone();
    let profiles =
        profile::resolve(certificate_profiles).map_err(|e| MDLReaderResponseError::Generic {
            value: format!("unable to build certificate profiles: {e}"),
        })?;
    // blocking to avoid turning all functions async as revocation checks are currently unused due
    // to `()`
    let validated_response = super::block_on(state.handle_response(&response, &profiles, &()));
    let (verified_response, doc_types, errors) =
        verified_namespaces_and_errors(&validated_response)?;
    let (issuer_authentication, device_authentication) =
        authentication_statuses(&validated_response);
    Ok(MDLReaderResponseData {
        state: Arc::new(MDLSessionManager(state)),
        verified_response,
        doc_types,
        issuer_authentication,
        device_authentication,
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

/// Verifies an mDL device response by using the session transcript and ephemeral
/// reader key, and an optional trust anchor registry.
///
/// Arguments:
/// device_response: cbor encoded `isomdl::definitions::DeviceResponse`
/// session_transcript: cbor encoded `isomdl::definitions::session::SessionTranscript`
/// ephemeral_reader_key: 32-byte private key
/// trust_anchor_registry: optional list of PEM encoded certificates
/// certificate_profiles: certificate rules per doctype, as for [`handle_response`]
///
/// Returns:
/// An object with the verified response, document types, issuer authentication result,
/// device authentication result, and an optional error string.
#[uniffi::export(default(certificate_profiles = None))]
pub fn verify_device_response(
    device_response: Vec<u8>,
    session_transcript: Vec<u8>,
    ephemeral_reader_key: Vec<u8>,
    trust_anchor_registry: Option<Vec<String>>,
    certificate_profiles: Option<HashMap<String, MdocCertificateProfiles>>,
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

    let registry =
        build_registry(trust_anchor_registry).map_err(|e| MDLReaderResponseError::Generic {
            value: format!("unable to construct TrustAnchorRegistry: {e:?}"),
        })?;

    // `requested_doc_types` is `None` because the caller supplies a transcript rather than having
    // built a request through a `SessionManager`, so there is no recorded request to filter
    // against. `isomdl` treats that as "validate everything that arrived" and records a
    // `RequestedDocTypesUnknown` warning, which matches what this entry point did before.
    let profiles =
        profile::resolve(certificate_profiles).map_err(|e| MDLReaderResponseError::Generic {
            value: format!("unable to build certificate profiles: {e}"),
        })?;
    let options = ValidationOptions::default();
    let config = ReaderValidationConfig {
        trust_anchors: &registry,
        requested_doc_types: None,
        options: &options,
        profiles: &profiles,
    };

    let ephemeral_reader_key: [u8; 32] = if ephemeral_reader_key.is_empty() {
        [0u8; 32]
    } else {
        ephemeral_reader_key
            .try_into()
            .map_err(|e: Vec<u8>| MDLReaderResponseError::Generic {
                value: format!(
                    "unable to parse ephemeral_reader_key: expected 32 bytes, got {}",
                    e.len()
                ),
            })?
    };

    let validated_response = super::block_on(reader_utils::validate_response(
        &device_response,
        &session_transcript,
        &config,
        &(),
        &ephemeral_reader_key,
    ));

    let (verified_response, doc_types, errors) =
        verified_namespaces_and_errors(&validated_response)?;
    let (issuer_authentication, device_authentication) =
        authentication_statuses(&validated_response);
    Ok(MDLDeviceResponseVerification {
        verified_response,
        doc_types,
        issuer_authentication,
        device_authentication,
        errors,
    })
}

#[cfg(test)]
mod tests {
    use isomdl::presentation::authentication::{FailedDocument, RejectedDocument, ResponseError};

    use super::*;

    fn outcome() -> ResponseValidationOutcome {
        ResponseValidationOutcome {
            documents: Vec::new(),
            failed: Vec::new(),
            rejected: Vec::new(),
            errors: Vec::new(),
            status: None,
            document_errors: Vec::new(),
            warnings: Vec::new(),
        }
    }

    /// A failed document with no `mso`, i.e. one that did not get past issuer authentication.
    fn failed(error: DocumentError) -> FailedDocument {
        FailedDocument {
            claimed_doc_type: "org.iso.23220.photoid.1".to_string(),
            errors: NonEmptyVec::new(error),
            mso: None,
            namespaces: BTreeMap::new(),
            warnings: Vec::new(),
        }
    }

    /// A response where every document failed its own checks carries an *empty* top-level `errors`
    /// -- that collection only holds reasons nothing could be evaluated. Deriving validity from it
    /// would report an invalid credential as verified, so this pins the fail-closed behaviour.
    #[test]
    fn a_failed_document_is_never_reported_as_valid() {
        let mut outcome = outcome();
        outcome.failed = vec![failed(DocumentError::IssuerAuthentication {
            detail: "bad signature".to_string(),
        })];
        assert!(outcome.errors.is_empty(), "precondition for this test");

        let (issuer, device) = authentication_statuses(&outcome);
        assert_eq!(issuer, AuthenticationStatus::Invalid);
        // Device authentication is unperformable when issuer authentication failed: report it as
        // unchecked rather than implying the device proved possession.
        assert_eq!(device, AuthenticationStatus::Unchecked);
    }

    /// An explicit device authentication failure is the one case reported as `Invalid`.
    #[test]
    fn a_device_authentication_failure_is_reported_as_invalid() {
        let mut outcome = outcome();
        outcome.failed = vec![failed(DocumentError::DeviceAuthentication {
            detail: "bad device signature".to_string(),
        })];

        let (_, device) = authentication_statuses(&outcome);
        assert_eq!(device, AuthenticationStatus::Invalid);
    }

    /// Nothing evaluated -- including the case where every document was rejected as an unrequested
    /// doctype -- must read as "not checked", never as valid or invalid.
    #[test]
    fn nothing_evaluated_is_unchecked() {
        let (issuer, device) = authentication_statuses(&outcome());
        assert_eq!(issuer, AuthenticationStatus::Unchecked);
        assert_eq!(device, AuthenticationStatus::Unchecked);

        let mut rejected_only = outcome();
        rejected_only.rejected = vec![RejectedDocument {
            claimed_doc_type: "org.iso.18013.5.1.mDL".to_string(),
        }];
        let (issuer, device) = authentication_statuses(&rejected_only);
        assert_eq!(issuer, AuthenticationStatus::Unchecked);
        assert_eq!(device, AuthenticationStatus::Unchecked);
    }

    /// A rejected document was never validated, so its claimed doctype must not leak into the
    /// verified element map or the doctype list -- but it must still be reported, or a response
    /// that arrived and was dropped looks identical to no response at all.
    #[test]
    fn rejected_documents_contribute_no_elements_but_are_still_reported() {
        let mut rejected_only = outcome();
        rejected_only.rejected = vec![RejectedDocument {
            claimed_doc_type: "org.iso.18013.5.1.mDL".to_string(),
        }];
        let (verified, doc_types, errors) =
            verified_namespaces_and_errors(&rejected_only).expect("derivation failed");
        assert!(verified.is_empty());
        assert!(doc_types.is_empty());
        let errors = errors.expect("an unrequested doctype must not be dropped silently");
        assert!(errors.contains("org.iso.18013.5.1.mDL"), "{errors}");
    }

    /// Nothing wrong at all reports nothing, so an empty payload never reads as a problem.
    #[test]
    fn a_clean_response_reports_no_errors() {
        let (_, _, errors) = verified_namespaces_and_errors(&outcome()).expect("derivation failed");
        assert!(errors.is_none());
    }

    /// Configuring certificate profiles that do not cover the doctype the holder sent is a new
    /// failure mode, and the only diagnosis a caller gets is this payload. The response-level
    /// error is a bare `DocumentsFailed`, so without the per-document reason the UI would show
    /// "issuer authentication invalid" and no explanation at all.
    #[test]
    fn a_missing_certificate_profile_is_reported_with_its_reason() {
        let mut outcome = outcome();
        outcome.failed = vec![failed(DocumentError::NoProfileConfigured {
            doc_type: "org.iso.23220.photoid.1".to_string(),
        })];
        outcome.errors = vec![ResponseError::DocumentsFailed];

        let (_, doc_types, errors) =
            verified_namespaces_and_errors(&outcome).expect("derivation failed");
        assert_eq!(doc_types, vec!["org.iso.23220.photoid.1".to_string()]);

        let errors = errors.expect("a failed document must be reported");
        assert!(
            errors.contains("org.iso.23220.photoid.1"),
            "the doctype whose profile is missing must be named: {errors}"
        );
        assert!(
            errors.contains("profile"),
            "the reason must survive into the payload, not just `DocumentsFailed`: {errors}"
        );
    }

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
        let result = verify_device_response(vec![0xff, 0xff, 0xff], vec![0x80], vec![], None, None);
        assert!(matches!(
            result,
            Err(MDLReaderResponseError::Generic { .. })
        ));
    }
}
