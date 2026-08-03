import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:sprucekit_mobile/sprucekit_mobile.dart';

void main() {
  group('MockMdlData pigeon codec', () {
    test(
      'survives a wire round-trip through the SpruceUtils channel codec',
      () {
        final data = MockMdlDataDefaults.johnDoe(
          documentNumber: 'DL61211920',
          drivingPrivileges: const ['A', 'B'],
          portrait: 'cG9ydHJhaXQ=',
        );

        final encoded = SpruceUtils.pigeonChannelCodec.encodeMessage(data);
        final decoded =
            SpruceUtils.pigeonChannelCodec.decodeMessage(encoded)
                as MockMdlData;

        // Pigeon generates deep equality for data classes.
        expect(decoded, equals(data));
        // Spot-check a few fields to guard against a degenerate `==`.
        expect(decoded.documentNumber, 'DL61211920');
        expect(decoded.drivingPrivileges, const ['A', 'B']);
        expect(decoded.portrait, 'cG9ydHJhaXQ=');
        expect(decoded.sex, 1);
        expect(decoded.ageOver60, isFalse);
      },
    );

    test('encode/decode preserves every field', () {
      final data = MockMdlData(
        familyName: 'fn',
        givenName: 'gn',
        birthDate: 'bd',
        issueDate: 'id',
        expiryDate: 'ed',
        issuingCountry: 'ic',
        issuingAuthority: 'ia',
        documentNumber: 'dn',
        portrait: 'p',
        drivingPrivileges: const ['dp1', 'dp2'],
        unDistinguishingSign: 'uds',
        administrativeNumber: 'an',
        sex: 2,
        height: 170,
        weight: 60,
        eyeColour: 'ec',
        hairColour: 'hc',
        birthPlace: 'bp',
        residentAddress: 'ra',
        portraitCaptureDate: 'pcd',
        ageInYears: 30,
        ageBirthYear: 1995,
        ageOver18: true,
        ageOver21: false,
        ageOver60: true,
        nationality: 'n',
        residentCity: 'rc',
        residentState: 'rs',
        residentPostalCode: 'rpc',
        residentCountry: 'rco',
      );

      final decoded = MockMdlData.decode(data.encode());
      expect(decoded, equals(data));
    });
  });

  group('MockMdlDataDefaults.johnDoe', () {
    test('matches the Rust generate_test_mdl hardcoded defaults', () {
      final data = MockMdlDataDefaults.johnDoe(documentNumber: 'DL61211920');

      // Pinned byte-for-byte against the hardcoded JSON in
      // rust/src/mdl/util.rs (prepare_mdoc).
      expect(data.familyName, 'Doe');
      expect(data.givenName, 'John');
      expect(data.birthDate, '1990-01-01');
      expect(data.issueDate, '2020-01-01');
      expect(data.expiryDate, '2030-01-01');
      expect(data.issuingCountry, 'US');
      expect(data.issuingAuthority, 'SpruceID');
      expect(data.drivingPrivileges, isEmpty);
      expect(data.unDistinguishingSign, 'USA');
      expect(data.sex, 1);
      expect(data.height, 180);
      expect(data.weight, 75);
      expect(data.eyeColour, 'blue');
      expect(data.hairColour, 'black');
      expect(data.birthPlace, 'USA, California');
      expect(
        data.residentAddress,
        '123 Main St, Los Angeles, California, 90001',
      );
      expect(data.portraitCaptureDate, '2020-01-01T12:00:00Z');
      expect(data.ageInYears, 35);
      expect(data.ageBirthYear, 1990);
      expect(data.ageOver18, isTrue);
      expect(data.ageOver21, isTrue);
      expect(data.ageOver60, isFalse);
      expect(data.nationality, 'US');
      expect(data.residentCity, 'Los Angeles');
      expect(data.residentState, 'CA');
      expect(data.residentPostalCode, '90001');
      expect(data.residentCountry, 'US');

      // Rust randomizes document_number (DL########) and
      // administrative_number (ADM########) on every call; here the former is
      // caller-chosen (the whole point of this API) and the latter is pinned.
      expect(data.documentNumber, 'DL61211920');
      expect(data.administrativeNumber, 'ADM12345678');
    });

    test('default portrait is byte-for-byte the Rust portrait asset', () {
      final data = MockMdlDataDefaults.johnDoe(documentNumber: 'DL61211920');

      // rust/src/mdl/util.rs embeds this file via include_str!.
      final rustPortraitFile = File('../rust/tests/res/mdl/portrait.base64');
      expect(
        rustPortraitFile.existsSync(),
        isTrue,
        reason:
            'expected to run `flutter test` from the flutter/ package '
            'root inside the sprucekit-mobile repo',
      );
      expect(data.portrait, rustPortraitFile.readAsStringSync());
    });

    test('overrides only the fields that are passed', () {
      final data = MockMdlDataDefaults.johnDoe(
        documentNumber: 'DL00000001',
        residentState: 'NY',
      );

      expect(data.documentNumber, 'DL00000001');
      expect(data.residentState, 'NY');
      // Everything else keeps its default.
      expect(data.familyName, 'Doe');
      expect(data.residentCity, 'Los Angeles');
      expect(data.portrait, kMockMdlDefaultPortraitBase64);
    });
  });
}
