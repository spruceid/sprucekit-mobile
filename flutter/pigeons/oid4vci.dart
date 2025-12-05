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
enum DidMethod {
  jwk,
  key,
}

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
