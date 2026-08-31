//! Caller-defined certificate profiles for mdoc validation.
//!
//! ISO/IEC 18013-5 Annex B's *structure* is credential-agnostic -- IACA root, no sub-CAs, subject
//! key identifier, key usage, CRL distribution points, issuer alternative name, matching country
//! codes. What is mDL-specific is the OID values. Other credentials define their own on their own
//! arc, and ISO/IEC TS 23220-4 Annex B is the recipe for doing so.
//!
//! This module lets a caller say which rules apply to which doctype, at three levels of control:
//!
//! - [`IssuerCertificateProfile::Builtin`] -- one of the profiles isomdl ships.
//! - [`IssuerCertificateProfile::Config`] -- the 18013-5 Annex B checks with caller-supplied OIDs
//!   and extension rules. The structural checks are unchanged; only the parameters move.
//! - [`IssuerCertificateProfile::Callback`] -- validation delegated wholly to caller code
//!   implementing [`MdocCertificateProfile`].
//!
//! # A callback profile is trusted completely
//!
//! The `validate_*` methods report problems by returning them. A profile that returns an empty
//! list from all three accepts **any** certificate, including an expired one, a self-signed one,
//! or one issued by an unrelated CA -- issuer authentication then means nothing. Callback
//! implementations must either perform the Annex B checks themselves or delegate to a `Builtin`
//! profile for the parts they do not override.

use std::{
    collections::{BTreeMap, HashMap},
    sync::Arc,
};

use isomdl::definitions::x509::{
    trust_anchor::TrustPurpose,
    validation::{
        CertificateProfile as IsoCertificateProfile, ChainRule, ExtensionRule, IssuerProfile,
        MdocProfile, ObjectIdentifier, ProfileSelector, RdnRule, ReaderProfile, RevocationRule,
    },
};
use x509_cert::{der::Encode, Certificate};

/// Which trust anchors may terminate a certificate chain.
#[derive(uniffi::Enum, Clone, Copy, Debug, PartialEq, Eq)]
pub enum CertificateTrustPurpose {
    /// Issuer Authority Certificate Authority (ISO/IEC 18013-5).
    Iaca,
    /// Reader Certificate Authority (ISO/IEC 18013-5).
    ReaderCa,
    /// The CA that issues VICAL signer certificates.
    VicalAuthority,
}

/// Whether intermediate CA certificates may sit between the end-entity certificate and the trust
/// anchor. ISO/IEC 18013-5 Annex B forbids sub-CAs.
#[derive(uniffi::Enum, Clone, Copy, Debug, PartialEq, Eq)]
pub enum CertificateChainRule {
    /// Only the first certificate of the chain is used, and the trust anchor must be its direct
    /// issuer.
    EndEntityOnly,
    /// The chain is walked from end-entity towards root until it reaches a trust anchor.
    WalkToTrustAnchor,
}

/// Where a PKI publishes revocation.
#[derive(uniffi::Enum, Clone, Copy, Debug, PartialEq, Eq)]
pub enum CertificateRevocationRule {
    /// Certificates carry `cRLDistributionPoints` and are checked against the CRL found there.
    Crl,
    /// Revocation is published somewhere this library does not read. No CRL is fetched, and a
    /// warning records that revocation went unchecked so it never reads as "checked and clean".
    OutOfBand,
}

/// How a relative distinguished name is compared between end-entity certificate and trust anchor.
#[derive(uniffi::Enum, Clone, Copy, Debug, PartialEq, Eq)]
pub enum CertificateRdnRule {
    /// Compare only when at least one of the two carries the attribute, as ISO/IEC 18013-5
    /// requires. Absent from both is conformant.
    MatchIfPresent,
    /// Compare unconditionally, which also fails when the attribute is absent. AAMVA requires this
    /// of `stateOrProvinceName`.
    Required,
}

/// Whether a certificate extension is mandatory.
#[derive(uniffi::Enum, Clone, Copy, Debug, PartialEq, Eq)]
pub enum CertificateExtensionRule {
    /// The certificate must carry the extension, as ISO/IEC 18013-5 Annex B requires of
    /// `cRLDistributionPoints` and `issuerAlternativeName`.
    Required,
    /// The certificate may omit the extension.
    Optional,
}

/// The certificate profiles isomdl ships.
#[derive(uniffi::Enum, Clone, Copy, Debug, PartialEq, Eq)]
pub enum BuiltinCertificateProfile {
    /// ISO/IEC 18013-5 mDL.
    Mdl,
    /// AAMVA's mDL profile, which requires `stateOrProvinceName` to match.
    AamvaMdl,
    /// The EUDI Person Identification Data profile.
    EudiPid,
    /// ISO/IEC TS 23220-4 Annex B, used by the Photo ID profile. Note 23220-4 says a conformant
    /// profile *may* use these OIDs, not that it must, so a real deployment may define its own.
    Iso23220,
}

impl BuiltinCertificateProfile {
    fn profile(self) -> MdocProfile {
        match self {
            Self::Mdl => MdocProfile::MDL,
            Self::AamvaMdl => MdocProfile::AAMVA_MDL,
            Self::EudiPid => MdocProfile::EUDI_PID,
            Self::Iso23220 => MdocProfile::ISO_23220,
        }
    }
}

/// The ISO/IEC 18013-5 Annex B document-signer checks, parameterised.
///
/// The structural checks and the chain, revocation and trust-purpose rules are Annex B's and are
/// not configurable here: an IACA trust anchor, no sub-CAs, and CRL-based revocation. Use
/// [`IssuerCertificateProfile::Callback`] to depart from those.
#[derive(uniffi::Record, Clone, Debug)]
pub struct IssuerProfileConfig {
    /// Extended key usage OID the document signer certificate must carry, in dotted form --
    /// `"1.0.18013.5.1.2"` for an mDL, `"1.0.23220.4.1.2"` for ISO/IEC TS 23220-4.
    ///
    /// The certificate's extended key usage must contain this OID and nothing else, so a signer
    /// shared between two credential types needs one certificate per profile rather than one
    /// certificate carrying both OIDs.
    pub document_signer_eku: String,
    /// How `stateOrProvinceName` is compared against the trust anchor.
    pub state_or_province: CertificateRdnRule,
    /// Whether `cRLDistributionPoints` is mandatory.
    pub crl_distribution_points: CertificateExtensionRule,
    /// Whether `issuerAlternativeName` is mandatory.
    pub issuer_alternative_name: CertificateExtensionRule,
}

/// The ISO/IEC 18013-5 Annex B reader-certificate checks, parameterised.
#[derive(uniffi::Record, Clone, Debug)]
pub struct ReaderProfileConfig {
    /// Extended key usage OID the reader certificate must carry, in dotted form --
    /// `"1.0.18013.5.1.6"` for an mDL reader, `"1.0.23220.4.1.6"` for ISO/IEC TS 23220-4.
    pub reader_auth_eku: String,
    /// Whether `cRLDistributionPoints` is mandatory.
    pub crl_distribution_points: CertificateExtensionRule,
    /// Whether `issuerAlternativeName` is mandatory.
    pub issuer_alternative_name: CertificateExtensionRule,
}

/// Caller-implemented certificate validation rules.
///
/// Certificates arrive DER-encoded; parse them with `CertificateFactory` on Android or
/// `SecCertificate` / swift-certificates on iOS. The three `validate_*` methods return a list of
/// problems, empty meaning "no objection" -- see the module documentation on how much that trusts
/// an implementation.
#[uniffi::export(with_foreign)]
pub trait MdocCertificateProfile: Send + Sync {
    /// Checks applied to the end-entity certificate -- document signer or reader, depending on
    /// which half of a [`MdocCertificateProfiles`] this is.
    fn validate_end_entity(&self, certificate_der: Vec<u8>) -> Vec<String>;

    /// Which trust anchors may terminate the chain.
    fn trust_purpose(&self) -> CertificateTrustPurpose;

    /// Checks applied to the trust anchor the chain terminates at. Return an empty list to state
    /// that this profile does not constrain its anchor.
    fn validate_trust_anchor(&self, certificate_der: Vec<u8>) -> Vec<String>;

    /// Checks comparing the end-entity certificate against its trust anchor, such as the matching
    /// country codes ISO/IEC 18013-5 Annex B requires.
    fn validate_against_trust_anchor(
        &self,
        end_entity_der: Vec<u8>,
        trust_anchor_der: Vec<u8>,
    ) -> Vec<String>;

    /// Whether intermediate CA certificates are permitted.
    fn chain(&self) -> CertificateChainRule;

    /// Where this PKI publishes revocation.
    fn revocation(&self) -> CertificateRevocationRule;
}

/// Rules for document signer certificates of one doctype.
#[derive(uniffi::Enum, Clone)]
pub enum IssuerCertificateProfile {
    /// A profile isomdl ships.
    Builtin { profile: BuiltinCertificateProfile },
    /// The Annex B checks with caller-supplied parameters.
    Config { config: IssuerProfileConfig },
    /// Validation delegated to caller code.
    Callback {
        profile: Arc<dyn MdocCertificateProfile>,
    },
}

/// Rules for reader certificates of one doctype.
#[derive(uniffi::Enum, Clone)]
pub enum ReaderCertificateProfile {
    /// A profile isomdl ships.
    Builtin { profile: BuiltinCertificateProfile },
    /// The Annex B checks with caller-supplied parameters.
    Config { config: ReaderProfileConfig },
    /// Validation delegated to caller code.
    Callback {
        profile: Arc<dyn MdocCertificateProfile>,
    },
}

/// The certificate rules for one doctype, both directions.
///
/// The reader half applies when a holder authenticates an incoming request. A reader-side session
/// never exercises it, and vice versa, so the irrelevant half can be any value.
#[derive(uniffi::Record, Clone)]
pub struct MdocCertificateProfiles {
    /// Rules for the document signer certificate that signed a presented credential.
    pub issuer: IssuerCertificateProfile,
    /// Rules for the certificate a reader authenticates its request with.
    pub reader: ReaderCertificateProfile,
}

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum CertificateProfileError {
    #[error("`{value}` is not a valid object identifier: {cause}")]
    InvalidOid { value: String, cause: String },
}

fn oid(value: &str) -> Result<ObjectIdentifier, CertificateProfileError> {
    ObjectIdentifier::new(value).map_err(|e| CertificateProfileError::InvalidOid {
        value: value.to_string(),
        cause: e.to_string(),
    })
}

/// One `CertificateProfile` implementation covering every way a profile can be supplied, so that
/// `ProfileSelector`'s associated types resolve to a single concrete type and the generic
/// machinery monomorphizes.
#[derive(Clone)]
pub(crate) enum ProfileHalf {
    Issuer(IssuerProfile),
    Reader(ReaderProfile),
    Callback(Arc<dyn MdocCertificateProfile>),
}

impl IsoCertificateProfile for ProfileHalf {
    fn validate_end_entity(&self, certificate: &Certificate) -> Vec<String> {
        match self {
            Self::Issuer(p) => p.validate_end_entity(certificate),
            Self::Reader(p) => p.validate_end_entity(certificate),
            Self::Callback(p) => match certificate.to_der() {
                Ok(der) => p.validate_end_entity(der),
                Err(e) => vec![undecodable("end-entity certificate", e)],
            },
        }
    }

    fn trust_purpose(&self) -> TrustPurpose {
        match self {
            Self::Issuer(p) => p.trust_purpose(),
            Self::Reader(p) => p.trust_purpose(),
            Self::Callback(p) => p.trust_purpose().into(),
        }
    }

    fn validate_trust_anchor(&self, certificate: &Certificate) -> Vec<String> {
        match self {
            Self::Issuer(p) => p.validate_trust_anchor(certificate),
            Self::Reader(p) => p.validate_trust_anchor(certificate),
            Self::Callback(p) => match certificate.to_der() {
                Ok(der) => p.validate_trust_anchor(der),
                Err(e) => vec![undecodable("trust anchor", e)],
            },
        }
    }

    fn validate_against_trust_anchor(
        &self,
        end_entity: &Certificate,
        trust_anchor: &Certificate,
    ) -> Vec<String> {
        match self {
            Self::Issuer(p) => p.validate_against_trust_anchor(end_entity, trust_anchor),
            Self::Reader(p) => p.validate_against_trust_anchor(end_entity, trust_anchor),
            Self::Callback(p) => match (end_entity.to_der(), trust_anchor.to_der()) {
                (Ok(end_entity), Ok(trust_anchor)) => {
                    p.validate_against_trust_anchor(end_entity, trust_anchor)
                }
                (Err(e), _) => vec![undecodable("end-entity certificate", e)],
                (_, Err(e)) => vec![undecodable("trust anchor", e)],
            },
        }
    }

    fn chain(&self) -> ChainRule {
        match self {
            Self::Issuer(p) => p.chain(),
            Self::Reader(p) => p.chain(),
            Self::Callback(p) => p.chain().into(),
        }
    }

    fn revocation(&self) -> RevocationRule {
        match self {
            Self::Issuer(p) => p.revocation(),
            Self::Reader(p) => p.revocation(),
            Self::Callback(p) => p.revocation().into(),
        }
    }

    fn end_entity_name(&self) -> &'static str {
        match self {
            Self::Issuer(p) => p.end_entity_name(),
            Self::Reader(p) => p.end_entity_name(),
            Self::Callback(_) => "End-entity certificate",
        }
    }

    fn trust_anchor_name(&self) -> &'static str {
        match self {
            Self::Issuer(p) => p.trust_anchor_name(),
            Self::Reader(p) => p.trust_anchor_name(),
            Self::Callback(_) => "Trust anchor certificate",
        }
    }
}

/// Re-encoding a certificate that was already parsed should not fail. Report it as a validation
/// problem rather than silently accepting the certificate, so the fail-closed path is taken.
fn undecodable(which: &str, error: x509_cert::der::Error) -> String {
    format!("Could not re-encode the {which} to pass to the certificate profile: {error}")
}

/// Which certificate profile applies to a given doctype.
pub(crate) enum ProfileSelection {
    /// Every doctype is validated under the ISO/IEC 18013-5 mDL profile, and no doctype is
    /// refused for want of configuration. The behaviour before profiles could be configured.
    AnyDocTypeAsMdl,
    /// Per-doctype rules. A doctype absent from the map is refused rather than validated under a
    /// guess.
    PerDocType(BTreeMap<String, MdocProfile<ProfileHalf, ProfileHalf>>),
}

impl ProfileSelector for ProfileSelection {
    type Issuer = ProfileHalf;
    type Reader = ProfileHalf;

    fn issuer_profile_for(&self, doc_type: &str) -> Option<ProfileHalf> {
        match self {
            Self::AnyDocTypeAsMdl => Some(ProfileHalf::Issuer(MdocProfile::MDL.issuer)),
            Self::PerDocType(map) => map.issuer_profile_for(doc_type),
        }
    }

    fn reader_profile_for(&self, doc_type: &str) -> Option<ProfileHalf> {
        match self {
            Self::AnyDocTypeAsMdl => Some(ProfileHalf::Reader(MdocProfile::MDL.reader)),
            Self::PerDocType(map) => map.reader_profile_for(doc_type),
        }
    }
}

impl IssuerCertificateProfile {
    fn half(self) -> Result<ProfileHalf, CertificateProfileError> {
        Ok(match self {
            Self::Builtin { profile } => ProfileHalf::Issuer(profile.profile().issuer),
            Self::Config { config } => ProfileHalf::Issuer(IssuerProfile {
                document_signer_eku: oid(&config.document_signer_eku)?,
                state_or_province: config.state_or_province.into(),
                crl_distribution_points: config.crl_distribution_points.into(),
                issuer_alternative_name: config.issuer_alternative_name.into(),
            }),
            Self::Callback { profile } => ProfileHalf::Callback(profile),
        })
    }
}

impl ReaderCertificateProfile {
    fn half(self) -> Result<ProfileHalf, CertificateProfileError> {
        Ok(match self {
            Self::Builtin { profile } => ProfileHalf::Reader(profile.profile().reader),
            Self::Config { config } => ProfileHalf::Reader(ReaderProfile {
                reader_auth_eku: oid(&config.reader_auth_eku)?,
                crl_distribution_points: config.crl_distribution_points.into(),
                issuer_alternative_name: config.issuer_alternative_name.into(),
            }),
            Self::Callback { profile } => ProfileHalf::Callback(profile),
        })
    }
}

/// Resolve the FFI representation into something isomdl can select with.
///
/// `None` keeps the pre-configuration behaviour: the mDL profile for every doctype.
pub(crate) fn resolve(
    profiles: Option<HashMap<String, MdocCertificateProfiles>>,
) -> Result<ProfileSelection, CertificateProfileError> {
    let Some(profiles) = profiles else {
        return Ok(ProfileSelection::AnyDocTypeAsMdl);
    };
    profiles
        .into_iter()
        .map(|(doc_type, profiles)| {
            Ok((
                doc_type,
                MdocProfile {
                    issuer: profiles.issuer.half()?,
                    reader: profiles.reader.half()?,
                },
            ))
        })
        .collect::<Result<_, _>>()
        .map(ProfileSelection::PerDocType)
}

impl From<CertificateTrustPurpose> for TrustPurpose {
    fn from(value: CertificateTrustPurpose) -> Self {
        match value {
            CertificateTrustPurpose::Iaca => Self::Iaca,
            CertificateTrustPurpose::ReaderCa => Self::ReaderCa,
            CertificateTrustPurpose::VicalAuthority => Self::VicalAuthority,
        }
    }
}

impl From<CertificateChainRule> for ChainRule {
    fn from(value: CertificateChainRule) -> Self {
        match value {
            CertificateChainRule::EndEntityOnly => Self::EndEntityOnly,
            CertificateChainRule::WalkToTrustAnchor => Self::WalkToTrustAnchor,
        }
    }
}

impl From<CertificateRevocationRule> for RevocationRule {
    fn from(value: CertificateRevocationRule) -> Self {
        match value {
            CertificateRevocationRule::Crl => Self::Crl,
            CertificateRevocationRule::OutOfBand => Self::OutOfBand,
        }
    }
}

impl From<CertificateRdnRule> for RdnRule {
    fn from(value: CertificateRdnRule) -> Self {
        match value {
            CertificateRdnRule::MatchIfPresent => Self::MatchIfPresent,
            CertificateRdnRule::Required => Self::Required,
        }
    }
}

impl From<CertificateExtensionRule> for ExtensionRule {
    fn from(value: CertificateExtensionRule) -> Self {
        match value {
            CertificateExtensionRule::Required => Self::Required,
            CertificateExtensionRule::Optional => Self::Optional,
        }
    }
}

#[cfg(test)]
mod tests {
    use std::sync::Mutex;

    use x509_cert::der::{Decode, DecodePem};

    use super::*;

    /// Records what it was handed and objects to everything, so a call reaching it is visible in
    /// the outcome rather than silently passing.
    struct Objector {
        seen: Mutex<Vec<Vec<u8>>>,
    }

    impl Objector {
        fn new() -> Arc<Self> {
            Arc::new(Self {
                seen: Mutex::new(Vec::new()),
            })
        }
    }

    impl MdocCertificateProfile for Objector {
        fn validate_end_entity(&self, certificate_der: Vec<u8>) -> Vec<String> {
            if let Ok(mut seen) = self.seen.lock() {
                seen.push(certificate_der);
            }
            vec!["end entity refused".to_string()]
        }

        fn trust_purpose(&self) -> CertificateTrustPurpose {
            CertificateTrustPurpose::VicalAuthority
        }

        fn validate_trust_anchor(&self, _certificate_der: Vec<u8>) -> Vec<String> {
            vec!["anchor refused".to_string()]
        }

        fn validate_against_trust_anchor(
            &self,
            _end_entity_der: Vec<u8>,
            _trust_anchor_der: Vec<u8>,
        ) -> Vec<String> {
            vec!["pairing refused".to_string()]
        }

        fn chain(&self) -> CertificateChainRule {
            CertificateChainRule::WalkToTrustAnchor
        }

        fn revocation(&self) -> CertificateRevocationRule {
            CertificateRevocationRule::OutOfBand
        }
    }

    fn test_certificate() -> Certificate {
        Certificate::from_pem(include_str!("../../tests/res/mdl/iaca-certificate.pem"))
            .expect("the bundled test IACA certificate should parse")
    }

    fn issuer_eku(selection: &ProfileSelection, doc_type: &str) -> Option<String> {
        match selection.issuer_profile_for(doc_type)? {
            ProfileHalf::Issuer(p) => Some(p.document_signer_eku.to_string()),
            _ => None,
        }
    }

    #[test]
    fn no_configuration_validates_every_doctype_as_an_mdl() {
        let selection = resolve(None).expect("no configuration cannot fail");
        for doc_type in [
            "org.iso.18013.5.1.mDL",
            "org.iso.23220.photoid.1",
            "nonsense",
        ] {
            assert_eq!(
                issuer_eku(&selection, doc_type).as_deref(),
                Some("1.0.18013.5.1.2"),
                "{doc_type} should fall back to the mDL profile"
            );
        }
    }

    #[test]
    fn a_configured_map_refuses_a_doctype_it_does_not_cover() {
        let selection = resolve(Some(HashMap::from([(
            "org.iso.23220.photoid.1".to_string(),
            MdocCertificateProfiles {
                issuer: IssuerCertificateProfile::Builtin {
                    profile: BuiltinCertificateProfile::Iso23220,
                },
                reader: ReaderCertificateProfile::Builtin {
                    profile: BuiltinCertificateProfile::Iso23220,
                },
            },
        )])))
        .expect("a builtin profile cannot fail to resolve");

        assert_eq!(
            issuer_eku(&selection, "org.iso.23220.photoid.1").as_deref(),
            Some("1.0.23220.4.1.2")
        );
        assert!(
            selection
                .issuer_profile_for("org.iso.18013.5.1.mDL")
                .is_none(),
            "a doctype absent from the map must be refused, not guessed at"
        );
    }

    /// Guards the claim made at the Photo ID generation site: the 23220 document signer EKU
    /// differs from the 18013-5 one, and the extended key usage match is exclusive, so a Photo ID
    /// signed with the mDL EKU fails under this profile.
    #[test]
    fn the_iso_23220_profile_does_not_accept_the_mdl_signer_eku() {
        let selection = resolve(Some(HashMap::from([(
            "org.iso.23220.photoid.1".to_string(),
            MdocCertificateProfiles {
                issuer: IssuerCertificateProfile::Builtin {
                    profile: BuiltinCertificateProfile::Iso23220,
                },
                reader: ReaderCertificateProfile::Builtin {
                    profile: BuiltinCertificateProfile::Mdl,
                },
            },
        )])))
        .expect("a builtin profile cannot fail to resolve");

        assert_eq!(
            issuer_eku(&selection, "org.iso.23220.photoid.1").as_deref(),
            Some("1.0.23220.4.1.2")
        );
        assert_ne!(
            issuer_eku(&selection, "org.iso.23220.photoid.1").as_deref(),
            Some("1.0.18013.5.1.2")
        );
    }

    #[test]
    fn a_configured_profile_carries_the_callers_oid() {
        let selection = resolve(Some(HashMap::from([(
            "com.example.badge".to_string(),
            MdocCertificateProfiles {
                issuer: IssuerCertificateProfile::Config {
                    config: IssuerProfileConfig {
                        document_signer_eku: "1.3.6.1.4.1.99999.1".to_string(),
                        state_or_province: CertificateRdnRule::Required,
                        crl_distribution_points: CertificateExtensionRule::Optional,
                        issuer_alternative_name: CertificateExtensionRule::Optional,
                    },
                },
                reader: ReaderCertificateProfile::Builtin {
                    profile: BuiltinCertificateProfile::Mdl,
                },
            },
        )])))
        .expect("a well-formed OID resolves");

        assert_eq!(
            issuer_eku(&selection, "com.example.badge").as_deref(),
            Some("1.3.6.1.4.1.99999.1")
        );
    }

    #[test]
    fn a_malformed_oid_is_an_error_rather_than_a_silently_dropped_rule() {
        let result = resolve(Some(HashMap::from([(
            "com.example.badge".to_string(),
            MdocCertificateProfiles {
                issuer: IssuerCertificateProfile::Config {
                    config: IssuerProfileConfig {
                        document_signer_eku: "not an oid".to_string(),
                        state_or_province: CertificateRdnRule::MatchIfPresent,
                        crl_distribution_points: CertificateExtensionRule::Required,
                        issuer_alternative_name: CertificateExtensionRule::Required,
                    },
                },
                reader: ReaderCertificateProfile::Builtin {
                    profile: BuiltinCertificateProfile::Mdl,
                },
            },
        )])));

        assert!(matches!(
            result,
            Err(CertificateProfileError::InvalidOid { .. })
        ));
    }

    #[test]
    fn a_callback_profile_is_consulted_for_every_rule() {
        let objector = Objector::new();
        let selection = resolve(Some(HashMap::from([(
            "com.example.badge".to_string(),
            MdocCertificateProfiles {
                issuer: IssuerCertificateProfile::Callback {
                    profile: objector.clone(),
                },
                reader: ReaderCertificateProfile::Builtin {
                    profile: BuiltinCertificateProfile::Mdl,
                },
            },
        )])))
        .expect("a callback profile cannot fail to resolve");

        let half = selection
            .issuer_profile_for("com.example.badge")
            .expect("the configured doctype resolves");
        let certificate = test_certificate();

        assert_eq!(
            half.validate_end_entity(&certificate),
            vec!["end entity refused".to_string()]
        );
        assert_eq!(
            half.validate_trust_anchor(&certificate),
            vec!["anchor refused".to_string()]
        );
        assert_eq!(
            half.validate_against_trust_anchor(&certificate, &certificate),
            vec!["pairing refused".to_string()]
        );
        assert_eq!(half.trust_purpose(), TrustPurpose::VicalAuthority);
        assert_eq!(half.chain(), ChainRule::WalkToTrustAnchor);
        assert_eq!(half.revocation(), RevocationRule::OutOfBand);
    }

    /// The callback receives re-encoded DER it can actually parse, not an opaque blob.
    #[test]
    fn a_callback_profile_receives_parseable_der() {
        let objector = Objector::new();
        let half = ProfileHalf::Callback(objector.clone());
        let certificate = test_certificate();

        half.validate_end_entity(&certificate);

        let seen = objector
            .seen
            .lock()
            .expect("no other thread holds the lock");
        let der = seen.first().expect("the callback was handed a certificate");
        assert_eq!(
            &Certificate::from_der(der).expect("the DER handed over round-trips"),
            &certificate
        );
    }
}
