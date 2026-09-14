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

use std::{collections::HashMap, sync::Arc};

use base64::{engine::general_purpose::URL_SAFE, Engine as _};
use openid4vp::core::{
    credential_format::ClaimFormatDesignation, response::parameters::VpTokenItem,
};
use serde_json::Value as Json;
use ssi::status::bitstring_status_list::BitstringStatusListEntry;
use ssi::{
    claims::{
        data_integrity::{AnyDataIntegrity, AnySelectionOptions},
        vc::{
            syntax::{IdOr, NonEmptyObject, NonEmptyVec},
            v1::{Credential as _, JsonPresentation as JsonPresentationV1},
            v2::{
                syntax::JsonPresentation as JsonPresentationV2, Credential as _,
                JsonCredential as JsonCredentialV2,
            },
        },
        VerificationParameters,
    },
    dids::{AnyDidMethod, VerificationMethodDIDResolver},
    json_ld::{iref::UriBuf, ContextLoader},
    prelude::{AnyJsonCredential, AnyJsonPresentation},
    JsonPointerBuf,
};
use uuid::Uuid;

const ACCEPTED_CRYPTOSUITES: &[&str] = &["ecdsa-rdfc-2019"];

/// Cryptosuite whose base proof selective presentations are derived from.
const SD_CRYPTOSUITE: &str = "ecdsa-sd-2023";

/// Multibase prefix of an `ecdsa-sd-2023` base proof value (CBOR tag 0xd95d00).
/// Derived proofs carry 0xd95d01 (`u2V0B`) and cannot be derived from again.
const SD_BASE_PROOF_PREFIX: &str = "u2V0A";

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

    /// Whether presentations of this credential can selectively disclose claims.
    ///
    /// Requires an `ecdsa-sd-2023` base proof to derive from. Restricted to
    /// VCDM 2.0 credentials, matching the presentation path that implements
    /// derivation.
    pub(crate) fn selective_disclosable(&self) -> bool {
        self.vcdm_version() == VcdmVersion::V2 && self.has_sd_base_proof()
    }

    /// Whether deriving an `ecdsa-sd-2023` presentation is both possible for
    /// this credential and acceptable to the verifier behind `options`.
    ///
    /// OID4VP 1.0 §6.4 says a wallet MUST NOT send selectively disclosable
    /// claims that were not selected, which favors deriving whenever the
    /// verifier named claims. But Appendix B.1.3.2.3 also requires the
    /// presented credential's cryptosuite to match the verifier's
    /// `vp_formats_supported` when it lists one. A verifier that only
    /// announced `ecdsa-rdfc-2019` therefore gets the full credential under
    /// that proof, exactly what it received before derivation existed here.
    ///
    /// - <https://openid.net/specs/openid-4-verifiable-presentations-1_0.html#section-6.4>
    /// - <https://openid.net/specs/openid-4-verifiable-presentations-1_0.html#appendix-B.1.3.2.3>
    fn should_derive(&self, options: &PresentationOptions<'_>) -> Result<bool, OID4VPError> {
        if !self.selective_disclosable() {
            return Ok(false);
        }

        let accepted = options
            .verifier_accepts_credential_suite(&ClaimFormatDesignation::LdpVc, SD_CRYPTOSUITE)?;
        if !accepted {
            log::info!(
                "verifier's vp_formats_supported does not list `{SD_CRYPTOSUITE}` for ldp_vc; \
                 presenting the full credential instead of deriving"
            );
        }

        Ok(accepted)
    }

    /// Whether the credential's proof set carries an `ecdsa-sd-2023` base proof.
    fn has_sd_base_proof(&self) -> bool {
        let proofs = match self.raw.get("proof") {
            Some(Json::Array(proofs)) => proofs.as_slice(),
            Some(proof) => std::slice::from_ref(proof),
            None => return false,
        };

        proofs.iter().any(|proof| {
            proof.get("cryptosuite").and_then(Json::as_str) == Some(SD_CRYPTOSUITE)
                && proof
                    .get("proofValue")
                    .and_then(Json::as_str)
                    .is_some_and(|value| value.starts_with(SD_BASE_PROOF_PREFIX))
        })
    }

    /// Derive a selectively disclosed credential from the `ecdsa-sd-2023` base
    /// proof, revealing exactly the selected fields plus whatever the base
    /// proof itself marks mandatory. The wallet volunteers nothing else:
    /// metadata such as `issuer` and the validity window reach the verifier
    /// through the issuer's mandatory pointers, or because the verifier
    /// requested them.
    ///
    /// `selected_fields` uses the [`RequestedField`] path encoding: base64url
    /// DCQL path segments joined by commas.
    ///
    /// [`RequestedField`]: crate::oid4vp::RequestedField
    async fn derive_selective(
        &self,
        context_map: Option<&HashMap<String, String>>,
        selected_fields: &[String],
    ) -> Result<Json, OID4VPError> {
        let mut pointers = selected_fields
            .iter()
            .map(|field| self.selective_pointer(field))
            .collect::<Result<Vec<_>, _>>()?;
        pointers.sort();
        pointers.dedup();

        let stored = self.raw.clone();
        let context_map = context_map.cloned();

        // ssi's derivation expands and canonicalizes the document, recursing
        // deep enough to overflow the foreign thread's stack that UniFFI polls
        // this future on (~512 KB on iOS). Hop onto the dedicated 8 MB worker
        // (see `crate::big_stack`).
        let derived = crate::big_stack::run_async(move || async move {
            let stored: AnyDataIntegrity = serde_json::from_value(stored).map_err(|e| {
                OID4VPError::VpTokenCreate(format!("stored credential is not derivable: {e}"))
            })?;

            let loader = context_map
                .map(|map| ContextLoader::default().with_context_map_from(map))
                .transpose()
                .map_err(|e| OID4VPError::VpTokenCreate(format!("invalid context map: {e}")))?
                .unwrap_or_default();

            // Derivation is offline: the base proof carries the derivation
            // material, so the resolver only satisfies the parameter type.
            let params = VerificationParameters::from_resolver(VerificationMethodDIDResolver::new(
                AnyDidMethod::default(),
            ))
            .with_json_ld_loader(loader);

            let mut options = AnySelectionOptions::default();
            options.selective_pointers = pointers;

            let derived = stored.select(params, options).await.map_err(|e| {
                OID4VPError::VpTokenCreate(format!("selective disclosure derivation failed: {e}"))
            })?;

            serde_json::to_value(&derived).map_err(|e| {
                OID4VPError::VpTokenCreate(format!("derived credential encoding failed: {e}"))
            })
        })
        .await
        .map_err(|e| OID4VPError::VpTokenCreate(format!("big-stack derivation thread: {e}")))?;

        derived
    }

    /// Resolve one requested-field path against the credential and return it
    /// as a JSON pointer, per RFC 6901.
    fn selective_pointer(&self, field: &str) -> Result<JsonPointerBuf, OID4VPError> {
        let mut node = &self.raw;
        let mut pointer = String::new();

        for encoded in field.split(',') {
            let segment = URL_SAFE
                .decode(encoded)
                .map_err(|e| OID4VPError::JsonPathParse(e.to_string()))
                .and_then(|bytes| {
                    String::from_utf8(bytes).map_err(|e| OID4VPError::JsonPathParse(e.to_string()))
                })?;

            node = match node {
                Json::Object(object) => object.get(&segment),
                Json::Array(items) => segment
                    .parse::<usize>()
                    .ok()
                    .and_then(|index| items.get(index)),
                _ => None,
            }
            .ok_or_else(|| {
                OID4VPError::JsonPathResolve(format!("no credential member at {pointer}/{segment}"))
            })?;

            pointer.push('/');
            pointer.push_str(&segment.replace('~', "~0").replace('/', "~1"));
        }

        JsonPointerBuf::new(pointer).map_err(|e| OID4VPError::JsonPathToPointer(e.to_string()))
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
    ///
    /// When fields are selected, the credential carries an `ecdsa-sd-2023`
    /// base proof, and the verifier accepts that cryptosuite (see
    /// `should_derive`), the presentation embeds a derived credential
    /// disclosing only those fields. Otherwise the full credential is
    /// embedded, keeping the proofs verifiers accept.
    async fn as_vp_token_item<'a>(
        &self,
        options: &'a PresentationOptions<'a>,
        selected_fields: Option<Vec<String>>,
    ) -> Result<VpTokenItem, OID4VPError> {
        let id = UriBuf::new(format!("urn:uuid:{}", Uuid::new_v4()).as_bytes().to_vec())
            .map_err(|e| CredentialEncodingError::VpToken(format!("Error parsing ID: {e:?}")))?;

        // Check the signer supports the requested vp format crypto suite.
        options.supports_security_method(ClaimFormatDesignation::LdpVc)?;

        let unsigned_presentation = match self.parsed.clone() {
            AnyJsonCredential::V1(cred_v1) => {
                let holder_id: UriBuf = options.subject().parse().map_err(|e| {
                    CredentialEncodingError::VpToken(format!("Error parsing DID: {e:?}"))
                })?;

                let unsigned_presentation_v1 =
                    JsonPresentationV1::new(Some(id.clone()), Some(holder_id), vec![cred_v1]);

                AnyJsonPresentation::V1(unsigned_presentation_v1)
            }
            AnyJsonCredential::V2(cred_v2) => {
                let holder_id = IdOr::Id(options.subject().parse().map_err(|e| {
                    CredentialEncodingError::VpToken(format!("Error parsing DID: {e:?}"))
                })?);

                // A dual-proof credential has two conformant `ldp_vc`
                // presentations, and the wallet picks the proof. When the
                // verifier lists claims and accepts `ecdsa-sd-2023`, we
                // present under the SD proof and disclose only what was
                // selected, as OID4VP 1.0 §6.4 requires. When the verifier
                // lists no claims, we present under the `ecdsa-rdfc-2019`
                // proof instead: secured that way the credential is not
                // selectively disclosable, every claim is mandatory to
                // present, and §6.4.1 reads an absent `claims` as a request
                // for the full credential. Presenting the SD proof here would
                // shrink the disclosure to the issuer's mandatory pointers,
                // which is not what a verifier asking for the credential wants.
                // https://openid.net/specs/openid-4-verifiable-presentations-1_0.html#section-6.4.1
                let selected = match selected_fields {
                    Some(fields) if !fields.is_empty() && self.should_derive(options)? => {
                        Some(fields)
                    }
                    _ => None,
                };

                if let Some(fields) = selected {
                    // The derived credential discloses only the selected
                    // fields, so it may lack members the typed credential
                    // model requires: embed it untyped.
                    let derived = self
                        .derive_selective(options.context_map.as_ref(), &fields)
                        .await?;

                    let presentation =
                        JsonPresentationV2::new(Some(id), vec![holder_id], vec![derived]);
                    let signed = options.sign_derived_presentation(presentation).await?;

                    let signed = serde_json::to_value(&signed).map_err(|e| {
                        CredentialEncodingError::VpToken(format!(
                            "Error encoding presentation: {e:?}"
                        ))
                    })?;
                    let Json::Object(object) = signed else {
                        return Err(CredentialEncodingError::VpToken(
                            "signed presentation is not a JSON object".to_string(),
                        )
                        .into());
                    };
                    return Ok(VpTokenItem::JsonObject(object));
                }

                // Convert inner type of `Object` -> `NonEmptyObject`.
                let mut cred_v2 = try_map_subjects(cred_v2, NonEmptyObject::try_from_object)
                    .map_err(|e| OID4VPError::EmptyCredentialSubject(format!("{e:?}")))?;

                // Full disclosure keeps only the proofs verifiers accept:
                // an `ecdsa-sd-2023` base proof is derivation material,
                // never presented.
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
                                    return ACCEPTED_CRYPTOSUITES.contains(&suite);
                                }
                            }
                            true
                        })
                        .map(|p| p.clone().into())
                        .collect::<Vec<_>>();
                }

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

// NOTE: This is a temporary solution to convert an inner type of a credential,
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
    use crate::context::default_ld_json_context;

    fn dual_proof_badge() -> Arc<JsonVc> {
        JsonVc::new_from_json(
            include_str!("../../../tests/examples/open_badge_dual_proof_vc.json").to_string(),
        )
        .unwrap()
    }

    fn encode_path(segments: &[&str]) -> String {
        segments
            .iter()
            .map(|segment| URL_SAFE.encode(segment))
            .collect::<Vec<_>>()
            .join(",")
    }

    #[test]
    fn detects_selective_disclosure_support() {
        assert!(dual_proof_badge().selective_disclosable());

        // A lone full-document proof leaves nothing to derive from.
        let rdfc_only = JsonVc::new_from_json(
            include_str!("../../../tests/examples/open_badge_rdfc_only_vc.json").to_string(),
        )
        .unwrap();
        assert!(!rdfc_only.selective_disclosable());

        // Carries a base proof, but is VCDM 1.1: derivation is only
        // implemented for 2.0 presentations.
        let vcdm_v1 = JsonVc::new_from_json(
            include_str!("../../../tests/examples/alumni_vc.json").to_string(),
        )
        .unwrap();
        assert!(!vcdm_v1.selective_disclosable());
    }

    #[test]
    fn resolves_requested_field_paths_to_pointers() {
        let badge = dual_proof_badge();

        let pointer = badge
            .selective_pointer(&encode_path(&["credentialSubject", "achievement", "name"]))
            .unwrap();
        assert_eq!(pointer.as_str(), "/credentialSubject/achievement/name");

        badge
            .selective_pointer(&encode_path(&["credentialSubject", "missing"]))
            .expect_err("a path absent from the credential must not resolve");
    }

    #[tokio::test]
    async fn derives_a_selective_credential() {
        let badge = dual_proof_badge();
        let fields = vec![encode_path(&["credentialSubject", "achievement", "name"])];

        let value = badge
            .derive_selective(Some(&default_ld_json_context()), &fields)
            .await
            .unwrap();

        // This fixture's base proof was issued with the mandatory pointers
        // /issuer, /validFrom and /credentialSubject/id: those members
        // survive because the proof mandates them, not because the wallet
        // volunteers them. (The subject id would survive regardless, since
        // ancestors of a selected path keep their `id` and `type`.)
        assert_eq!(value["issuer"]["id"], badge.raw["issuer"]["id"]);
        assert!(value["validFrom"].is_string());
        assert_eq!(
            value["credentialSubject"]["id"],
            badge.raw["credentialSubject"]["id"]
        );

        // The selected field is revealed, undisclosed members are not.
        assert_eq!(
            value["credentialSubject"]["achievement"]["name"],
            badge.raw["credentialSubject"]["achievement"]["name"]
        );
        assert!(value["credentialSubject"]["achievement"]
            .get("description")
            .is_none());
        assert!(value["credentialSubject"].get("name").is_none());

        // A single derived proof replaces the stored proof set.
        assert_eq!(value["proof"]["cryptosuite"], "ecdsa-sd-2023");
        assert!(value["proof"]["proofValue"]
            .as_str()
            .unwrap()
            .starts_with("u2V0B"));
    }
}
