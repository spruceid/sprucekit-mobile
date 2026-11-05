use openid4vp::core::dcql_query::{
    DcqlCredentialClaimsQuery, DcqlCredentialClaimsQueryPath, DcqlCredentialClaimsQueryValue,
    DcqlCredentialQuery,
};
use serde_json::Value;

pub fn select_claims<'a>(
    root: &'a Value,
    path: &[DcqlCredentialClaimsQueryPath],
) -> Vec<&'a Value> {
    let mut selected = vec![root];
    for component in path {
        let mut next = Vec::new();
        for value in selected {
            match component {
                DcqlCredentialClaimsQueryPath::String(key) => {
                    if let Some(child) = value.as_object().and_then(|o| o.get(key)) {
                        next.push(child);
                    }
                }
                DcqlCredentialClaimsQueryPath::Integer(index) => {
                    if let Some(child) = value.as_array().and_then(|a| a.get(*index)) {
                        next.push(child);
                    }
                }
                DcqlCredentialClaimsQueryPath::Null => match value.as_array() {
                    Some(items) => next.extend(items.iter()),
                    None => return vec![],
                },
            }
        }
        if next.is_empty() {
            return vec![];
        }
        selected = next;
    }
    selected
}

fn value_matches(claim: &Value, expected: &DcqlCredentialClaimsQueryValue) -> bool {
    match expected {
        DcqlCredentialClaimsQueryValue::String(s) => claim.as_str() == Some(s.as_str()),
        DcqlCredentialClaimsQueryValue::Integer(i) => claim.as_i64() == Some(*i as i64),
        DcqlCredentialClaimsQueryValue::Boolean(b) => claim.as_bool() == Some(*b),
    }
}

fn claim_value_constraint_holds(root: &Value, claim: &DcqlCredentialClaimsQuery) -> bool {
    let Some(expected) = claim.values() else {
        return true;
    };
    select_claims(root, claim.path())
        .into_iter()
        .any(|selected| expected.iter().any(|e| value_matches(selected, e)))
}

pub fn claim_values_satisfied(credential_query: &DcqlCredentialQuery, root: &Value) -> bool {
    let Some(claims) = credential_query.claims() else {
        return true;
    };

    match credential_query.claim_sets() {
        None => claims
            .iter()
            .all(|claim| claim_value_constraint_holds(root, claim)),
        Some(claim_sets) => claim_sets.iter().any(|set| {
            set.iter().all(|claim_id| {
                claims
                    .iter()
                    .find(|c| c.id() == Some(claim_id))
                    .is_some_and(|claim| claim_value_constraint_holds(root, claim))
            })
        }),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn query(value: Value) -> DcqlCredentialQuery {
        serde_json::from_value(value).expect("valid credential query")
    }

    fn registration() -> Value {
        json!({
            "type": ["VerifiableCredential", "VehicleRegistrationCredential"],
            "credentialSubject": { "licensePlateIdentifier": "2GKK061", "doors": 4, "active": true }
        })
    }

    fn insurance() -> Value {
        json!({ "type": ["VerifiableCredential", "VehicleInsuranceCredential"] })
    }

    fn registration_query() -> DcqlCredentialQuery {
        query(json!({
            "id": "vehicle_registration",
            "format": "ldp_vc",
            "meta": { "type_values": [["https://www.w3.org/2018/credentials#VerifiableCredential"]] },
            "claims": [{ "path": ["type", null], "values": ["VehicleRegistrationCredential"] }]
        }))
    }

    #[test]
    fn type_claim_with_null_selects_matching_credential_type() {
        assert!(claim_values_satisfied(
            &registration_query(),
            &registration()
        ));
    }

    #[test]
    fn type_claim_with_null_rejects_other_credential_types() {
        assert!(!claim_values_satisfied(&registration_query(), &insurance()));
    }

    #[test]
    fn jsonpath_style_component_is_a_literal_key_and_selects_nothing() {
        let q = query(json!({
            "id": "q",
            "format": "ldp_vc",
            "meta": {},
            "claims": [{ "path": ["$.type"], "values": ["VehicleRegistrationCredential"] }]
        }));
        assert!(!claim_values_satisfied(&q, &registration()));
    }

    #[test]
    fn claims_without_values_do_not_filter() {
        let q = query(json!({
            "id": "q",
            "format": "ldp_vc",
            "meta": {},
            "claims": [{ "path": ["credentialSubject", "missing"] }]
        }));
        assert!(claim_values_satisfied(&q, &insurance()));
    }

    #[test]
    fn query_without_claims_does_not_filter() {
        let q = query(json!({ "id": "q", "format": "ldp_vc", "meta": {} }));
        assert!(claim_values_satisfied(&q, &insurance()));
    }

    #[test]
    fn integer_and_boolean_values_match_exactly() {
        let q = query(json!({
            "id": "q",
            "format": "ldp_vc",
            "meta": {},
            "claims": [
                { "path": ["credentialSubject", "doors"], "values": [4] },
                { "path": ["credentialSubject", "active"], "values": [true] }
            ]
        }));
        assert!(claim_values_satisfied(&q, &registration()));
    }

    #[test]
    fn integer_path_component_selects_array_index() {
        let q = query(json!({
            "id": "q",
            "format": "ldp_vc",
            "meta": {},
            "claims": [{ "path": ["type", 1], "values": ["VehicleRegistrationCredential"] }]
        }));
        assert!(claim_values_satisfied(&q, &registration()));
        assert!(!claim_values_satisfied(&q, &insurance()));
    }

    #[test]
    fn null_component_on_non_array_selects_nothing() {
        let q = query(json!({
            "id": "q",
            "format": "ldp_vc",
            "meta": {},
            "claims": [{ "path": ["credentialSubject", null], "values": ["x"] }]
        }));
        assert!(!claim_values_satisfied(&q, &registration()));
    }

    #[test]
    fn claim_sets_need_only_one_satisfied_set() {
        let q = query(json!({
            "id": "q",
            "format": "ldp_vc",
            "meta": {},
            "claims": [
                { "id": "reg", "path": ["type", null], "values": ["VehicleRegistrationCredential"] },
                { "id": "ins", "path": ["type", null], "values": ["VehicleInsuranceCredential"] }
            ],
            "claim_sets": [["reg"], ["ins"]]
        }));
        assert!(claim_values_satisfied(&q, &registration()));
        assert!(claim_values_satisfied(&q, &insurance()));
        assert!(!claim_values_satisfied(
            &q,
            &json!({ "type": ["VerifiableCredential"] })
        ));
    }
}
