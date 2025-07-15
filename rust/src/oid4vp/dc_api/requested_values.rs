use std::collections::BTreeMap;

use anyhow::{bail, Result};
use itertools::Itertools;
use openid4vp::core::dcql_query::{DcqlCredentialClaimsQueryPath, DcqlCredentialQuery};
use uuid::Uuid;

use crate::{
    credential::mdoc::Mdoc,
    oid4vp::iso_18013_7::requested_values::{
        calculate_age_over_mapping, cbor_to_string, FieldId180137, FieldMap, RequestMatch180137,
        RequestedField180137,
    },
};

/// Find the match between a query and a credential.
pub fn find_match(query: &DcqlCredentialQuery, credential: &Mdoc) -> Result<RequestMatch180137> {
    let mdoc = credential.document();

    if let Some(doc_type) = query
        .meta()
        .and_then(|meta| meta.get("doctype_value"))
        .and_then(|value| value.as_str())
    {
        if doc_type != mdoc.mso.doc_type {
            bail!("the request was not for an mDL: {}", doc_type)
        }
    }

    let mut age_over_mapping = calculate_age_over_mapping(&mdoc.namespaces);

    let mut field_map = FieldMap::new();

    let elements_map: BTreeMap<String, BTreeMap<String, FieldId180137>> = mdoc
        .namespaces
        .iter()
        .map(|(namespace, elements)| {
            (
                namespace.clone(),
                elements
                    .iter()
                    .flat_map(|(element_identifier, element_value)| {
                        let field_id = FieldId180137(Uuid::new_v4().to_string());
                        field_map
                            .insert(field_id.clone(), (namespace.clone(), element_value.clone()));
                        [(element_identifier.clone(), field_id.clone())]
                            .into_iter()
                            .chain(
                                // If there are other age attestations that this element
                                // should respond to, insert virtual elements for each
                                // of those mappings.
                                if namespace == "org.iso.18013.5.1" {
                                    age_over_mapping.remove(element_identifier)
                                } else {
                                    None
                                }
                                .into_iter()
                                .flat_map(|virtual_element_ids| virtual_element_ids.into_iter())
                                .map(move |virtual_element_id| {
                                    (virtual_element_id, field_id.clone())
                                }),
                            )
                    })
                    .collect(),
            )
        })
        .collect();

    let mut requested_fields = BTreeMap::new();
    let mut missing_fields = BTreeMap::new();

    'fields: for field in query
        .claims()
        .into_iter()
        .flat_map(|queries| queries.iter())
    {
        let Some(DcqlCredentialClaimsQueryPath::String(namespace)) = field.path().first() else {
            tracing::warn!(
                "no valid namespace provided in query: {:?}",
                field.path().first()
            );
            continue 'fields;
        };
        let Some(DcqlCredentialClaimsQueryPath::String(element_identifier)) = field.path().get(1)
        else {
            tracing::warn!(
                "no valid element identifier provided in query: {:?}",
                field.path().get(1)
            );
            continue 'fields;
        };
        let Some(field_id) = elements_map
            .get(namespace)
            .and_then(|elements| elements.get(element_identifier))
        else {
            missing_fields.insert(namespace.clone(), element_identifier.clone());
            continue 'fields;
        };
        let displayable_value = field_map
            .get(field_id)
            .and_then(|value| cbor_to_string(&value.1.as_ref().element_value));

        // Snake case to sentence case.
        let displayable_name = element_identifier
            .split("_")
            .map(|s| {
                let Some(first_letter) = s.chars().next() else {
                    return s.to_string();
                };
                format!("{}{}", first_letter.to_uppercase(), &s[1..])
            })
            .join(" ");

        requested_fields.insert(
            field_id.0.clone(),
            RequestedField180137 {
                id: field_id.clone(),
                displayable_name,
                displayable_value,
                selectively_disclosable: true,
                intent_to_retain: field.intent_to_retain().unwrap_or(false),
                required: true,
                purpose: None,
            },
        );
    }

    let mut seen_age_over_attestations = 0;
    let requested_fields = requested_fields
        .into_values()
        // According to the rules in ISO/IEC 18013-5 Section 7.2.5, don't respond with more
        // than 2 age over attestations.
        .filter(|field| {
            if field.displayable_name.starts_with("age_over_") {
                seen_age_over_attestations += 1;
                seen_age_over_attestations < 3
            } else {
                true
            }
        })
        .collect();

    Ok(RequestMatch180137 {
        credential_id: credential.id(),
        field_map,
        requested_fields,
        missing_fields,
    })
}
