uniffi::setup_scaffolding!();

pub mod common;
pub mod credential;
pub mod did;
pub mod local_store;
pub mod mdl;
pub mod oid4vci;
pub mod oid4vp;
pub mod proof_of_possession;
pub mod storage_manager;
#[cfg(test)]
mod tests;
pub mod vdc_collection;
pub mod verifier;
pub mod w3c_vc_barcodes;

pub use common::*;
pub use mdl::reader::*;
pub use mdl::*;
