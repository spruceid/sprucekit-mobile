import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_web_auth_2/flutter_web_auth_2.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:sprucekit_mobile/sprucekit_mobile.dart';

const _authCodeRedirectUrl = 'example-oid4vci-redirect://callback';
const _authCodeRedirectScheme = 'example-oid4vci-redirect';

class Oid4vciDemo extends StatefulWidget {
  const Oid4vciDemo({super.key});

  @override
  State<Oid4vciDemo> createState() => _Oid4vciDemoState();
}

class _Oid4vciDemoState extends State<Oid4vciDemo> {
  final _api = Oid4vci();
  final _offerController = TextEditingController();
  final _clientIdController = TextEditingController(text: 'skit-demo-wallet');
  final _keyIdController = TextEditingController(text: 'default-signing-key');
  final _pinController = TextEditingController();

  bool _loading = false;
  bool _showScanner = false;
  bool _cameraGranted = false;
  bool _showPinDialog = false;
  List<IssuedCredential> _issuedCredentials = [];
  String? _error;
  ParsedOfferMetadata? _parsedMetadata;
  String? _sessionId;
  TxCodeMetadata? _txCodeMetadata;
  Oid4vciCompatibilityMode _compatibilityMode = Oid4vciCompatibilityMode.auto;

  @override
  void initState() {
    super.initState();
    _checkCameraPermission();
  }

  @override
  void dispose() {
    _offerController.dispose();
    _clientIdController.dispose();
    _keyIdController.dispose();
    _pinController.dispose();
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
    _startIssuance();
  }

  Future<void> _startIssuance() async {
    FocusScope.of(context).unfocus();

    final offer = _offerController.text.trim();
    if (offer.isEmpty) {
      setState(() => _error = 'Please enter a credential offer URL');
      return;
    }

    setState(() {
      _loading = true;
      _issuedCredentials = [];
      _parsedMetadata = null;
      _sessionId = null;
      _txCodeMetadata = null;
      _error = null;
    });

    try {
      final metadata = await _api.parseOffer(offer, _compatibilityMode);
      setState(() => _parsedMetadata = metadata);

      switch (metadata.grantType) {
        case GrantType.preAuthCodeNoTxCode:
          await _runNoAuthFlow(offer);
        case GrantType.preAuthCodeWithTxCode:
          await _runTxCodeFlow(offer);
        case GrantType.authorizationCode:
          await _runAuthCodeFlow(offer);
      }
    } catch (e) {
      setState(() {
        _loading = false;
        _error = e.toString();
      });
    }
  }

  Future<void> _runNoAuthFlow(String offer) async {
    final result = await _api.runIssuance(
      offer,
      _clientIdController.text,
      _authCodeRedirectUrl,
      _keyIdController.text,
      DidMethod.jwk,
      null,
      _compatibilityMode,
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
  }

  Future<void> _runTxCodeFlow(String offer) async {
    final session = await _api.acceptOffer(
      offer,
      _clientIdController.text,
      _keyIdController.text,
      DidMethod.jwk,
      null,
      _compatibilityMode,
    );
    setState(() {
      _loading = false;
      _sessionId = session.sessionId;
      _txCodeMetadata = session.metadata.txCode;
      _showPinDialog = true;
    });
  }

  Future<void> _runAuthCodeFlow(String offer) async {
    final session = await _api.acceptOffer(
      offer,
      _clientIdController.text,
      _keyIdController.text,
      DidMethod.jwk,
      _authCodeRedirectUrl,
      _compatibilityMode,
    );
    final sessionId = session.sessionId;

    final authUrl = await _api.buildAuthorizationUrl(sessionId);
    if (authUrl == null) {
      await _api.releaseSession(sessionId);
      setState(() {
        _loading = false;
        _error = 'Failed to build authorization URL';
      });
      return;
    }

    Uri? redirect;
    try {
      final resultUrl = await FlutterWebAuth2.authenticate(
        url: authUrl,
        callbackUrlScheme: _authCodeRedirectScheme,
      );
      redirect = Uri.parse(resultUrl);
    } catch (_) {
      redirect = null;
    }

    if (redirect == null) {
      await _api.releaseSession(sessionId);
      setState(() {
        _loading = false;
        _error = 'Sign-in cancelled or browser error';
      });
      return;
    }

    final errorParam = redirect.queryParameters['error'];
    if (errorParam != null) {
      await _api.releaseSession(sessionId);
      setState(() {
        _loading = false;
        _error = 'Authorization error: $errorParam';
      });
      return;
    }

    final code = redirect.queryParameters['code'];
    if (code == null || code.isEmpty) {
      await _api.releaseSession(sessionId);
      setState(() {
        _loading = false;
        _error = 'Missing authorization code in callback';
      });
      return;
    }

    final result = await _api.continueWithAuthorizationCode(sessionId, code);
    setState(() {
      _loading = false;
      switch (result) {
        case Oid4vciSuccess(:final credentials):
          _issuedCredentials = credentials;
        case Oid4vciError(:final message):
          _error = message;
      }
    });
  }

  Future<void> _submitPin(String pin) async {
    final sessionId = _sessionId;
    if (sessionId == null) return;
    setState(() {
      _showPinDialog = false;
      _loading = true;
    });
    try {
      final result = await _api.continueWithTxCode(sessionId, pin);
      setState(() {
        _loading = false;
        _sessionId = null;
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
        _sessionId = null;
        _error = e.toString();
      });
    }
  }

  Future<void> _cancelPin() async {
    final sessionId = _sessionId;
    if (sessionId != null) {
      try {
        await _api.releaseSession(sessionId);
      } catch (_) {
        // best effort; don't surface
      }
    }
    setState(() {
      _showPinDialog = false;
      _loading = false;
      _sessionId = null;
      _txCodeMetadata = null;
      _error = 'PIN entry cancelled';
    });
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
              controller: _keyIdController,
              decoration: const InputDecoration(
                labelText: 'Key ID',
                border: OutlineInputBorder(),
              ),
            ),
            const SizedBox(height: 16),
            DropdownButtonFormField<Oid4vciCompatibilityMode>(
              initialValue: _compatibilityMode,
              decoration: const InputDecoration(
                labelText: 'Compatibility mode',
                border: OutlineInputBorder(),
              ),
              items: const [
                DropdownMenuItem(
                  value: Oid4vciCompatibilityMode.auto,
                  child: Text('Auto (v1, fall back to legacy on 400)'),
                ),
                DropdownMenuItem(
                  value: Oid4vciCompatibilityMode.v1,
                  child: Text('Force v1 (OID4VCI 1.0 final)'),
                ),
                DropdownMenuItem(
                  value: Oid4vciCompatibilityMode.legacy,
                  child: Text('Force legacy (draft 13)'),
                ),
              ],
              onChanged: _loading
                  ? null
                  : (mode) => setState(
                      () => _compatibilityMode =
                          mode ?? Oid4vciCompatibilityMode.auto,
                    ),
            ),
            const SizedBox(height: 24),
            Row(
              children: [
                Expanded(
                  child: ElevatedButton(
                    onPressed: _loading ? null : _startIssuance,
                    child: _loading
                        ? const SizedBox(
                            height: 20,
                            width: 20,
                            child: CircularProgressIndicator(strokeWidth: 2),
                          )
                        : const Text('Start Issuance'),
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
                  label: Text(_cameraGranted ? 'Scan QR' : 'Camera'),
                ),
              ],
            ),
            const SizedBox(height: 24),
            if (_parsedMetadata != null) ...[
              const Text(
                'Parsed Offer Metadata:',
                style: TextStyle(fontWeight: FontWeight.bold),
              ),
              Text(
                'Issuer: ${_parsedMetadata!.issuerDisplayName ?? "(no display name)"}',
              ),
              Text('Issuer ID: ${_parsedMetadata!.issuerId}'),
              Text(
                'Credentials: ${_parsedMetadata!.credentialConfigurationIds.join(", ")}',
              ),
              Text('Grant: ${_parsedMetadata!.grantType.name}'),
              if (_parsedMetadata!.txCode != null)
                Text(
                  'Tx Code: inputMode=${_parsedMetadata!.txCode!.inputMode?.name ?? "(default numeric)"}, '
                  'length=${_parsedMetadata!.txCode!.length ?? "(none)"}, '
                  'description=${_parsedMetadata!.txCode!.description ?? "(none)"}',
                ),
              const SizedBox(height: 16),
            ],
            if (_showPinDialog) ...[
              Card(
                color: Colors.amber.shade50,
                child: Padding(
                  padding: const EdgeInsets.all(16),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      const Text(
                        'Enter PIN',
                        style: TextStyle(
                          fontWeight: FontWeight.bold,
                          fontSize: 18,
                        ),
                      ),
                      const SizedBox(height: 4),
                      Text(
                        _txCodeMetadata?.description ??
                            'Enter the PIN provided with the offer.',
                      ),
                      if (_txCodeMetadata?.length != null)
                        Text(
                          'Length hint: ${_txCodeMetadata!.length}',
                          style: const TextStyle(
                            fontSize: 12,
                            color: Colors.grey,
                          ),
                        ),
                      if (_txCodeMetadata?.inputMode != null)
                        Text(
                          'Input mode: ${_txCodeMetadata!.inputMode!.name}',
                          style: const TextStyle(
                            fontSize: 12,
                            color: Colors.grey,
                          ),
                        ),
                      const SizedBox(height: 12),
                      TextField(
                        controller: _pinController,
                        keyboardType:
                            _txCodeMetadata?.inputMode ==
                                TxCodeInputMode.numeric
                            ? TextInputType.number
                            : TextInputType.text,
                        decoration: const InputDecoration(
                          labelText: 'PIN',
                          border: OutlineInputBorder(),
                        ),
                      ),
                      const SizedBox(height: 12),
                      Row(
                        children: [
                          Expanded(
                            child: ElevatedButton(
                              onPressed: () {
                                final pin = _pinController.text;
                                _pinController.clear();
                                _submitPin(pin);
                              },
                              child: const Text('Submit'),
                            ),
                          ),
                          const SizedBox(width: 8),
                          TextButton(
                            onPressed: () {
                              _pinController.clear();
                              _cancelPin();
                            },
                            child: const Text('Cancel'),
                          ),
                        ],
                      ),
                    ],
                  ),
                ),
              ),
              const SizedBox(height: 16),
            ],
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
