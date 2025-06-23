pub mod crypto;
pub mod helpers;
pub mod outcome;

use std::collections::HashMap;

use crate::verifier::{
    crypto::{CoseP256Verifier, Crypto},
    outcome::{ClaimValue, CredentialInfo, Failure, Outcome, Result},
};
use cose_rs::{
    cwt::{claim::ExpirationTime, ClaimsSet},
    sign1::VerificationResult,
    CoseSign1,
};
use num_bigint::BigUint;
use num_traits::Num as _;
use ssi::status::token_status_list::{json::JsonStatusList, DecodeError};
use time::OffsetDateTime;
use uniffi::deps::anyhow::{self, anyhow, bail, Context, Error};
use x509_cert::{certificate::CertificateInner, der::Encode, Certificate};

pub trait Credential {
    const TITLE: &'static str;
    const IMAGE: &'static [u8];

    fn schemas() -> Vec<&'static str>;
    fn parse_claims(claims: ClaimsSet) -> Result<HashMap<String, ClaimValue>>;
}

pub fn retrieve_entry_from_status_list(status_list: String, idx: usize) -> Result<u8, Error> {
    let status_list: JsonStatusList = serde_json::from_str(status_list.as_str())
        .map_err(|_: serde_json::Error| anyhow!("Unable to parse JSON String"))?;
    let bitstring = status_list
        .decode(None)
        .map_err(|_: DecodeError| anyhow!("Unable to decode JsonStatusList bitstring"))?;
    bitstring
        .get(idx)
        .ok_or(anyhow!("Unable to get idx from bitstring"))
}

pub trait Verifiable: Credential {
    fn decode(&self, qr_code_payload: String) -> Result<(CoseSign1, CredentialInfo)> {
        let base10_str = qr_code_payload.strip_prefix('9').ok_or_else(|| {
            Failure::base10_decoding("payload did not begin with multibase prefix '9'")
        })?;
        let compressed_cwt_bytes = BigUint::from_str_radix(base10_str, 10)
            .map_err(Failure::base10_decoding)?
            .to_bytes_be();

        let cwt_bytes = miniz_oxide::inflate::decompress_to_vec(&compressed_cwt_bytes)
            .map_err(Failure::decompression)?;

        let cwt: CoseSign1 = serde_cbor::from_slice(&cwt_bytes).map_err(Failure::cbor_decoding)?;

        let mut claims = cwt
            .claims_set()
            .map_err(Failure::claims_retrieval)?
            .ok_or_else(Failure::empty_payload)?;

        match claims
            .remove_i(-65537)
            .ok_or_else(|| Failure::missing_claim("Credential Schema"))?
        {
            serde_cbor::Value::Text(s) if Self::schemas().contains(&s.as_str()) => (),
            v => {
                return Err(Failure::incorrect_credential(
                    format!("{:?}", Self::schemas()),
                    v,
                ))
            }
        }

        let claims = Self::parse_claims(claims)?;

        Ok((
            cwt,
            CredentialInfo {
                title: Self::TITLE.to_string(),
                image: Self::IMAGE.to_vec(),
                claims,
            },
        ))
    }

    fn validate<C: Crypto>(
        &self,
        crypto: &C,
        cwt: CoseSign1,
        trusted_roots: Vec<Certificate>,
    ) -> Result<()> {
        let signer_certificate = helpers::get_signer_certificate(&cwt).map_err(Failure::trust)?;

        // We want to manually handle the Err to get all errors, so try_fold would not work
        #[allow(clippy::manual_try_fold)]
        trusted_roots
            .into_iter()
            .filter(|cert| {
                cert.tbs_certificate.subject == signer_certificate.tbs_certificate.issuer
            })
            .fold(Result::Err("\n".to_string()), |res, cert| match res {
                Ok(_) => Ok(()),
                Err(err) => match self.validate_certificate_chain(crypto, &cwt, cert.clone()) {
                    Ok(_) => Ok(()),
                    Err(e) => Err(format!("{}\n--------------\n{}", err, e)),
                },
            })
            .map_err(|err| {
                anyhow!(if err == "\n" {
                    format!("signer certificate was not issued by the root:\n\texpected:\n\t\t{}\n\tfound: None.", signer_certificate.tbs_certificate.issuer)
                } else {
                    err
                })
            })
            .map_err(Failure::trust)?;

        self.validate_cwt(cwt)
    }

    fn validate_cwt(&self, cwt: CoseSign1) -> Result<()> {
        let claims = cwt
            .claims_set()
            .map_err(Failure::claims_retrieval)?
            .ok_or_else(Failure::empty_payload)?;

        if let Some(ExpirationTime(exp)) = claims
            .get_claim()
            .map_err(|e| Failure::malformed_claim("exp", &e, "could not parse"))?
        {
            let exp: OffsetDateTime = exp
                .try_into()
                .map_err(|e| Failure::malformed_claim("exp", &e, "could not parse"))?;
            if exp < OffsetDateTime::now_utc() {
                let date_format = time::macros::format_description!("[month]/[day]/[year]");
                let expiration_date_str = exp.format(date_format).map_err(Failure::internal)?;
                return Err(Failure::cwt_expired(expiration_date_str));
            }
        }

        Ok(())
    }

    fn validate_certificate_chain(
        &self,
        crypto: &dyn Crypto,
        cwt: &CoseSign1,
        root_certificate: CertificateInner,
    ) -> anyhow::Result<()> {
        let signer_certificate = helpers::get_signer_certificate(cwt)?;

        // Root validation.
        {
            helpers::check_validity(&root_certificate.tbs_certificate.validity)?;

            let (key_usage, _crl_dp) = helpers::extract_extensions(&root_certificate)
                .context("couldn't extract extensions from root certificate")?;

            if !key_usage.key_cert_sign() {
                bail!("root certificate cannot be used for verifying certificate signatures")
            }

            // TODO: Check crl
        }

        // Validate that Root issued Signer.
        let root_subject = &root_certificate.tbs_certificate.subject;
        let signer_issuer = &signer_certificate.tbs_certificate.issuer;
        if root_subject != signer_issuer {
            bail!("signer certificate was not issued by the root:\n\texpected:\n\t\t{root_subject}\n\tfound:\n\t\t{signer_issuer}")
        }
        let signer_tbs_der = signer_certificate
            .tbs_certificate
            .to_der()
            .context("unable to encode signer certificate as der")?;
        let signer_signature = signer_certificate.signature.raw_bytes().to_vec();
        crypto
            .p256_verify(
                root_certificate
                    .to_der()
                    .context("unable to encode root certificate as der")?,
                signer_tbs_der,
                signer_signature,
            )
            .into_result()
            .map_err(Error::msg)
            .context("failed to verify the signature on the signer certificate")?;

        // Signer validation.
        {
            helpers::check_validity(&root_certificate.tbs_certificate.validity)?;

            let (key_usage, _crl_dp) = helpers::extract_extensions(&signer_certificate)
                .context("couldn't extract extensions from signer certificate")?;

            if !key_usage.digital_signature() {
                bail!("signer certificate cannot be used for verifying signatures")
            }

            // TODO: Check crl
        }

        // Validate that Signer issued CWT.
        let verifier = CoseP256Verifier {
            crypto,
            certificate_der: signer_certificate
                .to_der()
                .context("unable to encode signer certificate as der")?,
        };
        match cwt.verify(&verifier, None, None) {
            VerificationResult::Success => Ok(()),
            VerificationResult::Failure(e) => {
                bail!("failed to verify the CWT signature: {e}")
            }
            VerificationResult::Error(e) => {
                Err(e).context("error occurred when verifying CWT signature")
            }
        }
    }

    fn verify<C: Crypto>(
        &self,
        crypto: &C,
        qr_code_payload: String,
        trusted_roots: Vec<Certificate>,
    ) -> Outcome {
        let (cwt, credential_info) = match self.decode(qr_code_payload) {
            Ok(s) => s,
            Err(f) => {
                return Outcome::Unverified {
                    credential_info: None,
                    failure: f,
                }
            }
        };

        match self.validate(crypto, cwt, trusted_roots) {
            Ok(()) => Outcome::Verified { credential_info },
            Err(f) => Outcome::Unverified {
                credential_info: Some(credential_info),
                failure: f,
            },
        }
    }
}
