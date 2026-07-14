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

  /// JSON-encoded `Map<String, List<String>>` of per-category errors, or null
  /// when no errors. Categories include `issuer_authentication_errors`,
  /// `device_authentication_errors`, `certificate_errors`, `parsing_errors`.
  /// CRL `revocation_errors` are surfaced here as well (non-fatal).
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
  ///   The doctype (e.g. `"org.iso.18013.5.1.mDL"`) is derived from the
  ///   namespaces by the SDK; it is not passed separately.
  /// @param trustedRoots List of PEM-encoded IACA root certificates. Empty
  ///   list disables chain validation; [MdlAuthenticationStatus.invalid]
  ///   (or [unchecked]) will be returned in that case.
  void startNfcReader(
    Map<String, Map<String, bool>> query,
    List<String> trustedRoots,
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
  /// @param trustedRoots See [startNfcReader].
  void startQrReader(
    String qrUri,
    Map<String, Map<String, bool>> query,
    List<String> trustedRoots,
  );

  /// Cancel any in-flight session and tear down NFC / BLE handles.
  ///
  /// Idempotent. After [cancel] the reader transitions to
  /// [MdlReaderState.uninitialized]; the host may start a new session.
  void cancel();
}
