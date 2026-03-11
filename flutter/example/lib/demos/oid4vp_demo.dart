import 'package:flutter/material.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:sprucekit_mobile/sprucekit_mobile.dart';

import '../widgets/selective_disclosure_fields.dart';

enum _ScanPurpose { issuance, presentation }

/// Demo screen for the OID4VP (credential presentation) flow.
///
/// Two-step flow:
///   1. Issue a credential via OID4VCI (scan or paste an offer URL)
///   2. Present the issued credential via OID4VP (scan a verifier QR code)
class Oid4vpDemo extends StatefulWidget {
  const Oid4vpDemo({super.key});

  @override
  State<Oid4vpDemo> createState() => _Oid4vpDemoState();
}

class _Oid4vpDemoState extends State<Oid4vpDemo> {
  // --- SDK instances ---
  final _oid4vci = Oid4vci();
  final _oid4vp = Oid4vp();
  final _credentialPack = CredentialPack();

  // --- Step 1: Issuance state ---
  final _offerController = TextEditingController();
  final _keyId = 'oid4vp_demo_key';
  bool _issuanceLoading = false;
  String? _issuanceError;
  List<IssuedCredential> _issuedCredentials = [];

  // --- Step 2: Presentation state ---
  String _status = 'Ready';
  List<PresentableCredentialData> _credentials = [];
  PermissionRequestInfo? _requestInfo;
  List<SelectiveDisclosureFieldData> _requestedFields = [];
  int? _selectedCredentialIndex;
  Set<String> _selectedFields = {};
  bool _presentationLoading = false;
  _ScanPurpose? _scanPurpose;
  bool _cameraGranted = false;
  String? _packId;

  final _trustedDids = <String>[];

  @override
  void initState() {
    super.initState();
    _checkCameraPermission();
  }

  @override
  void dispose() {
    _offerController.dispose();
    _oid4vp.cancel();
    super.dispose();
  }

  // ──────────────────────────────────────────────
  // Step 1: OID4VCI Issuance
  // ──────────────────────────────────────────────

  Future<void> _runIssuance() async {
    FocusScope.of(context).unfocus();

    final offer = _offerController.text.trim();
    if (offer.isEmpty) {
      setState(() => _issuanceError = 'Please enter a credential offer URL');
      return;
    }

    setState(() {
      _issuanceLoading = true;
      _issuedCredentials = [];
      _issuanceError = null;
    });

    try {
      final result = await _oid4vci.runIssuance(
        offer,
        'skit-demo-wallet',
        'https://spruceid.com',
        _keyId,
        DidMethod.jwk,
        null,
      );

      setState(() {
        _issuanceLoading = false;
        switch (result) {
          case Oid4vciSuccess(:final credentials):
            _issuedCredentials = credentials;
          case Oid4vciError(:final message):
            _issuanceError = message;
        }
      });
    } catch (e) {
      setState(() {
        _issuanceLoading = false;
        _issuanceError = e.toString();
      });
    }
  }

  // ──────────────────────────────────────────────
  // Step 2: OID4VP Presentation
  // ──────────────────────────────────────────────

  Future<void> _checkCameraPermission() async {
    final status = await Permission.camera.status;
    setState(() => _cameraGranted = status.isGranted);
  }

  Future<void> _requestCameraPermission() async {
    final status = await Permission.camera.request();
    setState(() => _cameraGranted = status.isGranted);
  }

  void _openScanner(_ScanPurpose purpose) {
    setState(() => _scanPurpose = purpose);
  }

  void _closeScanner() => setState(() => _scanPurpose = null);

  void _handleScannedUrl(String url) {
    final purpose = _scanPurpose;
    _closeScanner();

    if (purpose == _ScanPurpose.issuance) {
      _offerController.text = url;
      _runIssuance();
      return;
    }

    if (!url.startsWith('openid4vp://')) {
      setState(() => _status = 'Invalid URL. Must start with openid4vp://');
      return;
    }

    _handleAuthorizationUrl(url);
  }

  Future<void> _handleAuthorizationUrl(String url) async {
    setState(() {
      _presentationLoading = true;
      _status = 'Adding issued credential to pack...';
    });

    try {
      // Add the first issued credential to a pack
      final credential = _issuedCredentials.first;
      _packId = await _credentialPack.createPack();
      final addResult = await _credentialPack.addAnyFormat(
        _packId!,
        credential.payload,
        '',
      );

      if (addResult is AddCredentialError) {
        setState(() {
          _status = 'Error adding credential: ${addResult.message}';
          _presentationLoading = false;
        });
        return;
      }

      setState(() => _status = 'Processing authorization request...');

      // Create holder with the credential pack
      final createResult = await _oid4vp.createHolder(
        [_packId!],
        _trustedDids,
        _keyId,
        null,
      );

      if (createResult is Oid4vpError) {
        setState(() {
          _status = 'Error creating holder: ${createResult.message}';
          _presentationLoading = false;
        });
        return;
      }

      // Handle authorization request
      final authResult = await _oid4vp.handleAuthorizationRequest(url);

      if (authResult is HandleAuthRequestError) {
        setState(() {
          _status = 'Error: ${authResult.message}';
          _presentationLoading = false;
        });
        return;
      }

      if (authResult is HandleAuthRequestSuccess) {
        setState(() {
          _credentials = authResult.credentials;
          _requestInfo = authResult.info;
          _status = 'Found ${_credentials.length} matching credential(s)';
          _presentationLoading = false;
        });

        if (_credentials.length == 1) {
          _selectCredential(0);
        }
      }
    } catch (e) {
      setState(() {
        _status = 'Error: $e';
        _presentationLoading = false;
      });
    }
  }

  Future<void> _selectCredential(int index) async {
    setState(() => _selectedCredentialIndex = index);

    try {
      final fields = await _oid4vp.getRequestedFields(index);
      debugPrint('OID4VP: Received ${fields.length} fields from SDK:');
      for (final f in fields) {
        debugPrint('  - name: ${f.name}, path: ${f.path}, id: ${f.id}');
      }
      final genericFields = fields
          .map(SelectiveDisclosureFieldData.fromRequestedField)
          .toList();
      setState(() {
        _requestedFields = genericFields;
        _selectedFields = genericFields
            .where((f) => f.required)
            .map((f) => f.id)
            .toSet();
      });
    } catch (e) {
      setState(() => _status = 'Error getting fields: $e');
    }
  }

  Future<void> _submitPresentation() async {
    if (_selectedCredentialIndex == null) return;

    setState(() {
      _presentationLoading = true;
      _status = 'Submitting presentation...';
    });

    try {
      final result = await _oid4vp.submitResponse(
        [_selectedCredentialIndex!],
        [_selectedFields.toList()],
        ResponseOptions(forceArraySerialization: false),
      );

      if (result is Oid4vpSuccess) {
        setState(() {
          _status = 'Presentation submitted successfully!';
          _presentationLoading = false;
        });
        _resetPresentation();
      } else if (result is Oid4vpError) {
        setState(() {
          _status = 'Error: ${result.message}';
          _presentationLoading = false;
        });
      }
    } catch (e) {
      setState(() {
        _status = 'Error: $e';
        _presentationLoading = false;
      });
    }
  }

  void _resetPresentation() {
    _oid4vp.cancel();
    setState(() {
      _credentials = [];
      _requestInfo = null;
      _requestedFields = [];
      _selectedCredentialIndex = null;
      _selectedFields = {};
    });
  }

  void _resetAll() {
    _resetPresentation();
    setState(() {
      _issuedCredentials = [];
      _issuanceError = null;
      _status = 'Ready';
    });
  }

  // ──────────────────────────────────────────────
  // UI
  // ──────────────────────────────────────────────

  @override
  Widget build(BuildContext context) {
    if (_scanPurpose != null) {
      final isIssuance = _scanPurpose == _ScanPurpose.issuance;
      return Scaffold(
        body: SpruceScanner(
          type: ScannerType.qrCode,
          title: isIssuance ? 'Scan Issuer QR' : 'Scan Verifier QR',
          subtitle: isIssuance
              ? 'Scan an openid-credential-offer:// QR code'
              : 'Scan an openid4vp:// QR code',
          onRead: _handleScannedUrl,
          onCancel: _closeScanner,
        ),
      );
    }

    return Scaffold(
      appBar: AppBar(
        title: const Text('OID4VP Demo'),
        actions: [
          if (_issuedCredentials.isNotEmpty)
            IconButton(
              icon: const Icon(Icons.refresh),
              onPressed: _resetAll,
              tooltip: 'Start over',
            ),
        ],
      ),
      body: _buildBody(),
    );
  }

  Widget _buildBody() {
    // Step 2b: Presentation in progress — show credential/field selection
    if (_credentials.isNotEmpty || _presentationLoading) {
      if (_presentationLoading) {
        return const Center(child: CircularProgressIndicator());
      }
      if (_selectedCredentialIndex == null) {
        return _buildCredentialSelector();
      }
      return _buildFieldSelector();
    }

    // Step 1 or Step 2a
    return SingleChildScrollView(
      padding: const EdgeInsets.all(16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          _buildStep1Card(),
          const SizedBox(height: 16),
          if (_issuedCredentials.isNotEmpty) ...[
            _buildStep2Card(),
            const SizedBox(height: 16),
          ],
          _buildStatusCard(),
        ],
      ),
    );
  }

  // --- Step 1: Issuance card ---
  Widget _buildStep1Card() {
    final isDone = _issuedCredentials.isNotEmpty;

    return Card(
      color: isDone ? Colors.green.shade50 : null,
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(
                  isDone ? Icons.check_circle : Icons.looks_one,
                  color: isDone ? Colors.green.shade700 : null,
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    'Issue Credential (OID4VCI)',
                    style: Theme.of(context).textTheme.titleMedium,
                  ),
                ),
              ],
            ),
            const SizedBox(height: 8),
            if (isDone) ...[
              Text(
                'Issued ${_issuedCredentials.length} credential(s) '
                '(${_issuedCredentials.first.format})',
                style: TextStyle(color: Colors.green.shade800),
              ),
            ] else ...[
              const Text(
                'Paste an OID4VCI offer URL from Animo Playground '
                'to issue a DC+SD-JWT PID credential to this wallet.',
              ),
              const SizedBox(height: 12),
              TextField(
                controller: _offerController,
                decoration: const InputDecoration(
                  labelText: 'Credential Offer URL',
                  hintText: 'openid-credential-offer://...',
                  border: OutlineInputBorder(),
                ),
                maxLines: 3,
              ),
              const SizedBox(height: 12),
              Row(
                children: [
                  Expanded(
                    child: ElevatedButton(
                      onPressed: _issuanceLoading ? null : _runIssuance,
                      child: _issuanceLoading
                          ? const SizedBox(
                              height: 20,
                              width: 20,
                              child: CircularProgressIndicator(strokeWidth: 2),
                            )
                          : const Text('Issue Credential'),
                    ),
                  ),
                  const SizedBox(width: 8),
                  ElevatedButton.icon(
                    onPressed: _issuanceLoading
                        ? null
                        : () => _openScanner(_ScanPurpose.issuance),
                    icon: const Icon(Icons.qr_code_scanner),
                    label: const Text('Scan'),
                  ),
                ],
              ),
              if (_issuanceError != null) ...[
                const SizedBox(height: 8),
                Text(
                  _issuanceError!,
                  style: TextStyle(color: Colors.red.shade700, fontSize: 13),
                ),
              ],
            ],
          ],
        ),
      ),
    );
  }

  // --- Step 2: Presentation card ---
  Widget _buildStep2Card() {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                const Icon(Icons.looks_two),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    'Present Credential (OID4VP)',
                    style: Theme.of(context).textTheme.titleMedium,
                  ),
                ),
              ],
            ),
            const SizedBox(height: 8),
            const Text(
              'Scan a QR code from a verifier that contains an '
              'openid4vp:// URL to present your issued credential.',
            ),
            const SizedBox(height: 12),
            _buildCameraPermissionCard(),
            const SizedBox(height: 8),
            ElevatedButton.icon(
              onPressed: _cameraGranted
                  ? () => _openScanner(_ScanPurpose.presentation)
                  : _requestCameraPermission,
              icon: const Icon(Icons.qr_code_scanner),
              label: Text(
                _cameraGranted ? 'Scan Verifier QR' : 'Grant Camera Permission',
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildCameraPermissionCard() {
    return Row(
      children: [
        Icon(
          _cameraGranted ? Icons.camera_alt : Icons.camera_alt_outlined,
          size: 18,
          color: _cameraGranted
              ? Colors.green.shade700
              : Colors.orange.shade700,
        ),
        const SizedBox(width: 8),
        Text(
          _cameraGranted ? 'Camera ready' : 'Camera permission required',
          style: TextStyle(
            fontSize: 13,
            color: _cameraGranted
                ? Colors.green.shade700
                : Colors.orange.shade700,
          ),
        ),
      ],
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
    return SafeArea(
      child: Column(
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
      ),
    );
  }
}
