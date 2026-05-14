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
enum CredentialFormat { jwtVc, jsonVc, sdJwt, dcSdJwt, msoMdoc, cwt, opticalBarcode }

/// A parsed credential with its metadata
class ParsedCredentialData {
  /// Unique identifier for this credential
  String id;

  /// The credential format
  CredentialFormat format;

  /// Raw credential data as string
  String rawCredential;

  /// `MsoMdoc.doctype()` for mdoc credentials. Null otherwise.
  /// E.g. `"org.iso.18013.5.1.mDL"`, `"org.iso.23220.photoID.1"`.
  String? doctype;

  /// `DcSdJwt.vct()` for IETF SD-JWT VC credentials. Null otherwise.
  /// E.g. `"eu.europa.ec.eudi.pid.1"`.
  String? vct;

  ParsedCredentialData({
    required this.id,
    required this.format,
    required this.rawCredential,
    this.doctype,
    this.vct,
  });
}

/// Stateless preview of a parsed credential, used to render claims BEFORE
/// the user has agreed to persist the credential into their wallet.
///
/// Unlike [ParsedCredentialData], this preview is not bound to a stored
/// credential pack and carries the full claims map inline so callers can
/// render it without a follow-up `getCredentialClaims` lookup.
class ParsedCredentialPreview {
  /// The credential format.
  CredentialFormat format;

  /// `MsoMdoc.doctype()` for mdoc credentials. Null otherwise.
  String? doctype;

  /// `DcSdJwt.vct()` for IETF SD-JWT VC credentials. Null otherwise.
  String? vct;

  /// JSON-encoded string of the credential claims. For mdoc, keys preserve
  /// the namespace path (e.g. `"org.iso.18013.5.1.given_name"`). Mirrors the
  /// shape returned by `getCredentialClaims` so callers can decode it the
  /// same way.
  String claimsJson;

  ParsedCredentialPreview({
    required this.format,
    required this.doctype,
    required this.vct,
    required this.claimsJson,
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

  /// Parse a raw credential payload into a stateless preview, without
  /// persisting it. Used by issuance flows to render claims before the user
  /// agrees to add the credential to their wallet.
  ///
  /// @param rawCredential The raw credential payload (compact JWS for JWT-VC
  ///   / SD-JWT, JSON for ldp_vc, base64url-encoded CBOR for mdoc).
  /// @param format The credential format.
  /// @return A preview containing the parsed claims. The native side passes
  ///   a throwaway key alias internally; the credential is never bound to
  ///   any device key during parsing.
  @async
  ParsedCredentialPreview parseRawCredential(
    String rawCredential,
    CredentialFormat format,
  );

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
  /// @param appGroupId Optional App Group ID (iOS) for persistent storage
  /// @param userHash Optional user hash for per-user isolation (null = device-global key)
  /// @return CredentialOperationResult indicating success or error
  @async
  CredentialOperationResult deletePack(
    String packId,
    String? appGroupId,
    String? userHash,
  );

  /// Get all credential pack IDs
  ///
  /// @return List of pack IDs (in-memory only)
  List<String> listPacks();

  /// Save a credential pack to persistent storage
  ///
  /// On iOS: Uses StorageManager with App Group for shared storage
  /// On Android: Uses StorageManager with app-private storage
  ///
  /// @param packId The pack identifier
  /// @param appGroupId Optional App Group ID (iOS only) for shared storage with extensions
  /// @param userHash Optional user hash for per-user isolation (null = device-global key)
  /// @return CredentialOperationResult indicating success or error
  @async
  CredentialOperationResult savePack(
    String packId,
    String? appGroupId,
    String? userHash,
  );

  /// Load all credential packs from persistent storage
  ///
  /// On iOS: Uses StorageManager with App Group for shared storage
  /// On Android: Uses StorageManager with app-private storage
  ///
  /// @param appGroupId Optional App Group ID (iOS only) for shared storage with extensions
  /// @param userHash Optional user hash — when non-null, enumerates only this user's packs
  /// @return List of loaded pack IDs
  @async
  List<String> loadAllPacks(String? appGroupId, String? userHash);

  /// Load a single credential pack from persistent storage by ID.
  /// Unlike loadAllPacks(), this loads only the specified pack into memory.
  ///
  /// @param packId The pack identifier (UUID string)
  /// @param appGroupId Optional App Group ID (iOS only) for shared storage
  /// @param userHash Optional user hash for per-user isolation
  /// @return CredentialOperationResult indicating success or error
  @async
  CredentialOperationResult loadPack(
    String packId,
    String? appGroupId,
    String? userHash,
  );
}
