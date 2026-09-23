import 'package:sprucekit_mobile/pigeon/mdl_presentation.g.dart';

/// Stable readers for the NFC detail of a presentation state update.
///
/// These getters are the migration path for the NFC states. Today they read
/// [MdlPresentationStateUpdate.nfcPhase]. When the next major release folds
/// the NFC phases into [MdlPresentationState], the getters read the state
/// instead and callers do not change. Code against the getters, not the
/// field.
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
