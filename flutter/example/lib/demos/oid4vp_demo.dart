import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart' show rootBundle;
import 'package:sprucekit_mobile/sprucekit_mobile.dart';

/// Decode a potentially base64-encoded JSONPath to readable field name
String _decodeFieldPath(String path) {
  try {
    final decoded = utf8.decode(base64Decode(path));
    // Extract field name from JSONPath like $['@context'] -> @context
    final match = RegExp(r"\['([^']+)'\]").firstMatch(decoded);
    if (match != null) {
      return match.group(1)!;
    }
    return decoded;
  } catch (_) {
    // If decoding fails, return original path
    return path;
  }
}

/// Demo screen for the OID4VP (credential presentation) flow
class Oid4vpDemo extends StatefulWidget {
  const Oid4vpDemo({super.key});

  @override
  State<Oid4vpDemo> createState() => _Oid4vpDemoState();
}

class _Oid4vpDemoState extends State<Oid4vpDemo> {
  final _oid4vp = Oid4vp();
  final _credentialPack = CredentialPack();
  final _urlController = TextEditingController();

  String _status = 'Ready';
  List<PresentableCredentialData> _credentials = [];
  PermissionRequestInfo? _requestInfo;
  List<RequestedFieldData> _requestedFields = [];
  int? _selectedCredentialIndex;
  Set<String> _selectedFields = {};
  bool _isLoading = false;
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
  void dispose() {
    _oid4vp.cancel();
    _urlController.dispose();
    super.dispose();
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
      setState(() {
        _requestedFields = fields;
        // Pre-select required fields
        _selectedFields = fields
            .where((f) => f.required)
            .map((f) => f.path)
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
    return Padding(
      padding: const EdgeInsets.all(16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Card(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    'Status',
                    style: Theme.of(context).textTheme.titleMedium,
                  ),
                  const SizedBox(height: 8),
                  Text(_status),
                ],
              ),
            ),
          ),
          const SizedBox(height: 16),
          const Text(
            'To test OID4VP presentation:\n'
            '1. Enter an OID4VP URL below (starts with openid4vp://)\n'
            '2. A test AlumniCredential will be used automatically',
          ),
          const SizedBox(height: 16),
          TextField(
            controller: _urlController,
            decoration: const InputDecoration(
              labelText: 'OID4VP URL',
              hintText: 'openid4vp://...',
              border: OutlineInputBorder(),
            ),
            maxLines: 3,
          ),
          const SizedBox(height: 16),
          ElevatedButton.icon(
            onPressed: () {
              final url = _urlController.text.trim();
              if (url.startsWith('openid4vp://')) {
                _handleAuthorizationUrl(url);
              } else {
                setState(() {
                  _status = 'Invalid URL. Must start with openid4vp://';
                });
              }
            },
            icon: const Icon(Icons.play_arrow),
            label: const Text('Process OID4VP Request'),
          ),
        ],
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
    final credential = _credentials[_selectedCredentialIndex!];

    return Column(
      children: [
        Expanded(
          child: ListView(
            padding: const EdgeInsets.all(16),
            children: [
              Text(
                'Select fields to share:',
                style: Theme.of(context).textTheme.titleMedium,
              ),
              const SizedBox(height: 8),
              ..._requestedFields.map((field) {
                final isSelected = _selectedFields.contains(field.path);
                final canToggle =
                    credential.selectiveDisclosable && !field.required;

                // Decode field name for display
                final displayName = _decodeFieldPath(field.name ?? field.path);

                return CheckboxListTile(
                  value: isSelected || field.required,
                  onChanged: canToggle
                      ? (value) {
                          setState(() {
                            if (value == true) {
                              _selectedFields.add(field.path);
                            } else {
                              _selectedFields.remove(field.path);
                            }
                          });
                        }
                      : null,
                  title: Text(displayName),
                  subtitle: field.purpose != null ? Text(field.purpose!) : null,
                  secondary: field.required
                      ? const Chip(label: Text('Required'))
                      : null,
                );
              }),
            ],
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
