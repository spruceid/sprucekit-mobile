import 'package:pigeon/pigeon.dart';

@ConfigurePigeon(
  PigeonOptions(
    dartOut: 'lib/pigeon/dc_api.g.dart',
    dartOptions: DartOptions(),
    kotlinOut:
        'android/src/main/kotlin/com/spruceid/sprucekit_mobile/DcApi.g.kt',
    kotlinOptions: KotlinOptions(
      errorClassName: 'DcApiFlutterError',
      package: 'com.spruceid.sprucekit_mobile',
    ),
    swiftOut: 'ios/Classes/DcApi.g.swift',
    swiftOptions: SwiftOptions(errorClassName: 'DcApiPigeonError'),
    dartPackageName: 'sprucekit_mobile',
  ),
)
/// Result of DC API operations
sealed class DcApiResult {}

/// Operation succeeded
class DcApiSuccess implements DcApiResult {
  String? message;

  DcApiSuccess({this.message});
}

/// Operation failed with error
class DcApiError implements DcApiResult {
  String message;

  DcApiError({required this.message});
}

/// Information about a registered credential
class RegisteredCredentialInfo {
  /// Credential ID
  String credentialId;

  /// Document type (e.g., "org.iso.18013.5.1.mDL")
  String docType;

  /// Whether the credential is currently registered with the system
  bool isRegistered;

  RegisteredCredentialInfo({
    required this.credentialId,
    required this.docType,
    required this.isRegistered,
  });
}

/// Digital Credentials API
///
/// Provides functionality for integrating with platform-specific Digital
/// Credentials APIs (iOS IdentityDocumentProvider, Android CredentialManager).
///
/// This API handles:
/// - Syncing credentials to shared storage (App Groups) for extension access
/// - Registering/unregistering mDoc credentials with the platform
///
/// Note: The actual DC API request handling is done by a native App Extension
/// (iOS) or Activity (Android) that reads credentials from shared storage.
@HostApi()
abstract class DcApi {
  /// Sync credentials to App Group shared storage
  ///
  /// This makes credentials accessible to the DC API Extension which runs
  /// in a separate process and cannot access the main app's storage.
  ///
  /// On iOS: Writes encrypted credential data to the App Group container
  /// On Android: Not needed (CredentialManager handles this differently)
  ///
  /// @param appGroupId The App Group identifier (e.g., "group.com.example.app")
  /// @param packIds List of credential pack IDs to sync
  /// @return DcApiResult indicating success or error
  @async
  DcApiResult syncCredentialsToAppGroup(
    String appGroupId,
    List<String> packIds,
  );

  /// Register mDoc credentials with the platform's identity provider
  ///
  /// On iOS 26+: Registers with IdentityDocumentProviderRegistrationStore
  /// On Android: Registers with CredentialManager registry
  ///
  /// This makes the credentials appear in the system's credential picker
  /// when a website requests credentials via the DC API.
  ///
  /// @param packIds List of credential pack IDs containing mDocs to register
  /// @param walletName Optional name to display for the wallet in credential selection UI
  /// @return DcApiResult indicating success or error
  @async
  DcApiResult registerCredentials(List<String> packIds, String? walletName);

  /// Unregister credentials from the platform's identity provider
  ///
  /// Removes the credentials from the system's credential picker.
  ///
  /// @param credentialIds List of credential IDs to unregister
  /// @return DcApiResult indicating success or error
  @async
  DcApiResult unregisterCredentials(List<String> credentialIds);

  /// Get information about registered credentials
  ///
  /// @return List of registered credential info
  List<RegisteredCredentialInfo> getRegisteredCredentials();

  /// Check if DC API is supported on this platform/version
  ///
  /// @return true if DC API is available (iOS 26+ or Android 14+)
  bool isSupported();
}
