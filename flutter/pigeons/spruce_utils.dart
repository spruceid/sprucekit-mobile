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
}
