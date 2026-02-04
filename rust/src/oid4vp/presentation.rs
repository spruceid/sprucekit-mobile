use crate::crypto::CryptoCurveUtils;

use super::{error::OID4VPError, RequestedField, ResponseOptions};

use std::{collections::HashMap, ops::Deref, str::FromStr, sync::Arc};

use openid4vp::core::{
    authorization_request::AuthorizationRequestObject, credential_format::ClaimFormatDesignation,
    dcql_query::DcqlCredentialQuery, response::parameters::VpTokenItem,
};
use serde::Serialize;
use ssi::{
    claims::{
        data_integrity::{suites::JsonWebSignature2020, AnyProtocol, CryptosuiteString},
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

                Arc::new(RequestedField::from_dcql_claims(
                    credential_query.id().to_string(),
                    path,
                    vec![], // raw_fields would need actual credential parsing
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
    /// Sign the payload with the private key and return the signature.
    ///
    /// The signing algorithm must match the `cryptosuite()` method result.
    async fn sign(&self, payload: Vec<u8>) -> Result<Vec<u8>, PresentationError>;

    /// Return the algorithm used for signing the vp token.
    ///
    /// E.g., "ES256"
    fn algorithm(&self) -> Algorithm;

    /// Return the verification method associated with the signing key.
    async fn verification_method(&self) -> String;

    /// Return the `DID` of the signing key.
    fn did(&self) -> String;

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

    /// Return the public JWK of the signing key.
    /// as a String-encoded JSON
    fn jwk(&self) -> String;
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

impl MessageSigner<WithProtocol<ssi::crypto::Algorithm, AnyProtocol>> for PresentationOptions<'_> {
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
            .sign(message.to_vec())
            .await
            .map_err(|e| MessageSignatureError::signature_failed(format!("{e:?}")))?;

        match self.signer.cryptosuite().as_ref() {
            "ecdsa-rdfc-2019" => self
                .curve_utils()
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

impl<M> ssi::verification_methods::Signer<M> for PresentationOptions<'_>
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
            .filter(|ctrl| **ctrl == self.signer.did())
            .map(|_| self.clone()))
    }
}

impl PresentationOptions<'_> {
    pub async fn verification_method_id(&self) -> Result<IriBuf, PresentationError> {
        self.signer
            .verification_method()
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
        self.signer.did()
    }

    pub fn subject(&self) -> String {
        self.signer.did()
    }

    pub fn jwk(&self) -> Result<JWK, PresentationError> {
        JWK::from_str(&self.signer.jwk()).map_err(|e| PresentationError::JWK(format!("{e:?}")))
    }

    /// Return the crypto curve utils based on the signing algorithm, e.g. ES256.
    pub fn curve_utils(&self) -> Result<CryptoCurveUtils, PresentationError> {
        match self.signer.algorithm() {
            ssi::crypto::Algorithm::ES256 => Ok(CryptoCurveUtils::secp256r1()),
            alg => Err(PresentationError::CryptographicSuite(format!(
                "Unsupported curve utils for algorithm: {alg:?}"
            ))),
        }
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

    /// Sign a JSON presentation type for a v1 OR v2 credential.
    pub async fn sign_presentation(
        &self,
        // NOTE: the presentation is `unsecured` at this point.
        presentation: AnyJsonPresentation,
    ) -> Result<DataIntegrity<AnyJsonPresentation, AnySuite>, PresentationError> {
        let resolver = VerificationMethodDIDResolver::new(AnyDidMethod::default());

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

        // Use the cryptosuite-specific signing method to sign the presentation.
        match suite.as_ref() {
            "ecdsa-rdfc-2019" => {
                AnySuite::EcdsaRdfc2019
                    .sign_with(
                        &env,
                        presentation,
                        resolver,
                        self,
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
                        self,
                        proof_options,
                        Default::default(),
                    )
                    .await
            }
            _ => return Err(PresentationError::CryptographicSuite(suite.to_string())),
        }
        .map_err(|e| PresentationError::Signing(format!("{e:?}")))
    }
}
