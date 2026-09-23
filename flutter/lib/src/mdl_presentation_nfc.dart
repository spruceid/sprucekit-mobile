import 'package:sprucekit_mobile/pigeon/mdl_presentation.g.dart';

/// Stable readers for the NFC detail of a presentation state update.
///
/// Code against these, not against [MdlPresentationStateUpdate.nfcPhase].
/// See [MdlNfcPhase] for the planned merge into [MdlPresentationState].
extension MdlPresentationNfcUpdate on MdlPresentationStateUpdate {
  /// The HCE service answers reader taps. Show the tap screen.
  bool get isWaitingForNfcTap => nfcPhase == MdlNfcPhase.waitingForTap;

  /// The tap delivered the BLE carrier. The connection is in progress.
  bool get isConnectingViaNfc => nfcPhase == MdlNfcPhase.connecting;

  /// NFC was turned off while waiting for the tap. Fall back to QR.
  bool get isNfcUnavailable => nfcPhase == MdlNfcPhase.unavailable;

  /// True for every update that belongs to an NFC tap based presentation
  /// before the BLE session is connected.
  bool get isNfcEngagement => nfcPhase != null;
}
