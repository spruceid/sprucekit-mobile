use std::{collections::HashMap, str::FromStr, sync::Arc};

use ssi::{
    claims::{data_integrity::AnyProtocol, MessageSignatureError, SignatureEnvironment},
    crypto::{Algorithm, AlgorithmInstance},
    dids::{AnyDidMethod, VerificationMethodDIDResolver},
    json_ld::{syntax::ContextEntry, ContextLoader, IriBuf, IriRefBuf},
    prelude::{AnyJsonPresentation, AnySuite, CryptographicSuite, DataIntegrity, ProofOptions},
    verification_methods::{protocol::WithProtocol, MessageSigner, ProofPurpose},
    xsd::DateTimeStamp,
    JWK,
};

use crate::crypto::CryptoCurveUtils;
use crate::oid4vp::presentation::{PresentationError, PresentationSigner};

use super::exchange::{CryptosuiteEntry, Vpr};

/// The `ecdsa-rdfc-2019` cryptosuite name — the only VP-proof suite currently
/// wired in. The `ecdsa-sd-2023` SD suite is satisfied at the CREDENTIAL layer
/// (derive), never as the VP proof.
const ECDSA_RDFC_2019: &str = "ecdsa-rdfc-2019";

/// The `ecdsa-sd-2023` selective-disclosure cryptosuite name. Mirrors
/// `json_vc::DERIVABLE_SD_CRYPTOSUITES`.
const ECDSA_SD_2023: &str = "ecdsa-sd-2023";

/// Render a [`CryptosuiteEntry`] to its suite name.
fn entry_name(entry: &CryptosuiteEntry) -> &str {
    match entry {
        CryptosuiteEntry::Name(name) => name.as_str(),
        CryptosuiteEntry::Object { cryptosuite } => cryptosuite.as_str(),
    }
}

/// §3.4.3.1 "cryptography suites among which the holder MUST choose": when
/// `acceptedCryptosuites` lists exist (at ANY placement) and NONE names a suite
/// this holder can produce — `ecdsa-rdfc-2019` for the VP proof, or
/// `ecdsa-sd-2023` satisfied at the credential layer via SD derive — nothing the
/// holder signs can be acceptable. Returns the union of listed names so the
/// caller can fail with a precise [`VcalmError::NoAcceptedCryptosuite`]
/// (refusing BEFORE user data is signed and transmitted), instead of the old
/// warn-and-send-a-doomed-proof behavior.
pub(crate) fn unsupported_cryptosuite_negotiation(vpr: &Vpr) -> Option<String> {
    let mut listed: Vec<String> = Vec::new();
    let mut collect = |entries: &Option<Vec<CryptosuiteEntry>>| {
        if let Some(entries) = entries {
            for e in entries {
                let name = entry_name(e).to_string();
                if !listed.contains(&name) {
                    listed.push(name);
                }
            }
        }
    };
    collect(&vpr.accepted_cryptosuites);
    for query in &vpr.query {
        collect(&query.accepted_cryptosuites);
        for cq in &query.credential_query {
            collect(&cq.accepted_cryptosuites);
        }
    }
    if listed.is_empty()
        || listed
            .iter()
            .any(|n| n == ECDSA_RDFC_2019 || n == ECDSA_SD_2023)
    {
        None
    } else {
        Some(listed.join(", "))
    }
}

/// GATE 1: true iff the VPR's `acceptedCryptosuites` lists the
/// `ecdsa-sd-2023` selective-disclosure suite. Reads the same `CryptosuiteEntry`
/// Name/Object forms as [`VpSigner::select_suite`].
///
/// This is the SD-satisfiable awareness that flips the fallthrough for
/// the two-gate case ONLY: the VP-proof suite still stays `ecdsa-rdfc-2019`
/// (two-layer model — SD lives on the VC, replay binding on the VP), so
/// [`VpSigner::select_suite`] and [`VpSigner::sign_presentation`] are UNCHANGED. The
/// holder consults this predicate to decide WHICH credential (full vs SD-derived) to
/// wrap, not which VP-proof suite to sign with.
pub(crate) fn vpr_lists_sd_suite(vpr: &Vpr) -> bool {
    fn entries_list_sd(entries: &[CryptosuiteEntry]) -> bool {
        entries.iter().any(|entry| {
            let name = match entry {
                CryptosuiteEntry::Name(name) => name.as_str(),
                CryptosuiteEntry::Object { cryptosuite } => cryptosuite.as_str(),
            };
            name == ECDSA_SD_2023
        })
    }

    // `acceptedCryptosuites` may sit at the VPR top level, at the QUERY level
    // (§3.4.3.1 — the spec's Examples 6/7 placement), OR inside each
    // `credentialQuery`.
    // Any location listing `ecdsa-sd-2023` arms GATE 1 — otherwise the SD leg
    // would silently fall back to full disclosure.
    if vpr
        .accepted_cryptosuites
        .as_ref()
        .is_some_and(|e| entries_list_sd(e))
    {
        return true;
    }
    vpr.query.iter().any(|q| {
        q.accepted_cryptosuites
            .as_ref()
            .is_some_and(|e| entries_list_sd(e))
            || q.credential_query.iter().any(|cq| {
                cq.accepted_cryptosuites
                    .as_ref()
                    .is_some_and(|e| entries_list_sd(e))
            })
    })
}

/// VCALM-local options/glue for building and signing a verifiable presentation.
///
/// Holds the signer callback and context map MINUS
/// `request`/`response_options`/`keystore` (there is no `AuthorizationRequestObject`
/// — challenge/domain come from the VPR). Implements the same two `ssi` traits
/// (`MessageSigner` + `Signer`) over the `PresentationSigner` callback so the private
/// key never crosses into VCALM logic.
#[derive(Clone)]
pub struct VpSigner {
    /// Signing callback interface that signs the canonicalized proof bytes.
    pub(crate) signer: Arc<Box<dyn PresentationSigner>>,
    /// Optional context map for resolving JSON-LD contexts during canonicalization.
    pub(crate) context_map: Option<HashMap<String, String>>,
}

impl std::fmt::Debug for VpSigner {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("VpSigner")
            .field("context_map", &self.context_map)
            .finish()
    }
}

// The `ecdsa-rdfc-2019` raw-fixed-width encoding clamp must be preserved exactly —
// dropping it makes the produced proof fail verification because the `KeySigner`
// returns DER, not raw r‖s.

impl MessageSigner<WithProtocol<Algorithm, AnyProtocol>> for VpSigner {
    #[allow(async_fn_in_trait)]
    async fn sign(
        self,
        WithProtocol(alg, _protocol): WithProtocol<AlgorithmInstance, AnyProtocol>,
        message: &[u8],
    ) -> Result<Vec<u8>, MessageSignatureError> {
        if !self.signer.algorithm().is_compatible_with(alg.algorithm()) {
            return Err(MessageSignatureError::UnsupportedAlgorithm(
                self.signer.algorithm().to_string(),
            ));
        }

        let signature_bytes = self
            .signer
            .sign(message.to_vec())
            .await
            .map_err(|e| MessageSignatureError::signature_failed(format!("{e:?}")))?;

        match self.signer.cryptosuite().as_ref() {
            "ecdsa-rdfc-2019" => self
                .curve_utils()
                .map(|utils| utils.ensure_raw_fixed_width_signature_encoding(signature_bytes))
                .map_err(|e| MessageSignatureError::UnsupportedAlgorithm(format!("{e:?}")))?
                .ok_or(MessageSignatureError::UnsupportedAlgorithm(
                    "Unsupported signature encoding".into(),
                )),
            _ => Err(MessageSignatureError::UnsupportedAlgorithm(
                self.signer.cryptosuite().to_string(),
            )),
        }
    }
}

impl<M> ssi::verification_methods::Signer<M> for VpSigner
where
    M: ssi::verification_methods::VerificationMethod,
{
    type MessageSigner = Self;

    #[allow(async_fn_in_trait)]
    async fn for_method(
        &self,
        method: std::borrow::Cow<'_, M>,
    ) -> Result<Option<Self::MessageSigner>, ssi::claims::SignatureError> {
        Ok(method
            .controller()
            .filter(|ctrl| **ctrl == self.signer.did())
            .map(|_| self.clone()))
    }
}

impl VpSigner {
    /// Construct a new VP signer glue over the holder's `PresentationSigner` callback.
    pub(crate) fn new(
        signer: Arc<Box<dyn PresentationSigner>>,
        context_map: Option<HashMap<String, String>>,
    ) -> Self {
        Self {
            signer,
            context_map,
        }
    }

    /// The verification method IRI for the signing key.
    pub async fn verification_method_id(&self) -> Result<IriBuf, PresentationError> {
        self.signer
            .verification_method()
            .await
            .parse()
            .map_err(|e| PresentationError::VerificationMethod(format!("{e:?}")))
    }

    /// The signing key's holder DID (e.g. a `did:key:...`).
    pub fn did(&self) -> String {
        self.signer.did()
    }

    /// The signing key's public JWK.
    pub fn jwk(&self) -> Result<JWK, PresentationError> {
        JWK::from_str(&self.signer.jwk()).map_err(|e| PresentationError::JWK(format!("{e:?}")))
    }

    /// Return the crypto curve utils based on the signing algorithm.
    pub fn curve_utils(&self) -> Result<CryptoCurveUtils, PresentationError> {
        match self.signer.algorithm() {
            ssi::crypto::Algorithm::ES256 => Ok(CryptoCurveUtils::secp256r1()),
            alg => Err(PresentationError::CryptographicSuite(format!(
                "Unsupported curve utils for algorithm: {alg:?}"
            ))),
        }
    }

    /// Select the signing cryptosuite for the VP proof.
    ///
    /// Negotiation REFUSAL is handled before signing —
    /// [`unsupported_cryptosuite_negotiation`] errors the whole submit when
    /// `acceptedCryptosuites` lists exclude everything this holder can produce
    /// (§3.4.3.1 "holder MUST choose among"). By the time `select_suite` runs,
    /// the VPR either lists `ecdsa-rdfc-2019`, lists `ecdsa-sd-2023` (satisfied
    /// at the credential layer; the VP proof stays `ecdsa-rdfc-2019` in the
    /// two-layer model), or lists nothing — all of which select the single
    /// wired suite.
    fn select_suite(_vpr: &Vpr) -> AnySuite {
        AnySuite::EcdsaRdfc2019
    }

    /// Sign an (unsecured) `AnyJsonPresentation` into a Data Integrity VP, binding the
    /// VPR `challenge`/`domain` with `ProofPurpose::Authentication`.
    ///
    /// The cryptosuite is SELECTED from `vpr.accepted_cryptosuites`
    /// ([`Self::select_suite`]). `challenge`/`domains` come
    /// from the VPR (§3.4.3.2). Sign failures route through [`PresentationError`]
    /// (transparent `#[from]` in [`super::error::VcalmError`]).
    pub async fn sign_presentation(
        &self,
        presentation: AnyJsonPresentation,
        vpr: &Vpr,
    ) -> Result<DataIntegrity<AnyJsonPresentation, AnySuite>, PresentationError> {
        let resolver = VerificationMethodDIDResolver::new(AnyDidMethod::default());

        let mut proof_options = ProofOptions::new(
            DateTimeStamp::now_ms().into(),
            self.verification_method_id().await?.into(),
            ProofPurpose::Authentication,
            Default::default(),
        );

        // Replay protection (§3.4.3.2): bind the VPR-supplied nonce and domain.
        // If `domain` is None, leave `domains` empty — do NOT fabricate one (the
        // host-side "domain == channel" check is the transport's job).
        proof_options.challenge = vpr.challenge.clone();
        proof_options.domains = vpr.domain.clone().into_iter().collect();

        // V1 presentations signed with Data Integrity require the
        // `data-integrity/v2` context entry or canonicalization/verification fails.
        if let AnyJsonPresentation::V1(_) = presentation {
            let iri_buf = IriRefBuf::new("https://w3id.org/security/data-integrity/v2".into())
                .map_err(|e| PresentationError::Context(format!("{e:?}")))?;

            proof_options.context = Some(ssi::json_ld::syntax::Context::One(ContextEntry::IriRef(
                iri_buf,
            )))
        }

        let context = self
            .context_map
            .clone()
            .map(|map| ContextLoader::default().with_context_map_from(map))
            .transpose()
            .map_err(|e| PresentationError::Context(format!("{e:?}")))?
            .unwrap_or_default();

        let env = SignatureEnvironment {
            json_ld_loader: context,
            eip712_loader: (),
        };

        // select ecdsa-rdfc-2019 (listed, or default). SD suites are not yet derived.
        let suite = Self::select_suite(vpr);

        suite
            .sign_with(
                &env,
                presentation,
                resolver,
                self,
                proof_options,
                Default::default(),
            )
            .await
            .map_err(|e| PresentationError::Signing(format!("{e:?}")))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    use serde_json::json;
    use ssi::claims::vc::v1::JsonPresentation as JsonPresentationV1;
    use ssi::claims::vc::v2::syntax::JsonPresentation as JsonPresentationV2;
    use ssi::claims::vc::{
        syntax::{IdOr, NonEmptyObject},
        v2::syntax::JsonCredential as JsonCredentialV2,
    };
    use ssi::dids::{AnyDidMethod, DIDResolver};
    use ssi::json_ld::iref::UriBuf;
    use ssi::prelude::{AnyJsonCredential, VerificationParameters};
    use uuid::Uuid;

    use crate::vcalm::exchange::Vpr;

    /// Build the VCALM signer glue over the shared P-256 `KeySigner` test double
    /// (`did:key`, `ecdsa-rdfc-2019`).
    fn signer_glue() -> VpSigner {
        let signer: Box<dyn PresentationSigner> = Box::new(crate::tests::load_signer());
        VpSigner::new(
            Arc::new(signer),
            Some(crate::context::default_ld_json_context()),
        )
    }

    /// A minimal VCDM v2 credential JSON for VP assembly. The inline `@context`
    /// object defines `givenName` so JSON-LD expansion succeeds without a remote fetch.
    fn v2_credential_json(holder_did: &str) -> serde_json::Value {
        json!({
            "@context": [
                "https://www.w3.org/ns/credentials/v2",
                { "givenName": "https://schema.org/givenName" }
            ],
            "type": ["VerifiableCredential"],
            "issuer": "https://issuer.example/",
            "credentialSubject": { "id": holder_did, "givenName": "Jane" }
        })
    }

    /// A minimal VCDM v1 credential JSON for VP assembly. The inline `@context`
    /// object defines `givenName` so JSON-LD expansion succeeds without a remote fetch.
    fn v1_credential_json(holder_did: &str) -> serde_json::Value {
        json!({
            "@context": [
                "https://www.w3.org/2018/credentials/v1",
                { "givenName": "https://schema.org/givenName" }
            ],
            "type": ["VerifiableCredential"],
            "issuer": "https://issuer.example/",
            "credentialSubject": { "id": holder_did, "givenName": "Jane" }
        })
    }

    /// Assemble an `AnyJsonPresentation` from the (optional) credential JSON values.
    /// An empty slice yields a DIDAuth-only presentation (empty `verifiableCredential`).
    fn assemble_presentation(holder_did: &str, creds: &[serde_json::Value]) -> AnyJsonPresentation {
        let id: UriBuf = format!("urn:uuid:{}", Uuid::new_v4()).parse().unwrap();
        let holder: UriBuf = holder_did.parse().unwrap();

        // Decide V1/V2 from the first credential's `@context`; default to V1 for the
        // empty (DIDAuth-only) case.
        let is_v2 = creds
            .first()
            .and_then(|c| c.get("@context"))
            .and_then(|c| c.as_array())
            .map(|ctx| {
                ctx.iter()
                    .any(|e| e.as_str() == Some("https://www.w3.org/ns/credentials/v2"))
            })
            .unwrap_or(false);

        if is_v2 {
            let mut vcs: Vec<JsonCredentialV2<NonEmptyObject>> = Vec::new();
            for c in creds {
                let parsed: JsonCredentialV2<NonEmptyObject> =
                    serde_json::from_value(c.clone()).unwrap();
                vcs.push(parsed);
            }
            let holder_id = IdOr::Id(holder);
            AnyJsonPresentation::V2(JsonPresentationV2::new(Some(id), vec![holder_id], vcs))
        } else {
            let mut vcs = Vec::new();
            for c in creds {
                let parsed: AnyJsonCredential = serde_json::from_value(c.clone()).unwrap();
                if let AnyJsonCredential::V1(v1) = parsed {
                    vcs.push(v1);
                }
            }
            AnyJsonPresentation::V1(JsonPresentationV1::new(Some(id), Some(holder), vcs))
        }
    }

    async fn verify(signed: &DataIntegrity<AnyJsonPresentation, AnySuite>) -> bool {
        let vm_resolver = AnyDidMethod::default().into_vm_resolver();
        let params = VerificationParameters::from_resolver(vm_resolver);
        signed.verify(&params).await.expect("verify ran").is_ok()
    }

    fn vpr_with_suites(suites: Option<Vec<CryptosuiteEntry>>) -> Vpr {
        Vpr {
            challenge: Some("nonce-123".into()),
            domain: Some("https://verifier.example".into()),
            accepted_cryptosuites: suites,
            ..Default::default()
        }
    }

    #[tokio::test]
    async fn vp_proof_verifies() {
        let glue = signer_glue();
        let did = glue.did();
        let pres = assemble_presentation(&did, &[v2_credential_json(&did)]);
        let vpr = vpr_with_suites(None);

        let signed = glue
            .sign_presentation(pres, &vpr)
            .await
            .expect("sign must succeed");
        assert!(verify(&signed).await, "VP proof must verify");
    }

    #[tokio::test]
    async fn vp_binds_challenge_domain() {
        let glue = signer_glue();
        let did = glue.did();
        let pres = assemble_presentation(&did, &[v2_credential_json(&did)]);
        let vpr = vpr_with_suites(None);

        let signed = glue
            .sign_presentation(pres, &vpr)
            .await
            .expect("sign must succeed");

        let value = serde_json::to_value(&signed).unwrap();
        let proof = &value["proof"];
        assert_eq!(proof["challenge"], json!("nonce-123"));
        // On-wire DI key is `domain` (singular) — confirmed empirically.
        assert_eq!(proof["domain"], json!("https://verifier.example"));
        assert_eq!(proof["proofPurpose"], json!("authentication"));
    }

    #[tokio::test]
    async fn selects_ecdsa_rdfc_2019_when_listed() {
        let glue = signer_glue();
        let did = glue.did();
        let pres = assemble_presentation(&did, &[v2_credential_json(&did)]);
        let vpr = vpr_with_suites(Some(vec![CryptosuiteEntry::Name("ecdsa-rdfc-2019".into())]));

        let signed = glue
            .sign_presentation(pres, &vpr)
            .await
            .expect("sign must succeed");
        let value = serde_json::to_value(&signed).unwrap();
        assert_eq!(value["proof"]["cryptosuite"], json!("ecdsa-rdfc-2019"));
        assert!(verify(&signed).await);
    }

    #[tokio::test]
    async fn defaults_ecdsa_rdfc_2019_when_absent() {
        let glue = signer_glue();
        let did = glue.did();
        let pres = assemble_presentation(&did, &[v2_credential_json(&did)]);
        let vpr = vpr_with_suites(None);

        let signed = glue
            .sign_presentation(pres, &vpr)
            .await
            .expect("sign must succeed");
        let value = serde_json::to_value(&signed).unwrap();
        assert_eq!(value["proof"]["cryptosuite"], json!("ecdsa-rdfc-2019"));
        assert!(verify(&signed).await);
    }

    #[tokio::test]
    async fn sd_suites_do_not_select_or_error() {
        let glue = signer_glue();
        let did = glue.did();
        let pres = assemble_presentation(&did, &[v2_credential_json(&did)]);
        // Only SD suites listed — must NOT select an SD suite, must NOT error;
        // falls through to the ecdsa-rdfc-2019 default.
        let vpr = vpr_with_suites(Some(vec![
            CryptosuiteEntry::Name("ecdsa-sd-2023".into()),
            CryptosuiteEntry::Object {
                cryptosuite: "bbs-2023".into(),
            },
        ]));

        let signed = glue
            .sign_presentation(pres, &vpr)
            .await
            .expect("SD-only VPR must still sign with ecdsa-rdfc-2019, not error");
        let value = serde_json::to_value(&signed).unwrap();
        assert_eq!(value["proof"]["cryptosuite"], json!("ecdsa-rdfc-2019"));
        assert!(verify(&signed).await);
    }

    #[test]
    fn query_level_accepted_cryptosuites_are_read() {
        use crate::vcalm::exchange::Query;

        // §3.4.3.1 places `acceptedCryptosuites` at the QUERY level (spec
        // Examples 6/7). GATE 1 (SD) must see it there…
        let mut vpr = vpr_with_suites(None);
        vpr.query = vec![Query {
            r#type: vec!["DIDAuthentication".into()],
            accepted_cryptosuites: Some(vec![CryptosuiteEntry::Name("ecdsa-sd-2023".into())]),
            ..Default::default()
        }];
        assert!(
            vpr_lists_sd_suite(&vpr),
            "query-level acceptedCryptosuites must arm GATE 1"
        );

        // …and suite selection must register the placement: a query-level list
        // naming ecdsa-rdfc-2019 is an explicit (non-warning) selection.
        let mut vpr = vpr_with_suites(None);
        vpr.query = vec![Query {
            r#type: vec!["DIDAuthentication".into()],
            accepted_cryptosuites: Some(vec![CryptosuiteEntry::Object {
                cryptosuite: "ecdsa-rdfc-2019".into(),
            }]),
            ..Default::default()
        }];
        assert_eq!(VpSigner::select_suite(&vpr), AnySuite::EcdsaRdfc2019);
    }

    #[tokio::test]
    async fn didauth_only_no_credentials() {
        let glue = signer_glue();
        let did = glue.did();
        // Empty credential set ⇒ DIDAuth-only VP, empty verifiableCredential vec.
        let pres = assemble_presentation(&did, &[]);
        let vpr = vpr_with_suites(None);

        let signed = glue
            .sign_presentation(pres, &vpr)
            .await
            .expect("DIDAuth-only sign must succeed");

        let value = serde_json::to_value(&signed).unwrap();
        // No verifiableCredential, or an empty array.
        let vc = &value["verifiableCredential"];
        assert!(
            vc.is_null() || vc.as_array().map(|a| a.is_empty()).unwrap_or(false),
            "DIDAuth-only VP must carry no verifiableCredential, got {vc:?}"
        );
        // Holder DID is present.
        assert_eq!(value["holder"], json!(did));
        assert!(verify(&signed).await, "DIDAuth-only proof must verify");
    }

    #[tokio::test]
    async fn v1_presentation_signs() {
        let glue = signer_glue();
        let did = glue.did();
        let pres = assemble_presentation(&did, &[v1_credential_json(&did)]);
        match &pres {
            AnyJsonPresentation::V1(_) => {}
            _ => panic!("expected a V1 presentation"),
        }
        let vpr = vpr_with_suites(None);

        let signed = glue
            .sign_presentation(pres, &vpr)
            .await
            .expect("V1 sign must succeed");
        assert!(verify(&signed).await, "V1 VP proof must verify");
    }
}
