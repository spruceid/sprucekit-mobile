import '../pigeon/spruce_utils.g.dart';
import 'mock_mdl_portrait.dart';

export 'mock_mdl_portrait.dart' show kMockMdlDefaultPortraitBase64;

/// Ready-made [MockMdlData] payloads for `SpruceUtils.generateMockMdlWithData`.
///
/// The values mirror, byte-for-byte, the hardcoded defaults the Rust layer
/// uses for `SpruceUtils.generateMockMdl` (rust/src/mdl/util.rs), so a mock
/// mDL generated through [johnDoe] is indistinguishable from the legacy path
/// except for the fields you override.
///
/// Two fields intentionally diverge from the Rust defaults, which randomize
/// them on every call:
///
/// * `documentNumber` is **required** — a stable, caller-chosen document
///   number is the reason this API exists (external systems key on it, and a
///   random default would silently reintroduce the instability).
/// * `administrativeNumber` defaults to the fixed `'ADM12345678'` (same
///   `ADM########` shape as the Rust random value) so regenerating an mDL is
///   fully deterministic.
abstract final class MockMdlDataDefaults {
  /// The default test subject used by the Rust `generate_test_mdl` path.
  ///
  /// Every field can be overridden via the named parameters; only
  /// [documentNumber] must be supplied:
  ///
  /// ```dart
  /// final result = await spruceUtils.generateMockMdlWithData(
  ///   alias,
  ///   MockMdlDataDefaults.johnDoe(documentNumber: 'DL61211920'),
  /// );
  /// ```
  static MockMdlData johnDoe({
    required String documentNumber,
    String familyName = 'Doe',
    String givenName = 'John',
    String birthDate = '1990-01-01',
    String issueDate = '2020-01-01',
    String expiryDate = '2030-01-01',
    String issuingCountry = 'US',
    String issuingAuthority = 'SpruceID',
    String portrait = kMockMdlDefaultPortraitBase64,
    List<String> drivingPrivileges = const [],
    String unDistinguishingSign = 'USA',
    String administrativeNumber = 'ADM12345678',
    int sex = 1,
    int height = 180,
    int weight = 75,
    String eyeColour = 'blue',
    String hairColour = 'black',
    String birthPlace = 'USA, California',
    String residentAddress = '123 Main St, Los Angeles, California, 90001',
    String portraitCaptureDate = '2020-01-01T12:00:00Z',
    int ageInYears = 35,
    int ageBirthYear = 1990,
    bool ageOver18 = true,
    bool ageOver21 = true,
    bool ageOver60 = false,
    String nationality = 'US',
    String residentCity = 'Los Angeles',
    String residentState = 'CA',
    String residentPostalCode = '90001',
    String residentCountry = 'US',
  }) {
    return MockMdlData(
      familyName: familyName,
      givenName: givenName,
      birthDate: birthDate,
      issueDate: issueDate,
      expiryDate: expiryDate,
      issuingCountry: issuingCountry,
      issuingAuthority: issuingAuthority,
      documentNumber: documentNumber,
      portrait: portrait,
      drivingPrivileges: drivingPrivileges,
      unDistinguishingSign: unDistinguishingSign,
      administrativeNumber: administrativeNumber,
      sex: sex,
      height: height,
      weight: weight,
      eyeColour: eyeColour,
      hairColour: hairColour,
      birthPlace: birthPlace,
      residentAddress: residentAddress,
      portraitCaptureDate: portraitCaptureDate,
      ageInYears: ageInYears,
      ageBirthYear: ageBirthYear,
      ageOver18: ageOver18,
      ageOver21: ageOver21,
      ageOver60: ageOver60,
      nationality: nationality,
      residentCity: residentCity,
      residentState: residentState,
      residentPostalCode: residentPostalCode,
      residentCountry: residentCountry,
    );
  }
}
