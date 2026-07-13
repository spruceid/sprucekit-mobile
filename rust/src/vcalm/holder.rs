use std::collections::HashMap;
use std::sync::Arc;
use std::time::Duration;

use futures::StreamExt;
use reqwest::header::{ACCEPT, AUTHORIZATION};
use ssi::claims::vc::{
    syntax::{IdOr, NonEmptyObject},
    v1::JsonPresentation as JsonPresentationV1,
    v2::syntax::{JsonCredential as JsonCredentialV2, JsonPresentation as JsonPresentationV2},
};
use ssi::json_ld::iref::UriBuf;
use ssi::prelude::{AnyJsonCredential, AnyJsonPresentation};
use url::Url;
use uuid::Uuid;

use crate::credential::json_vc::{JsonVc, SD_BASE_PROOF_CRYPTOSUITES};
use crate::credential::{verify_raw_credential, Credential, InvalidCredential, ParsedCredential};
use crate::crypto::KeyStore;
use crate::oid4vp::presentation::{PresentationError, PresentationSigner};
use crate::vdc_collection::VdcCollection;

use super::error::VcalmError;
use super::exchange::{classify, AcceptedMethodEntry, StepResult, VcapiMessage, Vpr};
use super::issuance::{self, OfferedEntry};
use super::matching::{self, QueryKind};
use super::presentation::{unsupported_cryptosuite_negotiation, vpr_lists_sd_suite, VpSigner};

/// Cap on a discovery/exchange response body (B.4: large payloads can trigger
/// DoS incidents — a malicious or broken server must not be able to exhaust the
/// wallet's memory). Generous for any plausible VPR/VP payload.
const MAX_RESPONSE_BYTES: usize = 10 * 1024 * 1024;

/// Interior, mutable session state retained across calls on a shared `Arc<VcalmHolder>`.
/// Guarded by a `tokio::sync::Mutex` on the [`VcalmHolder`].
#[derive(Debug, Default)]
pub(crate) struct ExchangeState {
    /// The resolved `vcapi` exchange URL every loop request POSTs to.
    pub(crate) exchange_url: Option<Url>,
    /// The most recent verifiable-presentation-request the server asked for.
    pub(crate) last_vpr: Option<Vpr>,
    /// The server-issued correlation id, echoed on the next request.
    pub(crate) reference_id: Option<String>,
    /// Optional bearer token attached to every exchange POST (never discovery).
    pub(crate) auth_header: Option<String>,
    /// The offered VP's `vcs` (the `verifiablePresentation` envelope) captured when
    /// the last step was a [`StepResult::Offer`]. Cleared after a successful
    /// accept/reject advance so a stale Offer is never re-accepted. Kept across a
    /// FAILED advance so the caller can retry (verify+store is idempotent).
    pub(crate) current_offer: Option<serde_json::Value>,
    /// The follow-on request the Offer carried, kept distinct from
    /// [`last_vpr`](Self::last_vpr) so `accept_offer` can decide whether to return it
    /// directly or POST an advance without re-reading state after a POST. Cleared
    /// alongside `current_offer`.
    pub(crate) current_offer_next_vpr: Option<Vpr>,
    /// A `redirectUrl` that rode along on the Offer message (§3.6 combined
    /// properties). Surfaced as the terminal step after a successful accept,
    /// instead of an extra advance POST. Cleared alongside `current_offer`.
    pub(crate) current_offer_redirect: Option<Url>,
}

/// A stateful VCALM holder session driving one `vcapi` exchange.
#[derive(uniffi::Object)]
pub struct VcalmHolder {
    /// An atomic reference to the VDC collection (enumerated for QBE matching).
    pub(crate) vdc_collection: Option<Arc<VdcCollection>>,
    /// A list of trusted DIDs (forward-looking).
    pub(crate) trusted_dids: Vec<String>,
    /// Foreign interface for the [`PresentationSigner`] used to sign the VP.
    pub(crate) signer: Arc<Box<dyn PresentationSigner>>,
    /// Optional context map for resolving JSON-LD contexts during signing.
    pub(crate) context_map: Option<HashMap<String, String>>,
    /// Optional KeyStore for credential signing (forward-looking).
    pub(crate) keystore: Option<Arc<dyn KeyStore>>,
    /// Injectable plain reqwest client (rustls) — targetable by wiremock.
    pub(crate) client: reqwest::Client,
    /// VCALM session state — interior-mutable because UniFFI methods take `&self`.
    pub(crate) state: tokio::sync::Mutex<ExchangeState>,
    /// Host-provided credentials (the host app's wallet packs) to match against
    /// for presentation. When set (via [`Self::provide_credentials`]), QBE
    /// matching enumerates THESE instead of the holder's own `vdc_collection`, so
    /// credentials the host app already stores (e.g. OID4VCI-issued) are
    /// presentable via VCALM. Issuance (`accept_offer`) still stores into
    /// `vdc_collection`.
    pub(crate) provided_credentials: tokio::sync::Mutex<Option<Vec<Arc<ParsedCredential>>>>,
}

/// The discovery document returned for an `interaction:` initiation (§3.7.4).
#[derive(serde::Deserialize)]
struct DiscoveryResponse {
    protocols: HashMap<String, String>,
}

#[uniffi::export(async_runtime = "tokio")]
impl VcalmHolder {
    /// Construct a holder session. Adds the default, empty [`ExchangeState`].
    ///
    /// NOTE: named `new_session`, NOT `new`. uniffi maps an async constructor
    /// literally named `new` onto the Kotlin *primary* constructor — which can't
    /// be `suspend` — so it generates neither a usable constructor nor a
    /// companion factory (the binding emits "no constructor generated for this
    /// object as it is async"), making the object unconstructable from Kotlin.
    /// A non-`new` name becomes a companion `suspend fun newSession(...)` (and a
    /// Swift static `VcalmHolder.newSession(...)`), which both adapters call.
    #[uniffi::constructor]
    pub async fn new_session(
        vdc_collection: Arc<VdcCollection>,
        trusted_dids: Vec<String>,
        signer: Box<dyn PresentationSigner>,
        context_map: Option<HashMap<String, String>>,
        keystore: Option<Arc<dyn KeyStore>>,
    ) -> Result<Arc<Self>, VcalmError> {
        let client = reqwest::Client::builder()
            .use_rustls_tls()
            .timeout(Duration::from_secs(30))
            .build()
            .map_err(|e| VcalmError::Network(e.to_string()))?;

        // Default to the SDK's bundled JSON-LD contexts (W3C + the full set:
        // alumni, first-responder, citizenship, render-method,
        // movie-ticket, …) when the caller supplies none — mirroring the Showcase
        // app. This lets offered-VC verification (accept) and VP canonicalization
        // (present) resolve `@context` URLs OFFLINE, instead of the caller having
        // to hand-supply each credential type's context (and avoids a remote fetch
        // in the verify path). An explicit non-empty map still overrides.
        let context_map = Some(match context_map {
            Some(map) if !map.is_empty() => map,
            _ => crate::context::default_ld_json_context(),
        });

        Ok(Arc::new(Self {
            vdc_collection: Some(vdc_collection),
            trusted_dids,
            signer: Arc::new(signer),
            context_map,
            keystore,
            client,
            state: tokio::sync::Mutex::new(ExchangeState::default()),
            provided_credentials: tokio::sync::Mutex::new(None),
        }))
    }

    /// Seed the QBE matcher with credentials loaded from the host app's wallet
    /// packs (mirrors OID4VP's `createHolder(packIds)` pre-seed). The native
    /// adapter resolves the host app's pack ids to `ParsedCredential` handles and
    /// calls this so wallet credentials become matchable for PRESENTATION. Without
    /// it, matching falls back to the holder's own `vdc_collection`
    /// (issuance-received credentials only).
    pub async fn provide_credentials(&self, credentials: Vec<Arc<ParsedCredential>>) {
        tracing::info!(
            "VCALM provide_credentials: seeding matcher with {} host credential(s)",
            credentials.len()
        );
        *self.provided_credentials.lock().await = Some(credentials);
    }

    /// Begin a `vcapi` exchange.
    ///
    /// `interaction:<url>` inputs and bare `http(s)` URLs carrying `?iuv=1`
    /// (§3.7.1 — the interaction QR format) trigger a discovery GET that extracts
    /// the `vcapi` exchange URL; any other `http(s)` URL is treated as the exchange
    /// URL directly (no discovery). An optional bearer token
    /// is stored for the loop's exchange POSTs — it is NEVER sent on
    /// the discovery GET (initiation needs no auth, §3.6.5 L3262). Begins by POSTing an
    /// empty `{}` message and returns the first [`StepResult`].
    pub async fn start_exchange(
        self: Arc<Self>,
        input: String,
        auth_header: Option<String>,
    ) -> Result<StepResult, VcalmError> {
        // §3.7.3 scheme matching is case-insensitive (URL schemes are).
        let interaction_rest = input
            .get(.."interaction:".len())
            .filter(|prefix| prefix.eq_ignore_ascii_case("interaction:"))
            .map(|_| &input["interaction:".len()..]);

        let exchange_url = if let Some(discovery_url) = interaction_rest {
            self.discover_vcapi(discovery_url).await?
        } else {
            let url = Url::parse(&input)
                .map_err(|e| VcalmError::Network(format!("invalid exchange URL: {e}")))?;
            // §3.7.1/§3.7.2: a spec-conformant interaction QR encodes a BARE http(s)
            // URL carrying `?iuv=<version>` ("MUST be 1 when using this version of
            // this API"). Route those through discovery — POSTing `{}` straight at a
            // discovery endpoint never starts the exchange. URLs without `iuv` keep
            // the existing direct-exchange-URL behavior.
            match url.query_pairs().find(|(k, _)| k == "iuv") {
                Some((_, v)) if v == "1" => self.discover_vcapi(url.as_str()).await?,
                Some((_, v)) => {
                    return Err(VcalmError::Network(format!(
                        "unsupported interaction URL version: iuv={v} (expected 1)"
                    )))
                }
                None => {
                    // §3.7.1/B.2: HTTPS-only (loopback http allowed for local dev).
                    validate_endpoint_url(&url)?;
                    url
                }
            }
        };

        // Reset the WHOLE session state: a previous exchange's referenceId,
        // last VPR, or pending Offer must never bleed into a new exchange
        // (possibly against a different server).
        {
            let mut state = self.state.lock().await;
            *state = ExchangeState {
                exchange_url: Some(exchange_url),
                auth_header,
                ..ExchangeState::default()
            };
        }

        self.post_message(VcapiMessage::default()).await
    }

    /// Return, per current-VPR QueryByExample query, the stored credentials that
    /// match that query. The result is keyed by a per-query index so the
    /// caller can select which credential(s) to present.
    ///
    /// Enumerates the [`VdcCollection`], keeps only full-disclosure W3C JSON-LD VCs
    /// (`LdpVc`/`JsonVc`), and runs [`matching::example_matches`] (type/@context/
    /// recursive credentialSubject subset + issuer filter) against each. A no-match
    /// query yields an empty match list — NEVER an error; a VPR
    /// with no QueryByExample queries yields an empty result.
    pub async fn matched_credentials(&self) -> Result<Vec<VcalmMatchedCredentials>, VcalmError> {
        let vpr = match &self.state.lock().await.last_vpr {
            Some(vpr) => vpr.clone(),
            None => return Ok(vec![]),
        };

        let all_credentials = self.enumerate_credentials().await?;

        // A. Privacy: wallet CONTENTS (stored types/@contexts) are sensitive —
        // logged at trace only; counts at debug.
        tracing::debug!(
            "VCALM matched_credentials: {} credential(s) enumerated from store",
            all_credentials.len()
        );
        for (i, cred) in all_credentials.iter().enumerate() {
            match cred.as_json_vc() {
                Some(jv) => tracing::trace!(
                    "VCALM store[{i}] type={} @context={}",
                    jv.raw
                        .get("type")
                        .map(|v| v.to_string())
                        .unwrap_or_default(),
                    jv.raw
                        .get("@context")
                        .map(|v| v.to_string())
                        .unwrap_or_default()
                ),
                None => tracing::trace!("VCALM store[{i}] not a JSON-LD VC (skipped by matcher)"),
            }
        }

        // Whether presenting under THIS VPR would SD-derive (per credential below).
        let sd_requested = vpr_lists_sd_suite(&vpr);

        let mut matched: Vec<VcalmMatchedCredentials> = Vec::new();
        for (query_index, query) in vpr.query.iter().enumerate() {
            // Only QueryByExample queries select credentials. DIDAuthentication and
            // unknown types contribute no credential.
            if matching::query_kind(query) != QueryKind::QueryByExample {
                continue;
            }
            if query.credential_query.is_empty() {
                // A QueryByExample query with no credentialQuery matches nothing.
                matched.push(VcalmMatchedCredentials {
                    query_index: query_index as u32,
                    credentials: vec![],
                });
                continue;
            }

            for cq in &query.credential_query {
                tracing::trace!(
                    "VCALM query[{query_index}] example type={} @context={}",
                    cq.example
                        .as_ref()
                        .and_then(|e| e.get("type"))
                        .map(|v| v.to_string())
                        .unwrap_or_default(),
                    cq.example
                        .as_ref()
                        .and_then(|e| e.get("@context"))
                        .map(|v| v.to_string())
                        .unwrap_or_default()
                );
            }

            let mut hits: Vec<VcalmMatchedCredential> = Vec::new();
            for cred in &all_credentials {
                if let Some(json_vc) = cred.as_json_vc() {
                    // OR across the query's credentialQuery alternatives (e.g. a VCDM
                    // v2 and a v1 context variant of the same credential type).
                    let matched_any = query
                        .credential_query
                        .iter()
                        .any(|cq| matching::example_matches(cq, &json_vc.raw));
                    tracing::debug!(
                        "VCALM query[{query_index}] vs stored VC -> match={matched_any}"
                    );
                    if matched_any {
                        hits.push(VcalmMatchedCredential {
                            credential: cred.clone(),
                            selective_disclosure: sd_requested && has_sd_base_proof(&json_vc.raw),
                        });
                    }
                }
            }
            matched.push(VcalmMatchedCredentials {
                query_index: query_index as u32,
                credentials: hits,
            });
        }

        Ok(matched)
    }

    /// Report, per current-VPR QueryByExample query, the fields NAMED by that query's
    /// `example`. Informational only: `ecdsa-rdfc-2019` reveals the entire
    /// credential, so this surfaces what will be shared for user display — it does
    /// NOT limit fields. `""` example leaf values render as `"any value"`.
    ///
    /// An empty VPR (or a VPR with no QueryByExample queries) yields an empty result;
    /// never an error.
    pub async fn requested_fields(&self) -> Result<Vec<VcalmRequestedField>, VcalmError> {
        let vpr = match &self.state.lock().await.last_vpr {
            Some(vpr) => vpr.clone(),
            None => return Ok(vec![]),
        };

        let mut fields: Vec<VcalmRequestedField> = Vec::new();
        for (query_index, query) in vpr.query.iter().enumerate() {
            if matching::query_kind(query) != QueryKind::QueryByExample {
                continue;
            }
            // Surface the example-named fields across ALL credentialQuery
            // alternatives, deduped by path within the query (context variants
            // name the same fields). Top-level `type`/`@context` and each
            // `credentialSubject` key (recursively). `""` ⇒ "any value".
            let mut seen_paths = std::collections::HashSet::new();
            for cq in &query.credential_query {
                let Some(example) = cq.example.as_ref() else {
                    continue;
                };
                let purpose = cq.reason.clone();
                for path in example_field_paths(example) {
                    if !seen_paths.insert(path.path.clone()) {
                        continue;
                    }
                    fields.push(VcalmRequestedField {
                        query_index: query_index as u32,
                        path: path.path,
                        value: path.value,
                        required: query.required.unwrap_or(true),
                        purpose: purpose.clone(),
                    });
                }
            }
        }

        Ok(fields)
    }

    /// Continue the exchange by building and signing a real W3C Verifiable
    /// Presentation from the holder-selected credentials, then POSTing it through the
    /// existing `post_message` loop.
    ///
    /// `selected_credentials` are the credentials the holder/user chose (e.g. from
    /// [`Self::matched_credentials`]). The VP is signed with `ecdsa-rdfc-2019`
    /// and binds the VPR `challenge`/`domain` (§3.4.3.2) with
    /// `ProofPurpose::Authentication`. A DIDAuthentication-only request (no
    /// selected credentials) yields a signed VP with an empty
    /// `verifiableCredential` array.
    ///
    /// §3.4.3.2 anti-replay: when the VPR `domain` does not match the exchange
    /// channel host, this REFUSES with [`VcalmError::DomainChannelMismatch`]
    /// BEFORE anything is signed. `allow_domain_mismatch` is the explicit
    /// host-app override for deployments that legitimately split the verifier
    /// origin from the workflow-service channel — the host owns that consent,
    /// and must pass `true` deliberately (never as a default).
    pub async fn submit_presentation(
        self: Arc<Self>,
        selected_credentials: Vec<Arc<ParsedCredential>>,
        allow_domain_mismatch: bool,
    ) -> Result<StepResult, VcalmError> {
        let (vpr, exchange_url) = {
            let state = self.state.lock().await;
            (state.last_vpr.clone(), state.exchange_url.clone())
        };
        let vpr = vpr.ok_or_else(|| {
            VcalmError::SessionState("no verifiable-presentation-request in session".into())
        })?;

        if let Some((domain, channel)) =
            domain_channel_mismatch(vpr.domain.as_deref(), exchange_url.as_ref())
        {
            if !allow_domain_mismatch {
                return Err(VcalmError::DomainChannelMismatch { domain, channel });
            }
            log::warn!(
                "VPR domain ({domain}) does not match the exchange channel host \
                 ({channel}) — §3.4.3.2 anti-replay check failed; proceeding because \
                 the caller explicitly allowed the mismatch"
            );
        }

        // ssi's JSON-LD canonicalization recurses deep enough to overflow the
        // foreign thread's stack (UniFFI polls this future on the calling
        // Kotlin/Swift thread — ~1 MB on Android coroutine workers, ~512 KB on
        // iOS child threads). Hop onto the dedicated 8 MB worker for the
        // build+sign, exactly like `w3c_vc_barcodes` (see `crate::big_stack`).
        let holder = Arc::clone(&self);
        let signed = crate::big_stack::run_async(move || async move {
            holder.build_and_sign_vp(&vpr, &selected_credentials).await
        })
        .await
        .map_err(|e| VcalmError::Network(format!("big-stack signing thread: {e}")))??;

        let mut value = serde_json::to_value(&signed)
            .map_err(|e| VcalmError::Deserialization(e.to_string()))?;

        // vcapi §3.6.5: `verifiableCredential` MUST be an array. ssi compacts a
        // single credential to a bare object; re-wrap it. Signature-safe: the VP
        // proof canonicalizes to RDF (ecdsa-rdfc-2019), and an object vs a
        // one-element array yield identical N-Quads.
        if let Some(vc) = value.get_mut("verifiableCredential") {
            if !vc.is_array() {
                let single = vc.take();
                *vc = serde_json::Value::Array(vec![single]);
            }
        }

        let message = VcapiMessage {
            verifiable_presentation: Some(value),
            ..Default::default()
        };
        self.post_message(message).await
    }

    /// Accept the credentials offered in the current Offer: verify EVERY offered VC's
    /// own issuer proof, store them all, then advance the exchange.
    ///
    /// Policy (atomic): verification runs over all entries FIRST. If any VC
    /// fails cryptographic proof verification, `accept_offer` returns
    /// [`VcalmError::InvalidCredentialProof`] (naming the entry index)
    /// immediately, stores NOTHING, and does NOT advance. A
    /// cryptographically-valid but time-bounded VC (expired/premature
    /// claims) is still stored and surfaced distinctly (a `tracing::warn!`
    /// keyed by the stable id; also reflected in the [`offered_credentials`] preview).
    /// An `ecdsa-sd-2023` BASE-proof VC — the very thing an SD-capable wallet is
    /// issued — is validated by deriving a full-reveal credential and verifying
    /// THAT (base proofs are derivation material; they cannot be verified
    /// directly), then the ORIGINAL base-proof VC is stored so later
    /// presentations can SD-derive from it. A `bbs-2023` base proof (recognized,
    /// not yet derivable) is refused with a typed
    /// [`VcalmError::UnsupportedCredentialFormat`].
    /// Storage uses the deterministic [`issuance::stable_local_id`] so re-accepting the
    /// same credential OVERWRITES rather than duplicating (idempotent). When the
    /// Offer carried a follow-on request, accept returns
    /// [`StepResult::Request`] WITHOUT a second POST; when it carried a combined
    /// `redirectUrl`, accept returns [`StepResult::Redirect`]; otherwise it POSTs
    /// the empty advance message.
    ///
    /// The Offer state is cleared only after a SUCCESSFUL advance — on an
    /// advance failure the Offer survives so the caller can retry
    /// (verify+store is idempotent). A server problem reply on the advance is
    /// surfaced truthfully as [`StepResult::Problem`] (§3.8) — the credential is
    /// already stored either way.
    ///
    /// `accept_offer` verifies each VC's OWN proof only — it does NOT gate storage on
    /// `trusted_dids`, so an untrusted-issuer but cryptographically-valid VC still
    /// stores. An `EnvelopedVerifiableCredential` is recognized and routed to a
    /// typed error (forward-compat, never silent-dropped).
    pub async fn accept_offer(self: Arc<Self>) -> Result<StepResult, VcalmError> {
        let (vcs, next_vpr, offer_redirect) = {
            let state = self.state.lock().await;
            match &state.current_offer {
                Some(vcs) => (
                    vcs.clone(),
                    state.current_offer_next_vpr.clone(),
                    state.current_offer_redirect.clone(),
                ),
                None => return Err(VcalmError::SessionState("no offer in session".into())),
            }
        };

        let entries = issuance::extract_offered_vcs(&vcs)?;
        tracing::debug!(
            "VCALM accept_offer: {} offered VC(s) to verify+store",
            entries.len()
        );

        // Verify-all-first. Collect a small per-VC outcome — NEVER the
        // `Verification` objects (they are not Clone).
        let mut collected: Vec<(Uuid, OfferOutcome, Credential)> =
            Vec::with_capacity(entries.len());
        for (index, entry) in entries.iter().enumerate() {
            let outcome =
                match Self::verify_offered_entry(entry.clone(), self.context_map.clone()).await? {
                    OfferedVerifyOutcome::Valid => OfferOutcome::Valid,
                    // Expired/premature/other claims — still store, surface distinctly.
                    OfferedVerifyOutcome::TimeBounded => OfferOutcome::ValidButTimeBounded,
                    // Proof failure is a hard, atomic abort — store nothing, do not advance.
                    OfferedVerifyOutcome::ProofInvalid => {
                        return Err(VcalmError::InvalidCredentialProof {
                            index: index as u32,
                        })
                    }
                    OfferedVerifyOutcome::Machinery(e) => return Err(e.into()),
                    OfferedVerifyOutcome::Unsupported(reason) => {
                        return Err(VcalmError::UnsupportedCredentialFormat(format!(
                            "offered credential #{index}: {reason}"
                        )))
                    }
                    OfferedVerifyOutcome::DeriveFailed(reason) => {
                        return Err(VcalmError::SdDeriveFailed(format!(
                            "offered credential #{index}: base-proof validation derive \
                         failed: {reason}"
                        )))
                    }
                };

            // Build the storable Credential now, overriding the local id with the
            // deterministic stable id and clearing key_alias (received full-disclosure
            // VCs have no holder key).
            let json_vc = JsonVc::new_from_json(entry.to_string())
                .map_err(|e| VcalmError::CredentialDecoding(e.into()))?;
            let generic = ParsedCredential::new_ldp_vc(json_vc).into_generic_form()?;
            let credential = Credential {
                id: issuance::stable_local_id(entry),
                key_alias: None,
                ..generic
            };
            collected.push((credential.id, outcome, credential));
        }

        // Store-all (only reached when every entry passed the proof gate).
        let vdc = self
            .vdc_collection
            .as_ref()
            .ok_or_else(|| VcalmError::SessionState("no credential storage configured".into()))?;
        for (stable_id, outcome, credential) in &collected {
            vdc.add(credential).await?; // overwrites on duplicate id ⇒ idempotent
            tracing::debug!("VCALM accept_offer: stored credential id={stable_id}");
            if matches!(outcome, OfferOutcome::ValidButTimeBounded) {
                // Distinct, non-failing signal so the UI can warn "stored, but
                // expired/premature". Keyed by the stable id only — never the VC body.
                tracing::warn!(
                    credential_id = %stable_id,
                    "stored an offered credential whose validity period failed (expired/premature) — surfaced for UI warning"
                );
            }
        }
        // Confirm the post-store enumeration size from the SAME VdcCollection the
        // presentation step will read.
        if let Ok(entries) = vdc.all_entries().await {
            tracing::debug!(
                "VCALM accept_offer: VdcCollection now reports {} entry/entries after store",
                entries.len()
            );
        }

        // Advance. The Offer is cleared on each SUCCESSFUL terminal path; a failed
        // advance keeps it so the caller can retry.
        if let Some(vpr) = next_vpr {
            // The Offer already carried the follow-on request — return it as a Request
            // WITHOUT a second advance POST.
            let mut state = self.state.lock().await;
            state.current_offer = None;
            state.current_offer_next_vpr = None;
            state.current_offer_redirect = None;
            state.last_vpr = Some(vpr.clone());
            return Ok(StepResult::Request { vpr });
        }
        if let Some(url) = offer_redirect {
            // §3.6 combined properties: the Offer carried a redirectUrl — that IS
            // the terminal step; no extra advance POST.
            let mut state = self.state.lock().await;
            state.current_offer = None;
            state.current_offer_next_vpr = None;
            state.current_offer_redirect = None;
            return Ok(StepResult::Redirect { url });
        }
        // No follow-on request bundled in the Offer. A vcapi exchange can deliver
        // its NEXT step either bundled in the Offer (next_vpr / redirect, handled
        // above) OR only on the next empty POST (multi-step exchanges), so we
        // still advance to discover whether a next step exists.
        //
        // The credential is ALREADY verified and stored above, so the advance is
        // a discovery step that cannot undo issuance:
        //   - Ok(Request/Offer/Redirect/Complete) → surface it (a real next step,
        //     or clean completion).
        //   - 4xx (a well-formed problem reply OR a malformed body) → the server
        //     treats the issuance exchange as already complete and rejects the
        //     extra POST (e.g. with a 403); issuance succeeded → report Complete.
        //   - 5xx / network error → transient, NOT "complete": keep the Offer and
        //     surface the error so the caller can retry (verify+store is
        //     idempotent; post_message stores nothing on an error).
        //
        // The consumed Offer is cleared BEFORE the POST so a reply carrying a NEW
        // Offer (stored inside post_message) is not clobbered; on a transient Err
        // it is RESTORED.
        {
            let mut state = self.state.lock().await;
            state.current_offer = None;
            state.current_offer_next_vpr = None;
            state.current_offer_redirect = None;
        }
        match self.post_message(VcapiMessage::default()).await {
            Ok(StepResult::Problem { .. }) | Err(VcalmError::MalformedProblemDetails { .. }) => {
                tracing::debug!(
                    "VCALM accept_offer: advance POST got a 4xx on a terminal issuance \
                     Offer; the credential is stored — reporting Complete"
                );
                Ok(StepResult::Complete)
            }
            Ok(step) => Ok(step),
            Err(e) => {
                let mut state = self.state.lock().await;
                if state.current_offer.is_none() {
                    state.current_offer = Some(vcs);
                    state.current_offer_next_vpr = None;
                    state.current_offer_redirect = None;
                }
                tracing::warn!(
                    "VCALM accept_offer: advance POST failed transiently ({e}); the \
                     credential IS stored and the Offer is kept so the caller can retry"
                );
                Err(e)
            }
        }
    }

    /// Reject the current Offer: advance the exchange WITHOUT storing anything.
    ///
    /// Requires a current Offer (same guard as [`accept_offer`]). The Offer is
    /// cleared BEFORE the advance POST (so a reply carrying a NEW Offer is not
    /// clobbered) and RESTORED on a failed advance so the caller can retry. If
    /// the rejected Offer carried a follow-on request, the resulting step
    /// surfaces it like any other reply — reject does NOT special-case it and
    /// does NOT fabricate an RFC 9457 Problem.
    pub async fn reject_offer(self: Arc<Self>) -> Result<StepResult, VcalmError> {
        let consumed = {
            let mut state = self.state.lock().await;
            let Some(vcs) = state.current_offer.take() else {
                return Err(VcalmError::SessionState("no offer in session".into()));
            };
            state.current_offer_next_vpr = None;
            state.current_offer_redirect = None;
            vcs
        };
        let result = self.post_message(VcapiMessage::default()).await;
        if result.is_err() {
            // post_message stores nothing on an error — restore for retry.
            let mut state = self.state.lock().await;
            if state.current_offer.is_none() {
                state.current_offer = Some(consumed);
            }
        }
        result
    }

    /// Preview the credentials offered in the current Offer for UI display.
    ///
    /// Read-only: no storage, no advance. Returns an empty vec when there is no current
    /// Offer (or it carries no credentials). Each previewed VC carries its issuer,
    /// type(s), a JSON rendering of its `credentialSubject`, and a `validity` hint
    /// derived by verifying the VC's proof/claims (so the UI can warn before the user
    /// accepts). Verification here never stores and never errors the
    /// whole preview: a VC whose machinery fails is surfaced as `unverifiable`.
    pub async fn offered_credentials(&self) -> Result<Vec<VcalmOfferedCredential>, VcalmError> {
        let vcs = match &self.state.lock().await.current_offer {
            Some(vcs) => vcs.clone(),
            None => return Ok(vec![]),
        };

        let entries = match issuance::extract_offered_vcs(&vcs) {
            Ok(entries) => entries,
            // An empty/absent offer previews as nothing — not an error for a read.
            Err(VcalmError::NoOfferedCredentials) => return Ok(vec![]),
            Err(e) => return Err(e),
        };

        let mut out = Vec::with_capacity(entries.len());
        for entry in &entries {
            // Same verification core as accept_offer — including the big-stack
            // hop (ssi JSON-LD canonicalization on an attacker-controlled VC must
            // never run on the small foreign caller's stack) and the SD-base
            // derive-then-verify path.
            let validity = match issuance::classify_offered_entry(entry) {
                OfferedEntry::Enveloped => OfferedValidity::Enveloped,
                OfferedEntry::BareDataIntegrity => {
                    match Self::verify_offered_entry(entry.clone(), self.context_map.clone()).await
                    {
                        Ok(OfferedVerifyOutcome::Valid) => OfferedValidity::Valid,
                        Ok(OfferedVerifyOutcome::TimeBounded) => OfferedValidity::TimeBounded,
                        Ok(OfferedVerifyOutcome::ProofInvalid)
                        | Ok(OfferedVerifyOutcome::DeriveFailed(_)) => {
                            OfferedValidity::ProofInvalid
                        }
                        Ok(OfferedVerifyOutcome::Machinery(_)) => OfferedValidity::Unverifiable,
                        Ok(OfferedVerifyOutcome::Unsupported(_)) => {
                            OfferedValidity::UnsupportedProof
                        }
                        // A preview never errors the whole read.
                        Err(_) => OfferedValidity::Unverifiable,
                    }
                }
            };
            out.push(VcalmOfferedCredential::from_entry(entry, validity));
        }
        Ok(out)
    }
}

/// Per-VC verify outcome captured during the [`VcalmHolder::accept_offer`]
/// verify-all-first loop. Deliberately small and `Clone`-free of the `Verification`
/// object (which is not `Clone`).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum OfferOutcome {
    /// Proof verified and claims are within their validity period.
    Valid,
    /// Proof verified but the validity period failed (expired/premature/other claims).
    /// Still stored; surfaced distinctly.
    ValidButTimeBounded,
}

/// The rich outcome of verifying ONE offered entry — shared by
/// [`VcalmHolder::accept_offer`] (which maps refusals to typed errors) and
/// [`VcalmHolder::offered_credentials`] (which maps everything to a non-failing
/// [`OfferedValidity`] preview hint).
enum OfferedVerifyOutcome {
    /// Proof verified; claims within their validity period.
    Valid,
    /// Proof verified; validity period failed (expired/premature/other claims).
    TimeBounded,
    /// The credential's own cryptographic proof failed.
    ProofInvalid,
    /// The verification machinery itself failed to run.
    Machinery(crate::credential::VerificationError),
    /// A recognized-but-unsupported format (enveloped VC, `bbs-2023` base proof).
    Unsupported(String),
    /// An `ecdsa-sd-2023` base proof whose validation derive failed.
    DeriveFailed(String),
}

impl VcalmHolder {
    /// Verify one offered entry, with the big-stack hop (ssi JSON-LD
    /// canonicalization overflows the small foreign caller's stack) and
    /// SD-base awareness:
    ///
    /// - `EnvelopedVerifiableCredential` → [`OfferedVerifyOutcome::Unsupported`].
    /// - `bbs-2023` base proof (recognized, not derivable) →
    ///   [`OfferedVerifyOutcome::Unsupported`].
    /// - `ecdsa-sd-2023` base proof → base proofs are derivation material and
    ///   cannot be verified directly (ssi rejects them by construction), so the
    ///   entry is validated by deriving a full-subject-reveal credential and
    ///   verifying THAT — a failed derive or an invalid derived proof rejects
    ///   the entry. The caller stores the ORIGINAL base-proof VC.
    /// - anything else → plain [`verify_raw_credential`].
    ///
    /// An associated fn (not `&self`) so the whole body can hop onto the
    /// dedicated big-stack worker with owned captures.
    async fn verify_offered_entry(
        entry: serde_json::Value,
        context_map: Option<HashMap<String, String>>,
    ) -> Result<OfferedVerifyOutcome, VcalmError> {
        if matches!(
            issuance::classify_offered_entry(&entry),
            OfferedEntry::Enveloped
        ) {
            return Ok(OfferedVerifyOutcome::Unsupported(
                "EnvelopedVerifiableCredential is not yet supported".into(),
            ));
        }
        if has_underivable_sd_base_proof(&entry) {
            return Ok(OfferedVerifyOutcome::Unsupported(format!(
                "credential carries a selective-disclosure base proof this SDK cannot \
                 derive yet ({:?})",
                proof_cryptosuites(&entry)
            )));
        }

        let outcome = crate::big_stack::run_async(move || async move {
            // For an ecdsa-sd-2023 base proof, verify via a full-reveal derive.
            let to_verify = if has_sd_base_proof(&entry) {
                let json_vc = match JsonVc::new_from_json(entry.to_string()) {
                    Ok(v) => v,
                    Err(e) => {
                        return OfferedVerifyOutcome::DeriveFailed(format!(
                            "base-proof credential failed to parse: {e}"
                        ))
                    }
                };
                let full_reveal = selective_pointers_from_paths(std::slice::from_ref(
                    &"credentialSubject".to_string(),
                ));
                match json_vc
                    .derive_sd_vp_credential(full_reveal, context_map.clone())
                    .await
                {
                    Ok(derived) => derived,
                    Err(e) => return OfferedVerifyOutcome::DeriveFailed(e.to_string()),
                }
            } else {
                entry
            };

            let raw = match issuance::build_raw_credential(&to_verify) {
                Ok(raw) => raw,
                Err(_) => {
                    return OfferedVerifyOutcome::Machinery(
                        crate::credential::VerificationError::InvalidCredentialPayload,
                    )
                }
            };
            match verify_raw_credential(&raw, context_map).await {
                Ok(verification) => match verification.expect_verified() {
                    Ok(()) => OfferedVerifyOutcome::Valid,
                    Err(InvalidCredential::Claims(_)) => OfferedVerifyOutcome::TimeBounded,
                    Err(InvalidCredential::Proof) => OfferedVerifyOutcome::ProofInvalid,
                },
                Err(e) => OfferedVerifyOutcome::Machinery(e),
            }
        })
        .await
        .map_err(|e| VcalmError::Network(format!("big-stack verification thread: {e}")))?;

        Ok(outcome)
    }
    /// Enumerate every stored credential and parse it (read-only over the
    /// [`VdcCollection`]). No collection ⇒ empty vec (never an error).
    async fn enumerate_credentials(&self) -> Result<Vec<Arc<ParsedCredential>>, VcalmError> {
        if let Some(provided) = self.provided_credentials.lock().await.as_ref() {
            tracing::info!(
                "VCALM enumerate: using {} host-provided credential(s)",
                provided.len()
            );
            return Ok(provided.clone());
        }
        let all = match &self.vdc_collection {
            None => {
                tracing::debug!("VCALM enumerate: no VdcCollection configured on holder");
                vec![]
            }
            Some(vdc_collection) => {
                let ids = vdc_collection.all_entries().await?;
                // list() count is pre-decrypt/parse, so it tells a genuinely empty
                // store apart from one whose entries are dropped by a failing
                // get/decrypt/parse (enumerate swallows those).
                tracing::debug!(
                    "VCALM enumerate: {} id(s) listed in store (pre-decrypt/parse)",
                    ids.len()
                );
                futures::stream::iter(ids)
                    .filter_map(|id| async move {
                        match vdc_collection.get(id).await {
                            Ok(Some(cred)) => match cred.try_into_parsed() {
                                Ok(parsed) => Some(parsed),
                                Err(e) => {
                                    tracing::warn!("VCALM enumerate: try_into_parsed failed: {e}");
                                    None
                                }
                            },
                            Ok(None) => {
                                tracing::warn!(
                                    "VCALM enumerate: get returned None for a listed id"
                                );
                                None
                            }
                            Err(e) => {
                                tracing::warn!("VCALM enumerate: get failed: {e}");
                                None
                            }
                        }
                    })
                    .collect::<Vec<Arc<ParsedCredential>>>()
                    .await
            }
        };
        Ok(all)
    }

    /// Build and sign the VP for `vpr` from the holder-selected credentials.
    ///
    /// Refuses up-front when the VPR's negotiation lists exclude everything this
    /// holder can produce — `acceptedCryptosuites` (§3.4.3.1,
    /// [`VcalmError::NoAcceptedCryptosuite`]) and `acceptedMethods` (§3.4.3.2,
    /// [`VcalmError::NoAcceptedDidMethod`]) — instead of signing a response the
    /// verifier must reject. Then runs §3.4.5 group resolution
    /// ([`matching::resolve_groups`]) honoring the caller's selection (the chosen
    /// credentials drive per-QBE-query satisfiability), assembles an
    /// `AnyJsonPresentation` (DIDAuth-only ⇒ empty vec), and signs it via the
    /// [`VpSigner`] glue.
    async fn build_and_sign_vp(
        &self,
        vpr: &Vpr,
        selected_credentials: &[Arc<ParsedCredential>],
    ) -> Result<ssi::prelude::DataIntegrity<AnyJsonPresentation, ssi::prelude::AnySuite>, VcalmError>
    {
        // §3.4.3.1 "holder MUST choose among" — refuse before signing when no
        // placement lists a producible suite.
        if let Some(accepted) = unsupported_cryptosuite_negotiation(vpr) {
            return Err(VcalmError::NoAcceptedCryptosuite { accepted });
        }
        // §3.4.3.2 — same for the holder DID method.
        ensure_accepted_methods(vpr)?;

        // Forward-looking deps held on the holder are not consumed by the current
        // signing path (issuer-trust scoring and the mdoc keystore are not yet wired);
        // surface their availability at trace level so the fields are read.
        log::trace!(
            "VCALM VP sign: {} trusted DID(s) configured, keystore {}",
            self.trusted_dids.len(),
            if self.keystore.is_some() {
                "present"
            } else {
                "absent"
            }
        );

        // §3.4.5 group resolution: a QBE query is satisfiable iff at least one of the
        // SELECTED credentials matches it; DIDAuthentication is satisfiable iff its
        // constraints don't exclude this holder; unknown types are never satisfiable.
        // The first fully-satisfiable AND-group is the default selection.
        let resolution = matching::resolve_groups(&vpr.query, |idx| {
            let query = &vpr.query[idx];
            let has_match = !query.credential_query.is_empty()
                && selected_credentials.iter().any(|cred| {
                    cred.as_json_vc()
                        .map(|json_vc| {
                            query
                                .credential_query
                                .iter()
                                .any(|cq| matching::example_matches(cq, &json_vc.raw))
                        })
                        .unwrap_or(false)
                });
            matching::query_satisfiable_by_kind(query, has_match)
        });

        // Gather the credentials to present: the union of selected credentials that
        // match a QueryByExample query in the chosen AND-group. If no group is
        // satisfiable (e.g. DIDAuthentication-only), present no credentials.
        //
        // `present_paths[i]` accumulates the QBE-named field paths of exactly the
        // queries that `present[i]` MATCHED — each credential's SD derive reveals
        // only what was asked OF THAT CREDENTIAL, not the union across the whole
        // request (a multi-query VPR must not leak query-A fields from a query-B
        // credential).
        let mut present: Vec<Arc<ParsedCredential>> = Vec::new();
        let mut present_paths: Vec<Vec<String>> = Vec::new();
        if resolution.is_satisfiable() {
            // SAFETY: is_satisfiable() <=> selected.is_some().
            let group_idx = resolution.selected.expect("satisfiable ⇒ a group selected");
            let group = &resolution.groups[group_idx];
            for &query_idx in &group.members {
                let query = &vpr.query[query_idx];
                if matching::query_kind(query) != QueryKind::QueryByExample {
                    continue;
                }
                // The fields THIS query names, across all credentialQuery
                // alternatives, deduped by path.
                let mut seen_paths = std::collections::HashSet::new();
                let mut query_paths: Vec<String> = Vec::new();
                for cq in &query.credential_query {
                    let Some(example) = cq.example.as_ref() else {
                        continue;
                    };
                    for field in example_field_paths(example) {
                        if seen_paths.insert(field.path.clone()) {
                            query_paths.push(field.path);
                        }
                    }
                }
                for cred in selected_credentials {
                    if let Some(json_vc) = cred.as_json_vc() {
                        if query
                            .credential_query
                            .iter()
                            .any(|cq| matching::example_matches(cq, &json_vc.raw))
                        {
                            match present.iter().position(|c| Arc::ptr_eq(c, cred)) {
                                Some(i) => {
                                    for p in &query_paths {
                                        if !present_paths[i].contains(p) {
                                            present_paths[i].push(p.clone());
                                        }
                                    }
                                }
                                None => {
                                    present.push(cred.clone());
                                    present_paths.push(query_paths.clone());
                                }
                            }
                        }
                    }
                }
            }
        }

        let holder_did = self.signer.did();
        let glue = VpSigner::new(self.signer.clone(), self.context_map.clone());

        // Two-gate SD activation. GATE 1: the VPR lists an SD suite. GATE 2
        // (per credential): the matched VC carries an `ecdsa-sd-2023` base proof. When
        // both hold, derive the SD VC revealing exactly the fields the credential's
        // OWN matching queries named (+ issuer mandatory pointers, merged by the
        // derive), wrap the derived VC in a VP, and sign through the UNCHANGED
        // `sign_presentation` (the VP proof stays `ecdsa-rdfc-2019` and binds the
        // VPR challenge/domain — two-layer model).
        //
        // A failed derive is a hard [`VcalmError::SdDeriveFailed`] — NEVER a silent
        // fall back to full disclosure: the user consented to the SD subset, and
        // silently widening that to the whole credential is an over-share.
        // A credential with NO SD base proof under an SD-requesting VPR is still
        // presented at full disclosure (the verifier sees a non-SD credential and
        // decides) — that is a capability gap, not a consent violation.
        if vpr_lists_sd_suite(vpr) && !present.is_empty() {
            let mut presented_json: Vec<serde_json::Value> = Vec::new();
            let mut sd_derived = false;

            for (cred, paths) in present.iter().zip(&present_paths) {
                let json_vc = cred.as_json_vc().ok_or_else(|| {
                    // Unreachable: only credentials that matched via as_json_vc are
                    // collected into `present`.
                    VcalmError::SdDeriveFailed("selected credential is not a JSON-LD VC".into())
                })?;
                if has_sd_base_proof(&json_vc.raw) {
                    // GATE 2 holds — derive the selective-disclosure VC. An example
                    // naming no subject fields (type-only, or e.g. only
                    // `credentialStatus`) still reveals the whole subject via the
                    // parent pointer (a derived VC without `credentialSubject`
                    // would not be a valid VC at all).
                    let mut selective_pointers = selective_pointers_from_paths(paths);
                    let names_subject = paths
                        .iter()
                        .any(|p| p == "credentialSubject" || p.starts_with("credentialSubject."));
                    if !names_subject {
                        selective_pointers.extend(selective_pointers_from_paths(
                            std::slice::from_ref(&"credentialSubject".to_string()),
                        ));
                    }
                    match json_vc
                        .derive_sd_vp_credential(selective_pointers, self.context_map.clone())
                        .await
                    {
                        Ok(derived) => {
                            sd_derived = true;
                            presented_json.push(derived);
                        }
                        Err(e) => {
                            return Err(VcalmError::SdDeriveFailed(format!(
                                "selective-disclosure derive failed for a matched \
                                 credential: {e}"
                            )));
                        }
                    }
                } else {
                    // SD requested but THIS credential carries no ecdsa-sd-2023 base
                    // proof — present it at full disclosure alongside any SD-derived
                    // ones, keeping only allowlisted proofs (B.1).
                    presented_json.push(retain_presentable_proofs(&json_vc.raw)?);
                }
            }

            if sd_derived {
                let presentation = build_presentation_from_json(&holder_did, &presented_json)?;
                let signed = glue.sign_presentation(presentation, vpr).await?;
                return Ok(signed);
            }

            log::info!(
                "VCALM: VPR requested SD but no presented credential carries an \
                 ecdsa-sd-2023 base proof; signing full disclosure"
            );
        }

        // Full-disclosure path (also the no-SD-capable-credential fallback).
        let presentation = build_presentation(&holder_did, &present)?;
        let signed = glue.sign_presentation(presentation, vpr).await?;
        Ok(signed)
    }

    /// Resolve the `vcapi` exchange URL from an `interaction:` discovery endpoint.
    /// The bearer token is intentionally NOT attached here. Both the discovery
    /// URL and the discovered `vcapi` URL must pass [`validate_endpoint_url`]
    /// (HTTPS, or loopback http for local dev — §3.7.1/B.2; also rejects
    /// `file:`/other schemes a QR code could smuggle in).
    async fn discover_vcapi(&self, discovery_url: &str) -> Result<Url, VcalmError> {
        let discovery_url = Url::parse(discovery_url)
            .map_err(|e| VcalmError::Network(format!("invalid interaction URL: {e}")))?;
        validate_endpoint_url(&discovery_url)?;

        let resp = self
            .client
            .get(discovery_url)
            .header(ACCEPT, "application/json")
            .send()
            .await
            .map_err(|e| VcalmError::Network(e.to_string()))?;

        let status = resp.status();
        let body = read_body_capped(resp).await?;

        if !status.is_success() {
            return Err(VcalmError::ServerError {
                status: status.as_u16(),
                body,
            });
        }

        let discovery: DiscoveryResponse =
            serde_json::from_str(&body).map_err(|e| VcalmError::Deserialization(e.to_string()))?;

        let vcapi = discovery
            .protocols
            .get("vcapi")
            .ok_or(VcalmError::NoVcapiProtocol)?;

        let vcapi = Url::parse(vcapi)
            .map_err(|e| VcalmError::Network(format!("invalid vcapi URL: {e}")))?;
        validate_endpoint_url(&vcapi)?;
        Ok(vcapi)
    }

    /// POST one `vcapi` message and classify the reply (zero retries).
    ///
    /// Attaches the configured bearer ONLY when present, echoes the
    /// stored `referenceId`, captures the reply's `referenceId`/`last_vpr` back
    /// into [`ExchangeState`] for the next request, and surfaces the [`StepResult`] via
    /// [`classify`]. A `redirectUrl` reply is returned as terminal data — never followed.
    async fn post_message(&self, mut message: VcapiMessage) -> Result<StepResult, VcalmError> {
        let (exchange_url, auth_header, reference_id) = {
            let state = self.state.lock().await;
            (
                state.exchange_url.clone(),
                state.auth_header.clone(),
                state.reference_id.clone(),
            )
        };

        let exchange_url = exchange_url
            .ok_or_else(|| VcalmError::SessionState("no active exchange URL in session".into()))?;

        // Echo the server-issued referenceId on the outgoing request.
        if message.reference_id.is_none() {
            message.reference_id = reference_id;
        }

        tracing::debug!(
            "VCALM post_message request body: {}",
            serde_json::to_string(&message).unwrap_or_else(|e| format!("<serialize error: {e}>"))
        );

        let mut request = self.client.post(exchange_url).json(&message);
        if let Some(token) = &auth_header {
            request = request.header(AUTHORIZATION, format!("Bearer {token}"));
        }

        let resp = request
            .send()
            .await
            .map_err(|e| VcalmError::Network(e.to_string()))?;
        let status = resp.status();
        let body = read_body_capped(resp).await?;

        tracing::debug!("VCALM post_message response status={status} body: {body}");

        let result = classify(status, &body)?;

        // Track the referenceId echo target from every successful reply: the spec
        // says echo "the same referenceId in its NEXT message", so a reply WITHOUT
        // one clears the stored value (no stale echo after the server stops
        // sending it).
        if status.is_success() {
            let reply_reference_id = if body.trim().is_empty() {
                None
            } else {
                serde_json::from_str::<VcapiMessage>(&body)
                    .ok()
                    .and_then(|reply| reply.reference_id)
            };
            self.state.lock().await.reference_id = reply_reference_id;
        }
        match &result {
            StepResult::Request { vpr } => {
                self.state.lock().await.last_vpr = Some(vpr.clone());
            }
            StepResult::Offer {
                vcs,
                next_vpr,
                redirect_url,
            } => {
                // Capture the Offer for accept_offer/reject_offer/offered_credentials.
                // `current_offer` is ALWAYS set; `current_offer_next_vpr` and
                // `current_offer_redirect` mirror the Offer's combined properties.
                // Keep the existing `last_vpr` capture when next_vpr is present so
                // reads that depend on it (e.g. matched_credentials) still work.
                let mut state = self.state.lock().await;
                state.current_offer = Some(vcs.clone());
                state.current_offer_next_vpr = next_vpr.clone();
                state.current_offer_redirect = redirect_url.clone();
                if let Some(vpr) = next_vpr {
                    state.last_vpr = Some(vpr.clone());
                }
            }
            _ => {}
        }

        Ok(result)
    }
}

/// The credentials matching one QueryByExample query in the current VPR.
/// `query_index` is the position of the query in `vpr.query`, so the caller can map
/// a selection back to the originating query.
#[derive(uniffi::Record)]
pub struct VcalmMatchedCredentials {
    /// Index of the originating query in the VPR's `query[]` array.
    pub query_index: u32,
    /// The stored credentials that satisfied that query (may be empty).
    pub credentials: Vec<VcalmMatchedCredential>,
}

/// One matched credential plus its disclosure mode for THIS request, so consent
/// UIs can say honestly whether presenting shares only the requested fields or
/// the entire credential.
#[derive(uniffi::Record)]
pub struct VcalmMatchedCredential {
    /// The stored credential that satisfied the query.
    pub credential: Arc<ParsedCredential>,
    /// `true` when presenting under the CURRENT VPR would selectively disclose
    /// (the VPR lists an SD suite AND this credential carries a derivable SD
    /// base proof); `false` means full disclosure — the WHOLE credential is
    /// shared, not just the fields [`VcalmHolder::requested_fields`] names.
    pub selective_disclosure: bool,
}

/// The validity hint surfaced for one previewed offered credential.
/// Derived by verifying the VC's proof/claims read-only, BEFORE the user accepts, so
/// the UI can warn about an expired/unverifiable credential up front.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum OfferedValidity {
    /// Proof verified and the validity period is current.
    Valid,
    /// Proof verified but the validity period failed (expired/premature/other claims).
    /// `accept_offer` would still STORE this, with a distinct warning.
    TimeBounded,
    /// The credential's own cryptographic proof failed — `accept_offer` would REJECT
    /// the whole Offer.
    ProofInvalid,
    /// An `EnvelopedVerifiableCredential` — recognized but not yet decodable.
    Enveloped,
    /// A proof type/cryptosuite this SDK recognizes but cannot process yet
    /// (e.g. a `bbs-2023` base proof). `accept_offer` would REJECT the Offer
    /// with a typed unsupported-format error.
    UnsupportedProof,
    /// The verification machinery could not run (bad payload / unsupported shape).
    Unverifiable,
}

/// One credential offered in the current Offer, previewed for UI display.
/// Read-only projection — mirrors the [`VcalmMatchedCredentials`]/[`VcalmRequestedField`]
/// Record style. It surfaces enough for a consent screen (issuer, type(s),
/// `credentialSubject`) plus a [`validity`](Self::validity) hint, without any storage
/// side-effect.
#[derive(Debug, Clone, uniffi::Record)]
pub struct VcalmOfferedCredential {
    /// The VC's `issuer` rendered as a string (the bare id, or the `id` of an issuer
    /// object). `None` when absent or unrecognized.
    pub issuer: Option<String>,
    /// The VC's `type` values (excluding nothing — surfaced verbatim for display).
    pub types: Vec<String>,
    /// The VC's `credentialSubject` rendered as a compact JSON string for display.
    /// `None` when absent.
    pub credential_subject: Option<String>,
    /// The read-only validity hint.
    pub validity: OfferedValidity,
    /// The full offered VC as a JSON string. The SDK's `accept_offer` only stores
    /// the credential in the holder's own `vdc_collection`; the host app needs the
    /// raw VC to persist it into its OWN wallet store.
    pub raw_credential: String,
}

impl VcalmOfferedCredential {
    /// Project an offered-VC JSON entry into the display Record. Pure — no storage.
    fn from_entry(entry: &serde_json::Value, validity: OfferedValidity) -> Self {
        let issuer = match entry.get("issuer") {
            Some(serde_json::Value::String(s)) => Some(s.clone()),
            Some(serde_json::Value::Object(o)) => {
                o.get("id").and_then(|v| v.as_str()).map(str::to_string)
            }
            _ => None,
        };
        let types = match entry.get("type") {
            Some(serde_json::Value::Array(a)) => a
                .iter()
                .filter_map(|v| v.as_str())
                .map(str::to_string)
                .collect(),
            Some(serde_json::Value::String(s)) => vec![s.clone()],
            _ => vec![],
        };
        let credential_subject = entry.get("credentialSubject").map(|s| s.to_string());
        Self {
            issuer,
            types,
            credential_subject,
            validity,
            raw_credential: entry.to_string(),
        }
    }
}

/// One field NAMED by a QueryByExample `example`. Informational only — it
/// surfaces what a full-disclosure presentation will share, mirroring the
/// `Oid4vpRequestedField` shape; it does NOT limit disclosed fields.
#[derive(Debug, Clone, uniffi::Record)]
pub struct VcalmRequestedField {
    /// Index of the originating query in the VPR's `query[]` array.
    pub query_index: u32,
    /// Dotted path of the named field, e.g. `credentialSubject.givenName`.
    pub path: String,
    /// The example value for the field; an `""` example leaf renders as `"any value"`.
    pub value: String,
    /// Whether the originating query is required (`required` defaults to `true`).
    pub required: bool,
    /// The query's human-readable `reason`, if any.
    pub purpose: Option<String>,
}

/// One named-field path/value pair extracted from a QBE `example`.
struct ExampleField {
    path: String,
    value: String,
}

/// Read a response body with a hard size cap (B.4). Checks `Content-Length`
/// first, then enforces the cap while streaming, so a server that lies about
/// (or omits) the length still cannot exhaust memory.
async fn read_body_capped(mut resp: reqwest::Response) -> Result<String, VcalmError> {
    if let Some(len) = resp.content_length() {
        if len > MAX_RESPONSE_BYTES as u64 {
            return Err(VcalmError::ResponseTooLarge {
                limit_bytes: MAX_RESPONSE_BYTES as u64,
            });
        }
    }
    let mut buf: Vec<u8> = Vec::new();
    while let Some(chunk) = resp
        .chunk()
        .await
        .map_err(|e| VcalmError::Network(e.to_string()))?
    {
        if buf.len() + chunk.len() > MAX_RESPONSE_BYTES {
            return Err(VcalmError::ResponseTooLarge {
                limit_bytes: MAX_RESPONSE_BYTES as u64,
            });
        }
        buf.extend_from_slice(&chunk);
    }
    String::from_utf8(buf)
        .map_err(|e| VcalmError::Deserialization(format!("response body is not UTF-8: {e}")))
}

/// §3.7.1/B.2: interaction, discovery, and exchange URLs must be HTTPS — the
/// HTTPS origin is the trust signal the whole interaction model hangs on, and a
/// bearer token must never travel over plaintext. Plain `http` is allowed ONLY
/// for loopback hosts (local development/test servers); every other scheme
/// (`file:`, custom schemes a QR code could smuggle in) is rejected.
fn validate_endpoint_url(url: &Url) -> Result<(), VcalmError> {
    match url.scheme() {
        "https" => Ok(()),
        "http" => {
            let loopback = match url.host() {
                Some(url::Host::Ipv4(ip)) => ip.is_loopback(),
                Some(url::Host::Ipv6(ip)) => ip.is_loopback(),
                Some(url::Host::Domain(d)) => d.eq_ignore_ascii_case("localhost"),
                None => false,
            };
            if loopback {
                Ok(())
            } else {
                Err(VcalmError::InsecureUrl(format!(
                    "plain http is only allowed for loopback hosts, got {url}"
                )))
            }
        }
        other => Err(VcalmError::InsecureUrl(format!(
            "unsupported URL scheme `{other}`"
        ))),
    }
}

/// §3.4.3.2: "Holder implementations MUST ensure that the `domain` specified by
/// the verifier matches the domain used for the current channel of
/// communication" — the anti-replay defense the spec motivates with the
/// bank-replay scenario. Returns `Some((domain_host, channel_host))` on a
/// mismatch so [`VcalmHolder::submit_presentation`] can REFUSE before anything
/// is signed (the caller may explicitly override after its own consent flow).
/// `None` when the VPR carries no `domain` (spec-legal, OPTIONAL) or there is
/// no channel to compare.
fn domain_channel_mismatch(
    domain: Option<&str>,
    exchange_url: Option<&Url>,
) -> Option<(String, String)> {
    let (Some(domain), Some(exchange_url)) = (domain, exchange_url) else {
        return None;
    };
    // `domain` may arrive as a bare host ("verifier.example") or a full origin URL.
    let domain_host = Url::parse(domain)
        .ok()
        .and_then(|u| u.host_str().map(str::to_owned))
        .unwrap_or_else(|| domain.trim_end_matches('/').to_owned());
    let channel_host = exchange_url.host_str().unwrap_or_default();
    if domain_host.eq_ignore_ascii_case(channel_host) {
        None
    } else {
        Some((domain_host, channel_host.to_string()))
    }
}

/// §3.4.3.2: the response `holder` "MUST be set to a specific DID that is of
/// the type that was requested". Only `did:key` is wired: when one or more
/// queries list `acceptedMethods` and NONE of them accepts `key`, refuse with
/// a typed [`VcalmError::NoAcceptedDidMethod`] instead of signing a response
/// the verifier must reject. Absent/empty lists (or any list naming `key`)
/// proceed with the `did:key` default.
fn ensure_accepted_methods(vpr: &Vpr) -> Result<(), VcalmError> {
    let mut listed: Vec<String> = Vec::new();
    let mut any_key = false;
    for query in &vpr.query {
        let Some(methods) = &query.accepted_methods else {
            continue;
        };
        for m in methods {
            let name = match m {
                AcceptedMethodEntry::Name(name) => name.as_str(),
                AcceptedMethodEntry::Object { method } => method.as_str(),
            };
            any_key |= name == "key";
            if !listed.iter().any(|l| l == name) {
                listed.push(name.to_string());
            }
        }
    }
    if listed.is_empty() {
        log::debug!("VPR acceptedMethods absent; defaulting to did:key");
        Ok(())
    } else if any_key {
        log::debug!("VPR acceptedMethods lists `key`; holder signs with did:key");
        Ok(())
    } else {
        Err(VcalmError::NoAcceptedDidMethod {
            accepted: listed.join(", "),
        })
    }
}

/// Assemble an `AnyJsonPresentation` from the holder DID and the credentials to
/// present (V1/V2 arms). An empty credential slice yields a
/// DIDAuthentication-only presentation with an empty `verifiableCredential`
/// vec, in the VCDM v2 data model (the spec's DIDAuth response examples use
/// the v2 context). Mixing v1 and v2 credentials in ONE presentation is
/// refused with [`VcalmError::MixedCredentialVersions`] — neither data model
/// can embed the other's credentials; the caller should present one version
/// at a time.
fn build_presentation(
    holder_did: &str,
    credentials: &[Arc<ParsedCredential>],
) -> Result<AnyJsonPresentation, VcalmError> {
    let id: UriBuf = format!("urn:uuid:{}", Uuid::new_v4())
        .parse()
        .map_err(|e| PresentationError::Context(format!("invalid VP id: {e:?}")))?;
    let holder: UriBuf = holder_did
        .parse()
        .map_err(|e| PresentationError::VerificationMethod(format!("invalid holder DID: {e:?}")))?;

    // Decode each selected JsonVc, splitting V1/V2. For V2 we deserialize the raw
    // JSON directly into the `NonEmptyObject`-subject form the VP requires (avoiding
    // the in-place subject re-mapping that has no public helper on `AnyJsonCredential`).
    let mut v1_creds = Vec::new();
    let mut v2_creds = Vec::new();
    for cred in credentials {
        let Some(json_vc) = cred.as_json_vc() else {
            // Only full-disclosure W3C JSON-LD VCs are presentable.
            continue;
        };
        // B.1: keep only allowlisted proofs before the credential is embedded at
        // full disclosure — drops SD/unlinkable base proofs (holder-secret) AND
        // unknown proof types; errors when nothing presentable remains.
        let raw = retain_presentable_proofs(&json_vc.raw)?;
        let parsed: AnyJsonCredential = serde_json::from_value(raw.clone())
            .map_err(|e| PresentationError::Context(format!("credential decode: {e:?}")))?;
        match parsed {
            AnyJsonCredential::V1(v1) => v1_creds.push(v1),
            AnyJsonCredential::V2(_) => {
                let v2: JsonCredentialV2<NonEmptyObject> =
                    serde_json::from_value(raw).map_err(|e| {
                        PresentationError::Context(format!("v2 credential decode: {e:?}"))
                    })?;
                v2_creds.push(v2);
            }
        }
    }

    match (v1_creds.is_empty(), v2_creds.is_empty()) {
        // A v1+v2 mix would silently drop one side — refuse instead.
        (false, false) => Err(VcalmError::MixedCredentialVersions),
        (false, true) => Ok(AnyJsonPresentation::V1(JsonPresentationV1::new(
            Some(id),
            Some(holder),
            v1_creds,
        ))),
        // V2 credentials, or the empty DIDAuth-only case (v2 default).
        (true, _) => Ok(AnyJsonPresentation::V2(JsonPresentationV2::new(
            Some(id),
            vec![IdOr::Id(holder)],
            v2_creds,
        ))),
    }
}

/// SD-path VP assembly: wrap already-proofed credential
/// JSON values (e.g. the `ecdsa-sd-2023` DERIVED VCs from
/// [`JsonVc::derive_sd_vp_credential`]) into an `AnyJsonPresentation`, PRESERVING
/// each credential's embedded proof. A sibling of [`build_presentation`] (which
/// stays unchanged for the full-disclosure path) that takes raw
/// JSON instead of `ParsedCredential` so the derived SD proof is not re-decoded away.
fn build_presentation_from_json(
    holder_did: &str,
    raws: &[serde_json::Value],
) -> Result<AnyJsonPresentation, VcalmError> {
    let id: UriBuf = format!("urn:uuid:{}", Uuid::new_v4())
        .parse()
        .map_err(|e| PresentationError::Context(format!("invalid VP id: {e:?}")))?;
    let holder: UriBuf = holder_did
        .parse()
        .map_err(|e| PresentationError::VerificationMethod(format!("invalid holder DID: {e:?}")))?;

    let mut v1_creds = Vec::new();
    let mut v2_creds = Vec::new();
    for raw in raws {
        let parsed: AnyJsonCredential = serde_json::from_value(raw.clone())
            .map_err(|e| PresentationError::Context(format!("derived credential decode: {e:?}")))?;
        match parsed {
            AnyJsonCredential::V1(v1) => v1_creds.push(v1),
            AnyJsonCredential::V2(_) => {
                let v2: JsonCredentialV2<NonEmptyObject> = serde_json::from_value(raw.clone())
                    .map_err(|e| {
                        PresentationError::Context(format!("derived v2 credential decode: {e:?}"))
                    })?;
                v2_creds.push(v2);
            }
        }
    }

    match (v1_creds.is_empty(), v2_creds.is_empty()) {
        // A v1+v2 mix would silently drop one side — refuse instead.
        (false, false) => Err(VcalmError::MixedCredentialVersions),
        (false, true) => Ok(AnyJsonPresentation::V1(JsonPresentationV1::new(
            Some(id),
            Some(holder),
            v1_creds,
        ))),
        (true, _) => Ok(AnyJsonPresentation::V2(JsonPresentationV2::new(
            Some(id),
            vec![IdOr::Id(holder)],
            v2_creds,
        ))),
    }
}

/// Extract the example-named fields (informational) from a QBE `example`:
/// top-level `type`/`@context` (rendered as their JSON), each `credentialSubject`
/// key recursively, and every OTHER top-level example property (e.g.
/// `credentialStatus`) — the example names what the response credential must
/// contain (§3.4.2), so those properties are displayed AND SD-revealed too.
/// A leaf `""` renders as `"any value"` (§3.4.2).
fn example_field_paths(example: &serde_json::Value) -> Vec<ExampleField> {
    let mut out = Vec::new();
    if let Some(obj) = example.as_object() {
        for key in ["type", "@context"] {
            if let Some(v) = obj.get(key) {
                out.push(ExampleField {
                    path: key.to_string(),
                    value: render_value(v),
                });
            }
        }
        if let Some(subject) = obj.get("credentialSubject") {
            collect_subject_paths("credentialSubject", subject, &mut out);
        }
        for (key, value) in obj {
            if matches!(key.as_str(), "type" | "@context" | "credentialSubject") {
                continue;
            }
            collect_subject_paths(key, value, &mut out);
        }
    }
    out
}

/// Recursively collect dotted paths of a `credentialSubject` example object.
fn collect_subject_paths(prefix: &str, value: &serde_json::Value, out: &mut Vec<ExampleField>) {
    match value {
        serde_json::Value::Object(map) => {
            for (k, v) in map {
                collect_subject_paths(&format!("{prefix}.{k}"), v, out);
            }
        }
        leaf => out.push(ExampleField {
            path: prefix.to_string(),
            value: render_value(leaf),
        }),
    }
}

/// The cryptosuite names found on `raw["proof"]` (object or array), for
/// SD-base detection and precise error messages.
fn proof_cryptosuites(raw: &serde_json::Value) -> Vec<String> {
    let proofs = match raw.get("proof") {
        Some(serde_json::Value::Array(a)) => a.clone(),
        Some(obj @ serde_json::Value::Object(_)) => vec![obj.clone()],
        _ => return vec![],
    };
    proofs
        .iter()
        .filter_map(|p| p.get("cryptosuite").and_then(|c| c.as_str()))
        .map(str::to_string)
        .collect()
}

/// GATE 2: true iff `raw["proof"]` (object or array) carries a base proof
/// whose `cryptosuite` is `ecdsa-sd-2023` (the derivable SD suite —
/// `json_vc::DERIVABLE_SD_CRYPTOSUITES`). Handles the V2 proof shape
/// (object-or-array), scoped to the SD gate.
fn has_sd_base_proof(raw: &serde_json::Value) -> bool {
    proof_cryptosuites(raw)
        .iter()
        .any(|s| crate::credential::json_vc::DERIVABLE_SD_CRYPTOSUITES.contains(&s.as_str()))
}

/// True iff the credential carries an SD-family base proof this SDK can
/// recognize but NOT derive (today: `bbs-2023`). Such a credential can neither
/// be verified directly (base proofs are derivation material) nor
/// derive-verified — `accept_offer` refuses it with a typed error.
fn has_underivable_sd_base_proof(raw: &serde_json::Value) -> bool {
    proof_cryptosuites(raw).iter().any(|s| {
        SD_BASE_PROOF_CRYPTOSUITES.contains(&s.as_str())
            && !crate::credential::json_vc::DERIVABLE_SD_CRYPTOSUITES.contains(&s.as_str())
    })
}

/// Proofs that are SAFE TO PRESENT on a full-disclosure credential — an
/// ALLOWLIST per spec B.1 ("Unknown Proof Types"): an unknown proof type might
/// be an SD/unlinkable base proof carrying holder-secret material, so unknown
/// types are dropped by default instead of riding along. The list names the
/// standard non-SD Data Integrity cryptosuites plus the pre-DI Ed25519 proof
/// types real issuers still use. The known SD base suites
/// ([`SD_BASE_PROOF_CRYPTOSUITES`]) are excluded by construction.
const PRESENTABLE_PROOF_CRYPTOSUITES: &[&str] = &[
    "ecdsa-rdfc-2019",
    "eddsa-rdfc-2022",
    "ecdsa-jcs-2019",
    "eddsa-jcs-2022",
];

/// Non-DataIntegrityProof proof `type` values that are known-safe to present.
const PRESENTABLE_PROOF_TYPES: &[&str] = &["Ed25519Signature2020", "Ed25519Signature2018"];

/// True iff a single proof object is on the B.1 presentation allowlist.
fn proof_is_presentable(p: &serde_json::Value) -> bool {
    match p.get("type").and_then(|t| t.as_str()) {
        Some("DataIntegrityProof") => p
            .get("cryptosuite")
            .and_then(|c| c.as_str())
            .is_some_and(|s| PRESENTABLE_PROOF_CRYPTOSUITES.contains(&s)),
        Some(t) => PRESENTABLE_PROOF_TYPES.contains(&t),
        None => false,
    }
}

/// Return a copy of `raw` keeping only allowlisted proofs (B.1), preserving the
/// original `proof` shape (single object stays an object, array stays an array).
/// A credential whose proofs are ALL dropped (e.g. only an `ecdsa-sd-2023` /
/// `bbs-2023` base proof, or only unknown proof types) is refused with
/// [`VcalmError::NoPresentableProof`] — presenting unverifiable PII helps
/// nobody, and presenting a base proof leaks holder-secret material. A
/// credential with no `proof` field at all passes through unchanged (nothing to
/// leak; the verifier sees exactly what is stored).
fn retain_presentable_proofs(raw: &serde_json::Value) -> Result<serde_json::Value, VcalmError> {
    let credential_types = || {
        raw.get("type")
            .map(|t| t.to_string())
            .unwrap_or_else(|| "<untyped>".into())
    };
    let mut out = raw.clone();
    let Some(obj) = out.as_object_mut() else {
        return Ok(out);
    };
    let kept: Vec<serde_json::Value> = match obj.get("proof") {
        Some(serde_json::Value::Array(a)) => a
            .iter()
            .filter(|p| proof_is_presentable(p))
            .cloned()
            .collect(),
        Some(p @ serde_json::Value::Object(_)) => {
            if proof_is_presentable(p) {
                vec![p.clone()]
            } else {
                Vec::new()
            }
        }
        // no `proof` field (or non-object/array shape) — nothing to strip.
        _ => return Ok(out),
    };
    if kept.is_empty() {
        return Err(VcalmError::NoPresentableProof {
            credential_types: credential_types(),
        });
    }
    if kept.len() == 1 && matches!(obj.get("proof"), Some(serde_json::Value::Object(_))) {
        let single = kept.into_iter().next().expect("len checked == 1");
        obj.insert("proof".into(), single);
    } else {
        obj.insert("proof".into(), serde_json::Value::Array(kept));
    }
    Ok(out)
}

/// Convert a dotted QBE path (`credentialSubject.givenName`) into an RFC 6901 JSON
/// pointer (`/credentialSubject/givenName`), escaping `~`→`~0` and `/`→`~1`
/// (dotted paths are NOT JSON pointers).
fn dotted_to_pointer(dotted: &str) -> String {
    let mut p = String::new();
    for seg in dotted.split('.') {
        p.push('/');
        p.push_str(&seg.replace('~', "~0").replace('/', "~1"));
    }
    p
}

/// Transform QBE-named field PATHS into the spec `selectivePointers`: an
/// array of field-level RFC 6901 JSON pointers naming EXACTLY the QBE-requested
/// fields (`credentialSubject.*` plus other example-named properties such as
/// `credentialStatus` — the response credential must contain what the example
/// names, §3.4.2). Structural keys (`type`, `@context`) are excluded — the
/// derive auto-adds the issuer's `mandatoryPointers`, so the holder reveals only
/// the QBE-named fields (no oversharing). Array-valued example fields already
/// arrive here as a single parent path (`collect_subject_paths` treats arrays as
/// leaves) → parent pointer.
fn selective_pointers_from_paths(paths: &[String]) -> Vec<ssi::JsonPointerBuf> {
    paths
        .iter()
        .filter(|p| {
            let top = p.split('.').next().unwrap_or(p);
            !matches!(top, "type" | "@context")
        })
        .filter_map(|p| ssi::JsonPointerBuf::new(dotted_to_pointer(p)).ok())
        .collect()
}

/// Render an example leaf for display: `""` ⇒ `"any value"`, otherwise the JSON.
fn render_value(value: &serde_json::Value) -> String {
    match value {
        serde_json::Value::String(s) if s.is_empty() => "any value".to_string(),
        serde_json::Value::String(s) => s.clone(),
        other => other.to_string(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::local_store::LocalStore;
    use crate::vcalm::exchange::ProblemDetails;
    use serde_json::json;
    use wiremock::matchers::{body_json, body_string_contains, header, method, path};
    use wiremock::{Mock, MockServer, ResponseTemplate};

    /// Build a holder with the oid4vp `KeySigner` test double and an in-memory store.
    async fn test_holder() -> Arc<VcalmHolder> {
        test_holder_with_vdc(Arc::new(VdcCollection::new(Arc::new(LocalStore::new())))).await
    }

    /// Build a holder over a caller-provided (possibly seeded) VdcCollection. Wires the
    /// default JSON-LD context map so VPs carrying credentials canonicalize/sign.
    async fn test_holder_with_vdc(vdc: Arc<VdcCollection>) -> Arc<VcalmHolder> {
        let signer: Box<dyn PresentationSigner> = Box::new(crate::tests::load_signer());
        VcalmHolder::new_session(
            vdc,
            vec![],
            signer,
            Some(crate::context::default_ld_json_context()),
            None,
        )
        .await
        .expect("holder construction must succeed")
    }

    /// The holder's `did:key` (matches the `KeySigner` test double).
    fn holder_did() -> String {
        crate::tests::load_signer().did()
    }

    /// Seed a VdcCollection with one LdpVc built from `raw` JSON, returning the parsed
    /// credential for selection in `submit_presentation`.
    async fn seed_ldp_vc(vdc: &VdcCollection, raw: serde_json::Value) -> Arc<ParsedCredential> {
        let json_vc = crate::credential::format::json_vc::JsonVc::new_from_json(raw.to_string())
            .expect("valid JsonVc");
        let parsed = ParsedCredential::new_ldp_vc(json_vc);
        let generic = parsed.into_generic_form().expect("generic form");
        vdc.add(&generic).await.expect("seed credential");
        parsed
    }

    /// A v2 credential whose `credentialSubject.id` is the holder DID, with an inline
    /// context defining `givenName` so JSON-LD expansion succeeds offline.
    fn v2_credential(holder: &str, given_name: &str) -> serde_json::Value {
        json!({
            "@context": [
                "https://www.w3.org/ns/credentials/v2",
                {
                    "givenName": "https://schema.org/givenName",
                    "PermanentResidentCard": "https://schema.org/PermanentResidentCard"
                }
            ],
            "type": ["VerifiableCredential", "PermanentResidentCard"],
            "issuer": "https://issuer.example/",
            "credentialSubject": { "id": holder, "givenName": given_name }
        })
    }

    #[tokio::test]
    async fn new_returns_holder_with_default_state() {
        let holder = test_holder().await;
        let state = holder.state.lock().await;
        assert!(state.exchange_url.is_none());
        assert!(state.reference_id.is_none());
        assert!(state.auth_header.is_none());
        assert!(state.last_vpr.is_none());
    }

    #[tokio::test]
    async fn initiation_interaction_runs_discovery() {
        let server = MockServer::start().await;
        let base = server.uri();

        Mock::given(method("GET"))
            .and(path("/discovery"))
            .respond_with(ResponseTemplate::new(200).set_body_json(json!({
                "protocols": { "vcapi": format!("{base}/exchange") }
            })))
            .expect(1)
            .mount(&server)
            .await;
        Mock::given(method("POST"))
            .and(path("/exchange"))
            .respond_with(ResponseTemplate::new(200).set_body_json(json!({})))
            .expect(1)
            .mount(&server)
            .await;

        let holder = test_holder().await;
        let result = holder
            .start_exchange(format!("interaction:{base}/discovery"), None)
            .await
            .expect("exchange should start");
        assert_eq!(result, StepResult::Complete);
        // Both the discovery GET and the exchange POST asserted via .expect(1) on drop.
    }

    #[tokio::test]
    async fn initiation_direct_url_skips_discovery() {
        let server = MockServer::start().await;
        let base = server.uri();

        // No GET is mounted; a discovery GET would 404 and fail the flow.
        Mock::given(method("POST"))
            .and(path("/exchange"))
            .respond_with(ResponseTemplate::new(200).set_body_json(json!({})))
            .expect(1)
            .mount(&server)
            .await;

        let holder = test_holder().await;
        let result = holder
            .start_exchange(format!("{base}/exchange"), None)
            .await
            .expect("direct-URL exchange should start");
        assert_eq!(result, StepResult::Complete);
    }

    #[tokio::test]
    async fn initiation_bare_iuv_url_runs_discovery() {
        let server = MockServer::start().await;
        let base = server.uri();

        // §3.7.1/§3.7.2: a spec-conformant interaction QR encodes a BARE http(s)
        // URL carrying `?iuv=1` — it must route through discovery, not be POSTed
        // `{}` directly as if it were the exchange URL.
        Mock::given(method("GET"))
            .and(path("/interactions/z8n38Dp7a"))
            .respond_with(ResponseTemplate::new(200).set_body_json(json!({
                "protocols": { "vcapi": format!("{base}/exchange") }
            })))
            .expect(1)
            .mount(&server)
            .await;
        Mock::given(method("POST"))
            .and(path("/exchange"))
            .respond_with(ResponseTemplate::new(200).set_body_json(json!({})))
            .expect(1)
            .mount(&server)
            .await;

        let holder = test_holder().await;
        let result = holder
            .start_exchange(format!("{base}/interactions/z8n38Dp7a?iuv=1"), None)
            .await
            .expect("bare iuv=1 interaction URL should start via discovery");
        assert_eq!(result, StepResult::Complete);
    }

    #[tokio::test]
    async fn initiation_unsupported_iuv_version_is_err() {
        // §3.7.1: iuv "MUST be 1 when using this version of this API" — an
        // incompatible future version must not be processed blindly.
        let holder = test_holder().await;
        let err = holder
            .start_exchange("https://app.example/interactions/x?iuv=2".into(), None)
            .await
            .expect_err("iuv=2 must be rejected");
        assert!(matches!(err, VcalmError::Network(_)));
    }

    #[tokio::test]
    async fn discovery_missing_vcapi_is_err() {
        let server = MockServer::start().await;
        let base = server.uri();

        Mock::given(method("GET"))
            .and(path("/discovery"))
            .respond_with(ResponseTemplate::new(200).set_body_json(json!({
                "protocols": { "OID4VP": format!("{base}/other") }
            })))
            .mount(&server)
            .await;

        let holder = test_holder().await;
        let err = holder
            .start_exchange(format!("interaction:{base}/discovery"), None)
            .await
            .expect_err("missing vcapi must error");
        assert!(matches!(err, VcalmError::NoVcapiProtocol));
    }

    #[tokio::test]
    async fn start_posts_empty_object() {
        let server = MockServer::start().await;
        let base = server.uri();

        // The body matcher asserts the start request is exactly `{}`.
        Mock::given(method("POST"))
            .and(path("/exchange"))
            .and(body_json(json!({})))
            .respond_with(ResponseTemplate::new(200).set_body_json(json!({})))
            .expect(1)
            .mount(&server)
            .await;

        let holder = test_holder().await;
        let result = holder
            .start_exchange(format!("{base}/exchange"), None)
            .await
            .expect("start should post {}");
        assert_eq!(result, StepResult::Complete);
    }

    #[tokio::test]
    async fn bearer_header_attached_when_configured() {
        let server = MockServer::start().await;
        let base = server.uri();

        // Only matches when the Authorization: Bearer header is present.
        Mock::given(method("POST"))
            .and(path("/exchange"))
            .and(header("authorization", "Bearer tok-123"))
            .respond_with(ResponseTemplate::new(200).set_body_json(json!({})))
            .expect(1)
            .mount(&server)
            .await;

        let holder = test_holder().await;
        let result = holder
            .start_exchange(format!("{base}/exchange"), Some("tok-123".into()))
            .await
            .expect("bearer-authenticated exchange should succeed");
        assert_eq!(result, StepResult::Complete);
    }

    #[tokio::test]
    async fn no_bearer_header_when_not_configured() {
        let server = MockServer::start().await;
        let base = server.uri();

        // Matches only when NO authorization header is present (negative matcher).
        Mock::given(method("POST"))
            .and(path("/exchange"))
            .and(|req: &wiremock::Request| !req.headers.contains_key("authorization"))
            .respond_with(ResponseTemplate::new(200).set_body_json(json!({})))
            .expect(1)
            .mount(&server)
            .await;

        let holder = test_holder().await;
        let result = holder
            .start_exchange(format!("{base}/exchange"), None)
            .await
            .expect("no-token exchange should succeed");
        assert_eq!(result, StepResult::Complete);
    }

    #[tokio::test]
    async fn exchange_loop_request_submit_offer_accept_complete() {
        let server = MockServer::start().await;
        let base = server.uri();

        // Step 1: the start `{}` -> Request{vpr}, server issues referenceId "ref-1".
        Mock::given(method("POST"))
            .and(path("/exchange"))
            .and(body_json(json!({})))
            .respond_with(ResponseTemplate::new(200).set_body_json(json!({
                "verifiablePresentationRequest": { "query": [], "challenge": "c1" },
                "referenceId": "ref-1"
            })))
            .expect(1)
            .mount(&server)
            .await;

        // Step 2: holder echoes "ref-1" -> Offer{vcs, next_vpr}, issues "ref-2".
        Mock::given(method("POST"))
            .and(path("/exchange"))
            .and(body_string_contains("ref-1"))
            .respond_with(ResponseTemplate::new(200).set_body_json(json!({
                "verifiablePresentation": { "offered": "vc" },
                "verifiablePresentationRequest": { "query": [], "challenge": "c2" },
                "referenceId": "ref-2"
            })))
            .expect(1)
            .mount(&server)
            .await;

        // Step 3: holder echoes "ref-2" -> terminal redirect.
        Mock::given(method("POST"))
            .and(path("/exchange"))
            .and(body_string_contains("ref-2"))
            .respond_with(ResponseTemplate::new(200).set_body_json(json!({
                "redirectUrl": "https://example.com/done"
            })))
            .expect(1)
            .mount(&server)
            .await;

        let holder = test_holder().await;

        let step1 = holder
            .clone()
            .start_exchange(format!("{base}/exchange"), None)
            .await
            .expect("step 1");
        assert!(matches!(step1, StepResult::Request { .. }));

        // DIDAuth-only (no QBE queries in this VPR) ⇒ pass no selected credentials.
        let step2 = holder
            .clone()
            .submit_presentation(vec![], false)
            .await
            .expect("step 2");
        match step2 {
            StepResult::Offer { next_vpr, .. } => assert!(next_vpr.is_some()),
            other => panic!("expected Offer, got {other:?}"),
        }

        let step3 = holder
            .submit_presentation(vec![], false)
            .await
            .expect("step 3");
        match step3 {
            StepResult::Redirect { url } => assert_eq!(url.as_str(), "https://example.com/done"),
            other => panic!("expected Redirect, got {other:?}"),
        }
    }

    #[tokio::test]
    async fn exchange_loop_4xx_problem_details() {
        let server = MockServer::start().await;
        let base = server.uri();

        Mock::given(method("POST"))
            .and(path("/exchange"))
            .respond_with(ResponseTemplate::new(400).set_body_json(json!({
                "type": "https://exchange.example/errors/CRYPTOGRAPHIC_SECURITY_ERROR",
                "status": 400,
                "title": "Security error",
                "detail": "challenge mismatch"
            })))
            .expect(1)
            .mount(&server)
            .await;

        let holder = test_holder().await;
        let result = holder
            .start_exchange(format!("{base}/exchange"), None)
            .await
            .expect("4xx problem-details must be surfaced as Ok, not Err");
        match result {
            StepResult::Problem {
                details: ProblemDetails { status, .. },
            } => assert_eq!(status, Some(400)),
            other => panic!("expected Problem, got {other:?}"),
        }
    }

    // --- matched / requested / submit -----------------------------------

    /// A QueryByExample VPR matching a PermanentResidentCard with a `givenName` field.
    fn qbe_vpr() -> Vpr {
        serde_json::from_value(json!({
            "query": [{
                "type": ["QueryByExample"],
                "credentialQuery": {
                    "reason": "We need your residency card.",
                    "example": {
                        "type": ["VerifiableCredential", "PermanentResidentCard"],
                        "credentialSubject": { "givenName": "" }
                    }
                }
            }],
            "challenge": "nonce-abc",
            "domain": "https://verifier.example"
        }))
        .expect("valid QBE VPR")
    }

    #[tokio::test]
    async fn matched_credentials_returns_per_query_matches() {
        let vdc = Arc::new(VdcCollection::new(Arc::new(LocalStore::new())));
        let did = holder_did();
        seed_ldp_vc(&vdc, v2_credential(&did, "Jane")).await;
        let holder = test_holder_with_vdc(vdc).await;
        holder.state.lock().await.last_vpr = Some(qbe_vpr());

        let matched = holder.matched_credentials().await.expect("matched ok");
        assert_eq!(matched.len(), 1);
        assert_eq!(matched[0].query_index, 0);
        assert_eq!(matched[0].credentials.len(), 1);
    }

    #[tokio::test]
    async fn matched_credentials_empty_is_not_error() {
        // A seeded credential that does NOT match the QBE example ⇒ empty match list,
        // never an error.
        let vdc = Arc::new(VdcCollection::new(Arc::new(LocalStore::new())));
        let did = holder_did();
        seed_ldp_vc(
            &vdc,
            json!({
                "@context": ["https://www.w3.org/ns/credentials/v2"],
                "type": ["VerifiableCredential", "AlumniCredential"],
                "issuer": "https://issuer.example/",
                "credentialSubject": { "id": did }
            }),
        )
        .await;
        let holder = test_holder_with_vdc(vdc).await;
        holder.state.lock().await.last_vpr = Some(qbe_vpr());

        let matched = holder.matched_credentials().await.expect("matched ok");
        assert_eq!(matched.len(), 1, "the QBE query is still surfaced");
        assert!(matched[0].credentials.is_empty(), "no credential matched");
    }

    #[tokio::test]
    async fn requested_fields_reports_example_named_fields() {
        let holder = test_holder().await;
        holder.state.lock().await.last_vpr = Some(qbe_vpr());

        let fields = holder.requested_fields().await.expect("fields ok");
        // type + credentialSubject.givenName named in the example.
        assert!(fields.iter().any(|f| f.path == "type"));
        let given = fields
            .iter()
            .find(|f| f.path == "credentialSubject.givenName")
            .expect("givenName field present");
        assert_eq!(given.value, "any value", "\"\" leaf renders as any value");
        assert!(given.required, "required defaults to true");
    }

    #[tokio::test]
    async fn holder_did_method_is_did_key_when_accepted_methods_lists_key() {
        let holder = test_holder().await;

        // A DIDAuthentication-only VPR listing acceptedMethods=["key"].
        let vpr: Vpr = serde_json::from_value(json!({
            "query": [{ "type": ["DIDAuthentication"], "acceptedMethods": ["key"] }],
            "challenge": "nonce-abc"
        }))
        .unwrap();

        // Build+sign directly to assert on the holder DID in the VP.
        let signed = holder.build_and_sign_vp(&vpr, &[]).await.expect("sign ok");
        let value = serde_json::to_value(&signed).unwrap();
        let holder_in_vp = value["holder"].as_str().expect("holder present");
        assert!(holder_in_vp.starts_with("did:key:"), "got {holder_in_vp}");
    }

    #[tokio::test]
    async fn holder_did_method_defaults_did_key_when_accepted_methods_absent() {
        let holder = test_holder().await;
        let vpr = qbe_vpr(); // no acceptedMethods anywhere
        let signed = holder.build_and_sign_vp(&vpr, &[]).await.expect("sign ok");
        let value = serde_json::to_value(&signed).unwrap();
        let holder_in_vp = value["holder"].as_str().expect("holder present");
        assert!(holder_in_vp.starts_with("did:key:"), "got {holder_in_vp}");
    }

    #[tokio::test]
    async fn submit_builds_signs_and_posts() {
        let server = MockServer::start().await;
        let base = server.uri();

        // Step 1: start `{}` -> Request{vpr} (QBE), referenceId "ref-1".
        Mock::given(method("POST"))
            .and(path("/exchange"))
            .and(body_json(json!({})))
            .respond_with(ResponseTemplate::new(200).set_body_json(json!({
                "verifiablePresentationRequest": {
                    "query": [{
                        "type": ["QueryByExample"],
                        "credentialQuery": {
                            "example": {
                                "type": ["VerifiableCredential", "PermanentResidentCard"],
                                "credentialSubject": { "givenName": "" }
                            }
                        }
                    }],
                    "challenge": "c1",
                    "domain": "https://verifier.example"
                },
                "referenceId": "ref-1"
            })))
            .expect(1)
            .mount(&server)
            .await;

        // Step 2: the submitted message must carry a signed verifiablePresentation
        // (its proof) and echo "ref-1" -> terminal redirect.
        Mock::given(method("POST"))
            .and(path("/exchange"))
            .and(body_string_contains("ref-1"))
            .and(body_string_contains("\"proof\""))
            .and(body_string_contains("authentication"))
            .respond_with(ResponseTemplate::new(200).set_body_json(json!({
                "redirectUrl": "https://example.com/done"
            })))
            .expect(1)
            .mount(&server)
            .await;

        let vdc = Arc::new(VdcCollection::new(Arc::new(LocalStore::new())));
        let did = holder_did();
        let parsed = seed_ldp_vc(&vdc, v2_credential(&did, "Jane")).await;
        let holder = test_holder_with_vdc(vdc).await;

        let step1 = holder
            .clone()
            .start_exchange(format!("{base}/exchange"), None)
            .await
            .expect("step 1");
        assert!(matches!(step1, StepResult::Request { .. }));

        // Holder selects the matched credential and submits — build+sign happens
        // internally, then POSTs through the existing loop. The mock VPR names
        // domain verifier.example while the channel is 127.0.0.1, so this also
        // exercises the explicit allow_domain_mismatch=true override.
        let step2 = holder
            .submit_presentation(vec![parsed], true)
            .await
            .expect("step 2 submit");
        match step2 {
            StepResult::Redirect { url } => assert_eq!(url.as_str(), "https://example.com/done"),
            other => panic!("expected Redirect, got {other:?}"),
        }
    }

    #[tokio::test]
    async fn submit_refuses_domain_channel_mismatch_before_signing() {
        // §3.4.3.2 MUST: the VPR domain must match the communication channel.
        // Default behavior REFUSES with a typed error BEFORE signing/POSTing —
        // the only POST the server sees is the initiation.
        let server = MockServer::start().await;
        let base = server.uri();

        Mock::given(method("POST"))
            .and(path("/exchange"))
            .respond_with(ResponseTemplate::new(200).set_body_json(json!({
                "verifiablePresentationRequest": {
                    "query": [{ "type": ["DIDAuthentication"] }],
                    "challenge": "c1",
                    "domain": "https://evil.example"
                }
            })))
            .expect(1) // ONLY the initiation POST — no VP must ever be sent
            .mount(&server)
            .await;

        let holder = test_holder().await;
        holder
            .clone()
            .start_exchange(format!("{base}/exchange"), None)
            .await
            .expect("step 1");

        let err = holder
            .submit_presentation(vec![], false)
            .await
            .expect_err("mismatched domain must refuse by default");
        match err {
            VcalmError::DomainChannelMismatch { domain, channel } => {
                assert_eq!(domain, "evil.example");
                assert_eq!(channel, "127.0.0.1");
            }
            other => panic!("expected DomainChannelMismatch, got {other:?}"),
        }
    }

    #[tokio::test]
    async fn submit_refuses_when_no_accepted_cryptosuite_is_producible() {
        // §3.4.3.1 "holder MUST choose among": lists that exclude everything
        // this holder can produce refuse BEFORE signing.
        let holder = test_holder().await;
        let vpr: Vpr = serde_json::from_value(json!({
            "query": [{ "type": ["DIDAuthentication"] }],
            "challenge": "c1",
            "acceptedCryptosuites": ["eddsa-rdfc-2022"]
        }))
        .unwrap();
        let err = holder
            .build_and_sign_vp(&vpr, &[])
            .await
            .expect_err("unsupported suite list must refuse");
        match err {
            VcalmError::NoAcceptedCryptosuite { accepted } => {
                assert!(accepted.contains("eddsa-rdfc-2022"));
            }
            other => panic!("expected NoAcceptedCryptosuite, got {other:?}"),
        }
    }

    #[tokio::test]
    async fn submit_refuses_when_accepted_methods_exclude_did_key() {
        // §3.4.3.2: the holder DID "MUST be of the type that was requested".
        let holder = test_holder().await;
        let vpr: Vpr = serde_json::from_value(json!({
            "query": [{ "type": ["DIDAuthentication"], "acceptedMethods": ["web"] }],
            "challenge": "c1"
        }))
        .unwrap();
        let err = holder
            .build_and_sign_vp(&vpr, &[])
            .await
            .expect_err("methods excluding `key` must refuse");
        match err {
            VcalmError::NoAcceptedDidMethod { accepted } => assert_eq!(accepted, "web"),
            other => panic!("expected NoAcceptedDidMethod, got {other:?}"),
        }
    }

    #[test]
    fn validate_endpoint_url_is_https_or_loopback() {
        let ok = |u: &str| validate_endpoint_url(&Url::parse(u).unwrap()).is_ok();
        assert!(ok("https://verifier.example/exchanges/1"));
        assert!(
            ok("http://127.0.0.1:8080/exchange"),
            "loopback http allowed"
        );
        assert!(ok("http://localhost:8080/exchange"));
        assert!(ok("http://[::1]:8080/exchange"));
        assert!(!ok("http://evil.example/exchange"), "remote http rejected");
        assert!(!ok("file:///etc/passwd"), "non-http scheme rejected");
        assert!(!ok("ftp://example.com/x"));
    }

    #[tokio::test]
    async fn start_exchange_rejects_insecure_urls_without_network() {
        let holder = test_holder().await;
        let err = holder
            .clone()
            .start_exchange("http://evil.example/exchange".into(), None)
            .await
            .expect_err("plain http to a remote host must be rejected");
        assert!(matches!(err, VcalmError::InsecureUrl(_)));

        let err = holder
            .clone()
            .start_exchange("interaction:file:///etc/passwd".into(), None)
            .await
            .expect_err("file: behind the interaction scheme must be rejected");
        assert!(matches!(err, VcalmError::InsecureUrl(_)));
    }

    #[tokio::test]
    async fn start_exchange_resets_previous_session_state() {
        // Exchange A leaves referenceId + last_vpr + a pending Offer behind;
        // starting exchange B must not leak ANY of it (different server!).
        let server_a = MockServer::start().await;
        let vc = signed_offered_vc("urn:uuid:reset-1", "Gail").await;
        Mock::given(method("POST"))
            .and(path("/a"))
            .respond_with(ResponseTemplate::new(200).set_body_json(json!({
                "verifiablePresentation": { "verifiableCredential": [vc] },
                "verifiablePresentationRequest": { "query": [], "challenge": "a" },
                "referenceId": "ref-a"
            })))
            .mount(&server_a)
            .await;
        let server_b = MockServer::start().await;
        Mock::given(method("POST"))
            .and(path("/b"))
            .and(body_json(json!({}))) // NO stale referenceId echo
            .respond_with(ResponseTemplate::new(200).set_body_json(json!({})))
            .expect(1)
            .mount(&server_b)
            .await;

        let holder = test_holder().await;
        holder
            .clone()
            .start_exchange(format!("{}/a", server_a.uri()), None)
            .await
            .expect("exchange A offer");
        {
            let state = holder.state.lock().await;
            assert!(state.reference_id.is_some());
            assert!(state.current_offer.is_some());
            assert!(state.last_vpr.is_some());
        }

        let step = holder
            .clone()
            .start_exchange(format!("{}/b", server_b.uri()), None)
            .await
            .expect("exchange B start");
        assert_eq!(step, StepResult::Complete);
        let state = holder.state.lock().await;
        assert!(state.reference_id.is_none(), "stale referenceId cleared");
        assert!(state.current_offer.is_none(), "stale Offer cleared");
        assert!(state.last_vpr.is_none(), "stale VPR cleared");
    }

    #[tokio::test]
    async fn mixed_vcdm_versions_refuse_instead_of_dropping() {
        let did = holder_did();
        let v2 = parsed_ldp(v2_credential(&did, "Jane"));
        let v1 = parsed_ldp(json!({
            "@context": [
                "https://www.w3.org/2018/credentials/v1",
                { "givenName": "https://schema.org/givenName" }
            ],
            "type": ["VerifiableCredential"],
            "issuer": "https://issuer.example/",
            "issuanceDate": "2024-01-01T00:00:00Z",
            "credentialSubject": { "id": did, "givenName": "Jane" }
        }));
        let err = build_presentation(&did, &[v1, v2])
            .expect_err("a v1+v2 mix must refuse, not silently drop one side");
        assert!(matches!(err, VcalmError::MixedCredentialVersions));
    }

    #[tokio::test]
    async fn accept_sd_base_proof_offer_stores_original_base_credential() {
        // H3 regression: an ecdsa-sd-2023 BASE-proof VC — the very credential an
        // SD-capable wallet is issued — must be acceptable. ssi cannot verify a
        // base proof directly; accept validates via a full-reveal derive and
        // stores the ORIGINAL base-proof VC for later SD presentations.
        let server = MockServer::start().await;
        let base = server.uri();
        let sd_vc = issue_sd_base_proof(
            json!({
                "@context": [
                    "https://www.w3.org/ns/credentials/v2",
                    { "@vocab": "https://example.org/vocab#" }
                ],
                "type": ["VerifiableCredential"],
                "issuer": "https://issuer.example/",
                "credentialSubject": { "givenName": "Jane", "familyName": "Doe" }
            }),
            &["/issuer"],
        )
        .await;

        Mock::given(method("POST"))
            .and(path("/exchange"))
            .and(body_json(json!({})))
            .respond_with(ResponseTemplate::new(200).set_body_json(json!({
                "verifiablePresentation": { "verifiableCredential": [sd_vc.clone()] },
                "referenceId": "ref-1"
            })))
            .expect(1)
            .mount(&server)
            .await;
        Mock::given(method("POST"))
            .and(path("/exchange"))
            .and(body_string_contains("ref-1"))
            .respond_with(ResponseTemplate::new(200).set_body_json(json!({})))
            .expect(1)
            .mount(&server)
            .await;

        let vdc = Arc::new(VdcCollection::new(Arc::new(LocalStore::new())));
        let holder = test_holder_with_vdc(vdc.clone()).await;
        holder
            .clone()
            .start_exchange(format!("{base}/exchange"), None)
            .await
            .expect("offer step");

        // The preview must rate the base-proof VC Valid (derive-then-verify).
        let preview = holder.offered_credentials().await.expect("preview");
        assert_eq!(preview.len(), 1);
        assert_eq!(
            preview[0].validity,
            OfferedValidity::Valid,
            "an authentic SD base-proof VC previews as Valid"
        );

        let step = holder.clone().accept_offer().await.expect("accept SD VC");
        assert_eq!(step, StepResult::Complete);

        // The STORED credential keeps its base proof (needed for later derives).
        let ids = vdc.all_entries().await.unwrap();
        assert_eq!(ids.len(), 1);
        let stored = vdc.get(ids[0]).await.unwrap().unwrap();
        let parsed = stored.try_into_parsed().expect("stored VC parses");
        let raw = &parsed.as_json_vc().expect("LDP VC").raw;
        assert!(
            has_sd_base_proof(raw),
            "the ORIGINAL base-proof credential is stored, not the derived form"
        );
    }

    #[tokio::test]
    async fn accept_offer_with_combined_redirect_surfaces_redirect() {
        // §3.6 combined properties: an Offer message carrying a redirectUrl —
        // after storing, accept surfaces the Redirect (no extra advance POST).
        let server = MockServer::start().await;
        let base = server.uri();
        let vc = signed_offered_vc("urn:uuid:redir-1", "Hugo").await;

        Mock::given(method("POST"))
            .and(path("/exchange"))
            .and(body_json(json!({})))
            .respond_with(ResponseTemplate::new(200).set_body_json(json!({
                "verifiablePresentation": { "verifiableCredential": [vc] },
                "redirectUrl": "https://example.com/done"
            })))
            .expect(1) // the ONLY POST — accept must not advance again
            .mount(&server)
            .await;

        let vdc = Arc::new(VdcCollection::new(Arc::new(LocalStore::new())));
        let holder = test_holder_with_vdc(vdc.clone()).await;
        holder
            .clone()
            .start_exchange(format!("{base}/exchange"), None)
            .await
            .expect("offer step");
        let step = holder.clone().accept_offer().await.expect("accept");
        match step {
            StepResult::Redirect { url } => assert_eq!(url.as_str(), "https://example.com/done"),
            other => panic!("expected the combined redirect surfaced, got {other:?}"),
        }
        assert_eq!(vdc.all_entries().await.unwrap().len(), 1, "VC stored");
    }

    #[tokio::test]
    async fn submit_didauth_only_posts_vp_without_credentials() {
        let server = MockServer::start().await;
        let base = server.uri();

        Mock::given(method("POST"))
            .and(path("/exchange"))
            .and(body_json(json!({})))
            .respond_with(ResponseTemplate::new(200).set_body_json(json!({
                "verifiablePresentationRequest": {
                    "query": [{ "type": ["DIDAuthentication"], "acceptedMethods": ["key"] }],
                    "challenge": "c1"
                },
                "referenceId": "ref-1"
            })))
            .expect(1)
            .mount(&server)
            .await;
        // The submitted VP must carry a proof but no verifiableCredential.
        Mock::given(method("POST"))
            .and(path("/exchange"))
            .and(body_string_contains("\"proof\""))
            .and(|req: &wiremock::Request| {
                let body = String::from_utf8_lossy(&req.body);
                body.contains("ref-1") && !body.contains("verifiableCredential")
            })
            .respond_with(ResponseTemplate::new(200).set_body_json(json!({})))
            .expect(1)
            .mount(&server)
            .await;

        let holder = test_holder().await;
        let step1 = holder
            .clone()
            .start_exchange(format!("{base}/exchange"), None)
            .await
            .expect("step 1");
        assert!(matches!(step1, StepResult::Request { .. }));

        let step2 = holder
            .submit_presentation(vec![], false)
            .await
            .expect("submit");
        assert_eq!(step2, StepResult::Complete);
    }

    // --- issuance receive (accept / reject / offered_credentials) --------

    /// Sign an (unsecured) VCDM v2 credential OFFLINE with the shared `did:key`
    /// `KeySigner` double, producing an `ecdsa-rdfc-2019` issuer Data Integrity proof
    /// that `verify_raw_credential` accepts with no network. Reuses the existing
    /// `VpSigner` glue (`AnySuite::EcdsaRdfc2019.sign_with`) —
    /// no hand-rolled signature/canonicalization. `ProofPurpose::AssertionMethod`
    /// (issuer proof), and the issuer is the holder `did:key` so the proof's
    /// verification method resolves offline.
    async fn sign_offered_vc(claims: serde_json::Value) -> serde_json::Value {
        use ssi::claims::vc::v1::JsonCredential;
        use ssi::claims::SignatureEnvironment;
        use ssi::dids::{AnyDidMethod, VerificationMethodDIDResolver};
        use ssi::json_ld::syntax::{Context, ContextEntry};
        use ssi::json_ld::{ContextLoader, IriRefBuf};
        use ssi::prelude::{AnySuite, CryptographicSuite, ProofOptions};
        use ssi::verification_methods::ProofPurpose;
        use ssi::xsd::DateTimeStamp;

        let signer: Box<dyn PresentationSigner> = Box::new(crate::tests::load_signer());
        let glue = VpSigner::new(
            Arc::new(signer),
            Some(crate::context::default_ld_json_context()),
        );

        let resolver = VerificationMethodDIDResolver::new(AnyDidMethod::default());
        let vm = glue
            .verification_method_id()
            .await
            .expect("verification method id");
        let mut proof_options = ProofOptions::new(
            DateTimeStamp::now_ms().into(),
            vm.into(),
            // Issuer proof, not Authentication.
            ProofPurpose::Assertion,
            Default::default(),
        );
        // VCDM v1 + Data Integrity requires the `data-integrity/v2` context entry or
        // canonicalization/verification fails.
        let di_context = IriRefBuf::new("https://w3id.org/security/data-integrity/v2".into())
            .expect("data-integrity context iri");
        proof_options.context = Some(Context::One(ContextEntry::IriRef(di_context)));

        let context = ContextLoader::default()
            .with_context_map_from(crate::context::default_ld_json_context())
            .expect("context loader");
        let env = SignatureEnvironment {
            json_ld_loader: context,
            eip712_loader: (),
        };

        // `verify_raw_credential`'s LdpVc arm decodes into the VCDM **v1** JsonCredential
        // (ssi `any_credential_from_json_slice`), which strictly requires the v1 context —
        // so the offered fixture is a v1 credential.
        let credential: JsonCredential =
            serde_json::from_value(claims).expect("valid v1 credential");

        let signed = AnySuite::EcdsaRdfc2019
            .sign_with(
                &env,
                credential,
                resolver,
                &glue,
                proof_options,
                Default::default(),
            )
            .await
            .expect("offered VC must sign offline");
        serde_json::to_value(&signed).expect("serialize signed offered VC")
    }

    /// A signed full-disclosure offered VC (PermanentResidentCard) whose proof verifies
    /// offline. `id` drives the stable storage id; `given_name` is QBE-matchable.
    async fn signed_offered_vc(id: &str, given_name: &str) -> serde_json::Value {
        let did = holder_did();
        sign_offered_vc(json!({
            "@context": [
                "https://www.w3.org/2018/credentials/v1",
                {
                    "givenName": "https://schema.org/givenName",
                    "PermanentResidentCard": "https://schema.org/PermanentResidentCard"
                }
            ],
            "id": id,
            "type": ["VerifiableCredential", "PermanentResidentCard"],
            "issuer": did,
            "issuanceDate": "2020-01-01T00:00:00Z",
            "credentialSubject": { "givenName": given_name }
        }))
        .await
    }

    /// A cryptographically-VALID offered VC whose validity period is in the past
    /// (`expirationDate` before now) — the proof verifies but claims are expired.
    async fn expired_offered_vc(id: &str) -> serde_json::Value {
        let did = holder_did();
        sign_offered_vc(json!({
            "@context": [
                "https://www.w3.org/2018/credentials/v1",
                {
                    "givenName": "https://schema.org/givenName",
                    "PermanentResidentCard": "https://schema.org/PermanentResidentCard"
                }
            ],
            "id": id,
            "type": ["VerifiableCredential", "PermanentResidentCard"],
            "issuer": did,
            "issuanceDate": "2019-01-01T00:00:00Z",
            "expirationDate": "2020-01-01T00:00:00Z",
            "credentialSubject": { "givenName": "Expired" }
        }))
        .await
    }

    /// A PROOF-INVALID offered VC: signed validly, then a signed claim is mutated AFTER
    /// signing so the issuer proof no longer matches the document (hard-fail input).
    async fn proof_invalid_offered_vc(id: &str) -> serde_json::Value {
        let mut vc = signed_offered_vc(id, "Original").await;
        vc["credentialSubject"]["givenName"] = json!("Tampered");
        vc
    }

    // ---- the signed-fixture verifies / fails offline as required ----

    #[tokio::test]
    async fn signed_offered_vc_verifies_offline() {
        let vc = signed_offered_vc("urn:uuid:offline-1", "Alice").await;
        let raw = issuance::build_raw_credential(&vc).expect("raw");
        let verification =
            verify_raw_credential(&raw, Some(crate::context::default_ld_json_context()))
                .await
                .expect("verification ran");
        assert!(
            verification.expect_verified().is_ok(),
            "the offered VC's issuer proof must verify OFFLINE"
        );
    }

    #[tokio::test]
    async fn proof_invalid_offered_vc_fails_proof() {
        let vc = proof_invalid_offered_vc("urn:uuid:bad-proof-1").await;
        let raw = issuance::build_raw_credential(&vc).expect("raw");
        let verification =
            verify_raw_credential(&raw, Some(crate::context::default_ld_json_context()))
                .await
                .expect("verification ran");
        assert!(
            matches!(
                verification.expect_verified(),
                Err(InvalidCredential::Proof)
            ),
            "a tampered offered VC must fail proof verification"
        );
    }

    // ---- end-to-end wiremock loop tests ----

    #[tokio::test]
    async fn accept_stores_advances_then_qbe_discovers() {
        // verify+store+advance AND QBE-discoverable in the loop.
        let server = MockServer::start().await;
        let base = server.uri();
        let vc = signed_offered_vc("urn:uuid:accept-1", "Alice").await;

        // Step 1: start `{}` -> Offer (no next_vpr), referenceId "ref-1".
        Mock::given(method("POST"))
            .and(path("/exchange"))
            .and(body_json(json!({})))
            .respond_with(ResponseTemplate::new(200).set_body_json(json!({
                "verifiablePresentation": { "verifiableCredential": [vc] },
                "referenceId": "ref-1"
            })))
            .expect(1)
            .mount(&server)
            .await;
        // Step 2: the accept advance echoes "ref-1" -> Complete (exactly ONE advance POST).
        Mock::given(method("POST"))
            .and(path("/exchange"))
            .and(body_string_contains("ref-1"))
            .respond_with(ResponseTemplate::new(200).set_body_json(json!({})))
            .expect(1)
            .mount(&server)
            .await;

        let vdc = Arc::new(VdcCollection::new(Arc::new(LocalStore::new())));
        let holder = test_holder_with_vdc(vdc.clone()).await;

        let step1 = holder
            .clone()
            .start_exchange(format!("{base}/exchange"), None)
            .await
            .expect("offer step");
        assert!(matches!(step1, StepResult::Offer { next_vpr: None, .. }));

        let step2 = holder.clone().accept_offer().await.expect("accept");
        assert_eq!(step2, StepResult::Complete);
        assert_eq!(
            vdc.all_entries().await.unwrap().len(),
            1,
            "the accepted VC is stored"
        );

        // The stored VC is QueryByExample-discoverable in the same loop.
        holder.state.lock().await.last_vpr = Some(qbe_vpr());
        let matched = holder.matched_credentials().await.expect("matched");
        assert_eq!(matched.len(), 1);
        assert_eq!(
            matched[0].credentials.len(),
            1,
            "issuance → presentation in one loop"
        );
    }

    #[tokio::test]
    async fn accept_advance_4xx_malformed_body_completes_after_store() {
        // The advance reply is a 4xx with a NON-problem-details body (e.g. a
        // 403 "Internal Server Error"): the server treats the issuance exchange
        // as already complete and rejects the extra POST. The
        // credential is already verified+stored, so issuance reports Complete and
        // the consumed Offer is cleared.
        let server = MockServer::start().await;
        let base = server.uri();
        let vc = signed_offered_vc("urn:uuid:advance-403-1", "Dora").await;

        Mock::given(method("POST"))
            .and(path("/exchange"))
            .and(body_json(json!({})))
            .respond_with(ResponseTemplate::new(200).set_body_json(json!({
                "verifiablePresentation": { "verifiableCredential": [vc] },
                "referenceId": "ref-1"
            })))
            .expect(1)
            .mount(&server)
            .await;
        Mock::given(method("POST"))
            .and(path("/exchange"))
            .and(body_string_contains("ref-1"))
            .respond_with(ResponseTemplate::new(403).set_body_string("Internal Server Error"))
            .expect(1)
            .mount(&server)
            .await;

        let vdc = Arc::new(VdcCollection::new(Arc::new(LocalStore::new())));
        let holder = test_holder_with_vdc(vdc.clone()).await;
        holder
            .clone()
            .start_exchange(format!("{base}/exchange"), None)
            .await
            .expect("offer step");
        let step = holder.clone().accept_offer().await.expect("accept");
        assert_eq!(step, StepResult::Complete);
        assert_eq!(vdc.all_entries().await.unwrap().len(), 1, "VC stored");
        assert!(
            holder.state.lock().await.current_offer.is_none(),
            "consumed offer cleared after a completed issuance"
        );
    }

    #[tokio::test]
    async fn accept_advance_4xx_problem_completes_after_store() {
        // Same terminal-offer server, with a WELL-FORMED RFC 9457 4xx problem on
        // the advance ("exchange already complete"): after a successful store the
        // 4xx means the exchange is closed, so issuance reports Complete instead
        // of surfacing a Problem — the credential is stored either way.
        let server = MockServer::start().await;
        let base = server.uri();
        let vc = signed_offered_vc("urn:uuid:advance-problem-1", "Eve").await;

        Mock::given(method("POST"))
            .and(path("/exchange"))
            .and(body_json(json!({})))
            .respond_with(ResponseTemplate::new(200).set_body_json(json!({
                "verifiablePresentation": { "verifiableCredential": [vc] },
                "referenceId": "ref-1"
            })))
            .expect(1)
            .mount(&server)
            .await;
        Mock::given(method("POST"))
            .and(path("/exchange"))
            .and(body_string_contains("ref-1"))
            .respond_with(ResponseTemplate::new(403).set_body_json(json!({
                "type": "https://example.com/problems/exchange-complete",
                "status": 403,
                "title": "Exchange already complete"
            })))
            .expect(1)
            .mount(&server)
            .await;

        let vdc = Arc::new(VdcCollection::new(Arc::new(LocalStore::new())));
        let holder = test_holder_with_vdc(vdc.clone()).await;
        holder
            .clone()
            .start_exchange(format!("{base}/exchange"), None)
            .await
            .expect("offer step");
        let step = holder.clone().accept_offer().await.expect("accept");
        assert_eq!(step, StepResult::Complete);
        assert_eq!(vdc.all_entries().await.unwrap().len(), 1, "VC stored");
    }

    #[tokio::test]
    async fn accept_advance_5xx_propagates_error_after_storing() {
        // A 5xx is NOT a "the exchange is over" signal — it propagates so the
        // caller can retry, but the credential is already safely stored.
        let server = MockServer::start().await;
        let base = server.uri();
        let vc = signed_offered_vc("urn:uuid:advance-500-1", "Frank").await;

        Mock::given(method("POST"))
            .and(path("/exchange"))
            .and(body_json(json!({})))
            .respond_with(ResponseTemplate::new(200).set_body_json(json!({
                "verifiablePresentation": { "verifiableCredential": [vc] },
                "referenceId": "ref-1"
            })))
            .expect(1)
            .mount(&server)
            .await;
        Mock::given(method("POST"))
            .and(path("/exchange"))
            .and(body_string_contains("ref-1"))
            .respond_with(ResponseTemplate::new(500).set_body_string("boom"))
            .expect(1)
            .mount(&server)
            .await;

        let vdc = Arc::new(VdcCollection::new(Arc::new(LocalStore::new())));
        let holder = test_holder_with_vdc(vdc.clone()).await;
        holder
            .clone()
            .start_exchange(format!("{base}/exchange"), None)
            .await
            .expect("offer step");
        let err = holder
            .clone()
            .accept_offer()
            .await
            .expect_err("5xx advance must surface, not masquerade as Complete");
        assert!(matches!(err, VcalmError::ServerError { status: 500, .. }));
        assert_eq!(
            vdc.all_entries().await.unwrap().len(),
            1,
            "the VC is stored even though the courtesy advance failed"
        );
    }

    #[tokio::test]
    async fn accept_with_next_vpr_returns_request_without_extra_post() {
        // The Offer carried a follow-on VPR ⇒ accept returns Request
        // with NO second advance POST (no third handler is mounted).
        let server = MockServer::start().await;
        let base = server.uri();
        let vc = signed_offered_vc("urn:uuid:nextvpr-1", "Bob").await;

        Mock::given(method("POST"))
            .and(path("/exchange"))
            .and(body_json(json!({})))
            .respond_with(ResponseTemplate::new(200).set_body_json(json!({
                "verifiablePresentation": { "verifiableCredential": [vc] },
                "verifiablePresentationRequest": { "query": [], "challenge": "c2" },
                "referenceId": "ref-1"
            })))
            .expect(1)
            .mount(&server)
            .await;
        // Deliberately NO matcher for an advance POST: if accept POSTs again it 404s and errors.

        let vdc = Arc::new(VdcCollection::new(Arc::new(LocalStore::new())));
        let holder = test_holder_with_vdc(vdc.clone()).await;

        let step1 = holder
            .clone()
            .start_exchange(format!("{base}/exchange"), None)
            .await
            .expect("offer step");
        assert!(matches!(
            step1,
            StepResult::Offer {
                next_vpr: Some(_),
                ..
            }
        ));

        let step2 = holder.clone().accept_offer().await.expect("accept");
        match step2 {
            StepResult::Request { vpr } => {
                assert_eq!(
                    vpr.challenge.as_deref(),
                    Some("c2"),
                    "the follow-on VPR is surfaced"
                )
            }
            other => panic!("expected Request (no extra POST), got {other:?}"),
        }
        assert_eq!(
            vdc.all_entries().await.unwrap().len(),
            1,
            "the VC is still stored before surfacing the next request"
        );
    }

    #[tokio::test]
    async fn reject_advances_without_storing() {
        let server = MockServer::start().await;
        let base = server.uri();
        let vc = signed_offered_vc("urn:uuid:reject-1", "Carol").await;

        Mock::given(method("POST"))
            .and(path("/exchange"))
            .and(body_json(json!({})))
            .respond_with(ResponseTemplate::new(200).set_body_json(json!({
                "verifiablePresentation": { "verifiableCredential": [vc] },
                "referenceId": "ref-1"
            })))
            .expect(1)
            .mount(&server)
            .await;
        Mock::given(method("POST"))
            .and(path("/exchange"))
            .and(body_string_contains("ref-1"))
            .respond_with(ResponseTemplate::new(200).set_body_json(json!({})))
            .expect(1)
            .mount(&server)
            .await;

        let vdc = Arc::new(VdcCollection::new(Arc::new(LocalStore::new())));
        let holder = test_holder_with_vdc(vdc.clone()).await;

        holder
            .clone()
            .start_exchange(format!("{base}/exchange"), None)
            .await
            .expect("offer step");
        let step = holder.clone().reject_offer().await.expect("reject");
        assert_eq!(step, StepResult::Complete);
        assert!(
            vdc.all_entries().await.unwrap().is_empty(),
            "reject stores nothing"
        );
    }

    #[tokio::test]
    async fn accept_proof_invalid_is_atomic_failure() {
        // Proof-invalid ⇒ Err, store nothing, do NOT advance (no advance handler).
        let server = MockServer::start().await;
        let base = server.uri();
        let bad = proof_invalid_offered_vc("urn:uuid:bad-proof-loop-1").await;

        Mock::given(method("POST"))
            .and(path("/exchange"))
            .and(body_json(json!({})))
            .respond_with(ResponseTemplate::new(200).set_body_json(json!({
                "verifiablePresentation": { "verifiableCredential": [bad] },
                "referenceId": "ref-1"
            })))
            .expect(1)
            .mount(&server)
            .await;

        let vdc = Arc::new(VdcCollection::new(Arc::new(LocalStore::new())));
        let holder = test_holder_with_vdc(vdc.clone()).await;

        holder
            .clone()
            .start_exchange(format!("{base}/exchange"), None)
            .await
            .expect("offer step");
        let err = holder
            .clone()
            .accept_offer()
            .await
            .expect_err("proof-invalid accept must error");
        assert!(matches!(err, VcalmError::InvalidCredentialProof { .. }));
        assert!(
            vdc.all_entries().await.unwrap().is_empty(),
            "nothing stored on a proof failure (atomic)"
        );
    }

    #[tokio::test]
    async fn re_accept_same_credential_is_idempotent() {
        // Two Offer→accept cycles delivering the SAME VC id ⇒ one stored row.
        let server = MockServer::start().await;
        let base = server.uri();
        let vc = signed_offered_vc("urn:uuid:idem-1", "Dave").await;

        Mock::given(method("POST"))
            .and(path("/exchange"))
            .and(body_json(json!({})))
            .respond_with(ResponseTemplate::new(200).set_body_json(json!({
                "verifiablePresentation": { "verifiableCredential": [vc.clone()] },
                "referenceId": "ref-1"
            })))
            .expect(1)
            .mount(&server)
            .await;
        Mock::given(method("POST"))
            .and(path("/exchange"))
            .and(body_string_contains("ref-1"))
            .respond_with(ResponseTemplate::new(200).set_body_json(json!({
                "verifiablePresentation": { "verifiableCredential": [vc] },
                "referenceId": "ref-2"
            })))
            .expect(1)
            .mount(&server)
            .await;
        Mock::given(method("POST"))
            .and(path("/exchange"))
            .and(body_string_contains("ref-2"))
            .respond_with(ResponseTemplate::new(200).set_body_json(json!({})))
            .expect(1)
            .mount(&server)
            .await;

        let vdc = Arc::new(VdcCollection::new(Arc::new(LocalStore::new())));
        let holder = test_holder_with_vdc(vdc.clone()).await;

        holder
            .clone()
            .start_exchange(format!("{base}/exchange"), None)
            .await
            .expect("offer 1");
        let s2 = holder.clone().accept_offer().await.expect("accept 1");
        assert!(
            matches!(s2, StepResult::Offer { .. }),
            "second offer surfaced"
        );
        let s3 = holder.clone().accept_offer().await.expect("accept 2");
        assert_eq!(s3, StepResult::Complete);

        assert_eq!(
            vdc.all_entries().await.unwrap().len(),
            1,
            "re-accepting the same VC id overwrites, not duplicates"
        );
    }

    #[tokio::test]
    async fn accept_expired_stores_with_distinct_signal() {
        // Cryptographically valid but expired ⇒ still stored, surfaced distinctly.
        let server = MockServer::start().await;
        let base = server.uri();
        let expired = expired_offered_vc("urn:uuid:expired-1").await;

        Mock::given(method("POST"))
            .and(path("/exchange"))
            .and(body_json(json!({})))
            .respond_with(ResponseTemplate::new(200).set_body_json(json!({
                "verifiablePresentation": { "verifiableCredential": [expired] },
                "referenceId": "ref-1"
            })))
            .expect(1)
            .mount(&server)
            .await;
        Mock::given(method("POST"))
            .and(path("/exchange"))
            .and(body_string_contains("ref-1"))
            .respond_with(ResponseTemplate::new(200).set_body_json(json!({})))
            .expect(1)
            .mount(&server)
            .await;

        let vdc = Arc::new(VdcCollection::new(Arc::new(LocalStore::new())));
        let holder = test_holder_with_vdc(vdc.clone()).await;

        holder
            .clone()
            .start_exchange(format!("{base}/exchange"), None)
            .await
            .expect("offer step");

        // The distinct signal: the read-only preview flags it as time-bounded
        // BEFORE the user accepts.
        let preview = holder.offered_credentials().await.expect("preview");
        assert_eq!(preview.len(), 1);
        assert_eq!(
            preview[0].validity,
            OfferedValidity::TimeBounded,
            "an expired offered VC previews as time-bounded"
        );

        let step = holder.clone().accept_offer().await.expect("accept expired");
        assert_eq!(step, StepResult::Complete);
        assert_eq!(
            vdc.all_entries().await.unwrap().len(),
            1,
            "an expired-but-cryptographically-valid VC is still stored"
        );
    }

    #[tokio::test]
    async fn accept_with_no_offer_errors() {
        let holder = test_holder().await;
        let err = holder
            .accept_offer()
            .await
            .expect_err("accept with no offer must error");
        assert!(matches!(err, VcalmError::SessionState(_)));
    }

    #[tokio::test]
    async fn offered_credentials_empty_without_offer() {
        let holder = test_holder().await;
        let preview = holder.offered_credentials().await.expect("preview ok");
        assert!(
            preview.is_empty(),
            "no offer ⇒ empty preview, never an error"
        );
    }

    // --- two-gate selective disclosure (ecdsa-sd-2023) --------------------

    /// Build a dotted path for unit-testing the pointer transform.
    fn req_field(path: &str) -> String {
        path.to_string()
    }

    #[test]
    fn has_sd_base_proof_detects_object_array_and_rejects_others() {
        // Single proof object with the SD cryptosuite.
        assert!(has_sd_base_proof(&json!({
            "proof": { "type": "DataIntegrityProof", "cryptosuite": "ecdsa-sd-2023" }
        })));
        // Array of proofs, one of which is SD.
        assert!(has_sd_base_proof(&json!({
            "proof": [
                { "cryptosuite": "ecdsa-rdfc-2019" },
                { "cryptosuite": "ecdsa-sd-2023" }
            ]
        })));
        // No proof at all.
        assert!(!has_sd_base_proof(
            &json!({ "type": ["VerifiableCredential"] })
        ));
        // Non-SD proof only.
        assert!(!has_sd_base_proof(&json!({
            "proof": { "cryptosuite": "ecdsa-rdfc-2019" }
        })));
    }

    #[test]
    fn retain_presentable_proofs_is_a_b1_allowlist() {
        // A credential whose ONLY proof is an SD base proof is refused — never
        // presented proof-less, never leaking holder-secret base material (B.1).
        let err = retain_presentable_proofs(&json!({
            "type": ["VerifiableCredential"],
            "proof": { "type": "DataIntegrityProof", "cryptosuite": "ecdsa-sd-2023" }
        }))
        .unwrap_err();
        assert!(matches!(err, VcalmError::NoPresentableProof { .. }));

        // Mixed array ⇒ only allowlisted proofs survive; SD base proofs (incl.
        // bbs-2023) AND unknown proof types are dropped.
        let kept = retain_presentable_proofs(&json!({
            "proof": [
                { "type": "DataIntegrityProof", "cryptosuite": "ecdsa-sd-2023" },
                { "type": "DataIntegrityProof", "cryptosuite": "ecdsa-rdfc-2019" },
                { "type": "DataIntegrityProof", "cryptosuite": "bbs-2023" },
                { "type": "TotallyUnknownProof2099" }
            ]
        }))
        .unwrap();
        assert_eq!(
            kept["proof"],
            json!([{ "type": "DataIntegrityProof", "cryptosuite": "ecdsa-rdfc-2019" }])
        );

        // Allowlisted single proof ⇒ untouched, object shape preserved.
        let original = json!({
            "proof": { "type": "DataIntegrityProof", "cryptosuite": "eddsa-rdfc-2022" }
        });
        assert_eq!(retain_presentable_proofs(&original).unwrap(), original);

        // Pre-DI Ed25519 proof types are allowlisted.
        let original = json!({ "proof": { "type": "Ed25519Signature2020" } });
        assert_eq!(retain_presentable_proofs(&original).unwrap(), original);

        // Unknown-only proof ⇒ refused (default-deny, not default-allow).
        let err = retain_presentable_proofs(&json!({
            "type": ["VerifiableCredential"],
            "proof": { "type": "TotallyUnknownProof2099" }
        }))
        .unwrap_err();
        assert!(matches!(err, VcalmError::NoPresentableProof { .. }));

        // No proof at all ⇒ untouched (nothing to leak).
        let original = json!({ "type": ["VerifiableCredential"] });
        assert_eq!(retain_presentable_proofs(&original).unwrap(), original);
    }

    #[test]
    fn selective_pointers_transform_escapes_and_excludes_structural() {
        let requested = vec![
            req_field("type"),                        // structural — excluded
            req_field("@context"),                    // structural — excluded
            req_field("credentialSubject.givenName"), // → /credentialSubject/givenName
            req_field("credentialSubject.boards"),    // array parent pointer
            req_field("credentialSubject.a/b"),       // RFC 6901 `/` → ~1
            req_field("credentialSubject.c~d"),       // RFC 6901 `~` → ~0
        ];
        let pointers: Vec<String> = selective_pointers_from_paths(&requested)
            .iter()
            .map(|p| p.as_str().to_string())
            .collect();

        // Only credentialSubject pointers, no structural ones.
        assert_eq!(
            pointers.len(),
            4,
            "structural type/@context excluded, got {pointers:?}"
        );
        assert!(pointers.contains(&"/credentialSubject/givenName".to_string()));
        assert!(pointers.contains(&"/credentialSubject/boards".to_string()));
        // RFC 6901 escaping.
        assert!(pointers.contains(&"/credentialSubject/a~1b".to_string()));
        assert!(pointers.contains(&"/credentialSubject/c~0d".to_string()));
    }

    /// Issue an `ecdsa-sd-2023` base-proof VC in-test for the gate tests:
    /// P-256 issuer key, `did:key` Multikey VM, `AnySuite::EcdsaSd2023`
    /// with the issuer `mandatory_pointers`.
    async fn issue_sd_base_proof(
        unsecured: serde_json::Value,
        mandatory_pointers: &[&str],
    ) -> serde_json::Value {
        use ssi::claims::data_integrity::{
            AnyDataIntegrity, AnySignatureOptions, AnySuite, DataIntegrityDocument,
            ProofConfiguration,
        };
        use ssi::claims::SignatureEnvironment;
        use ssi::dids::{AnyDidMethod, DIDKey, DIDResolver};
        use ssi::prelude::CryptographicSuite;
        use ssi::verification_methods::SingleSecretSigner;
        use ssi::JWK;

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

    /// An SD-requesting QBE VPR (lists `ecdsa-sd-2023`) over a PermanentResidentCard,
    /// revealing only `credentialSubject.givenName`, bound to a challenge/domain.
    fn sd_qbe_vpr() -> Vpr {
        serde_json::from_value(json!({
            "query": [{
                "type": ["QueryByExample"],
                "credentialQuery": {
                    "reason": "We need your residency card.",
                    "example": {
                        "type": ["VerifiableCredential", "PermanentResidentCard"],
                        "credentialSubject": { "givenName": "" }
                    }
                }
            }],
            "challenge": "nonce-sd",
            "domain": "https://verifier.example",
            "acceptedCryptosuites": ["ecdsa-sd-2023"]
        }))
        .expect("valid SD QBE VPR")
    }

    fn parsed_ldp(raw: serde_json::Value) -> Arc<ParsedCredential> {
        let json_vc = JsonVc::new_from_json(raw.to_string()).expect("valid JsonVc");
        ParsedCredential::new_ldp_vc(json_vc)
    }

    async fn verify_vp(
        signed: &ssi::prelude::DataIntegrity<AnyJsonPresentation, ssi::prelude::AnySuite>,
    ) -> bool {
        use ssi::dids::{AnyDidMethod, DIDResolver};
        use ssi::prelude::VerificationParameters;
        let params =
            VerificationParameters::from_resolver(AnyDidMethod::default().into_vm_resolver());
        signed.verify(&params).await.expect("verify ran").is_ok()
    }

    /// Pull the first embedded `verifiableCredential` proof's cryptosuite from a signed VP JSON.
    fn embedded_vc_cryptosuite(vp: &serde_json::Value) -> Option<String> {
        let vc = match &vp["verifiableCredential"] {
            serde_json::Value::Array(a) => a.first().cloned(),
            v @ serde_json::Value::Object(_) => Some(v.clone()),
            _ => None,
        }?;
        let proof = match vc.get("proof")? {
            serde_json::Value::Array(a) => a.first().cloned()?,
            obj @ serde_json::Value::Object(_) => obj.clone(),
            _ => return None,
        };
        proof
            .get("cryptosuite")
            .and_then(|c| c.as_str())
            .map(|s| s.to_string())
    }

    /// Build a V2 PermanentResidentCard with `givenName` + `familyName`, then issue an
    /// `ecdsa-sd-2023` base proof over it (mandatory `/issuer`). `givenName` is the
    /// QBE-named field; `familyName` must NOT be disclosed.
    async fn sd_base_proof_v2(given_name: &str) -> serde_json::Value {
        issue_sd_base_proof(
            json!({
                "@context": [
                    "https://www.w3.org/ns/credentials/v2",
                    {
                        "givenName": "https://schema.org/givenName",
                        "familyName": "https://schema.org/familyName",
                        "PermanentResidentCard": "https://schema.org/PermanentResidentCard"
                    }
                ],
                "type": ["VerifiableCredential", "PermanentResidentCard"],
                "issuer": "https://issuer.example/",
                "credentialSubject": { "givenName": given_name, "familyName": "Doe" }
            }),
            &["/issuer"],
        )
        .await
    }

    #[tokio::test]
    async fn sd_gate_activates_when_both_gates_hold() {
        let holder = test_holder().await;
        let vpr = sd_qbe_vpr();
        let cred = parsed_ldp(sd_base_proof_v2("Jane").await);

        let signed = holder
            .build_and_sign_vp(&vpr, &[cred])
            .await
            .expect("SD VP must sign");
        let value = serde_json::to_value(&signed).unwrap();

        // GATE1 ∧ GATE2 ⇒ embedded VC carries an ecdsa-sd-2023 derived proof.
        assert_eq!(
            embedded_vc_cryptosuite(&value).as_deref(),
            Some("ecdsa-sd-2023"),
            "embedded credential must carry the SD derived proof, VP: {value}"
        );
        // Non-oversharing: givenName revealed, familyName absent.
        let vc = &value["verifiableCredential"];
        let vc0 = vc.as_array().and_then(|a| a.first()).unwrap_or(vc);
        assert_eq!(vc0["credentialSubject"]["givenName"], json!("Jane"));
        assert!(
            vc0["credentialSubject"].get("familyName").is_none(),
            "familyName must NOT be disclosed, got {:?}",
            vc0["credentialSubject"]
        );
        // The VP itself verifies (VP proof + embedded SD proof).
        assert!(verify_vp(&signed).await, "SD VP must verify end-to-end");
    }

    #[tokio::test]
    async fn sd_vp_binds_challenge_domain() {
        // The VP proof carries the VPR challenge/domain with
        // ProofPurpose::Authentication via the UNCHANGED sign_presentation, even on
        // the SD path (the VP proof stays ecdsa-rdfc-2019 — two-layer model).
        let holder = test_holder().await;
        let vpr = sd_qbe_vpr();
        let cred = parsed_ldp(sd_base_proof_v2("Jane").await);

        let signed = holder.build_and_sign_vp(&vpr, &[cred]).await.expect("sign");
        let value = serde_json::to_value(&signed).unwrap();
        let proof = &value["proof"];
        assert_eq!(proof["challenge"], json!("nonce-sd"));
        assert_eq!(proof["domain"], json!("https://verifier.example"));
        assert_eq!(proof["proofPurpose"], json!("authentication"));
        // The VP-proof suite stays ecdsa-rdfc-2019 (SD is on the VC, not the VP).
        assert_eq!(proof["cryptosuite"], json!("ecdsa-rdfc-2019"));
    }

    #[tokio::test]
    async fn sd_falls_back_to_full_disclosure_when_no_sd_base_proof() {
        // SD requested, but the matched VC carries only an ecdsa-rdfc-2019 proof
        // (here: no proof at all) ⇒ a signed FULL VP, not an error. The embedded VC
        // is the full credential (no ecdsa-sd-2023 proof).
        let holder = test_holder().await;
        let did = holder_did();
        let vpr = sd_qbe_vpr();
        let cred = parsed_ldp(v2_credential(&did, "Jane")); // full-disclosure VC, no SD proof

        let signed = holder
            .build_and_sign_vp(&vpr, &[cred])
            .await
            .expect("SD-unsatisfiable VPR must still sign a full VP, not error");
        let value = serde_json::to_value(&signed).unwrap();
        assert_ne!(
            embedded_vc_cryptosuite(&value).as_deref(),
            Some("ecdsa-sd-2023"),
            "no SD base proof ⇒ no SD derived proof on the embedded VC (fallback)"
        );
        assert_eq!(value["proof"]["proofPurpose"], json!("authentication"));
    }

    #[tokio::test]
    async fn sd_derive_error_is_a_hard_error_not_silent_full_disclosure() {
        // GATE2 passes (a proof claims cryptosuite ecdsa-sd-2023) but the proof
        // is bogus, so derive_sd_vp_credential errors ⇒ a typed SdDeriveFailed.
        // The user consented to the SD subset; silently widening to full
        // disclosure would be an over-share.
        let holder = test_holder().await;
        let did = holder_did();
        let mut bogus = v2_credential(&did, "Jane");
        bogus["proof"] = json!({
            "type": "DataIntegrityProof",
            "cryptosuite": "ecdsa-sd-2023",
            "created": "2024-01-01T00:00:00Z",
            "verificationMethod": "did:key:zDnaeBOGUS#zDnaeBOGUS",
            "proofPurpose": "assertionMethod",
            "proofValue": "u_not_a_real_base_proof"
        });
        let cred = parsed_ldp(bogus);
        let vpr = sd_qbe_vpr();

        let err = holder
            .build_and_sign_vp(&vpr, &[cred])
            .await
            .expect_err("a derive error must surface, never silently over-disclose");
        assert!(matches!(err, VcalmError::SdDeriveFailed(_)));
    }

    #[tokio::test]
    async fn sd_gate_v1_end_to_end() {
        // The two-gate SD path works for a VCDM v1 credential too.
        let holder = test_holder().await;
        let v1 = issue_sd_base_proof(
            json!({
                "@context": [
                    "https://www.w3.org/2018/credentials/v1",
                    "https://w3id.org/security/data-integrity/v2",
                    {
                        "givenName": "https://schema.org/givenName",
                        "familyName": "https://schema.org/familyName",
                        "PermanentResidentCard": "https://schema.org/PermanentResidentCard"
                    }
                ],
                "type": ["VerifiableCredential", "PermanentResidentCard"],
                "issuer": "https://issuer.example/",
                "issuanceDate": "2024-01-01T00:00:00Z",
                "credentialSubject": { "givenName": "Jane", "familyName": "Doe" }
            }),
            &["/issuer"],
        )
        .await;
        let cred = parsed_ldp(v1);
        let vpr = sd_qbe_vpr();

        let signed = holder
            .build_and_sign_vp(&vpr, &[cred])
            .await
            .expect("v1 SD VP signs");
        let value = serde_json::to_value(&signed).unwrap();
        assert_eq!(
            embedded_vc_cryptosuite(&value).as_deref(),
            Some("ecdsa-sd-2023"),
            "v1 embedded VC must carry the SD derived proof"
        );
        assert!(verify_vp(&signed).await, "v1 SD VP must verify end-to-end");
    }
}
