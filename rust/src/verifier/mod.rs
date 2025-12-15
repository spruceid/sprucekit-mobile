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
                    Err(e) => Err(format!("{err}\n--------------\n{e}")),
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

#[cfg(test)]
mod tests {
    use cose_rs::CoseSign1;
    use signature::Verifier;
    use x509_cert::{
        der::{referenced::OwnedToRef, Decode, DecodePem, Encode},
        Certificate,
    };

    use super::Crypto;
    use crate::{
        base10_string_to_bytes_num, bytes_to_base10_string_num,
        credential::cwt::Cwt,
        verifier::crypto::{CoseP256Verifier, VerificationResult},
    };

    const COSE_SIGN_1_HEX: &str = "84590324a2012618218159031b30820317308202bda00302010202143fd62567134b2f3832589ba13f9e98a142001d60300a06082a8648ce3d040302306d310b30090603550406130255533111300f06035504080c08436f6c6f7261646f310f300d06035504070c0644656e766572310c300a060355040a0c034f495431133011060355040b0c0a6d79436f6c6f7261646f3117301506035504030c0e6d79636f6c6f7261646f2e676f76301e170d3235313231323138353432345a170d3335313231303138353432345a306d310b30090603550406130255533111300f06035504080c08436f6c6f7261646f310f300d06035504070c0644656e766572310c300a060355040a0c034f495431133011060355040b0c0a6d79436f6c6f7261646f3117301506035504030c0e6d79636f6c6f7261646f2e676f763059301306072a8648ce3d020106082a8648ce3d03010703420004a8f0b55a513875e3c52e495cb3236505a687c154f1fe62b3df6de94ae268877dc691ddda35d27185c6e9c6b7429c6ca9dca42b9f6dd234df59da9293b790c81fa382013930820135301d0603551d0e04160414a17000cba93b0c5a3c96e6c75ea6d37ca4546ee63081aa0603551d230481a230819f8014a17000cba93b0c5a3c96e6c75ea6d37ca4546ee6a171a46f306d310b30090603550406130255533111300f06035504080c08436f6c6f7261646f310f300d06035504070c0644656e766572310c300a060355040a0c034f495431133011060355040b0c0a6d79436f6c6f7261646f3117301506035504030c0e6d79636f6c6f7261646f2e676f7682143fd62567134b2f3832589ba13f9e98a142001d6030090603551d1304023000300e0603551d0f0101ff0404030202f4304c0603551d1f044530433041a03fa03d863b68747470733a2f2f61706976322e6465762e6d79636f6c6f7261646f2e676f762f2e77656c6c5f6b6e6f776e2f6d79636f6c6f7261646f2e63726c300a06082a8648ce3d040302034800304502201af9931e53639594c58557eb657aca29b33c58a3cbe87c59c50131b94f7ea570022100bcc741208b7123b536aed601c6a4ddcea0b596c0527dbc09b8d08422d60f2956a058cba601781f68747470733a2f2f6d79636f6c6f7261646f2e73746174652e636f2e75732f02693137303637313832300381686d79636f2d617070041a69405a5d0a782466326133653338382d336563662d343737342d383862392d3832316330646234343864383a00010000a5686c6173744e616d6564544553546966697273744e616d656554414d4d596363696e693137303637313832306b646174654f6642697274686a31322d30312d3139373874646f63756d656e7444697363696d696e61746f7266313139343939584036ca06f782e1b0162099d7698e47c172a6e9a0a33065b96a61d050b20fdd1fcadf377cf949cfca5858540e57be903a91c67ca79e26ddb2e06abe97f255874ec2";

    const CERT_PEM: &str = include_str!("../../tests/examples/pem_cert.txt");

    struct TestCrypto;

    impl Crypto for TestCrypto {
        fn p256_verify(
            &self,
            certificate_der: Vec<u8>,
            payload: Vec<u8>,
            signature: Vec<u8>,
        ) -> VerificationResult {
            let certificate = x509_cert::Certificate::from_der(&certificate_der).unwrap();
            let spki = certificate
                .tbs_certificate
                .subject_public_key_info
                .owned_to_ref();
            let pk: p256::PublicKey = spki.try_into().unwrap();
            let verifier: p256::ecdsa::VerifyingKey = pk.into();
            let signature = p256::ecdsa::DerSignature::from_bytes(&signature).unwrap();
            match verifier.verify(&payload, &signature) {
                Ok(()) => VerificationResult::Success,
                Err(e) => VerificationResult::Failure {
                    cause: e.to_string(),
                },
            }
        }
    }

    fn load_verifier<'a>(crypto: &'a TestCrypto, certificate_der: Vec<u8>) -> CoseP256Verifier<'a> {
        let verifier = CoseP256Verifier {
            crypto,
            certificate_der,
        };

        return verifier;
    }

    #[test]
    fn test_cose_sign1_parse() {
        let test_crypto = TestCrypto;

        let bytes = hex::decode(COSE_SIGN_1_HEX).expect("Failed to decode hex string");

        let cose_sign1: CoseSign1 =
            serde_cbor::from_slice(&bytes).expect("failed to parse CoseSign1 message");

        println!("CoseSign1: {cose_sign1:?}");

        let claims = cose_sign1.claims_set().expect("Failed to find claims set");

        println!("CWT Claims: {claims:?}");

        let cert = Certificate::from_pem(CERT_PEM.as_bytes()).expect("failed to parse cert");
        let verifier = load_verifier(&test_crypto, cert.to_der().expect("failed to parse as DER"));
        let result = cose_sign1.verify(&verifier, None, None);

        println!("Result: {result:?}");
    }

    #[tokio::test]
    async fn test_cwt_validate() {
        let bytes = hex::decode(COSE_SIGN_1_HEX).expect("Failed to decode hex string");

        let cwt = Cwt::new_from_bytes(bytes.to_vec()).expect("failed to parse cwt");
        let claims = cwt.claims_json().expect("failed to retrieve claims");
        println!("Claims: {claims:?}");

        match cwt.verify_with_certs(vec![CERT_PEM.to_string()]).await {
            Ok(()) => {}
            Err(crate::credential::cwt::CwtError::CwtExpired(_)) => {
                // NOTE: the example cwt is expired
            }
            Err(e) => panic!("{e:?}"),
        }
    }

    #[test]
    fn test_base10_encoding() {
        // miniz_oxide::deflate::compress_to_vec(input, level)
        let hex_bytes = hex::decode(COSE_SIGN_1_HEX).expect("failed to decode hex bytes");
        let base10_string = bytes_to_base10_string_num(hex_bytes.clone());
        let cwt_bytes = base10_string_to_bytes_num(base10_string).unwrap();

        assert_eq!(hex_bytes, cwt_bytes);

        let cwt = Cwt::new_from_bytes(cwt_bytes).expect("failed to parse base10 cwt");
        cwt.claims_json().expect("failed to retrieve claims");
    }
}
