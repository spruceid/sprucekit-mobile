import 'package:pigeon/pigeon.dart';

@ConfigurePigeon(
  PigeonOptions(
    dartOut: 'lib/pigeon/oid4vp_mdoc.g.dart',
    dartOptions: DartOptions(),
    kotlinOut:
        'android/src/main/kotlin/com/spruceid/sprucekit_mobile/Oid4vpMdoc.g.kt',
    kotlinOptions: KotlinOptions(
      errorClassName: 'Oid4vpMdocFlutterError',
      package: 'com.spruceid.sprucekit_mobile',
    ),
    swiftOut: 'ios/Classes/Oid4vpMdoc.g.swift',
    swiftOptions: SwiftOptions(errorClassName: 'Oid4vpMdocPigeonError'),
    dartPackageName: 'sprucekit_mobile',
  ),
)
/// A field requested by the verifier for mDoc presentation
class RequestedField180137Data {
  /// Field identifier (namespace + element name)
  String id;

  /// Human-readable display name
  String displayableName;

  /// Current value of the field (if available)
  String? displayableValue;

  /// Whether this field can be selectively disclosed
  bool selectivelyDisclosable;

  /// Whether the verifier intends to retain this data
  bool intentToRetain;

  /// Whether this field is required by the verifier
  bool required;

  /// Purpose description for requesting this field
  String? purpose;

  RequestedField180137Data({
    required this.id,
    required this.displayableName,
    this.displayableValue,
    required this.selectivelyDisclosable,
    required this.intentToRetain,
    required this.required,
    this.purpose,
  });
}

/// A matching credential for the OID4VP request
class RequestMatch180137Data {
  /// Index in the matches list
  int index;

  /// Credential ID (UUID as string)
  String credentialId;

  /// Fields requested for this credential
  List<RequestedField180137Data> requestedFields;

  RequestMatch180137Data({
    required this.index,
    required this.credentialId,
    required this.requestedFields,
  });
}

/// Information about the OID4VP request
class Oid4vpMdocRequestInfo {
  /// Name of the verifier requesting the credential
  String? requestedBy;

  /// List of matching credentials
  List<RequestMatch180137Data> matches;

  Oid4vpMdocRequestInfo({this.requestedBy, required this.matches});
}

/// Result of OID4VP mDoc operations
sealed class Oid4vpMdocResult {}

/// Operation succeeded
class Oid4vpMdocSuccess implements Oid4vpMdocResult {
  String? message;

  /// Redirect URL (if any) returned after successful presentation
  String? redirectUrl;

  Oid4vpMdocSuccess({this.message, this.redirectUrl});
}

/// Operation failed with error
class Oid4vpMdocError implements Oid4vpMdocResult {
  String message;

  Oid4vpMdocError({required this.message});
}

/// Result of processing an authorization request
sealed class ProcessRequestResult {}

/// Request processed successfully
class ProcessRequestSuccess implements ProcessRequestResult {
  Oid4vpMdocRequestInfo info;

  ProcessRequestSuccess({required this.info});
}

/// Request processing failed
class ProcessRequestError implements ProcessRequestResult {
  String message;

  ProcessRequestError({required this.message});
}

/// OID4VP mDoc presentation API (ISO 18013-7)
///
/// Handles OpenID for Verifiable Presentation with mDoc credentials
/// using the mdoc-openid4vp:// URI scheme
@HostApi()
abstract class Oid4vpMdoc {
  /// Initialize the handler with mDoc credentials from credential packs
  ///
  /// @param credentialPackIds List of credential pack IDs containing mDocs
  /// @return Result indicating success or error
  @async
  Oid4vpMdocResult initialize(List<String> credentialPackIds);

  /// Process an authorization request URL
  ///
  /// @param url The authorization request URL (mdoc-openid4vp://...)
  /// @return ProcessRequestResult with matching credentials on success
  @async
  ProcessRequestResult processRequest(String url);

  /// Submit the presentation response
  ///
  /// @param matchIndex Index of the selected credential match
  /// @param approvedFieldIds List of approved field IDs to share
  /// @return Result with redirect URL on success
  @async
  Oid4vpMdocResult submitResponse(
    int matchIndex,
    List<String> approvedFieldIds,
  );

  /// Cancel and cleanup the current session
  void cancel();
}
