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

  /// The key alias used for signing
  String keyAlias;

  GenerateMockMdlSuccess({
    required this.packId,
    required this.credentialId,
    required this.keyAlias,
  });
}

/// Mock mDL generation failed
class GenerateMockMdlError implements GenerateMockMdlResult {
  String message;

  GenerateMockMdlError({required this.message});
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
  /// @return Result with packId, credentialId, and keyAlias, or error
  @async
  GenerateMockMdlResult generateMockMdl(String? keyAlias);
}
