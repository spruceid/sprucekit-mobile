//! DCQL `meta.type_values` matching for W3C VCs (OID4VP 1.0 Appendix B.1.1).
//!
//! A credential matches if it has every type of one alternative, compared
//! after expanding its `type` with its `@context`. Beyond the spec, for
//! interoperability, a type may also equal a literal `type` value: verifiers
//! send terms like `VerifiableCredential`. Only literals match when the
//! `@context` cannot be loaded offline.

use std::collections::HashMap;

use json_syntax::Parse;
use openid4vp::core::dcql_query::DcqlCredentialQuery;
use serde_json::{Map, Value as Json};
use ssi::json_ld::{ContextLoader, Expand, IriBuf};

use super::error::OID4VPError;
use crate::credential::ParsedCredential;

const TYPE_VALUES: &str = "type_values";

/// The types of a credential, for matching `type_values`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) enum CredentialTypes {
    /// The credential is not a W3C VC, so `type_values` does not apply.
    NotApplicable,
    W3c {
        /// The expanded `type` IRIs, or `None` if a `@context` could not be
        /// loaded offline.
        expanded: Option<Vec<String>>,
        /// The `type` values as written in the credential.
        literal: Vec<String>,
    },
}

impl CredentialTypes {
    /// Whether the credential has the type `required`, as an expanded IRI or
    /// as written.
    fn has(&self, required: &str) -> bool {
        match self {
            Self::NotApplicable => false,
            Self::W3c { expanded, literal } => expanded
                .iter()
                .flatten()
                .chain(literal)
                .any(|ty| ty == required),
        }
    }
}

/// Whether any credential query in `queries` constrains `type_values`.
pub(crate) fn any_requests_type_values<'a>(
    mut queries: impl Iterator<Item = &'a DcqlCredentialQuery>,
) -> bool {
    queries.any(|query| query.meta().contains_key(TYPE_VALUES))
}

/// The types of every credential, in order, expanded once so that matching
/// each credential query needs no JSON-LD work.
pub(crate) async fn expand_all(
    credentials: &[std::sync::Arc<ParsedCredential>],
    context_map: Option<HashMap<String, String>>,
) -> Result<Vec<CredentialTypes>, OID4VPError> {
    let loader = context_map
        .map(|map| ContextLoader::default().with_context_map_from(map))
        .transpose()
        .map_err(|e| OID4VPError::DcqlQueryResolution(format!("invalid JSON-LD context map: {e}")))?
        .unwrap_or_default();

    let documents = credentials
        .iter()
        .map(|credential| {
            credential
                .w3c_vc_json()
                .map(|vc| (credential.id(), vc.into_owned()))
        })
        .collect::<Vec<_>>();

    // JSON-LD expansion needs a larger stack than iOS threads have.
    crate::big_stack::run_async(move || async move {
        let mut expanded = Vec::with_capacity(documents.len());
        for document in documents {
            expanded.push(match document {
                None => CredentialTypes::NotApplicable,
                Some((id, vc)) => CredentialTypes::W3c {
                    expanded: expand_types(&vc, &loader)
                        .await
                        .inspect_err(|e| {
                            log::warn!("failed to expand the types of credential {id}: {e}")
                        })
                        .ok(),
                    literal: literal_types(&vc),
                },
            });
        }
        expanded
    })
    .await
    .map_err(|e| OID4VPError::DcqlQueryResolution(format!("failed to expand types: {e}")))
}

/// Whether a credential with `types` satisfies the query's `type_values`.
///
/// A query without `type_values` places no constraint on the types. A
/// malformed `type_values` matches no credential.
pub(crate) fn satisfies_type_values(
    credential_query: &DcqlCredentialQuery,
    types: &CredentialTypes,
) -> bool {
    let Some(value) = credential_query.meta().get(TYPE_VALUES) else {
        return true;
    };

    if *types == CredentialTypes::NotApplicable {
        return true;
    }

    let Some(alternatives) = parse_type_values(value) else {
        log::warn!(
            "credential query {:?} has a malformed `type_values`: {value}",
            credential_query.id()
        );
        return false;
    };

    alternatives
        .iter()
        .any(|required| required.iter().all(|t| types.has(t)))
}

/// Parse `type_values` as a non-empty array of non-empty string arrays.
fn parse_type_values(value: &Json) -> Option<Vec<Vec<&str>>> {
    let alternatives = value
        .as_array()
        .filter(|alternatives| !alternatives.is_empty())?
        .iter()
        .map(|alternative| {
            alternative
                .as_array()
                .filter(|types| !types.is_empty())?
                .iter()
                .map(Json::as_str)
                .collect::<Option<Vec<_>>>()
        })
        .collect::<Option<Vec<_>>>()?;

    Some(alternatives)
}

/// The `type` values of the credential as written.
fn literal_types(vc: &Json) -> Vec<String> {
    ["type", "@type"]
        .iter()
        .filter_map(|key| vc.get(key))
        .flat_map(|value| match value {
            Json::Array(values) => values.iter().filter_map(Json::as_str).collect(),
            value => value.as_str().into_iter().collect::<Vec<_>>(),
        })
        .map(ToOwned::to_owned)
        .collect()
}

/// The credential's `type` expanded with its `@context`.
///
/// Expanding only these two members gives the same types as expanding the
/// whole credential, an equivalent mechanism Appendix B.1.1 allows.
async fn expand_types(vc: &Json, loader: &ContextLoader) -> Result<Vec<String>, String> {
    let root = vc
        .as_object()
        .ok_or("the credential is not a JSON object")?;

    let mut document = Map::new();
    for key in ["@context", "type", "@type"] {
        if let Some(value) = root.get(key) {
            document.insert(key.to_owned(), value.clone());
        }
    }

    let (document, _) = json_syntax::Value::parse_str(&Json::Object(document).to_string())
        .map_err(|e| e.to_string())?;
    let expanded = Expand::<IriBuf>::expand(&document, loader)
        .await
        .map_err(|e| e.to_string())?;

    Ok(expanded
        .iter()
        .find_map(|object| object.as_node())
        .map(|node| node.types().iter().map(|t| t.as_str().to_owned()).collect())
        .unwrap_or_default())
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    const VC_V1: &str = "https://www.w3.org/2018/credentials/v1";
    const VC_V1_EXAMPLES: &str = "https://www.w3.org/2018/credentials/examples/v1";
    const VC_V2: &str = "https://www.w3.org/ns/credentials/v2";
    const VC: &str = "https://www.w3.org/2018/credentials#VerifiableCredential";

    fn query(meta: Json) -> DcqlCredentialQuery {
        serde_json::from_value(json!({ "id": "q", "format": "ldp_vc", "meta": meta })).unwrap()
    }

    /// The `type_values` from the Appendix B.1.1 example.
    fn spec_query() -> DcqlCredentialQuery {
        query(json!({
            "type_values": [
                [
                    VC,
                    "https://example.org/examples#AlumniCredential",
                    "https://example.org/examples#BachelorDegree"
                ],
                [VC, "https://example.org/examples#UniversityDegreeCredential"],
                ["IdentityCredential"]
            ]
        }))
    }

    /// The types of `vc`, expanded with the default loader.
    async fn types(vc: Json) -> CredentialTypes {
        CredentialTypes::W3c {
            expanded: expand_types(&vc, &ContextLoader::default()).await.ok(),
            literal: literal_types(&vc),
        }
    }

    /// Only the expanded IRIs, to check expansion without literal matching.
    fn expanded_only(types: CredentialTypes) -> CredentialTypes {
        match types {
            CredentialTypes::W3c { expanded, .. } => CredentialTypes::W3c {
                expanded,
                literal: vec![],
            },
            types => types,
        }
    }

    #[tokio::test]
    async fn matches_the_spec_examples() {
        let query = spec_query();

        for ty in [
            json!(["VerifiableCredential", "UniversityDegreeCredential"]),
            // Order and additional types do not matter.
            json!(["VerifiableCredential", "BachelorDegree", "AlumniCredential"]),
        ] {
            let vc = json!({ "@context": [VC_V1, VC_V1_EXAMPLES], "type": ty });
            assert!(satisfies_type_values(
                &query,
                &expanded_only(types(vc).await)
            ));
        }

        // `IdentityCredential` is in no `@context`, so it stays relative.
        let vc =
            json!({ "@context": [VC_V1], "type": ["VerifiableCredential", "IdentityCredential"] });
        assert!(satisfies_type_values(
            &query,
            &expanded_only(types(vc).await)
        ));
    }

    #[tokio::test]
    async fn every_type_of_an_alternative_must_be_present() {
        // Only `AlumniCredential` of the first alternative is present.
        let vc = json!({
            "@context": [VC_V1, VC_V1_EXAMPLES],
            "type": ["VerifiableCredential", "AlumniCredential"]
        });
        assert!(!satisfies_type_values(&spec_query(), &types(vc).await));
    }

    #[tokio::test]
    async fn matches_terms_as_written() {
        // The contexts define both terms, so their expanded IRIs differ from
        // them; the literal values still match.
        let vc = json!({
            "@context": [VC_V1, VC_V1_EXAMPLES],
            "type": ["VerifiableCredential", "UniversityDegreeCredential"]
        });
        let types = types(vc).await;

        let terms = query(json!({
            "type_values": [["VerifiableCredential", "UniversityDegreeCredential"]]
        }));
        assert!(satisfies_type_values(&terms, &types));
        assert!(!satisfies_type_values(
            &terms,
            &expanded_only(types.clone())
        ));

        // A term and an expanded IRI in one alternative.
        let mixed = query(json!({
            "type_values": [[VC, "UniversityDegreeCredential"]]
        }));
        assert!(satisfies_type_values(&mixed, &types));
    }

    #[tokio::test]
    async fn expands_undefined_types_with_the_vocab() {
        // A `@vocab` maps terms no `@context` defines, so they no longer stay
        // relative.
        let vc = json!({
            "@context": [VC_V2, { "@vocab": "https://example.org/vocab#" }],
            "type": ["VerifiableCredential", "IDCredential"]
        });
        let types = expanded_only(types(vc).await);

        let expanded = query(json!({
            "type_values": [[VC, "https://example.org/vocab#IDCredential"]]
        }));
        assert!(satisfies_type_values(&expanded, &types));

        let relative = query(json!({ "type_values": [["IDCredential"]] }));
        assert!(!satisfies_type_values(&relative, &types));
    }

    #[tokio::test]
    async fn unknown_context_matches_terms_only() {
        let vc = json!({
            "@context": [VC_V1, "https://example.com/unknown/v1"],
            "type": ["VerifiableCredential", "ExampleCredential"]
        });
        assert!(expand_types(&vc, &ContextLoader::default()).await.is_err());
        let types = types(vc).await;

        let terms = query(json!({ "type_values": [["ExampleCredential"]] }));
        assert!(satisfies_type_values(&terms, &types));

        let expanded = query(json!({ "type_values": [[VC]] }));
        assert!(!satisfies_type_values(&expanded, &types));
    }

    #[test]
    fn reads_a_single_type() {
        let vc = json!({ "type": "VerifiableCredential" });
        assert_eq!(literal_types(&vc), ["VerifiableCredential"]);
    }

    #[test]
    fn absent_type_values_places_no_constraint() {
        for types in [
            CredentialTypes::NotApplicable,
            CredentialTypes::W3c {
                expanded: None,
                literal: vec![],
            },
        ] {
            assert!(satisfies_type_values(&query(json!({})), &types));
        }
    }

    #[test]
    fn does_not_apply_to_other_formats() {
        let query = query(json!({ "type_values": [[VC]] }));
        assert!(satisfies_type_values(
            &query,
            &CredentialTypes::NotApplicable
        ));
    }

    #[test]
    fn malformed_type_values_matches_nothing() {
        let types = CredentialTypes::W3c {
            expanded: Some(vec![VC.to_owned()]),
            literal: vec!["VerifiableCredential".to_owned()],
        };
        for malformed in [
            json!([]),
            json!([[]]),
            json!([VC]),
            json!([[VC, 1]]),
            json!(VC),
        ] {
            let query = query(json!({ "type_values": malformed }));
            assert!(!satisfies_type_values(&query, &types), "{malformed}");
        }
    }
}
