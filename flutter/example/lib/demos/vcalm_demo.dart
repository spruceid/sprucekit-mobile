import 'package:flutter/material.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:sprucekit_mobile/sprucekit_mobile.dart';

import '../widgets/selective_disclosure_fields.dart';

enum _ScanPurpose { issuance, presentation }

/// Demo screen for the VCALM (`vcapi`) holder flow.
///
/// One protocol, two workflows driven by a single [Vcalm] session:
///   1. **Seed** a credential via VCALM *issuance* — scan/paste an
///      `interaction:`/exchange URL, preview the Offer, accept (stored in the
///      holder's `VdcCollection`).
///   2. **Present** via QueryByExample — scan/paste a presentation URL, pick a
///      matched credential + fields, submit.
///
/// The demo carries NO protocol logic: it renders each [VcalmStepResult] and
/// displays the cryptosuite indicator. The suite is server-driven — there is no
/// manual toggle. Redirect steps are displayed, never navigated.
class VcalmDemo extends StatefulWidget {
  const VcalmDemo({super.key});

  @override
  State<VcalmDemo> createState() => _VcalmDemoState();
}

class _VcalmDemoState extends State<VcalmDemo> {
  // Single VCALM session shared across both steps (same holder ⇒ same
  // VdcCollection, so a credential seeded in Step 1 is visible to Step 2).
  final _vcalm = Vcalm();
  final _keyId = 'vcalm_demo_key';
  final _trustedDids = <String>[];
  bool _holderCreated = false;

  // --- Step 1: issuance state ---
  final _issuanceUrlController = TextEditingController();
  bool _issuanceLoading = false;
  String? _issuanceStatus;
  List<VcalmOfferedCredentialData> _offered = [];
  bool _offerPending = false;
  bool _seeded = false;

  // --- Step 2: presentation state ---
  final _presentationUrlController = TextEditingController();
  bool _presentationLoading = false;
  String _presentationStatus = 'Ready';
  bool _inPresentation = false;
  bool? _vprListsSdSuite;
  String? _requestPurpose;
  List<VcalmCredentialKey> _matchedKeys = [];
  VcalmCredentialKey? _selectedKey;
  List<SelectiveDisclosureFieldData> _requestedFields = [];
  Set<String> _selectedFields = {};

  // --- Scanner ---
  _ScanPurpose? _scanPurpose;
  bool _cameraGranted = false;

  @override
  void initState() {
    super.initState();
    _checkCameraPermission();
  }

  @override
  void dispose() {
    _issuanceUrlController.dispose();
    _presentationUrlController.dispose();
    _vcalm.cancel();
    super.dispose();
  }

  // ──────────────────────────────────────────────
  // Shared
  // ──────────────────────────────────────────────

  // Lightweight flow logger. Filter device logs with the `VcalmDemo` tag
  // (Flutter `debugPrint` → adb logcat / Xcode console). Native-side logs use
  // the `VcalmAdapter` tag.
  void _log(String message) => debugPrint('[VcalmDemo] $message');

  Future<bool> _ensureHolder() async {
    if (_holderCreated) return true;
    _log('createHolder: keyId=$_keyId');
    // credentialPackIds is intentionally empty for VCALM — credentials arrive
    // via Step-1 issuance into the holder's own VdcCollection. contextMap is null:
    // the Rust holder defaults to the SDK's bundled JSON-LD contexts
    // (default_ld_json_context — the full bundled set), exactly like the
    // Showcase app, so offered VCs verify offline regardless of credential type.
    final result = await _vcalm.createHolder([], _trustedDids, _keyId, null);
    switch (result) {
      case VcalmSuccess():
        _log('createHolder: success');
        _holderCreated = true;
        return true;
      case VcalmError(:final message):
        _log('createHolder: ERROR $message');
        setState(() => _issuanceStatus = 'Error creating holder: $message');
        return false;
    }
  }

  String _suiteLabel(bool listsSd) =>
      listsSd ? 'SD-requesting (ecdsa-sd-2023)' : 'plain (ecdsa-rdfc-2019)';

  /// Unwraps an interaction URL of the form `https://…/interactions/<percent-
  /// encoded-exchange-url>?iuv=…` to the real VCAPI exchange URL.
  ///
  /// Some exchange servers hand out that wrapper `/interactions/<encoded>` path,
  /// which serves an HTML web app — POSTing to it yields HTML, not a VCAPI JSON
  /// response. The actual exchange endpoint is the percent-encoded inner URL
  /// (e.g. `https://…/workflows/…/exchanges/…`). This is a server-specific input
  /// normalization — no protocol logic; the Rust holder still drives the exchange.
  String _normalizeExchangeUrl(String input) {
    const marker = '/interactions/';
    final i = input.indexOf(marker);
    if (i < 0) return input;
    var encoded = input.substring(i + marker.length);
    final q = encoded.indexOf('?');
    if (q >= 0) encoded = encoded.substring(0, q);
    try {
      final decoded = Uri.decodeComponent(encoded);
      if (decoded.startsWith('http://') || decoded.startsWith('https://')) {
        _log('unwrapped interaction URL -> $decoded');
        return decoded;
      }
    } catch (_) {
      // Not an encoded URL — fall through and use the input as-is.
    }
    return input;
  }

  // ──────────────────────────────────────────────
  // Step 1: VCALM issuance
  // ──────────────────────────────────────────────

  Future<void> _runIssuance() async {
    FocusScope.of(context).unfocus();
    final raw = _issuanceUrlController.text.trim();
    if (raw.isEmpty) {
      setState(() => _issuanceStatus = 'Please enter an issuance URL');
      return;
    }
    final url = _normalizeExchangeUrl(raw);

    setState(() {
      _issuanceLoading = true;
      _issuanceStatus = 'Starting exchange...';
      _offered = [];
      _offerPending = false;
    });

    try {
      _log('issuance.startExchange: url=$url');
      if (!await _ensureHolder()) {
        setState(() => _issuanceLoading = false);
        return;
      }
      final step = await _vcalm.startExchange(url, null);
      _handleIssuanceStep(step);
    } catch (e, st) {
      _log('issuance.startExchange: EXCEPTION $e\n$st');
      setState(() {
        _issuanceLoading = false;
        _issuanceStatus = 'Error: $e';
      });
    }
  }

  void _handleIssuanceStep(VcalmStepResult step) {
    _log('issuance step: ${step.runtimeType}');
    setState(() {
      _issuanceLoading = false;
      switch (step) {
        case VcalmOffer(:final credentials, :final hasNextRequest):
          _offered = credentials;
          _offerPending = true;
          _issuanceStatus =
              'Offered ${credentials.length} credential(s)'
              '${hasNextRequest ? ' (a follow-on request will follow)' : ''}.';
        case VcalmComplete():
          _seeded = true;
          _issuanceStatus = 'Exchange complete.';
        case VcalmRedirect(:final url):
          // Surfaced only — never navigated.
          _issuanceStatus = 'Redirect (not followed): $url';
        case VcalmProblem(:final title, :final detail, :final problemType):
          _issuanceStatus =
              'Problem: ${title ?? problemType}'
              '${detail != null ? ' — $detail' : ''}';
        case VcalmRequest():
          _issuanceStatus = 'Unexpected presentation request during issuance.';
      }
    });
  }

  Future<void> _acceptOffer() async {
    setState(() {
      _issuanceLoading = true;
      _issuanceStatus = 'Accepting offer...';
    });
    try {
      final step = await _vcalm.acceptOffer();
      setState(() {
        _seeded = true;
        _offerPending = false;
      });
      _handleIssuanceStep(step);
      if (mounted && _issuanceStatus != null) {
        setState(
          () => _issuanceStatus =
              'Credential accepted & stored. ${_issuanceStatus!}',
        );
      }
    } catch (e, st) {
      _log('acceptOffer: EXCEPTION $e\n$st');
      setState(() {
        _issuanceLoading = false;
        _issuanceStatus = 'Error accepting offer: $e';
      });
    }
  }

  Future<void> _rejectOffer() async {
    setState(() {
      _issuanceLoading = true;
      _issuanceStatus = 'Rejecting offer...';
      _offerPending = false;
    });
    try {
      final step = await _vcalm.rejectOffer();
      _handleIssuanceStep(step);
    } catch (e, st) {
      _log('rejectOffer: EXCEPTION $e\n$st');
      setState(() {
        _issuanceLoading = false;
        _issuanceStatus = 'Error rejecting offer: $e';
      });
    }
  }

  // ──────────────────────────────────────────────
  // Step 2: QBE presentation
  // ──────────────────────────────────────────────

  Future<void> _runPresentation() async {
    FocusScope.of(context).unfocus();
    final raw = _presentationUrlController.text.trim();
    if (raw.isEmpty) {
      setState(() => _presentationStatus = 'Please enter a presentation URL');
      return;
    }
    final url = _normalizeExchangeUrl(raw);

    setState(() {
      _presentationLoading = true;
      _presentationStatus = 'Starting exchange...';
      _inPresentation = true;
      _vprListsSdSuite = null;
      _matchedKeys = [];
      _selectedKey = null;
      _requestedFields = [];
      _selectedFields = {};
    });

    try {
      _log('presentation.startExchange: url=$url');
      if (!await _ensureHolder()) {
        setState(() => _presentationLoading = false);
        return;
      }
      final step = await _vcalm.startExchange(url, null);
      _log('presentation step: ${step.runtimeType}');
      switch (step) {
        case VcalmRequest(:final purpose, :final vprListsSdSuite):
          _log(
            'presentation request: vprListsSdSuite=$vprListsSdSuite, '
            'purpose=$purpose',
          );
          _vprListsSdSuite = vprListsSdSuite;
          _requestPurpose = purpose;
          final keys = await _vcalm.matchedCredentials();
          _log('presentation: ${keys.length} matched credential(s)');
          setState(() {
            _matchedKeys = keys;
            _presentationLoading = false;
            _presentationStatus = keys.isEmpty
                ? 'No matching credentials. Seed one in Step 1 first.'
                : 'Found ${keys.length} matching credential(s). '
                      'Leg: ${_suiteLabel(vprListsSdSuite)}.';
          });
          if (keys.length == 1) {
            await _selectCredential(keys.first);
          }
        case VcalmProblem(:final title, :final detail, :final problemType):
          _log(
            'presentation PROBLEM: type=$problemType title=$title detail=$detail',
          );
          setState(() {
            _presentationLoading = false;
            _presentationStatus =
                'Problem: ${title ?? problemType}'
                '${detail != null ? ' — $detail' : ''}';
          });
        case VcalmRedirect(:final url):
          _log('presentation redirect (not followed): $url');
          setState(() {
            _presentationLoading = false;
            _presentationStatus = 'Redirect (not followed): $url';
          });
        case VcalmComplete():
          setState(() {
            _presentationLoading = false;
            _presentationStatus = 'Exchange complete (no presentation asked).';
          });
        case VcalmOffer():
          setState(() {
            _presentationLoading = false;
            _presentationStatus = 'Unexpected offer during presentation step.';
          });
      }
    } catch (e, st) {
      _log('presentation.startExchange: EXCEPTION $e\n$st');
      setState(() {
        _presentationLoading = false;
        _presentationStatus = 'Error: $e';
      });
    }
  }

  Future<void> _selectCredential(VcalmCredentialKey key) async {
    _log(
      'selectCredential: queryIndex=${key.queryIndex} id=${key.credentialId}',
    );
    setState(() => _selectedKey = key);
    try {
      final fields = await _vcalm.requestedFields();
      _log('selectCredential: ${fields.length} requested field(s)');
      final generic = fields
          .map(SelectiveDisclosureFieldData.fromVcalmRequestedField)
          .toList();
      setState(() {
        _requestedFields = generic;
        _selectedFields = generic
            .where((f) => f.required)
            .map((f) => f.id)
            .toSet();
      });
    } catch (e, st) {
      _log('selectCredential: EXCEPTION $e\n$st');
      setState(() => _presentationStatus = 'Error getting fields: $e');
    }
  }

  Future<void> _submitPresentation() async {
    final key = _selectedKey;
    if (key == null) return;

    setState(() {
      _presentationLoading = true;
      _presentationStatus = 'Submitting presentation...';
    });

    try {
      _log('submitPresentation: key=${key.credentialId}');
      final step = await _vcalm.submitPresentation([key]);
      _log('submitPresentation step: ${step.runtimeType}');
      setState(() {
        _presentationLoading = false;
        switch (step) {
          case VcalmComplete():
            _presentationStatus =
                'Presented successfully with '
                '${_suiteLabel(_vprListsSdSuite ?? false)}.';
            _resetPresentation();
          case VcalmRedirect(:final url):
            _presentationStatus = 'Redirect (not followed): $url';
          case VcalmProblem(:final title, :final detail, :final problemType):
            _log(
              'submit PROBLEM: type=$problemType title=$title detail=$detail',
            );
            _presentationStatus =
                'Problem: ${title ?? problemType}'
                '${detail != null ? ' — $detail' : ''}';
          case VcalmRequest():
            _presentationStatus = 'Follow-on request received.';
          case VcalmOffer():
            _presentationStatus = 'Unexpected offer after submit.';
        }
      });
    } catch (e, st) {
      _log('submitPresentation: EXCEPTION $e\n$st');
      setState(() {
        _presentationLoading = false;
        _presentationStatus = 'Error: $e';
      });
    }
  }

  void _resetPresentation() {
    _matchedKeys = [];
    _selectedKey = null;
    _requestedFields = [];
    _selectedFields = {};
    _inPresentation = false;
  }

  // ──────────────────────────────────────────────
  // Scanner / camera
  // ──────────────────────────────────────────────

  Future<void> _checkCameraPermission() async {
    final status = await Permission.camera.status;
    setState(() => _cameraGranted = status.isGranted);
  }

  Future<void> _requestCameraPermission() async {
    final status = await Permission.camera.request();
    setState(() => _cameraGranted = status.isGranted);
  }

  void _openScanner(_ScanPurpose purpose) =>
      setState(() => _scanPurpose = purpose);

  void _closeScanner() => setState(() => _scanPurpose = null);

  void _handleScannedUrl(String url) {
    final purpose = _scanPurpose;
    _closeScanner();
    if (purpose == _ScanPurpose.issuance) {
      _issuanceUrlController.text = url;
      _runIssuance();
    } else if (purpose == _ScanPurpose.presentation) {
      _presentationUrlController.text = url;
      _runPresentation();
    }
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
          title: isIssuance ? 'Scan Issuance QR' : 'Scan Presentation QR',
          subtitle: 'Scan an exchange QR code',
          onRead: _handleScannedUrl,
          onCancel: _closeScanner,
        ),
      );
    }

    return Scaffold(
      appBar: AppBar(title: const Text('VCALM Demo')),
      body: _inPresentation && (_matchedKeys.isNotEmpty || _presentationLoading)
          ? _buildPresentationFlow()
          : _buildHome(),
    );
  }

  Widget _buildHome() {
    return SingleChildScrollView(
      padding: const EdgeInsets.all(16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          _buildStep1Card(),
          const SizedBox(height: 16),
          _buildStep2Card(),
          const SizedBox(height: 16),
          _buildStatusCard(),
        ],
      ),
    );
  }

  Widget _buildStep1Card() {
    return Card(
      color: _seeded ? Colors.green.shade50 : null,
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(
                  _seeded ? Icons.check_circle : Icons.looks_one,
                  color: _seeded ? Colors.green.shade700 : null,
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    'Seed via VCALM issuance',
                    style: Theme.of(context).textTheme.titleMedium,
                  ),
                ),
              ],
            ),
            const SizedBox(height: 8),
            const Text(
              'Paste or scan an issuance exchange URL. The offered '
              'credential is verified and stored in the holder.',
            ),
            const SizedBox(height: 12),
            TextField(
              controller: _issuanceUrlController,
              decoration: const InputDecoration(
                labelText: 'Issuance URL',
                hintText: 'interaction:... or https://...',
                border: OutlineInputBorder(),
              ),
              maxLines: 2,
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
                        : const Text('Start issuance'),
                  ),
                ),
                const SizedBox(width: 8),
                ElevatedButton.icon(
                  onPressed: _issuanceLoading
                      ? null
                      : () => _cameraGranted
                            ? _openScanner(_ScanPurpose.issuance)
                            : _requestCameraPermission(),
                  icon: const Icon(Icons.qr_code_scanner),
                  label: Text(_cameraGranted ? 'Scan' : 'Camera'),
                ),
              ],
            ),
            if (_offerPending) ...[
              const SizedBox(height: 12),
              ..._buildOfferPreview(),
            ],
            if (_issuanceStatus != null) ...[
              const SizedBox(height: 8),
              Text(_issuanceStatus!, style: const TextStyle(fontSize: 13)),
            ],
          ],
        ),
      ),
    );
  }

  List<Widget> _buildOfferPreview() {
    return [
      Text(
        'Offered credential(s):',
        style: Theme.of(context).textTheme.titleSmall,
      ),
      const SizedBox(height: 4),
      ..._offered.map(
        (c) => Card(
          margin: const EdgeInsets.symmetric(vertical: 4),
          child: ListTile(
            leading: const Icon(Icons.card_membership),
            title: Text(c.types.isEmpty ? 'Credential' : c.types.join(', ')),
            subtitle: Text(
              'Issuer: ${c.issuer ?? 'unknown'}\nValidity: ${c.validity}',
            ),
          ),
        ),
      ),
      const SizedBox(height: 8),
      Row(
        children: [
          Expanded(
            child: OutlinedButton(
              onPressed: _issuanceLoading ? null : _rejectOffer,
              child: const Text('Reject'),
            ),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: ElevatedButton(
              onPressed: _issuanceLoading ? null : _acceptOffer,
              child: const Text('Accept'),
            ),
          ),
        ],
      ),
    ];
  }

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
                    'Present via QueryByExample',
                    style: Theme.of(context).textTheme.titleMedium,
                  ),
                ),
              ],
            ),
            const SizedBox(height: 8),
            const Text(
              'Paste or scan a presentation exchange URL. The '
              'requested cryptosuite is shown below — it is server-driven.',
            ),
            const SizedBox(height: 12),
            TextField(
              controller: _presentationUrlController,
              decoration: const InputDecoration(
                labelText: 'Presentation URL',
                hintText: 'interaction:... or https://...',
                border: OutlineInputBorder(),
              ),
              maxLines: 2,
            ),
            const SizedBox(height: 12),
            Row(
              children: [
                Expanded(
                  child: ElevatedButton(
                    onPressed: _presentationLoading ? null : _runPresentation,
                    child: const Text('Start presentation'),
                  ),
                ),
                const SizedBox(width: 8),
                ElevatedButton.icon(
                  onPressed: _presentationLoading
                      ? null
                      : () => _cameraGranted
                            ? _openScanner(_ScanPurpose.presentation)
                            : _requestCameraPermission(),
                  icon: const Icon(Icons.qr_code_scanner),
                  label: Text(_cameraGranted ? 'Scan' : 'Camera'),
                ),
              ],
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
            Text(_presentationStatus),
          ],
        ),
      ),
    );
  }

  Widget _buildPresentationFlow() {
    if (_presentationLoading) {
      return const Center(child: CircularProgressIndicator());
    }
    if (_selectedKey == null) {
      return _buildCredentialSelector();
    }
    return _buildFieldSelector();
  }

  Widget _buildSuiteIndicator() {
    final listsSd = _vprListsSdSuite ?? false;
    return Card(
      color: listsSd ? Colors.purple.shade50 : Colors.blue.shade50,
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Row(
          children: [
            Icon(
              listsSd ? Icons.shield : Icons.description,
              color: listsSd ? Colors.purple : Colors.blue,
            ),
            const SizedBox(width: 8),
            Expanded(
              child: Text(
                'Requested leg: ${_suiteLabel(listsSd)}',
                style: const TextStyle(fontWeight: FontWeight.w600),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildCredentialSelector() {
    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        _buildSuiteIndicator(),
        if (_requestPurpose != null) ...[
          const SizedBox(height: 8),
          Text('Purpose: $_requestPurpose'),
        ],
        const SizedBox(height: 16),
        Text(
          'Select a credential to present:',
          style: Theme.of(context).textTheme.titleMedium,
        ),
        const SizedBox(height: 8),
        ..._matchedKeys.asMap().entries.map((entry) {
          final index = entry.key;
          final key = entry.value;
          return Card(
            child: ListTile(
              leading: const Icon(Icons.badge),
              title: Text('Credential ${index + 1}'),
              subtitle: Text(
                'ID: ${key.credentialId}\nQuery index: ${key.queryIndex}',
              ),
              trailing: const Icon(Icons.chevron_right),
              onTap: () => _selectCredential(key),
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
          Padding(
            padding: const EdgeInsets.all(8),
            child: _buildSuiteIndicator(),
          ),
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
                    onPressed: () => setState(() {
                      _selectedKey = null;
                      _requestedFields = [];
                      _selectedFields = {};
                    }),
                    child: const Text('Back'),
                  ),
                ),
                const SizedBox(width: 16),
                Expanded(
                  child: ElevatedButton(
                    onPressed: _submitPresentation,
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
