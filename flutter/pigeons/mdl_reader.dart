import 'package:pigeon/pigeon.dart';

@ConfigurePigeon(
  PigeonOptions(
    dartOut: 'lib/pigeon/mdl_reader.g.dart',
    dartOptions: DartOptions(),
    kotlinOut:
        'android/src/main/kotlin/com/spruceid/sprucekit_mobile/MdlReader.g.kt',
    kotlinOptions: KotlinOptions(
      errorClassName: 'MdlReaderFlutterError',
      package: 'com.spruceid.sprucekit_mobile',
    ),
    swiftOut: 'ios/Classes/MdlReader.g.swift',
    swiftOptions: SwiftOptions(errorClassName: 'MdlReaderPigeonError'),
    dartPackageName: 'sprucekit_mobile',
  ),
)
/// Reader-side state for ISO 18013-5 mDL verification.
///
/// State transitions for the NFC engagement path:
///   uninitialized → nfcWaitingForTag → nfcExchanging → bleConnecting
///   → bleReceivingResponse → success
///
/// For the QR engagement path:
///   uninitialized → bleConnecting → bleReceivingResponse → success
///
/// Terminal states are [success] and [error]. NFC-specific terminal-ish states
/// ([nfcUnsupported], [nfcDisabled]) are surfaced once at start and require
/// the host to take action (enable NFC, switch to QR, etc.) before retrying.
enum MdlReaderState {
  /// No session running.
  uninitialized,

  /// NFC hardware is not present on this device.
  nfcUnsupported,

  /// NFC adapter exists but is turned off in system settings.
  nfcDisabled,

  /// Reader mode is armed and waiting for a holder tap.
  nfcWaitingForTag,

  /// A tap has been detected; the APDU handover exchange is in progress.
  nfcExchanging,

  /// Engagement (NFC or QR) is done; BLE session is being established.
  bleConnecting,

  /// BLE connected; waiting for the holder's device response.
  bleReceivingResponse,

  /// Response received, parsed, and verified. The [MdlReaderStateUpdate.response]
  /// field is populated.
  success,

  /// Terminal error. The [MdlReaderStateUpdate.error] field is populated.
  error,
}

/// Outcome of authenticity checks. Mirrors Rust `AuthenticationStatus` 1:1.
///
/// - [valid] — signature verified AND certificate chain validated to a
///   trust anchor in the registry passed to start.
/// - [invalid] — signature failed OR chain validation failed.
/// - [unchecked] — not yet validated (e.g. parsing failed before validation
///   could run, or no trust anchors provided).
enum MdlAuthenticationStatus { valid, invalid, unchecked }

/// Verified response from a successful read.
///
/// The verified items are transported as a JSON string (the canonical Rust
/// `verifiedResponseAsJsonString` output) rather than a typed nested Map.
/// This avoids two Pigeon limitations:
///   1. Pigeon's binary codec only shallow-casts nested generic maps, leading
///      to a runtime `_Map<Object?, Object?>` mismatch on the Dart side.
///   2. Recursive class graphs (a typed `MDocItem` would need to reference
///      itself via map/array variants) hit an OOM in Pigeon's type analyzer.
///
/// Consumers should `jsonDecode(verifiedResponseJson)` to get a
/// `Map<String, dynamic>` shaped like:
/// ```
/// {
///   "org.iso.18013.5.1": {
///     "given_name": "ALICE",
///     "age_over_21": true,
///     "portrait": [255, 216, ...],         // JPEG bytes as int array
///     "driving_privileges": [ { ... } ],
///   },
///   "org.iso.18013.5.1.aamva": { ... },
/// }
/// ```
/// Numeric integer values come through as Dart `int`, booleans as `bool`,
/// strings as `String`, nested objects as `Map<String, dynamic>`, arrays as
/// `List<dynamic>`. Byte strings (e.g. `portrait`) arrive as a list of
/// integers in [0, 255] which can be wrapped with `Uint8List.fromList(...)`.
class MdlReadResponse {
  /// JSON-encoded `Map<namespace, Map<element, value>>`. See class docs for
  /// the shape and how to decode.
  String verifiedResponseJson;

  /// Document types (doctypes) from the presented credentials.
  /// E.g. `["org.iso.18013.5.1.mDL"]`.
  List<String> docTypes;

  /// Outcome of issuer (MSO) signature + cert-chain-to-trust-anchor validation.
  MdlAuthenticationStatus issuerAuthentication;

  /// Outcome of device authentication (replay protection).
  MdlAuthenticationStatus deviceAuthentication;

  /// JSON-encoded diagnostics, or null when nothing went wrong. Shaped as:
  /// ```
  /// {
  ///   "response": ["..."],                       // response-level failures
  ///   "documents": { "<claimed doctype>": ["..."] },  // per-document reasons
  ///   "unrequested": ["<claimed doctype>"]       // arrived unasked, not validated
  /// }
  /// ```
  /// The per-document entries carry the reason a document failed, which the
  /// response-level list does not: a document failing on its own contributes
  /// only a bare "documents failed" there. A doctype missing from
  /// [MdlReader.startNfcReader]'s `certificateProfiles` shows up here.
  ///
  /// Consumers can `jsonDecode(errors)` if non-null to inspect specifics.
  String? errors;

  MdlReadResponse({
    required this.verifiedResponseJson,
    required this.docTypes,
    required this.issuerAuthentication,
    required this.deviceAuthentication,
    this.errors,
  });
}

/// State update event from the reader session.
class MdlReaderStateUpdate {
  /// Current state of the reader.
  MdlReaderState state;

  /// Non-null only when [state] == [MdlReaderState.success].
  MdlReadResponse? response;

  /// Non-null only when [state] == [MdlReaderState.error] or terminal NFC
  /// error states ([MdlReaderState.nfcUnsupported], [MdlReaderState.nfcDisabled]).
  String? error;

  MdlReaderStateUpdate({required this.state, this.response, this.error});
}

/// A certificate profile this SDK ships.
///
/// ISO/IEC 18013-5 Annex B's *structure* is credential-agnostic -- IACA root, no sub-CAs, subject
/// key identifier, key usage, matching country codes. What differs between credential types is
/// the OID values, and each of these bundles a known set.
enum MdlBuiltinCertificateProfile {
  /// ISO/IEC 18013-5 mDL. The profile used when none is configured.
  mdl,

  /// AAMVA's mDL profile, which additionally requires `stateOrProvinceName` to match.
  aamvaMdl,

  /// The EUDI Person Identification Data profile.
  eudiPid,

  /// ISO/IEC TS 23220-4 Annex B, used by the Photo ID profile. Note 23220-4 says a conformant
  /// profile *may* use these OIDs, not that it must, so a real deployment may define its own.
  iso23220,
}

/// How a relative distinguished name is compared between end-entity certificate and trust anchor.
enum MdlCertificateRdnRule {
  /// Compare only when at least one of the two carries the attribute, as ISO/IEC 18013-5
  /// requires. Absent from both is conformant.
  matchIfPresent,

  /// Compare unconditionally, which also fails when the attribute is absent.
  required,
}

/// Whether a certificate extension is mandatory.
enum MdlCertificateExtensionRule {
  /// The certificate must carry the extension, as ISO/IEC 18013-5 Annex B requires of
  /// `cRLDistributionPoints` and `issuerAlternativeName`.
  required,

  /// The certificate may omit the extension.
  optional,
}

/// The ISO/IEC 18013-5 Annex B document-signer checks, parameterised.
///
/// The structural checks and the chain, revocation and trust-purpose rules are Annex B's and are
/// not configurable: an IACA trust anchor, no sub-CAs, and CRL-based revocation.
class MdlIssuerProfileConfig {
  /// Extended key usage OID the document signer certificate must carry, in dotted form --
  /// `"1.0.18013.5.1.2"` for an mDL, `"1.0.23220.4.1.2"` for ISO/IEC TS 23220-4.
  ///
  /// The certificate's extended key usage must contain this OID and nothing else, so a signer
  /// shared between two credential types needs one certificate per profile rather than one
  /// certificate carrying both OIDs.
  String documentSignerEku;

  /// How `stateOrProvinceName` is compared against the trust anchor.
  MdlCertificateRdnRule stateOrProvince;

  /// Whether `cRLDistributionPoints` is mandatory.
  MdlCertificateExtensionRule crlDistributionPoints;

  /// Whether `issuerAlternativeName` is mandatory.
  MdlCertificateExtensionRule issuerAlternativeName;

  MdlIssuerProfileConfig({
    required this.documentSignerEku,
    required this.stateOrProvince,
    required this.crlDistributionPoints,
    required this.issuerAlternativeName,
  });
}

/// The ISO/IEC 18013-5 Annex B reader-certificate checks, parameterised.
class MdlReaderProfileConfig {
  /// Extended key usage OID the reader certificate must carry, in dotted form --
  /// `"1.0.18013.5.1.6"` for an mDL reader, `"1.0.23220.4.1.6"` for ISO/IEC TS 23220-4.
  String readerAuthEku;

  /// Whether `cRLDistributionPoints` is mandatory.
  MdlCertificateExtensionRule crlDistributionPoints;

  /// Whether `issuerAlternativeName` is mandatory.
  MdlCertificateExtensionRule issuerAlternativeName;

  MdlReaderProfileConfig({
    required this.readerAuthEku,
    required this.crlDistributionPoints,
    required this.issuerAlternativeName,
  });
}

/// Rules for document signer certificates of one doctype.
///
/// Native callers (Kotlin, Swift) can additionally implement validation
/// themselves via the `MdocCertificateProfile` interface on the platform SDKs.
/// That is not offered here: the underlying interface is synchronous and
/// Pigeon's value-returning calls are not, so a Dart implementation would have
/// to block a native thread on every certificate validated.
sealed class MdlIssuerCertificateProfile {}

/// Validate document signers of this doctype under a profile the SDK ships.
class MdlIssuerBuiltinProfile implements MdlIssuerCertificateProfile {
  MdlBuiltinCertificateProfile profile;

  MdlIssuerBuiltinProfile({required this.profile});
}

/// Validate document signers of this doctype under the Annex B checks with
/// caller-supplied parameters.
class MdlIssuerConfiguredProfile implements MdlIssuerCertificateProfile {
  MdlIssuerProfileConfig config;

  MdlIssuerConfiguredProfile({required this.config});
}

/// Rules for reader certificates of one doctype.
///
/// A reader session never exercises this half -- it applies when a *holder*
/// authenticates an incoming request -- so any value will do.
sealed class MdlReaderCertificateProfile {}

/// Validate reader certificates of this doctype under a profile the SDK ships.
class MdlReaderBuiltinProfile implements MdlReaderCertificateProfile {
  MdlBuiltinCertificateProfile profile;

  MdlReaderBuiltinProfile({required this.profile});
}

/// Validate reader certificates of this doctype under the Annex B checks with
/// caller-supplied parameters.
class MdlReaderConfiguredProfile implements MdlReaderCertificateProfile {
  MdlReaderProfileConfig config;

  MdlReaderConfiguredProfile({required this.config});
}

/// The certificate rules for one doctype, both directions.
class MdlCertificateProfiles {
  /// Rules for the document signer certificate that signed a presented credential.
  MdlIssuerCertificateProfile issuer;

  /// Rules for the certificate a reader authenticates its request with.
  MdlReaderCertificateProfile reader;

  MdlCertificateProfiles({required this.issuer, required this.reader});
}

/// Callback interface for reader state updates.
///
/// All callbacks are dispatched on the main thread (Android: main looper,
/// iOS: main dispatch queue).
@FlutterApi()
abstract class MdlReaderCallback {
  /// Called whenever the reader transitions to a new state. The
  /// [MdlReaderStateUpdate.response] field is populated when the new state is
  /// [MdlReaderState.success]; [MdlReaderStateUpdate.error] is populated when
  /// the new state is [MdlReaderState.error] or a terminal NFC error state.
  void onStateChange(MdlReaderStateUpdate update);
}

/// ISO 18013-5 mDL Reader (verifier) API.
///
/// Drives the reader side of an in-person mDL verification flow. Two
/// engagement paths are supported:
///
///  - **NFC**: The reader device arms NFC reader mode and waits for the
///    holder to tap. The SDK runs the APDU handover, extracts the BLE
///    connection info, then establishes the BLE session and exchanges the
///    request/response. Use [startNfcReader].
///
///  - **QR**: The consumer scans the holder's QR code (e.g. via this
///    plugin's [Scanner] view) and passes the resulting URI to
///    [startQrReader]. The SDK skips the NFC step and goes directly to BLE.
///
/// Both paths converge on the same BLE pipeline and produce
/// [MdlReadResponse] via [MdlReaderCallback.onStateChange].
///
/// A consumer typically holds exactly one [MdlReader] instance per
/// verification session; call [cancel] to tear it down.
@HostApi()
abstract class MdlReader {
  /// Whether NFC hardware is present.
  ///
  /// - Android: `NfcAdapter.getDefaultAdapter(...) != null`.
  /// - iOS: `NFCTagReaderSession.readingAvailable`.
  ///
  /// Note: this does NOT check whether NFC is currently enabled by the user.
  /// If `false` is returned the host should hide / disable the NFC option;
  /// if `true` is returned the host can call [startNfcReader] but should
  /// also be ready to handle [MdlReaderState.nfcDisabled] (NFC switched off).
  bool isNfcSupported();

  /// Start an NFC-engagement reader session.
  ///
  /// Subsequent state transitions are reported via
  /// [MdlReaderCallback.onStateChange]. Any in-flight session is implicitly
  /// cancelled before the new one starts.
  ///
  /// @param query Requested items, shaped as namespace → element name →
  ///   `intentToRetain`. For example:
  ///   ```
  ///   {
  ///     "org.iso.18013.5.1": { "given_name": false, "portrait": false },
  ///     "org.iso.18013.5.1.aamva": { "EDL_credential": false },
  ///   }
  ///   ```
  /// @param docType The document type to request, e.g. `"org.iso.18013.5.1.mDL"`
  ///   or `"org.iso.23220.photoid.1"`. Required, and not inferred from [query]:
  ///   a request naming the wrong doctype is answered with nothing rather than
  ///   an error, so the caller must say what it is asking for.
  /// @param trustedRoots List of PEM-encoded IACA root certificates. Empty
  ///   list disables chain validation; [MdlAuthenticationStatus.invalid]
  ///   (or [unchecked]) will be returned in that case.
  /// @param certificateProfiles Certificate validation rules, keyed by doctype.
  ///   Null validates every doctype under the ISO/IEC 18013-5 mDL profile,
  ///   which is what every caller got before this was configurable. When
  ///   supplied it must contain an entry for [docType]: a doctype absent from
  ///   the map is refused rather than validated under a guess.
  void startNfcReader(
    Map<String, Map<String, bool>> query,
    String docType,
    List<String> trustedRoots,
    Map<String, MdlCertificateProfiles>? certificateProfiles,
  );

  /// Start a QR-engagement reader session from a pre-scanned QR code URI.
  ///
  /// The URI typically starts with `mdoc:` (per ISO 18013-5 §8.2.2.3) and
  /// encodes the holder's device engagement + BLE connection info.
  ///
  /// Same callback contract as [startNfcReader].
  ///
  /// @param qrUri The full QR code payload string scanned from the holder's
  ///   device.
  /// @param query See [startNfcReader].
  /// @param docType See [startNfcReader].
  /// @param trustedRoots See [startNfcReader].
  /// @param certificateProfiles See [startNfcReader].
  void startQrReader(
    String qrUri,
    Map<String, Map<String, bool>> query,
    String docType,
    List<String> trustedRoots,
    Map<String, MdlCertificateProfiles>? certificateProfiles,
  );

  /// Cancel any in-flight session and tear down NFC / BLE handles.
  ///
  /// Idempotent. After [cancel] the reader transitions to
  /// [MdlReaderState.uninitialized]; the host may start a new session.
  void cancel();
}
