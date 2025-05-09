use std::{collections::BTreeMap, sync::Arc};

use anyhow::{bail, Result};
use ciborium::Value as Cbor;
use isomdl::definitions::{
    device_request::NameSpace, helpers::NonEmptyMap, issuer_signed::IssuerSignedItemBytes,
};
use openid4vp::core::{
    input_descriptor::InputDescriptor, presentation_definition::PresentationDefinition,
};
use serde::{Deserialize, Serialize};
use serde_json::Value as Json;
use uuid::Uuid;

use crate::credential::mdoc::Mdoc;

#[derive(Debug, Clone, uniffi::Object)]
/// A viable match for the credential request.
pub struct RequestMatch180137 {
    pub credential_id: Uuid,
    pub field_map: FieldMap,
    pub requested_fields: Vec<RequestedField180137>,
    pub missing_fields: BTreeMap<String, String>,
}

uniffi::custom_newtype!(FieldId180137, String);
#[derive(Debug, Serialize, Deserialize, PartialEq, Clone, PartialOrd, Ord, Eq)]
/// Opaque field identifier for internal API mappings.
pub struct FieldId180137(pub String);

#[derive(Debug, Clone, uniffi::Record)]
pub struct RequestedField180137 {
    pub id: FieldId180137,
    pub displayable_name: String,
    pub displayable_value: Option<String>,
    pub selectively_disclosable: bool,
    pub intent_to_retain: bool,
    pub required: bool,
    pub purpose: Option<String>,
}

pub type FieldMap = BTreeMap<FieldId180137, (NameSpace, IssuerSignedItemBytes)>;

#[uniffi::export]
impl RequestMatch180137 {
    pub fn credential_id(&self) -> Uuid {
        self.credential_id
    }

    pub fn requested_fields(&self) -> Vec<RequestedField180137> {
        self.requested_fields.clone()
    }
}

pub fn parse_request<'l, C>(
    presentation_definition: &PresentationDefinition,
    credentials: C,
) -> Vec<Arc<RequestMatch180137>>
where
    C: Iterator<Item = &'l Mdoc>,
{
    tracing::debug!("processing request: {:#?}", presentation_definition);

    let input_descriptors = presentation_definition.input_descriptors().as_slice();
    let input_descriptor = match input_descriptors {
        [] => {
            tracing::warn!("presentation contained no input descriptors");
            return vec![];
        }
        [input_descriptor] => input_descriptor,
        [input_descriptor, ..] => {
            tracing::warn!("only handling the first request");
            input_descriptor
        }
    };

    credentials
        .filter_map(
            |credential| match find_match(input_descriptor, credential) {
                Ok(m) => Some(Arc::new(m)),
                Err(e) => {
                    tracing::info!("credential did not match: {e}");
                    None
                }
            },
        )
        .collect()
}

fn find_match(input_descriptor: &InputDescriptor, credential: &Mdoc) -> Result<RequestMatch180137> {
    let mdoc = credential.document();

    if mdoc.mso.doc_type != input_descriptor.id {
        bail!("the request was not for an mDL: {}", input_descriptor.id)
    }

    let mut age_over_mapping = calculate_age_over_mapping(&mdoc.namespaces);

    let mut field_map = FieldMap::new();

    let elements_json = Json::Object(
        mdoc.namespaces
            .iter()
            .map(|(namespace, elements)| {
                (
                    namespace.clone(),
                    Json::Object(
                        elements
                            .iter()
                            .flat_map(|(element_identifier, element_value)| {
                                let reference = Uuid::new_v4().to_string();
                                field_map.insert(
                                    FieldId180137(reference.clone()),
                                    (namespace.clone(), element_value.clone()),
                                );
                                [(element_identifier.clone(), Json::String(reference.clone()))]
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
                                        .flat_map(|virtual_element_ids| {
                                            virtual_element_ids.into_iter()
                                        })
                                        .map(
                                            move |virtual_element_id| {
                                                (
                                                    virtual_element_id,
                                                    Json::String(reference.clone()),
                                                )
                                            },
                                        ),
                                    )
                            })
                            .collect(),
                    ),
                )
            })
            .collect(),
    );

    let mut requested_fields = BTreeMap::new();
    let mut missing_fields = BTreeMap::new();

    let elements_json_ref = &elements_json;

    'fields: for field in input_descriptor.constraints.fields().iter() {
        match field
            .path
            .iter()
            .flat_map(|json_path| json_path.query_located(elements_json_ref).into_iter())
            .next()
        {
            Some(node) => {
                let Json::String(reference) = node.node() else {
                    bail!("unexpected type {:?}", node.node())
                };

                // Deduplicating, for example if there are duplicate requests, or multiple age attestation
                // requests that are serviced by the same response.
                if requested_fields.contains_key(reference) {
                    continue 'fields;
                }

                let field_id = FieldId180137(reference.clone());

                // Find the last "name" in the JSON path expression. This is probably the best name for the requested field.
                let found_name = node
                    .location()
                    .iter()
                    .filter_map(|element| element.as_name())
                    .next_back();

                let displayable_name = match found_name {
                    Some(name) => name.to_string(),
                    None => {
                        if node.location().is_empty() {
                            "Everything".to_string()
                        } else {
                            node.location().to_json_pointer()
                        }
                    }
                };

                let displayable_value = field_map
                    .get(&field_id)
                    .and_then(|value| cbor_to_string(&value.1.as_ref().element_value));

                requested_fields.insert(
                    reference.clone(),
                    RequestedField180137 {
                        id: field_id,
                        displayable_name,
                        displayable_value,
                        selectively_disclosable: true,
                        intent_to_retain: field.intent_to_retain,
                        required: field.is_required(),
                        purpose: field.purpose.clone(),
                    },
                );
            }
            None => {
                let json_path = field.path.as_ref()[0].to_string();
                if let Some((namespace, element_identifier)) = split_json_path(&json_path) {
                    missing_fields.insert(namespace, element_identifier);
                } else {
                    tracing::warn!("invalid JSON path expression: {json_path}")
                }
            }
        }
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

fn split_json_path(json_path: &str) -> Option<(String, String)> {
    // Find the namespace between "$['" and "']['"".
    let (namespace, rest) = json_path.strip_prefix("$['")?.split_once("']['")?;
    // Find the element identifier up to "']".
    let (element_id, "") = rest.split_once("']")? else {
        // Unexpected trailing characters.
        return None;
    };

    Some((namespace.to_string(), element_id.to_string()))
}

pub fn cbor_to_string(cbor: &Cbor) -> Option<String> {
    cbor_to_string_inner(cbor, 3)
}

fn cbor_to_string_inner(cbor: &Cbor, allowed_depth: u8) -> Option<String> {
    if allowed_depth == 0 {
        return None;
    }

    match cbor {
        Cbor::Text(t) => Some(t.clone()),
        Cbor::Integer(integer) => Some(<i128>::from(integer.to_owned()).to_string()),
        Cbor::Float(float) => Some(float.to_string()),
        Cbor::Bool(b) => Some(b.to_string()),
        Cbor::Tag(_, value) => cbor_to_string_inner(value.as_ref(), allowed_depth - 1),
        _ => None,
    }
}

fn age_from_str(s: &str) -> Option<u8> {
    let mut chars = s.chars();
    let first = match chars.next() {
        Some(d @ '0'..='9') => d,
        _ => return None,
    };
    match chars.next() {
        Some(_d @ '0'..='9') => (),
        _ => return None,
    };
    if chars.next().is_some() {
        return None;
    }

    if first == '0' {
        s[1..].parse().ok()
    } else {
        s.parse().ok()
    }
}

pub fn calculate_age_over_mapping(
    namespaces: &NonEmptyMap<String, NonEmptyMap<String, IssuerSignedItemBytes>>,
) -> BTreeMap<String, Vec<String>> {
    let mut age_over_x_elements: Vec<(u8, bool)> = namespaces
        .iter()
        .filter(|(ns, _)| *ns == "org.iso.18013.5.1")
        .flat_map(|(_, elems)| elems.iter())
        .filter_map(|(id, elem)| {
            id.strip_prefix("age_over_")
                .and_then(age_from_str)
                .and_then(|age| elem.as_ref().element_value.as_bool().map(|b| (age, b)))
        })
        .collect();

    age_over_x_elements.sort_by(|a, b| a.0.cmp(&b.0));

    // Transform this mapping from (requested_age -> responded_age) into
    // (responded_age -> requested_age[]]) so that virtual elements for every possible requested_age
    // can be constructed.
    reverse_mapping(age_over_x_elements)
        .into_iter()
        .map(|(request, response)| {
            (
                format!("age_over_{request:02}"),
                format!("age_over_{response:02}"),
            )
        })
        .fold(BTreeMap::new(), |mut acc, (request, response)| {
            if let Some(arr) = acc.get_mut(&response) {
                arr.push(request);
            } else {
                acc.insert(response, vec![request]);
            }
            acc
        })
}

/// Create a reverse mapping of age_over attestation responses, where the key is the requested
/// age and the value is the responding age.
///
/// For example, if we had "age_over_18: true", "age_over_21: true", "age_over_30: false" and
/// "age_over_60: false", then we would want to construct the following mapping:
///
/// 0..=18 -> 18 (requests for age over 0-18 are responded with age_over_18: true)
/// 19..=21 -> 21 (requests for age over 19-21 are responded with age_over_21: true)
/// 22..=29 -> None (requests for age over 22-29 have no response)
/// 30..=59 -> 30 (requests for age over 30-59 are responded with age_over_30: false)
/// 60..=99 -> 30 (requests for age over 30-59 are responded with age_over_30: false)
///
/// This follows the rules defined in ISO/IEC 18013-5 Section 7.2.5.
fn reverse_mapping(age_over_x_elements: Vec<(u8, bool)>) -> BTreeMap<u8, u8> {
    let mut reverse_mapping = BTreeMap::<u8, u8>::new();

    // Starting with the lowest age_over_XX: false claims.
    //
    // Using the above example, before the first iteration the mapping will be:
    // 0..=99 -> None
    //
    // After the first iteration, the mapping will be:
    // 0..=29 -> None
    // 30..=99 -> 30
    //
    // After the second and final iteration, the mapping will be:
    // 0..=29 -> None
    // 30..=59 -> 30
    // 60..=99 -> 60
    for age in age_over_x_elements
        .iter()
        .filter_map(|(age, b)| if !b { Some(age) } else { None })
    {
        for xx in *age..=99 {
            reverse_mapping.insert(xx, *age);
        }
    }

    // Starting with the highest age_over_XX: true claims.
    //
    // Using the above example, before the first iteration the mapping will be:
    // 0..=29 -> None
    // 30..=59 -> 30
    // 60..=99 -> 60
    //
    // After the first iteration, the mapping will be:
    // 0..=21 -> 21
    // 22..=29 -> None
    // 30..=59 -> 30
    // 60..=99 -> 60
    //
    // After the second and final iteration, the mapping will be:
    // 0..=18 -> 18
    // 19..=21 -> 21
    // 22..=29 -> None
    // 30..=59 -> 30
    // 60..=99 -> 60
    for age in age_over_x_elements
        .iter()
        .rev()
        .filter_map(|(age, b)| if *b { Some(age) } else { None })
    {
        for xx in 0..=*age {
            reverse_mapping.insert(xx, *age);
        }
    }

    reverse_mapping
}

#[cfg(test)]
mod test {
    use std::{fs::File, sync::Arc};

    use openid4vp::core::presentation_definition::PresentationDefinition;
    use rstest::rstest;

    use crate::crypto::{KeyAlias, RustTestKeyManager};

    use super::{parse_request, reverse_mapping};

    #[rstest]
    #[case::valid("tests/examples/18013_7_presentation_definition.json", 0)]
    #[case::missing("tests/examples/18013_7_presentation_definition_age_over_25.json", 1)]
    #[tokio::test]
    async fn mdl_matches_presentation_definition(
        #[case] filepath: &str,
        #[case] missing_fields: usize,
    ) {
        let key_manager = Arc::new(RustTestKeyManager::default());
        let key_alias = KeyAlias("".to_string());

        key_manager
            .generate_p256_signing_key(key_alias.clone())
            .await
            .unwrap();

        let credentials =
            vec![crate::mdl::util::generate_test_mdl(key_manager, key_alias).unwrap()];

        let presentation_definition: PresentationDefinition =
            serde_json::from_reader(File::open(filepath).unwrap()).unwrap();

        let request = parse_request(&presentation_definition, credentials.iter());

        assert_eq!(request.len(), 1);

        let request = &request[0];
        assert_eq!(request.requested_fields.len(), 12 - missing_fields);
        assert_eq!(request.missing_fields.len(), missing_fields);
    }

    #[test]
    fn age_attestation_mapping() {
        let reverse_mapping =
            reverse_mapping(vec![(18, true), (21, true), (30, false), (60, false)]);
        assert_eq!(reverse_mapping.len(), 92);

        reverse_mapping
            .into_iter()
            .for_each(|(request, response)| match request {
                0..=18 => assert_eq!(response, 18),
                19..=21 => assert_eq!(response, 21),
                30..=59 => assert_eq!(response, 30),
                60..=99 => assert_eq!(response, 60),
                _ => panic!("unexpected value"),
            })
    }

    #[rstest]
    #[case::valid("$['namespace']['element_id']", true)]
    #[case::invalid("$.namespace.element_id", false)]
    #[case::trailing("$['namespace']['element_id']['extra']", false)]
    fn json_path_splitting(#[case] path: &str, #[case] is_some: bool) {
        let Some((namespace, element_id)) = super::split_json_path(path) else {
            assert!(!is_some);
            return;
        };

        assert_eq!(namespace, "namespace");
        assert_eq!(element_id, "element_id");
    }
}
