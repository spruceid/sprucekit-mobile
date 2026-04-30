import 'package:pigeon/pigeon.dart';

@ConfigurePigeon(
  PigeonOptions(
    dartOut: 'lib/pigeon/spruce_utils.g.dart',
    dartOptions: DartOptions(),
    kotlinOut:
        'android/src/main/kotlin/com/spruceid/sprucekit_mobile/SpruceUtils.g.kt',
    kotlinOptions: KotlinOptions(
      errorClassName: 'SpruceUtilsFlutterError',
      package: 'com.spruceid.sprucekit_mobile',
    ),
    swiftOut: 'ios/Classes/SpruceUtils.g.swift',
    swiftOptions: SwiftOptions(errorClassName: 'SpruceUtilsPigeonError'),
    dartPackageName: 'sprucekit_mobile',
  ),
)
/// Result of generating a mock mDL
sealed class GenerateMockMdlResult {}

/// Mock mDL generated successfully - stored in a CredentialPack
class GenerateMockMdlSuccess implements GenerateMockMdlResult {
  /// The credential pack ID where the mDL was stored
  String packId;

  /// The credential ID of the generated mDL
  String credentialId;

  /// The raw mSO mDoc as base64 string for storage
  String rawCredential;

  /// The key alias used for signing
  String keyAlias;

  GenerateMockMdlSuccess({
    required this.packId,
    required this.credentialId,
    required this.rawCredential,
    required this.keyAlias,
  });
}

/// Mock mDL generation failed
class GenerateMockMdlError implements GenerateMockMdlResult {
  String message;

  GenerateMockMdlError({required this.message});
}

/// The type of a PDF supplement.
///
/// New supplement types can be added here without changing the function
/// signature of `generateCredentialPdf`.
enum PdfSupplementType { barcode }

/// Barcode type for PDF supplements
enum PdfBarcodeType { qrCode, pdf417 }

/// A supplement to include in the generated PDF.
///
/// This is an extensible carrier: the `type` field determines which optional
/// fields are relevant.  Adding a new supplement kind only requires a new
/// [PdfSupplementType] value and new optional fields — the API signature
/// stays the same.
///
/// **Barcode** (`type == PdfSupplementType.barcode`):
///   - `data` — raw bytes to encode (e.g. VP Token, AAMVA payload)
///   - `barcodeType` — QR Code or PDF-417
class PdfSupplement {
  PdfSupplementType type;

  // ── Barcode fields ──────────────────────────────────────────────────
  /// Raw bytes to encode as a barcode. Required when `type` is `barcode`.
  Uint8List? data;

  /// The barcode symbology. Required when `type` is `barcode`.
  PdfBarcodeType? barcodeType;

  PdfSupplement({required this.type, this.data, this.barcodeType});
}

/// Selective-disclosure mode for VP token generation.
///
/// Mirrors the Rust `DisclosureSelection` enum.  The `type` discriminator
/// keeps the Pigeon class extensible: future modes (path-based selection,
/// presentation-definition driven, etc.) can be added without changing
/// [generateCredentialVpToken]'s signature.
enum DisclosureSelectionType { hideOnly, selectOnly }

/// Parameters for selective-disclosure VP token generation.
///
/// `hideOnly` reveals every disclosable claim **except** [fields]; ergonomic
/// for the mDL PDF case where almost every claim is shown and only `portrait`
/// is hidden.
///
/// `selectOnly` reveals **only** [fields]; ergonomic for narrow disclosures
/// like age verification (`["age_over_21"]`).
class DisclosureSelection {
  DisclosureSelectionType type;

  /// Field names (top-level under `credentialSubject.driversLicense`) to
  /// hide or select, depending on [type].
  List<String> fields;

  DisclosureSelection({required this.type, required this.fields});
}

/// Parameters for [SpruceUtils.generateCredentialVpToken].
///
/// `audience` and `nonce` are reserved for a future KB-JWT signing path; the
/// current implementation does not produce a key-binding JWT (suitable for
/// offline PDF-embedded VPs).
class VpTokenParams {
  DisclosureSelection disclosure;
  String audience;
  String? nonce;

  VpTokenParams({required this.disclosure, required this.audience, this.nonce});
}

/// Utility functions for credential operations
@HostApi()
abstract class SpruceUtils {
  /// Generate a mock mDL for testing
  ///
  /// Creates a self-signed test mDL credential and stores it in a new
  /// CredentialPack. The returned packId and credentialId can be used with
  /// MdlPresentation.initializeQrPresentation().
  ///
  /// @param keyAlias Optional key alias to use (defaults to "testMdl")
  /// @return Result with packId, credentialId, rawCredential, and keyAlias, or error
  @async
  GenerateMockMdlResult generateMockMdl(String? keyAlias);

  /// Generate a PDF from a raw mDL credential.
  ///
  /// Accepts the raw mSO mDoc as a base64url-encoded IssuerSigned string
  /// (the same format returned by generateMockMdl's rawCredential field).
  ///
  /// @param rawMdoc Base64url-encoded IssuerSigned bytes of the mDL
  /// @param supplements Optional list of supplements to include in the PDF
  /// @return Raw PDF bytes ready to write to a file and share
  @async
  Uint8List generateCredentialPdf(
    String rawMdoc,
    List<PdfSupplement> supplements,
  );

  /// Generate a compact SD-JWT VP token suitable for embedding in a PDF QR
  /// code.
  ///
  /// Wallets typically pass the returned bytes to [generateCredentialPdf] as
  /// a [PdfSupplement] with `barcodeType == PdfBarcodeType.qrCode`.
  ///
  /// **Currently only VCDM2 SD-JWT credentials are supported** (mDoc / JWT VC
  /// will throw `UnsupportedCredentialType`).  For the offline PDF case the
  /// returned token does **not** include a key-binding JWT — `audience` and
  /// `nonce` are accepted but not yet used.
  ///
  /// @param rawSdJwt Compact SD-JWT serialization of a VCDM2 SD-JWT credential
  /// @param params Disclosure selection + reserved audience / nonce
  /// @return Compact SD-JWT VP token bytes (UTF-8)
  @async
  Uint8List generateCredentialVpToken(String rawSdJwt, VpTokenParams params);

  /// Generate a **QR-ready compressed** SD-JWT VP token.
  ///
  /// Combines [generateCredentialVpToken] with the Colorado-pattern compression
  /// pipeline (`deflate → BigUint → base10 → "9"-prefix`) used to fit dense
  /// VP tokens into a QR numeric-mode payload.
  ///
  /// This is the **recommended path** for embedding a VP token in a PDF QR:
  /// wallets pass the returned bytes directly to [generateCredentialPdf] as
  /// a [PdfSupplement] with `barcodeType == PdfBarcodeType.qrCode` — no
  /// manual compression step needed.
  ///
  /// The verifier side (a) auto-detects the leading `"9"` and decompresses
  /// transparently inside `verifySdJwtVp`, or (b) can call
  /// [decompressVpFromQr] directly for inspection.
  ///
  /// @param rawSdJwt Compact SD-JWT serialization of a VCDM2 SD-JWT credential
  /// @param params Disclosure selection + reserved audience / nonce
  /// @return QR-ready compressed bytes (UTF-8 ASCII, `"9"` prefix + base10 digits)
  @async
  Uint8List generateCompressedVpToken(String rawSdJwt, VpTokenParams params);

  /// Generate a test mDL VCDM2 SD-JWT credential, returned as a compact
  /// SD-JWS string.
  ///
  /// The credential mirrors the schema CA DMV will issue once the SD-JWT
  /// microservice ships, but is signed with a test key generated on demand —
  /// so the credential is **not** verifiable against any production trust
  /// anchor. Useful for showcase / demo flows that need a real SD-JWT to
  /// drive [generateCompressedVpToken] without depending on a live issuer.
  ///
  /// @return Compact SD-JWT serialization (`<jwt>~<disc1>~…`) — feed straight
  ///         to [generateCredentialVpToken] / [generateCompressedVpToken]
  ///         as `rawSdJwt`.
  @async
  String generateTestMdlSdJwtCompact();

  /// Verify a compact SD-JWT VP token.
  ///
  /// Accepts either a raw compact SD-JWT (`<jwt>~<disc1>~…`) or a
  /// `"9"`-prefixed base10 QR payload — the implementation auto-detects the
  /// leading `"9"` and decompresses transparently before verifying. Throws
  /// on any failure (issuer signature mismatch, decompression error,
  /// disclosure hash mismatch, etc.); returns normally on success.
  ///
  /// Issuer trust is established via DID resolution (`AnyDidMethod`), so
  /// `did:jwk` issuers are fully verifiable offline.
  ///
  /// @param input Compact SD-JWT VP, or its `"9"`-prefixed compressed form
  ///              (e.g. straight from a [SpruceScanner] callback)
  @async
  void verifySdJwtVp(String input);

  /// Decompress a `"9"`-prefixed base10 QR payload back into a compact
  /// SD-JWT VP token.
  ///
  /// The inverse of the compression step inside [generateCompressedVpToken].
  /// Verification code typically does **not** need to call this directly —
  /// `verifySdJwtVp` auto-detects the prefix and decompresses internally —
  /// but it is exposed for inspection, logging, or non-verification flows
  /// (e.g. extracting fields client-side from a scanned QR).
  ///
  /// @param qrPayload Bytes scanned from the QR (`"9"` prefix + base10 digits)
  /// @return Original compact SD-JWT VP token bytes (UTF-8)
  @async
  Uint8List decompressVpFromQr(Uint8List qrPayload);
}
