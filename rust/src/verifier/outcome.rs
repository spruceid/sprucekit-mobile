use std::{collections::HashMap, fmt};
use uniffi::deps::anyhow;

pub type Result<T, E = Failure> = ::std::result::Result<T, E>;

/// The outcome of attempting to verify a credential.
#[derive(uniffi::Enum)]
pub enum Outcome {
    /// The credential was successfully verified.
    Verified { credential_info: CredentialInfo },
    /// The credential could not be verified.
    Unverified {
        credential_info: Option<CredentialInfo>,
        failure: Failure,
    },
}

/// Information about the verified credential.
#[derive(uniffi::Record)]
pub struct CredentialInfo {
    /// The credential title that should be displayed on the success screen.
    pub title: String,
    /// The image that should be displayed on the success screen.
    pub image: Vec<u8>,
    /// The claims decoded from the credential.
    pub claims: HashMap<String, ClaimValue>,
}

/// Credential claim values.
#[derive(uniffi::Enum, Debug)]
pub enum ClaimValue {
    /// Any text claim that doesn't need special formatting.
    Text { value: String },
    /// A date claim in the format `[year]-[month]-[day]`.
    Date { value: String },
    /// MapArray
    MapClaim { value: HashMap<String, String> },
}

// TODO: This is intended to be replaced by an enumerative `#[uniffi::Error]` type.
// It has an existing use, and therefore is on a deprecated path when the new type is implemented.
#[derive(uniffi::Record, Debug)]
/// A verification failure with a code and reason.
pub struct Failure {
    code: u64,
    reason: String,
    details: String,
}

impl From<CredentialInfo> for Outcome {
    fn from(credential_info: CredentialInfo) -> Self {
        Outcome::Verified { credential_info }
    }
}

impl Failure {
    pub fn internal<D: fmt::Display>(d: D) -> Failure {
        Failure {
            code: 1,
            reason: "An unrecoverable error occurred.".to_string(),
            details: d.to_string(),
        }
    }

    pub fn base10_decoding<E: fmt::Display>(e: E) -> Failure {
        Failure {
            code: 2,
            reason: "Credential format incorrect: The credential could not be parsed from the QR code. Please ensure you are scanning a valid/supported credential".to_string(),
            details: format!("unable to decode the payload of the QR code: {e}"),
        }
    }

    pub fn decompression<E: fmt::Display>(e: E) -> Failure {
        Failure {
            code: 3,
            reason: "Credential format incorrect: The credential could not be parsed from the QR code. Please ensure you are scanning a valid/supported credential".to_string(),
            details: format!("unable to decompress the payload of the QR code: {e}"),
        }
    }

    pub fn cbor_decoding<E: fmt::Display>(e: E) -> Failure {
        Failure {
            code: 4,
            reason: "Credential format incorrect: The credential could not be parsed from the QR code. Please ensure you are scanning a valid/supported credential".to_string(),
            details: format!("unable to decode the credential: {e}"),
        }
    }

    pub fn claims_retrieval<E: fmt::Display>(e: E) -> Failure {
        Failure {
            code: 5,
            reason: "Credential format incorrect: The credential could not be parsed from the QR code. Please ensure you are scanning a valid/supported credential".to_string(),
            details: format!("unable to retrieve the claims from the credential: {e}"),
        }
    }

    pub fn empty_payload() -> Failure {
        Failure {
            code: 6,
            reason: "Credential format incorrect: The credential could not be parsed from the QR code. Please ensure you are scanning a valid/supported credential".to_string(),
            details: "credential does not have a payload".into(),
        }
    }

    pub fn incorrect_credential<D1: fmt::Display, D2: fmt::Debug>(
        expected: D1,
        found: D2,
    ) -> Failure {
        Failure {
            code: 7,
            reason: "Credential format incorrect: The credential could not be parsed from the QR code. Please ensure you are scanning a valid/supported credential".to_string(),
            details: format!("user did not present the expected credential: expected {expected}, received {found:?}"),
        }
    }

    pub fn missing_claim<D: fmt::Display>(d: D) -> Failure {
        Failure {
            code: 8,
            reason: "Credential format incorrect: The credential could not be parsed from the QR code. Please ensure you are scanning a valid/supported credential".to_string(),
            details: format!("credential is missing expected claim: {d}"),
        }
    }

    pub fn malformed_claim<D: fmt::Display, D2: fmt::Debug, D3: fmt::Display>(
        d: D,
        v: &D2,
        reason: D3,
    ) -> Failure {
        Failure {
            code: 9,
            reason: "Credential format incorrect: The credential could not be parsed from the QR code. Please ensure you are scanning a valid/supported credential".to_string(),
            details: format!("credential claim {d} is malformed: {reason}: {v:?}"),
        }
    }

    pub fn trust(error: anyhow::Error) -> Failure {
        Failure {
            code: 10,
            reason: "Signature Invalid: The credentials signature is incorrect.".to_string(),
            details: format!("could not establish trust in the credential: {error:?}"),
        }
    }

    pub fn cwt_expired(expiration_date: String) -> Failure {
        Failure {
            code: 11,
            reason: "Credential Expired: This credential is no longer valid.".to_string(),
            details: format!("Expiration Date: {expiration_date}"),
        }
    }

    pub fn load_root_certificates(error: anyhow::Error) -> Failure {
        Failure {
            code: 12,
            reason: "Trust could not be established in the credential".to_string(),
            details: format!("Root certificates could not be loaded: {error:?}"),
        }
    }
}
