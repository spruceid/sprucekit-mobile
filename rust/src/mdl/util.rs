use std::collections::BTreeMap;
use std::sync::Arc;
use std::time::Duration;

use anyhow::{Context, Result};
use base64::prelude::*;
use ciborium::Value;
use isomdl::{
    definitions::{
        helpers::NonEmptyMap,
        namespaces::org_iso_18013_5_1::OrgIso1801351,
        traits::{FromJson, ToNamespaceMap},
        x509::X5Chain,
        CoseKey, DeviceKeyInfo, DigestAlgorithm, EC2Curve, ValidityInfo, EC2Y,
    },
    issuance::{mdoc::Namespaces, Mdoc},
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

/// ISO 18013-5 mDL doctype.
const MDL_DOCTYPE: &str = "org.iso.18013.5.1.mDL";
/// ISO 18013-5 mDL namespace.
const MDL_NAMESPACE: &str = "org.iso.18013.5.1";

/// Photo ID doctype, per the ISO/IEC TS 23220-4 Annex C Photo ID profile.
///
/// Annex C is *informative*: it is a worked example of a profile, not a binding one. Normative
/// Annex B is the recipe national bodies and document-specific standards follow to define their own
/// profiles, so a real-world Photo ID may differ from what this module issues. Annex C is the right
/// basis for a test credential precisely because it is the published worked example.
///
/// Annex C spells this with a lowercase `id`. Some implementations use `org.iso.23220.photoID.1`
/// instead, and doctype matching is case-sensitive, so change this constant to interoperate with
/// those.
pub(crate) const PHOTO_ID_DOCTYPE: &str = "org.iso.23220.photoid.1";
/// Namespace of the common data elements defined by ISO/IEC 23220-2 (Annex C table 1).
pub(crate) const ISO_23220_1_NAMESPACE: &str = "org.iso.23220.1";
/// Namespace of the Photo ID specific data elements (Annex C table 2).
pub(crate) const PHOTO_ID_NAMESPACE: &str = "org.iso.23220.photoid.1";

/// CBOR tag for `full-date` (RFC 8943), used by date-only data elements.
const CBOR_TAG_FULL_DATE: u64 = 1004;
/// CBOR tag for a standard date/time string (RFC 8949), used by `tdate` data elements.
const CBOR_TAG_DATE_TIME: u64 = 0;

/// mDL is valid for thirty days.
const MDOC_VALIDITY: Duration = Duration::from_secs(60 * 60 * 24 * 30);

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

/// Issue a test mdoc of an arbitrary doctype, signed by the bundled SpruceID test CA and bound to
/// `key_alias` as the DeviceKey.
fn issue_test_mdoc(
    key_manager: Arc<dyn KeyStore>,
    key_alias: KeyAlias,
    doc_type: &str,
    namespaces: Namespaces,
) -> Result<crate::credential::mdoc::Mdoc> {
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

    let validity_info = ValidityInfo {
        signed: OffsetDateTime::now_utc(),
        valid_from: OffsetDateTime::now_utc(),
        valid_until: OffsetDateTime::now_utc() + MDOC_VALIDITY,
        expected_update: None,
    };

    let x5chain = X5Chain::builder()
        .with_certificate(certificate)
        .context("failed to add certificate to x5chain")?
        .build()
        .context("failed to build x5chain")?;

    let mdoc = Mdoc::builder()
        .doc_type(doc_type.to_string())
        .namespaces(namespaces)
        .validity_info(validity_info)
        .digest_algorithm(DigestAlgorithm::SHA256)
        .device_key_info(device_key_info(pk)?)
        .issue::<p256::ecdsa::SigningKey, p256::ecdsa::Signature>(x5chain, signer)
        .context("failed to issue mdoc")?;

    // `Mdoc` keys elements positionally within each namespace, whereas `Document` keys them by
    // element identifier; reshape accordingly.
    let namespaces = mdoc
        .namespaces
        .into_inner()
        .into_iter()
        .map(|(namespace, elements)| {
            let elements = NonEmptyMap::maybe_new(
                elements
                    .into_inner()
                    .into_iter()
                    .map(|element| (element.as_ref().element_identifier.clone(), element))
                    .collect(),
            )
            .with_context(|| format!("issued mdoc namespace {namespace} has no elements"))?;
            Ok((namespace, elements))
        })
        .collect::<Result<BTreeMap<_, _>>>()?;
    let namespaces = NonEmptyMap::maybe_new(namespaces).context("issued mdoc has no namespaces")?;

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

/// Derive the mdoc `DeviceKeyInfo` from the holder's public key.
fn device_key_info(pub_key: PublicKey) -> Result<DeviceKeyInfo> {
    let ec = pub_key.to_encoded_point(false);
    let x = ec.x().context("EC missing X coordinate")?.to_vec();
    let y = EC2Y::Value(ec.y().context("EC missing Y coordinate")?.to_vec());
    let device_key = CoseKey::EC2 {
        crv: EC2Curve::P256,
        x,
        y,
    };

    Ok(DeviceKeyInfo {
        device_key,
        key_authorizations: None,
        key_info: None,
    })
}

fn generate_test_mdl_inner(
    key_manager: Arc<dyn KeyStore>,
    key_alias: KeyAlias,
    data: Option<TestMdlData>,
) -> Result<crate::credential::mdoc::Mdoc> {
    tracing::info!("Generating test mDL");
    let namespaces = mdl_namespaces(data).context("failed to build mDL namespaces")?;
    issue_test_mdoc(key_manager, key_alias, MDL_DOCTYPE, namespaces)
}

fn mdl_namespaces(data: Option<TestMdlData>) -> Result<Namespaces> {
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

    let isomdl_data = OrgIso1801351::from_json(&isomdl_data)?.to_ns_map();

    Ok([(MDL_NAMESPACE.to_string(), isomdl_data)]
        .into_iter()
        .collect())
}

/// Test Photo ID data struct to provide dummy data to pass to generating a test Photo ID.
///
/// Elements the Annex C example marks mandatory or recommended are required here; elements it marks
/// optional are `Option`al so a minimal credential can be issued. Annex C is informative, so treat
/// this presence split as "what the worked example does", not as a conformance requirement.
#[derive(uniffi::Record)]
pub struct TestPhotoIdData {
    // `org.iso.23220.1` (Annex C table 1), mandatory.
    family_name_unicode: String,
    given_name_unicode: String,
    /// `full-date`, e.g. `"1990-01-01"`.
    birth_date: String,
    /// Base64-encoded image, issued as a CBOR byte string.
    portrait: String,
    /// `full-date`, e.g. `"2020-01-01"`.
    issue_date: String,
    /// `full-date`, e.g. `"2030-01-01"`.
    expiry_date: String,
    issuing_authority_unicode: String,
    /// ISO 3166-1 alpha-2 country code.
    issuing_country: String,
    age_over_18: bool,
    // `org.iso.23220.1`, recommended.
    age_in_years: u16,
    age_birth_year: u16,
    age_over_21: Option<bool>,
    // `org.iso.23220.1`, optional.
    /// `tdate`, e.g. `"2020-01-01T12:00:00Z"`.
    portrait_capture_date: Option<String>,
    birthplace: Option<String>,
    name_at_birth: Option<String>,
    resident_address_unicode: Option<String>,
    resident_city_unicode: Option<String>,
    resident_postal_code: Option<String>,
    resident_country: Option<String>,
    resident_city_latin1: Option<String>,
    sex: Option<u16>,
    /// ISO 3166-1 alpha-2 country code. Unlike the EUDI PID, this is a single string.
    nationality: Option<String>,
    document_number: Option<String>,
    issuing_subdivision: Option<String>,
    family_name_latin1: Option<String>,
    given_name_latin1: Option<String>,
    // `org.iso.23220.photoid.1` (Annex C table 2), all optional.
    person_id: Option<String>,
    birth_country: Option<String>,
    birth_state: Option<String>,
    birth_city: Option<String>,
    administrative_number: Option<String>,
    resident_street: Option<String>,
    resident_house_number: Option<String>,
    travel_document_number: Option<String>,
    resident_state: Option<String>,
}

#[uniffi::export]
/// Generate a new test Photo ID with hardcoded values, using the supplied key as the DeviceKey.
pub fn generate_test_photo_id(
    key_manager: Arc<dyn KeyStore>,
    key_alias: KeyAlias,
) -> Result<crate::credential::mdoc::Mdoc, MdlUtilError> {
    Ok(generate_test_photo_id_inner(
        key_manager,
        key_alias,
        default_test_photo_id_data(),
    )?)
}

#[uniffi::export]
/// Generate a new test Photo ID from the supplied data, using the supplied key as the DeviceKey.
pub fn generate_test_photo_id_with_data(
    key_manager: Arc<dyn KeyStore>,
    key_alias: KeyAlias,
    data: TestPhotoIdData,
) -> Result<crate::credential::mdoc::Mdoc, MdlUtilError> {
    Ok(generate_test_photo_id_inner(key_manager, key_alias, data)?)
}

fn generate_test_photo_id_inner(
    key_manager: Arc<dyn KeyStore>,
    key_alias: KeyAlias,
    data: TestPhotoIdData,
) -> Result<crate::credential::mdoc::Mdoc> {
    tracing::info!("Generating test Photo ID");
    let namespaces = photo_id_namespaces(data).context("failed to build Photo ID namespaces")?;
    issue_test_mdoc(key_manager, key_alias, PHOTO_ID_DOCTYPE, namespaces)
}

fn default_test_photo_id_data() -> TestPhotoIdData {
    TestPhotoIdData {
        family_name_unicode: "Doe".into(),
        given_name_unicode: "John".into(),
        birth_date: "1990-01-01".into(),
        portrait: include_str!("../../tests/res/mdl/portrait.base64").into(),
        issue_date: "2020-01-01".into(),
        expiry_date: "2030-01-01".into(),
        issuing_authority_unicode: "SpruceID".into(),
        issuing_country: "US".into(),
        age_over_18: true,
        age_in_years: 35,
        age_birth_year: 1990,
        age_over_21: Some(true),
        portrait_capture_date: Some("2020-01-01T12:00:00Z".into()),
        birthplace: Some("USA, California".into()),
        name_at_birth: None,
        resident_address_unicode: Some("123 Main St, Los Angeles, California, 90001".into()),
        resident_city_unicode: Some("Los Angeles".into()),
        resident_postal_code: Some("90001".into()),
        resident_country: Some("US".into()),
        resident_city_latin1: Some("Los Angeles".into()),
        sex: Some(1),
        nationality: Some("US".into()),
        document_number: Some(format!(
            "ID{}",
            rand::thread_rng().gen_range(10_000_000..100_000_000)
        )),
        issuing_subdivision: Some("US-CA".into()),
        family_name_latin1: Some("Doe".into()),
        given_name_latin1: Some("John".into()),
        person_id: Some(format!(
            "PID{}",
            rand::thread_rng().gen_range(10_000_000..100_000_000)
        )),
        birth_country: Some("US".into()),
        birth_state: Some("CA".into()),
        birth_city: Some("Los Angeles".into()),
        administrative_number: Some(format!(
            "ADM{}",
            rand::thread_rng().gen_range(10_000_000..100_000_000)
        )),
        resident_street: Some("Main St".into()),
        resident_house_number: Some("123".into()),
        travel_document_number: None,
        resident_state: Some("CA".into()),
    }
}

/// Build the Photo ID namespace map.
///
/// Unlike the mDL, `isomdl` has no typed namespace for ISO/IEC 23220-2, so the CBOR values are
/// constructed directly and each data element must be given the encoding Annex C requires -- dates
/// are tagged, the portrait is a byte string, and ages are unsigned integers.
fn photo_id_namespaces(data: TestPhotoIdData) -> Result<Namespaces> {
    let portrait = BASE64_STANDARD
        .decode(data.portrait.trim())
        .context("failed to base64-decode portrait")?;

    let mut iso_23220_1 = BTreeMap::from([
        (
            "family_name_unicode".to_string(),
            Value::from(data.family_name_unicode),
        ),
        (
            "given_name_unicode".to_string(),
            Value::from(data.given_name_unicode),
        ),
        ("birth_date".to_string(), full_date(data.birth_date)),
        ("portrait".to_string(), Value::from(portrait)),
        ("issue_date".to_string(), full_date(data.issue_date)),
        ("expiry_date".to_string(), full_date(data.expiry_date)),
        (
            "issuing_authority_unicode".to_string(),
            Value::from(data.issuing_authority_unicode),
        ),
        (
            "issuing_country".to_string(),
            Value::from(data.issuing_country),
        ),
        ("age_over_18".to_string(), Value::from(data.age_over_18)),
        ("age_in_years".to_string(), Value::from(data.age_in_years)),
        (
            "age_birth_year".to_string(),
            Value::from(data.age_birth_year),
        ),
    ]);
    insert_optional(&mut iso_23220_1, "age_over_21", data.age_over_21);
    insert_optional(
        &mut iso_23220_1,
        "portrait_capture_date",
        data.portrait_capture_date.map(date_time),
    );
    insert_optional(&mut iso_23220_1, "birthplace", data.birthplace);
    insert_optional(&mut iso_23220_1, "name_at_birth", data.name_at_birth);
    insert_optional(
        &mut iso_23220_1,
        "resident_address_unicode",
        data.resident_address_unicode,
    );
    insert_optional(
        &mut iso_23220_1,
        "resident_city_unicode",
        data.resident_city_unicode,
    );
    insert_optional(
        &mut iso_23220_1,
        "resident_postal_code",
        data.resident_postal_code,
    );
    insert_optional(&mut iso_23220_1, "resident_country", data.resident_country);
    insert_optional(
        &mut iso_23220_1,
        "resident_city_latin1",
        data.resident_city_latin1,
    );
    insert_optional(&mut iso_23220_1, "sex", data.sex);
    insert_optional(&mut iso_23220_1, "nationality", data.nationality);
    insert_optional(&mut iso_23220_1, "document_number", data.document_number);
    insert_optional(
        &mut iso_23220_1,
        "issuing_subdivision",
        data.issuing_subdivision,
    );
    insert_optional(
        &mut iso_23220_1,
        "family_name_latin1",
        data.family_name_latin1,
    );
    insert_optional(
        &mut iso_23220_1,
        "given_name_latin1",
        data.given_name_latin1,
    );

    let mut photo_id = BTreeMap::new();
    insert_optional(&mut photo_id, "person_id", data.person_id);
    insert_optional(&mut photo_id, "birth_country", data.birth_country);
    insert_optional(&mut photo_id, "birth_state", data.birth_state);
    insert_optional(&mut photo_id, "birth_city", data.birth_city);
    insert_optional(
        &mut photo_id,
        "administrative_number",
        data.administrative_number,
    );
    insert_optional(&mut photo_id, "resident_street", data.resident_street);
    insert_optional(
        &mut photo_id,
        "resident_house_number",
        data.resident_house_number,
    );
    insert_optional(
        &mut photo_id,
        "travel_document_number",
        data.travel_document_number,
    );
    insert_optional(&mut photo_id, "resident_state", data.resident_state);

    let mut namespaces = Namespaces::from([(ISO_23220_1_NAMESPACE.to_string(), iso_23220_1)]);
    // Every table 2 element is optional, and `isomdl` rejects an empty namespace.
    if !photo_id.is_empty() {
        namespaces.insert(PHOTO_ID_NAMESPACE.to_string(), photo_id);
    }

    Ok(namespaces)
}

/// Encode a `full-date` data element, e.g. `"1990-01-01"`.
fn full_date(value: String) -> Value {
    Value::Tag(CBOR_TAG_FULL_DATE, Box::new(Value::Text(value)))
}

/// Encode a `tdate` data element, e.g. `"2020-01-01T12:00:00Z"`.
fn date_time(value: String) -> Value {
    Value::Tag(CBOR_TAG_DATE_TIME, Box::new(Value::Text(value)))
}

/// Insert a data element only when a value was supplied.
fn insert_optional(
    elements: &mut BTreeMap<String, Value>,
    identifier: &str,
    value: Option<impl Into<Value>>,
) {
    if let Some(value) = value {
        elements.insert(identifier.to_string(), value.into());
    }
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

    // The 18013-5 document-signer EKU, used for every doctype this module issues -- including the
    // 23220-4 Photo ID.
    //
    // The *structure* of the 18013-5 Annex B DS profile (IACA root, no sub-CAs, SKI, KeyUsage,
    // mandatory CRLDistributionPoints, IssuerAltName) is credential-agnostic; it is only the OID
    // values that 18013-5 reserves for the mDL, and 23220-4 defines generic replacements. So this
    // certificate is structurally right for a Photo ID and wrong only in this one value -- which is
    // also what `isomdl` currently expects, since it validates every document signer against the
    // mDL ruleset regardless of doctype. Hence: correct for our own verifier today.
    //
    // Note this extension must stay a *single* OID. `isomdl`'s `ExtendedKeyUsageValidator` requires
    // that every OID present equal the expected one, so a dual-purpose signer carrying both an mDL
    // and a Photo ID EKU is rejected.
    //
    // The 23220 replacement is known: an in-flight `isomdl` profile uses `1.0.23220.4.1.2` as the
    // document-signer EKU for 23220 credentials (the 23220-4 Annex B.2.5 arc). Because the match is
    // exclusive, adopting it means *swapping* this value for the Photo ID, which in turn means
    // `setup_certificate_chain` must issue a per-profile DS certificate rather than the one it
    // shares across doctypes today. Generation and verifier profile selection have to move in the
    // same change: select a 23220 profile while still signing with the mDL EKU (or the reverse) and
    // every Photo ID fails issuer authentication, which presents as a signature bug.
    builder.add_extension(&ExtendedKeyUsage(vec![ObjectIdentifier::new(
        "1.0.18013.5.1.2",
    )?]))?;

    Ok(builder)
}

#[cfg(test)]
mod tests {
    use test_log::test;

    use super::*;
    use crate::crypto::RustTestKeyManager;

    fn element<'a>(namespaces: &'a Namespaces, namespace: &str, identifier: &str) -> &'a Value {
        namespaces
            .get(namespace)
            .unwrap_or_else(|| panic!("missing namespace {namespace}"))
            .get(identifier)
            .unwrap_or_else(|| panic!("missing element {namespace}/{identifier}"))
    }

    #[test]
    fn photo_id_namespaces_use_the_encodings_annex_c_requires() {
        let namespaces =
            photo_id_namespaces(default_test_photo_id_data()).expect("failed to build namespaces");

        assert_eq!(
            namespaces.keys().collect::<Vec<_>>(),
            vec![ISO_23220_1_NAMESPACE, PHOTO_ID_NAMESPACE]
        );

        // Dates are tagged, not bare text.
        assert_eq!(
            element(&namespaces, ISO_23220_1_NAMESPACE, "birth_date"),
            &Value::Tag(1004, Box::new(Value::Text("1990-01-01".into())))
        );
        assert_eq!(
            element(&namespaces, ISO_23220_1_NAMESPACE, "issue_date"),
            &Value::Tag(1004, Box::new(Value::Text("2020-01-01".into())))
        );
        assert_eq!(
            element(&namespaces, ISO_23220_1_NAMESPACE, "expiry_date"),
            &Value::Tag(1004, Box::new(Value::Text("2030-01-01".into())))
        );
        assert_eq!(
            element(&namespaces, ISO_23220_1_NAMESPACE, "portrait_capture_date"),
            &Value::Tag(0, Box::new(Value::Text("2020-01-01T12:00:00Z".into())))
        );

        // The portrait is a byte string, and must be the decoded image rather than its base64.
        let portrait = element(&namespaces, ISO_23220_1_NAMESPACE, "portrait")
            .as_bytes()
            .expect("portrait is not a byte string");
        assert!(portrait.starts_with(&[0xff, 0xd8, 0xff]), "not a JPEG");

        // Ages and sex are unsigned integers, not text.
        for identifier in ["age_in_years", "age_birth_year", "sex"] {
            assert!(
                element(&namespaces, ISO_23220_1_NAMESPACE, identifier).is_integer(),
                "{identifier} is not an integer"
            );
        }
        assert_eq!(
            element(&namespaces, ISO_23220_1_NAMESPACE, "age_over_18"),
            &Value::Bool(true)
        );

        // Unlike the EUDI PID, nationality is a single alpha-2 string.
        assert_eq!(
            element(&namespaces, ISO_23220_1_NAMESPACE, "nationality"),
            &Value::Text("US".into())
        );

        // Table 2 elements go in the Photo ID namespace, not the ISO 23220-2 one.
        assert!(element(&namespaces, PHOTO_ID_NAMESPACE, "person_id").is_text());
        assert!(!namespaces[ISO_23220_1_NAMESPACE].contains_key("person_id"));
        // `travel_document_number` is unset by default, so it must be absent rather than null.
        assert!(!namespaces[PHOTO_ID_NAMESPACE].contains_key("travel_document_number"));
    }

    #[test]
    fn photo_id_namespaces_omits_the_photo_id_namespace_when_it_would_be_empty() {
        let data = TestPhotoIdData {
            person_id: None,
            birth_country: None,
            birth_state: None,
            birth_city: None,
            administrative_number: None,
            resident_street: None,
            resident_house_number: None,
            travel_document_number: None,
            resident_state: None,
            ..default_test_photo_id_data()
        };

        let namespaces = photo_id_namespaces(data).expect("failed to build namespaces");

        assert_eq!(
            namespaces.keys().collect::<Vec<_>>(),
            vec![ISO_23220_1_NAMESPACE]
        );
    }

    #[test(tokio::test)]
    async fn generates_a_signed_photo_id_bound_to_the_device_key() {
        let key_manager = RustTestKeyManager::default();
        let key_alias = KeyAlias("test_photo_id".to_string());
        key_manager
            .generate_p256_signing_key(key_alias.clone())
            .await
            .expect("key generation failed");

        let mdoc = generate_test_photo_id(Arc::new(key_manager), key_alias.clone())
            .expect("Photo ID generation failed");

        assert_eq!(mdoc.doctype(), PHOTO_ID_DOCTYPE);
        assert_eq!(mdoc.key_alias(), key_alias);

        let document = mdoc.document();
        assert_eq!(document.mso.doc_type, PHOTO_ID_DOCTYPE);
        let mut namespaces = document.namespaces.keys().cloned().collect::<Vec<_>>();
        namespaces.sort();
        assert_eq!(namespaces, vec![ISO_23220_1_NAMESPACE, PHOTO_ID_NAMESPACE]);

        // The DeviceKey must be the holder's key, otherwise device authentication cannot succeed.
        let device_key = document.mso.device_key_info.device_key.clone();
        assert!(matches!(device_key, CoseKey::EC2 { .. }), "not an EC2 key");

        // Every element must be covered by a value digest so a reader can verify it. The digest
        // map is larger than the element map because decoy digests are enabled by default.
        for namespace in [ISO_23220_1_NAMESPACE, PHOTO_ID_NAMESPACE] {
            let digests = document
                .mso
                .value_digests
                .get(namespace)
                .unwrap_or_else(|| panic!("no value digests for {namespace}"));
            for (identifier, element) in document.namespaces[namespace].iter() {
                assert!(
                    digests.contains_key(&element.as_ref().digest_id),
                    "no digest for {namespace}/{identifier}"
                );
            }
        }

        assert!(!document.issuer_auth.inner.signature.is_empty());
    }

    #[test(tokio::test)]
    async fn photo_id_renders_through_the_display_path() {
        let key_manager = RustTestKeyManager::default();
        let key_alias = KeyAlias("test_photo_id_display".to_string());
        key_manager
            .generate_p256_signing_key(key_alias.clone())
            .await
            .expect("key generation failed");

        let mdoc = generate_test_photo_id(Arc::new(key_manager), key_alias)
            .expect("Photo ID generation failed");

        // `details()` is what both the wallet card and the verifier result screen render from, so
        // it is the closest we can get to the on-device output without a device.
        let details = mdoc.details();
        let value = |identifier: &str| -> String {
            details
                .values()
                .flatten()
                .find(|element| element.identifier == identifier)
                .unwrap_or_else(|| panic!("{identifier} missing from details()"))
                .value
                .clone()
                .unwrap_or_else(|| panic!("{identifier} has no displayable value"))
        };

        // Tagged dates must surface as the date itself, not as CBOR tag debug output.
        assert_eq!(value("birth_date"), "\"1990-01-01\"");
        assert_eq!(value("expiry_date"), "\"2030-01-01\"");
        assert_eq!(value("portrait_capture_date"), "\"2020-01-01T12:00:00Z\"");

        // The portrait must be a renderable data URI, not a raw byte dump.
        let portrait = value("portrait");
        assert!(
            portrait.starts_with("\"data:image/jpeg;base64,"),
            "portrait is not a JPEG data URI: {}",
            &portrait[..portrait.len().min(64)]
        );

        assert_eq!(value("sex"), "1");
        assert_eq!(value("age_over_18"), "true");
        // A Table 2 element, to confirm the second namespace reaches the display path too.
        assert!(value("person_id").starts_with("\"PID"));
    }
}
