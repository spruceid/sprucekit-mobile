import 'package:pigeon/pigeon.dart';

@ConfigurePigeon(
  PigeonOptions(
    dartOut: 'lib/pigeon/mdl_presentation.g.dart',
    dartOptions: DartOptions(),
    kotlinOut:
        'android/src/main/kotlin/com/spruceid/sprucekit_mobile/MdlPresentation.g.kt',
    kotlinOptions: KotlinOptions(
      errorClassName: 'MdlPresentationFlutterError',
      package: 'com.spruceid.sprucekit_mobile',
    ),
    swiftOut: 'ios/Classes/MdlPresentation.g.swift',
    swiftOptions: SwiftOptions(errorClassName: 'MdlPresentationPigeonError'),
    dartPackageName: 'sprucekit_mobile',
  ),
)
/// Presentation state for ISO 18013-5 mDL presentation
enum MdlPresentationState {
  /// Initial state, not yet started
  uninitialized,

  /// Waiting for Bluetooth/transport to be ready
  initializing,

  /// Bluetooth needs to be turned on to continue
  bluetoothRequired,

  /// Bluetooth authorization required in Settings
  bluetoothAuthorizationRequired,

  /// QR code is ready to be displayed
  engagingQrCode,

  /// Reader has connected, waiting for request
  connected,

  /// Received request from reader, user needs to select fields
  selectingNamespaces,

  /// Sending response to reader
  sendingResponse,

  /// Successfully sent response
  success,

  /// Session timed out
  timeout,

  /// Reader disconnected unexpectedly
  readerDisconnected,

  /// An error occurred
  error,
}

/// Requested item from a namespace
class MdlNamespaceItem {
  /// Field name
  String name;

  /// Whether the field is required by the reader
  bool intentToRetain;

  MdlNamespaceItem({required this.name, required this.intentToRetain});
}

/// Request from reader for a specific namespace
class MdlNamespaceRequest {
  /// Namespace identifier (e.g., "org.iso.18013.5.1")
  String namespace;

  /// Items requested in this namespace
  List<MdlNamespaceItem> items;

  MdlNamespaceRequest({required this.namespace, required this.items});
}

/// Items request from reader for a specific docType
class MdlItemsRequest {
  /// Document type (e.g., "org.iso.18013.5.1.mDL")
  String docType;

  /// Namespaces and their requested items
  List<MdlNamespaceRequest> namespaces;

  MdlItemsRequest({required this.docType, required this.namespaces});
}

/// State update event from the presentation session
class MdlPresentationStateUpdate {
  /// Current state
  MdlPresentationState state;

  /// QR code URI (only set when state is engagingQrCode)
  String? qrCodeUri;

  /// Items requests from reader (only set when state is selectingNamespaces)
  List<MdlItemsRequest>? itemsRequests;

  /// Error message (only set when state is error)
  String? error;

  MdlPresentationStateUpdate({
    required this.state,
    this.qrCodeUri,
    this.itemsRequests,
    this.error,
  });
}

/// Result type for presentation operations
sealed class MdlPresentationResult {}

/// Operation succeeded
class MdlPresentationSuccess implements MdlPresentationResult {
  String? message;

  MdlPresentationSuccess({this.message});
}

/// Operation failed
class MdlPresentationError implements MdlPresentationResult {
  String message;

  MdlPresentationError({required this.message});
}

/// Callback interface for presentation state updates
@FlutterApi()
abstract class MdlPresentationCallback {
  /// Called when the presentation state changes
  void onStateChange(MdlPresentationStateUpdate update);
}

/// ISO 18013-5 mDL Presentation API
///
/// Handles close proximity presentation of mDL credentials via BLE
@HostApi()
abstract class MdlPresentation {
  /// Initialize a QR code based presentation session
  ///
  /// @param packId The credential pack ID containing the mDL
  /// @param credentialId The credential ID of the mDL to present
  /// @return Result indicating success or error
  @async
  MdlPresentationResult initializeQrPresentation(
    String packId,
    String credentialId,
  );

  /// Get the current QR code URI for the presentation
  ///
  /// @return The QR code URI, or null if not in the correct state
  String? getQrCodeUri();

  /// Get the current presentation state
  ///
  /// @return Current state update with all relevant data
  MdlPresentationStateUpdate getCurrentState();

  /// Submit the user's selection of namespaces/fields to share
  ///
  /// The map structure is: docType -> namespace -> list of field names
  ///
  /// @param selectedNamespaces The selected namespaces and fields
  /// @return Result indicating success or error
  @async
  MdlPresentationResult submitNamespaces(
    Map<String, Map<String, List<String>>> selectedNamespaces,
  );

  /// Cancel the current presentation session
  void cancel();
}
