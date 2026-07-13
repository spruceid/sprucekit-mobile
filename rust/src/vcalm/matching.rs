use serde_json::Value;

use super::exchange::{
    AcceptedIssuerEntry, AcceptedMethodEntry, CredentialQuery, CryptosuiteEntry, Query,
};

/// The VCALM 1.0 query `type` values (§3.4.2/§3.4.3). Anything else is unknown
/// and unsatisfiable for matching.
const QUERY_BY_EXAMPLE: &str = "QueryByExample";
const DID_AUTHENTICATION: &str = "DIDAuthentication";

// ---------------------------------------------------------------------------
// Per-query QueryByExample matcher (§3.4.2)
// ---------------------------------------------------------------------------

/// Recursive subset: every key/element in `want` must be satisfied by `have`.
///
/// - object: every key in `want` must exist in `have` and recurse (§3.4.2
///   "every field in the example must be present").
/// - array vs array: every wanted element must match *some* element in `have`
///   (subset).
/// - JSON-LD set normalization: a scalar/object `want` matches an array `have`
///   when ANY element matches (multi-subject credentials, array-valued claims);
///   an array `want` matches a scalar `have` when every wanted element matches
///   it (the scalar is a one-element set).
/// - leaf `""` (empty string): the field must be present, any value
///   (§3.4.2 "leave the value as an empty string"). A present key is guaranteed
///   by the object arm's `.get(k)`, so an empty-string leaf is unconditionally
///   satisfied here; an absent key already failed in the parent object arm.
/// - leaf non-empty: values must be equal.
fn value_is_subset(want: &Value, have: &Value) -> bool {
    match (want, have) {
        // "" leaf => present-with-any-value (§3.4.2). Presence is enforced by the
        // object arm's `.get(k)`; reaching here means the key is present.
        (Value::String(s), _) if s.is_empty() => true,
        (Value::Object(w), Value::Object(h)) => w
            .iter()
            .all(|(k, wv)| h.get(k).map(|hv| value_is_subset(wv, hv)).unwrap_or(false)),
        (Value::Array(w), Value::Array(h)) => {
            // every wanted element must appear (subset) somewhere in `have`.
            w.iter()
                .all(|we| h.iter().any(|he| value_is_subset(we, he)))
        }
        // JSON-LD treats a scalar and a one-element set interchangeably: a
        // non-array `want` is satisfied by ANY element of an array `have`
        // (e.g. `credentialSubject` example object vs a multi-subject array,
        // or a single wanted claim value vs an array-valued claim).
        (w, Value::Array(h)) => h.iter().any(|he| value_is_subset(w, he)),
        // …and an array `want` against a scalar `have` normalizes `have` to a
        // one-element set: every wanted element must match it.
        (Value::Array(w), h) => w.iter().all(|we| value_is_subset(we, h)),
        // leaf equality (covers strings, numbers, bools, null, and type mismatches).
        _ => want == have,
    }
}

/// `type`/`@context` are a string or an array of strings: every wanted entry must
/// appear in the VC's array. A scalar value is normalized to a single-element vec.
fn array_subset(want: &Value, have: &Value) -> bool {
    fn to_vec(v: &Value) -> Vec<Value> {
        match v {
            Value::Array(a) => a.clone(),
            other => vec![other.clone()],
        }
    }
    let h = to_vec(have);
    to_vec(want).iter().all(|w| h.contains(w))
}

/// Extract the matchable issuer string from an accepted-issuer entry.
///
/// - bare string -> `Some(s)`.
/// - `{id}` -> `Some(id)`; `{issuer}` -> `Some(issuer)`.
/// - `{recognizedIn}` -> `None` — recognized syntactically but not resolved,
///   so the entry never matches.
fn accepted_issuer_value(item: &AcceptedIssuerEntry) -> Option<String> {
    match item {
        AcceptedIssuerEntry::Id(s) => Some(s.clone()),
        AcceptedIssuerEntry::Object {
            recognized_in: Some(_),
            ..
        } => None, // recognizedIn is not resolved — never matches.
        AcceptedIssuerEntry::Object {
            id,
            issuer,
            recognized_in: None,
        } => id.clone().or_else(|| issuer.clone()),
    }
}

/// Extract the VC's issuer identifier: a bare string `issuer`, or the `id` of an
/// issuer object. Uses `.get(...)` so a missing/odd shape yields `None`, never a panic.
/// Shared with [`super::issuance::stable_local_id`] (issuer-scoped storage ids).
pub(crate) fn vc_issuer(vc: &Value) -> Option<String> {
    match vc.get("issuer")? {
        Value::String(s) => Some(s.clone()),
        Value::Object(o) => o.get("id").and_then(|v| v.as_str()).map(str::to_string),
        _ => None,
    }
}

/// Returns `true` if the decoded VC `vc_raw` matches a single QueryByExample
/// `query` (§3.4.2).
///
/// A match requires ALL of:
/// 1. `example.type` subset of the VC `type` array;
/// 2. `example.@context` subset of the VC `@context` array;
/// 3. recursive `credentialSubject` subset (`""` = present, every named field required);
/// 4. every OTHER example-named property (e.g. `credentialStatus`) present and
///    matching, recursively (`""` = present with any value);
/// 5. the issuer filter — if `acceptedIssuers`/`trustedIssuer` is present and
///    non-empty, the VC issuer must string-equal (RAW, no URL normalization) some
///    accepted-issuer value; absent -> unconstrained.
///
/// Absent `example`, or an absent sub-field within it, makes that step vacuously
/// true (only the present constraints bind). NEVER returns an error or panics — a
/// no-match is simply `false`.
pub(crate) fn example_matches(query: &CredentialQuery, vc_raw: &Value) -> bool {
    if let Some(example) = &query.example {
        // 1. type subset (skipped when the example omits `type`).
        if let Some(want_type) = example.get("type") {
            let have_type = vc_raw.get("type").unwrap_or(&Value::Null);
            if !array_subset(want_type, have_type) {
                return false;
            }
        }
        // 2. @context subset (skipped when the example omits `@context`).
        if let Some(want_ctx) = example.get("@context") {
            let have_ctx = vc_raw.get("@context").unwrap_or(&Value::Null);
            if !array_subset(want_ctx, have_ctx) {
                return false;
            }
        }
        // 3. recursive credentialSubject subset (skipped when the example omits it).
        if let Some(want_subject) = example.get("credentialSubject") {
            let have_subject = vc_raw.get("credentialSubject").unwrap_or(&Value::Null);
            if !value_is_subset(want_subject, have_subject) {
                return false;
            }
        }
        // 4. every OTHER example-named property (e.g. `credentialStatus`) must be
        //    present in the VC and match (§3.4.2 — the example names what the
        //    credential must contain; `""` = present with any value).
        if let Some(obj) = example.as_object() {
            for (key, want) in obj {
                if matches!(key.as_str(), "type" | "@context" | "credentialSubject") {
                    continue;
                }
                let Some(have) = vc_raw.get(key) else {
                    return false;
                };
                if !value_is_subset(want, have) {
                    return false;
                }
            }
        }
    }

    // 4. issuer filter: acceptedIssuers ∪ trustedIssuer.
    let accepted: Vec<&AcceptedIssuerEntry> = query
        .accepted_issuers
        .iter()
        .flatten()
        .chain(query.trusted_issuer.iter().flatten())
        .collect();
    if !accepted.is_empty() {
        match vc_issuer(vc_raw) {
            Some(issuer) => accepted
                .iter()
                .filter_map(|item| accepted_issuer_value(item))
                .any(|accepted| accepted == issuer),
            None => false,
        }
    } else {
        true
    }
}

// ---------------------------------------------------------------------------
// §3.4.5 AND/OR grouping resolution
// ---------------------------------------------------------------------------

/// The recognized kind of a single query (§3.4.2/§3.4.3). Anything outside VCALM
/// 1.0 is [`QueryKind::Unknown`] and unsatisfiable for matching.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum QueryKind {
    /// `QueryByExample` (§3.4.2) — satisfied iff a stored VC matches.
    QueryByExample,
    /// `DIDAuthentication` (§3.4.3) — always satisfiable, contributes no VC.
    DidAuthentication,
    /// Any other (DCQL, PEX, …) — unsatisfiable, never an error.
    Unknown,
}

/// Classify a query by its declared `type`. The first recognized entry wins; a
/// query with no recognized type is [`QueryKind::Unknown`].
pub(crate) fn query_kind(query: &Query) -> QueryKind {
    for t in &query.r#type {
        if t == QUERY_BY_EXAMPLE {
            return QueryKind::QueryByExample;
        }
        if t == DID_AUTHENTICATION {
            return QueryKind::DidAuthentication;
        }
    }
    QueryKind::Unknown
}

/// `required` defaults to `true` when absent.
fn is_required(query: &Query) -> bool {
    query.required.unwrap_or(true)
}

/// One AND-group of queries (§3.4.5): every `required` member must be satisfiable.
///
/// `members` are indices into the original `&[Query]` slice. Queries sharing the
/// same `group` value form one [`AndGroup`]; a query with no `group` is its own
/// singleton OR-alternative.
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct AndGroup {
    /// The `group` value shared by every member, or `None` for a singleton
    /// (no-group) OR-alternative.
    pub(crate) group: Option<String>,
    /// Indices into the source `&[Query]` slice, in first-seen order.
    pub(crate) members: Vec<usize>,
}

/// Partition `queries` into AND-groups (§3.4.5): each distinct `group` value
/// is one [`AndGroup`] (preserving first-seen order); every query with no
/// `group` becomes its own singleton OR-alternative.
pub(crate) fn partition_groups(queries: &[Query]) -> Vec<AndGroup> {
    let mut groups: Vec<AndGroup> = Vec::new();
    for (idx, query) in queries.iter().enumerate() {
        match &query.group {
            // Same `group` value joins the existing AND-group; a new value starts one.
            Some(g) => match groups
                .iter_mut()
                .find(|grp| grp.group.as_deref() == Some(g))
            {
                Some(existing) => existing.members.push(idx),
                None => groups.push(AndGroup {
                    group: Some(g.clone()),
                    members: vec![idx],
                }),
            },
            // No group -> its own singleton OR-alternative.
            None => groups.push(AndGroup {
                group: None,
                members: vec![idx],
            }),
        }
    }
    groups
}

/// Returns `true` if one AND-group is fully satisfiable (§3.4.5).
///
/// A group is fully satisfiable iff every `required` member is satisfiable. A
/// `required: false` member that is unsatisfiable is dropped. An empty group
/// (no members, never produced by [`partition_groups`]) is vacuously satisfiable.
pub(crate) fn group_is_satisfiable(
    group: &AndGroup,
    queries: &[Query],
    mut query_satisfiable: impl FnMut(usize) -> bool,
) -> bool {
    group.members.iter().all(|&idx| {
        // A `required: false` query never blocks the group; a required one
        // (incl. the default-true case) must be satisfiable.
        match queries.get(idx) {
            Some(query) if is_required(query) => query_satisfiable(idx),
            _ => true,
        }
    })
}

/// The resolver's verdict over a whole VPR `query[]` (§3.4.5).
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct GroupResolution {
    /// All AND-groups, in first-seen order (the holder may override the default).
    pub(crate) groups: Vec<AndGroup>,
    /// Index (into `groups`) of the selected AND-group, or `None` if none is
    /// satisfiable. Preferred: a fully-satisfiable group with a SATISFIED
    /// QueryByExample member (first in first-seen order); else the first
    /// satisfiable group. Rationale: spec Example Workflow 1 sends ungrouped
    /// `DIDAuthentication` + `QueryByExample` (OR-alternatives per §3.4.5.2) but
    /// its `presentationSchema` requires `verifiableCredential` — so a leading
    /// always-satisfiable DIDAuth singleton must not eclipse a credential-bearing
    /// alternative the holder can satisfy.
    pub(crate) selected: Option<usize>,
}

impl GroupResolution {
    /// The request is satisfiable iff at least one AND-group is fully satisfiable.
    pub(crate) fn is_satisfiable(&self) -> bool {
        self.selected.is_some()
    }
}

/// Resolve §3.4.5 grouping over `queries`: partition into AND-groups, then select
/// the default group — the first fully-satisfiable group containing a
/// satisfied QueryByExample member if one exists, otherwise the first
/// fully-satisfiable group.
/// `query_satisfiable(idx)` reports per-query satisfiability (the holder supplies
/// one that runs QBE matching and treats DIDAuthentication as always satisfiable /
/// Unknown as never).
pub(crate) fn resolve_groups(
    queries: &[Query],
    mut query_satisfiable: impl FnMut(usize) -> bool,
) -> GroupResolution {
    let groups = partition_groups(queries);
    let satisfiable: Vec<usize> = groups
        .iter()
        .enumerate()
        .filter(|(_, g)| group_is_satisfiable(g, queries, &mut query_satisfiable))
        .map(|(idx, _)| idx)
        .collect();
    let selected = satisfiable
        .iter()
        .copied()
        .find(|&idx| {
            groups[idx].members.iter().any(|&m| {
                queries.get(m).is_some_and(|q| {
                    query_kind(q) == QueryKind::QueryByExample && query_satisfiable(m)
                })
            })
        })
        .or_else(|| satisfiable.first().copied());
    GroupResolution { groups, selected }
}

/// The single VP-proof cryptosuite this holder can produce. Mirrors
/// `presentation::ECDSA_RDFC_2019`; kept local so matching stays
/// presentation-independent.
const SUPPORTED_VP_CRYPTOSUITE: &str = "ecdsa-rdfc-2019";

/// The single DID method this holder can authenticate with.
const SUPPORTED_DID_METHOD: &str = "key";

/// §3.4.3.1/.2: whether THIS holder can satisfy a DIDAuthentication query's
/// constraints. `acceptedMethods` absent/empty, or listing `key`, is
/// satisfiable; `acceptedCryptosuites` (query-level) absent/empty, or listing
/// `ecdsa-rdfc-2019`, is satisfiable. A DIDAuthentication query whose lists
/// exclude both is NOT satisfiable — the group resolver routes around it
/// instead of signing a response the spec says the verifier must reject.
pub(crate) fn didauth_constraints_supported(query: &Query) -> bool {
    let methods_ok = match &query.accepted_methods {
        None => true,
        Some(methods) if methods.is_empty() => true,
        Some(methods) => methods.iter().any(|m| {
            let name = match m {
                AcceptedMethodEntry::Name(name) => name.as_str(),
                AcceptedMethodEntry::Object { method } => method.as_str(),
            };
            name == SUPPORTED_DID_METHOD
        }),
    };
    let suites_ok = match &query.accepted_cryptosuites {
        None => true,
        Some(suites) if suites.is_empty() => true,
        Some(suites) => suites.iter().any(|entry| {
            let name = match entry {
                CryptosuiteEntry::Name(name) => name.as_str(),
                CryptosuiteEntry::Object { cryptosuite } => cryptosuite.as_str(),
            };
            name == SUPPORTED_VP_CRYPTOSUITE
        }),
    };
    methods_ok && suites_ok
}

/// Per-query satisfiability from the query kind alone, given whether a QBE query
/// has at least one matched credential. DIDAuthentication is satisfiable iff its
/// `acceptedMethods`/`acceptedCryptosuites` constraints don't exclude this
/// holder ([`didauth_constraints_supported`]); Unknown is never satisfiable.
/// The holder computes `qbe_has_match` by running [`example_matches`] over
/// stored VCs.
pub(crate) fn query_satisfiable_by_kind(query: &Query, qbe_has_match: bool) -> bool {
    match query_kind(query) {
        QueryKind::QueryByExample => qbe_has_match,
        QueryKind::DidAuthentication => didauth_constraints_supported(query),
        QueryKind::Unknown => false,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn cq_example(example: Value) -> CredentialQuery {
        CredentialQuery {
            example: Some(example),
            ..Default::default()
        }
    }

    #[test]
    fn type_subset() {
        let vc = json!({
            "type": ["VerifiableCredential", "PermanentResidentCard"],
            "credentialSubject": {}
        });
        // Wanted type is a subset of the VC's type array -> match.
        let q = cq_example(json!({ "type": ["PermanentResidentCard"] }));
        assert!(example_matches(&q, &vc));
        // A type not present in the VC -> no match.
        let q = cq_example(json!({ "type": ["AlumniCredential"] }));
        assert!(!example_matches(&q, &vc));
        // Scalar (non-array) example type normalizes to a single-element subset.
        let q = cq_example(json!({ "type": "VerifiableCredential" }));
        assert!(example_matches(&q, &vc));
    }

    #[test]
    fn context_subset() {
        let vc = json!({
            "@context": ["https://www.w3.org/ns/credentials/v2", "https://example.com/ctx"],
            "type": ["VerifiableCredential"],
            "credentialSubject": {}
        });
        let q = cq_example(json!({ "@context": ["https://www.w3.org/ns/credentials/v2"] }));
        assert!(example_matches(&q, &vc));
        // A context entry the VC lacks -> no match.
        let q = cq_example(json!({ "@context": ["https://other.example/ctx"] }));
        assert!(!example_matches(&q, &vc));
    }

    #[test]
    fn json_ld_set_normalization_scalar_vs_array() {
        // Multi-subject credential: `credentialSubject` is an ARRAY of subjects;
        // an example OBJECT matches when ANY subject satisfies it.
        let vc = json!({
            "type": ["VerifiableCredential"],
            "credentialSubject": [
                { "name": "Alice", "role": "driver" },
                { "name": "Bob", "role": "owner" }
            ]
        });
        let q = cq_example(json!({ "credentialSubject": { "role": "owner" } }));
        assert!(example_matches(&q, &vc), "any-subject match");
        let q = cq_example(json!({ "credentialSubject": { "role": "pilot" } }));
        assert!(!example_matches(&q, &vc));

        // Array-valued claim: wanted scalar matches ANY element.
        let vc = json!({
            "type": ["VerifiableCredential"],
            "credentialSubject": { "boards": ["alpha", "beta"] }
        });
        let q = cq_example(json!({ "credentialSubject": { "boards": "beta" } }));
        assert!(example_matches(&q, &vc), "scalar-vs-array claim match");

        // Wanted array vs scalar claim: the scalar is a one-element set.
        let vc = json!({
            "type": ["VerifiableCredential"],
            "credentialSubject": { "board": "alpha" }
        });
        let q = cq_example(json!({ "credentialSubject": { "board": ["alpha"] } }));
        assert!(example_matches(&q, &vc), "array-vs-scalar claim match");
        let q = cq_example(json!({ "credentialSubject": { "board": ["alpha", "beta"] } }));
        assert!(!example_matches(&q, &vc), "two wanted values, one present");
    }

    #[test]
    fn didauth_constraints_gate_satisfiability() {
        // Absent/empty lists, or lists naming what this holder produces, are
        // satisfiable; lists excluding did:key / ecdsa-rdfc-2019 are not.
        let q = |v: serde_json::Value| -> Query { serde_json::from_value(v).unwrap() };

        let plain = q(json!({ "type": "DIDAuthentication" }));
        assert!(query_satisfiable_by_kind(&plain, false));

        let with_key = q(json!({
            "type": "DIDAuthentication",
            "acceptedMethods": [{"method": "key"}, "web"]
        }));
        assert!(query_satisfiable_by_kind(&with_key, false));

        let web_only = q(json!({
            "type": "DIDAuthentication",
            "acceptedMethods": ["web"]
        }));
        assert!(
            !query_satisfiable_by_kind(&web_only, false),
            "acceptedMethods excluding `key` is unsatisfiable — never answered \
             with a holder DID the verifier must reject"
        );

        let sd_suite_only = q(json!({
            "type": "DIDAuthentication",
            "acceptedCryptosuites": ["bbs-2023"]
        }));
        assert!(
            !query_satisfiable_by_kind(&sd_suite_only, false),
            "acceptedCryptosuites excluding ecdsa-rdfc-2019 is unsatisfiable"
        );

        let rdfc_listed = q(json!({
            "type": "DIDAuthentication",
            "acceptedCryptosuites": [{"cryptosuite": "ecdsa-rdfc-2019"}]
        }));
        assert!(query_satisfiable_by_kind(&rdfc_listed, false));
    }

    #[test]
    fn credential_subject_recursive() {
        let vc = json!({
            "type": ["VerifiableCredential"],
            "credentialSubject": {
                "name": "Alice",
                "address": { "country": "US", "city": "NYC" }
            }
        });
        // Nested subset: country matches, deeper key recursion holds.
        let q = cq_example(json!({
            "credentialSubject": { "address": { "country": "US" } }
        }));
        assert!(example_matches(&q, &vc));
        // Nested value mismatch -> no match.
        let q = cq_example(json!({
            "credentialSubject": { "address": { "country": "CA" } }
        }));
        assert!(!example_matches(&q, &vc));
        // A wanted nested key the VC lacks -> no match.
        let q = cq_example(json!({
            "credentialSubject": { "address": { "zip": "10001" } }
        }));
        assert!(!example_matches(&q, &vc));
    }

    #[test]
    fn empty_string_means_present() {
        let vc = json!({
            "type": ["VerifiableCredential"],
            "credentialSubject": { "givenName": "Jane" }
        });
        // "" => present with any value -> match.
        let q = cq_example(json!({ "credentialSubject": { "givenName": "" } }));
        assert!(example_matches(&q, &vc));
        // "" but the field is ABSENT -> no match (§3.4.2).
        let q = cq_example(json!({ "credentialSubject": { "familyName": "" } }));
        assert!(!example_matches(&q, &vc));
        // "" matches a non-string value too (any value present).
        let vc_num = json!({
            "type": ["VerifiableCredential"],
            "credentialSubject": { "age": 42 }
        });
        let q = cq_example(json!({ "credentialSubject": { "age": "" } }));
        assert!(example_matches(&q, &vc_num));
    }

    #[test]
    fn issuer_filter_shapes() {
        let vc = json!({
            "type": ["VerifiableCredential"],
            "issuer": "did:web:red-issuer.example",
            "credentialSubject": {}
        });
        let with_issuers = |issuers: Vec<AcceptedIssuerEntry>| CredentialQuery {
            example: Some(json!({ "type": ["VerifiableCredential"] })),
            accepted_issuers: Some(issuers),
            ..Default::default()
        };

        // bare string accepted
        assert!(example_matches(
            &with_issuers(vec![AcceptedIssuerEntry::Id(
                "did:web:red-issuer.example".into()
            )]),
            &vc
        ));
        // {id} accepted
        assert!(example_matches(
            &with_issuers(vec![AcceptedIssuerEntry::Object {
                id: Some("did:web:red-issuer.example".into()),
                issuer: None,
                recognized_in: None,
            }]),
            &vc
        ));
        // {issuer} accepted
        assert!(example_matches(
            &with_issuers(vec![AcceptedIssuerEntry::Object {
                id: None,
                issuer: Some("did:web:red-issuer.example".into()),
                recognized_in: None,
            }]),
            &vc
        ));
        // {recognizedIn} never matches (not resolved)
        assert!(!example_matches(
            &with_issuers(vec![AcceptedIssuerEntry::Object {
                id: None,
                issuer: None,
                recognized_in: Some(json!({ "type": "VerifiableRecognitionCredential" })),
            }]),
            &vc
        ));
        // a different issuer -> no match (RAW equality; trailing slash differs)
        assert!(!example_matches(
            &with_issuers(vec![AcceptedIssuerEntry::Id(
                "did:web:red-issuer.example/".into()
            )]),
            &vc
        ));
        // issuer object .id on the VC side is also read
        let vc_obj = json!({
            "type": ["VerifiableCredential"],
            "issuer": { "id": "did:web:red-issuer.example", "name": "Red" },
            "credentialSubject": {}
        });
        assert!(example_matches(
            &with_issuers(vec![AcceptedIssuerEntry::Id(
                "did:web:red-issuer.example".into()
            )]),
            &vc_obj
        ));
    }

    #[test]
    fn trusted_issuer_alias() {
        let vc = json!({
            "type": ["VerifiableCredential"],
            "issuer": "did:web:blue-issuer.example",
            "credentialSubject": {}
        });
        // trustedIssuer is honored exactly like acceptedIssuers.
        let q = CredentialQuery {
            example: Some(json!({ "type": ["VerifiableCredential"] })),
            trusted_issuer: Some(vec![AcceptedIssuerEntry::Id(
                "did:web:blue-issuer.example".into(),
            )]),
            ..Default::default()
        };
        assert!(example_matches(&q, &vc));
        // trustedIssuer that doesn't match -> no match.
        let q = CredentialQuery {
            example: Some(json!({ "type": ["VerifiableCredential"] })),
            trusted_issuer: Some(vec![AcceptedIssuerEntry::Id(
                "did:web:other.example".into(),
            )]),
            ..Default::default()
        };
        assert!(!example_matches(&q, &vc));
    }

    #[test]
    fn no_match_is_empty() {
        // Wholly unrelated VC + a malformed/empty VC must yield `false`, never panic.
        let q = cq_example(json!({
            "type": ["PermanentResidentCard"],
            "credentialSubject": { "givenName": "" }
        }));
        assert!(!example_matches(
            &q,
            &json!({ "type": ["AlumniCredential"] })
        ));
        assert!(!example_matches(&q, &json!({})));
        assert!(!example_matches(&q, &json!(null)));
        // An absent example + no issuer filter is vacuously a match (only present
        // constraints bind) — proves we never over-restrict, and never panic.
        assert!(example_matches(&CredentialQuery::default(), &json!({})));
    }

    fn q(kind: &str, group: Option<&str>, required: Option<bool>) -> Query {
        Query {
            r#type: vec![kind.to_string()],
            group: group.map(str::to_string),
            required,
            ..Default::default()
        }
    }

    #[test]
    fn unknown_type_unsatisfiable() {
        // Unknown type classifies as Unknown and is never satisfiable.
        let dcql = q("DigitalCredentialQueryLanguage", None, None);
        assert_eq!(query_kind(&dcql), QueryKind::Unknown);
        assert!(!query_satisfiable_by_kind(&dcql, true)); // never, even if "matched"

        let queries = vec![
            q("QueryByExample", Some("g"), None),
            q("DigitalCredentialQueryLanguage", Some("g"), None),
        ];
        let mut by_kind =
            |idx: usize| query_satisfiable_by_kind(&queries[idx], /* qbe match */ true);
        let res = resolve_groups(&queries, &mut by_kind);
        assert!(
            !res.is_satisfiable(),
            "required unknown blocks its AND-group"
        );

        assert_eq!(query_kind(&Query::default()), QueryKind::Unknown);
    }

    #[test]
    fn grouping_and_or() {
        // AND: two queries sharing group "certification".
        let and_queries = vec![
            q("QueryByExample", Some("certification"), None),
            q("QueryByExample", Some("certification"), None),
        ];
        let groups = partition_groups(&and_queries);
        assert_eq!(groups.len(), 1, "same group = one AND-group");
        assert_eq!(groups[0].members, vec![0, 1]);
        // Only the first query matches -> group not fully satisfiable.
        let res = resolve_groups(&and_queries, |idx| idx == 0);
        assert!(!res.is_satisfiable());
        // Both match -> satisfiable, group 0 selected.
        let res = resolve_groups(&and_queries, |_| true);
        assert!(res.is_satisfiable());
        assert_eq!(res.selected, Some(0));

        // OR: differing groups -> separate AND-groups (OR alternatives).
        let or_queries = vec![
            q("QueryByExample", Some("college-degree"), None),
            q("QueryByExample", Some("job-experience"), None),
        ];
        let groups = partition_groups(&or_queries);
        assert_eq!(groups.len(), 2, "differing groups = OR alternatives");
        // Only the SECOND alternative is satisfiable -> request satisfiable via it.
        let res = resolve_groups(&or_queries, |idx| idx == 1);
        assert!(res.is_satisfiable());
        assert_eq!(res.selected, Some(1));
        // Neither satisfiable -> not satisfiable.
        let res = resolve_groups(&or_queries, |_| false);
        assert!(!res.is_satisfiable());

        // Absent group -> each query is its own singleton OR-alternative.
        let no_group = vec![
            q("QueryByExample", None, None),
            q("QueryByExample", None, None),
        ];
        let groups = partition_groups(&no_group);
        assert_eq!(groups.len(), 2);
        assert!(groups
            .iter()
            .all(|g| g.group.is_none() && g.members.len() == 1));
    }

    #[test]
    fn required_false_optional_query_does_not_block_group() {
        // An AND-group with a satisfiable required query and an unsatisfiable
        // `required: false` query is still fully satisfiable.
        let queries = vec![
            q("QueryByExample", Some("g"), Some(true)),
            q("QueryByExample", Some("g"), Some(false)),
        ];
        let groups = partition_groups(&queries);
        assert_eq!(groups.len(), 1);
        // Index 0 (required) matches; index 1 (optional) does not.
        let res = resolve_groups(&queries, |idx| idx == 0);
        assert!(
            res.is_satisfiable(),
            "required:false unsatisfiable query is dropped, group still satisfies"
        );
        assert_eq!(res.selected, Some(0));

        // But if the REQUIRED query is unsatisfiable, the group fails regardless.
        let res = resolve_groups(&queries, |idx| idx == 1);
        assert!(!res.is_satisfiable());
    }

    #[test]
    fn didauthentication_always_satisfiable() {
        // A DIDAuthentication query is satisfied implicitly (no VC); its match
        // closure value is irrelevant.
        let did = q("DIDAuthentication", None, None);
        assert_eq!(query_kind(&did), QueryKind::DidAuthentication);
        assert!(query_satisfiable_by_kind(&did, false));

        // A DIDAuthentication-only request is satisfiable with no credentials.
        let queries = vec![did];
        let res = resolve_groups(&queries, |idx| {
            query_satisfiable_by_kind(&queries[idx], /* no VC match */ false)
        });
        assert!(res.is_satisfiable());
        assert_eq!(res.selected, Some(0));
    }

    #[test]
    fn satisfied_qbe_group_preferred_over_bare_didauth() {
        // Spec Example Workflow 1 shape: ungrouped DIDAuthentication BEFORE an
        // ungrouped QueryByExample — formally OR-alternatives (§3.4.5.2), but the
        // workflow's presentationSchema requires credentials in the response. With
        // a matching credential, the QBE alternative must win even though the
        // always-satisfiable DIDAuth singleton appears first.
        let queries = vec![
            q("DIDAuthentication", None, None),
            q("QueryByExample", None, None),
        ];
        let res = resolve_groups(&queries, |idx| {
            query_satisfiable_by_kind(&queries[idx], /* qbe match */ true)
        });
        assert_eq!(res.selected, Some(1), "satisfied QBE group preferred");

        // No credential matches the QBE -> fall back to the DIDAuth singleton
        // (first satisfiable group, the pre-existing default).
        let res = resolve_groups(&queries, |idx| {
            query_satisfiable_by_kind(&queries[idx], /* qbe match */ false)
        });
        assert_eq!(res.selected, Some(0), "DIDAuth fallback when QBE unmatched");
    }

    #[test]
    fn required_defaults_to_true() {
        // Absent `required` -> true: an unsatisfiable required-by-default query
        // blocks its group.
        let queries = vec![q("QueryByExample", Some("g"), None)];
        let res = resolve_groups(&queries, |_| false);
        assert!(!res.is_satisfiable(), "absent required defaults to true");
    }
}
