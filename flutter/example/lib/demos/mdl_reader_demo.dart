import 'dart:convert';
import 'dart:io' show Platform;
import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:sprucekit_mobile/sprucekit_mobile.dart';

/// Demo screen for ISO 18013-5 mDL **reader** (verifier) via NFC or QR.
///
/// - NFC tab: user taps the holder's phone; SDK drives the APDU handover
///   then BLE session.
/// - QR tab: user scans the holder's QR code with [SpruceScanner]; the URI
///   is fed directly into [MdlReader.startQrReader].
///
/// Both paths converge on the same response display. Tap "Reset" to start
/// over.
class MdlReaderDemo extends StatefulWidget {
  const MdlReaderDemo({super.key});

  @override
  State<MdlReaderDemo> createState() => _MdlReaderDemoState();
}

class _MdlReaderDemoState extends State<MdlReaderDemo>
    with SingleTickerProviderStateMixin
    implements MdlReaderCallback {
  final _reader = MdlReader();
  late final TabController _tabController;

  MdlReaderState _state = MdlReaderState.uninitialized;
  MdlReadResponse? _response;
  String? _error;
  bool _isScanningQr = false;
  PermissionStatus _cameraPermission = PermissionStatus.denied;
  bool _nfcSupported = false;
  bool _bluetoothGranted = false;

  /// Default query — exercises the commonly-rendered mDL + AAMVA fields.
  /// Outer key = namespace, inner key = element name, value = intent-to-retain.
  static const Map<String, Map<String, bool>> _defaultQuery = {
    'org.iso.18013.5.1': {
      'given_name': false,
      'family_name': false,
      'birth_date': false,
      'issue_date': false,
      'expiry_date': false,
      'document_number': false,
      'portrait': false,
      'age_over_18': false,
      'age_over_21': false,
      'driving_privileges': false,
      'issuing_country': false,
      'issuing_authority': false,
    },
    'org.iso.18013.5.1.aamva': {
      'domestic_driving_privileges': false,
      'EDL_credential': false,
      'sex': false,
    },
  };

  /// IACA trust anchors (PEM). Empty list → no chain validation
  /// (issuerAuthentication will be `invalid` or `unchecked`). Demo leaves
  /// empty so the trust path is exercised but visibly fails — see the
  /// trust badge in the response view.
  static const List<String> _trustedRoots = [];

  @override
  void initState() {
    super.initState();
    _tabController = TabController(length: 2, vsync: this);
    MdlReaderCallback.setUp(this);
    _check();
  }

  Future<void> _check() async {
    final supported = await _reader.isNfcSupported();
    final cameraStatus = await Permission.camera.status;
    final btGranted = await _checkBluetoothGranted();
    if (!mounted) return;
    setState(() {
      _nfcSupported = supported;
      _cameraPermission = cameraStatus;
      _bluetoothGranted = btGranted;
    });
  }

  Future<bool> _checkBluetoothGranted() async {
    if (Platform.isAndroid) {
      // Android 12+ runtime perms; on Android 11 and below these calls
      // return granted (manifest BLUETOOTH/BLUETOOTH_ADMIN are install-time).
      final c = await Permission.bluetoothConnect.status;
      final s = await Permission.bluetoothScan.status;
      final a = await Permission.bluetoothAdvertise.status;
      return c.isGranted && s.isGranted && a.isGranted;
    }
    if (Platform.isIOS) {
      final status = await Permission.bluetooth.status;
      return status.isGranted;
    }
    return true;
  }

  Future<void> _requestBluetoothPermission() async {
    if (Platform.isAndroid) {
      final results = await [
        Permission.bluetoothConnect,
        Permission.bluetoothScan,
        Permission.bluetoothAdvertise,
      ].request();
      final granted = results.values.every((s) => s.isGranted);
      if (!mounted) return;
      setState(() => _bluetoothGranted = granted);
    } else if (Platform.isIOS) {
      final status = await Permission.bluetooth.request();
      if (!mounted) return;
      setState(() => _bluetoothGranted = status.isGranted);
    }
  }

  Future<void> _requestCameraPermission() async {
    final status = await Permission.camera.request();
    if (!mounted) return;
    setState(() => _cameraPermission = status);
  }

  @override
  void dispose() {
    _reader.cancel();
    _tabController.dispose();
    super.dispose();
  }

  // MdlReaderCallback ------------------------------------------------------

  @override
  void onStateChange(MdlReaderStateUpdate update) {
    if (!mounted) return;
    setState(() {
      _state = update.state;
      _response = update.response;
      _error = update.error;
      // QR scanner overlay should auto-dismiss once a handover starts.
      if (update.state != MdlReaderState.uninitialized) _isScanningQr = false;
    });
  }

  // Actions ----------------------------------------------------------------

  Future<bool> _ensureBluetoothGranted() async {
    if (!_bluetoothGranted) {
      await _requestBluetoothPermission();
    }
    return _bluetoothGranted;
  }

  Future<void> _startNfc() async {
    if (!await _ensureBluetoothGranted()) return;
    setState(() {
      _state = MdlReaderState.uninitialized;
      _response = null;
      _error = null;
    });
    await _reader.startNfcReader(_defaultQuery, _trustedRoots);
  }

  Future<void> _startQrScan() async {
    if (!await _ensureBluetoothGranted()) return;
    if (!_cameraPermission.isGranted) {
      await _requestCameraPermission();
      if (!_cameraPermission.isGranted) return;
    }
    setState(() {
      _isScanningQr = true;
      _state = MdlReaderState.uninitialized;
      _response = null;
      _error = null;
    });
  }

  Future<void> _onQrRead(String content) async {
    setState(() => _isScanningQr = false);
    await _reader.startQrReader(content, _defaultQuery, _trustedRoots);
  }

  Future<void> _reset() async {
    await _reader.cancel();
    setState(() {
      _state = MdlReaderState.uninitialized;
      _response = null;
      _error = null;
      _isScanningQr = false;
    });
  }

  // UI ---------------------------------------------------------------------

  @override
  Widget build(BuildContext context) {
    if (_isScanningQr) {
      return SpruceScanner(
        type: ScannerType.qrCode,
        title: 'Scan holder QR',
        subtitle: 'Align the mDL QR code within the frame',
        onRead: _onQrRead,
        onCancel: () => setState(() => _isScanningQr = false),
      );
    }

    return Scaffold(
      appBar: AppBar(
        title: const Text('mDL Reader'),
        bottom: TabBar(
          controller: _tabController,
          tabs: const [
            Tab(icon: Icon(Icons.contactless), text: 'NFC'),
            Tab(icon: Icon(Icons.qr_code_scanner), text: 'QR'),
          ],
        ),
      ),
      body: _response != null
          ? _ResponseView(response: _response!, onReset: _reset)
          : TabBarView(
              controller: _tabController,
              children: [_nfcTab(), _qrTab()],
            ),
    );
  }

  Widget _nfcTab() {
    return Padding(
      padding: const EdgeInsets.all(24),
      child: Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            _StateIcon(state: _state),
            const SizedBox(height: 16),
            Text(
              _stateLabel(_state),
              style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w500),
              textAlign: TextAlign.center,
            ),
            if (_error != null) ...[
              const SizedBox(height: 12),
              Text(
                _error!,
                style: const TextStyle(color: Colors.red),
                textAlign: TextAlign.center,
              ),
            ],
            const SizedBox(height: 24),
            ElevatedButton.icon(
              onPressed: _nfcSupported ? _startNfc : null,
              icon: const Icon(Icons.contactless),
              label: Text(
                _state == MdlReaderState.nfcWaitingForTag
                    ? 'Restart scanning'
                    : 'Start NFC scanning',
              ),
            ),
            if (!_nfcSupported)
              const Padding(
                padding: EdgeInsets.only(top: 12),
                child: Text(
                  'NFC hardware not detected on this device.',
                  style: TextStyle(color: Colors.grey),
                  textAlign: TextAlign.center,
                ),
              ),
            if (_state != MdlReaderState.uninitialized) ...[
              const SizedBox(height: 12),
              TextButton(onPressed: _reset, child: const Text('Cancel')),
            ],
          ],
        ),
      ),
    );
  }

  Widget _qrTab() {
    return Padding(
      padding: const EdgeInsets.all(24),
      child: Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            const Icon(Icons.qr_code_scanner, size: 64, color: Colors.grey),
            const SizedBox(height: 16),
            Text(
              _stateLabel(_state),
              style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w500),
              textAlign: TextAlign.center,
            ),
            if (_error != null) ...[
              const SizedBox(height: 12),
              Text(
                _error!,
                style: const TextStyle(color: Colors.red),
                textAlign: TextAlign.center,
              ),
            ],
            const SizedBox(height: 24),
            ElevatedButton.icon(
              onPressed: _startQrScan,
              icon: const Icon(Icons.camera_alt),
              label: const Text('Scan QR code'),
            ),
            if (!_cameraPermission.isGranted)
              const Padding(
                padding: EdgeInsets.only(top: 12),
                child: Text(
                  'Camera permission required to scan QR codes.',
                  style: TextStyle(color: Colors.grey),
                  textAlign: TextAlign.center,
                ),
              ),
            if (_state != MdlReaderState.uninitialized) ...[
              const SizedBox(height: 12),
              TextButton(onPressed: _reset, child: const Text('Cancel')),
            ],
          ],
        ),
      ),
    );
  }

  String _stateLabel(MdlReaderState state) {
    switch (state) {
      case MdlReaderState.uninitialized:
        return 'Ready';
      case MdlReaderState.nfcUnsupported:
        return 'NFC not supported on this device';
      case MdlReaderState.nfcDisabled:
        return 'NFC is disabled — enable it in system settings';
      case MdlReaderState.nfcWaitingForTag:
        return 'Tap the holder phone to share their credential';
      case MdlReaderState.nfcExchanging:
        return 'NFC handover in progress…';
      case MdlReaderState.bleConnecting:
        return 'Connecting over Bluetooth…';
      case MdlReaderState.bleReceivingResponse:
        return 'Receiving response…';
      case MdlReaderState.success:
        return 'Done';
      case MdlReaderState.error:
        return 'Error';
    }
  }
}

/// Visual indicator for the current reader state.
class _StateIcon extends StatelessWidget {
  const _StateIcon({required this.state});
  final MdlReaderState state;

  @override
  Widget build(BuildContext context) {
    switch (state) {
      case MdlReaderState.nfcWaitingForTag:
        return const Icon(Icons.contactless, size: 64, color: Colors.blue);
      case MdlReaderState.nfcExchanging:
      case MdlReaderState.bleConnecting:
      case MdlReaderState.bleReceivingResponse:
        return const SizedBox(
          height: 64,
          width: 64,
          child: CircularProgressIndicator(),
        );
      case MdlReaderState.nfcUnsupported:
      case MdlReaderState.nfcDisabled:
        return const Icon(Icons.error_outline, size: 64, color: Colors.orange);
      case MdlReaderState.error:
        return const Icon(Icons.error, size: 64, color: Colors.red);
      case MdlReaderState.success:
        return const Icon(Icons.check_circle, size: 64, color: Colors.green);
      case MdlReaderState.uninitialized:
        return const Icon(Icons.contactless, size: 64, color: Colors.grey);
    }
  }
}

/// Renders a successful [MdlReadResponse]: trust badge, namespaces /
/// elements, errors panel.
class _ResponseView extends StatelessWidget {
  const _ResponseView({required this.response, required this.onReset});
  final MdlReadResponse response;
  final VoidCallback onReset;

  bool get _trusted =>
      response.issuerAuthentication == MdlAuthenticationStatus.valid &&
      response.deviceAuthentication == MdlAuthenticationStatus.valid;

  Color get _trustColor => _trusted ? Colors.green : Colors.red.shade700;

  /// Decoded `verifiedResponseJson` shaped as `namespace → element → value`.
  /// Values are JSON primitives, nested maps, or lists. We keep them as
  /// `dynamic` and let [_ElementRow] format them.
  Map<String, dynamic> get _decodedNamespaces {
    try {
      final decoded = jsonDecode(response.verifiedResponseJson);
      if (decoded is Map<String, dynamic>) return decoded;
      if (decoded is Map) {
        return decoded.map((k, v) => MapEntry(k.toString(), v));
      }
    } catch (_) {}
    return const {};
  }

  @override
  Widget build(BuildContext context) {
    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        Card(
          color: _trustColor.withValues(alpha: 0.08),
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: Row(
              children: [
                Icon(
                  _trusted ? Icons.verified : Icons.gpp_bad,
                  color: _trustColor,
                  size: 32,
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        _trusted ? 'Trusted' : 'Not trusted',
                        style: TextStyle(
                          fontSize: 18,
                          fontWeight: FontWeight.bold,
                          color: _trustColor,
                        ),
                      ),
                      const SizedBox(height: 4),
                      Text(
                        'issuer: ${response.issuerAuthentication.name}'
                        '   device: ${response.deviceAuthentication.name}',
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
        ),
        const SizedBox(height: 8),
        if (response.docTypes.isNotEmpty)
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 4),
            child: Text(
              'docTypes: ${response.docTypes.join(", ")}',
              style: const TextStyle(color: Colors.grey),
            ),
          ),
        const SizedBox(height: 12),
        for (final entry in _decodedNamespaces.entries)
          _NamespaceCard(
            namespace: entry.key,
            elements: entry.value as Map<String, dynamic>,
          ),
        if (response.errors != null) _ErrorsPanel(json: response.errors!),
        const SizedBox(height: 24),
        Center(
          child: ElevatedButton.icon(
            onPressed: onReset,
            icon: const Icon(Icons.refresh),
            label: const Text('Read another'),
          ),
        ),
        const SizedBox(height: 24),
      ],
    );
  }
}

class _NamespaceCard extends StatelessWidget {
  const _NamespaceCard({required this.namespace, required this.elements});
  final String namespace;
  final Map<String, dynamic> elements;

  @override
  Widget build(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              namespace,
              style: const TextStyle(
                fontFamily: 'monospace',
                fontWeight: FontWeight.bold,
                color: Colors.blueGrey,
              ),
            ),
            const Divider(),
            for (final entry in elements.entries)
              _ElementRow(name: entry.key, value: entry.value),
          ],
        ),
      ),
    );
  }
}

class _ElementRow extends StatelessWidget {
  const _ElementRow({required this.name, required this.value});
  final String name;
  final dynamic value;

  @override
  Widget build(BuildContext context) {
    // Portrait special case: render as image when the value is a list of
    // byte-range integers (JPEG bytes serialized as a JSON array).
    if (name == 'portrait') {
      final bytes = _tryBytes(value);
      if (bytes != null) {
        return Padding(
          padding: const EdgeInsets.symmetric(vertical: 6),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(name, style: const TextStyle(fontWeight: FontWeight.w500)),
              const SizedBox(height: 4),
              Image.memory(bytes, height: 160),
            ],
          ),
        );
      }
    }
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 6),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Expanded(
            flex: 4,
            child: Text(
              name,
              style: const TextStyle(fontWeight: FontWeight.w500),
            ),
          ),
          Expanded(
            flex: 6,
            child: Text(
              _format(value),
              style: const TextStyle(fontFamily: 'monospace'),
            ),
          ),
        ],
      ),
    );
  }

  /// Treat a `List<int>` whose values are all in `[0, 255]` as binary data.
  /// Rust serializes `MDocItem::Array(Integer)` as a JSON array of numbers,
  /// which arrives in Dart as `List<dynamic>` after `jsonDecode`.
  static Uint8List? _tryBytes(dynamic v) {
    if (v is! List) return null;
    final out = Uint8List(v.length);
    for (var i = 0; i < v.length; i++) {
      final n = v[i];
      if (n is! int || n < 0 || n > 255) return null;
      out[i] = n;
    }
    return out;
  }

  /// Lightweight stringification for any JSON-decoded value. Maps and lists
  /// are rendered one level deep for the row preview; nested complex
  /// structures still show up but as `{...}` / `[...]`.
  static String _format(dynamic v) {
    if (v == null) return '';
    if (v is String) return v;
    if (v is bool || v is num) return v.toString();
    if (v is Map) {
      return v.entries.map((e) => '${e.key}=${_format(e.value)}').join(', ');
    }
    if (v is List) {
      return '[${v.map(_format).join(', ')}]';
    }
    return v.toString();
  }
}

class _ErrorsPanel extends StatelessWidget {
  const _ErrorsPanel({required this.json});
  final String json;

  @override
  Widget build(BuildContext context) {
    Map<String, dynamic>? decoded;
    try {
      decoded = jsonDecode(json) as Map<String, dynamic>;
    } catch (_) {
      // fall through; render raw
    }
    return Card(
      color: Colors.orange.shade50,
      child: ExpansionTile(
        leading: const Icon(Icons.warning_amber, color: Colors.orange),
        title: const Text('Errors / warnings reported by reader'),
        children: [
          Padding(
            padding: const EdgeInsets.all(12),
            child: SelectableText(
              decoded != null
                  ? const JsonEncoder.withIndent('  ').convert(decoded)
                  : json,
              style: const TextStyle(fontFamily: 'monospace', fontSize: 12),
            ),
          ),
        ],
      ),
    );
  }
}
