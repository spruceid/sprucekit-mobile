import 'package:sprucekit_mobile/pigeon/mdl_presentation.g.dart';

/// Readers for the NFC detail of a presentation state update.
///
/// Code against these, not against [MdlPresentationStateUpdate.nfcPhase].
extension MdlPresentationNfcUpdate on MdlPresentationStateUpdate {
  /// The HCE service answers reader taps. Show the tap screen.
  bool get isWaitingForNfcTap => nfcPhase == MdlNfcPhase.waitingForTap;

  /// The tap delivered the BLE carrier. The connection is in progress.
  bool get isConnectingViaNfc => nfcPhase == MdlNfcPhase.connecting;

  /// NFC was turned off while waiting for the tap. Fall back to QR.
  bool get isNfcUnavailable => nfcPhase == MdlNfcPhase.unavailable;
}

/// Readers for [MdlPresentation.getNfcAvailability].
extension MdlNfcAvailabilityReaders on MdlNfcAvailability {
  /// The device and the app can present over NFC, now or after the user
  /// turns NFC on. Use it to decide whether to offer the tap option.
  bool get isSupported =>
      this == MdlNfcAvailability.available ||
      this == MdlNfcAvailability.turnedOff;
}
