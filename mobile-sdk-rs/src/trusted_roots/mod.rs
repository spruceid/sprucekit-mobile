// certificates.rs
//
// SPRUCE SYSTEMS, INC. PROPRIETARY AND CONFIDENTIAL.
//
// Spruce Systems, Inc. Copyright 2025. All Rights Reserved. Spruce Systems, Inc.
// retains sole and exclusive, right, title and interest in and to all code, Work
// Product and other deliverables, and all copies, modifications, and derivative
// works thereof, including all proprietary or intellectual property rights
// contained therein. The file may not be used or distributed without express
// permission of Spruce Systems, Inc.

use x509_cert::{der::Decode as _, Certificate};

const SPRUCE_COUNTY_ROOT_CERTIFICATE_DER: &[u8] = include_bytes!("./spruce_county.der");

pub fn trusted_roots() -> uniffi::deps::anyhow::Result<Vec<Certificate>> {
    vec![load_spruce_county_root_certificate()]
        .into_iter()
        .collect()
}

fn load_spruce_county_root_certificate() -> anyhow::Result<Certificate> {
    Certificate::from_der(SPRUCE_COUNTY_ROOT_CERTIFICATE_DER)
        .map_err(|e| anyhow::anyhow!("could not load the root certificate: {e}"))
}
