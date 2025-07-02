use std::{
    collections::{BTreeMap, HashMap},
    sync::Arc,
};

use itertools::Itertools;
use uuid::Uuid;

use crate::{
    credential::{ParsedCredential, ParsedCredentialInner},
    oid4vp::iso_18013_7::requested_values::{
        calculate_age_over_mapping, cbor_to_string, FieldId180137, FieldMap, RequestMatch180137,
        RequestedField180137,
    },
};

#[derive(uniffi::Object)]
pub struct IOSISO18013MobileDocumentRequest {
    presentment_requests: Vec<Arc<IOSISO18013MobileDocumentRequestPresentmentRequest>>,
}

#[uniffi::export]
impl IOSISO18013MobileDocumentRequest {
    #[uniffi::constructor]
    pub fn new(
        presentment_requests: Vec<Arc<IOSISO18013MobileDocumentRequestPresentmentRequest>>,
    ) -> Arc<Self> {
        Arc::new(Self {
            presentment_requests,
        })
    }

    #[uniffi::method]
    pub fn to_matches(
        &self,
        parsed_credentials: Vec<Arc<ParsedCredential>>,
    ) -> Vec<Arc<RequestMatch180137>> {
        let mut res = vec![];
        for presentment_request in &self.presentment_requests {
            for request_set in &presentment_request.document_request_sets {
                for request in &request_set.requests {
                    for credential in &parsed_credentials {
                        if let ParsedCredentialInner::MsoMdoc(ref mdoc) = credential.inner {
                            if mdoc.doctype() == request.document_type {
                                let doc = mdoc.document();
                                let mut age_over_mapping =
                                    calculate_age_over_mapping(&doc.namespaces);

                                let mut field_map = FieldMap::new();

                                let elements_map: BTreeMap<
                                    String,
                                    BTreeMap<String, FieldId180137>,
                                > = doc
                                    .namespaces
                                    .iter()
                                    .map(|(namespace, elements)| {
                                        (
                                            namespace.clone(),
                                            elements
                                                .iter()
                                                .flat_map(|(element_identifier, element_value)| {
                                                    let field_id =
                                                        FieldId180137(Uuid::new_v4().to_string());
                                                    field_map.insert(
                                                        field_id.clone(),
                                                        (namespace.clone(), element_value.clone()),
                                                    );
                                                    [(element_identifier.clone(), field_id.clone())]
                                                        .into_iter()
                                                        .chain(
                                                            // If there are other age attestations that this element
                                                            // should respond to, insert virtual elements for each
                                                            // of those mappings.
                                                            if namespace == "org.iso.18013.5.1" {
                                                                age_over_mapping
                                                                    .remove(element_identifier)
                                                            } else {
                                                                None
                                                            }
                                                            .into_iter()
                                                            .flat_map(|virtual_element_ids| {
                                                                virtual_element_ids.into_iter()
                                                            })
                                                            .map(move |virtual_element_id| {
                                                                (
                                                                    virtual_element_id,
                                                                    field_id.clone(),
                                                                )
                                                            }),
                                                        )
                                                })
                                                .collect(),
                                        )
                                    })
                                    .collect();

                                let mut requested_fields = BTreeMap::new();
                                let mut missing_fields = BTreeMap::new();

                                for (namespace, elements) in &request.namespaces {
                                    for (element_identifier, element_info) in elements {
                                        let Some(field_id) = elements_map
                                            .get(namespace)
                                            .and_then(|elements| elements.get(element_identifier))
                                        else {
                                            missing_fields.insert(
                                                namespace.clone(),
                                                element_identifier.clone(),
                                            );
                                            continue;
                                        };
                                        let displayable_value =
                                            field_map.get(field_id).and_then(|value| {
                                                cbor_to_string(&value.1.as_ref().element_value)
                                            });

                                        // Snake case to sentence case.
                                        let displayable_name = element_identifier
                                            .split("_")
                                            .map(|s| {
                                                let Some(first_letter) = s.chars().next() else {
                                                    return s.to_string();
                                                };
                                                format!(
                                                    "{}{}",
                                                    first_letter.to_uppercase(),
                                                    &s[1..]
                                                )
                                            })
                                            .join(" ");

                                        requested_fields.insert(
                                            field_id.0.clone(),
                                            RequestedField180137 {
                                                id: field_id.clone(),
                                                displayable_name,
                                                displayable_value,
                                                selectively_disclosable: true,
                                                intent_to_retain: element_info.is_retaining,
                                                required: true,
                                                purpose: None,
                                            },
                                        );
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
                                res.push(Arc::new(RequestMatch180137 {
                                    credential_id: mdoc.id(),
                                    field_map,
                                    requested_fields,
                                    missing_fields,
                                }));
                            }
                        }
                    }
                }
            }
        }
        res
    }
}

#[derive(uniffi::Object)]
pub struct IOSISO18013MobileDocumentRequestPresentmentRequest {
    document_request_sets: Vec<Arc<IOSISO18013MobileDocumentRequestDocumentRequestSet>>,
    is_mandatory: bool,
}

#[uniffi::export]
impl IOSISO18013MobileDocumentRequestPresentmentRequest {
    #[uniffi::constructor]
    pub fn new(
        document_request_sets: Vec<Arc<IOSISO18013MobileDocumentRequestDocumentRequestSet>>,
        is_mandatory: bool,
    ) -> Arc<Self> {
        Arc::new(Self {
            document_request_sets,
            is_mandatory,
        })
    }
}

#[derive(uniffi::Object)]
pub struct IOSISO18013MobileDocumentRequestDocumentRequestSet {
    requests: Vec<Arc<IOSISO18013MobileDocumentRequestDocumentRequest>>,
}

#[uniffi::export]
impl IOSISO18013MobileDocumentRequestDocumentRequestSet {
    #[uniffi::constructor]
    pub fn new(requests: Vec<Arc<IOSISO18013MobileDocumentRequestDocumentRequest>>) -> Arc<Self> {
        Arc::new(Self { requests })
    }
}

#[derive(uniffi::Object)]
pub struct IOSISO18013MobileDocumentRequestDocumentRequest {
    document_type: String,
    namespaces: HashMap<String, HashMap<String, Arc<IOSISO18013MobileDocumentRequestElementInfo>>>,
}

#[uniffi::export]
impl IOSISO18013MobileDocumentRequestDocumentRequest {
    #[uniffi::constructor]
    pub fn new(
        document_type: String,
        namespaces: HashMap<
            String,
            HashMap<String, Arc<IOSISO18013MobileDocumentRequestElementInfo>>,
        >,
    ) -> Arc<Self> {
        Arc::new(Self {
            document_type,
            namespaces,
        })
    }
}

#[derive(uniffi::Object)]
pub struct IOSISO18013MobileDocumentRequestElementInfo {
    is_retaining: bool,
}

#[uniffi::export]
impl IOSISO18013MobileDocumentRequestElementInfo {
    #[uniffi::constructor]
    pub fn new(is_retaining: bool) -> Arc<Self> {
        Arc::new(Self { is_retaining })
    }
}
