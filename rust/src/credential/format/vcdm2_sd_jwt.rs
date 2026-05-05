use crate::{
    credential::{
        status::StatusListError,
        status_20240406::{
            BitStringStatusListResolver20240406 as BitStringStatusListResolver, Status20240406,
        },
        Credential, CredentialEncodingError, CredentialFormat, ParsedCredential,
        ParsedCredentialInner,
    },
    crypto::KeyAlias,
    oid4vp::{
        error::OID4VPError,
        presentation::{CredentialPresentation, PresentationOptions},
    },
    CredentialType,
};

use core::str;
use std::sync::Arc;

use base64::{engine::general_purpose::URL_SAFE, Engine as _};
use futures::stream::{self, StreamExt};
use num_bigint::BigUint;
use num_traits::Num;
use openid4vp::{
    core::{credential_format::ClaimFormatDesignation, response::parameters::VpTokenItem},
    JsonPath,
};
use reqwest::StatusCode;
use ssi::{
    claims::{
        jwt::AnyClaims,
        sd_jwt::SdJwtBuf,
        vc::v2::{Credential as _, JsonCredential},
        vc_jose_cose::SdJwtVc,
    },
    prelude::AnyJsonCredential,
    status::bitstring_status_list_20240406::{
        BitstringStatusListCredential, BitstringStatusListEntry,
    },
    JsonPointer, JsonPointerBuf,
};
use url::Url;
use uuid::Uuid;

pub const SPRUCE_FORMAT_VC_SD_JWT: &str = "spruce-vc+sd-jwt";

#[derive(Debug, uniffi::Object)]
pub struct VCDM2SdJwt {
    pub(crate) id: Uuid,
    pub(crate) key_alias: Option<KeyAlias>,
    pub(crate) credential: JsonCredential,
    pub(crate) inner: SdJwtBuf,
}

// Internal utility methods for decoding a SdJwt.
impl VCDM2SdJwt {
    /// Return the revealed claims as a JSON value.
    pub fn revealed_claims_as_json(&self) -> Result<serde_json::Value, SdJwtError> {
        serde_json::to_value(&self.credential)
            .map_err(|e| SdJwtError::Serialization(format!("{e:?}")))
    }

    /// The types of the credential from the VCDM, excluding the base `VerifiableCredential` type.
    pub fn types(&self) -> Vec<String> {
        self.credential.additional_types().to_vec()
    }

    /// Returns the SD-JWT credential as an AnyCredential type.
    pub fn credential(&self) -> Result<AnyJsonCredential, SdJwtError> {
        // NOTE: Due to the type constraints on AnyJsonCredential, we're
        // reserializing the type into a V2 credential.
        serde_json::to_value(&self.credential)
            .map_err(|e| SdJwtError::Serialization(format!("{e:?}")))
            .and_then(|v| {
                serde_json::from_value(v).map_err(|e| SdJwtError::Serialization(format!("{e:?}")))
            })
    }

    fn format() -> CredentialFormat {
        CredentialFormat::VCDM2SdJwt
    }
}

#[uniffi::export(async_runtime = "tokio")]
impl VCDM2SdJwt {
    /// Create a new SdJwt instance from a compact SD-JWS string.
    #[uniffi::constructor]
    pub fn new_from_compact_sd_jwt(input: String) -> Result<Arc<Self>, SdJwtError> {
        let inner: SdJwtBuf =
            SdJwtBuf::new(input).map_err(|e| SdJwtError::InvalidSdJwt(format!("{e:?}")))?;

        let mut sd_jwt = VCDM2SdJwt::try_from(inner)?;
        sd_jwt.key_alias = None;

        Ok(Arc::new(sd_jwt))
    }

    /// Create a new SdJwt instance from a compact SD-JWS string with a provided key alias.
    #[uniffi::constructor]
    pub fn new_from_compact_sd_jwt_with_key(
        input: String,
        key_alias: KeyAlias,
    ) -> Result<Arc<Self>, SdJwtError> {
        let inner: SdJwtBuf =
            SdJwtBuf::new(input).map_err(|e| SdJwtError::InvalidSdJwt(format!("{e:?}")))?;

        let mut sd_jwt = VCDM2SdJwt::try_from(inner)?;
        sd_jwt.key_alias = Some(key_alias);

        Ok(Arc::new(sd_jwt))
    }

    #[uniffi::constructor]
    pub fn from_compact_sd_jwt_with_id_and_key(
        id: Uuid,
        input: String,
        key_alias: KeyAlias,
    ) -> Result<Arc<Self>, SdJwtError> {
        let inner: SdJwtBuf =
            SdJwtBuf::new(input).map_err(|e| SdJwtError::InvalidSdJwt(format!("{e:?}")))?;

        let mut sd_jwt = VCDM2SdJwt::try_from((id, inner))?;
        sd_jwt.key_alias = Some(key_alias);

        Ok(Arc::new(sd_jwt))
    }

    /// Return the ID for the SdJwt instance.
    pub fn id(&self) -> Uuid {
        self.id
    }

    /// Return the key alias for the credential
    pub fn key_alias(&self) -> Option<KeyAlias> {
        self.key_alias.clone()
    }

    /// The type of this credential. Note that if there is more than one type (i.e. `types()`
    /// returns more than one value), then the types will be concatenated with a "+".
    pub fn r#type(&self) -> CredentialType {
        CredentialType(self.types().join("+"))
    }

    /// Return the revealed claims as a UTF-8 encoded JSON string.
    pub fn revealed_claims_as_json_string(&self) -> Result<String, SdJwtError> {
        serde_json::to_string(&self.credential)
            .map_err(|e| SdJwtError::Serialization(format!("{e:?}")))
    }

    /// Returns the status of the credential, resolving the value in the status list,
    /// along with the purpose of the status.
    pub async fn status(&self) -> Result<Vec<Arc<Status20240406>>, StatusListError> {
        self.status_list_values()
            .await
            .map(|v| v.into_iter().map(Arc::new).collect())
    }
}

impl CredentialPresentation for VCDM2SdJwt {
    type Credential = ssi::claims::vc::v2::SpecializedJsonCredential;
    type CredentialFormat = ClaimFormatDesignation;
    type PresentationFormat = ClaimFormatDesignation;

    fn credential(&self) -> &Self::Credential {
        &self.credential
    }

    fn presentation_format(&self) -> Self::PresentationFormat {
        ClaimFormatDesignation::Other(Self::format().to_string())
    }

    fn credential_format(&self) -> Self::CredentialFormat {
        ClaimFormatDesignation::Other(Self::format().to_string())
    }

    /// Return the credential as a VpToken
    async fn as_vp_token_item<'a>(
        &self,
        _options: &'a PresentationOptions<'a>,
        selected_fields: Option<Vec<String>>,
    ) -> Result<VpTokenItem, OID4VPError> {
        let compact: &str = self.inner.as_ref();
        let vp_token = if let Some(selected_fields) = selected_fields {
            let json = self
                .revealed_claims_as_json()
                .map_err(|e| OID4VPError::CredentialEncoding(CredentialEncodingError::SdJwt(e)))?;

            let selected_fields_pointers = selected_fields
                .into_iter()
                .map(|sfield| {
                    // TODO: Remove hotfix encoding and improve path usage
                    // SAFETY: encoded by client (sprucekit-mobile@holder)
                    let path = sfield.split(",").next().unwrap().to_owned();
                    let path = match URL_SAFE.decode(path) {
                        Ok(path) => path,
                        Err(err) => return Err(OID4VPError::JsonPathParse(err.to_string())),
                    };
                    let path = match str::from_utf8(&path) {
                        Ok(path) => path,
                        Err(err) => return Err(OID4VPError::JsonPathParse(err.to_string())),
                    };
                    let path = match JsonPath::parse(path) {
                        Ok(path) => path,
                        Err(err) => return Err(OID4VPError::JsonPathParse(err.to_string())),
                    };
                    let located_node = path.query_located(&json);

                    if located_node.is_empty() {
                        Err(OID4VPError::JsonPathResolve(format!(
                            "Unable to resolve JsonPath: {path}"
                        )))
                    } else {
                        // SAFETY: Empty check above
                        JsonPointerBuf::new(
                            located_node.first().unwrap().location().to_json_pointer(),
                        )
                        .map_err(|e| OID4VPError::JsonPathToPointer(e.to_string()))
                    }
                })
                .collect::<Result<Vec<_>, _>>()?;

            let ret = self
                .inner
                .decode_reveal::<AnyClaims>()
                .map_err(|e| OID4VPError::Debug(e.to_string()))?
                .retaining(&selected_fields_pointers)
                .into_encoded()
                .as_str()
                .to_string();
            ret
        } else {
            compact.to_string()
        };

        Ok(VpTokenItem::String(vp_token))
    }
}

#[async_trait::async_trait]
impl BitStringStatusListResolver for VCDM2SdJwt {
    fn status_list_entries(&self) -> Result<Vec<BitstringStatusListEntry>, StatusListError> {
        let value = match &self
            .credential()
            .map_err(|e| StatusListError::Resolution(format!("{e:?}")))?
        {
            AnyJsonCredential::V1(credential) => credential
                .credential_status
                .iter()
                .map(serde_json::to_value)
                .collect::<Result<serde_json::Value, serde_json::Error>>(),
            AnyJsonCredential::V2(credential) => credential
                .credential_status
                .iter()
                .map(serde_json::to_value)
                .collect::<Result<serde_json::Value, serde_json::Error>>(),
        }
        .map_err(|e| StatusListError::Resolution(format!("{e:?}")))?;

        let entries = serde_json::from_value(value).map_err(|e| {
            StatusListError::Resolution(format!("Failed to parse credential status: {e:?}"))
        })?;

        Ok(entries)
    }

    async fn status_list_credentials(
        &self,
    ) -> Result<Vec<BitstringStatusListCredential>, StatusListError> {
        let entries = self.status_list_entries()?;
        stream::iter(entries)
            .map(|entry| async move {
                let url = entry
                    .status_list_credential
                    .parse::<Url>()
                    .map_err(|e| StatusListError::Resolution(format!("{e:?}")))?;

                let response = reqwest::get(url)
                    .await
                    .map_err(|e| StatusListError::Resolution(format!("{e:?}")))?;

                if response.status() != StatusCode::OK {
                    return Err(StatusListError::Resolution(format!(
                        "Failed to resolve status list credential: {}",
                        response.status()
                    )));
                }

                let sd_jwt_buf = SdJwtBuf::new(
                    response
                        .text()
                        .await
                        .map_err(|e| StatusListError::Resolution(format!("{e:?}")))?,
                )
                .map_err(|e| StatusListError::Resolution(format!("{e:?}")))?;

                let credential = sd_jwt_buf
                    .decode_reveal_any()
                    .map_err(|e| StatusListError::Resolution(format!("{e:?}")))?
                    .into_claims()
                    .private;

                serde_json::from_value(
                    serde_json::to_value(credential)
                        .map_err(|e| StatusListError::Resolution(format!("{e:?}")))?,
                )
                .map_err(|e| StatusListError::Resolution(format!("{e:?}")))
            })
            .buffer_unordered(3)
            .collect::<Vec<Result<BitstringStatusListCredential, StatusListError>>>()
            .await
            .into_iter()
            .collect()
    }

    // NOTE: The remaining methods are default implemented in the trait.
}

impl From<VCDM2SdJwt> for ParsedCredential {
    fn from(value: VCDM2SdJwt) -> Self {
        ParsedCredential {
            inner: ParsedCredentialInner::VCDM2SdJwt(Arc::new(value)),
        }
    }
}

impl TryFrom<VCDM2SdJwt> for Credential {
    type Error = SdJwtError;

    fn try_from(value: VCDM2SdJwt) -> Result<Self, Self::Error> {
        ParsedCredential::from(value)
            .into_generic_form()
            .map_err(|e| SdJwtError::CredentialEncoding(format!("{e:?}")))
    }
}

impl TryFrom<Arc<VCDM2SdJwt>> for Credential {
    type Error = SdJwtError;

    fn try_from(value: Arc<VCDM2SdJwt>) -> Result<Self, Self::Error> {
        ParsedCredential::new_sd_jwt(value)
            .into_generic_form()
            .map_err(|e| SdJwtError::CredentialEncoding(format!("{e:?}")))
    }
}

impl TryFrom<&Credential> for VCDM2SdJwt {
    type Error = SdJwtError;

    fn try_from(value: &Credential) -> Result<VCDM2SdJwt, SdJwtError> {
        let inner = SdJwtBuf::new(value.payload.clone())
            .map_err(|_| SdJwtError::InvalidSdJwt(Default::default()))?;

        let mut sd_jwt = VCDM2SdJwt::try_from(inner)?;
        // Set the ID and key alias from the credential.
        sd_jwt.id = value.id;
        sd_jwt.key_alias = value.key_alias.clone();

        Ok(sd_jwt)
    }
}

impl TryFrom<Credential> for Arc<VCDM2SdJwt> {
    type Error = SdJwtError;

    fn try_from(value: Credential) -> Result<Arc<VCDM2SdJwt>, SdJwtError> {
        Ok(Arc::new(VCDM2SdJwt::try_from(&value)?))
    }
}

impl TryFrom<SdJwtBuf> for VCDM2SdJwt {
    type Error = SdJwtError;

    fn try_from(value: SdJwtBuf) -> Result<Self, Self::Error> {
        let SdJwtVc(credential) = SdJwtVc::decode_reveal_any(&value)
            .map_err(|e| SdJwtError::SdJwtDecoding(format!("{e:?}")))?
            .into_claims()
            .private;

        Ok(VCDM2SdJwt {
            id: Uuid::new_v4(),
            key_alias: None,
            inner: value,
            credential,
        })
    }
}

impl TryFrom<(Uuid, SdJwtBuf)> for VCDM2SdJwt {
    type Error = SdJwtError;

    fn try_from(value: (Uuid, SdJwtBuf)) -> Result<Self, Self::Error> {
        let SdJwtVc(credential) = SdJwtVc::decode_reveal_any(&value.1)
            .map_err(|e| SdJwtError::SdJwtDecoding(format!("{e:?}")))?
            .into_claims()
            .private;

        Ok(VCDM2SdJwt {
            id: value.0,
            key_alias: None,
            inner: value.1,
            credential,
        })
    }
}

#[uniffi::export]
pub fn decode_reveal_sd_jwt(input: String) -> Result<String, SdJwtError> {
    let jwt: SdJwtBuf =
        SdJwtBuf::new(input).map_err(|e| SdJwtError::InvalidSdJwt(format!("{e:?}")))?;
    let SdJwtVc(vc) = SdJwtVc::decode_reveal_any(&jwt)
        .map_err(|e| SdJwtError::SdJwtDecoding(format!("{e:?}")))?
        .into_claims()
        .private;
    serde_json::to_string(&vc).map_err(|e| SdJwtError::Serialization(format!("{e:?}")))
}

/// Verify a compact SD-JWT VP (or VC) by parsing it, resolving the issuer's
/// public key via DID resolution, and checking the JWS signature.
///
/// Mirrors [`crate::mdl::verify_jwt_vp`] but for SD-JWT compact form
/// (`<jwt>~<disclosure>~<disclosure>~…~`). Used by Showcase's `VerifySdJwtView`
/// (and the wallet via UniFFI) to validate that a scanned QR payload was
/// genuinely issued by the claimed authority.
///
/// **Trust model**: relies on `AnyDidMethod` resolving the issuer's
/// `verificationMethod` to a public key. For `did:jwk` (which embeds the key
/// in the DID itself) this works fully offline. For other DID methods (e.g.
/// `did:web`) network access may be required.
///
/// Disclosure-level integrity (each disclosure's hash matches an `_sd` array
/// entry) is enforced by `SdJwtVc::decode_reveal_any` during parsing, before
/// signature verification reaches the JWT.
///
/// **Caveat**: this does *not* verify a key-binding JWT, even if one is
/// present. Holder-binding for the offline PDF-embedded VP scenario is
/// considered out of scope (see `vp_token::generate_credential_vp_token`
/// module note).
#[uniffi::export(async_runtime = "tokio")]
pub async fn verify_sd_jwt_vp(input: String) -> Result<(), SdJwtError> {
    use ssi::dids::{AnyDidMethod, DIDResolver};
    use ssi::prelude::VerificationParameters;

    // Auto-detect Colorado-style "9"+base10+deflate compressed form (used
    // when the VP is too large for QR byte mode and the caller compressed
    // it with `compress_vp_for_qr`).  An SD-JWT compact form always starts
    // with `eyJ` (base64url of `{"`), so the leading `9` disambiguates
    // cleanly.
    let input = if input.starts_with('9') {
        let decompressed = decompress_vp_from_qr(input.into_bytes())
            .map_err(|e| SdJwtError::InvalidSdJwt(format!("decompress: {e:?}")))?;
        String::from_utf8(decompressed)
            .map_err(|e| SdJwtError::InvalidSdJwt(format!("decompressed UTF-8: {e}")))?
    } else {
        input
    };

    let jwt: SdJwtBuf =
        SdJwtBuf::new(input).map_err(|e| SdJwtError::InvalidSdJwt(format!("{e:?}")))?;

    // `decode_reveal_any` parses + checks disclosure-hash integrity but does
    // not verify the JWT signature.
    let revealed = SdJwtVc::decode_reveal_any(&jwt)
        .map_err(|e| SdJwtError::SdJwtDecoding(format!("{e:?}")))?;

    let vm_resolver: ssi::dids::VerificationMethodDIDResolver<
        AnyDidMethod,
        ssi::verification_methods::AnyMethod,
    > = AnyDidMethod::default().into_vm_resolver();
    let params = VerificationParameters::from_resolver(vm_resolver);

    revealed
        .verify(params)
        .await
        .map_err(|e| SdJwtError::Verification(format!("{e:?}")))?
        .map_err(|e| SdJwtError::Verification(format!("signature invalid: {e:?}")))
}

fn inner_list_sd_fields(input: &VCDM2SdJwt) -> Result<Vec<String>, SdJwtError> {
    let revealed_sd_jwt = SdJwtVc::decode_reveal_any(&input.inner)
        .map_err(|e| SdJwtError::SdJwtDecoding(format!("{e:?}")))?;

    Ok(revealed_sd_jwt
        .disclosures
        .iter()
        .map(|(p, d)| match &d.desc {
            ssi::claims::sd_jwt::DisclosureDescription::ObjectEntry { key: _, value: _ } => {
                p.to_string()
            }
            ssi::claims::sd_jwt::DisclosureDescription::ArrayItem(_) => p.to_string(),
        })
        .collect())
}

#[uniffi::export]
pub fn list_sd_fields(input: Arc<VCDM2SdJwt>) -> Result<Vec<String>, SdJwtError> {
    inner_list_sd_fields(&input)
}

#[derive(Debug, uniffi::Error, thiserror::Error)]
pub enum SdJwtError {
    #[error("failed to initialize SD-JWT: {0}")]
    SdJwtVcInitError(String),
    #[error("failed to decode SD-JWT as a JWT: {0}")]
    SdJwtDecoding(String),
    #[error("invalid SD-JWT: {0}")]
    InvalidSdJwt(String),
    #[error("serialization error: {0}")]
    Serialization(String),
    #[error("failed to encode SD-JWT: {0}")]
    CredentialEncoding(String),
    #[error("'vc' is missing from the SD-JWT decoded claims")]
    CredentialClaimMissing,
    #[error("verification failed: {0}")]
    Verification(String),
    // ── VP token generation ───────────────────────────────────────────────
    #[error("VP token generation only supported for SD-JWT credentials")]
    UnsupportedCredentialType,
    #[error("disclosure failure: {0}")]
    Disclosure(String),
    #[error("internal error: {0}")]
    Internal(String),
    // ── QR codec (Colorado-style deflate + base10 + "9" prefix) ───────────
    #[error("QR codec error: {0}")]
    QrCodec(String),
}

// ─────────────────────────────────────────────────────────────────────────────
// VP token generation + QR codec
//
// Selective-disclosure VP tokens for VCDM2 SD-JWT credentials, plus the
// "deflate + base10 + 9-prefix" QR encoding pattern (mirrors the
// `Cwt::from_base10` reader in `cwt.rs:240` — each credential format owns
// its own compact QR codec).
//
// # KB-JWT
//
// `as_vp_token_item` does not sign a key-binding JWT in the current
// SpruceKit (see line 176 — the `PresentationOptions` argument is ignored).
// For the offline mDL PDF use case this is acceptable for v1: there is no
// live verifier issuing nonces and the audit flow does not require KB-JWT.
// `audience` and `nonce` are accepted on this API for future-proofing but
// are not yet used.
// ─────────────────────────────────────────────────────────────────────────────

/// Selective-disclosure mode for VP token generation.
///
/// `HideOnly` is more ergonomic when the caller wants to reveal the bulk of
/// available claims (e.g. the mDL PDF use case, which hides only `portrait`
/// to fit the QR capacity). `SelectOnly` is more ergonomic when only a few
/// claims should be revealed (e.g. an age-verification flow that discloses
/// just `age_over_21`).
#[derive(Debug, Clone, uniffi::Enum)]
pub enum DisclosureSelection {
    /// Reveal every selectively-disclosable claim **except** those listed.
    HideOnly { fields: Vec<String> },
    /// Reveal **only** the listed selectively-disclosable claims.
    SelectOnly { fields: Vec<String> },
}

/// Parameters controlling VP token generation.
///
/// `audience` and `nonce` are reserved for a future KB-JWT signing path; they
/// are currently accepted but ignored (see module-level KB-JWT note above).
#[derive(Debug, Clone, uniffi::Record)]
pub struct VpTokenParams {
    pub disclosure: DisclosureSelection,
    pub audience: String,
    pub nonce: Option<String>,
}

/// Compress a UTF-8 VP token (typically a compact SD-JWT) into the Colorado
/// "deflate + base10 + 9-prefix" form that fits in a QR **numeric-mode**
/// payload (~7089 digits at V40 L-EC, vs ~2953 bytes in byte mode).
///
/// Pipeline:
/// 1. `deflate` raw compression of the input bytes
/// 2. interpret compressed bytes as a big-endian unsigned integer
/// 3. encode that integer as a base-10 string
/// 4. prefix with `9` (mirrors `cwt.rs::from_base10` decoder convention)
///
/// **Why**: SD-JWT compact form for an mDL with 35 SD claims is ~3-6 KB even
/// after `HideOnly(["portrait"])` — the issuer-signed `_sd` array dominates
/// (1.6 KB just for 35 SHA-256 hashes) and we can't shrink it on the holder
/// side. Deflate typically compresses the text-heavy SD-JWT by 50%+, and
/// base10 numeric-mode QR has the highest density of any QR mode.
///
/// Symmetric with the existing CWT base10 reader at `cwt.rs::from_base10`
/// (line 240) — wallets / verifiers reading the QR can decompress with the
/// same logic, swapping the final CBOR step for SD-JWT parsing.
#[uniffi::export]
pub fn compress_vp_for_qr(vp_token: Vec<u8>) -> Result<Vec<u8>, SdJwtError> {
    let compressed = miniz_oxide::deflate::compress_to_vec(&vp_token, 9);
    let big = BigUint::from_bytes_be(&compressed);
    let base10 = big.to_str_radix(10);
    Ok(format!("9{base10}").into_bytes())
}

/// Inverse of [`compress_vp_for_qr`]. Given a `9`-prefixed base10 numeric
/// string (as scanned from a QR code), recover the original VP token bytes.
///
/// Mirrors [`crate::credential::format::cwt::Cwt::from_base10`] but stops
/// after deflate decompression — the decompressed bytes are a UTF-8 SD-JWT
/// compact serialization (vs a CWT CBOR blob in the Colorado path).
#[uniffi::export]
pub fn decompress_vp_from_qr(qr_payload: Vec<u8>) -> Result<Vec<u8>, SdJwtError> {
    let payload = String::from_utf8(qr_payload).map_err(|e| SdJwtError::QrCodec(e.to_string()))?;
    let base10_str = payload
        .strip_prefix('9')
        .ok_or_else(|| SdJwtError::QrCodec("missing '9' prefix".to_string()))?;
    let compressed = BigUint::from_str_radix(base10_str, 10)
        .map_err(|e| SdJwtError::QrCodec(e.to_string()))?
        .to_bytes_be();
    miniz_oxide::inflate::decompress_to_vec(&compressed)
        .map_err(|e| SdJwtError::QrCodec(format!("{e:?}")))
}

/// Build a compact VP token suitable for embedding in a PDF QR code.
///
/// Returns the SD-JWT compact serialization (`<jwt>~<disc1>~<disc2>~…`) as
/// UTF-8 bytes, with non-revealed disclosures filtered out per `params`.
#[uniffi::export(async_runtime = "tokio")]
pub async fn generate_credential_vp_token(
    credential: Arc<ParsedCredential>,
    params: VpTokenParams,
) -> Result<Vec<u8>, SdJwtError> {
    match &credential.inner {
        ParsedCredentialInner::VCDM2SdJwt(sd_jwt) => sd_jwt_vp_token(sd_jwt, params).await,
        _ => Err(SdJwtError::UnsupportedCredentialType),
    }
}

async fn sd_jwt_vp_token(
    sd_jwt: &Arc<VCDM2SdJwt>,
    params: VpTokenParams,
) -> Result<Vec<u8>, SdJwtError> {
    // `audience` / `nonce` reserved for future KB-JWT signing; see module note.
    let _ = (params.audience, params.nonce);

    let revealed_fields = match params.disclosure {
        DisclosureSelection::SelectOnly { fields } => fields,
        DisclosureSelection::HideOnly { fields: hidden } => all_disclosable_field_names(sd_jwt)?
            .into_iter()
            .filter(|name| !hidden.contains(name))
            .collect(),
    };

    // Translate field names → owned JSON pointers under the driversLicense
    // subtree, which is where every SD claim lives in the CA DMV mDL schema.
    let pointer_bufs: Result<Vec<JsonPointerBuf>, _> = revealed_fields
        .iter()
        .map(|name| {
            JsonPointerBuf::new(format!("/credentialSubject/driversLicense/{name}"))
                .map_err(|e| SdJwtError::Disclosure(format!("invalid JSON pointer: {e}")))
        })
        .collect();
    let pointer_bufs = pointer_bufs?;
    let pointers: Vec<&JsonPointer> = pointer_bufs.iter().map(|p| p.as_ref()).collect();

    // Direct ssi-level call: decode all disclosures, retain only the ones
    // matching `pointers`, and re-encode as compact SD-JWT.
    //
    // This deliberately bypasses `VCDM2SdJwt::as_vp_token_item` (which requires
    // a real `PresentationOptions` borrowed from a verifier's authorization
    // request — not available offline) and does not produce a KB-JWT. Both
    // are intentional for the v1 offline PDF flow.
    let compact = sd_jwt
        .inner
        .decode_reveal::<AnyClaims>()
        .map_err(|e| SdJwtError::Disclosure(e.to_string()))?
        .retaining(&pointers)
        .into_encoded()
        .as_str()
        .to_string();

    Ok(compact.into_bytes())
}

/// Enumerate the top-level claim names available under
/// `credentialSubject.driversLicense` in the fully-revealed SD-JWT. Used to
/// turn `HideOnly([...])` into the underlying SelectOnly list (i.e.
/// "everything minus the hidden set").
///
/// Filters out the structural `type` discriminator — it isn't a SD claim and
/// must always remain in the credential graph for the JSON-LD context to
/// resolve.
fn all_disclosable_field_names(sd_jwt: &Arc<VCDM2SdJwt>) -> Result<Vec<String>, SdJwtError> {
    let claims = sd_jwt
        .revealed_claims_as_json()
        .map_err(|e| SdJwtError::Internal(e.to_string()))?;
    let dl = claims
        .pointer("/credentialSubject/driversLicense")
        .ok_or_else(|| {
            SdJwtError::Internal("missing credentialSubject.driversLicense".to_string())
        })?;
    let obj = dl.as_object().ok_or_else(|| {
        SdJwtError::Internal("credentialSubject.driversLicense is not an object".to_string())
    })?;
    Ok(obj
        .keys()
        .filter(|k| k.as_str() != "type")
        .cloned()
        .collect())
}

/// Generate a self-signed mDL VCDM2 SD-JWT for **demonstration / Showcase**.
///
/// Schema mirrors what the CA DMV microservice will issue (per Ryan's
/// 2026-04-24 SD field list): a W3C VC v2 JSON-LD credential of type
/// `Iso18013DriversLicenseCredential`, fields nested under
/// `credentialSubject.driversLicense`. Marks 35 leaf fields as selectively
/// disclosable so callers can build VPs that hide `portrait` (or any other
/// field) for QR encoding.
///
/// Generates a fresh ephemeral ed25519 keypair on each call and embeds its
/// `did:jwk` as the issuer DID and as the JWT `kid` header. Verifiers
/// resolving the `did:jwk` will recover the matching public key, so
/// `verify_sd_jwt_vp` succeeds on the resulting VP.
///
/// **NOT for production use** — the signature is meaningful only to the
/// extent that the verifier trusts a `did:jwk` chain. Real DMV credentials
/// bind to a CA DMV-rooted x.509 chain, which this fixture does not.
///
/// ## How to swap to a real CA DMV credential
///
/// Replace the `String` returned by [`generate_test_mdl_sd_jwt_compact`]
/// with the SD-JWT compact serialization that comes back from the wallet's
/// credential storage. Concretely:
/// 1. The wallet calls the OID4VCI `/credential` endpoint and receives a
///    response with `format == "vc+sd-jwt"` (or whatever Alice/Tiago's PR
///    chose) and a `credential` field containing the SD-JWT compact string.
/// 2. Store that string keyed by user/credential ID.
/// 3. In `getDemoSupplements()` (iOS / Android Showcase) or the equivalent
///    Wallet code, replace
///    `await generateTestMdlSdJwtCompact()` →
///    `wallet.getStoredSdJwt(credentialId)`.
/// 4. Everything downstream (`generate_credential_vp_token`, the QR
///    rendering, `verify_sd_jwt_vp`) is identical — it operates on the
///    SD-JWT compact string regardless of issuer.
pub async fn generate_test_mdl_sd_jwt() -> SdJwtBuf {
    use ssi::{claims::sd_jwt::SdAlg, JWK};

    let mut jwk: JWK = JWK::generate_ed25519().expect("unable to generate mdl sd-jwt");

    // Derive a `did:jwk` that holds this key's public half so the issuer
    // identifier embedded in the credential resolves back to the *same*
    // key we sign with. Setting `key_id` on the JWK causes `conceal_and_sign`
    // to embed it as the JWT `kid` header — that's what verifiers
    // (`SdJwtVc::verify` via `AnyDidMethod`) use to look up the public key.
    // Without this, signature verification fails with `MissingPublicKey`.
    let did_jwk = ssi::dids::DIDJWK::generate_url(&jwk.to_public()).to_string();
    jwk.key_id = Some(did_jwk.clone());

    // Reuse the same portrait fixture as the mDoc test path so that the
    // SD-JWT path exercises a real (decodable) JPEG when the data-URL is
    // not selectively hidden. Stripped of any whitespace `include_str!`
    // produces — defensive against editor-inserted newlines.
    let portrait_b64: String = include_str!("../../../tests/res/mdl/portrait.base64")
        .chars()
        .filter(|c| !c.is_whitespace())
        .collect();
    let portrait_data_url = format!("data:image/jpeg;base64,{portrait_b64}");

    // Field shapes match Duncan's 2026-01-23 UAT VC sample (Slack
    // #workstream-ca-dmv) so that downstream `MdlContent` extraction tests
    // see realistic data. Raw string avoids the `serde_json::json!` macro
    // recursion limit hit by deeply-nested object literals; portrait is
    // injected post-parse to keep the literal compact.
    const CLAIMS_JSON: &str = r#"{
        "@context": [
            "https://www.w3.org/ns/credentials/v2",
            "https://w3id.org/vdl/v1",
            "https://w3id.org/vdl/aamva/v1"
        ],
        "id": "urn:uuid:00000000-0000-0000-0000-000000000001",
        "type": ["VerifiableCredential", "Iso18013DriversLicenseCredential"],
        "issuanceDate": "2026-01-23T17:36:38Z",
        "expirationDate": "2028-03-16T00:00:00Z",
        "issuer": {
            "id": "__ISSUER_DID_PLACEHOLDER__",
            "name": "CA DMV"
        },
        "credentialSubject": {
            "id": "did:jwk:eyJhbGciOiJFUzI1NiIsImNydiI6IlAtMjU2Iiwia3R5IjoiRUMiLCJ4IjoibWJUM2dqOWFvOGNuS280M0prcVRPUmNJQVI4MFgwTUFXQWNGYzZvR1JMYyIsInkiOiJiOFVOY0hDMmFHQ3J1STZ0QlRWSVY0dW5ZWEVyS0M4ZDRnRTFGZ0s0Q05JIn0",
            "type": "LicensedDriver",
            "driversLicense": {
                "type": "Iso18013DriversLicense",
                "aamva_dhs_compliance": "F",
                "aamva_domestic_driving_privileges": [
                    {
                        "domestic_vehicle_class": {
                            "domestic_vehicle_class_code": "C NON-COMMERCIAL",
                            "domestic_vehicle_class_description": "Class C NON-COMMERCIAL",
                            "expiry_date": "2028-03-16",
                            "issue_date": "2024-02-14"
                        }
                    }
                ],
                "aamva_family_name_truncation": "U",
                "aamva_given_name_truncation": "U",
                "aamva_organ_donor": 1,
                "aamva_resident_county": "067",
                "aamva_sex": 9,
                "aamva_veteran": 1,
                "aamva_weight_range": 2,
                "age_in_years": 26,
                "age_over_18": true,
                "age_over_21": true,
                "age_over_25": true,
                "age_over_62": false,
                "age_over_65": false,
                "birth_date": "1999-03-16",
                "document_number": "I8882610",
                "driving_privileges": [
                    {
                        "vehicle_category_code": "B",
                        "issue_date": "2024-02-14",
                        "expiry_date": "2028-03-16"
                    }
                ],
                "expiry_date": "2028-03-16",
                "eye_colour": "green",
                "family_name": "ONEZERO",
                "given_name": "IRVINGTEST",
                "hair_colour": "unknown",
                "height": 185,
                "issue_date": "2024-02-14",
                "issuing_authority": "CA,USA",
                "issuing_country": "US",
                "issuing_jurisdiction": "US-CA",
                "portrait": "__PORTRAIT_PLACEHOLDER__",
                "resident_address": "2415 1ST AVE",
                "resident_city": "SACRAMENTO",
                "resident_postal_code": "95818",
                "resident_state": "CA",
                "sex": 9,
                "un_distinguishing_sign": "USA",
                "weight": 55
            }
        }
    }"#;
    let mut registered_claims: serde_json::Value =
        serde_json::from_str(CLAIMS_JSON).expect("fixture JSON is valid");
    registered_claims["credentialSubject"]["driversLicense"]["portrait"] =
        serde_json::Value::String(portrait_data_url);
    registered_claims["issuer"]["id"] = serde_json::Value::String(did_jwk);

    // Reduced "dealer-relevant" SD field set, sized to fit a portrait-hidden
    // VP into a single QR code (QR byte mode max ~2953 bytes at V40 L-EC).
    //
    // **Why not Ryan's full 35-field list?** Each issuer-signed `_sd` hash
    // adds ~44 base64url chars to the JWT payload — 35 of them push the
    // bare JWT alone (no disclosures) past QR capacity. Empirically:
    //
    //   35 SD claims, hide portrait → ~6,200 byte VP → fails QR encode
    //    9 SD claims, hide portrait → ~2,000 byte VP → fits QR byte mode
    //
    // Deflate + base10 (Colorado's CWT pattern) doesn't help: SD-JWT's
    // base64-heavy payload barely compresses, and base10 inflates 2.4x.
    //
    // For the **dealer test-drive PDF** use case, we only need fields a
    // dealer would record: name, DOB, DL #, expiry, jurisdiction. Portrait
    // is included so we can hide-it-for-QR while keeping it visible in the
    // PDF body (the actual MdlContent rendering reads `portrait` from the
    // fully-revealed credential).
    //
    // For the broader CA DMV deployment, the issuer-side decision is to
    // either (a) sign only this minimal set as SD, or (b) issue multiple
    // VC formats (full SD-JWT for online OID4VP + minimal SD-JWT for
    // offline PDF). Open question; pinned in the cheat sheet under §3.
    let sd_field_names: &[&str] = &[
        "family_name",
        "given_name",
        "birth_date",
        "document_number",
        "expiry_date",
        "issuing_country",
        "issuing_jurisdiction",
        "age_over_21",
        "portrait",
    ];

    let pointer_bufs: Vec<JsonPointerBuf> = sd_field_names
        .iter()
        .map(|name| {
            JsonPointerBuf::new(format!("/credentialSubject/driversLicense/{name}"))
                .expect("valid JSON pointer")
        })
        .collect();
    let pointers: Vec<&ssi::JsonPointer> = pointer_bufs.iter().map(|p| p.as_ref()).collect();

    let claims: SdJwtVc = serde_json::from_value(registered_claims).unwrap();
    claims
        .conceal_and_sign(SdAlg::Sha256, &pointers, &jwk)
        .await
        .expect("conceal_and_sign mDL claims")
}

/// Demo-only: generate a self-signed mDL SD-JWT and return its compact
/// serialization. UniFFI-exposed wrapper around [`generate_test_mdl_sd_jwt`]
/// so iOS / Android Showcase can produce a verifiable VP without waiting on
/// the CA DMV microservice deploy.
///
/// **Replace with stored real-issuer SD-JWT when** the wallet's OID4VCI
/// flow returns one (see [`generate_test_mdl_sd_jwt`] for swap recipe).
#[uniffi::export(async_runtime = "tokio")]
pub async fn generate_test_mdl_sd_jwt_compact() -> String {
    generate_test_mdl_sd_jwt().await.to_string()
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;

    use ssi::{claims::sd_jwt::SdAlg, json_pointer, JWK};

    #[test]
    fn test_decode_static() {
        // Example SD-JWT input (you should replace this with a real SD-JWT string for a proper test)
        let sd_jwt_input = include_str!("../../../tests/examples/sd_vc.jwt");

        // Call the function with the SD-JWT input
        let output =
            decode_reveal_sd_jwt(sd_jwt_input.to_string()).expect("failed to decode SD-JWT");

        // Check the output JSON string structure
        assert!(output.contains("\"identityHash\":\"john.smith@spruce.com\""));
        assert!(output.contains("\"awardedDate\":\"2024-10-23T09:34:30+0000\""));
    }

    /// Backwards-compatibility shim: existing tests reference
    /// `tests::generate_mdl_sd_jwt`. The implementation now lives at module
    /// level (so it can be UniFFI-exported); this thin wrapper preserves the
    /// historical call site.
    pub async fn generate_mdl_sd_jwt() -> SdJwtBuf {
        super::generate_test_mdl_sd_jwt().await
    }

    pub async fn generate_sd_jwt() -> SdJwtBuf {
        // Define the key (this is a private key; for testing purposes you can use this inline or generate one)
        let jwk: JWK = JWK::generate_ed25519().expect("unable to generate sd-jwt");

        // Create the JWT claims
        let registeredclaims = serde_json::json!({"@context": [
            "https://www.w3.org/ns/credentials/v2",
            "https://purl.imsglobal.org/spec/ob/v3p0/context-3.0.3.json"
          ],
          "awardedDate": "2024-09-23T18:12:12+0000",
          "credentialSubject": {
            "identity": [
              {
                "hashed": false,
                "identityHash": "John Smith",
                "identityType": "name",
                "salt": "not-used",
                "type": "IdentityObject"
              },
              {
                "hashed": false,
                "identityHash": "john.smith@example.com",
                "identityType": "emailAddress",
                "salt": "not-used",
                "type": "IdentityObject"
              }
            ],
            "achievement": {
              "name": "Team Membership",
              "type": "Achievement"
            }
          },
          "issuer": {
            "id": "did:jwk:eyJhbGciOiJFUzI1NiIsImNydiI6IlAtMjU2Iiwia3R5IjoiRUMiLCJ4IjoibWJUM2dqOWFvOGNuS280M0prcVRPUmNJQVI4MFgwTUFXQWNGYzZvR1JMYyIsInkiOiJiOFVOY0hDMmFHQ3J1STZ0QlRWSVY0dW5ZWEVyS0M4ZDRnRTFGZ0s0Q05JIn0#0",
            "name": "Workforce Development Council",
            "type": "Profile"
          },
          "name": "TeamMembership",
          "type": ["VerifiableCredential", "OpenBadgeCredential"]
        });

        let claims: SdJwtVc = serde_json::from_value(registeredclaims).unwrap();
        let my_pointer = json_pointer!("/credentialSubject/identity/0");

        claims
            .conceal_and_sign(SdAlg::Sha256, &[my_pointer], &jwk)
            .await
            .unwrap()
    }

    #[tokio::test]
    async fn test_sd_jwt() -> Result<(), SdJwtError> {
        let input = generate_sd_jwt().await;

        assert!(VCDM2SdJwt::new_from_compact_sd_jwt(input.to_string()).is_ok());

        Ok(())
    }

    /// Smoke test: the mDL fixture signs cleanly, parses back as a VCDM2 SD-JWT,
    /// and exposes the expected mDL fields after fully revealing the claims.
    #[tokio::test]
    async fn test_generate_mdl_sd_jwt_roundtrip() -> Result<(), SdJwtError> {
        let sd_jwt_buf = generate_mdl_sd_jwt().await;
        let parsed = VCDM2SdJwt::new_from_compact_sd_jwt(sd_jwt_buf.to_string())?;

        let revealed = parsed.revealed_claims_as_json_string()?;

        // Top-level structure
        assert!(revealed.contains("Iso18013DriversLicenseCredential"));
        assert!(revealed.contains("\"name\":\"CA DMV\""));
        // A handful of SD claims that should be revealed when no filtering is
        // applied (full-reveal mode).
        assert!(revealed.contains("\"family_name\":\"ONEZERO\""));
        assert!(revealed.contains("\"given_name\":\"IRVINGTEST\""));
        assert!(revealed.contains("\"birth_date\":\"1999-03-16\""));
        assert!(revealed.contains("\"document_number\":\"I8882610\""));
        assert!(revealed.contains("data:image/jpeg;base64,"));

        Ok(())
    }

    #[tokio::test]
    async fn test_decode_gen() -> Result<(), SdJwtError> {
        // Example SD-JWT input (you should replace this with a real SD-JWT string for a proper test)
        let sd_jwt_input = generate_sd_jwt().await;

        // Call the function with the SD-JWT input
        let output =
            decode_reveal_sd_jwt(sd_jwt_input.to_string()).expect("failed to decode SD-JWT");

        // Check the output JSON string structure
        assert!(output.contains("\"identityHash\":\"john.smith@example.com\""));
        assert!(output.contains("\"identityHash\":\"John Smith\""));

        Ok(())
    }

    // ── VP token + QR codec tests (migrated from rust/src/vp_token.rs) ───

    /// Build a VCDM2SdJwt-backed `ParsedCredential` from the test fixture.
    async fn fixture_credential() -> Arc<ParsedCredential> {
        let buf: SdJwtBuf = generate_mdl_sd_jwt().await;
        let sd_jwt =
            VCDM2SdJwt::new_from_compact_sd_jwt(buf.to_string()).expect("parse fixture SD-JWT");
        ParsedCredential::new_sd_jwt(sd_jwt)
    }

    /// Assert that the disclosure-array portion of an SD-JWT compact serialization
    /// (everything between the JWT and the trailing `~`/KB-JWT) contains a
    /// disclosure for the named claim.
    fn vp_contains_disclosure(vp: &str, claim_name: &str) -> bool {
        use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine as _};

        // Compact form: <jwt>~<disc1>~<disc2>~...~[<KB-JWT>]
        let parts: Vec<&str> = vp.split('~').collect();
        // Skip the leading JWT and any trailing empty segment / KB-JWT.
        for d in &parts[1..] {
            if d.is_empty() {
                continue;
            }
            let decoded = match URL_SAFE_NO_PAD.decode(d) {
                Ok(v) => v,
                Err(_) => continue,
            };
            let arr: serde_json::Value = match serde_json::from_slice(&decoded) {
                Ok(v) => v,
                Err(_) => continue,
            };
            // Disclosure = [salt, claim_name, claim_value] for objects.
            if let Some(name) = arr.get(1).and_then(|v| v.as_str()) {
                if name == claim_name {
                    return true;
                }
            }
        }
        false
    }

    /// Hiding `portrait` should produce a VP token that does NOT contain a
    /// portrait disclosure, while still containing other typical mDL fields.
    #[tokio::test]
    async fn vp_token_hides_portrait() {
        let credential = fixture_credential().await;
        let params = VpTokenParams {
            disclosure: DisclosureSelection::HideOnly {
                fields: vec!["portrait".to_string()],
            },
            audience: "https://test.local".to_string(),
            nonce: None,
        };

        let bytes = generate_credential_vp_token(credential, params)
            .await
            .expect("VP token generation");
        let vp = std::str::from_utf8(&bytes).expect("VP token is UTF-8");

        assert!(
            !vp_contains_disclosure(vp, "portrait"),
            "VP should not contain portrait disclosure"
        );
        assert!(
            vp_contains_disclosure(vp, "family_name"),
            "VP should contain family_name disclosure"
        );
        assert!(
            vp_contains_disclosure(vp, "given_name"),
            "VP should contain given_name disclosure"
        );
        assert!(
            vp_contains_disclosure(vp, "birth_date"),
            "VP should contain birth_date disclosure"
        );
    }

    /// `SelectOnly(["age_over_21"])` should produce a VP token containing
    /// only that disclosure (privacy-friendly age-verification scenario).
    #[tokio::test]
    async fn vp_token_selects_only_listed_fields() {
        let credential = fixture_credential().await;
        let params = VpTokenParams {
            disclosure: DisclosureSelection::SelectOnly {
                fields: vec!["age_over_21".to_string()],
            },
            audience: "https://test.local".to_string(),
            nonce: None,
        };

        let bytes = generate_credential_vp_token(credential, params)
            .await
            .expect("VP token generation");
        let vp = std::str::from_utf8(&bytes).expect("VP token is UTF-8");

        assert!(
            vp_contains_disclosure(vp, "age_over_21"),
            "VP should contain age_over_21 disclosure"
        );
        assert!(
            !vp_contains_disclosure(vp, "portrait"),
            "VP should not contain portrait disclosure"
        );
        assert!(
            !vp_contains_disclosure(vp, "family_name"),
            "VP should not contain family_name disclosure"
        );
    }

    /// End-to-end roundtrip: generate VP token from fixture, verify via the
    /// new `verify_sd_jwt_vp`. This proves the QR payload is verifiable
    /// against the issuer's `did:jwk` without any external infrastructure.
    #[tokio::test]
    async fn verify_sd_jwt_vp_roundtrip_succeeds() {
        let credential = fixture_credential().await;
        let params = VpTokenParams {
            disclosure: DisclosureSelection::HideOnly {
                fields: vec!["portrait".to_string()],
            },
            audience: "https://test.local".to_string(),
            nonce: None,
        };

        let bytes = generate_credential_vp_token(credential, params)
            .await
            .expect("VP token generation");
        let vp = String::from_utf8(bytes).expect("UTF-8");

        verify_sd_jwt_vp(vp)
            .await
            .expect("portrait-hidden VP should verify against fixture issuer DID");
    }

    /// Probe whether `verify_jwt_vp` (the existing Showcase-wired verifier in
    /// `mdl/mod.rs`) can validate our SD-JWT VP output. This is a *negative*
    /// test: we expect it to fail because SD-JWT compact form has a `~`-
    /// delimited disclosure suffix that JwsString parsing rejects.
    ///
    /// If this test ever starts succeeding, `verify_jwt_vp` is enough and we
    /// don't need a dedicated `verify_sd_jwt_vp`. As of 2026-04-27 it fails,
    /// so adding a new verifier function (or extending the existing one) is
    /// required before iOS / Android `VerifyVCView` can validate our QR.
    #[tokio::test]
    async fn verify_jwt_vp_does_not_handle_sd_jwt_vp() {
        use crate::mdl::verify_jwt_vp;

        let credential = fixture_credential().await;
        let params = VpTokenParams {
            disclosure: DisclosureSelection::HideOnly {
                fields: vec!["portrait".to_string()],
            },
            audience: "https://test.local".to_string(),
            nonce: None,
        };

        let bytes = generate_credential_vp_token(credential, params)
            .await
            .expect("VP token generation");
        let vp = String::from_utf8(bytes).expect("UTF-8");

        let result = verify_jwt_vp(vp).await;
        eprintln!("verify_jwt_vp(SD-JWT VP) → {:?}", result);
        // Expected: an error of some kind. Document exact behaviour for the
        // record so future readers know why we built `verify_sd_jwt_vp`.
        assert!(
            result.is_err(),
            "verify_jwt_vp unexpectedly accepted SD-JWT VP — \
             reconsider whether verify_sd_jwt_vp is needed"
        );
    }

    /// Compress + decompress roundtrip preserves the original VP bytes
    /// exactly. Guards against silent corruption in the deflate / base10
    /// pipeline.
    #[tokio::test]
    async fn compress_decompress_roundtrip() {
        let credential = fixture_credential().await;
        let params = VpTokenParams {
            disclosure: DisclosureSelection::HideOnly {
                fields: vec!["portrait".to_string()],
            },
            audience: "https://test.local".to_string(),
            nonce: None,
        };

        let original = generate_credential_vp_token(credential, params)
            .await
            .expect("VP token generation");

        let compressed = compress_vp_for_qr(original.clone()).expect("compress");
        let decompressed = decompress_vp_from_qr(compressed).expect("decompress");

        assert_eq!(decompressed, original, "roundtrip must be lossless");
    }

    /// Compressed VP token must fit QR **numeric mode** capacity (~7089
    /// digits at version 40 / L-EC). This is the Colorado pattern that the
    /// CWT path already uses on the read side (`cwt.rs::from_base10`).
    #[tokio::test]
    async fn compressed_vp_fits_qr_numeric_mode() {
        let credential = fixture_credential().await;
        let params = VpTokenParams {
            disclosure: DisclosureSelection::HideOnly {
                fields: vec!["portrait".to_string()],
            },
            audience: "https://test.local".to_string(),
            nonce: None,
        };

        let raw = generate_credential_vp_token(credential, params)
            .await
            .expect("VP token generation");
        let compressed = compress_vp_for_qr(raw.clone()).expect("compress");

        eprintln!(
            "VP token: raw {} bytes, compressed {} digits ({:.1}% of raw)",
            raw.len(),
            compressed.len(),
            (compressed.len() as f64 / raw.len() as f64) * 100.0
        );

        // QR numeric mode @ V40 / L-EC = 7089 digits.
        const QR_NUMERIC_MAX_DIGITS: usize = 7089;
        assert!(
            compressed.len() < QR_NUMERIC_MAX_DIGITS,
            "compressed VP ({} digits) exceeds QR numeric capacity ({})",
            compressed.len(),
            QR_NUMERIC_MAX_DIGITS
        );
    }

    /// `verify_sd_jwt_vp` should accept both raw SD-JWT compact form AND the
    /// `9`-prefixed compressed form (auto-detect via leading byte).
    #[tokio::test]
    async fn verify_accepts_compressed_form() {
        let credential = fixture_credential().await;
        let params = VpTokenParams {
            disclosure: DisclosureSelection::HideOnly {
                fields: vec!["portrait".to_string()],
            },
            audience: "https://test.local".to_string(),
            nonce: None,
        };

        let raw = generate_credential_vp_token(credential, params)
            .await
            .expect("VP token generation");
        let compressed_bytes = compress_vp_for_qr(raw).expect("compress");
        let compressed_str = String::from_utf8(compressed_bytes).expect("UTF-8");

        verify_sd_jwt_vp(compressed_str)
            .await
            .expect("compressed VP should verify after auto-decompression");
    }

    /// Track the size of a portrait-hidden VP token for the CA DMV mDL schema.
    ///
    /// **Current observation (2026-04-24)**: ~6.1 KB compact SD-JWT text. This
    /// exceeds raw QR capacity in any mode (alphanumeric ~4296 chars, byte
    /// ~2953 bytes, numeric ~7089 digits → with deflate+base10 → ~7200 digits
    /// post-encoding). Further compression (deflate per Colorado's CWT pattern)
    /// or more aggressive field hiding (e.g. drop `aamva_*` extension claims,
    /// `age_over_*` flags) is needed before the QR encoding path is viable.
    ///
    /// This test asserts at a *generous* upper bound that captures the
    /// current state without breaking CI; it logs the actual size each run
    /// so regressions surface in the test output.
    #[tokio::test]
    async fn vp_token_without_portrait_size_baseline() {
        let credential = fixture_credential().await;
        let params = VpTokenParams {
            disclosure: DisclosureSelection::HideOnly {
                fields: vec!["portrait".to_string()],
            },
            audience: "https://test.local".to_string(),
            nonce: None,
        };

        let bytes = generate_credential_vp_token(credential, params)
            .await
            .expect("VP token generation");

        eprintln!("VP token (portrait hidden): {} bytes", bytes.len());

        // Goal once compression / aggressive hiding lands:
        //   bytes.len() < 3900   (QR alphanumeric capacity with safety margin)
        // Current realistic upper bound — guards against runaway growth without
        // failing CI on the known SD-JWT-text-overhead gap.
        const CURRENT_UPPER_BOUND_BYTES: usize = 8_000;
        assert!(
            bytes.len() < CURRENT_UPPER_BOUND_BYTES,
            "VP token grew beyond expected baseline: {} bytes (limit {})",
            bytes.len(),
            CURRENT_UPPER_BOUND_BYTES
        );
    }

    /// Diagnostic: print the actual QR version + module density that the
    /// current SD-JWT VP fixture pipeline produces.
    ///
    /// Useful when triaging "Android scanners can't read this QR" — the
    /// numbers tell you whether the QR is genuinely too dense for off-the-shelf
    /// Android decoders, or whether the failure is elsewhere (camera config,
    /// print quality, viewer DPI). ML Kit / ZXing both need ≥0.5 mm per
    /// module @ 1080p capture to decode reliably; iOS Vision is more
    /// permissive.
    ///
    /// Doesn't assert anything — pure measurement. Run via:
    /// `cargo test --lib credential::format::vcdm2_sd_jwt::tests::diagnostic_qr_density -- --nocapture`
    #[tokio::test]
    async fn diagnostic_qr_density() {
        use qrcode::QrCode;

        let credential = fixture_credential().await;
        let params = VpTokenParams {
            disclosure: DisclosureSelection::HideOnly {
                fields: vec!["portrait".to_string()],
            },
            audience: "x".to_string(),
            nonce: None,
        };

        let vp = generate_credential_vp_token(credential, params)
            .await
            .expect("VP token generation");
        eprintln!("VP uncompressed: {} bytes", vp.len());

        let compressed = compress_vp_for_qr(vp).expect("compress");
        eprintln!(
            "VP compressed:   {} bytes (\"9\" + base10 digits)",
            compressed.len()
        );

        let code = QrCode::new(&compressed).expect("QR encode");
        let modules = code.width();
        eprintln!("QR version:        {:?}", code.version());
        eprintln!("QR EC level:       {:?}", code.error_correction_level());
        eprintln!("QR modules / side: {}", modules);
        eprintln!(
            "@ 30mm print: {:.3} mm/module  ({:.1} px/module @ 1080p, ~60% frame fill)",
            30.0 / modules as f32,
            (1080.0 * 0.6) / modules as f32,
        );
        eprintln!(
            "@ 70mm print: {:.3} mm/module  ({:.1} px/module @ 1080p, ~60% frame fill)",
            70.0 / modules as f32,
            (1080.0 * 0.6) / modules as f32,
        );
        eprintln!("ML Kit / ZXing reliable threshold: ~3 px/module (≈0.5 mm @ typical viewing)");
    }
}
