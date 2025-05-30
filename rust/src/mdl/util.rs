use std::sync::Arc;
use std::time::Duration;

use anyhow::{Context, Result};
use isomdl::{
    definitions::{
        helpers::NonEmptyMap,
        namespaces::{
            org_iso_18013_5_1::OrgIso1801351, org_iso_18013_5_1_aamva::OrgIso1801351Aamva,
        },
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

#[uniffi::export]
/// Generate a new test mDL with hardcoded values, using the supplied key as the DeviceKey.
pub fn generate_test_mdl(
    key_manager: Arc<dyn KeyStore>,
    key_alias: KeyAlias,
) -> Result<crate::credential::mdoc::Mdoc, MdlUtilError> {
    Ok(generate_test_mdl_inner(key_manager, key_alias)?)
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

    let mdoc_builder = prepare_mdoc(pk).context("failed to prepare mdoc")?;

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

fn prepare_mdoc(pub_key: PublicKey) -> Result<isomdl::issuance::mdoc::Builder> {
    let isomdl_data = serde_json::json!(
        {
          "family_name":"Smith",
          "given_name":"Alice",
          "birth_date":"1980-01-01",
          "issue_date":"2020-01-01",
          "expiry_date":"2030-01-01",
          "issuing_country":"US",
          "issuing_authority":"NY DMV",
          "document_number": format!("DL{}", rand::thread_rng().gen_range(10_000_000..100_000_000)),
          "portrait":include_str!("../../tests/res/mdl/portrait.base64"),
          "driving_privileges":[
            {
               "vehicle_category_code":"A",
               "issue_date":"2020-01-01",
               "expiry_date":"2030-01-01"
            },
            {
               "vehicle_category_code":"B",
               "issue_date":"2020-01-01",
               "expiry_date":"2030-01-01"
            }
          ],
          "un_distinguishing_sign":"USA",
          "administrative_number":"ABC123",
          "sex":1,
          "height":170,
          "weight":70,
          "eye_colour":"hazel",
          "hair_colour":"red",
          "birth_place":"Canada",
          "resident_address":"138 Eagle Street",
          "portrait_capture_date":"2020-01-01T12:00:00Z",
          "age_in_years":43,
          "age_birth_year":1980,
          "age_over_18":true,
          "age_over_21":true,
          "issuing_jurisdiction":"US-NY",
          "nationality":"US",
          "resident_city":"Albany",
          "resident_state":"New York",
          "resident_postal_code":"12202-1719",
          "resident_country": "US"
        }
    );

    let aamva_isomdl_data = serde_json::json!(
        {
          "domestic_driving_privileges":[
            {
              "domestic_vehicle_class":{
                "domestic_vehicle_class_code":"A",
                "domestic_vehicle_class_description":"unknown",
                "issue_date":"2020-01-01",
                "expiry_date":"2030-01-01"
              }
            },
            {
              "domestic_vehicle_class":{
                "domestic_vehicle_class_code":"B",
                "domestic_vehicle_class_description":"unknown",
                "issue_date":"2020-01-01",
                "expiry_date":"2030-01-01"
              }
            }
          ],
          "name_suffix":"1ST",
          "organ_donor":1,
          "veteran":1,
          "family_name_truncation":"N",
          "given_name_truncation":"N",
          "aka_family_name.v2":"Smithy",
          "aka_given_name.v2":"Ally",
          "aka_suffix":"I",
          "weight_range":3,
          "race_ethnicity":"AI",
          "EDL_credential":1,
          "sex":1,
          "DHS_compliance":"F",
          "resident_county":"001",
          "hazmat_endorsement_expiration_date":"2024-01-30",
          "CDL_indicator":1,
          "DHS_compliance_text":"Compliant",
          "DHS_temporary_lawful_status":1,
        }
    );

    let doc_type = String::from("org.iso.18013.5.1.mDL");
    let isomdl_namespace = String::from("org.iso.18013.5.1");
    let aamva_namespace = String::from("org.iso.18013.5.1.aamva");

    let isomdl_data = OrgIso1801351::from_json(&isomdl_data)?.to_ns_map();
    let aamva_data = OrgIso1801351Aamva::from_json(&aamva_isomdl_data)?.to_ns_map();

    let namespaces = [
        (isomdl_namespace, isomdl_data),
        (aamva_namespace, aamva_data),
    ]
    .into_iter()
    .collect();

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
    let iaca_cert_pem = include_str!("../../tests/res/mdl/utrecht-certificate.pem");
    let iaca_cert = Certificate::from_pem(iaca_cert_pem)?;
    let iaca_name: Name = iaca_cert.tbs_certificate.subject;
    let key_pem = include_str!("../../tests/res/mdl/utrecht-key.pem");
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
