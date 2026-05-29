//! Generic, domain-agnostic hook that lets a consuming application **mint a
//! credential on device during an OID4VP presentation** to satisfy a DCQL
//! credential query, with explicit user opt-in.
//!
//! Some credentials are minted per-presentation rather than stored (e.g.
//! self-issued proofs of possession, on-device-generated VCs). The SDK only
//! presents stored credentials; this hook moves the domain logic for producing
//! such credentials out of the SDK behind a foreign trait — the same way
//! [`crate::crypto::KeyStore`] keeps key material out of the SDK.
//!
//! The provider holds whatever it needs (its own key handles, schema,
//! derivation, signing). The SDK passes it **no `KeyStore` and no domain
//! types**; it does not interpret the minted bytes.

use openid4vp::core::dcql_query::DcqlCredentialQuery;

/// A DCQL credential query passed to a [`DynamicCredentialProvider`], as its
/// JSON serialization.
///
/// `DcqlCredentialQuery` is defined in the `openid4vp` crate, so it cannot
/// itself cross the uniffi FFI (the orphan rule forbids implementing the
/// required FFI traits on a foreign type). Instead the query crosses as its
/// JSON string — the same shape the verifier sent in the `dcql_query` request
/// parameter. Choosing a serialized view also keeps the FFI surface minimal:
/// the SDK does not need to mirror the whole DCQL type tree across uniffi.
///
/// The provider interprets whatever it needs (e.g. `format`,
/// `meta.type_values`, `claims`); use [`DcqlCredentialQueryJson::parse`] to
/// deserialize it into a `DcqlCredentialQuery` in Rust.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DcqlCredentialQueryJson(pub String);

uniffi::custom_newtype!(DcqlCredentialQueryJson, String);

impl DcqlCredentialQueryJson {
    /// Serialize a `DcqlCredentialQuery` into its JSON wire form.
    ///
    /// # Panics
    /// Never in practice: a `DcqlCredentialQuery` always serializes to JSON.
    pub fn from_query(query: &DcqlCredentialQuery) -> Self {
        Self(serde_json::to_string(query).expect("DcqlCredentialQuery serializes to JSON"))
    }

    /// Parse the JSON back into a `DcqlCredentialQuery`.
    pub fn parse(&self) -> Result<DcqlCredentialQuery, serde_json::Error> {
        serde_json::from_str(&self.0)
    }
}

/// An offer to mint a credential that could satisfy a DCQL credential query.
///
/// Surfaced in the [`crate::oid4vp::PermissionRequest`] alongside stored
/// matches so the UI can let the user opt in (or decline). Echoed back to
/// [`DynamicCredentialProvider::mint`] only for offers the user selected.
#[derive(Debug, Clone, uniffi::Record)]
pub struct DynamicCredentialOffer {
    /// Provider-scoped identifier, echoed back to [`DynamicCredentialProvider::mint`].
    pub offer_id: String,
    /// The DCQL credential-query id this offer satisfies (the `vp_token` key
    /// the minted item is placed under).
    pub credential_query_id: String,
    /// Human-readable label for the consent UI.
    pub title: String,
}

/// The presentation-specific values a provider binds a minted credential to.
///
/// Sourced from the live authorization request — the same `nonce` and
/// `client_id` the SDK uses elsewhere when building the response.
#[derive(Debug, Clone, uniffi::Record)]
pub struct PresentationBinding {
    /// The OID4VP request `nonce`.
    pub nonce: String,
    /// The verifier `client_id` (presentation audience).
    pub client_id: String,
}

/// A credential minted by a [`DynamicCredentialProvider`] for a presentation.
#[derive(Debug, Clone, uniffi::Record)]
pub struct MintedCredential {
    /// The `vp_token` entry, verbatim (e.g. a compact JWS). Placed under the
    /// offer's `credential_query_id`. The SDK does not interpret it.
    pub vp_token_item: String,
}

/// Errors a [`DynamicCredentialProvider`] may return from
/// [`DynamicCredentialProvider::mint`].
#[derive(Debug, uniffi::Error, thiserror::Error)]
pub enum DynamicCredentialError {
    /// The user (or provider) declined to mint the offered credential.
    #[error("Dynamic credential minting was cancelled.")]
    Cancelled,

    /// Minting failed for any other reason (signing, derivation, schema, etc.).
    #[error("Failed to mint dynamic credential: {0}")]
    MintFailed(String),
}

/// A foreign-implemented provider that can mint credentials on device during a
/// presentation to satisfy a DCQL credential query.
///
/// The provider owns its own keys, schema and derivation; the SDK passes it no
/// key material and does not interpret what it mints.
#[uniffi::export(with_foreign)]
pub trait DynamicCredentialProvider: Send + Sync {
    /// Credentials this provider could mint to satisfy `query`.
    ///
    /// Surfaced in the [`crate::oid4vp::PermissionRequest`] so the UI can let
    /// the user opt in (or decline). An empty vector means this provider cannot
    /// satisfy the query.
    ///
    /// `query` is the DCQL credential query as JSON; use
    /// [`DcqlCredentialQueryJson::parse`] to inspect it.
    fn offers(&self, query: DcqlCredentialQueryJson) -> Vec<DynamicCredentialOffer>;

    /// Mint the raw `vp_token` entry for a previously-offered credential, bound
    /// to this presentation.
    ///
    /// Called only for offers the user selected. `offer_id` is the value from
    /// the [`DynamicCredentialOffer`] returned by [`Self::offers`].
    fn mint(
        &self,
        offer_id: String,
        binding: PresentationBinding,
    ) -> Result<MintedCredential, DynamicCredentialError>;
}
