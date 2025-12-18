import 'package:flutter/material.dart';
import 'package:flutter/services.dart' show rootBundle;
import 'package:permission_handler/permission_handler.dart';
import 'package:sprucekit_mobile/sprucekit_mobile.dart';

import '../widgets/selective_disclosure_fields.dart';

/// Demo screen for the OID4VP (credential presentation) flow
class Oid4vpDemo extends StatefulWidget {
  const Oid4vpDemo({super.key});

  @override
  State<Oid4vpDemo> createState() => _Oid4vpDemoState();
}

class _Oid4vpDemoState extends State<Oid4vpDemo> {
  final _oid4vp = Oid4vp();
  final _credentialPack = CredentialPack();

  String _status = 'Ready';
  List<PresentableCredentialData> _credentials = [];
  PermissionRequestInfo? _requestInfo;
  List<SelectiveDisclosureFieldData> _requestedFields = [];
  int? _selectedCredentialIndex;
  Set<String> _selectedFields = {};
  bool _isLoading = false;
  bool _showScanner = false;
  bool _cameraGranted = false;
  String? _testPackId;

  // You would typically get these from your app configuration
  final _trustedDids = <String>[];
  final _keyId = 'presentation_key';

  // Context map for JSON-LD credentials (required for credential parsing)
  static const _contextMap = <String, String>{
    'https://examples.vcplayground.org/contexts/alumni/v2.json': '''
{
  "@context": {
    "@protected": true,
    "alumniOf": "https://schema.org/alumniOf",
    "name": "https://schema.org/name",
    "description": "https://schema.org/description",
    "identifier": "https://schema.org/identifier",
    "image": {
      "@id": "https://schema.org/image",
      "@type": "@id"
    },
    "AlumniCredential": "https://examples.vcplayground.org/contexts/alumni/vocab#AlumniCredential"
  }
}
''',
  };

  @override
  void initState() {
    super.initState();
    _checkCameraPermission();
  }

  @override
  void dispose() {
    _oid4vp.cancel();
    super.dispose();
  }

  Future<void> _checkCameraPermission() async {
    final status = await Permission.camera.status;
    setState(() {
      _cameraGranted = status.isGranted;
    });
  }

  Future<void> _requestCameraPermission() async {
    final status = await Permission.camera.request();
    setState(() {
      _cameraGranted = status.isGranted;
    });
  }

  void _openScanner() {
    setState(() {
      _showScanner = true;
    });
  }

  void _closeScanner() {
    setState(() {
      _showScanner = false;
    });
  }

  void _handleScannedUrl(String url) {
    _closeScanner();

    if (!url.startsWith('openid4vp://')) {
      setState(() {
        _status = 'Invalid URL. Must start with openid4vp://';
      });
      return;
    }

    _handleAuthorizationUrl(url);
  }

  Future<void> _handleAuthorizationUrl(String url) async {
    setState(() {
      _isLoading = true;
      _status = 'Loading credential from asset...';
    });

    try {
      // Load credential from external asset file
      final testCredential = await rootBundle.loadString(
        'assets/test_credential.json',
      );

      setState(() {
        _status = 'Processing authorization request...';
      });

      // Create a pack with the test credential
      _testPackId = await _credentialPack.createPack();
      final addResult = await _credentialPack.addRawCredential(
        _testPackId!,
        testCredential,
      );

      if (addResult is AddCredentialError) {
        setState(() {
          _status = 'Error adding test credential: ${addResult.message}';
          _isLoading = false;
        });
        return;
      }

      // Create holder with the test credential pack
      final createResult = await _oid4vp.createHolder(
        [_testPackId!],
        _trustedDids,
        _keyId,
        _contextMap,
      );

      if (createResult is Oid4vpError) {
        setState(() {
          _status = 'Error creating holder: ${createResult.message}';
          _isLoading = false;
        });
        return;
      }

      // Handle authorization request
      final authResult = await _oid4vp.handleAuthorizationRequest(url);

      if (authResult is HandleAuthRequestError) {
        setState(() {
          _status = 'Error: ${authResult.message}';
          _isLoading = false;
        });
        return;
      }

      if (authResult is HandleAuthRequestSuccess) {
        setState(() {
          _credentials = authResult.credentials;
          _requestInfo = authResult.info;
          _status = 'Found ${_credentials.length} matching credential(s)';
          _isLoading = false;
        });

        // If only one credential, auto-select it
        if (_credentials.length == 1) {
          _selectCredential(0);
        }
      }
    } catch (e) {
      setState(() {
        _status = 'Error: $e';
        _isLoading = false;
      });
    }
  }

  Future<void> _selectCredential(int index) async {
    setState(() {
      _selectedCredentialIndex = index;
    });

    try {
      final fields = await _oid4vp.getRequestedFields(index);
      // Debug: print fields to see what SDK returns
      debugPrint('OID4VP: Received ${fields.length} fields from SDK:');
      for (final f in fields) {
        debugPrint('  - name: ${f.name}, path: ${f.path}, id: ${f.id}');
      }
      // Convert to generic SelectiveDisclosureFieldData
      final genericFields = fields
          .map(SelectiveDisclosureFieldData.fromRequestedField)
          .toList();
      setState(() {
        _requestedFields = genericFields;
        // Pre-select required fields
        _selectedFields = genericFields
            .where((f) => f.required)
            .map((f) => f.id)
            .toSet();
      });
    } catch (e) {
      setState(() {
        _status = 'Error getting fields: $e';
      });
    }
  }

  Future<void> _submitPresentation() async {
    if (_selectedCredentialIndex == null) return;

    setState(() {
      _isLoading = true;
      _status = 'Submitting presentation...';
    });

    try {
      final result = await _oid4vp.submitResponse(
        [_selectedCredentialIndex!],
        [_selectedFields.toList()],
        ResponseOptions(
          shouldStripQuotes: false,
          forceArraySerialization: false,
          removeVpPathPrefix: false,
        ),
      );

      if (result is Oid4vpSuccess) {
        setState(() {
          _status = 'Presentation submitted successfully!';
          _isLoading = false;
        });
        _reset();
      } else if (result is Oid4vpError) {
        setState(() {
          _status = 'Error: ${result.message}';
          _isLoading = false;
        });
      }
    } catch (e) {
      setState(() {
        _status = 'Error: $e';
        _isLoading = false;
      });
    }
  }

  void _reset() {
    _oid4vp.cancel();
    setState(() {
      _credentials = [];
      _requestInfo = null;
      _requestedFields = [];
      _selectedCredentialIndex = null;
      _selectedFields = {};
    });
  }

  @override
  Widget build(BuildContext context) {
    if (_showScanner) {
      return Scaffold(
        body: SpruceScanner(
          type: ScannerType.qrCode,
          title: 'Scan Verifier QR',
          subtitle: 'Scan an openid4vp:// QR code',
          onRead: _handleScannedUrl,
          onCancel: _closeScanner,
        ),
      );
    }

    return Scaffold(
      appBar: AppBar(
        title: const Text('OID4VP Demo'),
        actions: [
          if (_credentials.isNotEmpty)
            IconButton(
              icon: const Icon(Icons.refresh),
              onPressed: _reset,
              tooltip: 'Reset',
            ),
        ],
      ),
      body: _isLoading
          ? const Center(child: CircularProgressIndicator())
          : _buildContent(),
    );
  }

  Widget _buildContent() {
    if (_credentials.isEmpty) {
      return _buildInitialView();
    }

    if (_selectedCredentialIndex == null) {
      return _buildCredentialSelector();
    }

    return _buildFieldSelector();
  }

  Widget _buildInitialView() {
    return SingleChildScrollView(
      padding: const EdgeInsets.all(16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          _buildCameraPermissionCard(),
          const SizedBox(height: 16),
          Card(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    'OID4VP Presentation',
                    style: Theme.of(context).textTheme.titleMedium,
                  ),
                  const SizedBox(height: 8),
                  const Text(
                    'Scan a QR code from a verifier that contains an '
                    'openid4vp:// URL to start the presentation flow.\n\n'
                    'A test AlumniCredential will be used automatically.',
                  ),
                  const SizedBox(height: 16),
                  ElevatedButton.icon(
                    onPressed: _cameraGranted
                        ? _openScanner
                        : _requestCameraPermission,
                    icon: const Icon(Icons.qr_code_scanner),
                    label: Text(
                      _cameraGranted
                          ? 'Scan QR Code'
                          : 'Grant Camera Permission',
                    ),
                  ),
                ],
              ),
            ),
          ),
          const SizedBox(height: 16),
          _buildStatusCard(),
        ],
      ),
    );
  }

  Widget _buildCameraPermissionCard() {
    return Card(
      color: _cameraGranted ? Colors.green.shade50 : Colors.orange.shade50,
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Row(
          children: [
            Icon(
              _cameraGranted ? Icons.camera_alt : Icons.camera_alt_outlined,
              color: _cameraGranted
                  ? Colors.green.shade700
                  : Colors.orange.shade700,
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    'Camera',
                    style: TextStyle(
                      fontWeight: FontWeight.bold,
                      color: _cameraGranted
                          ? Colors.green.shade800
                          : Colors.orange.shade800,
                    ),
                  ),
                  Text(
                    _cameraGranted ? 'Ready' : 'Permission required',
                    style: TextStyle(
                      fontSize: 12,
                      color: _cameraGranted
                          ? Colors.green.shade600
                          : Colors.orange.shade600,
                    ),
                  ),
                ],
              ),
            ),
            if (!_cameraGranted)
              TextButton(
                onPressed: _requestCameraPermission,
                child: const Text('Grant'),
              ),
          ],
        ),
      ),
    );
  }

  Widget _buildStatusCard() {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('Status', style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 8),
            Text(_status),
          ],
        ),
      ),
    );
  }

  Widget _buildCredentialSelector() {
    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        if (_requestInfo != null) ...[
          Card(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    'Verifier Request',
                    style: Theme.of(context).textTheme.titleMedium,
                  ),
                  if (_requestInfo!.domain != null)
                    Text('Domain: ${_requestInfo!.domain}'),
                  if (_requestInfo!.purpose != null)
                    Text('Purpose: ${_requestInfo!.purpose}'),
                ],
              ),
            ),
          ),
          const SizedBox(height: 16),
        ],
        Text(
          'Select a credential to present:',
          style: Theme.of(context).textTheme.titleMedium,
        ),
        const SizedBox(height: 8),
        ..._credentials.asMap().entries.map((entry) {
          final index = entry.key;
          final cred = entry.value;
          return Card(
            child: ListTile(
              leading: const Icon(Icons.badge),
              title: Text('Credential ${index + 1}'),
              subtitle: Text(
                'ID: ${cred.credentialId}\n'
                'Selective Disclosure: ${cred.selectiveDisclosable ? 'Yes' : 'No'}',
              ),
              trailing: const Icon(Icons.chevron_right),
              onTap: () => _selectCredential(index),
            ),
          );
        }),
      ],
    );
  }

  Widget _buildFieldSelector() {
    return Column(
      children: [
        Expanded(
          child: SelectiveDisclosureFieldsList(
            fields: _requestedFields,
            selectedIds: _selectedFields,
            onFieldToggled: (id, isSelected) {
              setState(() {
                if (isSelected) {
                  _selectedFields.add(id);
                } else {
                  _selectedFields.remove(id);
                }
              });
            },
          ),
        ),
        Padding(
          padding: const EdgeInsets.all(16),
          child: Row(
            children: [
              Expanded(
                child: OutlinedButton(
                  onPressed: () {
                    setState(() {
                      _selectedCredentialIndex = null;
                      _requestedFields = [];
                      _selectedFields = {};
                    });
                  },
                  child: const Text('Back'),
                ),
              ),
              const SizedBox(width: 16),
              Expanded(
                child: ElevatedButton(
                  onPressed: _selectedFields.isNotEmpty
                      ? _submitPresentation
                      : null,
                  child: const Text('Submit'),
                ),
              ),
            ],
          ),
        ),
      ],
    );
  }
}
