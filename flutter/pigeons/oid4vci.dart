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

/// An OID4VCI protocol version the compatibility facade is willing to use.
///
/// - [v1] OID4VCI 1.0 (final).
/// - [legacy] The legacy draft-13 request shape.
///
/// Passed as a set of supported versions. An empty list means auto: probe
/// both versions and prefer v1, transparently falling back to legacy if the
/// issuer rejects the v1 request. `[v1, legacy]` is equivalent to auto.
enum Oid4vciVersion { v1, legacy }

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

/// Input character set for the transaction code.
///
/// Mirrors the upstream `oid4vci-rs` `InputMode` enum. The OID4VCI spec
/// defines `numeric` as the default when the issuer omits the field.
enum TxCodeInputMode { numeric, text }

/// Metadata describing the issuer's transaction code requirements.
///
/// All fields are optional per OID4VCI §4.1.1. `tx_code: {}` (an empty
/// object) is a valid signal that PIN is required but with no hints.
class TxCodeMetadata {
  /// Input character set. `null` ⇒ wallet treats as `numeric` (spec default).
  TxCodeInputMode? inputMode;

  /// Expected code length. `null` ⇒ no length hint (free textfield).
  /// `<= 0` ⇒ misconfigured issuer; wallet skips the PIN input and sends "".
  int? length;

  /// Optional guidance string from the issuer (max 300 chars per spec).
  /// Displayed below the localized subtitle when present.
  String? description;

  TxCodeMetadata({this.inputMode, this.length, this.description});
}

/// Opaque handle to a server-side OID4VCI session.
///
/// Created by `acceptOffer`. The `sessionId` is the key into the Kotlin/Swift
/// registry that holds the underlying Rust `Arc<...>` state handle. Callers
/// must call `releaseSession` if the flow is abandoned before terminal.
class OfferSession {
  String sessionId;

  /// Pre-issuance metadata for the resolved offer. Returned here (rather than
  /// requiring a separate `parseOffer` call) because `acceptOffer` performs
  /// its own resolve as part of the token exchange. Use this value, not a
  /// cached `parseOffer` result, when constructing the UI for the session.
  ParsedOfferMetadata metadata;

  OfferSession({required this.sessionId, required this.metadata});
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

  /// Populated only when `grantType == preAuthCodeWithTxCode`.
  TxCodeMetadata? txCode;

  ParsedOfferMetadata({
    required this.issuerId,
    required this.issuerDisplayName,
    required this.credentialConfigurationIds,
    required this.grantType,
    this.txCode,
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
  /// @param supportedVersions The OID4VCI versions to resolve against. An empty
  ///   list means auto (probe both, prefer v1 with legacy fallback).
  /// @return Pre-issuance metadata describing the issuer and the grant type
  ///   the user will need to complete.
  /// @throws when the URL is unparseable or the issuer metadata fetch fails.
  @async
  ParsedOfferMetadata parseOffer(
    String credentialOffer,
    List<Oid4vciVersion> supportedVersions,
  );

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
  /// @param supportedVersions The OID4VCI versions to resolve against. An empty
  ///   list means auto (probe both, prefer v1 with legacy fallback).
  /// @return Oid4vciResult with credentials on success or error message on failure
  @async
  Oid4vciResult runIssuance(
    String credentialOffer,
    String clientId,
    String redirectUrl,
    String keyId,
    DidMethod didMethod,
    Map<String, String>? contextMap,
    List<Oid4vciVersion> supportedVersions,
  );

  /// Resolve the offer URL and complete the token-endpoint exchange.
  ///
  /// Performs a full `resolveOfferUrl` (which re-fetches issuer metadata)
  /// followed by the token endpoint call, returning the session in its
  /// post-token state (e.g., `RequiresTxCode` for tx_code grants).
  ///
  /// The caller does NOT need to call `parseOffer` separately — this call
  /// performs its own resolve. The embedded `OfferSession.metadata` reflects
  /// the resolve performed here.
  ///
  /// Returns a stateful session handle keyed into the platform-side registry.
  /// Callers must call `releaseSession` if the flow is abandoned before
  /// terminal.
  ///
  /// The `redirectUrl` is stored on the session and consumed by
  /// `buildAuthorizationUrl` when the grant is `authorizationCode`. Pass null
  /// for grants that do not require a browser sign-in (pre-auth ± tx_code);
  /// if a later `buildAuthorizationUrl` is invoked on a session that was
  /// created with a null redirect, it returns null.
  ///
  /// @throws when the offer cannot be resolved or the token request fails.
  @async
  OfferSession acceptOffer(
    String credentialOffer,
    String clientId,
    String keyId,
    DidMethod didMethod,
    String? redirectUrl,
    List<Oid4vciVersion> supportedVersions,
  );

  /// Submit a transaction code (PIN) and complete the issuance.
  ///
  /// Returns `Oid4vciSuccess` on success or `Oid4vciError` on any failure
  /// (wrong PIN, network, server error). Upstream `oid4vci-rs` erases the
  /// OAuth2 error code at the FFI boundary, so we cannot distinguish a
  /// wrong PIN from other token-endpoint failures at this layer; all
  /// token-endpoint errors surface as `Oid4vciError("authorization failed")`.
  ///
  /// The session is consumed and removed from the registry in all cases.
  @async
  Oid4vciResult continueWithTxCode(String sessionId, String txCode);

  /// Build the issuer's authorization URL for the browser sign-in step.
  ///
  /// Returns the fully-formed authorization URL (client_id, redirect_uri,
  /// response_type, scope, code_challenge, code_challenge_method, state) for
  /// the wallet to hand to a system-managed browser surface.
  ///
  /// Returns null when the session is not in the authorization-code state,
  /// when the session was created without a `redirectUrl`, or when URL
  /// construction fails (e.g., issuer metadata fetch error).
  ///
  /// The session is preserved on success — the caller must follow up with
  /// `continueWithAuthorizationCode` or `releaseSession`.
  @async
  String? buildAuthorizationUrl(String sessionId);

  /// Submit the authorization code returned by the issuer redirect and
  /// complete the issuance.
  ///
  /// Returns `Oid4vciSuccess` on success or `Oid4vciError` for any failure
  /// (token endpoint, state mismatch, network). Same upstream-error-erasure
  /// constraint as `continueWithTxCode` applies — callers cannot distinguish
  /// underlying causes at this layer.
  ///
  /// The session is consumed and removed from the registry in all cases.
  @async
  Oid4vciResult continueWithAuthorizationCode(String sessionId, String code);

  /// Drop a session without consuming it (user abort).
  ///
  /// Safe to call with an unknown sessionId — no-op.
  @async
  void releaseSession(String sessionId);
}
