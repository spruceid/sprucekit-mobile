#[cfg(test)]
mod tests {
    use base64::prelude::*;
    use isomdl::definitions::{DeviceKeyInfo, DigestAlgorithm, ValidityInfo};
    use isomdl::issuance::mdoc::Builder;
    use uniffi::deps::anyhow;

    use isomdl::definitions::device_key::cose_key::{CoseKey, EC2Curve, EC2Y};
    use isomdl::definitions::namespaces::{
        org_iso_18013_5_1::OrgIso1801351, org_iso_18013_5_1_aamva::OrgIso1801351Aamva,
    };
    use isomdl::issuance::mdoc::*;

    use elliptic_curve::sec1::ToEncodedPoint;
    use isomdl::definitions::traits::{FromJson, ToNamespaceMap};
    use p256::ecdsa::{Signature, SigningKey};
    use p256::pkcs8::DecodePrivateKey;
    use p256::SecretKey;
    use time::OffsetDateTime;

    #[tokio::test]
    async fn base64_encode_profile() -> Result<(), anyhow::Error> {
        let base64_encoded =
            BASE64_STANDARD.encode(include_bytes!("../../tests/examples/profile.jpg"));

        // let content = format!("data:image/jpg;base64,{}", base64_encoded);
        let content = base64_encoded;

        // Save picture to a file
        std::fs::write("tests/examples/profile.b64", &content)?;

        Ok(())
    }

    #[tokio::test]
    async fn test_remote_provisioning() -> Result<(), anyhow::Error> {
        let client = reqwest::Client::new();
        let url = "http://0.0.0.0:3003/api2/issuance/mdl/application";

        let mdl_data = isomdl_data();
        let prepared = minimal_test_mdoc_builder()
            .prepare(isomdl::cose_rs::algorithm::Algorithm::ES256)
            .expect("failed to prepare mdoc");

        let prepared_bytes = serde_cbor::to_vec(&prepared).expect("failed to serialize cbor bytes");

        let payload = serde_json::json!({
            "mdoc_json": mdl_data,
            "prepared_mdoc_cbor_bytes": prepared_bytes
        });

        println!("Payload: {payload}");

        let response = client.post(url).json(&payload).send().await?;

        println!("Response: {response:?}");

        let text = response.text().await?;

        println!("Text: {text}");

        Ok(())
    }

    pub fn isomdl_data() -> serde_json::Value {
        serde_json::json!(
            {
              "family_name":"Staples",
              "given_name":"James",
              "birth_date":"1980-01-01",
              "issue_date":"2020-01-01",
              "expiry_date":"2030-01-01",
              "issuing_country":"US",
              "issuing_authority":"IN BMV",
              "document_number":"DL12345678",
              "portrait": include_str!("../../tests/examples/profile.b64"),
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
              "issuing_jurisdiction":"US-IN",
              "nationality":"US",
              "resident_city":"Indianapolis",
              "resident_state":"Indiana",
              "resident_postal_code":"46201-0000",
              "resident_country": "US"
            }
        )
    }

    fn aamva_isomdl_data() -> serde_json::Value {
        serde_json::json!(
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
        )
    }

    fn minimal_test_mdoc_builder() -> Builder {
        let doc_type = String::from("org.iso.18013.5.1.mDL");
        let isomdl_namespace = String::from("org.iso.18013.5.1");
        let aamva_namespace = String::from("org.iso.18013.5.1.aamva");

        let isomdl_data = OrgIso1801351::from_json(&isomdl_data())
            .unwrap()
            .to_ns_map();
        let aamva_data = OrgIso1801351Aamva::from_json(&aamva_isomdl_data())
            .unwrap()
            .to_ns_map();

        let namespaces = [
            (isomdl_namespace, isomdl_data),
            (aamva_namespace, aamva_data),
        ]
        .into_iter()
        .collect();

        let validity_info = ValidityInfo {
            signed: OffsetDateTime::now_utc(),
            valid_from: OffsetDateTime::now_utc(),
            valid_until: OffsetDateTime::now_utc(),
            expected_update: None,
        };

        let digest_algorithm = DigestAlgorithm::SHA256;

        let der = include_str!("../../tests/examples/device_key.b64");
        let der_bytes = base64::decode(der).unwrap();
        let key = p256::SecretKey::from_sec1_der(&der_bytes).unwrap();
        let pub_key = key.public_key();
        let ec = pub_key.to_encoded_point(false);
        let x = ec.x().unwrap().to_vec();
        let y = EC2Y::Value(ec.y().unwrap().to_vec());
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

        Mdoc::builder()
            .doc_type(doc_type)
            .namespaces(namespaces)
            .validity_info(validity_info)
            .digest_algorithm(digest_algorithm)
            .device_key_info(device_key_info)
    }
}
