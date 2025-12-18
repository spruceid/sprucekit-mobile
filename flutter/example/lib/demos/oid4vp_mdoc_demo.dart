import 'package:flutter/material.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:sprucekit_mobile/sprucekit_mobile.dart';

import '../widgets/selective_disclosure_fields.dart';

/// Demo screen for the OID4VP mDoc (ISO 18013-7) presentation flow
class Oid4vpMdocDemo extends StatefulWidget {
  const Oid4vpMdocDemo({super.key});

  @override
  State<Oid4vpMdocDemo> createState() => _Oid4vpMdocDemoState();
}

class _Oid4vpMdocDemoState extends State<Oid4vpMdocDemo> {
  final _oid4vpMdoc = Oid4vpMdoc();
  final _spruceUtils = SpruceUtils();

  String? _packId;
  bool _isGeneratingMdl = false;
  bool _cameraGranted = false;

  Oid4vpMdocRequestInfo? _requestInfo;
  int? _selectedMatchIndex;
  Set<String> _selectedFieldIds = {};

  String _status = 'Ready';
  bool _isLoading = false;
  bool _showScanner = false;

  @override
  void initState() {
    super.initState();
    _checkCameraPermission();
  }

  @override
  void dispose() {
    _oid4vpMdoc.cancel();
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

  Future<void> _generateMockMdl() async {
    setState(() {
      _isGeneratingMdl = true;
      _status = 'Generating test mDL...';
    });

    try {
      final result = await _spruceUtils.generateMockMdl('oid4vp_test_mdl');

      if (result is GenerateMockMdlSuccess) {
        setState(() {
          _packId = result.packId;
          _isGeneratingMdl = false;
          _status = 'Test mDL generated successfully';
        });
      } else if (result is GenerateMockMdlError) {
        setState(() {
          _status = 'Error: ${result.message}';
          _isGeneratingMdl = false;
        });
      }
    } catch (e) {
      setState(() {
        _status = 'Error generating mDL: $e';
        _isGeneratingMdl = false;
      });
    }
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

  Future<void> _handleScannedUrl(String url) async {
    _closeScanner();

    // Validate URL scheme
    if (!url.startsWith('mdoc-openid4vp://')) {
      setState(() {
        _status = 'Invalid URL. Must start with mdoc-openid4vp://';
      });
      return;
    }

    setState(() {
      _isLoading = true;
      _status = 'Initializing handler...';
    });

    try {
      // Initialize with the mDL pack
      final initResult = await _oid4vpMdoc.initialize([_packId!]);

      if (initResult is Oid4vpMdocError) {
        setState(() {
          _status = 'Error: ${initResult.message}';
          _isLoading = false;
        });
        return;
      }

      setState(() {
        _status = 'Processing request...';
      });

      // Process the authorization request
      final processResult = await _oid4vpMdoc.processRequest(url);

      if (processResult is ProcessRequestError) {
        setState(() {
          _status = 'Error: ${processResult.message}';
          _isLoading = false;
        });
        return;
      }

      if (processResult is ProcessRequestSuccess) {
        final info = processResult.info;
        setState(() {
          _requestInfo = info;
          _status = 'Found ${info.matches.length} matching credential(s)';
          _isLoading = false;
        });

        // Auto-select if only one match
        if (info.matches.length == 1) {
          _selectMatch(0);
        }
      }
    } catch (e) {
      setState(() {
        _status = 'Error: $e';
        _isLoading = false;
      });
    }
  }

  void _selectMatch(int index) {
    final match = _requestInfo!.matches[index];
    setState(() {
      _selectedMatchIndex = index;
      // Pre-select required fields
      _selectedFieldIds = match.requestedFields
          .where((f) => f.required || !f.selectivelyDisclosable)
          .map((f) => f.id)
          .toSet();
    });
  }

  Future<void> _submitPresentation() async {
    if (_selectedMatchIndex == null) return;

    setState(() {
      _isLoading = true;
      _status = 'Submitting presentation...';
    });

    try {
      final result = await _oid4vpMdoc.submitResponse(
        _selectedMatchIndex!,
        _selectedFieldIds.toList(),
      );

      if (result is Oid4vpMdocSuccess) {
        setState(() {
          _status = 'Presentation submitted successfully!';
          _isLoading = false;
        });

        // Show success and reset
        if (mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(
              content: Text('Presentation submitted successfully!'),
              backgroundColor: Colors.green,
            ),
          );
        }
        _reset();
      } else if (result is Oid4vpMdocError) {
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
    _oid4vpMdoc.cancel();
    setState(() {
      _requestInfo = null;
      _selectedMatchIndex = null;
      _selectedFieldIds = {};
    });
  }

  @override
  Widget build(BuildContext context) {
    if (_showScanner) {
      return Scaffold(
        body: SpruceScanner(
          type: ScannerType.qrCode,
          title: 'Scan Verifier QR',
          subtitle: 'Scan an mdoc-openid4vp:// QR code',
          onRead: _handleScannedUrl,
          onCancel: _closeScanner,
        ),
      );
    }

    return Scaffold(
      appBar: AppBar(
        title: const Text('OID4VP mDoc Demo'),
        actions: [
          if (_requestInfo != null)
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
    // Step 1: Generate mDL
    if (_packId == null) {
      return _buildGenerateMdlView();
    }

    // Step 2: Scan QR
    if (_requestInfo == null) {
      return _buildScanView();
    }

    // Step 3: Select match (if multiple)
    if (_selectedMatchIndex == null) {
      return _buildMatchSelector();
    }

    // Step 4: Field selection
    return _buildFieldSelector();
  }

  Widget _buildGenerateMdlView() {
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
                    'Step 1: Generate Test mDL',
                    style: Theme.of(context).textTheme.titleMedium,
                  ),
                  const SizedBox(height: 8),
                  const Text(
                    'Generate a mock mobile driver\'s license for testing '
                    'OID4VP presentation with mDoc credentials.',
                  ),
                  const SizedBox(height: 16),
                  ElevatedButton.icon(
                    onPressed: _isGeneratingMdl ? null : _generateMockMdl,
                    icon: _isGeneratingMdl
                        ? const SizedBox(
                            width: 16,
                            height: 16,
                            child: CircularProgressIndicator(strokeWidth: 2),
                          )
                        : const Icon(Icons.badge),
                    label: Text(
                      _isGeneratingMdl ? 'Generating...' : 'Generate Test mDL',
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

  Widget _buildScanView() {
    return SingleChildScrollView(
      padding: const EdgeInsets.all(16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Card(
            color: Colors.green.shade50,
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Row(
                children: [
                  Icon(Icons.check_circle, color: Colors.green.shade700),
                  const SizedBox(width: 12),
                  const Expanded(
                    child: Text('Test mDL generated successfully!'),
                  ),
                ],
              ),
            ),
          ),
          const SizedBox(height: 16),
          Card(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    'Step 2: Scan Verifier QR Code',
                    style: Theme.of(context).textTheme.titleMedium,
                  ),
                  const SizedBox(height: 8),
                  const Text(
                    'Scan a QR code from a verifier that contains an '
                    'mdoc-openid4vp:// URL to start the presentation flow.',
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

  Widget _buildMatchSelector() {
    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        if (_requestInfo!.requestedBy != null)
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
                  const SizedBox(height: 8),
                  Text('Requested by: ${_requestInfo!.requestedBy}'),
                ],
              ),
            ),
          ),
        const SizedBox(height: 16),
        Text(
          'Select a credential to present:',
          style: Theme.of(context).textTheme.titleMedium,
        ),
        const SizedBox(height: 8),
        ..._requestInfo!.matches.map((match) {
          return Card(
            child: ListTile(
              leading: const Icon(Icons.badge),
              title: Text('mDL Credential'),
              subtitle: Text(
                'ID: ${match.credentialId}\n'
                '${match.requestedFields.length} fields requested',
              ),
              trailing: const Icon(Icons.chevron_right),
              onTap: () => _selectMatch(match.index),
            ),
          );
        }),
      ],
    );
  }

  Widget _buildFieldSelector() {
    final match = _requestInfo!.matches[_selectedMatchIndex!];
    final genericFields = match.requestedFields
        .map(SelectiveDisclosureFieldData.fromMdocField)
        .toList();

    return Column(
      children: [
        Expanded(
          child: SelectiveDisclosureFieldsList(
            fields: genericFields,
            selectedIds: _selectedFieldIds,
            title: _requestInfo!.requestedBy != null
                ? 'From: ${_requestInfo!.requestedBy}'
                : 'Select fields to share:',
            onFieldToggled: (id, isSelected) {
              setState(() {
                if (isSelected) {
                  _selectedFieldIds.add(id);
                } else {
                  _selectedFieldIds.remove(id);
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
                  onPressed: _reset,
                  child: const Text('Deny'),
                ),
              ),
              const SizedBox(width: 16),
              Expanded(
                child: ElevatedButton(
                  onPressed: _selectedFieldIds.isNotEmpty
                      ? _submitPresentation
                      : null,
                  child: const Text('Approve'),
                ),
              ),
            ],
          ),
        ),
      ],
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
}
