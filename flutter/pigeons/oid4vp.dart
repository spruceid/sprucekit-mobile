import 'package:pigeon/pigeon.dart';

@ConfigurePigeon(
  PigeonOptions(
    dartOut: 'lib/pigeon/oid4vp.g.dart',
    dartOptions: DartOptions(),
    kotlinOut:
        'android/src/main/kotlin/com/spruceid/sprucekit_mobile/Oid4vp.g.kt',
    kotlinOptions: KotlinOptions(
      errorClassName: 'Oid4vpFlutterError',
      package: 'com.spruceid.sprucekit_mobile',
    ),
    swiftOut: 'ios/Classes/Oid4vp.g.swift',
    swiftOptions: SwiftOptions(errorClassName: 'Oid4vpPigeonError'),
    dartPackageName: 'sprucekit_mobile',
  ),
)
/// Options for creating the permission response
class ResponseOptions {
  bool forceArraySerialization;

  ResponseOptions({required this.forceArraySerialization});
}

/// A field requested by the verifier
class RequestedFieldData {
  String id;
  String? name;
  String path;
  bool required;
  bool retained;
  String? purpose;
  String credentialQueryId;
  List<String> rawFields;

  RequestedFieldData({
    required this.id,
    this.name,
    required this.path,
    required this.required,
    required this.retained,
    this.purpose,
    required this.credentialQueryId,
    required this.rawFields,
  });
}

/// Stable identifier for a presentable credential within a single
/// `handleAuthorizationRequest` session.
///
/// A credential is uniquely identified by the pair
/// `(credentialId, credentialQueryId)`: the same credential may appear
/// once per DCQL credential query that it satisfies. Use this key
/// (not list position) when calling `getRequestedFields` and `submitResponse`.
class PresentableCredentialKey {
  String credentialId;
  String credentialQueryId;

  PresentableCredentialKey({
    required this.credentialId,
    required this.credentialQueryId,
  });
}

/// A credential that can be presented.
///
/// `credentialId` and `credentialQueryId` together form the stable key
/// used to refer back to this credential in subsequent calls
/// (`getRequestedFields`, `submitResponse`). See `PresentableCredentialKey`.
class PresentableCredentialData {
  String credentialId;
  String credentialQueryId;
  bool selectiveDisclosable;

  PresentableCredentialData({
    required this.credentialId,
    required this.credentialQueryId,
    required this.selectiveDisclosable,
  });
}

/// Information about the permission request
class PermissionRequestInfo {
  String? clientId;
  String? domain;
  String? purpose;
  bool isMultiCredentialSelection;
  bool isMultiCredentialMatching;

  PermissionRequestInfo({
    this.clientId,
    this.domain,
    this.purpose,
    required this.isMultiCredentialSelection,
    required this.isMultiCredentialMatching,
  });
}

/// Result of OID4VP operations
sealed class Oid4vpResult {}

/// Operation succeeded
class Oid4vpSuccess implements Oid4vpResult {
  String? message;

  Oid4vpSuccess({this.message});
}

/// Operation failed with error
class Oid4vpError implements Oid4vpResult {
  String message;

  Oid4vpError({required this.message});
}

/// Result of handling an authorization request
sealed class HandleAuthRequestResult {}

/// Authorization request handled successfully
class HandleAuthRequestSuccess implements HandleAuthRequestResult {
  List<PresentableCredentialData> credentials;
  PermissionRequestInfo info;

  HandleAuthRequestSuccess({required this.credentials, required this.info});
}

/// Authorization request failed
class HandleAuthRequestError implements HandleAuthRequestResult {
  String message;

  HandleAuthRequestError({required this.message});
}

/// A group of credentials matching a credential query
class CredentialQueryGroupData {
  String credentialQueryId;
  List<PresentableCredentialData> credentials;

  CredentialQueryGroupData({
    required this.credentialQueryId,
    required this.credentials,
  });
}

/// A credential requirement from the verifier
class CredentialRequirementData {
  String displayName;
  bool required;
  List<String> credentialQueryIds;
  List<PresentableCredentialData> credentials;

  CredentialRequirementData({
    required this.displayName,
    required this.required,
    required this.credentialQueryIds,
    required this.credentials,
  });
}

/// OID4VP credential presentation API
///
/// Handles the OpenID for Verifiable Presentation flow
@HostApi()
abstract class Oid4vp {
  /// Create a holder with credentials for presentation
  ///
  /// @param credentialPackIds List of credential pack IDs to use
  /// @param trustedDids List of trusted DIDs for verification
  /// @param keyId The key identifier for signing (will create if doesn't exist)
  /// @param contextMap Optional JSON-LD context map
  @async
  Oid4vpResult createHolder(
    List<String> credentialPackIds,
    List<String> trustedDids,
    String keyId,
    Map<String, String>? contextMap,
  );

  /// Handle an authorization request URL
  ///
  /// @param url The authorization request URL (e.g., "openid4vp://...")
  /// @return HandleAuthRequestResult with matching credentials on success
  @async
  HandleAuthRequestResult handleAuthorizationRequest(String url);

  /// Get requested fields for a credential
  ///
  /// @param key Stable key identifying the credential. Take the
  ///   `credentialId` and `credentialQueryId` from a `PresentableCredentialData`
  ///   returned by `handleAuthorizationRequest`, `getCredentialRequirements`,
  ///   or `getCredentialsGroupedByQuery`.
  /// @return List of requested fields for the credential
  List<RequestedFieldData> getRequestedFields(PresentableCredentialKey key);

  /// Submit the presentation response
  ///
  /// @param selectedCredentials Stable keys of the credentials the user selected
  /// @param selectedFieldPaths List of selected field paths per credential, in
  ///   the same order as `selectedCredentials`
  /// @param options Response configuration options
  @async
  Oid4vpResult submitResponse(
    List<PresentableCredentialKey> selectedCredentials,
    List<List<String>> selectedFieldPaths,
    ResponseOptions options,
  );

  /// Get credential requirements from the permission request
  ///
  /// @return List of credential requirements
  List<CredentialRequirementData> getCredentialRequirements();

  /// Get credentials grouped by credential query
  ///
  /// @return List of credential query groups
  List<CredentialQueryGroupData> getCredentialsGroupedByQuery();

  /// Get credential query IDs from the permission request
  ///
  /// @return List of credential query ID strings
  List<String> getCredentialQueryIds();

  /// Cancel and cleanup the current session
  void cancel();
}
