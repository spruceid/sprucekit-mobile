use super::{error::OID4VPError, permission_request::RequestedField};

use std::{ops::Deref, str::FromStr, sync::Arc};

use openid4vp::core::{
    authorization_request::AuthorizationRequestObject, credential_format::ClaimFormatDesignation,
    presentation_definition::PresentationDefinition, presentation_submission::DescriptorMap,
    response::parameters::VpTokenItem,
};
use serde::Serialize;
use ssi::{
    claims::{
        data_integrity::{
            suites::{EcdsaRdfc2019, JsonWebSignature2020},
            AnyInputSuiteOptions, CryptosuiteString,
        },
        MessageSignatureError,
    },
    crypto::algorithm::SignatureAlgorithmType,
    dids::{VerificationMethodDIDResolver, DIDJWK},
    json_ld::IriBuf,
    prelude::{AnyJsonPresentation, AnySuite, CryptographicSuite, DataIntegrity, ProofOptions},
    verification_methods::{MessageSigner, ProofPurpose},
    xsd::DateTimeStamp,
    JWK,
};
use uniffi::deps::log;

#[derive(Debug, uniffi::Error, thiserror::Error)]
pub enum PresentationError {
    #[error("Error signing presentation: {0}")]
    Signing(String),

    #[error("Invalid or Missing Cryptographic Suite: {0}")]
    CryptographicSuite(String),

    #[error("Invalid Verification Method Identifier: {0}")]
    VerificationMethod(String),

    #[error("Failed to parse public JsonWebKey: {0}")]
    JWK(String),
}
/// Credential Presentation trait defines the set of standard methods
/// each credential format must implement.
pub trait CredentialPresentation {
    /// Presentation format is the expected format of the presentation.
    ///
    /// For example, JwtVp, LdpVp, etc.
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

    /// Method to check whether a credential satisfies a given
    /// reference to a presentation definition.
    fn satisfies_presentation_definition(
        &self,
        presentation_definition: &PresentationDefinition,
    ) -> bool {
        // If the credential does not match the definition requested format,
        // then return false.
        if !presentation_definition.format().is_empty()
            && !presentation_definition.contains_format(self.credential_format())
            && !presentation_definition.contains_format(self.presentation_format())
        {
            log::debug!(
                "Credential does not match the presentation definition requested format: {:?}.",
                presentation_definition.format()
            );

            return false;
        }

        let Ok(json) = serde_json::to_value(self.credential()) else {
            // NOTE: Instead of erroring here, we return false, which will
            // indicate that the credential does not satisfy the presentation
            // and the verifier can continue to the next credential.
            //
            // Still, we log an `error` here to alert that we were unable to serialize
            // the value to JSON, which for the implementation of this trait should
            // be a rare occurrence (ideally, never).
            log::error!(
                "Failed to serialize credential format, {:?}, into JSON.",
                self.credential_format()
            );
            return false;
        };

        // Check the JSON-encoded credential against the definition.
        presentation_definition.is_credential_match(&json)
    }

    /// Return the requested fields from the credential matching
    /// the presentation definition.
    fn requested_fields(
        &self,
        presentation_definition: &PresentationDefinition,
    ) -> Vec<Arc<RequestedField>> {
        // Default implementation
        let Ok(json) = serde_json::to_value(self.credential()) else {
            // NOTE: if we cannot convert the credential to a JSON value, then we cannot
            // check the presentation definition, so we return false to allow for
            // the holder to continue to the next credential.
            log::error!(
                "credential could not be converted to JSON: {:?}",
                self.credential_format()
            );
            return Vec::new();
        };

        presentation_definition
            .requested_fields(&json)
            .into_iter()
            .map(Into::into)
            .map(Arc::new)
            .collect()
    }

    /// Create a descriptor map for the credential,
    /// provided an input descriptor id and an index
    /// of where the credential is located in the
    /// presentation submission.
    fn create_descriptor_map(
        &self,
        input_descriptor_id: impl Into<String>,
        index: Option<usize>,
    ) -> Result<DescriptorMap, OID4VPError>;

    /// Return the credential as a verifiable presentation token item.
    #[allow(async_fn_in_trait)]
    async fn as_vp_token_item<'a>(
        &self,
        options: &'a PresentationOptions<'a>,
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
    /// The signing algorith must match the `cryptosuite()` method result.
    async fn sign(&self, payload: Vec<u8>) -> Result<Vec<u8>, PresentationError>;

    /// Return the algorithm used for signing the vp token.
    ///
    /// E.g., "ES256"
    #[deprecated(
        since = "0.1.0",
        note = "This method is deprecated and will be removed in a future release. Use `cryptosuite()` instead."
    )]
    fn algorithm(&self) -> String;

    /// Return the verification method associated with the signing key.
    ///
    /// E.g., DidJwk or DidKey
    async fn verification_method(&self) -> String;

    /// Return the `DID` of the signing key.
    fn did(&self) -> String;

    /// Data Integrity Cryptographic Suite of the Signer.
    ///
    /// This corresponds to the `proof_type` in the
    /// authorization request corresponding to the
    /// format of the verifiable presentation, e.g,
    /// `ldp_vp`, `jwt_vp`.
    ///
    ///
    /// E.g., JsonWebSignature2020, ecdsa-rdfc-2019
    fn cryptosuite(&self) -> String;

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
    pub(crate) request: &'a AuthorizationRequestObject,
    /// Signing callback interface that can be used to sign the `vp_token`.
    pub(crate) signer: Arc<Box<dyn PresentationSigner>>,
    /// Cryptographic Suite String Identifier
    /// e.g., JsonWebSignature2020
    pub(crate) cryptographic_suite: CryptosuiteString,
    /// Verification Method Id:
    pub(crate) verification_method_id: IriBuf,
}

impl<'a, A> MessageSigner<A> for PresentationOptions<'a>
where
    A: SignatureAlgorithmType,
{
    #[allow(async_fn_in_trait)]
    async fn sign(
        self,
        _algorithm: A::Instance,
        message: &[u8],
    ) -> Result<Vec<u8>, MessageSignatureError> {
        println!("Signing message");

        let signature = self
            .signer
            .sign(message.to_vec())
            .await
            .map_err(|e| MessageSignatureError::signature_failed(format!("{e:?}")))?;

        Ok(signature)
    }

    #[allow(async_fn_in_trait)]
    async fn sign_multi(
        self,
        _algorithm: A::Instance,
        messages: &[Vec<u8>],
    ) -> Result<Vec<u8>, MessageSignatureError> {
        match messages.split_first() {
            // // If there is a single message, sign the message
            // Some((message, [])) => self.sign(algorithm, message).await,
            // NOTE: The `MessageSigner` trait only returns a
            // single signature response, therefore limits to only signing
            // a single message.
            Some(_) => Err(MessageSignatureError::TooManyMessages),
            None => Err(MessageSignatureError::MissingMessage),
        }
    }
}

impl<'a, M> ssi::verification_methods::Signer<M> for PresentationOptions<'a>
where
    M: ssi::verification_methods::VerificationMethod,
{
    type MessageSigner = Self;

    #[allow(async_fn_in_trait)]
    async fn for_method(
        &self,
        _method: std::borrow::Cow<'_, M>,
    ) -> Result<Option<Self::MessageSigner>, ssi::claims::SignatureError> {
        Ok(Some(self.clone()))
    }
}

impl<'a> PresentationOptions<'a> {
    pub(crate) async fn new(
        request: &'a AuthorizationRequestObject,
        signer: Arc<Box<dyn PresentationSigner>>,
    ) -> Result<Self, PresentationError> {
        let cryptographic_suite = CryptosuiteString::new(signer.cryptosuite())
            .map_err(|e| PresentationError::CryptographicSuite(format!("{e:?}")))?;

        let verification_method_id = signer
            .verification_method()
            .await
            .parse()
            .map_err(|e| PresentationError::VerificationMethod(format!("{e:?}")))?;

        Ok(Self {
            request,
            signer,
            cryptographic_suite,
            verification_method_id,
        })
    }

    pub fn audience(&self) -> &String {
        &self.request.client_id().0
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

        if !vp_formats.supports_security_method(&format, &suite) {
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
        let resolver = VerificationMethodDIDResolver::new(DIDJWK);

        let proof_options = ProofOptions::new(
            DateTimeStamp::now(),
            self.verification_method_id.clone().into(),
            ProofPurpose::Authentication,
            AnyInputSuiteOptions {
                public_key_jwk: self.jwk().map(Box::new).ok(),
                ..Default::default()
            },
        );

        // Use the cryptosuite-specific signing method to sign the presentation.
        match self.cryptographic_suite.as_ref() {
            EcdsaRdfc2019::NAME | "ecdsa-rdfc-2019" => AnySuite::EcdsaRdfc2019
                .sign(presentation, resolver, self, proof_options)
                .await
                .map_err(|e| PresentationError::Signing(format!("{e:?}"))),
            JsonWebSignature2020::NAME => AnySuite::JsonWebSignature2020
                .sign(presentation, resolver, self, proof_options)
                .await
                .map_err(|e| PresentationError::Signing(format!("{e:?}"))),
            _ => Err(PresentationError::CryptographicSuite(
                self.cryptographic_suite.to_string(),
            )),
        }
    }
}
