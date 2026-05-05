import 'package:pigeon/pigeon.dart';

@ConfigurePigeon(
  PigeonOptions(
    dartOut: 'lib/pigeon/oid4vci.g.dart',
    dartOptions: DartOptions(),
    kotlinOut:
        'android/src/main/kotlin/com/spruceid/sprucekit_mobile/Oid4vci.g.kt',
    kotlinOptions: KotlinOptions(
      errorClassName: 'Oid4vciFlutterError',
      package: 'com.spruceid.sprucekit_mobile',
    ),
    swiftOut: 'ios/Classes/Oid4vci.g.swift',
    swiftOptions: SwiftOptions(errorClassName: 'Oid4vciPigeonError'),
    dartPackageName: 'sprucekit_mobile',
  ),
)
/// DID method for proof of possession
enum DidMethod { jwk, key }

/// Options for credential exchange
class Oid4vciExchangeOptions {
  /// Whether to verify the credential after exchange
  bool verifyAfterExchange;

  Oid4vciExchangeOptions({required this.verifyAfterExchange});
}

/// Credential received from issuance
class IssuedCredential {
  /// The credential payload as string (JSON, JWT, etc.)
  String payload;

  /// The credential format (e.g., "jwt_vc_json", "ldp_vc", "mso_mdoc")
  String format;

  IssuedCredential({required this.payload, required this.format});
}

/// Grant type discriminator for an OID4VCI credential offer.
///
/// Mirrors the Rust `GrantType` enum on `ResolvedCredentialOffer`.
enum GrantType {
  /// Pre-authorized code grant with no transaction code (PIN). No user
  /// authorization required beyond the wallet's confirmation.
  preAuthCodeNoTxCode,

  /// Pre-authorized code grant requiring a transaction code (PIN) provided
  /// out-of-band by the issuer.
  preAuthCodeWithTxCode,

  /// Authorization-code grant requiring the user to sign in via browser.
  authorizationCode,
}

/// Pre-issuance metadata about an OID4VCI offer.
///
/// Returned by `parseOffer`. Contains everything the wallet needs to render
/// an issuer-details screen before requesting any user authorization.
class ParsedOfferMetadata {
  /// The issuer's identifier URL (`credential_issuer` field in OID4VCI metadata).
  String issuerId;

  /// Optional human-readable display name of the issuer, taken from the
  /// `display[0].name` field in the issuer metadata. May be null when the
  /// issuer does not provide a display name.
  String? issuerDisplayName;

  /// The credential configuration IDs included in this offer. Multi-credential
  /// offers will contain more than one entry.
  List<String> credentialConfigurationIds;

  /// The grant type required to complete the issuance.
  GrantType grantType;

  ParsedOfferMetadata({
    required this.issuerId,
    required this.issuerDisplayName,
    required this.credentialConfigurationIds,
    required this.grantType,
  });
}

/// Result of OID4VCI issuance
sealed class Oid4vciResult {}

/// Issuance succeeded with credentials
class Oid4vciSuccess implements Oid4vciResult {
  List<IssuedCredential> credentials;

  Oid4vciSuccess({required this.credentials});
}

/// Issuance failed with error
class Oid4vciError implements Oid4vciResult {
  String message;

  Oid4vciError({required this.message});
}

/// OID4VCI credential issuance API
///
/// Handles the OpenID for Verifiable Credential Issuance flow
@HostApi()
abstract class Oid4vci {
  /// Resolve an OID4VCI credential offer URL and return pre-issuance metadata.
  ///
  /// Performs the issuer-metadata HTTP fetch but does NOT initiate token
  /// exchange or credential request. Safe to call before any user authorization.
  ///
  /// @param credentialOffer The full credential offer URL.
  /// @return Pre-issuance metadata describing the issuer and the grant type
  ///   the user will need to complete.
  /// @throws when the URL is unparseable or the issuer metadata fetch fails.
  @async
  ParsedOfferMetadata parseOffer(String credentialOffer);

  /// Run the complete OID4VCI issuance flow
  ///
  /// This method handles:
  /// 1. Initiating session with the credential offer
  /// 2. Token exchange
  /// 3. Proof of possession generation
  /// 4. Credential exchange
  ///
  /// @param credentialOffer The full credential offer URL (e.g., "openid-credential-offer://...")
  /// @param clientId The client ID for the wallet
  /// @param redirectUrl The redirect URL for the wallet
  /// @param keyId The key identifier for signing (will create if doesn't exist)
  /// @param didMethod The DID method for proof of possession
  /// @param contextMap Optional JSON-LD context map for credential parsing
  /// @return Oid4vciResult with credentials on success or error message on failure
  @async
  Oid4vciResult runIssuance(
    String credentialOffer,
    String clientId,
    String redirectUrl,
    String keyId,
    DidMethod didMethod,
    Map<String, String>? contextMap,
  );
}
