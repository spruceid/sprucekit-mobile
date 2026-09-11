#![allow(deprecated)]

use crate::crypto::CryptoCurveUtils;

use super::{error::OID4VPError, RequestedField, ResponseOptions};

use std::{collections::HashMap, ops::Deref, str::FromStr, sync::Arc};

use openid4vp::core::{
    authorization_request::AuthorizationRequestObject,
    credential_format::{ClaimFormatDesignation, ClaimFormatMap, ClaimFormatPayload},
    dcql_query::DcqlCredentialQuery,
    response::parameters::VpTokenItem,
};
use serde::Serialize;
use ssi::{
    claims::{
        data_integrity::{suites::JsonWebSignature2020, AnyProtocol, CryptosuiteString},
        vc::v2::syntax::JsonPresentation as JsonPresentationV2,
        MessageSignatureError, SignatureEnvironment,
    },
    crypto::{Algorithm, AlgorithmInstance},
    dids::{AnyDidMethod, VerificationMethodDIDResolver},
    json_ld::{syntax::ContextEntry, ContextLoader, IriBuf, IriRefBuf},
    prelude::{AnyJsonPresentation, AnySuite, CryptographicSuite, DataIntegrity, ProofOptions},
    verification_methods::{protocol::WithProtocol, MessageSigner, ProofPurpose},
    xsd::DateTimeStamp,
    JWK,
};

#[derive(Debug, uniffi::Error, thiserror::Error)]
pub enum PresentationError {
    #[error("Error signing presentation: {0}")]
    Signing(String),

    #[error("Invalid or Missing Cryptographic Suite: {0}")]
    CryptographicSuite(String),

    #[error("Invalid Verification Method Identifier: {0}")]
    VerificationMethod(String),

    #[error("Invalid Context: {0}")]
    Context(String),

    #[error("Failed to parse public JsonWebKey: {0}")]
    JWK(String),
}
/// Credential Presentation trait defines the set of standard methods
/// each credential format must implement.
pub trait CredentialPresentation {
    /// Presentation format is the expected format of the presentation.
    type PresentationFormat: Into<ClaimFormatDesignation> + std::fmt::Debug;

    /// Credential format is the format of the credential itself.
    type CredentialFormat: Into<ClaimFormatDesignation> + std::fmt::Debug;

    /// Credential value is the actual credential.
    type Credential: Serialize;

    /// Return the credential format designation.
    fn credential_format(&self) -> Self::CredentialFormat;

    /// Return the presentation format designation.
    fn presentation_format(&self) -> Self::PresentationFormat;

    /// Return the credential
    fn credential(&self) -> &Self::Credential;

    /// Method to check whether a credential satisfies a DCQL credential query.
    fn satisfies_dcql_query(&self, credential_query: &DcqlCredentialQuery) -> bool {
        // Check if the credential format matches the query format
        let query_format = credential_query.format();
        let cred_format: ClaimFormatDesignation = self.credential_format().into();
        let pres_format: ClaimFormatDesignation = self.presentation_format().into();

        if *query_format != cred_format && *query_format != pres_format {
            log::debug!(
                "Credential format {:?} does not match DCQL query format {:?}.",
                cred_format,
                query_format
            );
            return false;
        }

        // For now, if the format matches, we consider it a match.
        // More sophisticated matching (e.g., checking meta.vct_values) can be added later.
        true
    }

    /// Return the requested fields from the credential matching
    /// the DCQL credential query.
    fn requested_fields_dcql(
        &self,
        credential_query: &DcqlCredentialQuery,
    ) -> Vec<Arc<RequestedField>> {
        // Default implementation. Extract claims from DCQL query
        let Some(claims) = credential_query.claims() else {
            return vec![];
        };

        claims
            .iter()
            .map(|claim| {
                let path: Vec<String> = claim
                    .path()
                    .iter()
                    .filter_map(|p| match p {
                        openid4vp::core::dcql_query::DcqlCredentialClaimsQueryPath::String(s) => {
                            Some(s.clone())
                        }
                        openid4vp::core::dcql_query::DcqlCredentialClaimsQueryPath::Integer(i) => {
                            Some(i.to_string())
                        }
                        openid4vp::core::dcql_query::DcqlCredentialClaimsQueryPath::Null => None,
                    })
                    .collect();

                // The dotted path doubles as the display name; the `path`
                // field itself is base64url-encoded for transport.
                let name = path.join(".");
                Arc::new(RequestedField::from_dcql_claims_with_name(
                    credential_query.id().to_string(),
                    path,
                    vec![], // raw_fields would need actual credential parsing
                    Some(name),
                ))
            })
            .collect()
    }

    /// Return the credential as a verifiable presentation token item.
    #[allow(async_fn_in_trait)]
    async fn as_vp_token_item<'a>(
        &self,
        options: &'a PresentationOptions<'a>,
        selected_fields: Option<Vec<String>>,
    ) -> Result<VpTokenItem, OID4VPError>;
}

/// The `PresentationSigner` foreign callback interface to be implemented
/// by the host environment, e.g. Kotlin or Swift.
///
/// Signing is handled after the authorization request is reviewed and authorized
/// and the credentials for presentation have been selected.
///
/// The payload for signing is determined by the credential format and the encoding
/// type of the `vp_token`.
///
/// For example, in the case of `JwtVc` credential format,
/// the signing payload consists of the JWT header and payload (JWS).
#[uniffi::export(callback_interface)]
#[async_trait::async_trait]
pub trait PresentationSigner: Send + Sync + std::fmt::Debug {
    /// Sign the payload with the private key identified by `key_id` and return
    /// the signature.
    ///
    /// The signing algorithm must match the `cryptosuite()` method result.
    async fn sign(&self, key_id: String, payload: Vec<u8>) -> Result<Vec<u8>, PresentationError>;

    /// Return the algorithm used for signing the vp token.
    ///
    /// E.g., "ES256"
    ///
    /// NOTE: unlike `sign`/`verification_method`/`did`/`jwk`, this is NOT keyed
    /// by `key_id`. Per-credential signing keys are assumed to all share one
    /// signing algorithm (P-256 / ES256). If a holder ever mixes keys of
    /// different algorithms, this must be keyed by `key_id` too (as must
    /// `cryptosuite`).
    fn algorithm(&self) -> Algorithm;

    /// Return the verification method associated with the signing key `key_id`.
    async fn verification_method(&self, key_id: String) -> String;

    /// Return the `DID` of the signing key identified by `key_id`.
    fn did(&self, key_id: String) -> String;

    /// Data Integrity Cryptographic Suite of the Signer.
    ///
    /// This corresponds to the `proof_type` in the
    /// authorization request's `vp_formats_supported` for the
    /// credential format (e.g., `ldp_vc`, `jwt_vc_json`).
    ///
    /// Per OID4VP v1.0, these format identifiers cover both
    /// credentials and presentations.
    ///
    /// E.g., JsonWebSignature2020, ecdsa-rdfc-2019
    fn cryptosuite(&self) -> CryptosuiteString;

    /// Return the public JWK of the signing key identified by `key_id`,
    /// as a String-encoded JSON.
    fn jwk(&self, key_id: String) -> String;
}

/// Internal options for constructing a VP Token, and optionally signing it.
///
/// PresentationOptions provides a means to pass metadata about the verifiable presentation
/// claims in the `vp_token` parameter.
#[derive(Clone)]
pub struct PresentationOptions<'a> {
    /// Borrowed reference to the authorization request object.
    pub(crate) request: &'a AuthorizationRequestObject,
    /// Signing callback interface that can be used to sign the `vp_token`.
    pub(crate) signer: Arc<Box<dyn PresentationSigner>>,
    /// The per-credential signing key id to use with `signer` for the
    /// credential currently being encoded.
    pub(crate) key_id: String,
    /// Optional context map for the presentation.
    pub(crate) context_map: Option<HashMap<String, String>>,
    pub(crate) response_options: &'a ResponseOptions,
    /// Optional KeyStore for mdoc credential signing
    pub(crate) keystore: Option<Arc<dyn crate::crypto::KeyStore>>,
}

impl std::fmt::Debug for PresentationOptions<'_> {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("PresentationOptions")
            .field("request", &self.request)
            .field("context_map", &self.context_map)
            .field("response_options", &self.response_options)
            .field("keystore", &self.keystore.as_ref().map(|_| "KeyStore"))
            .finish()
    }
}

/// Owned signing context, so data-integrity signing can move onto the
/// dedicated big-stack thread; [`PresentationOptions`] itself borrows the
/// authorization request and cannot.
#[derive(Clone)]
struct OwnedPresentationSigner {
    signer: Arc<Box<dyn PresentationSigner>>,
    key_id: String,
}

impl MessageSigner<WithProtocol<ssi::crypto::Algorithm, AnyProtocol>> for OwnedPresentationSigner {
    #[allow(async_fn_in_trait)]
    async fn sign(
        self,
        // NOTE: The `protocol` parameter is not used in this implementation, but it would
        // be preferable to have a `suite` parameter that would be used here instead.
        //
        // For example, `WithSuite` could accept a `AnySuite` type. This might already
        // exist? But, I tried to change `AnyProtocol` to `AnySuite` to match against
        // the [PresentationSigner::cryptosuite] method, but alas, this does not work
        // with the `sign` method.
        //
        // TODO: Determine if there is a way to provide a `suite` parameter here.
        WithProtocol(alg, _protocol): WithProtocol<AlgorithmInstance, AnyProtocol>,
        message: &[u8],
    ) -> Result<Vec<u8>, MessageSignatureError> {
        if !self.signer.algorithm().is_compatible_with(alg.algorithm()) {
            return Err(MessageSignatureError::UnsupportedAlgorithm(
                self.signer.algorithm().to_string(),
            ));
        }

        let signature_bytes = self
            .signer
            .sign(self.key_id.clone(), message.to_vec())
            .await
            .map_err(|e| MessageSignatureError::signature_failed(format!("{e:?}")))?;

        match self.signer.cryptosuite().as_ref() {
            "ecdsa-rdfc-2019" => curve_utils_for(self.signer.algorithm())
                .map(|utils| utils.ensure_raw_fixed_width_signature_encoding(signature_bytes))
                .map_err(|e| MessageSignatureError::UnsupportedAlgorithm(format!("{e:?}")))?
                .ok_or(MessageSignatureError::UnsupportedAlgorithm(
                    "Unsupported signature encoding".into(),
                )),
            _ => Err(MessageSignatureError::UnsupportedAlgorithm(
                self.signer.cryptosuite().to_string(),
            )),
        }
    }
}

impl<M> ssi::verification_methods::Signer<M> for OwnedPresentationSigner
where
    M: ssi::verification_methods::VerificationMethod,
{
    type MessageSigner = Self;

    #[allow(async_fn_in_trait)]
    async fn for_method(
        &self,
        method: std::borrow::Cow<'_, M>,
    ) -> Result<Option<Self::MessageSigner>, ssi::claims::SignatureError> {
        Ok(method
            .controller()
            .filter(|ctrl| **ctrl == self.signer.did(self.key_id.clone()))
            .map(|_| self.clone()))
    }
}

/// Return the crypto curve utils for a signing algorithm, e.g. ES256.
fn curve_utils_for(algorithm: Algorithm) -> Result<CryptoCurveUtils, PresentationError> {
    match algorithm {
        Algorithm::ES256 => Ok(CryptoCurveUtils::secp256r1()),
        alg => Err(PresentationError::CryptographicSuite(format!(
            "Unsupported curve utils for algorithm: {alg:?}"
        ))),
    }
}

/// See [`PresentationOptions::verifier_accepts_credential_suite`].
///
/// OID4VP 1.0 Appendix B.1.3.2.3 splits the declaration in two: proof types
/// go in `proof_type_values` (`DataIntegrityProof` for every current suite)
/// and cryptosuites in `cryptosuite_values`. `openid4vp` models only the
/// former, and verifiers built on the drafts, where the proof `type` named
/// the suite, still list cryptosuite names under `proof_type_values`. Both
/// shapes are honored:
///
/// - `cryptosuite_values` present: the suite must be listed there.
/// - Otherwise, a `proof_type_values` naming any cryptosuite (lowercase,
///   hyphenated identifiers such as `ecdsa-rdfc-2019`) is read as a
///   cryptosuite list, and the suite must be listed.
/// - Otherwise `proof_type_values` holds proof types only, and any Data
///   Integrity suite is accepted when `DataIntegrityProof` is listed.
/// - No entry for the format, or no list at all: no restriction.
fn vp_formats_accept_credential_suite(
    vp_formats: &ClaimFormatMap,
    format: &ClaimFormatDesignation,
    suite: &str,
) -> bool {
    let Some(payload) = vp_formats.get(format) else {
        return true;
    };

    match payload {
        ClaimFormatPayload::ProofTypeValues(values) => {
            let values: Vec<&str> = values.iter().map(String::as_str).collect();
            proof_type_values_accept_suite(&values, suite)
        }
        // The spec's two-list shape deserializes untyped.
        ClaimFormatPayload::Other(value) => {
            if let Some(cryptosuites) = string_list(value, "cryptosuite_values") {
                cryptosuites.contains(&suite)
            } else if let Some(proof_types) = string_list(value, "proof_type_values") {
                proof_type_values_accept_suite(&proof_types, suite)
            } else {
                true
            }
        }
        // The JWT shape; it declares nothing about Data Integrity suites.
        ClaimFormatPayload::AlgValues(_) => false,
    }
}

/// Data Integrity proof `type` shared by every current cryptosuite.
const DATA_INTEGRITY_PROOF_TYPE: &str = "DataIntegrityProof";

/// Whether a `proof_type_values` list with no `cryptosuite_values` beside it
/// accepts `suite`. See [`vp_formats_accept_credential_suite`].
fn proof_type_values_accept_suite(values: &[&str], suite: &str) -> bool {
    // Cryptosuite identifiers are lowercase and hyphenated; proof types are
    // CamelCase terms. A list naming any cryptosuite is a cryptosuite list.
    if values.iter().any(|value| value.contains('-')) {
        values.contains(&suite)
    } else {
        values.contains(&DATA_INTEGRITY_PROOF_TYPE)
    }
}

/// The string members of the array at `key`, if `value` has one.
fn string_list<'v>(value: &'v serde_json::Value, key: &str) -> Option<Vec<&'v str>> {
    value
        .get(key)
        .and_then(serde_json::Value::as_array)
        .map(|values| {
            values
                .iter()
                .filter_map(serde_json::Value::as_str)
                .collect()
        })
}

impl PresentationOptions<'_> {
    pub async fn verification_method_id(&self) -> Result<IriBuf, PresentationError> {
        self.signer
            .verification_method(self.key_id.clone())
            .await
            .parse()
            .map_err(|e| PresentationError::VerificationMethod(format!("{e:?}")))
    }

    pub fn audience(&self) -> Option<&String> {
        self.request.client_id().map(|id| &id.0)
    }

    pub fn nonce(&self) -> &String {
        self.request.nonce().deref()
    }

    pub fn issuer(&self) -> String {
        self.signer.did(self.key_id.clone())
    }

    pub fn subject(&self) -> String {
        self.signer.did(self.key_id.clone())
    }

    pub fn jwk(&self) -> Result<JWK, PresentationError> {
        JWK::from_str(&self.signer.jwk(self.key_id.clone()))
            .map_err(|e| PresentationError::JWK(format!("{e:?}")))
    }

    /// Return the crypto curve utils based on the signing algorithm, e.g. ES256.
    pub fn curve_utils(&self) -> Result<CryptoCurveUtils, PresentationError> {
        curve_utils_for(self.signer.algorithm())
    }

    /// Validate the signing cryptosuite against the supported request algorithms.
    pub fn supports_security_method(
        &self,
        format: impl Into<ClaimFormatDesignation>,
    ) -> Result<(), PresentationError> {
        let format = format.into();
        let suite = self.signer.cryptosuite();

        // Retrieve the vp_formats from the authorization request object.
        let vp_formats = self
            .request
            .vp_formats()
            .map_err(|e| PresentationError::CryptographicSuite(format!("{e:?}")))?;

        // vp_formats_supported is only required when the wallet cannot
        // obtain this info through other means (e.g., OpenID Federation, prior
        // registration). When empty, we assume the verifier accepts the wallet's
        // cryptosuite since they didn't specify restrictions.
        //
        // - https://openid.net/specs/openid-4-verifiable-presentations-1_0.html#section-5.1
        // - http://openid.net/specs/openid-4-verifiable-presentations-1_0.html#section-11.1
        if vp_formats.0.is_empty() {
            log::warn!(
                "vp_formats is empty in authorization request. Skipping security method validation for format {:?} with suite {:?}",
                format,
                suite
            );
            return Ok(());
        }

        if !vp_formats.supports_security_method(&format, &suite.to_string()) {
            let err_msg = format!("Cryptographic Suite not supported for this request format: {format:?} and suite: {suite:?}. Supported Cryptographic Suites: {vp_formats:?}");
            return Err(PresentationError::CryptographicSuite(err_msg));
        }

        Ok(())
    }

    /// Whether the verifier accepts credentials secured with `suite` under
    /// `format`, according to its `vp_formats_supported`.
    ///
    /// [`Self::supports_security_method`] checks the suite the *wallet* signs
    /// the presentation with. This checks a suite the *issuer* used on the
    /// credential being embedded, which matters when the wallet can choose
    /// between issuer proofs: presenting an `ecdsa-rdfc-2019` full proof, or
    /// deriving from an `ecdsa-sd-2023` base proof.
    ///
    /// OID4VP 1.0 Appendix B.1.3.2.3 makes `proof_type_values` and
    /// `cryptosuite_values` optional, but when present the `cryptosuite` of
    /// the presented credential MUST match one of the listed values:
    /// <https://openid.net/specs/openid-4-verifiable-presentations-1_0.html#appendix-B.1.3.2.3>
    ///
    /// So a missing `vp_formats_supported`, a missing entry for `format`, or
    /// an entry listing nothing imposes no restriction.
    pub(crate) fn verifier_accepts_credential_suite(
        &self,
        format: &ClaimFormatDesignation,
        suite: &str,
    ) -> Result<bool, PresentationError> {
        let vp_formats = self
            .request
            .vp_formats()
            .map_err(|e| PresentationError::CryptographicSuite(format!("{e:?}")))?;

        Ok(vp_formats_accept_credential_suite(
            &vp_formats.0,
            format,
            suite,
        ))
    }

    /// Sign a JSON presentation type for a v1 OR v2 credential.
    pub async fn sign_presentation(
        &self,
        // NOTE: the presentation is `unsecured` at this point.
        presentation: AnyJsonPresentation,
    ) -> Result<DataIntegrity<AnyJsonPresentation, AnySuite>, PresentationError> {
        let mut proof_options = ProofOptions::new(
            DateTimeStamp::now_ms().into(),
            self.verification_method_id().await?.into(),
            ProofPurpose::Authentication,
            Default::default(),
        );

        // See: https://openid.net/specs/openid-4-verifiable-presentations-1_0.html#section-13.1-14
        //
        // domain is the client_id of the request, in the example above.
        proof_options.challenge = Some(self.nonce().to_owned());
        proof_options.domains = vec![self
            .request
            .client_id()
            .ok_or_else(|| PresentationError::Context("request missing 'client_id'".to_string()))?
            .0
            .clone()];

        if let AnyJsonPresentation::V1(_) = presentation {
            let iri_buf = IriRefBuf::new("https://w3id.org/security/data-integrity/v2".into())
                .map_err(|e| PresentationError::Context(format!("{e:?}")))?;

            proof_options.context = Some(ssi::json_ld::syntax::Context::One(ContextEntry::IriRef(
                iri_buf,
            )))
        }

        let context = self
            .context_map
            .clone()
            .map(|map| ContextLoader::default().with_context_map_from(map))
            .transpose()
            .map_err(|e| PresentationError::Context(format!("{e:?}")))?
            .unwrap_or_default();

        let suite = self.signer.cryptosuite();

        let env = SignatureEnvironment {
            json_ld_loader: context,
            eip712_loader: (),
        };

        let resolver = VerificationMethodDIDResolver::new(AnyDidMethod::default());
        let signer = OwnedPresentationSigner {
            signer: self.signer.clone(),
            key_id: self.key_id.clone(),
        };

        // ssi's data-integrity signing performs JSON-LD expansion + RDF
        // canonicalization deep enough to overflow the foreign thread's stack
        // that UniFFI polls this future on (~512 KB on iOS). Hop onto the
        // dedicated 8 MB worker, like the draft18 path (see `crate::big_stack`).
        crate::big_stack::run_async(move || async move {
            // Use the cryptosuite-specific signing method to sign the presentation.
            match suite.as_ref() {
                "ecdsa-rdfc-2019" => {
                    AnySuite::EcdsaRdfc2019
                        .sign_with(
                            &env,
                            presentation,
                            resolver,
                            signer,
                            proof_options,
                            Default::default(),
                        )
                        .await
                }
                JsonWebSignature2020::NAME => {
                    AnySuite::JsonWebSignature2020
                        .sign_with(
                            &env,
                            presentation,
                            resolver,
                            signer,
                            proof_options,
                            Default::default(),
                        )
                        .await
                }
                _ => return Err(PresentationError::CryptographicSuite(suite.to_string())),
            }
            .map_err(|e| PresentationError::Signing(format!("{e:?}")))
        })
        .await
        .map_err(|e| PresentationError::Signing(format!("big-stack signing thread: {e}")))?
    }

    /// Sign a VCDM 2.0 presentation whose credentials are embedded as raw
    /// JSON.
    ///
    /// [`Self::sign_presentation`] requires each credential to parse as a
    /// complete VCDM document. A selectively derived credential carries only
    /// what the holder disclosed — nothing guarantees the members the typed
    /// model requires — so it is embedded and signed untyped.
    pub(crate) async fn sign_derived_presentation(
        &self,
        presentation: JsonPresentationV2<serde_json::Value>,
    ) -> Result<DataIntegrity<JsonPresentationV2<serde_json::Value>, AnySuite>, PresentationError>
    {
        let mut proof_options = ProofOptions::new(
            DateTimeStamp::now_ms().into(),
            self.verification_method_id().await?.into(),
            ProofPurpose::Authentication,
            Default::default(),
        );
        proof_options.challenge = Some(self.nonce().to_owned());
        proof_options.domains = vec![self
            .request
            .client_id()
            .ok_or_else(|| PresentationError::Context("request missing 'client_id'".to_string()))?
            .0
            .clone()];

        let context = self
            .context_map
            .clone()
            .map(|map| ContextLoader::default().with_context_map_from(map))
            .transpose()
            .map_err(|e| PresentationError::Context(format!("{e:?}")))?
            .unwrap_or_default();

        let suite = self.signer.cryptosuite();

        let env = SignatureEnvironment {
            json_ld_loader: context,
            eip712_loader: (),
        };

        let resolver = VerificationMethodDIDResolver::new(AnyDidMethod::default());
        let signer = OwnedPresentationSigner {
            signer: self.signer.clone(),
            key_id: self.key_id.clone(),
        };

        // Same big-stack hop as `sign_presentation`.
        crate::big_stack::run_async(move || async move {
            match suite.as_ref() {
                "ecdsa-rdfc-2019" => {
                    AnySuite::EcdsaRdfc2019
                        .sign_with(
                            &env,
                            presentation,
                            resolver,
                            signer,
                            proof_options,
                            Default::default(),
                        )
                        .await
                }
                JsonWebSignature2020::NAME => {
                    AnySuite::JsonWebSignature2020
                        .sign_with(
                            &env,
                            presentation,
                            resolver,
                            signer,
                            proof_options,
                            Default::default(),
                        )
                        .await
                }
                _ => return Err(PresentationError::CryptographicSuite(suite.to_string())),
            }
            .map_err(|e| PresentationError::Signing(format!("{e:?}")))
        })
        .await
        .map_err(|e| PresentationError::Signing(format!("big-stack signing thread: {e}")))?
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    const SD: &str = "ecdsa-sd-2023";
    const LDP_VC: ClaimFormatDesignation = ClaimFormatDesignation::LdpVc;

    fn ldp_vc(payload: ClaimFormatPayload) -> ClaimFormatMap {
        ClaimFormatMap::from([(LDP_VC, payload)])
    }

    #[test]
    fn no_declaration_imposes_no_restriction() {
        assert!(vp_formats_accept_credential_suite(
            &ClaimFormatMap::new(),
            &LDP_VC,
            SD
        ));

        let other_format_only = ClaimFormatMap::from([(
            ClaimFormatDesignation::MsoMDoc,
            ClaimFormatPayload::Other(json!({ "alg_values": ["ES256"] })),
        )]);
        assert!(vp_formats_accept_credential_suite(
            &other_format_only,
            &LDP_VC,
            SD
        ));

        let empty_entry = ldp_vc(ClaimFormatPayload::Other(json!({})));
        assert!(vp_formats_accept_credential_suite(
            &empty_entry,
            &LDP_VC,
            SD
        ));
    }

    #[test]
    fn proof_type_values_naming_cryptosuites_must_name_the_suite() {
        let listed = ldp_vc(ClaimFormatPayload::ProofTypeValues(vec![
            "DataIntegrityProof".into(),
            "ecdsa-rdfc-2019".into(),
            SD.into(),
        ]));
        assert!(vp_formats_accept_credential_suite(&listed, &LDP_VC, SD));

        let rdfc_only = ldp_vc(ClaimFormatPayload::ProofTypeValues(vec![
            "DataIntegrityProof".into(),
            "ecdsa-rdfc-2019".into(),
        ]));
        assert!(!vp_formats_accept_credential_suite(&rdfc_only, &LDP_VC, SD));
    }

    #[test]
    fn proof_types_only_accept_any_data_integrity_suite() {
        // The spec's reading: `DataIntegrityProof` is the type of every
        // current suite, and no cryptosuite restriction was declared.
        let data_integrity = ldp_vc(ClaimFormatPayload::ProofTypeValues(vec![
            "DataIntegrityProof".into(),
        ]));
        assert!(vp_formats_accept_credential_suite(
            &data_integrity,
            &LDP_VC,
            SD
        ));

        // A legacy suite-as-type, with no `DataIntegrityProof`, cannot
        // secure an `ecdsa-sd-2023` credential.
        let legacy_type = ldp_vc(ClaimFormatPayload::ProofTypeValues(vec![
            "Ed25519Signature2020".into(),
        ]));
        assert!(!vp_formats_accept_credential_suite(
            &legacy_type,
            &LDP_VC,
            SD
        ));
    }

    #[test]
    fn cryptosuite_values_is_authoritative_when_present() {
        // The spec's shape carries both lists and lands in `Other`.
        let both: ClaimFormatPayload = serde_json::from_value(json!({
            "proof_type_values": ["DataIntegrityProof"],
            "cryptosuite_values": ["ecdsa-rdfc-2019", SD],
        }))
        .unwrap();
        assert!(matches!(both, ClaimFormatPayload::Other(_)));
        assert!(vp_formats_accept_credential_suite(
            &ldp_vc(both),
            &LDP_VC,
            SD
        ));

        let rdfc_only: ClaimFormatPayload = serde_json::from_value(json!({
            "proof_type_values": ["DataIntegrityProof", SD],
            "cryptosuite_values": ["ecdsa-rdfc-2019"],
        }))
        .unwrap();
        assert!(!vp_formats_accept_credential_suite(
            &ldp_vc(rdfc_only),
            &LDP_VC,
            SD
        ));
    }
}
