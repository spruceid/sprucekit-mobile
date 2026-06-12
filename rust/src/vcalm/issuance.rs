use serde_json::Value;
use uuid::Uuid;

use crate::credential::{CredentialFormat, RawCredential};

use super::error::VcalmError;

/// Classification of a single offered-credential entry.
///
/// Only the [`BareDataIntegrity`](OfferedEntry::BareDataIntegrity) path is decoded.
/// [`Enveloped`](OfferedEntry::Enveloped) is recognized so the accept verb can
/// route it to a typed error rather than silently dropping it; decoding the `data:`
/// URL payload by media type is not yet implemented.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum OfferedEntry {
    /// A bare W3C Data-Integrity VC (`application/vc` → `LdpVc`) — the primary path.
    BareDataIntegrity,
    /// An `EnvelopedVerifiableCredential` — recognized but not yet decoded.
    Enveloped,
}

/// True iff the offered `verifiablePresentation` value is itself an
/// `EnvelopedVerifiablePresentation` — a shape the holder cannot unwrap yet.
/// Detected so the caller can surface a typed unsupported-format error instead
/// of an indistinguishable-from-empty offer.
pub(crate) fn is_enveloped_presentation(vcs: &Value) -> bool {
    vcs.get("type").is_some_and(|t| {
        t.as_str() == Some("EnvelopedVerifiablePresentation")
            || t.as_array().is_some_and(|a| {
                a.iter()
                    .any(|x| x.as_str() == Some("EnvelopedVerifiablePresentation"))
            })
    })
}

/// Normalize the offered VP envelope's `verifiableCredential` field into a `Vec`.
///
/// `vcs` is the [`StepResult::Offer`](super::exchange::StepResult) `vcs` value.
/// Shaping rules:
/// - `EnvelopedVerifiablePresentation` envelope →
///   [`VcalmError::UnsupportedCredentialFormat`] (typed, never silently empty).
/// - `Array` → cloned as-is.
/// - single `Object` → one-element vec (single-VC offers omit the array wrapper).
/// - `Null` / absent / empty array → [`VcalmError::NoOfferedCredentials`].
/// - any other JSON type → [`VcalmError::Deserialization`] describing the shape
///   (no verbatim server body in the message).
pub(crate) fn extract_offered_vcs(vcs: &Value) -> Result<Vec<Value>, VcalmError> {
    if is_enveloped_presentation(vcs) {
        return Err(VcalmError::UnsupportedCredentialFormat(
            "EnvelopedVerifiablePresentation offers are not yet supported".into(),
        ));
    }
    let field = vcs.get("verifiableCredential");
    let list = match field {
        Some(Value::Array(a)) => a.clone(),
        Some(obj @ Value::Object(_)) => vec![obj.clone()], // single → one-element
        Some(Value::Null) | None => return Err(VcalmError::NoOfferedCredentials),
        Some(_other) => {
            return Err(VcalmError::Deserialization(
                "verifiableCredential must be an object or array".to_string(),
            ))
        }
    };
    if list.is_empty() {
        return Err(VcalmError::NoOfferedCredentials);
    }
    Ok(list)
}

/// Classify a single offered entry as bare Data-Integrity vs enveloped.
///
/// An entry is [`Enveloped`](OfferedEntry::Enveloped) iff its `type` is — or, when
/// `type` is an array, contains — the string `"EnvelopedVerifiableCredential"`.
/// Everything else is [`BareDataIntegrity`](OfferedEntry::BareDataIntegrity).
pub(crate) fn classify_offered_entry(entry: &Value) -> OfferedEntry {
    let is_enveloped = entry
        .get("type")
        .map(|t| {
            t.as_str() == Some("EnvelopedVerifiableCredential")
                || t.as_array().is_some_and(|a| {
                    a.iter()
                        .any(|x| x.as_str() == Some("EnvelopedVerifiableCredential"))
                })
        })
        .unwrap_or(false);
    if is_enveloped {
        OfferedEntry::Enveloped
    } else {
        OfferedEntry::BareDataIntegrity
    }
}

/// Wrap a bare offered entry as the [`RawCredential`] the existing verifier accepts:
/// serialize the entry's JSON to bytes and tag it [`CredentialFormat::LdpVc`]. The
/// payload round-trips back to the input JSON object. Caller is responsible for
/// having classified the entry as [`OfferedEntry::BareDataIntegrity`] first.
pub(crate) fn build_raw_credential(entry: &Value) -> Result<RawCredential, VcalmError> {
    let payload =
        serde_json::to_vec(entry).map_err(|e| VcalmError::Deserialization(e.to_string()))?;
    Ok(RawCredential {
        format: CredentialFormat::LdpVc,
        payload,
    })
}

/// Derive a deterministic local storage id for an offered VC (idempotency).
///
/// The v5 name is SCOPED BY ISSUER (`"{issuer}\n{id}"`): `VdcCollection::add`
/// replaces on duplicate key, so an id-only name would let a malicious issuer
/// mint a VC whose `id` collides with — and silently overwrites — a different
/// stored credential. Same issuer re-issuing the same `id` still overwrites
/// (intended idempotent re-accept); a different issuer claiming the same `id`
/// gets a distinct storage slot. VCs with no usable `id` fall back to a
/// content-derived name (re-accepting identical content stays idempotent).
pub(crate) fn stable_local_id(entry: &Value) -> Uuid {
    let issuer = super::matching::vc_issuer(entry).unwrap_or_default();
    match entry.get("id").and_then(Value::as_str) {
        Some(id) if !id.is_empty() => {
            let name = format!("{issuer}\n{id}");
            Uuid::new_v5(&Uuid::NAMESPACE_URL, name.as_bytes())
        }
        _ => {
            let canonical = serde_json::to_vec(entry).unwrap_or_default();
            Uuid::new_v5(&Uuid::NAMESPACE_URL, &canonical)
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn bare_vc(id: &str) -> Value {
        json!({
            "@context": ["https://www.w3.org/ns/credentials/v2"],
            "id": id,
            "type": ["VerifiableCredential", "PermanentResidentCard"],
            "issuer": "did:key:zExample",
            "credentialSubject": { "givenName": "Alice" }
        })
    }

    #[test]
    fn extract_array_returns_all() {
        let vcs = json!({ "verifiableCredential": [bare_vc("urn:a"), bare_vc("urn:b")] });
        let out = extract_offered_vcs(&vcs).unwrap();
        assert_eq!(out.len(), 2);
        assert_eq!(out[0]["id"], "urn:a");
        assert_eq!(out[1]["id"], "urn:b");
    }

    #[test]
    fn extract_single_object_normalizes_to_one_element() {
        let vcs = json!({ "verifiableCredential": bare_vc("urn:solo") });
        let out = extract_offered_vcs(&vcs).unwrap();
        assert_eq!(out.len(), 1);
        assert_eq!(out[0]["id"], "urn:solo");
    }

    #[test]
    fn extract_absent_field_is_no_offered_credentials() {
        let vcs = json!({ "somethingElse": 1 });
        assert!(matches!(
            extract_offered_vcs(&vcs),
            Err(VcalmError::NoOfferedCredentials)
        ));
    }

    #[test]
    fn extract_null_field_is_no_offered_credentials() {
        let vcs = json!({ "verifiableCredential": null });
        assert!(matches!(
            extract_offered_vcs(&vcs),
            Err(VcalmError::NoOfferedCredentials)
        ));
    }

    #[test]
    fn extract_empty_array_is_no_offered_credentials() {
        let vcs = json!({ "verifiableCredential": [] });
        assert!(matches!(
            extract_offered_vcs(&vcs),
            Err(VcalmError::NoOfferedCredentials)
        ));
    }

    #[test]
    fn extract_garbage_type_is_deserialization_error() {
        let vcs = json!({ "verifiableCredential": 42 });
        let err = extract_offered_vcs(&vcs).unwrap_err();
        match err {
            VcalmError::Deserialization(msg) => {
                // message describes shape, not the verbatim value.
                assert!(msg.contains("verifiableCredential"));
                assert!(!msg.contains("42"));
            }
            other => panic!("expected Deserialization, got {other:?}"),
        }
    }

    #[test]
    fn classify_bare_data_integrity() {
        assert_eq!(
            classify_offered_entry(&bare_vc("urn:x")),
            OfferedEntry::BareDataIntegrity
        );
    }

    #[test]
    fn classify_enveloped_string_type() {
        let entry = json!({
            "type": "EnvelopedVerifiableCredential",
            "id": "data:application/vc;base64,eyJ9"
        });
        assert_eq!(classify_offered_entry(&entry), OfferedEntry::Enveloped);
    }

    #[test]
    fn classify_enveloped_array_type() {
        let entry = json!({
            "type": ["EnvelopedVerifiableCredential"],
            "id": "data:application/vc;base64,eyJ9"
        });
        assert_eq!(classify_offered_entry(&entry), OfferedEntry::Enveloped);
    }

    #[test]
    fn classify_missing_type_is_bare() {
        let entry = json!({ "id": "urn:no-type" });
        assert_eq!(
            classify_offered_entry(&entry),
            OfferedEntry::BareDataIntegrity
        );
    }

    #[test]
    fn build_raw_credential_round_trips() {
        let entry = bare_vc("urn:rt");
        let raw = build_raw_credential(&entry).unwrap();
        assert_eq!(raw.format, CredentialFormat::LdpVc);
        let decoded: Value = serde_json::from_slice(&raw.payload).unwrap();
        assert_eq!(decoded, entry);
    }

    #[test]
    fn stable_local_id_same_id_collides() {
        // Same ISSUER + same VC `id`, different incidental content → SAME local
        // id (idempotent re-accept).
        let a = bare_vc("urn:uuid:same");
        let mut b = bare_vc("urn:uuid:same");
        b["credentialSubject"]["givenName"] = json!("Bob");
        assert_eq!(stable_local_id(&a), stable_local_id(&b));
    }

    #[test]
    fn stable_local_id_is_issuer_scoped() {
        // A DIFFERENT issuer claiming the same VC `id` must get a DISTINCT
        // storage slot — otherwise a malicious issuer could mint a colliding
        // `id` and silently overwrite a credential the user already holds.
        let a = bare_vc("urn:uuid:same");
        let mut b = bare_vc("urn:uuid:same");
        b["issuer"] = json!("did:key:zMallory");
        assert_ne!(stable_local_id(&a), stable_local_id(&b));

        // Issuer-object form scopes by the issuer `id`.
        let mut c = bare_vc("urn:uuid:same");
        c["issuer"] = json!({ "id": "did:key:zExample" });
        assert_eq!(
            stable_local_id(&a),
            stable_local_id(&c),
            "string and {{id}} issuer forms with the same identifier agree"
        );
    }

    #[test]
    fn stable_local_id_different_id_differs() {
        assert_ne!(
            stable_local_id(&bare_vc("urn:uuid:one")),
            stable_local_id(&bare_vc("urn:uuid:two"))
        );
    }

    #[test]
    fn stable_local_id_idless_fallback_is_deterministic() {
        let entry = json!({
            "type": ["VerifiableCredential"],
            "credentialSubject": { "givenName": "NoId" }
        });
        // Two calls on the same id-less content → equal (content-derived fallback).
        assert_eq!(stable_local_id(&entry), stable_local_id(&entry.clone()));
    }

    #[test]
    fn stable_local_id_idless_differs_from_other_idless() {
        let a = json!({ "type": ["VerifiableCredential"], "credentialSubject": { "n": 1 } });
        let b = json!({ "type": ["VerifiableCredential"], "credentialSubject": { "n": 2 } });
        assert_ne!(stable_local_id(&a), stable_local_id(&b));
    }
}
