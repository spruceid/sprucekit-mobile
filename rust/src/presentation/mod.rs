use std::{collections::HashMap, str::FromStr, sync::Arc};

use serde::de::{Deserialize, IntoDeserializer};
use ssi::{
    claims::{data_integrity::AnyProtocol, MessageSignatureError, SignatureEnvironment},
    crypto::AlgorithmInstance,
    dids::{AnyDidMethod, VerificationMethodDIDResolver},
    json_ld::{iref::UriBuf, ContextLoader, IriBuf},
    prelude::{AnySuite, CryptographicSuite, ProofOptions},
    verification_methods::{protocol::WithProtocol, MessageSigner, ProofPurpose},
};

pub use error::*;

use crate::{
    credential::{ParsedCredential, ParsedCredentialInner},
    crypto::CryptoCurveUtils,
    oid4vp::PresentationSigner,
};

mod error;

#[derive(Debug, Clone, uniffi::Object)]
pub struct JsonLdPresentationBuilder {
    pub(crate) id: String,
    pub(crate) holder: String,

    pub(crate) proof_purpose: ProofPurpose,
    pub(crate) challenge: Option<String>,
    pub(crate) domain: Option<String>,

    pub(crate) signer: Arc<Box<dyn PresentationSigner>>,
    pub(crate) context_map: Option<HashMap<String, String>>,
}

#[uniffi::export]
impl JsonLdPresentationBuilder {
    #[uniffi::constructor(name = "new")]
    fn new(
        id: String,
        holder: String,

        proof_purpose: String,
        challenge: Option<String>,
        domain: Option<String>,

        signer: Box<dyn PresentationSigner>,
        context_map: Option<HashMap<String, String>>,
    ) -> Arc<Self> {
        let proof_purpose: Result<ProofPurpose, serde::de::value::Error> =
            ProofPurpose::deserialize(proof_purpose.into_deserializer());
        Self {
            id,
            holder,
            proof_purpose: proof_purpose.unwrap(),
            challenge,
            domain,
            signer: Arc::new(signer),
            context_map,
        }
        .into()
    }

    pub async fn issue_presentation(
        &self,
        credentials: Vec<Arc<ParsedCredential>>,
    ) -> Result<String, PresentationBuilderError> {
        let key = serde_json::from_str(&self.signer.jwk())?;
        let vm = self.signer.verification_method().await;

        let id = UriBuf::from_str(&self.id)?;
        let holder = UriBuf::from_str(&self.holder)?;

        let vp = ssi::claims::vc::v1::JsonPresentation::new(
            Some(id),
            Some(holder),
            credentials
                .into_iter()
                .map(|c| match &c.inner {
                    ParsedCredentialInner::MsoMdoc(_) => {
                        Err(PresentationBuilderError::UnsupportedCredentialFormat)
                    }
                    ParsedCredentialInner::JwtVcJson(jwt_vc_json) => Ok(serde_json::Value::String(
                        jwt_vc_json.jws.clone().into_string(),
                    )),
                    ParsedCredentialInner::JwtVcJsonLd(jwt_vc_json_ld) => Ok(
                        serde_json::Value::String(jwt_vc_json_ld.jws.clone().into_string()),
                    ),
                    ParsedCredentialInner::VCDM2SdJwt(_) => {
                        Err(PresentationBuilderError::UnsupportedCredentialFormat)
                    }
                    ParsedCredentialInner::LdpVc(ldp_vc) => Ok(ldp_vc.raw.clone()),
                    ParsedCredentialInner::Cwt(_) => {
                        Err(PresentationBuilderError::UnsupportedCredentialFormat)
                    }
                })
                .collect::<Result<_, _>>()?,
        );

        let mut params = ProofOptions::from_method(IriBuf::new(vm)?.into());

        params.proof_purpose = self.proof_purpose;
        params.challenge = self.challenge.to_owned();
        params.domains = self.domain.to_owned().map(|d| vec![d]).unwrap_or_default();

        let resolver = VerificationMethodDIDResolver::new(AnyDidMethod::default());
        let suite = AnySuite::pick(&key, params.verification_method.as_ref())
            .ok_or(PresentationBuilderError::SigningSuitePickError)?;

        let context = self
            .context_map
            .clone()
            .map(|map| ContextLoader::default().with_context_map_from(map))
            .transpose()
            .map_err(|e| PresentationBuilderError::Context(format!("{e:?}")))?
            .unwrap_or_default();

        let vp = suite
            .sign_with(
                SignatureEnvironment {
                    json_ld_loader: context,
                    eip712_loader: (),
                },
                vp,
                &resolver,
                self,
                params,
                Default::default(),
            )
            .await?;

        Ok(serde_json::to_string(&vp)?)
    }
}

impl MessageSigner<WithProtocol<ssi::crypto::Algorithm, AnyProtocol>>
    for JsonLdPresentationBuilder
{
    #[allow(async_fn_in_trait)]
    async fn sign(
        self,
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

        let curve_utils = match self.signer.algorithm() {
            ssi::crypto::Algorithm::ES256 => Ok(CryptoCurveUtils::secp256r1()),
            alg => Err(MessageSignatureError::UnsupportedAlgorithm(format!(
                "Unsupported curve utils for algorithm: {alg:?}"
            ))),
        };

        match self.signer.cryptosuite().as_ref() {
            "EcdsaSecp256r1Signature2019" | "ecdsa-rdfc-2019" => curve_utils
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

impl<M> ssi::verification_methods::Signer<M> for JsonLdPresentationBuilder
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
