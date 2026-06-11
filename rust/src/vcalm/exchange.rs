use serde::{Deserialize, Serialize};
use serde_json::Value as JsonValue;
use url::Url;

use super::error::VcalmError;

uniffi::custom_type!(JsonValue, String, {
    remote,
    try_lift: |s| Ok(serde_json::from_str(&s)?),
    lower: |v| v.to_string(),
});

/// The symmetric `vcapi` message envelope.
///
/// The same shape is sent by the holder and received from the exchange server.
/// Every field is omitted from the wire when `None` (`skip_serializing_if`), so an
/// all-`None` message serializes to `{}` — the body the holder POSTs to begin an
/// exchange.
#[derive(Debug, Clone, Serialize, Deserialize, Default, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct VcapiMessage {
    /// A request for a verifiable presentation from the server.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub verifiable_presentation_request: Option<Vpr>,
    /// An offered verifiable presentation. Opaque, UNVERIFIED JSON.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub verifiable_presentation: Option<serde_json::Value>,
    /// A terminal redirect target surfaced to the caller (never auto-followed).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub redirect_url: Option<Url>,
    /// Opaque correlation id echoed back to the server on the next request.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub reference_id: Option<String>,
}

/// Deserialize a field that may arrive as a single value OR an array of values
/// into a `Vec<T>`. Real VC-API servers send `query[].type` as a
/// bare string and `credentialQuery` as a single object on some workflows and an
/// array on others; this normalizes both to a `Vec`. Absent fields are handled
/// by `#[serde(default)]` at the field, so this is only called when the key is
/// present.
fn one_or_many<'de, D, T>(deserializer: D) -> Result<Vec<T>, D::Error>
where
    D: serde::Deserializer<'de>,
    T: serde::Deserialize<'de>,
{
    #[derive(Deserialize)]
    #[serde(untagged)]
    enum OneOrMany<T> {
        One(T),
        Many(Vec<T>),
    }
    Ok(match OneOrMany::<T>::deserialize(deserializer)? {
        OneOrMany::One(v) => vec![v],
        OneOrMany::Many(v) => v,
    })
}

/// A verifiable-presentation-request. Fully typed for losslessness; QBE/query
/// interpretation is a later phase, so query internals are kept defensively loose.
#[derive(Debug, Clone, Serialize, Deserialize, Default, PartialEq, uniffi::Record)]
#[serde(rename_all = "camelCase")]
pub struct Vpr {
    /// The presentation query/queries. Interpreted in a later phase.
    #[serde(default)]
    pub query: Vec<Query>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub challenge: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub domain: Option<String>,
    /// Accepted cryptosuites — each entry is a bare name OR an object.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub accepted_cryptosuites: Option<Vec<CryptosuiteEntry>>,
    /// Accepted envelope formats — same string-or-object shape.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub accepted_envelopes: Option<Vec<EnvelopeEntry>>,
    /// Interaction hints. Not consumed — carried opaquely for losslessness.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub interact: Option<serde_json::Value>,
}

/// A single presentation query inside a [`Vpr`].
///
/// `credentialQuery` is a typed [`CredentialQuery`] (§3.4.2), and the §3.4.5
/// `group` / §3.4.3.1 `required` logical-operation fields plus §3.4.3
/// `acceptedMethods` are surfaced. Unknown query `type` values are carried
/// losslessly in `r#type` — an unrecognized type is unsatisfiable for matching,
/// never an error.
#[derive(Debug, Clone, Serialize, Deserialize, Default, PartialEq, uniffi::Record)]
#[serde(rename_all = "camelCase")]
pub struct Query {
    /// The query type(s). Accepts a bare string (`"QueryByExample"`) OR an array
    /// (`["QueryByExample"]`).
    #[serde(rename = "type", default, deserialize_with = "one_or_many")]
    pub r#type: Vec<String>,
    /// The QueryByExample payload(s) (§3.4.2). Accepts a single object OR an
    /// array of objects.The matcher walks each contained `example` as JSON.
    #[serde(
        default,
        skip_serializing_if = "Vec::is_empty",
        deserialize_with = "one_or_many"
    )]
    pub credential_query: Vec<CredentialQuery>,
    /// §3.4.5 logical-operations group. Queries sharing the same `group` value are
    /// ANDed; absent or differing values are ORed. Kept `Option` so absence
    /// (its own singleton OR-alternative) is distinguishable from a named group.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub group: Option<String>,
    /// §3.4.3.1 `required`. Absence is treated as `true` at the use-site; kept
    /// `Option` so absence is distinguishable from an explicit value.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub required: Option<bool>,
    /// §3.4.3 `acceptedMethods` — each entry is a bare name OR a `{method}` object.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub accepted_methods: Option<Vec<AcceptedMethodEntry>>,
    /// §3.4.3.1 `acceptedCryptosuites` at the QUERY level — the placement the spec's
    /// Examples 6/7 use (a sibling of `acceptedMethods` on a DIDAuthentication
    /// query). Same string-or-object entry shape as the VPR-top-level and
    /// per-`credentialQuery` placements; suite selection consults all three.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub accepted_cryptosuites: Option<Vec<CryptosuiteEntry>>,
}

/// The QueryByExample `credentialQuery` payload (§3.4.2).
///
/// `example` stays an opaque [`serde_json::Value`] — the matcher walks it as JSON
/// (`type`/`@context` subset and recursive `credentialSubject` subset, with `""`
/// meaning "field present, any value"). The issuer filter accepts both
/// `acceptedIssuers` (§3.4.2) and `trustedIssuer` (alt-key).
#[derive(Debug, Clone, Serialize, Deserialize, Default, PartialEq, uniffi::Record)]
#[serde(rename_all = "camelCase")]
pub struct CredentialQuery {
    /// Human-readable reason the credential is requested.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub reason: Option<String>,
    /// The example credential to subset-match against (carried as JSON).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub example: Option<serde_json::Value>,
    /// `acceptedIssuers` (§3.4.2) — string / `{id}` / `{issuer}` / `{recognizedIn}`.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub accepted_issuers: Option<Vec<AcceptedIssuerEntry>>,
    /// `trustedIssuer` — alt-key for the issuer filter, same shapes.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub trusted_issuer: Option<Vec<AcceptedIssuerEntry>>,
    /// `acceptedCryptosuites` — same string-or-object shape as the VPR's.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub accepted_cryptosuites: Option<Vec<CryptosuiteEntry>>,
}

/// An `acceptedCryptosuites` entry: either a bare cryptosuite name or an object
/// carrying a `cryptosuite` field. `#[serde(untagged)]` is the ONLY sanctioned
/// untagged use in this module — it is NEVER applied to the envelope.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, uniffi::Enum)]
#[serde(untagged)]
pub enum CryptosuiteEntry {
    /// A bare cryptosuite name, e.g. `"ecdsa-rdfc-2019"`.
    Name(String),
    /// An object form, e.g. `{"cryptosuite": "ecdsa-sd-2023"}`.
    Object { cryptosuite: String },
}

/// An `acceptedEnvelopes` entry: a bare media-type string or an object carrying a
/// `mediaType` field. Same string-or-object backward-compat shape.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, uniffi::Enum)]
#[serde(untagged)]
pub enum EnvelopeEntry {
    /// A bare media-type, e.g. `"application/vp+jwt"`.
    Name(String),
    /// An object form, e.g. `{"mediaType": "application/vp+jwt"}`.
    Object {
        #[serde(rename = "mediaType")]
        media_type: String,
    },
}

/// An `acceptedIssuers`/`trustedIssuer` entry (§3.4.2). Uses the sanctioned
/// [`CryptosuiteEntry`] untagged pattern: a bare issuer URL string, or an object
/// carrying `id`, `issuer`, or `recognizedIn`. The `{recognizedIn}` form is
/// carried but NOT resolved — the matcher treats it as non-matching.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, uniffi::Enum)]
#[serde(untagged, rename_all = "camelCase")]
pub enum AcceptedIssuerEntry {
    /// A bare issuer identifier, e.g. `"did:web:red-issuer.example"`.
    Id(String),
    /// An object form: `{"id": ...}`, `{"issuer": ...}`, or `{"recognizedIn": ...}`.
    Object {
        #[serde(default, skip_serializing_if = "Option::is_none")]
        id: Option<String>,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        issuer: Option<String>,
        #[serde(
            default,
            rename = "recognizedIn",
            skip_serializing_if = "Option::is_none"
        )]
        recognized_in: Option<serde_json::Value>,
    },
}

/// An `acceptedMethods` entry (§3.4.3). Same string-or-object shape: a bare DID
/// method name, e.g. `"key"`, or an object `{"method": "key"}`.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, uniffi::Enum)]
#[serde(untagged)]
pub enum AcceptedMethodEntry {
    /// A bare method name, e.g. `"key"`.
    Name(String),
    /// An object form, e.g. `{"method": "key"}`.
    Object { method: String },
}

/// RFC 9457 problem-details, surfaced verbatim to the caller on a 4xx (§3.8).
///
/// Note: the string fields are server-provided, caller-facing data — not to be
/// logged verbatim at info level.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, uniffi::Record)]
pub struct ProblemDetails {
    /// The problem type URI/identifier. §3.8: MUST be present.
    #[serde(rename = "type")]
    pub problem_type: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub status: Option<u16>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub title: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub detail: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub instance: Option<String>,
}

/// The stable, app-facing outcome of one exchange step.
///
/// This is the `uniffi::Enum` the holder session returns to the native caller for
/// every server reply. `serde` is derived only for the round-trip unit tests; the
/// actual wire types are [`VcapiMessage`]/[`ProblemDetails`].
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, uniffi::Enum)]
#[serde(tag = "step")]
pub enum StepResult {
    /// The server requests a verifiable presentation; the caller should respond.
    Request { vpr: Vpr },
    /// The server offered verifiable presentation(s) (opaque, UNVERIFIED),
    /// optionally with a follow-on request to continue the exchange.
    Offer {
        vcs: serde_json::Value,
        next_vpr: Option<Vpr>,
    },
    /// A terminal redirect target. Surfaced as data — NEVER auto-followed.
    Redirect { url: Url },
    /// The exchange completed successfully with no further action.
    Complete,
    /// The server returned an RFC 9457 problem (a surfaced 4xx, NOT an error).
    Problem { details: ProblemDetails },
}

/// Deterministically map an HTTP `(status, body)` pair to a [`StepResult`] or a
/// [`VcalmError`]. Pure, no HTTP, no retries.
///
/// Precedence on a 2xx body (§3.6.5): `verifiablePresentation` (Offer, carrying any
/// follow-on `verifiablePresentationRequest` as `next_vpr`) → `redirectUrl`
/// (terminal) → `verifiablePresentationRequest` (Request) → `Complete`. An empty
/// body is `Complete`. The Offer-before-Redirect order matters: §3.6 allows a
/// message to combine "zero or more" properties, and a server that issues VCs and
/// redirects in ONE message must not have its credentials silently dropped — the
/// redirect is the recommendation, the VP is the payload.
///
/// A 4xx whose body parses as [`ProblemDetails`] is surfaced as `Ok(Problem)`;
/// a malformed 4xx body, a 5xx response, or an undeserializable 2xx body are `Err`.
pub fn classify(status: reqwest::StatusCode, body: &str) -> Result<StepResult, VcalmError> {
    if status.is_success() {
        let message: VcapiMessage = if body.trim().is_empty() {
            VcapiMessage::default()
        } else {
            serde_json::from_str(body).map_err(|e| VcalmError::Deserialization(e.to_string()))?
        };

        // Field-presence precedence — first match wins. Offer outranks Redirect so a
        // combined "here are your credentials, now go here" message keeps the VCs.
        if let Some(vcs) = message.verifiable_presentation {
            Ok(StepResult::Offer {
                vcs,
                next_vpr: message.verifiable_presentation_request,
            })
        } else if let Some(url) = message.redirect_url {
            Ok(StepResult::Redirect { url })
        } else if let Some(vpr) = message.verifiable_presentation_request {
            Ok(StepResult::Request { vpr })
        } else {
            Ok(StepResult::Complete)
        }
    } else if status.is_client_error() {
        // 4xx: a well-formed problem-details is surfaced; malformed is an error.
        match serde_json::from_str::<ProblemDetails>(body) {
            Ok(details) => Ok(StepResult::Problem { details }),
            Err(_) => Err(VcalmError::MalformedProblemDetails {
                status: status.as_u16(),
                body: body.to_string(),
            }),
        }
    } else {
        // 5xx and any other non-2xx/4xx status.
        Err(VcalmError::ServerError {
            status: status.as_u16(),
            body: body.to_string(),
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use reqwest::StatusCode;
    use serde_json::json;

    mod serde {
        use super::*;

        #[test]
        fn all_none_vcapi_message_serializes_to_empty_object() {
            let msg = VcapiMessage::default();
            assert_eq!(serde_json::to_value(&msg).unwrap(), json!({}));
        }

        #[test]
        fn query_level_accepted_cryptosuites_deserialize() {
            // §3.4.3.1 / spec Examples 6-7: `acceptedCryptosuites` as a sibling of
            // `acceptedMethods` on a DIDAuthentication query, in both entry shapes.
            let vpr: Vpr = serde_json::from_value(json!({
                "query": [{
                    "type": "DIDAuthentication",
                    "acceptedMethods": [{"method": "key"}],
                    "acceptedCryptosuites": [
                        {"cryptosuite": "ecdsa-rdfc-2019"},
                        "eddsa-rdfc-2022"
                    ]
                }]
            }))
            .unwrap();
            let suites = vpr.query[0].accepted_cryptosuites.as_ref().unwrap();
            assert_eq!(
                suites,
                &vec![
                    CryptosuiteEntry::Object {
                        cryptosuite: "ecdsa-rdfc-2019".into()
                    },
                    CryptosuiteEntry::Name("eddsa-rdfc-2022".into()),
                ]
            );
        }

        #[test]
        fn vcapi_message_round_trips_each_field() {
            let with_vpr = VcapiMessage {
                verifiable_presentation_request: Some(Vpr {
                    challenge: Some("c".into()),
                    ..Default::default()
                }),
                ..Default::default()
            };
            let with_vp = VcapiMessage {
                verifiable_presentation: Some(json!({"foo": "bar"})),
                ..Default::default()
            };
            let with_redirect = VcapiMessage {
                redirect_url: Some(Url::parse("https://example.com/done").unwrap()),
                ..Default::default()
            };
            let with_ref = VcapiMessage {
                reference_id: Some("ref-123".into()),
                ..Default::default()
            };
            for msg in [with_vpr, with_vp, with_redirect, with_ref] {
                let json = serde_json::to_string(&msg).unwrap();
                let back: VcapiMessage = serde_json::from_str(&json).unwrap();
                assert_eq!(msg, back);
            }
        }

        #[test]
        fn problem_details_renames_type_and_omits_none() {
            let pd = ProblemDetails {
                problem_type: "https://example.com/err".into(),
                status: Some(400),
                title: None,
                detail: None,
                instance: None,
            };
            let value = serde_json::to_value(&pd).unwrap();
            assert_eq!(
                value,
                json!({"type": "https://example.com/err", "status": 400})
            );
            let back: ProblemDetails = serde_json::from_value(value).unwrap();
            assert_eq!(pd, back);
        }

        #[test]
        fn vpr_deserializes_mixed_string_and_object_cryptosuites() {
            let vpr: Vpr = serde_json::from_value(json!({
                "query": [],
                "acceptedCryptosuites": ["ecdsa-rdfc-2019", {"cryptosuite": "ecdsa-sd-2023"}]
            }))
            .expect("mixed string/object acceptedCryptosuites must deserialize");
            let suites = vpr.accepted_cryptosuites.expect("present");
            assert_eq!(suites.len(), 2);
            assert_eq!(suites[0], CryptosuiteEntry::Name("ecdsa-rdfc-2019".into()));
            assert_eq!(
                suites[1],
                CryptosuiteEntry::Object {
                    cryptosuite: "ecdsa-sd-2023".into()
                }
            );
        }

        #[test]
        fn qbe_vpr_deserializes_typed_credential_query_and_mixed_issuer_shapes() {
            // Mirrors §3.4.2 Example 2: a QueryByExample query with a typed
            // credentialQuery carrying `example` (type/@context/credentialSubject)
            // and `acceptedIssuers` mixing all four shapes.
            let vpr: Vpr = serde_json::from_value(json!({
                "query": [{
                    "type": ["QueryByExample"],
                    "credentialQuery": {
                        "reason": "We need your citizenship credential.",
                        "example": {
                            "@context": ["https://www.w3.org/ns/credentials/v2"],
                            "type": ["VerifiableCredential", "PermanentResidentCard"],
                            "credentialSubject": { "givenName": "" }
                        },
                        "acceptedIssuers": [
                            "did:web:red-issuer.example",
                            {"id": "https://id-issuer.example/"},
                            {"issuer": "https://blue-issuer.example/"},
                            {"recognizedIn": {"type": "VerifiableRecognitionCredential"}}
                        ]
                    }
                }]
            }))
            .expect("typed QBE VPR with mixed issuer shapes must deserialize");

            assert_eq!(vpr.query.len(), 1);
            let q = &vpr.query[0];
            assert_eq!(q.r#type, vec!["QueryByExample".to_string()]);

            let cq = &q.credential_query[0];
            assert_eq!(
                cq.reason.as_deref(),
                Some("We need your citizenship credential.")
            );
            // `example` stays opaque JSON for the matcher to walk.
            assert!(cq.example.as_ref().unwrap()["credentialSubject"]["givenName"] == json!(""));

            let issuers = cq
                .accepted_issuers
                .as_ref()
                .expect("acceptedIssuers present");
            assert_eq!(issuers.len(), 4);
            // bare string
            assert_eq!(
                issuers[0],
                AcceptedIssuerEntry::Id("did:web:red-issuer.example".into())
            );
            // {id}
            assert_eq!(
                issuers[1],
                AcceptedIssuerEntry::Object {
                    id: Some("https://id-issuer.example/".into()),
                    issuer: None,
                    recognized_in: None,
                }
            );
            // {issuer}
            assert_eq!(
                issuers[2],
                AcceptedIssuerEntry::Object {
                    id: None,
                    issuer: Some("https://blue-issuer.example/".into()),
                    recognized_in: None,
                }
            );
            // {recognizedIn} — carried, not resolved
            match &issuers[3] {
                AcceptedIssuerEntry::Object {
                    id: None,
                    issuer: None,
                    recognized_in: Some(_),
                } => {}
                other => panic!("expected {{recognizedIn}} object carried, got {other:?}"),
            }

            // Round-trip discipline: typed fields survive re-serialization.
            let back: Vpr = serde_json::from_value(serde_json::to_value(&vpr).unwrap()).unwrap();
            assert_eq!(vpr, back);
        }

        #[test]
        fn trusted_issuer_alt_key_deserializes_into_same_enum_vec() {
            let vpr: Vpr = serde_json::from_value(json!({
                "query": [{
                    "type": ["QueryByExample"],
                    "credentialQuery": {
                        "trustedIssuer": [
                            "did:web:red-issuer.example",
                            {"issuer": "https://blue-issuer.example/"}
                        ]
                    }
                }]
            }))
            .expect("trustedIssuer alt-key must deserialize");

            let cq = &vpr.query[0].credential_query[0];
            assert!(cq.accepted_issuers.is_none());
            let trusted = cq.trusted_issuer.as_ref().expect("trustedIssuer present");
            assert_eq!(trusted.len(), 2);
            assert_eq!(
                trusted[0],
                AcceptedIssuerEntry::Id("did:web:red-issuer.example".into())
            );
            assert_eq!(
                trusted[1],
                AcceptedIssuerEntry::Object {
                    id: None,
                    issuer: Some("https://blue-issuer.example/".into()),
                    recognized_in: None,
                }
            );

            let back: Vpr = serde_json::from_value(serde_json::to_value(&vpr).unwrap()).unwrap();
            assert_eq!(vpr, back);
        }

        #[test]
        fn accepted_methods_string_and_object_shapes_deserialize() {
            let vpr: Vpr = serde_json::from_value(json!({
                "query": [{
                    "type": ["DIDAuthentication"],
                    "acceptedMethods": ["key", {"method": "web"}]
                }]
            }))
            .expect("acceptedMethods string/object shapes must deserialize");

            let methods = vpr.query[0].accepted_methods.as_ref().expect("present");
            assert_eq!(methods.len(), 2);
            assert_eq!(methods[0], AcceptedMethodEntry::Name("key".into()));
            assert_eq!(
                methods[1],
                AcceptedMethodEntry::Object {
                    method: "web".into()
                }
            );

            let back: Vpr = serde_json::from_value(serde_json::to_value(&vpr).unwrap()).unwrap();
            assert_eq!(vpr, back);
        }

        #[test]
        fn group_and_required_round_trip_present_and_absent() {
            let vpr: Vpr = serde_json::from_value(json!({
                "query": [
                    { "type": ["QueryByExample"], "group": "A", "required": false },
                    { "type": ["QueryByExample"] }
                ]
            }))
            .expect("group/required present and absent must deserialize");

            assert_eq!(vpr.query[0].group.as_deref(), Some("A"));
            assert_eq!(vpr.query[0].required, Some(false));
            // Absent group/required stay None (distinguishable from an explicit value).
            assert_eq!(vpr.query[1].group, None);
            assert_eq!(vpr.query[1].required, None);

            // Absent fields are omitted from the wire (skip_serializing_if).
            let value = serde_json::to_value(&vpr).unwrap();
            assert!(value["query"][1].get("group").is_none());
            assert!(value["query"][1].get("required").is_none());

            let back: Vpr = serde_json::from_value(value).unwrap();
            assert_eq!(vpr, back);
        }

        #[test]
        fn credential_query_accepted_cryptosuites_round_trips() {
            let vpr: Vpr = serde_json::from_value(json!({
                "query": [{
                    "type": ["QueryByExample"],
                    "credentialQuery": {
                        "acceptedCryptosuites": ["ecdsa-rdfc-2019", {"cryptosuite": "ecdsa-sd-2023"}]
                    }
                }]
            }))
            .expect("credentialQuery.acceptedCryptosuites must deserialize");

            let cq = &vpr.query[0].credential_query[0];
            let suites = cq.accepted_cryptosuites.as_ref().expect("present");
            assert_eq!(suites.len(), 2);
            assert_eq!(suites[0], CryptosuiteEntry::Name("ecdsa-rdfc-2019".into()));
            assert_eq!(
                suites[1],
                CryptosuiteEntry::Object {
                    cryptosuite: "ecdsa-sd-2023".into()
                }
            );

            let back: Vpr = serde_json::from_value(serde_json::to_value(&vpr).unwrap()).unwrap();
            assert_eq!(vpr, back);
        }

        #[test]
        fn step_result_round_trips_every_branch() {
            let branches = vec![
                StepResult::Request {
                    vpr: Vpr {
                        challenge: Some("c".into()),
                        ..Default::default()
                    },
                },
                StepResult::Offer {
                    vcs: json!({"vp": "opaque"}),
                    next_vpr: None,
                },
                StepResult::Offer {
                    vcs: json!({"vp": "opaque"}),
                    next_vpr: Some(Vpr {
                        domain: Some("d".into()),
                        ..Default::default()
                    }),
                },
                StepResult::Redirect {
                    url: Url::parse("https://example.com/done").unwrap(),
                },
                StepResult::Complete,
                StepResult::Problem {
                    details: ProblemDetails {
                        problem_type: "urn:err".into(),
                        status: Some(400),
                        title: Some("bad".into()),
                        detail: None,
                        instance: None,
                    },
                },
            ];
            for branch in branches {
                let json = serde_json::to_string(&branch).unwrap();
                let back: StepResult = serde_json::from_str(&json).unwrap();
                assert_eq!(branch, back);
            }
        }
    }

    mod classify {
        use super::*;

        fn status(code: u16) -> StatusCode {
            StatusCode::from_u16(code).unwrap()
        }

        #[test]
        fn empty_2xx_body_is_complete() {
            assert_eq!(classify(status(200), "").unwrap(), StepResult::Complete);
            assert_eq!(classify(status(200), "{}").unwrap(), StepResult::Complete);
        }

        #[test]
        fn redirect_only_is_terminal_redirect() {
            let body = json!({
                "redirectUrl": "https://example.com/done",
                "referenceId": "ref-1"
            })
            .to_string();
            match classify(status(200), &body).unwrap() {
                StepResult::Redirect { url } => {
                    assert_eq!(url.as_str(), "https://example.com/done")
                }
                other => panic!("expected Redirect, got {other:?}"),
            }
        }

        #[test]
        fn offer_outranks_redirect_in_combined_message() {
            // §3.6: a message may combine "zero or more" properties. A server that
            // issues VCs AND recommends a redirect in one message must not have its
            // credentials silently dropped — the Offer wins.
            let body = json!({
                "verifiablePresentation": {"vp": "opaque"},
                "redirectUrl": "https://example.com/done"
            })
            .to_string();
            match classify(status(200), &body).unwrap() {
                StepResult::Offer { vcs, next_vpr } => {
                    assert_eq!(vcs, json!({"vp": "opaque"}));
                    assert!(next_vpr.is_none());
                }
                other => panic!("expected Offer, got {other:?}"),
            }
        }

        #[test]
        fn verifiable_presentation_with_follow_on_request_is_offer_with_next_vpr() {
            let body = json!({
                "verifiablePresentation": {"vp": "opaque"},
                "verifiablePresentationRequest": {"query": [], "challenge": "next"}
            })
            .to_string();
            match classify(status(200), &body).unwrap() {
                StepResult::Offer { vcs, next_vpr } => {
                    assert_eq!(vcs, json!({"vp": "opaque"}));
                    assert_eq!(next_vpr.unwrap().challenge.as_deref(), Some("next"));
                }
                other => panic!("expected Offer, got {other:?}"),
            }
        }

        #[test]
        fn only_verifiable_presentation_request_is_request() {
            let body = json!({
                "verifiablePresentationRequest": {"query": [], "challenge": "c"}
            })
            .to_string();
            match classify(status(200), &body).unwrap() {
                StepResult::Request { vpr } => assert_eq!(vpr.challenge.as_deref(), Some("c")),
                other => panic!("expected Request, got {other:?}"),
            }
        }

        #[test]
        fn valid_4xx_problem_details_is_surfaced_as_ok_problem() {
            let body = json!({
                "type": "https://exchange.example/errors/CRYPTOGRAPHIC_SECURITY_ERROR",
                "status": 400,
                "title": "Security error",
                "detail": "challenge mismatch"
            })
            .to_string();
            match classify(status(400), &body).unwrap() {
                StepResult::Problem { details } => {
                    assert_eq!(details.status, Some(400));
                    assert_eq!(details.title.as_deref(), Some("Security error"));
                }
                other => panic!("expected Problem, got {other:?}"),
            }
        }

        #[test]
        fn malformed_4xx_body_is_err() {
            let err = classify(status(400), "<html>not json</html>").unwrap_err();
            match err {
                VcalmError::MalformedProblemDetails { status, .. } => assert_eq!(status, 400),
                other => panic!("expected MalformedProblemDetails, got {other:?}"),
            }
        }

        #[test]
        fn server_5xx_is_err() {
            let err = classify(status(500), "boom").unwrap_err();
            match err {
                VcalmError::ServerError { status, .. } => assert_eq!(status, 500),
                other => panic!("expected ServerError, got {other:?}"),
            }
        }

        #[test]
        fn undeserializable_2xx_body_is_err() {
            let err = classify(status(200), "not json").unwrap_err();
            assert!(matches!(err, VcalmError::Deserialization(_)));
        }
    }
}
