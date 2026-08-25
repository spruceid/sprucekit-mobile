use std::sync::Arc;
use std::time::Duration;

use anyhow::{Context, Result};
use isomdl::{
    definitions::{
        helpers::NonEmptyMap,
        namespaces::org_iso_18013_5_1::OrgIso1801351,
        traits::{FromJson, ToNamespaceMap},
        x509::X5Chain,
        CoseKey, DeviceKeyInfo, DigestAlgorithm, EC2Curve, ValidityInfo, EC2Y,
    },
    issuance::Mdoc,
    presentation::device::Document,
};
use p256::{
    elliptic_curve::sec1::ToEncodedPoint,
    pkcs8::{DecodePrivateKey, EncodePublicKey, ObjectIdentifier},
    PublicKey,
};
use rand::Rng;
use serde::{Deserialize, Serialize};
use sha1::{Digest, Sha1};
use signature::{Keypair, KeypairRef, Signer};
use ssi::crypto::rand;
use time::OffsetDateTime;
use x509_cert::{
    builder::{Builder, CertificateBuilder},
    der::{asn1::OctetString, DecodePem as _},
    ext::pkix::{
        crl::dp::DistributionPoint,
        name::{DistributionPointName, GeneralName},
        AuthorityKeyIdentifier, CrlDistributionPoints, ExtendedKeyUsage, IssuerAltName, KeyUsage,
        KeyUsages, SubjectKeyIdentifier,
    },
    name::Name,
    spki::{
        DynSignatureAlgorithmIdentifier, SignatureBitStringEncoding, SubjectPublicKeyInfoOwned,
    },
    time::Validity,
    Certificate,
};

use crate::crypto::{KeyAlias, KeyStore};

#[derive(Debug, uniffi::Error, thiserror::Error)]
pub enum MdlUtilError {
    #[error("{0}")]
    General(String),
}

impl From<anyhow::Error> for MdlUtilError {
    fn from(value: anyhow::Error) -> Self {
        Self::General(format!("{value:#?}"))
    }
}

/// Test mDL data struct to provide dummy data
/// to pass to generating a test mDL.
#[derive(uniffi::Record, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub struct TestMdlData {
    family_name: String,
    given_name: String,
    birth_date: String,
    issue_date: String,
    expiry_date: String,
    issuing_country: String,
    issuing_authority: String,
    document_number: String,
    portrait: String,
    driving_privileges: Vec<String>,
    un_distinguishing_sign: String,
    administrative_number: String,
    sex: u16,
    height: u16,
    weight: u16,
    eye_colour: String,
    hair_colour: String,
    birth_place: String,
    resident_address: String,
    portrait_capture_date: String,
    age_in_years: u16,
    age_birth_year: u16,
    age_over_18: bool,
    age_over_21: bool,
    age_over_60: bool,
    nationality: String,
    resident_city: String,
    resident_state: String,
    resident_postal_code: String,
    resident_country: String,
    /// ISO 3166-2 issuing jurisdiction code (e.g. "US-NY"). When unset, the
    /// `issuing_jurisdiction` element is omitted from the generated mDoc.
    #[serde(skip_serializing_if = "Option::is_none")]
    issuing_jurisdiction: Option<String>,
}

#[uniffi::export]
/// Generate a new test mDL with hardcoded values, using the supplied key as the DeviceKey.
pub fn generate_test_mdl(
    key_manager: Arc<dyn KeyStore>,
    key_alias: KeyAlias,
) -> Result<crate::credential::mdoc::Mdoc, MdlUtilError> {
    Ok(generate_test_mdl_inner(key_manager, key_alias, None)?)
}

#[uniffi::export]
/// Generate a new test mDL with hardcoded values, using the supplied key as the DeviceKey.
pub fn generate_test_mdl_with_data(
    key_manager: Arc<dyn KeyStore>,
    key_alias: KeyAlias,
    data: TestMdlData,
) -> Result<crate::credential::mdoc::Mdoc, MdlUtilError> {
    Ok(generate_test_mdl_inner(key_manager, key_alias, Some(data))?)
}

#[derive(Debug, Serialize, Deserialize)]
struct MinimalEcJwk {
    kty: String,
    crv: String,
    x: String,
    y: String,
}

fn generate_test_mdl_inner(
    key_manager: Arc<dyn KeyStore>,
    key_alias: KeyAlias,
    data: Option<TestMdlData>,
) -> Result<crate::credential::mdoc::Mdoc> {
    tracing::info!("Generating test mDL");
    let (certificate, signer) =
        setup_certificate_chain().context("failed to setup certificate chain")?;
    let key = key_manager
        .get_signing_key(key_alias.clone())
        .context("failed to get signing key")?;
    // RustCrypto does not accept JWKs with additional fields, including the `alg` field, so we
    // need to manually extract the minimal JWK.
    let jwk: MinimalEcJwk = serde_json::from_str(&key.jwk().context("failed to get jwk")?)
        .context("failed to parse minimal jwk")?;
    let pk = p256::PublicKey::from_jwk_str(
        &serde_json::to_string(&jwk).context("failed to serialize minimal jwk")?,
    )
    .context("failed to parse public key")?;

    let mdoc_builder = prepare_mdoc(pk, data).context("failed to prepare mdoc")?;

    let x5chain = X5Chain::builder()
        .with_certificate(certificate)
        .context("failed to add certificate to x5chain")?
        .build()
        .context("failed to build x5chain")?;

    let mdoc = mdoc_builder
        .issue::<p256::ecdsa::SigningKey, p256::ecdsa::Signature>(x5chain, signer)
        .context("failed to issue mdoc")?;

    let namespaces = NonEmptyMap::maybe_new(
        mdoc.namespaces
            .into_inner()
            .into_iter()
            .map(|(namespace, elements)| {
                (
                    namespace,
                    NonEmptyMap::maybe_new(
                        elements
                            .into_inner()
                            .into_iter()
                            .map(|element| (element.as_ref().element_identifier.clone(), element))
                            .collect(),
                    )
                    .unwrap(),
                )
            })
            .collect(),
    )
    .unwrap();

    let document = Document {
        id: uuid::Uuid::new_v4(),
        issuer_auth: mdoc.issuer_auth,
        mso: mdoc.mso,
        namespaces,
    };

    Ok(crate::credential::mdoc::Mdoc::new_from_parts(
        document, key_alias,
    ))
}

fn prepare_mdoc(
    pub_key: PublicKey,
    data: Option<TestMdlData>,
) -> Result<isomdl::issuance::mdoc::Builder> {
    let isomdl_data = data.map(|d| {
        serde_json::to_value(&d)
    }).unwrap_or(Ok(serde_json::json!(
        {
          "family_name":"Doe",
          "given_name":"John",
          "birth_date":"1990-01-01",
          "issue_date":"2020-01-01",
          "expiry_date":"2030-01-01",
          "issuing_country":"US",
          "issuing_authority":"SpruceID",
          "document_number": format!("DL{}", rand::thread_rng().gen_range(10_000_000..100_000_000)),
          "portrait":include_str!("../../tests/res/mdl/portrait.base64"),
          "driving_privileges":[],
          "un_distinguishing_sign":"USA",
          "administrative_number":format!("ADM{}", rand::thread_rng().gen_range(10_000_000..100_000_000)),
          "sex":1,
          "height":180,
          "weight":75,
          "eye_colour":"blue",
          "hair_colour":"black",
          "birth_place":"USA, California",
          "resident_address":"123 Main St, Los Angeles, California, 90001",
          "portrait_capture_date":"2020-01-01T12:00:00Z",
          "age_in_years":35,
          "age_birth_year":1990,
          "age_over_18":true,
          "age_over_21":true,
          "age_over_60":false,
          "nationality":"US",
          "resident_city":"Los Angeles",
          "resident_state":"CA",
          "resident_postal_code":"90001",
          "resident_country": "US"
        }
    )))?;

    let doc_type = String::from("org.iso.18013.5.1.mDL");
    let isomdl_namespace = String::from("org.iso.18013.5.1");

    let isomdl_data = OrgIso1801351::from_json(&isomdl_data)?.to_ns_map();

    let namespaces = [(isomdl_namespace, isomdl_data)].into_iter().collect();

    let validity_info = ValidityInfo {
        signed: OffsetDateTime::now_utc(),
        valid_from: OffsetDateTime::now_utc(),
        // mDL valid for thirty days.
        valid_until: OffsetDateTime::now_utc() + Duration::from_secs(60 * 60 * 24 * 30),
        expected_update: None,
    };

    let digest_algorithm = DigestAlgorithm::SHA256;

    let ec = pub_key.to_encoded_point(false);
    let x = ec.x().context("EC missing X coordinate")?.to_vec();
    let y = EC2Y::Value(ec.y().context("EC missing X coordinate")?.to_vec());
    let device_key = CoseKey::EC2 {
        crv: EC2Curve::P256,
        x,
        y,
    };

    let device_key_info = DeviceKeyInfo {
        device_key,
        key_authorizations: None,
        key_info: None,
    };

    Ok(Mdoc::builder()
        .doc_type(doc_type)
        .namespaces(namespaces)
        .validity_info(validity_info)
        .digest_algorithm(digest_algorithm)
        .device_key_info(device_key_info))
}

fn setup_certificate_chain() -> Result<(Certificate, p256::ecdsa::SigningKey)> {
    let iaca_cert_pem = include_str!("../../tests/res/mdl/iaca-certificate.pem");
    let iaca_cert = Certificate::from_pem(iaca_cert_pem)?;
    let iaca_name: Name = iaca_cert.tbs_certificate.subject;
    let key_pem = include_str!("../../tests/res/mdl/iaca-key.pem");
    let iaca_key = p256::ecdsa::SigningKey::from_pkcs8_pem(key_pem)?;

    let ds_key = p256::ecdsa::SigningKey::random(&mut rand::thread_rng());
    let mut prepared_ds_certificate =
        prepare_signer_certificate(&ds_key, &iaca_key, iaca_name.clone())?;
    let signature: p256::ecdsa::Signature = iaca_key.sign(&prepared_ds_certificate.finalize()?);
    let ds_certificate: Certificate =
        prepared_ds_certificate.assemble(signature.to_der().to_bitstring()?)?;

    Ok((ds_certificate, ds_key))
}

fn prepare_signer_certificate<'s, S>(
    signer_key: &'s S,
    iaca_key: &'s S,
    iaca_name: Name,
) -> Result<CertificateBuilder<'s, S>>
where
    S: KeypairRef + DynSignatureAlgorithmIdentifier,
    S::VerifyingKey: EncodePublicKey,
{
    let spki = SubjectPublicKeyInfoOwned::from_key(signer_key.verifying_key())?;
    let ski_digest = Sha1::digest(spki.subject_public_key.raw_bytes());
    let ski_digest_octet = OctetString::new(ski_digest.to_vec())?;

    let apki = SubjectPublicKeyInfoOwned::from_key(iaca_key.verifying_key())?;
    let aki_digest = Sha1::digest(apki.subject_public_key.raw_bytes());
    let aki_digest_octet = OctetString::new(aki_digest.to_vec())?;

    let mut builder = CertificateBuilder::new(
        x509_cert::builder::Profile::Manual {
            issuer: Some(iaca_name),
        },
        rand::random::<u64>().into(),
        // Document signer certificate valid for sixty days.
        Validity::from_now(Duration::from_secs(60 * 60 * 24 * 60))?,
        "CN=SpruceID Test DS,C=US,ST=NY,O=SpruceID".parse()?,
        spki,
        iaca_key,
    )?;

    builder.add_extension(&SubjectKeyIdentifier(ski_digest_octet))?;

    builder.add_extension(&AuthorityKeyIdentifier {
        key_identifier: Some(aki_digest_octet),
        ..Default::default()
    })?;

    builder.add_extension(&KeyUsage(KeyUsages::DigitalSignature.into()))?;

    builder.add_extension(&IssuerAltName(vec![GeneralName::Rfc822Name(
        "isointerop@spruceid.com".to_string().try_into()?,
    )]))?;

    builder.add_extension(&CrlDistributionPoints(vec![DistributionPoint {
        distribution_point: Some(DistributionPointName::FullName(vec![
            GeneralName::UniformResourceIdentifier(
                "https://interopevent.spruceid.com/interop.crl"
                    .to_string()
                    .try_into()?,
            ),
        ])),
        reasons: None,
        crl_issuer: None,
    }]))?;

    builder.add_extension(&ExtendedKeyUsage(vec![ObjectIdentifier::new(
        "1.0.18013.5.1.2",
    )?]))?;

    Ok(builder)
}

#[cfg(test)]
mod tests {
    use ciborium::Value as Cbor;

    use super::*;
    use crate::crypto::{KeyAlias, RustTestKeyManager};

    fn test_mdl_data(issuing_jurisdiction: Option<String>) -> TestMdlData {
        TestMdlData {
            family_name: "Doe".to_string(),
            given_name: "John".to_string(),
            birth_date: "1990-01-01".to_string(),
            issue_date: "2020-01-01".to_string(),
            expiry_date: "2030-01-01".to_string(),
            issuing_country: "US".to_string(),
            issuing_authority: "SpruceID".to_string(),
            document_number: "DL12345678".to_string(),
            portrait: include_str!("../../tests/res/mdl/portrait.base64").to_string(),
            driving_privileges: vec![],
            un_distinguishing_sign: "USA".to_string(),
            administrative_number: "ADM12345678".to_string(),
            sex: 1,
            height: 180,
            weight: 75,
            eye_colour: "blue".to_string(),
            hair_colour: "black".to_string(),
            birth_place: "New York".to_string(),
            resident_address: "123 Main St, New York, NY, 10001".to_string(),
            portrait_capture_date: "2020-01-01T12:00:00Z".to_string(),
            age_in_years: 35,
            age_birth_year: 1990,
            age_over_18: true,
            age_over_21: true,
            age_over_60: false,
            nationality: "US".to_string(),
            resident_city: "New York".to_string(),
            resident_state: "NY".to_string(),
            resident_postal_code: "10001".to_string(),
            resident_country: "US".to_string(),
            issuing_jurisdiction,
        }
    }

    async fn generate(data: TestMdlData) -> crate::credential::mdoc::Mdoc {
        let key_manager = Arc::new(RustTestKeyManager::default());
        let key_alias = KeyAlias(uuid::Uuid::new_v4().to_string());
        key_manager
            .generate_p256_signing_key(key_alias.clone())
            .await
            .expect("key generation failed");
        generate_test_mdl_with_data(key_manager, key_alias, data)
            .expect("test mDL generation failed")
    }

    fn mdl_namespace_element(
        mdoc: &crate::credential::mdoc::Mdoc,
        element_identifier: &str,
    ) -> Option<Cbor> {
        mdoc.document()
            .namespaces
            .get("org.iso.18013.5.1")
            .expect("mDL namespace present")
            .get(element_identifier)
            .map(|element| element.as_ref().element_value.clone())
    }

    #[test_log::test(tokio::test)]
    async fn issuing_jurisdiction_element_present_when_set() {
        let mdoc = generate(test_mdl_data(Some("US-NY".to_string()))).await;

        assert_eq!(
            mdl_namespace_element(&mdoc, "issuing_jurisdiction"),
            Some(Cbor::Text("US-NY".to_string()))
        );
    }

    #[test_log::test(tokio::test)]
    async fn issuing_jurisdiction_element_absent_when_unset() {
        let mdoc = generate(test_mdl_data(None)).await;

        assert!(mdl_namespace_element(&mdoc, "issuing_jurisdiction").is_none());
        // The rest of the mdoc is unaffected.
        assert_eq!(
            mdl_namespace_element(&mdoc, "family_name"),
            Some(Cbor::Text("Doe".to_string()))
        );
    }
}
