use crate::credential::{CredentialEncodingError, ParsedCredential, ParsedCredentialInner};

use super::presentation::Draft18PresentationError;

use super::{
    error::Draft18OID4VPError,
    permission_request::{Draft18RequestedField, Draft18ResponseOptions},
    presentation::Draft18PresentationOptions,
};

use std::sync::Arc;

use base64::{engine::general_purpose::URL_SAFE, prelude::*};
use openidvp_draft18::{
    core::{
        credential_format::ClaimFormatDesignation,
        presentation_definition::PresentationDefinition,
        presentation_submission::DescriptorMap,
        response::parameters::VpTokenItem,
    },
    JsonPath,
};
use ssi::{
    claims::{
        jws::Header,
        jwt::AnyClaims,
        vc::{
            syntax::{IdOr, NonEmptyObject, NonEmptyVec},
            v1::JsonPresentation,
            v2::{
                syntax::JsonPresentation as JsonPresentationV2,
                JsonCredential as JsonCredentialV2,
            },
        },
    },
    json_ld::iref::UriBuf,
    prelude::{AnyJsonCredential, AnyJsonPresentation},
    JsonPointerBuf,
};
use uuid::Uuid;

const ACCEPTED_CRYPTOSUITES: &[&str] = &["ecdsa-rdfc-2019"];

/// Draft18-compatible Draft18PresentableCredential with the original field structure.
///
/// The current `Draft18PresentableCredential` uses DCQL-based fields (`credential_query_id`),
/// but draft18 used presentation-definition-based fields (`input_descriptor_id`, `limit_disclosure`).
#[derive(Debug, Clone, uniffi::Object)]
pub struct Draft18PresentableCredential {
    pub(crate) inner: ParsedCredentialInner,
    pub(crate) limit_disclosure: bool,
    pub(crate) selected_fields: Option<Vec<String>>,
    /// The ID of the input descriptor that matches the credential being presented.
    pub(crate) input_descriptor_id: String,
}

#[uniffi::export]
impl Draft18PresentableCredential {
    /// Converts to the primitive ParsedCredential type.
    pub fn as_parsed_credential(&self) -> Arc<ParsedCredential> {
        Arc::new(ParsedCredential {
            inner: self.inner.clone(),
        })
    }

    /// Return the input descriptor id that matched this credential.
    pub fn input_descriptor_id(&self) -> String {
        self.input_descriptor_id.clone()
    }

    /// Return if the credential supports selective disclosure.
    pub fn selective_disclosable(&self) -> bool {
        matches!(
            &self.inner,
            ParsedCredentialInner::VCDM2SdJwt(_) | ParsedCredentialInner::DcSdJwt(_)
        )
    }
}


// -- Helpers for getting credential format designations using openidvp_draft18 types --

fn credential_format_designation(inner: &ParsedCredentialInner) -> ClaimFormatDesignation {
    match inner {
        ParsedCredentialInner::JwtVcJson(_) => ClaimFormatDesignation::JwtVcJson,
        ParsedCredentialInner::JwtVcJsonLd(_) => ClaimFormatDesignation::JwtVcJson,
        ParsedCredentialInner::LdpVc(_) => ClaimFormatDesignation::LdpVc,
        ParsedCredentialInner::VCDM2SdJwt(_) => {
            ClaimFormatDesignation::Other("vcdm2_sd_jwt".into())
        }
        ParsedCredentialInner::MsoMdoc(_) => ClaimFormatDesignation::Other("mso_mdoc".into()),
        ParsedCredentialInner::Cwt(_) => ClaimFormatDesignation::Other("cwt".into()),
        ParsedCredentialInner::DcSdJwt(_) => ClaimFormatDesignation::Other("dc+sd-jwt".into()),
    }
}

fn presentation_format_designation(inner: &ParsedCredentialInner) -> ClaimFormatDesignation {
    match inner {
        ParsedCredentialInner::JwtVcJson(_) | ParsedCredentialInner::JwtVcJsonLd(_) => {
            ClaimFormatDesignation::JwtVpJson
        }
        ParsedCredentialInner::LdpVc(_) => ClaimFormatDesignation::LdpVp,
        ParsedCredentialInner::VCDM2SdJwt(_) => {
            ClaimFormatDesignation::Other("vcdm2_sd_jwt".into())
        }
        ParsedCredentialInner::MsoMdoc(_) => ClaimFormatDesignation::Other("mso_mdoc".into()),
        ParsedCredentialInner::Cwt(_) => ClaimFormatDesignation::Other("cwt".into()),
        ParsedCredentialInner::DcSdJwt(_) => ClaimFormatDesignation::Other("dc+sd-jwt".into()),
    }
}

/// Serialize the inner credential to a JSON value for presentation definition matching.
fn credential_as_json(inner: &ParsedCredentialInner) -> Option<serde_json::Value> {
    match inner {
        ParsedCredentialInner::JwtVcJson(vc) | ParsedCredentialInner::JwtVcJsonLd(vc) => {
            // The payload JSON string contains the full JWT payload.
            serde_json::from_str(&vc.jws_payload_as_json_encoded_utf8_string()).ok()
        }
        ParsedCredentialInner::LdpVc(vc) => Some(vc.raw.clone()),
        ParsedCredentialInner::VCDM2SdJwt(sd) => serde_json::to_value(&sd.credential).ok(),
        _ => None,
    }
}

// -- Extension trait for ParsedCredential to add draft18-compatible methods --

/// Extension methods for `ParsedCredential` that use draft18 `PresentationDefinition`.
pub trait ParsedCredentialDraft18Ext {
    /// Check if the credential satisfies a draft18 presentation definition.
    fn satisfies_presentation_definition(&self, definition: &PresentationDefinition) -> bool;

    /// Return the requested fields for the credential based on a draft18 presentation definition.
    fn requested_fields(
        &self,
        definition: &PresentationDefinition,
    ) -> Vec<Arc<Draft18RequestedField>>;
}

impl ParsedCredentialDraft18Ext for ParsedCredential {
    fn satisfies_presentation_definition(&self, definition: &PresentationDefinition) -> bool {
        let cred_fmt = credential_format_designation(&self.inner);
        let pres_fmt = presentation_format_designation(&self.inner);

        // If the credential does not match the definition requested format,
        // then return false.
        if !definition.format().is_empty()
            && !definition.contains_format(cred_fmt)
            && !definition.contains_format(pres_fmt)
        {
            log::debug!(
                "Credential does not match the presentation definition requested format: {:?}.",
                definition.format()
            );
            return false;
        }

        let Some(json) = credential_as_json(&self.inner) else {
            log::error!("Failed to serialize credential into JSON.");
            return false;
        };

        definition.is_credential_match(&json)
    }

    fn requested_fields(
        &self,
        definition: &PresentationDefinition,
    ) -> Vec<Arc<Draft18RequestedField>> {
        let Some(json) = credential_as_json(&self.inner) else {
            log::error!("credential could not be converted to JSON");
            return Vec::new();
        };

        definition
            .requested_fields(&json)
            .into_iter()
            .map(Into::into)
            .map(Arc::new)
            .collect()
    }
}

impl ParsedCredentialDraft18Ext for Arc<ParsedCredential> {
    fn satisfies_presentation_definition(&self, definition: &PresentationDefinition) -> bool {
        (**self).satisfies_presentation_definition(definition)
    }

    fn requested_fields(
        &self,
        definition: &PresentationDefinition,
    ) -> Vec<Arc<Draft18RequestedField>> {
        (**self).requested_fields(definition)
    }
}

// -- Draft18PresentableCredential methods for VP token and descriptor map --

impl Draft18PresentableCredential {
    /// Return a VP Token from the credential, given provided
    /// options for constructing the VP Token.
    pub async fn as_vp_token<'a>(
        &self,
        options: &'a Draft18PresentationOptions<'a>,
    ) -> Result<VpTokenItem, Draft18OID4VPError> {
        match &self.inner {
            ParsedCredentialInner::VCDM2SdJwt(sd_jwt) => {
                self.sd_jwt_as_vp_token_item(sd_jwt, options).await
            }
            ParsedCredentialInner::JwtVcJson(vc) | ParsedCredentialInner::JwtVcJsonLd(vc) => {
                self.jwt_vc_as_vp_token_item(vc, options).await
            }
            ParsedCredentialInner::LdpVc(vc) => {
                self.json_vc_as_vp_token_item(vc, options).await
            }
            _ => Err(CredentialEncodingError::VpToken(format!(
                "Credential encoding for VP Token is not implemented for {:?}.",
                self.inner,
            ))
            .into()),
        }
    }

    /// Return the descriptor map with the associated format type of the inner credential.
    pub fn create_descriptor_map(
        self: &Arc<Self>,
        options: Draft18ResponseOptions,
        input_descriptor_id: impl Into<String>,
        index: Option<usize>,
    ) -> Result<DescriptorMap, Draft18OID4VPError> {
        match &self.inner {
            ParsedCredentialInner::VCDM2SdJwt(_) => {
                self.sd_jwt_create_descriptor_map(input_descriptor_id, index)
            }
            ParsedCredentialInner::JwtVcJson(_) | ParsedCredentialInner::JwtVcJsonLd(_) => {
                self.jwt_vc_create_descriptor_map(options, input_descriptor_id, index)
            }
            ParsedCredentialInner::LdpVc(_) => {
                self.json_vc_create_descriptor_map(input_descriptor_id, index)
            }
            _ => unimplemented!(
                "create_descriptor_map not implemented for {:?}",
                self.inner
            ),
        }
    }

    // -- SD-JWT VP Token --

    async fn sd_jwt_as_vp_token_item<'a>(
        &self,
        sd_jwt: &crate::credential::vcdm2_sd_jwt::VCDM2SdJwt,
        _options: &'a Draft18PresentationOptions<'a>,
    ) -> Result<VpTokenItem, Draft18OID4VPError> {
        if self.limit_disclosure {
            return Err(Draft18OID4VPError::LimitDisclosure(
                "Limit disclosure is required but is not supported.".to_string(),
            ));
        }

        let compact: &str = sd_jwt.inner.as_ref();
        let vp_token = if let Some(ref selected_fields) = self.selected_fields {
            let json = sd_jwt
                .revealed_claims_as_json()
                .map_err(|e| CredentialEncodingError::VpToken(format!("{e:?}")))?;

            let selected_fields_pointers = selected_fields
                .iter()
                .map(|sfield| {
                    let path = sfield.split(",").next().unwrap().to_owned();
                    let path = match URL_SAFE.decode(&path) {
                        Ok(path) => path,
                        Err(err) => return Err(Draft18OID4VPError::JsonPathParse(err.to_string())),
                    };
                    let path = match std::str::from_utf8(&path) {
                        Ok(path) => path,
                        Err(err) => return Err(Draft18OID4VPError::JsonPathParse(err.to_string())),
                    };
                    let path = match openidvp_draft18::JsonPath::parse(path) {
                        Ok(path) => path,
                        Err(err) => return Err(Draft18OID4VPError::JsonPathParse(err.to_string())),
                    };
                    let located_node = path.query_located(&json);

                    if located_node.is_empty() {
                        Err(Draft18OID4VPError::JsonPathResolve(format!(
                            "Unable to resolve JsonPath: {path}"
                        )))
                    } else {
                        JsonPointerBuf::new(
                            located_node.first().unwrap().location().to_json_pointer(),
                        )
                        .map_err(|e| Draft18OID4VPError::JsonPathToPointer(e.to_string()))
                    }
                })
                .collect::<Result<Vec<_>, _>>()?;

            sd_jwt
                .inner
                .decode_reveal::<AnyClaims>()
                .map_err(|e| Draft18OID4VPError::Debug(e.to_string()))?
                .retaining(&selected_fields_pointers)
                .into_encoded()
                .as_str()
                .to_string()
        } else {
            compact.to_string()
        };

        Ok(VpTokenItem::String(vp_token))
    }

    fn sd_jwt_create_descriptor_map(
        &self,
        input_descriptor_id: impl Into<String>,
        index: Option<usize>,
    ) -> Result<DescriptorMap, Draft18OID4VPError> {
        let path = match index {
            None => JsonPath::default(),
            Some(i) => format!("$[{i}]")
                .parse()
                .map_err(|e| Draft18OID4VPError::JsonPathParse(format!("{e:?}")))?,
        };

        Ok(DescriptorMap::new(
            input_descriptor_id,
            ClaimFormatDesignation::Other("vcdm2_sd_jwt".into()),
            path,
        ))
    }

    // -- JWT VC VP Token --

    async fn jwt_vc_as_vp_token_item<'a>(
        &self,
        vc: &crate::credential::jwt_vc::JwtVc,
        options: &'a Draft18PresentationOptions<'a>,
    ) -> Result<VpTokenItem, Draft18OID4VPError> {
        let vm = options.verification_method_id().await?.to_string();
        let holder_id = options.signer.did();

        let subject = vc
            .credential()
            .credential_subjects
            .iter()
            .flat_map(|obj| obj.get("id"))
            .find(|id| id.as_str() == Some(&holder_id));

        if subject.is_none() {
            return Err(Draft18OID4VPError::VpTokenCreate(
                "supplied verificationMethod does not match the subject of the jwt-vc".into(),
            ));
        }

        let mut vp = serde_json::to_value(JsonPresentation::new(
            UriBuf::new(format!("urn:uuid:{}", Uuid::new_v4()).as_bytes().to_vec()).ok(),
            holder_id.parse().ok(),
            vec![vc.jws.clone()],
        ))
        .map_err(|e| Draft18OID4VPError::VpTokenCreate(format!("{e:?}")))?;

        if options.response_options.force_array_serialization {
            if let Some(vc_val) = vp.get_mut("verifiableCredential") {
                if vc_val.is_object() || vc_val.is_string() {
                    let vc_obj = vc_val.take();
                    *vc_val = serde_json::Value::Array(vec![vc_obj]);
                }
            }
        }

        let iat = time::OffsetDateTime::now_utc().unix_timestamp();
        let exp = iat + 3600;

        let iss = options.issuer();
        let aud = options.audience();
        let nonce = options.nonce();
        let subject = options.subject();

        let key_id = Some(vm);
        let algorithm = options.signer.algorithm().try_into().map_err(|e| {
            CredentialEncodingError::VpToken(format!("Invalid Signing Algorithm: {e:?}"))
        })?;

        let header = Header {
            algorithm,
            key_id,
            ..Default::default()
        };

        let header_b64: String = serde_json::to_vec(&header)
            .map(|b| BASE64_URL_SAFE_NO_PAD.encode(b))
            .map_err(|e| CredentialEncodingError::VpToken(format!("{e:?}")))?;

        let claims = serde_json::json!({
            "iat": iat,
            "exp": exp,
            "iss": iss,
            "sub": subject,
            "aud": aud,
            "nonce": nonce,
            "vp": vp,
        });

        let body_b64 = serde_json::to_vec(&claims)
            .map(|b| BASE64_URL_SAFE_NO_PAD.encode(b))
            .map_err(|e| CredentialEncodingError::VpToken(format!("{e:?}")))?;

        let unsigned_vp_token_jwt = format!("{header_b64}.{body_b64}");

        let signature = options
            .signer
            .sign(unsigned_vp_token_jwt.as_bytes().to_vec())
            .await
            .map_err(|e| CredentialEncodingError::VpToken(format!("{e:?}")))?;

        let signature = options
            .curve_utils()
            .map(|utils| utils.ensure_raw_fixed_width_signature_encoding(signature))?
            .ok_or(Draft18OID4VPError::Presentation(Draft18PresentationError::Signing(
                "Unsupported signature encoding.".into(),
            )))?;

        let signature_b64 = BASE64_URL_SAFE_NO_PAD.encode(&signature);

        Ok(VpTokenItem::String(format!(
            "{unsigned_vp_token_jwt}.{signature_b64}"
        )))
    }

    fn jwt_vc_create_descriptor_map(
        &self,
        options: Draft18ResponseOptions,
        input_descriptor_id: impl Into<String>,
        index: Option<usize>,
    ) -> Result<DescriptorMap, Draft18OID4VPError> {
        let id = input_descriptor_id.into();
        let vp_path = if options.remove_vp_path_prefix {
            "$"
        } else {
            "$.vp"
        }
        .parse()
        .map_err(|e| Draft18OID4VPError::JsonPathParse(format!("{e:?}")))?;

        let cred_path = match index {
            Some(idx) => format!("$.verifiableCredential[{idx}]"),
            None => {
                if options.force_array_serialization {
                    "$.verifiableCredential[0]".into()
                } else {
                    "$.verifiableCredential".into()
                }
            }
        }
        .parse()
        .map_err(|e| Draft18OID4VPError::JsonPathParse(format!("{e:?}")))?;

        Ok(
            DescriptorMap::new(id.clone(), ClaimFormatDesignation::JwtVpJson, vp_path)
                .set_path_nested(DescriptorMap::new(
                    id,
                    ClaimFormatDesignation::JwtVcJson,
                    cred_path,
                )),
        )
    }

    // -- JSON-LD VC VP Token --

    async fn json_vc_as_vp_token_item<'a>(
        &self,
        vc: &crate::credential::json_vc::JsonVc,
        options: &'a Draft18PresentationOptions<'a>,
    ) -> Result<VpTokenItem, Draft18OID4VPError> {
        let id = UriBuf::new(format!("urn:uuid:{}", Uuid::new_v4()).as_bytes().to_vec())
            .map_err(|e| CredentialEncodingError::VpToken(format!("Error parsing ID: {e:?}")))?;

        // Check the signer supports the requested vp format crypto suite.
        options.supports_security_method(ClaimFormatDesignation::LdpVp)?;

        // Parse the credential from the raw JSON to determine V1/V2.
        let parsed: AnyJsonCredential = serde_json::from_value(vc.raw.clone())
            .map_err(|e| CredentialEncodingError::VpToken(format!("Error parsing credential: {e:?}")))?;

        let unsigned_presentation = match parsed {
            AnyJsonCredential::V1(cred_v1) => {
                let holder_id: UriBuf = options.signer.did().parse().map_err(|e| {
                    CredentialEncodingError::VpToken(format!("Error parsing DID: {e:?}"))
                })?;

                let unsigned_presentation_v1 =
                    ssi::claims::vc::v1::JsonPresentation::new(Some(id.clone()), Some(holder_id), vec![cred_v1]);

                AnyJsonPresentation::V1(unsigned_presentation_v1)
            }
            AnyJsonCredential::V2(cred_v2) => {
                // Convert inner type of `Object` -> `NonEmptyObject`.
                let mut cred_v2 = try_map_subjects(cred_v2, NonEmptyObject::try_from_object)
                    .map_err(|e| Draft18OID4VPError::EmptyCredentialSubject(format!("{e:?}")))?;

                // Remove SD proof from the credential before adding it to the presentation.
                if let Some(p) = cred_v2
                    .extra_properties
                    .get_mut("proof")
                    .and_then(|p| p.as_array_mut())
                {
                    *p = p
                        .iter_mut()
                        .flat_map(|p| p.as_object())
                        .filter(|obj| {
                            while let Some(cryptosuite) = obj.get("cryptosuite").next() {
                                if let Some(suite) = cryptosuite.as_string() {
                                    return ACCEPTED_CRYPTOSUITES.contains(&suite);
                                }
                            }
                            true
                        })
                        .map(|p| p.clone().into())
                        .collect::<Vec<_>>();
                }

                let holder_id = IdOr::Id(options.signer.did().parse().map_err(|e| {
                    CredentialEncodingError::VpToken(format!("Error parsing DID: {e:?}"))
                })?);

                let unsigned_presentation_v2 =
                    JsonPresentationV2::new(Some(id), vec![holder_id], vec![cred_v2]);

                AnyJsonPresentation::V2(unsigned_presentation_v2)
            }
        };

        let signed_presentation = options.sign_presentation(unsigned_presentation).await?;

        Ok(VpTokenItem::from(signed_presentation))
    }

    fn json_vc_create_descriptor_map(
        &self,
        input_descriptor_id: impl Into<String>,
        index: Option<usize>,
    ) -> Result<DescriptorMap, Draft18OID4VPError> {
        let path = match index {
            Some(idx) => format!("$.verifiableCredential[{idx}]"),
            None => "$.verifiableCredential".into(),
        }
        .parse()
        .map_err(|e| Draft18OID4VPError::JsonPathParse(format!("{e:?}")))?;

        let id = input_descriptor_id.into();

        Ok(
            DescriptorMap::new(id.clone(), ClaimFormatDesignation::LdpVp, JsonPath::default())
                .set_path_nested(DescriptorMap::new(
                    id,
                    ClaimFormatDesignation::LdpVc,
                    path,
                )),
        )
    }
}

// Helper function to convert inner types of a V2 credential.
fn try_map_subjects<T, U, E: std::fmt::Debug>(
    cred: JsonCredentialV2<T>,
    f: impl FnMut(T) -> Result<U, E>,
) -> Result<JsonCredentialV2<U>, Draft18OID4VPError> {
    Ok(JsonCredentialV2 {
        name: cred.name,
        description: cred.description,
        context: cred.context,
        id: cred.id,
        types: cred.types,
        credential_subjects: NonEmptyVec::try_from_vec(
            cred.credential_subjects
                .into_iter()
                .map(f)
                .collect::<Result<_, _>>()
                .map_err(|e| Draft18OID4VPError::EmptyCredentialSubject(format!("{e:?}")))?,
        )
        .map_err(|e| Draft18OID4VPError::EmptyCredentialSubject(format!("{e:?}")))?,
        issuer: cred.issuer,
        valid_from: cred.valid_from,
        valid_until: cred.valid_until,
        credential_status: cred.credential_status,
        terms_of_use: cred.terms_of_use,
        evidence: cred.evidence,
        credential_schema: cred.credential_schema,
        refresh_services: cred.refresh_services,
        extra_properties: cred.extra_properties,
    })
}
