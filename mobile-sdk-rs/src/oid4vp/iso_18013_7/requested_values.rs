use std::{collections::BTreeMap, sync::Arc};

use anyhow::{bail, Result};
use ciborium::Value as Cbor;
use isomdl::definitions::{device_request::NameSpace, issuer_signed::IssuerSignedItemBytes};
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
                Ok((field_map, requested_fields)) => Some(Arc::new(RequestMatch180137 {
                    field_map,
                    requested_fields,
                    credential_id: credential.id(),
                })),
                Err(e) => {
                    tracing::info!("credential did not match: {e}");
                    None
                }
            },
        )
        .collect()
}

fn find_match(
    input_descriptor: &InputDescriptor,
    credential: &Mdoc,
) -> Result<(FieldMap, Vec<RequestedField180137>)> {
    let mdoc = credential.document();

    if mdoc.mso.doc_type != input_descriptor.id {
        bail!("the request was not for an mDL: {}", input_descriptor.id)
    }

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
                            .map(|(element_identifier, element_value)| {
                                let reference = Uuid::new_v4().to_string();
                                field_map.insert(
                                    FieldId180137(reference.clone()),
                                    (namespace.clone(), element_value.clone()),
                                );
                                (element_identifier.clone(), Json::String(reference))
                            })
                            .collect(),
                    ),
                )
            })
            .collect(),
    );

    let mut requested_fields = Vec::new();

    let elements_json_ref = &elements_json;

    for field in input_descriptor.constraints.fields().iter() {
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

                let field_id = FieldId180137(reference.clone());

                // Find the last "name" in the JSON path expression. This is probably the best name for the requested field.
                let found_name = node
                    .location()
                    .iter()
                    .filter_map(|element| element.as_name())
                    .last();

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

                requested_fields.push(RequestedField180137 {
                    id: field_id,
                    displayable_name,
                    displayable_value,
                    selectively_disclosable: true,
                    intent_to_retain: field.intent_to_retain,
                    required: field.is_required(),
                    purpose: field.purpose.clone(),
                })
            }
            None if field.is_required() => bail!(
                "missing requested field: {}",
                field.path.as_ref()[0].to_string()
            ),
            None => (),
        }
    }

    Ok((field_map, requested_fields))
}

fn cbor_to_string(cbor: &Cbor) -> Option<String> {
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

#[cfg(test)]
mod test {
    use std::{fs::File, sync::Arc};

    use openid4vp::core::presentation_definition::PresentationDefinition;

    use crate::crypto::{KeyAlias, RustTestKeyManager};

    use super::parse_request;

    #[test]
    fn mdl_matches_presentation_definition() {
        let key_manager = Arc::new(RustTestKeyManager::default());
        let key_alias = KeyAlias("".to_string());

        key_manager
            .generate_p256_signing_key(key_alias.clone())
            .unwrap();

        let credentials =
            vec![crate::mdl::util::generate_test_mdl(key_manager, key_alias).unwrap()];

        let presentation_definition: PresentationDefinition = serde_json::from_reader(
            File::open("tests/examples/18013_7_presentation_definition.json").unwrap(),
        )
        .unwrap();

        let request = parse_request(&presentation_definition, credentials.iter());

        assert_eq!(request.len(), 1);

        let request = &request[0];

        assert_eq!(request.requested_fields.len(), 11)
    }
}
