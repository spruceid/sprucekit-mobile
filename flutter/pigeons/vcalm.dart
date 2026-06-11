import 'package:pigeon/pigeon.dart';

@ConfigurePigeon(
  PigeonOptions(
    dartOut: 'lib/pigeon/vcalm.g.dart',
    dartOptions: DartOptions(),
    kotlinOut:
        'android/src/main/kotlin/com/spruceid/sprucekit_mobile/Vcalm.g.kt',
    kotlinOptions: KotlinOptions(
      errorClassName: 'VcalmFlutterError',
      package: 'com.spruceid.sprucekit_mobile',
    ),
    swiftOut: 'ios/Classes/Vcalm.g.swift',
    swiftOptions: SwiftOptions(errorClassName: 'VcalmPigeonError'),
    dartPackageName: 'sprucekit_mobile',
  ),
)
/// The outcome of one VCALM (`vcapi`) exchange step.
///
/// Mirrors the Rust `StepResult` enum
/// (`Request | Offer | Redirect | Complete | Problem`), which is the stable
/// cross-layer surface. Pigeon and UniFFI are separate codegens that meet
/// inside the native adapter: the adapter projects the UniFFI `StepResult`
/// returned by `VcalmHolder` onto this sealed hierarchy.
sealed class VcalmStepResult {}

/// The verifier asked for a presentation (a Verifiable-Presentation-Request).
///
/// The VPR itself is projected to display data — `challenge`/`domain`/`purpose`
/// plus, via [Vcalm.requestedFields], the named fields. [vprListsSdSuite] is an
/// indicator the adapter computes from `Vpr.accepted_cryptosuites` so the demo
/// can show whether the selective-disclosure leg is in play. The cryptosuite
/// itself is server-driven — there is no client toggle.
class VcalmRequest implements VcalmStepResult {
  String? challenge;
  String? domain;
  String? purpose;
  bool vprListsSdSuite;

  VcalmRequest({
    this.challenge,
    this.domain,
    this.purpose,
    required this.vprListsSdSuite,
  });
}

/// The issuer offered one or more credentials.
class VcalmOffer implements VcalmStepResult {
  List<VcalmOfferedCredentialData> credentials;
  bool hasNextRequest;

  VcalmOffer({required this.credentials, required this.hasNextRequest});
}

/// The exchange wants the holder to follow a redirect URL.
class VcalmRedirect implements VcalmStepResult {
  String url;

  VcalmRedirect({required this.url});
}

/// The exchange completed with nothing further to do.
///
/// The Rust `StepResult::Complete` is a unit variant carrying no data. Pigeon
/// (26.x) cannot codec a field-less data class in a sealed hierarchy, so this
/// member carries a single always-`true` marker. Match on the type, not the
/// flag.
class VcalmComplete implements VcalmStepResult {
  bool completed;

  VcalmComplete({this.completed = true});
}

/// The exchange returned an RFC 9457 problem document.
class VcalmProblem implements VcalmStepResult {
  String problemType;
  int? status;
  String? title;
  String? detail;

  VcalmProblem({
    required this.problemType,
    this.status,
    this.title,
    this.detail,
  });
}

/// Result of creating a holder session.
sealed class VcalmResult {}

/// Holder session created successfully.
class VcalmSuccess implements VcalmResult {
  String? message;

  VcalmSuccess({this.message});
}

/// Holder session creation failed.
class VcalmError implements VcalmResult {
  String message;

  VcalmError({required this.message});
}

/// A field named by the current VPR's QueryByExample query.
///
/// Informational only: `ecdsa-rdfc-2019` reveals the entire credential, so this
/// surfaces what will be shared for user display — it does NOT limit fields.
class VcalmRequestedFieldData {
  int queryIndex;
  String path;
  String value;
  bool required;
  String? purpose;

  VcalmRequestedFieldData({
    required this.queryIndex,
    required this.path,
    required this.value,
    required this.required,
    this.purpose,
  });
}

/// A credential previewed from the current Offer.
///
/// [validity] is one of `valid` | `timeBounded` | `proofInvalid` | `enveloped` |
/// `unverifiable`.
class VcalmOfferedCredentialData {
  String? issuer;
  List<String> types;
  String? credentialSubject;
  String validity;

  VcalmOfferedCredentialData({
    this.issuer,
    required this.types,
    this.credentialSubject,
    required this.validity,
  });
}

/// Stable key for a credential matched within a single VCALM session.
///
/// The Rust `ParsedCredential` is an opaque UniFFI `Object` handle that CANNOT
/// cross Pigeon. The native adapter retains the live handles in a key-map and
/// accepts these lightweight keys back from Dart. A credential is identified by
/// the pair `(queryIndex, credentialId)`.
class VcalmCredentialKey {
  int queryIndex;
  String credentialId;

  VcalmCredentialKey({required this.queryIndex, required this.credentialId});
}

/// VCALM (`vcapi`) holder presentation + issuance API.
///
/// Drives one `vcapi` exchange end-to-end: receive a credential via VCALM
/// issuance (Offer → accept), then present it via QueryByExample (Request →
/// submit). All protocol logic lives in the Rust `VcalmHolder`; this surface is
/// a pure marshaling contract.
@HostApi()
abstract class Vcalm {
  /// Build the holder session.
  ///
  /// The signer is `did:key` over the named keystore key (the one deliberate
  /// divergence from OID4VP, which uses `did:jwk`).
  ///
  /// @param credentialPackIds Credential pack IDs to enumerate for QBE matching
  /// @param trustedDids Trusted DIDs for verification (forward-looking)
  /// @param keyId The keystore key identifier for signing (created if absent)
  /// @param contextMap Optional JSON-LD context map
  @async
  VcalmResult createHolder(
    List<String> credentialPackIds,
    List<String> trustedDids,
    String keyId,
    Map<String, String>? contextMap,
  );

  /// Start the exchange from an `interaction:`/`https` URL.
  ///
  /// @param url The exchange or discovery URL
  /// @param authHeader Optional bearer token sent on exchange POSTs (never on
  ///   the discovery GET)
  /// @return The first [VcalmStepResult]
  @async
  VcalmStepResult startExchange(String url, String? authHeader);

  /// Matched credentials for the current VPR's QueryByExample queries.
  ///
  /// @return Stable keys only (the opaque handles stay native)
  @async
  List<VcalmCredentialKey> matchedCredentials();

  /// Fields named by the current VPR (for user display).
  @async
  List<VcalmRequestedFieldData> requestedFields();

  /// Submit a Verifiable Presentation for the selected credentials.
  ///
  /// The chosen cryptosuite is server-driven.
  ///
  /// @param selected Stable keys of the credentials the user selected
  @async
  VcalmStepResult submitPresentation(List<VcalmCredentialKey> selected);

  /// Preview the credentials offered in the current Offer.
  @async
  List<VcalmOfferedCredentialData> offeredCredentials();

  /// Accept the current Offer (verify + store), then advance the exchange.
  @async
  VcalmStepResult acceptOffer();

  /// Reject the current Offer, then advance the exchange.
  @async
  VcalmStepResult rejectOffer();

  /// Cancel and clean up the current session.
  void cancel();
}
