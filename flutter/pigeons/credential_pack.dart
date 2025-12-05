import 'package:pigeon/pigeon.dart';

@ConfigurePigeon(
  PigeonOptions(
    dartOut: 'lib/pigeon/credential_pack.g.dart',
    dartOptions: DartOptions(),
    kotlinOut:
        'android/src/main/kotlin/com/spruceid/sprucekit_mobile/CredentialPack.g.kt',
    kotlinOptions: KotlinOptions(
      errorClassName: 'CredentialPackFlutterError',
      package: 'com.spruceid.sprucekit_mobile',
    ),
    swiftOut: 'ios/Classes/CredentialPack.g.swift',
    swiftOptions: SwiftOptions(errorClassName: 'CredentialPackPigeonError'),
    dartPackageName: 'sprucekit_mobile',
  ),
)

/// Credential status from status list
enum CredentialStatus {
  valid,
  revoked,
  suspended,
  unknown,
  invalid,
  undefined,
  pending,
  ready,
}

/// Credential format type
enum CredentialFormat {
  jwtVc,
  jsonVc,
  sdJwt,
  msoMdoc,
  cwt,
}

/// A parsed credential with its metadata
class ParsedCredentialData {
  /// Unique identifier for this credential
  String id;

  /// The credential format
  CredentialFormat format;

  /// Raw credential data as string
  String rawCredential;

  ParsedCredentialData({
    required this.id,
    required this.format,
    required this.rawCredential,
  });
}

/// A credential pack with its credentials
class CredentialPackData {
  /// Unique identifier for this pack
  String id;

  /// List of credentials in this pack
  List<ParsedCredentialData> credentials;

  CredentialPackData({required this.id, required this.credentials});
}

/// Result types for credential operations
sealed class CredentialOperationResult {}

/// Operation succeeded
class CredentialOperationSuccess implements CredentialOperationResult {
  int? unused;
}

/// Operation failed with error message
class CredentialOperationError implements CredentialOperationResult {
  String message;

  CredentialOperationError({required this.message});
}

/// Result of adding a credential
sealed class AddCredentialResult {}

/// Adding credential succeeded
class AddCredentialSuccess implements AddCredentialResult {
  /// The updated list of credentials in the pack
  List<ParsedCredentialData> credentials;

  AddCredentialSuccess({required this.credentials});
}

/// Adding credential failed
class AddCredentialError implements AddCredentialResult {
  String message;

  AddCredentialError({required this.message});
}

/// CredentialPack management API
///
/// Manages collections of credentials with parsing and storage capabilities
@HostApi()
abstract class CredentialPack {
  /// Create a new empty credential pack
  ///
  /// @return The pack ID
  String createPack();

  /// Get a credential pack by ID
  ///
  /// @param packId The pack identifier
  /// @return The credential pack data, or null if not found
  CredentialPackData? getPack(String packId);

  /// Add a raw credential to a pack (auto-detects format)
  ///
  /// Tries to parse the credential as: JWT VC, JSON VC, SD-JWT, or CWT
  ///
  /// @param packId The pack identifier
  /// @param rawCredential The raw credential string
  /// @return AddCredentialResult with updated credentials or error
  @async
  AddCredentialResult addRawCredential(String packId, String rawCredential);

  /// Add a raw mDoc credential to a pack
  ///
  /// @param packId The pack identifier
  /// @param rawCredential The raw mDoc credential
  /// @param keyAlias The key alias to use for the mDoc
  /// @return AddCredentialResult with updated credentials or error
  @async
  AddCredentialResult addRawMdoc(
    String packId,
    String rawCredential,
    String keyAlias,
  );

  /// Add a credential in any supported format
  ///
  /// Tries standard formats first, then mDoc with the provided key alias
  ///
  /// @param packId The pack identifier
  /// @param rawCredential The raw credential string
  /// @param mdocKeyAlias The key alias to use if parsing as mDoc
  /// @return AddCredentialResult with updated credentials or error
  @async
  AddCredentialResult addAnyFormat(
    String packId,
    String rawCredential,
    String mdocKeyAlias,
  );

  /// Get all credentials in a pack
  ///
  /// @param packId The pack identifier
  /// @return List of credentials, empty if pack not found
  List<ParsedCredentialData> listCredentials(String packId);

  /// Get credential claims
  ///
  /// @param packId The pack identifier
  /// @param credentialId The credential identifier
  /// @param claimNames Optional list of claim names to filter (empty = all claims)
  /// @return JSON string of claims, or null if not found
  String? getCredentialClaims(
    String packId,
    String credentialId,
    List<String> claimNames,
  );

  /// Delete a credential pack
  ///
  /// @param packId The pack identifier
  /// @return CredentialOperationResult indicating success or error
  @async
  CredentialOperationResult deletePack(String packId);

  /// Get all credential pack IDs
  ///
  /// @return List of pack IDs
  List<String> listPacks();
}
