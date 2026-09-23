import 'package:flutter_test/flutter_test.dart';
import 'package:sprucekit_mobile/sprucekit_mobile.dart';

void main() {
  group('MdlPresentationNfcUpdate', () {
    test('a QR update has no NFC detail', () {
      final update = MdlPresentationStateUpdate(
        state: MdlPresentationState.engagingQrCode,
        qrCodeUri: 'mdoc:abc',
      );
      expect(update.isNfcEngagement, isFalse);
      expect(update.isWaitingForNfcTap, isFalse);
      expect(update.isConnectingViaNfc, isFalse);
      expect(update.isNfcUnavailable, isFalse);
    });

    test('waiting for the tap rides on initializing', () {
      final update = MdlPresentationStateUpdate(
        state: MdlPresentationState.initializing,
        nfcPhase: MdlNfcPhase.waitingForTap,
      );
      expect(update.state, MdlPresentationState.initializing);
      expect(update.isWaitingForNfcTap, isTrue);
      expect(update.isConnectingViaNfc, isFalse);
      expect(update.isNfcEngagement, isTrue);
    });

    test('connecting rides on initializing', () {
      final update = MdlPresentationStateUpdate(
        state: MdlPresentationState.initializing,
        nfcPhase: MdlNfcPhase.connecting,
      );
      expect(update.isConnectingViaNfc, isTrue);
      expect(update.isWaitingForNfcTap, isFalse);
    });

    test('nfc turned off rides on error', () {
      final update = MdlPresentationStateUpdate(
        state: MdlPresentationState.error,
        error: 'NFC was turned off',
        nfcPhase: MdlNfcPhase.unavailable,
      );
      expect(update.state, MdlPresentationState.error);
      expect(update.isNfcUnavailable, isTrue);
    });
  });
}
