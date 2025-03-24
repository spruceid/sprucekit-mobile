uniffi::setup_scaffolding!();

pub mod common;
pub mod context;
pub mod credential;
pub mod crypto;
pub mod did;
pub mod local_store;
pub mod logger;
pub mod mdl;
pub mod oid4vci;
pub mod oid4vp;
pub mod proof_of_possession;
pub mod storage_manager;
#[cfg(test)]
mod tests;
pub mod trusted_roots;
pub mod vdc_collection;
pub mod verifier;
pub mod w3c_vc_barcodes;
pub mod wallet_service_client;

pub use common::*;
pub use mdl::reader::*;
pub use mdl::*;
