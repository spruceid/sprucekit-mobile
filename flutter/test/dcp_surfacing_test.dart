import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sprucekit_mobile/sprucekit_mobile.dart';

/// Tests for the two DCP surfacing seams:
///  - `Oid4vciPresentationRequired`, the typed issuance interruption that
///    carries the issuer's OID4VP authorization request for the retry loop;
///  - `DynamicOfferData` + `getDynamicOffers`/`submitResponseWithOffers`, the
///    Dart view of natively-registered dynamic credential providers.
///
/// The host platform is faked at the pigeon channel layer, so these cover
/// only the Dart surface (codec, channel wiring, typed dispatch).
void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  /// Installs a fake host handler for one pigeon host-API channel, removed
  /// again after the test.
  void mockHost(
    String channelName,
    MessageCodec<Object?> codec,
    Future<Object?> Function(Object? message) handler,
  ) {
    final channel = BasicMessageChannel<Object?>(channelName, codec);
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockDecodedMessageHandler<Object?>(channel, handler);
    addTearDown(
      () => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockDecodedMessageHandler<Object?>(channel, null),
    );
  }

  group('Oid4vciPresentationRequired', () {
    test('survives a wire round-trip through the Oid4vci channel codec', () {
      final result = Oid4vciPresentationRequired(
        authorizationRequestJson: '{"response_type":"vp_token","nonce":"n1"}',
      );

      final encoded = Oid4vci.pigeonChannelCodec.encodeMessage(result);
      final decoded =
          Oid4vci.pigeonChannelCodec.decodeMessage(encoded)
              as Oid4vciPresentationRequired;

      expect(decoded, equals(result));
      expect(
        decoded.authorizationRequestJson,
        '{"response_type":"vp_token","nonce":"n1"}',
      );
      // The retry loop dispatches on the sealed result type.
      expect(decoded, isA<Oid4vciResult>());
    });

    test('is surfaced as a typed runIssuance result', () async {
      const authRequestJson = '{"response_type":"vp_token","nonce":"abc123"}';

      mockHost(
        'dev.flutter.pigeon.sprucekit_mobile.Oid4vci.runIssuance',
        Oid4vci.pigeonChannelCodec,
        (message) async {
          final args = message! as List<Object?>;
          expect(args[0], 'openid-credential-offer://issuer.example/offer');
          // The retry contract requires auto version negotiation ([]): a
          // pure-v1 exchange can never observe presentation_required.
          expect(args[6], isEmpty);
          return <Object?>[
            Oid4vciPresentationRequired(
              authorizationRequestJson: authRequestJson,
            ),
          ];
        },
      );

      final result = await Oid4vci().runIssuance(
        'openid-credential-offer://issuer.example/offer',
        'client-id',
        'https://wallet.example/redirect',
        'key-id',
        DidMethod.jwk,
        null,
        const <Oid4vciVersion>[],
      );

      // Dispatch via `is` chains on the sealed result.
      expect(result, isA<Oid4vciPresentationRequired>());
      expect(result, isNot(isA<Oid4vciSuccess>()));
      expect(result, isNot(isA<Oid4vciError>()));
      expect(
        (result as Oid4vciPresentationRequired).authorizationRequestJson,
        authRequestJson,
      );
    });
  });

  group('Dynamic credential offers', () {
    final offer = DynamicOfferData(
      offerId: 'offer-1',
      credentialQueryId: 'binding_vc_0',
      title: 'Vehicle binding credential',
    );

    test('survive a wire round-trip through the Oid4vp channel codec', () {
      final encoded = Oid4vp.pigeonChannelCodec.encodeMessage(offer);
      final decoded =
          Oid4vp.pigeonChannelCodec.decodeMessage(encoded) as DynamicOfferData;

      expect(decoded, equals(offer));
      expect(decoded.offerId, 'offer-1');
      expect(decoded.credentialQueryId, 'binding_vc_0');
      expect(decoded.title, 'Vehicle binding credential');
    });

    test('getDynamicOffers returns the host-surfaced offers', () async {
      mockHost(
        'dev.flutter.pigeon.sprucekit_mobile.Oid4vp.getDynamicOffers',
        Oid4vp.pigeonChannelCodec,
        (message) async => <Object?>[
          <Object?>[offer],
        ],
      );

      final offers = await Oid4vp().getDynamicOffers();
      expect(offers, hasLength(1));
      expect(offers.single, equals(offer));
    });

    test('submitResponseWithOffers sends credentials, fields, offer ids and '
        'options', () async {
      Object? sent;
      mockHost(
        'dev.flutter.pigeon.sprucekit_mobile.Oid4vp.submitResponseWithOffers',
        Oid4vp.pigeonChannelCodec,
        (message) async {
          sent = message;
          return <Object?>[Oid4vpSuccess(message: 'ok')];
        },
      );

      final key = PresentableCredentialKey(
        credentialId: 'cred-1',
        credentialQueryId: 'mdl_0',
      );
      final result = await Oid4vp().submitResponseWithOffers(
        <PresentableCredentialKey>[key],
        const <List<String>>[
          ['field.a', 'field.b'],
        ],
        const <String>['offer-1'],
        ResponseOptions(forceArraySerialization: false),
      );

      expect(result, isA<Oid4vpSuccess>());

      final args = sent! as List<Object?>;
      expect((args[0]! as List<Object?>).single, equals(key));
      expect(args[1], <Object?>[
        <Object?>['field.a', 'field.b'],
      ]);
      // Offers travel back as ids only — the native adapter holds the
      // authoritative offer records.
      expect(args[2], <Object?>['offer-1']);
      expect(args[3], isA<ResponseOptions>());
    });

    test('offers-only submission is accepted by the Dart surface', () async {
      mockHost(
        'dev.flutter.pigeon.sprucekit_mobile.Oid4vp.submitResponseWithOffers',
        Oid4vp.pigeonChannelCodec,
        (message) async {
          final args = message! as List<Object?>;
          expect(args[0], isEmpty);
          expect(args[1], isEmpty);
          expect(args[2], <Object?>['offer-1']);
          return <Object?>[Oid4vpSuccess(message: 'ok')];
        },
      );

      final result = await Oid4vp().submitResponseWithOffers(
        const <PresentableCredentialKey>[],
        const <List<String>>[],
        const <String>['offer-1'],
        ResponseOptions(forceArraySerialization: false),
      );
      expect(result, isA<Oid4vpSuccess>());
    });
  });
}
