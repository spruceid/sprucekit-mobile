import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:sprucekit_mobile/sprucekit_mobile.dart';

class Oid4vciDemo extends StatefulWidget {
  const Oid4vciDemo({super.key});

  @override
  State<Oid4vciDemo> createState() => _Oid4vciDemoState();
}

class _Oid4vciDemoState extends State<Oid4vciDemo> {
  final _api = Oid4vci();
  final _offerController = TextEditingController();
  final _clientIdController = TextEditingController(text: 'skit-demo-wallet');
  final _redirectUrlController = TextEditingController(
    text: 'https://spruceid.com',
  );
  final _keyIdController = TextEditingController(text: 'default-signing-key');

  bool _loading = false;
  bool _showScanner = false;
  bool _cameraGranted = false;
  List<IssuedCredential> _issuedCredentials = [];
  String? _error;

  @override
  void initState() {
    super.initState();
    _checkCameraPermission();
  }

  @override
  void dispose() {
    _offerController.dispose();
    _clientIdController.dispose();
    _redirectUrlController.dispose();
    _keyIdController.dispose();
    super.dispose();
  }

  Future<void> _checkCameraPermission() async {
    final status = await Permission.camera.status;
    setState(() => _cameraGranted = status.isGranted);
  }

  Future<void> _requestCameraPermission() async {
    final status = await Permission.camera.request();
    setState(() => _cameraGranted = status.isGranted);
  }

  void _openScanner() => setState(() => _showScanner = true);
  void _closeScanner() => setState(() => _showScanner = false);

  void _handleScannedUrl(String url) {
    _closeScanner();
    _offerController.text = url;
    _runIssuance();
  }

  Future<void> _runIssuance() async {
    FocusScope.of(context).unfocus();

    final offer = _offerController.text.trim();
    if (offer.isEmpty) {
      setState(() => _error = 'Please enter a credential offer URL');
      return;
    }

    setState(() {
      _loading = true;
      _issuedCredentials = [];
      _error = null;
    });

    try {
      final result = await _api.runIssuance(
        offer,
        _clientIdController.text,
        _redirectUrlController.text,
        _keyIdController.text,
        DidMethod.jwk,
        null,
      );

      setState(() {
        _loading = false;
        switch (result) {
          case Oid4vciSuccess(:final credentials):
            _issuedCredentials = credentials;
          case Oid4vciError(:final message):
            _error = message;
        }
      });
    } catch (e) {
      setState(() {
        _loading = false;
        _error = e.toString();
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    if (_showScanner) {
      return Scaffold(
        body: SpruceScanner(
          type: ScannerType.qrCode,
          title: 'Scan Issuer QR',
          subtitle: 'Scan an openid-credential-offer:// QR code',
          onRead: _handleScannedUrl,
          onCancel: _closeScanner,
        ),
      );
    }

    return Scaffold(
      appBar: AppBar(title: const Text('OID4VCI Issuance')),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            TextField(
              controller: _offerController,
              decoration: const InputDecoration(
                labelText: 'Credential Offer URL',
                hintText: 'openid-credential-offer://...',
                border: OutlineInputBorder(),
              ),
              maxLines: 3,
            ),
            const SizedBox(height: 16),
            TextField(
              controller: _clientIdController,
              decoration: const InputDecoration(
                labelText: 'Client ID',
                border: OutlineInputBorder(),
              ),
            ),
            const SizedBox(height: 16),
            TextField(
              controller: _redirectUrlController,
              decoration: const InputDecoration(
                labelText: 'Redirect URL',
                border: OutlineInputBorder(),
              ),
            ),
            const SizedBox(height: 16),
            TextField(
              controller: _keyIdController,
              decoration: const InputDecoration(
                labelText: 'Key ID',
                border: OutlineInputBorder(),
              ),
            ),
            const SizedBox(height: 24),
            Row(
              children: [
                Expanded(
                  child: ElevatedButton(
                    onPressed: _loading ? null : _runIssuance,
                    child: _loading
                        ? const SizedBox(
                            height: 20,
                            width: 20,
                            child: CircularProgressIndicator(strokeWidth: 2),
                          )
                        : const Text('Run Issuance'),
                  ),
                ),
                const SizedBox(width: 8),
                ElevatedButton.icon(
                  onPressed: _loading
                      ? null
                      : _cameraGranted
                      ? _openScanner
                      : _requestCameraPermission,
                  icon: const Icon(Icons.qr_code_scanner),
                  label: Text(_cameraGranted ? 'Scan' : 'Camera'),
                ),
              ],
            ),
            const SizedBox(height: 24),
            if (_error != null)
              Container(
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                  color: Colors.red.shade100,
                  borderRadius: BorderRadius.circular(8),
                ),
                child: Text(
                  _error!,
                  style: TextStyle(color: Colors.red.shade900),
                ),
              ),
            for (final (i, cred) in _issuedCredentials.indexed)
              Card(
                color: Colors.green.shade50,
                child: Padding(
                  padding: const EdgeInsets.all(12),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Row(
                        children: [
                          Expanded(
                            child: Text(
                              'Credential ${i + 1} (${cred.format})',
                              style: TextStyle(
                                fontWeight: FontWeight.bold,
                                color: Colors.green.shade900,
                              ),
                            ),
                          ),
                          IconButton(
                            icon: const Icon(Icons.copy, size: 20),
                            tooltip: 'Copy payload',
                            onPressed: () {
                              Clipboard.setData(
                                ClipboardData(text: cred.payload),
                              );
                              ScaffoldMessenger.of(context).showSnackBar(
                                const SnackBar(
                                  content: Text('Payload copied to clipboard'),
                                ),
                              );
                            },
                          ),
                        ],
                      ),
                      const SizedBox(height: 4),
                      Text(
                        cred.payload.length > 200
                            ? '${cred.payload.substring(0, 200)}...'
                            : cred.payload,
                        style: TextStyle(
                          fontSize: 12,
                          color: Colors.green.shade800,
                          fontFamily: 'monospace',
                        ),
                      ),
                    ],
                  ),
                ),
              ),
          ],
        ),
      ),
    );
  }
}
