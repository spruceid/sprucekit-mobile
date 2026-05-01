import 'package:flutter/foundation.dart';
import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';

/// The type of scanner to use
enum ScannerType {
  /// QR Code scanner
  qrCode,

  /// PDF417 barcode scanner (used for driver's licenses)
  pdf417,

  /// MRZ (Machine Readable Zone) scanner for passports/IDs
  mrz,
}

/// A scanner widget that uses the native SpruceID Mobile SDK scanner components.
///
/// This widget provides QR code, PDF417, and MRZ scanning capabilities with
/// a native camera view.
///
/// Example:
/// ```dart
/// SpruceScanner(
///   type: ScannerType.qrCode,
///   title: 'Scan QR Code',
///   subtitle: 'Align the code within the frame',
///   onRead: (content) {
///     print('Scanned: $content');
///   },
///   onCancel: () {
///     Navigator.pop(context);
///   },
/// )
/// ```
class SpruceScanner extends StatefulWidget {
  /// Creates a scanner widget.
  const SpruceScanner({
    super.key,
    required this.type,
    required this.onRead,
    required this.onCancel,
    this.title = 'Scan QR Code',
    this.subtitle = 'Please align within the guides',
    this.isMatch,
    this.showCancelButton = true,
    this.headless = false,
    this.scanCooldownMs = 0,
    this.onCameraReady,
  });

  /// The type of scanner to use.
  final ScannerType type;

  /// The title displayed on the scanner screen.
  final String title;

  /// The subtitle displayed below the title.
  final String subtitle;

  /// Callback when a code is successfully read.
  final void Function(String content) onRead;

  /// Optional callback to validate if the scanned content matches expected format.
  /// Return true to accept the scan, false to continue scanning.
  final bool Function(String content)? isMatch;

  /// Callback when the user cancels scanning.
  final VoidCallback onCancel;

  /// Whether to show the cancel button. Defaults to true.
  final bool showCancelButton;

  /// When true, the scanner renders only the camera preview with no chrome
  /// (no title, subtitle, overlay, guides, scan-line, or cancel button).
  /// The caller is expected to draw its own UI on top.
  final bool headless;

  /// Cooldown between successive [onRead] emissions, in milliseconds.
  /// When 0 (default), the scanner stops after the first read on iOS and
  /// fires per-frame on Android. When > 0, the scanner runs continuously
  /// and ignores reads within the cooldown window.
  final int scanCooldownMs;

  /// Fired once when the camera preview is producing frames.
  ///
  /// Android: triggered when `PreviewView.previewStreamState` reaches
  /// `STREAMING` (CameraX's first-frame signal).
  /// iOS: fired immediately after the platform view is constructed
  /// (UIKitView composition does not need a real frame signal).
  final VoidCallback? onCameraReady;

  @override
  State<SpruceScanner> createState() => _SpruceScannerState();
}

class _SpruceScannerState extends State<SpruceScanner> {
  late MethodChannel _channel;
  bool _channelInitialized = false;

  @override
  void dispose() {
    if (_channelInitialized) {
      _channel.setMethodCallHandler(null);
    }
    super.dispose();
  }

  void _onPlatformViewCreated(int id) {
    _channel = MethodChannel('com.spruceid.sprucekit_mobile/scanner_$id');
    _channelInitialized = true;
    _channel.setMethodCallHandler(_handleMethodCall);
  }

  Future<dynamic> _handleMethodCall(MethodCall call) async {
    switch (call.method) {
      case 'onRead':
        final content = call.arguments as String;
        widget.onRead(content);
        return null;
      case 'onCancel':
        widget.onCancel();
        return null;
      case 'onCameraReady':
        widget.onCameraReady?.call();
        return null;
      case 'isMatch':
        final content = call.arguments as String;
        if (widget.isMatch != null) {
          return widget.isMatch!(content);
        }
        return true;
      default:
        throw PlatformException(
          code: 'Unimplemented',
          details: "Method '${call.method}' not implemented",
        );
    }
  }

  Map<String, dynamic> _creationParams() {
    if (widget.headless) {
      // Native scanners render no chrome when text is empty and overlays are transparent.
      return {
        'type': widget.type.name,
        'title': '',
        'subtitle': '',
        'instructions': '',
        'guidesText': '',
        'showCancelButton': false,
        'backgroundOpacity': 0.0,
        'guidesColor': 0x00000000,
        'readerColor': 0x00000000,
        'scanCooldownMs': widget.scanCooldownMs,
      };
    }
    return {
      'type': widget.type.name,
      'title': widget.title,
      'subtitle': widget.subtitle,
      'showCancelButton': widget.showCancelButton,
      'scanCooldownMs': widget.scanCooldownMs,
    };
  }

  @override
  Widget build(BuildContext context) {
    const String viewType = 'com.spruceid.sprucekit_mobile/scanner';

    switch (defaultTargetPlatform) {
      case TargetPlatform.android:
        return PlatformViewLink(
          viewType: viewType,
          surfaceFactory: (context, controller) {
            return AndroidViewSurface(
              controller: controller as AndroidViewController,
              gestureRecognizers:
                  const <Factory<OneSequenceGestureRecognizer>>{},
              hitTestBehavior: PlatformViewHitTestBehavior.opaque,
            );
          },
          onCreatePlatformView: (params) {
            return PlatformViewsService.initExpensiveAndroidView(
                id: params.id,
                viewType: viewType,
                layoutDirection: TextDirection.ltr,
                creationParams: _creationParams(),
                creationParamsCodec: const StandardMessageCodec(),
                onFocus: () {
                  params.onFocusChanged(true);
                },
              )
              ..addOnPlatformViewCreatedListener(params.onPlatformViewCreated)
              ..addOnPlatformViewCreatedListener(_onPlatformViewCreated)
              ..create();
          },
        );
      case TargetPlatform.iOS:
        return UiKitView(
          viewType: viewType,
          creationParams: _creationParams(),
          creationParamsCodec: const StandardMessageCodec(),
          onPlatformViewCreated: _onPlatformViewCreated,
        );
      default:
        return Center(
          child: Text(
            'Scanner is not supported on ${defaultTargetPlatform.name}',
          ),
        );
    }
  }
}
