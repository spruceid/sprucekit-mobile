import 'package:flutter/material.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:sprucekit_mobile/sprucekit_mobile.dart';

import '../widgets/selective_disclosure_fields.dart';

enum _ScanPurpose { issuance, presentation }

/// Demo screen for the OID4VP (credential presentation) flow.
///
/// Two-step flow:
///   1. Issue a credential by scanning/pasting an offer URL. The demo detects
///      the issuance protocol from the offer and runs the matching workflow:
///      OID4VCI (`openid-credential-offer://` / a `credential_offer[_uri]`
///      link) or VCALM (an `interaction:` URL, or any link carrying `?iuv=`).
///   2. Present the issued credential via OID4VP (scan a verifier QR code)
class Oid4vpDemo extends StatefulWidget {
  const Oid4vpDemo({super.key});

  @override
  State<Oid4vpDemo> createState() => _Oid4vpDemoState();
}

class _Oid4vpDemoState extends State<Oid4vpDemo> {
  // --- SDK instances ---
  final _oid4vci = Oid4vci();
  final _vcalm = Vcalm();
  final _oid4vp = Oid4vp();
  final _credentialPack = CredentialPack();

  // --- Step 1: Issuance state ---
  final _offerController = TextEditingController();
  final _keyId = 'oid4vp_demo_key';
  final _vcalmKeyId = 'oid4vp_demo_vcalm_key';
  bool _issuanceLoading = false;
  String? _issuanceError;
  List<IssuedCredential> _issuedCredentials = [];

  /// Credential types issued via the VCALM workflow (stored in the holder's own
  /// collection rather than returned as [IssuedCredential]s). Drives the Step-1
  /// "done" state alongside [_issuedCredentials].
  List<String> _vcalmIssuedTypes = [];

  /// Credential pack holding the VCALM-issued credential(s). The native
  /// `acceptOffer` only stores the credential in the VCALM holder's own
  /// VdcCollection, so — mirroring ca-career-passport-pilot's
  /// `VcalmService.persistOffered` — we also persist the raw VC into a
  /// CredentialPack at accept time so the OID4VP holder in Step 2 can present it.
  String? _vcalmPackId;

  // --- Step 2: Presentation state ---
  String _status = 'Ready';
  List<PresentableCredentialData> _credentials = [];
  PermissionRequestInfo? _requestInfo;
  List<SelectiveDisclosureFieldData> _requestedFields = [];
  PresentableCredentialKey? _selectedCredentialKey;
  Set<String> _selectedFields = {};
  bool _presentationLoading = false;
  _ScanPurpose? _scanPurpose;
  bool _cameraGranted = false;
  String? _packId;

  /// OID4VP versions to negotiate against. Empty detects from the request; a
  /// non-empty list restricts detection (e.g. exclude draft 18 so a bare
  /// `request_uri` draft-13 request is not misrouted and its single-use fetch
  /// is not burned). draft 13 and draft 18 cannot be combined.
  List<Oid4vpVersion> _supportedVersions = const [];

  final _trustedDids = <String>[];

  @override
  void initState() {
    super.initState();
    _checkCameraPermission();
  }

  void _toggleVersion(Oid4vpVersion version, bool selected) {
    setState(() {
      final next = [..._supportedVersions];
      if (selected) {
        if (version == Oid4vpVersion.draft13) {
          next.remove(Oid4vpVersion.draft18);
        } else if (version == Oid4vpVersion.draft18) {
          next.remove(Oid4vpVersion.draft13);
        }
        next.add(version);
      } else {
        next.remove(version);
      }
      _supportedVersions = next;
    });
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

  /// True iff the offer is a VCALM (VC-API) interaction rather than an OID4VCI
  /// credential offer: an `interaction:` URL, or any link carrying an `iuv`
  /// query parameter (the interaction-URL version marker). Everything else is
  /// treated as an OID4VCI offer.
  bool _isVcalmOffer(String url) {
    final trimmed = url.trim();
    if (trimmed.toLowerCase().startsWith('interaction:')) return true;
    final uri = Uri.tryParse(trimmed);
    return uri != null && uri.queryParameters.containsKey('iuv');
  }

  Future<void> _runIssuance() async {
    FocusScope.of(context).unfocus();

    final offer = _offerController.text.trim();
    if (offer.isEmpty) {
      setState(() => _issuanceError = 'Please enter a credential offer URL');
      return;
    }

    // Route by detected issuance protocol.
    if (_isVcalmOffer(offer)) {
      await _runVcalmIssuance(offer);
      return;
    }

    setState(() {
      _issuanceLoading = true;
      _issuedCredentials = [];
      _vcalmIssuedTypes = [];
      _vcalmPackId = null;
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
        const [],
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

  /// VCALM (VC-API) issuance: create a holder, start the exchange from the
  /// interaction/exchange URL, and accept the offered credential into the
  /// wallet's own collection. The demo carries no protocol logic — it renders
  /// each [VcalmStepResult].
  Future<void> _runVcalmIssuance(String url) async {
    setState(() {
      _issuanceLoading = true;
      _issuedCredentials = [];
      _vcalmIssuedTypes = [];
      _vcalmPackId = null;
      _issuanceError = null;
    });

    try {
      final created = await _vcalm.createHolder(
        [],
        _trustedDids,
        _vcalmKeyId,
        null,
      );
      if (created is VcalmError) {
        setState(() {
          _issuanceLoading = false;
          _issuanceError = 'VCALM createHolder failed: ${created.message}';
        });
        return;
      }

      final step = await _vcalm.startExchange(url, null);
      if (step is! VcalmOffer) {
        setState(() {
          _issuanceLoading = false;
          _issuanceError = step is VcalmProblem
              ? 'VCALM exchange problem: ${step.detail ?? step.title ?? step.problemType}'
              : 'VCALM exchange did not return a credential offer '
                    '(got ${step.runtimeType}).';
        });
        return;
      }

      final offeredTypes = step.credentials
          .expand((c) => c.types)
          .toSet()
          .toList();

      // Accept the offer — the credential is verified and stored in the VCALM
      // holder's own VdcCollection.
      final accepted = await _vcalm.acceptOffer();
      if (accepted is VcalmProblem) {
        setState(() {
          _issuanceLoading = false;
          _issuanceError =
              'VCALM accept failed: ${accepted.detail ?? accepted.title ?? accepted.problemType}';
        });
        return;
      }

      // Persist the accepted VC(s) into a CredentialPack so Step 2's OID4VP
      // holder can present them — acceptOffer only stores them in the VCALM
      // holder's own collection. Mirrors ca-career-passport-pilot's
      // VcalmService.persistOffered (rawCredential -> wallet store).
      final packId = await _credentialPack.createPack();
      for (final credential in step.credentials) {
        // Bind the stored credential to the same key VCALM signed the proof
        // with, so its presentation key matches its `cnf`.
        final add = await _credentialPack.addAnyFormat(
          packId,
          credential.rawCredential,
          _vcalmKeyId,
        );
        if (add is AddCredentialError) {
          setState(() {
            _issuanceLoading = false;
            _issuanceError = 'Failed to store VCALM credential: ${add.message}';
          });
          return;
        }
      }

      setState(() {
        _issuanceLoading = false;
        _vcalmIssuedTypes = offeredTypes.isEmpty
            ? ['Verifiable Credential']
            : offeredTypes;
        _vcalmPackId = packId;
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

    // OID4VP requests arrive under many schemes — `openid4vp://`, vendor deep
    // links (`mdoc-openid4vp://`, `openid-vc://`, `haip://`, …), or `https://`
    // universal links / request_uri references. Accept any scheme-bearing URL
    // and let the facade's version detection + error handling judge it, rather
    // than hard-coding one scheme.
    final parsed = Uri.tryParse(url.trim());
    if (parsed == null || parsed.scheme.isEmpty) {
      setState(() => _status = 'Invalid URL: $url');
      return;
    }

    _handleAuthorizationUrl(url.trim());
  }

  Future<void> _handleAuthorizationUrl(String url) async {
    setState(() {
      _presentationLoading = true;
      _status = 'Adding issued credential to pack...';
    });

    try {
      // Use the pack that holds the issued credential. VCALM persisted its VC
      // into a pack at accept time; OID4VCI credentials are seeded here.
      if (_vcalmPackId != null) {
        _packId = _vcalmPackId;
      } else {
        _packId = await _credentialPack.createPack();
        for (final credential in _issuedCredentials) {
          final addResult = await _credentialPack.addAnyFormat(
            _packId!,
            credential.payload,
            _keyId,
          );
          if (addResult is AddCredentialError) {
            setState(() {
              _status = 'Error adding credential: ${addResult.message}';
              _presentationLoading = false;
            });
            return;
          }
        }
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

      final authResult = await _oid4vp.handleAuthorizationRequest(
        url,
        _supportedVersions,
      );

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
          _selectCredential(_credentials.first);
        }
      }
    } catch (e) {
      setState(() {
        _status = 'Error: $e';
        _presentationLoading = false;
      });
    }
  }

  Future<void> _selectCredential(PresentableCredentialData cred) async {
    final key = PresentableCredentialKey(
      credentialId: cred.credentialId,
      credentialQueryId: cred.credentialQueryId,
    );
    setState(() => _selectedCredentialKey = key);

    try {
      final fields = await _oid4vp.getRequestedFields(key);
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
    final selectedKey = _selectedCredentialKey;
    if (selectedKey == null) return;

    setState(() {
      _presentationLoading = true;
      _status = 'Submitting presentation...';
    });

    try {
      final result = await _oid4vp.submitResponse(
        [selectedKey],
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
      _selectedCredentialKey = null;
      _selectedFields = {};
    });
  }

  void _resetAll() {
    _resetPresentation();
    setState(() {
      _issuedCredentials = [];
      _vcalmIssuedTypes = [];
      _vcalmPackId = null;
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
      if (_selectedCredentialKey == null) {
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
          if (_issuedCredentials.isNotEmpty || _vcalmPackId != null) ...[
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
    final isDone =
        _issuedCredentials.isNotEmpty || _vcalmIssuedTypes.isNotEmpty;

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
                    'Issue Credential (OID4VCI or VCALM)',
                    style: Theme.of(context).textTheme.titleMedium,
                  ),
                ),
              ],
            ),
            const SizedBox(height: 8),
            if (isDone) ...[
              Text(
                _issuedCredentials.isNotEmpty
                    ? 'Issued ${_issuedCredentials.length} credential(s) via '
                          'OID4VCI (${_issuedCredentials.first.format})'
                    : 'Issued via VCALM (${_vcalmIssuedTypes.join(", ")})',
                style: TextStyle(color: Colors.green.shade800),
              ),
            ] else ...[
              const Text(
                'Scan or paste an offer URL. The protocol is detected '
                'automatically: an OID4VCI offer '
                '(openid-credential-offer://…) runs the OID4VCI issuance flow; '
                'a VCALM interaction URL (interaction:… or a link with ?iuv=) '
                'runs the VC-API exchange and accepts the offered credential.',
              ),
              const SizedBox(height: 12),
              TextField(
                controller: _offerController,
                decoration: const InputDecoration(
                  labelText: 'Credential Offer / Interaction URL',
                  hintText: 'openid-credential-offer://…  or  interaction:…',
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
                  style: TextStyle(
                    color: Theme.of(context).colorScheme.error,
                    fontSize: 13,
                  ),
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
              'Scan a verifier QR code (openid4vp://, a vendor deep link, or '
              'an https:// link) to present your issued credential.',
            ),
            const SizedBox(height: 12),
            const Text('OID4VP versions (none = auto-detect):'),
            const SizedBox(height: 4),
            Wrap(
              spacing: 8,
              children: [
                FilterChip(
                  label: const Text('v1'),
                  selected: _supportedVersions.contains(Oid4vpVersion.v1),
                  onSelected: (selected) =>
                      _toggleVersion(Oid4vpVersion.v1, selected),
                ),
                FilterChip(
                  label: const Text('draft 13'),
                  selected: _supportedVersions.contains(Oid4vpVersion.draft13),
                  onSelected: (selected) =>
                      _toggleVersion(Oid4vpVersion.draft13, selected),
                ),
                FilterChip(
                  label: const Text('draft 18'),
                  selected: _supportedVersions.contains(Oid4vpVersion.draft18),
                  onSelected: (selected) =>
                      _toggleVersion(Oid4vpVersion.draft18, selected),
                ),
              ],
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
                'Query: ${cred.credentialQueryId}\n'
                'Selective Disclosure: ${cred.selectiveDisclosable ? 'Yes' : 'No'}',
              ),
              trailing: const Icon(Icons.chevron_right),
              onTap: () => _selectCredential(cred),
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
          if (_status.contains('Error'))
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 0, 16, 8),
              child: Text(
                _status,
                style: TextStyle(color: Theme.of(context).colorScheme.error),
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
                        _selectedCredentialKey = null;
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
                    onPressed:
                        (_selectedFields.isNotEmpty && !_presentationLoading)
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
