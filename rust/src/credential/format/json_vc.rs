#![allow(deprecated)]

use crate::{
    credential::{
        status::{BitStringStatusListResolver, Status, StatusListError},
        Credential, CredentialEncodingError, CredentialFormat, VcdmVersion,
    },
    crypto::KeyAlias,
    oid4vp::{
        error::OID4VPError,
        presentation::{CredentialPresentation, PresentationOptions},
    },
    CredentialType,
};

use std::sync::Arc;

use openid4vp::core::{
    credential_format::ClaimFormatDesignation, response::parameters::VpTokenItem,
};
use serde_json::Value as Json;
use ssi::status::bitstring_status_list::BitstringStatusListEntry;
use ssi::{
    claims::vc::{
        syntax::{IdOr, NonEmptyObject, NonEmptyVec},
        v1::{Credential as _, JsonPresentation as JsonPresentationV1},
        v2::{
            syntax::JsonPresentation as JsonPresentationV2, Credential as _,
            JsonCredential as JsonCredentialV2,
        },
    },
    json_ld::iref::UriBuf,
    prelude::{AnyJsonCredential, AnyJsonPresentation},
};
use uuid::Uuid;

const ACCEPTED_CRYPTOSUITES: &[&str] = &["ecdsa-rdfc-2019"];

/// Selective-disclosure cryptosuites recognized by the isolated SD derive seam
/// ([`JsonVc::derive_sd_vp_credential`]).
const SD_CRYPTOSUITES: &[&str] = &["ecdsa-sd-2023"];

/// True iff `raw["proof"]` (object or array) carries a base proof whose
/// `cryptosuite` is in [`SD_CRYPTOSUITES`].
fn is_sd_base_proof(raw: &Json) -> bool {
    let proofs = match raw.get("proof") {
        Some(Json::Array(a)) => a.clone(),
        Some(obj @ Json::Object(_)) => vec![obj.clone()],
        _ => return false,
    };
    proofs.iter().any(|p| {
        p.get("cryptosuite")
            .and_then(|c| c.as_str())
            .map(|s| SD_CRYPTOSUITES.contains(&s))
            .unwrap_or(false)
    })
}

#[derive(Debug, uniffi::Error, thiserror::Error)]
pub enum JsonVcInitError {
    #[error("failed to decode a W3C VCDM (v1 or v2) Credential from JSON")]
    CredentialDecoding,
    #[error("failed to encode the credential as a UTF-8 string")]
    CredentialStringEncoding,
    #[error("failed to decode JSON from bytes")]
    JsonBytesDecoding,
    #[error("failed to decode JSON from a UTF-8 string")]
    JsonStringDecoding,
}

#[derive(Debug, uniffi::Error, thiserror::Error)]
pub enum JsonVcEncodingError {
    #[error("failed to encode JSON as bytes")]
    JsonBytesEncoding,
}

/// Errors from the isolated `ecdsa-sd-2023` selective-disclosure derive seam
/// ([`JsonVc::derive_sd_vp_credential`]).
#[derive(Debug, thiserror::Error)]
pub enum JsonVcDeriveError {
    #[error("credential does not carry an ecdsa-sd-2023 base proof")]
    NotSdBaseProof,
    #[error("failed to decode base-proof credential JSON for selective disclosure")]
    Decode,
    #[error("selective-disclosure derivation failed: {0}")]
    Select(String),
    #[error("failed to encode the derived selective-disclosure credential")]
    Encode,
}

#[derive(uniffi::Object, Debug, Clone)]
/// A verifiable credential secured as JSON.
pub struct JsonVc {
    id: Uuid,
    pub(crate) raw: Json,
    credential_string: String,
    parsed: AnyJsonCredential,
    key_alias: Option<KeyAlias>,
}

#[uniffi::export(async_runtime = "tokio")]
impl JsonVc {
    #[uniffi::constructor]
    /// Construct a new credential from UTF-8 encoded JSON.
    pub fn new_from_json(utf8_json_string: String) -> Result<Arc<Self>, JsonVcInitError> {
        let id = Uuid::new_v4();
        let json = serde_json::from_str(&utf8_json_string)
            .map_err(|_| JsonVcInitError::JsonStringDecoding)?;
        Self::from_json(id, json, None)
    }

    #[uniffi::constructor]
    /// Construct a new credential from UTF-8 encoded JSON.
    pub fn new_from_json_with_key(
        utf8_json_string: String,
        key_alias: KeyAlias,
    ) -> Result<Arc<Self>, JsonVcInitError> {
        let id = Uuid::new_v4();
        Self::from_json_with_id_and_key(id, utf8_json_string, key_alias)
    }

    #[uniffi::constructor]
    /// Construct a new credential from UTF-8 encoded JSON.
    pub fn from_json_with_id_and_key(
        id: Uuid,
        utf8_json_string: String,
        key_alias: KeyAlias,
    ) -> Result<Arc<Self>, JsonVcInitError> {
        let json = serde_json::from_str(&utf8_json_string)
            .map_err(|_| JsonVcInitError::JsonStringDecoding)?;
        Self::from_json(id, json, Some(key_alias))
    }

    /// The keypair identified in the credential for use in a verifiable presentation.
    pub fn key_alias(&self) -> Option<KeyAlias> {
        self.key_alias.clone()
    }

    /// The local ID of this credential.
    pub fn id(&self) -> Uuid {
        self.id
    }

    /// The version of the Verifiable Credential Data Model that this credential conforms to.
    pub fn vcdm_version(&self) -> VcdmVersion {
        match &self.parsed {
            ssi::claims::vc::AnySpecializedJsonCredential::V1(_) => VcdmVersion::V1,
            ssi::claims::vc::AnySpecializedJsonCredential::V2(_) => VcdmVersion::V2,
        }
    }

    /// Access the W3C VCDM credential as a JSON encoded UTF-8 string.
    pub fn credential_as_json_encoded_utf8_string(&self) -> String {
        self.credential_string.clone()
    }

    /// The type of this credential. Note that if there is more than one type (i.e. `types()`
    /// returns more than one value), then the types will be concatenated with a "+".
    pub fn r#type(&self) -> CredentialType {
        CredentialType(self.types().join("+"))
    }

    /// The types of the credential from the VCDM, excluding the base `VerifiableCredential` type.
    pub fn types(&self) -> Vec<String> {
        match &self.parsed {
            ssi::claims::vc::AnySpecializedJsonCredential::V1(vc) => vc.additional_types().to_vec(),
            ssi::claims::vc::AnySpecializedJsonCredential::V2(vc) => vc.additional_types().to_vec(),
        }
    }

    /// Returns the status of the credential, resolving the value in the status list,
    /// along with the purpose of the status.
    pub async fn status(&self) -> Result<Status, StatusListError> {
        self.status_list_value().await
    }
}

impl JsonVc {
    pub(crate) fn to_json_bytes(&self) -> Result<Vec<u8>, JsonVcEncodingError> {
        serde_json::to_vec(&self.raw).map_err(|_| JsonVcEncodingError::JsonBytesEncoding)
    }

    fn from_json_bytes(
        id: Uuid,
        raw: Vec<u8>,
        key_alias: Option<KeyAlias>,
    ) -> Result<Arc<Self>, JsonVcInitError> {
        let json = serde_json::from_slice(&raw).map_err(|_| JsonVcInitError::JsonBytesDecoding)?;
        Self::from_json(id, json, key_alias)
    }

    fn from_json(
        id: Uuid,
        json: Json,
        key_alias: Option<KeyAlias>,
    ) -> Result<Arc<Self>, JsonVcInitError> {
        let raw = json;

        let parsed =
            serde_json::from_value(raw.clone()).map_err(|_| JsonVcInitError::CredentialDecoding)?;

        let credential_string = serde_json::to_string(&parsed)
            .map_err(|_| JsonVcInitError::CredentialStringEncoding)?;

        Ok(Arc::new(Self {
            id,
            raw,
            credential_string,
            parsed,
            key_alias,
        }))
    }

    pub fn format() -> CredentialFormat {
        CredentialFormat::LdpVc
    }

    /// Derive an `ecdsa-sd-2023` selective-disclosure credential from this
    /// credential's base proof, revealing only `selective_pointers` plus the
    /// issuer-mandated `mandatoryPointers`.
    pub(crate) async fn derive_sd_vp_credential(
        &self,
        selective_pointers: Vec<ssi::JsonPointerBuf>,
    ) -> Result<Json, JsonVcDeriveError> {
        match &self.parsed {
            AnyJsonCredential::V1(_) => self.select_sd_proof(selective_pointers).await,
            AnyJsonCredential::V2(_) => self.select_sd_proof(selective_pointers).await,
        }
    }

    /// Shared SD derive core for both VCDM arms: deserialize the
    /// single-base-proof VC into `AnyDataIntegrity` and call `select`.
    async fn select_sd_proof(
        &self,
        selective_pointers: Vec<ssi::JsonPointerBuf>,
    ) -> Result<Json, JsonVcDeriveError> {
        use ssi::claims::data_integrity::{AnyDataIntegrity, AnySelectionOptions};
        use ssi::claims::VerificationParameters;
        use ssi::dids::{AnyDidMethod, DIDResolver};

        if !is_sd_base_proof(&self.raw) {
            return Err(JsonVcDeriveError::NotSdBaseProof);
        }

        let input: AnyDataIntegrity =
            serde_json::from_value(self.raw.clone()).map_err(|_| JsonVcDeriveError::Decode)?;

        let params =
            VerificationParameters::from_resolver(AnyDidMethod::default().into_vm_resolver());

        let mut options = AnySelectionOptions::default();
        options.selective_pointers = selective_pointers;

        let derived = input
            .select(params, options)
            .await
            .map_err(|e| JsonVcDeriveError::Select(format!("{e:?}")))?;

        serde_json::to_value(&derived).map_err(|_| JsonVcDeriveError::Encode)
    }
}

impl CredentialPresentation for JsonVc {
    type Credential = Json;
    type CredentialFormat = ClaimFormatDesignation;
    type PresentationFormat = ClaimFormatDesignation;

    fn credential(&self) -> &Self::Credential {
        &self.raw
    }

    fn presentation_format(&self) -> Self::PresentationFormat {
        // Per OID4VP v1.0 Section B.1.3.2.1:
        // "The Credential Format Identifier is `ldp_vc` to request a W3C Verifiable
        // Credential... or a Verifiable Presentation of such a Credential."
        ClaimFormatDesignation::LdpVc
    }

    fn credential_format(&self) -> Self::CredentialFormat {
        ClaimFormatDesignation::LdpVc
    }

    /// Return the credential as a VpToken
    async fn as_vp_token_item<'a>(
        &self,
        options: &'a PresentationOptions<'a>,
        _selected_fields: Option<Vec<String>>,
    ) -> Result<VpTokenItem, OID4VPError> {
        let id = UriBuf::new(format!("urn:uuid:{}", Uuid::new_v4()).as_bytes().to_vec())
            .map_err(|e| CredentialEncodingError::VpToken(format!("Error parsing ID: {e:?}")))?;

        // Check the signer supports the requested vp format crypto suite.
        options.supports_security_method(ClaimFormatDesignation::LdpVc)?;

        let unsigned_presentation = match self.parsed.clone() {
            AnyJsonCredential::V1(cred_v1) => {
                let holder_id: UriBuf = options.signer.did().parse().map_err(|e| {
                    CredentialEncodingError::VpToken(format!("Error parsing DID: {e:?}"))
                })?;

                let unsigned_presentation_v1 =
                    JsonPresentationV1::new(Some(id.clone()), Some(holder_id), vec![cred_v1]);

                AnyJsonPresentation::V1(unsigned_presentation_v1)
            }
            AnyJsonCredential::V2(cred_v2) => {
                // Convert inner type of `Object` -> `NonEmptyObject`.
                let mut cred_v2 = try_map_subjects(cred_v2, NonEmptyObject::try_from_object)
                    .map_err(|e| OID4VPError::EmptyCredentialSubject(format!("{e:?}")))?;

                // TODO: Handle transformation of the selective disclosure.
                // SKIP: Remove SD proof from the credential before adding it to the presentation.
                if let Some(p) = cred_v2
                    .extra_properties
                    .get_mut("proof")
                    .and_then(|p| p.as_array_mut())
                {
                    *p = p
                        .iter_mut()
                        .flat_map(|p| p.as_object())
                        .filter(|obj| {
                            while let Some(cryptosuite) = obj.get("cryptosuite").next() {
                                if let Some(suite) = cryptosuite.as_string() {
                                    // Check if the cryptosuite is supported.
                                    // NOTE: we're filtering proofs for only supported
                                    // cryptosuites, e.g., `ecdsa-rdfc-2019`
                                    return ACCEPTED_CRYPTOSUITES.contains(&suite);
                                }
                            }
                            true
                        })
                        .map(|p| p.clone().into())
                        .collect::<Vec<_>>();
                }

                let holder_id = IdOr::Id(options.signer.did().parse().map_err(|e| {
                    CredentialEncodingError::VpToken(format!("Error parsing DID: {e:?}"))
                })?);

                let unsigned_presentation_v2 =
                    JsonPresentationV2::new(Some(id), vec![holder_id], vec![cred_v2]);

                AnyJsonPresentation::V2(unsigned_presentation_v2)
            }
        };

        let signed_presentation = options.sign_presentation(unsigned_presentation).await?;

        Ok(VpTokenItem::from(signed_presentation))
    }
}

impl BitStringStatusListResolver for JsonVc {
    fn status_list_entry(&self) -> Result<BitstringStatusListEntry, StatusListError> {
        let value = match &self.parsed {
            AnyJsonCredential::V1(credential) => credential
                .credential_status
                .first()
                .map(serde_json::to_value),
            AnyJsonCredential::V2(credential) => credential
                .credential_status
                .first()
                .map(serde_json::to_value),
        }
        .ok_or(StatusListError::Resolution(
            "Credential status not found in credential".into(),
        ))?
        .map_err(|e| StatusListError::Resolution(format!("{e:?}")))?;

        let entry = serde_json::from_value(value).map_err(|e| {
            StatusListError::Resolution(format!("Failed to parse credential status: {e:?}"))
        })?;

        Ok(entry)
    }

    // NOTE: The remaining methods are default implemented in the trait.
}

impl TryFrom<Credential> for Arc<JsonVc> {
    type Error = JsonVcInitError;

    fn try_from(credential: Credential) -> Result<Self, Self::Error> {
        JsonVc::from_json_bytes(credential.id, credential.payload, credential.key_alias)
    }
}

// NOTE: This is an temporary solution to convert an inner type of a credential,
// i.e. `Object` -> `NonEmptyObject`.
//
// This should be removed once fixed in ssi crate.
fn try_map_subjects<T, U, E: std::fmt::Debug>(
    cred: JsonCredentialV2<T>,
    f: impl FnMut(T) -> Result<U, E>,
) -> Result<JsonCredentialV2<U>, OID4VPError> {
    Ok(JsonCredentialV2 {
        name: cred.name,
        description: cred.description,
        context: cred.context,
        id: cred.id,
        types: cred.types,
        credential_subjects: NonEmptyVec::try_from_vec(
            cred.credential_subjects
                .into_iter()
                .map(f)
                .collect::<Result<_, _>>()
                .map_err(|e| OID4VPError::EmptyCredentialSubject(format!("{e:?}")))?,
        )
        .map_err(|e| OID4VPError::EmptyCredentialSubject(format!("{e:?}")))?,
        issuer: cred.issuer,
        valid_from: cred.valid_from,
        valid_until: cred.valid_until,
        credential_status: cred.credential_status,
        terms_of_use: cred.terms_of_use,
        evidence: cred.evidence,
        credential_schema: cred.credential_schema,
        refresh_services: cred.refresh_services,
        extra_properties: cred.extra_properties,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    use serde_json::json;
    use ssi::claims::data_integrity::{
        AnyDataIntegrity, AnySignatureOptions, AnySuite, DataIntegrityDocument, ProofConfiguration,
    };
    use ssi::claims::{SignatureEnvironment, VerificationParameters};
    use ssi::dids::{AnyDidMethod, DIDKey, DIDResolver};
    use ssi::prelude::CryptographicSuite;
    use ssi::verification_methods::SingleSecretSigner;
    use ssi::JWK;

    async fn issue_sd_base_proof(unsecured: Json, mandatory_pointers: &[&str]) -> Json {
        let issuer_jwk = JWK::generate_p256();
        let vm = DIDKey::generate_url(&issuer_jwk).expect("did:key Multikey VM");
        let vm_str = vm.to_string();

        let configuration: ProofConfiguration<AnySuite> = serde_json::from_value(json!({
            "type": "DataIntegrityProof",
            "cryptosuite": "ecdsa-sd-2023",
            "created": "2024-01-01T00:00:00Z",
            "verificationMethod": vm_str,
            "proofPurpose": "assertionMethod"
        }))
        .expect("valid ecdsa-sd-2023 proof configuration");

        let (suite, options) = configuration.into_suite_and_options();

        let input: DataIntegrityDocument =
            serde_json::from_value(unsecured).expect("unsecured DI document");

        let mut sig_options = AnySignatureOptions::default();
        sig_options.mandatory_pointers = mandatory_pointers
            .iter()
            .map(|p| p.parse().expect("valid mandatory JSON pointer"))
            .collect();

        let signed: AnyDataIntegrity = suite
            .sign_with(
                SignatureEnvironment::default(),
                input,
                AnyDidMethod::default().into_vm_resolver(),
                SingleSecretSigner::new(issuer_jwk).into_local(),
                options.cast(),
                sig_options,
            )
            .await
            .expect("ecdsa-sd-2023 base-proof issuance must succeed");

        serde_json::to_value(&signed).expect("serialize signed base-proof VC")
    }

    async fn verify_di(value: &Json) -> bool {
        let parsed: AnyDataIntegrity =
            serde_json::from_value(value.clone()).expect("derived VC parses as AnyDataIntegrity");
        let params =
            VerificationParameters::from_resolver(AnyDidMethod::default().into_vm_resolver());
        parsed.verify(params).await.expect("verify ran").is_ok()
    }

    fn ptrs(raw: &[&str]) -> Vec<ssi::JsonPointerBuf> {
        raw.iter()
            .map(|p| p.parse().expect("valid selective JSON pointer"))
            .collect()
    }

    #[tokio::test]
    async fn sd_derive_v1() {
        let unsecured = json!({
            "@context": [
                "https://www.w3.org/2018/credentials/v1",
                "https://w3id.org/security/data-integrity/v2",
                { "@vocab": "https://example.org/vocab#" }
            ],
            "type": ["VerifiableCredential"],
            "issuer": "https://issuer.example/",
            "issuanceDate": "2024-01-01T00:00:00Z",
            "credentialSubject": {
                "givenName": "Jane",
                "familyName": "Doe"
            }
        });

        let base_proof = issue_sd_base_proof(unsecured, &["/issuer"]).await;
        let vc = JsonVc::from_json(Uuid::new_v4(), base_proof, None).expect("v1 base-proof JsonVc");
        assert_eq!(
            vc.vcdm_version(),
            VcdmVersion::V1,
            "fixture must be VCDM v1"
        );

        let derived = vc
            .derive_sd_vp_credential(ptrs(&["/credentialSubject/givenName"]))
            .await
            .expect("v1 SD derive must succeed (Open Question 2 retired)");

        let subject = &derived["credentialSubject"];
        assert_eq!(
            subject["givenName"],
            json!("Jane"),
            "requested field revealed"
        );
        assert!(
            subject.get("familyName").is_none(),
            "familyName must NOT be disclosed (D-04 non-oversharing), got: {subject:?}"
        );
        assert_eq!(
            derived["issuer"],
            json!("https://issuer.example/"),
            "mandatory /issuer survives"
        );

        assert!(
            verify_di(&derived).await,
            "derived v1 VC must verify against the issuer base proof"
        );
    }

    #[tokio::test]
    async fn sd_derive_v2() {
        let unsecured = json!({
            "@context": [
                "https://www.w3.org/ns/credentials/v2",
                { "@vocab": "https://example.org/vocab#" }
            ],
            "type": ["VerifiableCredential"],
            "issuer": "https://issuer.example/",
            "credentialSubject": {
                "givenName": "Jane",
                "familyName": "Doe",
                "favoriteColor": "blue",
                "boards": ["alpha", "beta"]
            }
        });

        let base_proof = issue_sd_base_proof(unsecured, &["/issuer"]).await;
        let vc = JsonVc::from_json(Uuid::new_v4(), base_proof, None).expect("v2 base-proof JsonVc");
        assert_eq!(
            vc.vcdm_version(),
            VcdmVersion::V2,
            "fixture must be VCDM v2"
        );

        let derived = vc
            .derive_sd_vp_credential(ptrs(&[
                "/credentialSubject/givenName",
                "/credentialSubject/boards",
            ]))
            .await
            .expect("v2 SD derive must succeed");

        let subject = &derived["credentialSubject"];
        assert_eq!(
            subject["givenName"],
            json!("Jane"),
            "requested givenName revealed"
        );
        assert_eq!(
            subject["boards"],
            json!(["alpha", "beta"]),
            "array field revealed via parent pointer (A4)"
        );
        assert!(
            subject.get("familyName").is_none(),
            "familyName must NOT be disclosed (D-04), got: {subject:?}"
        );
        assert!(
            subject.get("favoriteColor").is_none(),
            "favoriteColor must NOT be disclosed (D-04), got: {subject:?}"
        );
        assert_eq!(
            derived["issuer"],
            json!("https://issuer.example/"),
            "mandatory /issuer survives"
        );

        assert!(
            verify_di(&derived).await,
            "derived v2 VC must verify against the issuer base proof"
        );

        let mut tampered = derived.clone();
        tampered["credentialSubject"]["givenName"] = json!("Mallory");
        assert!(
            !verify_di(&tampered).await,
            "tampering a revealed field must fail verification (criterion #3a)"
        );
    }
}
