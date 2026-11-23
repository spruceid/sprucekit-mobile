use std::collections::HashMap;

use openid4vp::core::dcql_query::CredentialTypes;
use ssi::json_ld::ContextLoader;

use super::error::OID4VPError;
use crate::credential::ParsedCredential;

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
        .map(|credential| credential.w3c_vc_json().map(|vc| vc.into_owned()))
        .collect::<Vec<_>>();

    crate::big_stack::run_async(move || async move {
        let mut expanded = Vec::with_capacity(documents.len());
        for document in documents {
            expanded.push(match document {
                None => CredentialTypes::NotApplicable,
                Some(vc) => CredentialTypes::from_w3c_vc(&vc, &loader).await,
            });
        }
        expanded
    })
    .await
    .map_err(|e| OID4VPError::DcqlQueryResolution(format!("failed to expand types: {e}")))
}
