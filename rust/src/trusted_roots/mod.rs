use x509_cert::{der::Decode as _, Certificate};

const SPRUCE_COUNTY_PROD_ROOT_CERTIFICATE_DER: &[u8] = include_bytes!("./spruce_county_prod.der");
const SPRUCE_COUNTY_STAGING_ROOT_CERTIFICATE_DER: &[u8] =
    include_bytes!("./spruce_county_staging.der");
const SPRUCE_COUNTY_DEV_ROOT_CERTIFICATE_DER: &[u8] = include_bytes!("./spruce_county_dev.der");

pub(crate) const AAMVA_CA_ROOT_CERTIFICATE_DER: &[u8] = include_bytes!("./aamva_ca_root.der");
pub(crate) const AAMVA_CA_INTERMEDIATE_CERTIFICATE_DER: &[u8] =
    include_bytes!("./aamva_ca_intermediate.der");

pub fn trusted_roots() -> uniffi::deps::anyhow::Result<Vec<Certificate>> {
    vec![
        load_spruce_county_prod_root_certificate(),
        load_spruce_county_staging_root_certificate(),
        load_spruce_county_dev_root_certificate(),
    ]
    .into_iter()
    .collect()
}

pub(crate) fn load_aamva_ca_root_certificate() -> anyhow::Result<Certificate> {
    Certificate::from_der(AAMVA_CA_ROOT_CERTIFICATE_DER)
        .map_err(|e| anyhow::anyhow!("could not load the AAMVA root CA certificate: {e}"))
}

pub(crate) fn load_aamva_ca_intermediate_certificate() -> anyhow::Result<Certificate> {
    Certificate::from_der(AAMVA_CA_INTERMEDIATE_CERTIFICATE_DER)
        .map_err(|e| anyhow::anyhow!("could not load the AAMVA intermediate CA certificate: {e}"))
}

fn load_spruce_county_prod_root_certificate() -> anyhow::Result<Certificate> {
    Certificate::from_der(SPRUCE_COUNTY_PROD_ROOT_CERTIFICATE_DER)
        .map_err(|e| anyhow::anyhow!("could not load the root certificate: {e}"))
}

fn load_spruce_county_staging_root_certificate() -> anyhow::Result<Certificate> {
    Certificate::from_der(SPRUCE_COUNTY_STAGING_ROOT_CERTIFICATE_DER)
        .map_err(|e| anyhow::anyhow!("could not load the root certificate: {e}"))
}

fn load_spruce_county_dev_root_certificate() -> anyhow::Result<Certificate> {
    Certificate::from_der(SPRUCE_COUNTY_DEV_ROOT_CERTIFICATE_DER)
        .map_err(|e| anyhow::anyhow!("could not load the root certificate: {e}"))
}
