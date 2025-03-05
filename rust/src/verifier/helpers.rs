use std::time::SystemTime;

use crate::verifier::outcome::{ClaimValue, Failure};
use cose_rs::{cwt::ClaimsSet, CoseSign1};
use log::debug;
use serde_cbor::Value;
use time::Date;
use time_macros::format_description;
use uniffi::deps::anyhow::{bail, Context, Result};
use x509_cert::{
    der::{oid::AssociatedOid, Decode},
    ext::pkix::{CrlDistributionPoints, KeyUsage},
    time::Validity,
    Certificate,
};

pub fn get_signer_certificate(cwt: &CoseSign1) -> Result<Certificate> {
    let cert_der = match cwt
        .protected()
        .get_i(33)
        .context("x5chain (label '33') is not in the protected header")?
    {
        serde_cbor::Value::Bytes(der) => der,
        serde_cbor::Value::Array(x5c) if x5c.len() == 1 => match &x5c[0] {
            serde_cbor::Value::Bytes(der) => der,
            v => bail!("unexpected format for x509 certificate: {v:?}"),
        },
        serde_cbor::Value::Array(_) => {
            bail!("x5chain contains more than one certificate")
        }
        v => bail!("unexpected format for x5chain: {v:?}"),
    };

    Certificate::from_der(cert_der).context("signer certificate could not be parsed")
}

pub fn extract_extensions(certificate: &Certificate) -> Result<(KeyUsage, CrlDistributionPoints)> {
    let mut key_usage = None;
    let mut crl_dp = None;

    // Find specific extensions and error if any unsupported 'critical' extensions are found.
    for extension in certificate
        .tbs_certificate
        .extensions
        .as_ref()
        .context("no extensions")?
    {
        match extension.extn_id {
            KeyUsage::OID => key_usage = Some(&extension.extn_value),
            CrlDistributionPoints::OID => crl_dp = Some(&extension.extn_value),
            oid if extension.critical => bail!("unexpected critical extension: {oid}"),
            oid => {
                debug!("skipping certificate extension {oid}")
            }
        }
    }

    let key_usage = KeyUsage::from_der(
        key_usage
            .context("'key usage' extension could not be found")?
            .as_bytes(),
    )
    .context("unable to parse 'key usage' extension")?;

    let crl_dp = CrlDistributionPoints::from_der(
        crl_dp
            .context("'crl distribution points' extension could not be found")?
            .as_bytes(),
    )
    .context("unable to parse 'crl distribution points' extension")?;

    Ok((key_usage, crl_dp))
}

// TODO: Use treeldr instead of manual parsing?
pub trait Claim: Sized {
    const CWT_LABEL: i32;
    const LABEL: &'static str;

    fn from_claims(claims: &ClaimsSet) -> crate::verifier::outcome::Result<Self> {
        claims
            .get_i(Self::CWT_LABEL)
            .ok_or_else(|| Failure::missing_claim(Self::LABEL))
            .and_then(Self::from_value)
    }

    /// Parse date strings of the form "YYYY-MM-DD".
    fn parse_datestr(value: &Value) -> crate::verifier::outcome::Result<ClaimValue> {
        let date_str = match value {
            Value::Text(t) => t,
            _ => return Err(Failure::malformed_claim(Self::LABEL, value, "wrong type")),
        };
        let format = format_description!("[year]-[month]-[day]");
        Date::parse(date_str, format)
            .map_err(|e| Failure::malformed_claim(Self::LABEL, value, e))
            .map(|_| ClaimValue::Date {
                value: date_str.clone(),
            })
    }

    fn from_value(value: &Value) -> crate::verifier::outcome::Result<Self>;
}

pub fn check_validity(validity: &Validity) -> Result<()> {
    let nbf = validity.not_before.to_system_time();
    let exp = validity.not_after.to_system_time();

    let now = SystemTime::now();

    if nbf <= now && now < exp {
        return Ok(());
    }

    bail!("certificate is invalid")
}
