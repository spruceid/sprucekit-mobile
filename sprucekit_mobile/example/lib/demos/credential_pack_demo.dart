import 'package:flutter/material.dart';
import 'package:sprucekit_mobile/sprucekit_mobile.dart';

class CredentialPackDemo extends StatefulWidget {
  const CredentialPackDemo({super.key});

  @override
  State<CredentialPackDemo> createState() => _CredentialPackDemoState();
}

class _CredentialPackDemoState extends State<CredentialPackDemo> {
  final _api = CredentialPack();
  final _credentialController = TextEditingController();

  String? _currentPackId;
  List<ParsedCredentialData> _credentials = [];
  String? _error;
  String? _message;
  bool _loading = false;

  @override
  void dispose() {
    _credentialController.dispose();
    super.dispose();
  }

  Future<void> _createPack() async {
    setState(() {
      _loading = true;
      _error = null;
    });

    try {
      final packId = await _api.createPack();
      setState(() {
        _currentPackId = packId;
        _credentials = [];
        _message = 'Pack created: $packId';
        _loading = false;
      });
    } catch (e) {
      setState(() {
        _error = e.toString();
        _loading = false;
      });
    }
  }

  Future<void> _addCredential() async {
    if (_currentPackId == null) {
      setState(() => _error = 'Create a pack first');
      return;
    }

    final rawCredential = _credentialController.text.trim();
    if (rawCredential.isEmpty) {
      setState(() => _error = 'Please enter a credential');
      return;
    }

    setState(() {
      _loading = true;
      _error = null;
      _message = null;
    });

    try {
      final result = await _api.addRawCredential(
        _currentPackId!,
        rawCredential,
      );

      setState(() {
        _loading = false;
        switch (result) {
          case AddCredentialSuccess(:final credentials):
            _credentials = credentials;
            _message = 'Added credential! Total: ${credentials.length}';
            _credentialController.clear();
          case AddCredentialError(:final message):
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

  Future<void> _listCredentials() async {
    if (_currentPackId == null) {
      setState(() => _error = 'Create a pack first');
      return;
    }

    setState(() {
      _loading = true;
      _error = null;
    });

    try {
      final credentials = await _api.listCredentials(_currentPackId!);
      setState(() {
        _credentials = credentials;
        _message = 'Found ${credentials.length} credential(s)';
        _loading = false;
      });
    } catch (e) {
      setState(() {
        _error = e.toString();
        _loading = false;
      });
    }
  }

  Future<void> _listPacks() async {
    setState(() {
      _loading = true;
      _error = null;
    });

    try {
      final packs = await _api.listPacks();
      setState(() {
        _message = 'Packs: ${packs.join(', ')}';
        _loading = false;
      });
    } catch (e) {
      setState(() {
        _error = e.toString();
        _loading = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Credential Pack')),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            // Current pack info
            if (_currentPackId != null)
              Container(
                padding: const EdgeInsets.all(12),
                margin: const EdgeInsets.only(bottom: 16),
                decoration: BoxDecoration(
                  color: Colors.blue.shade50,
                  borderRadius: BorderRadius.circular(8),
                  border: Border.all(color: Colors.blue.shade200),
                ),
                child: Text(
                  'Current Pack: $_currentPackId',
                  style: const TextStyle(fontWeight: FontWeight.bold),
                ),
              ),

            // Action buttons
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                ElevatedButton.icon(
                  onPressed: _loading ? null : _createPack,
                  icon: const Icon(Icons.add),
                  label: const Text('Create Pack'),
                ),
                ElevatedButton.icon(
                  onPressed: _loading ? null : _listPacks,
                  icon: const Icon(Icons.list),
                  label: const Text('List Packs'),
                ),
                ElevatedButton.icon(
                  onPressed: _loading ? null : _listCredentials,
                  icon: const Icon(Icons.refresh),
                  label: const Text('Refresh'),
                ),
              ],
            ),

            const SizedBox(height: 24),
            const Divider(),
            const SizedBox(height: 16),

            // Add credential
            const Text(
              'Add Credential',
              style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold),
            ),
            const SizedBox(height: 8),
            TextField(
              controller: _credentialController,
              decoration: const InputDecoration(
                labelText: 'Raw Credential (JWT, JSON-LD, SD-JWT)',
                hintText: 'eyJ...',
                border: OutlineInputBorder(),
              ),
              maxLines: 4,
            ),
            const SizedBox(height: 8),
            ElevatedButton(
              onPressed: _loading ? null : _addCredential,
              child: _loading
                  ? const SizedBox(
                      height: 20,
                      width: 20,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    )
                  : const Text('Add Credential'),
            ),

            const SizedBox(height: 24),

            // Messages
            if (_error != null)
              Container(
                padding: const EdgeInsets.all(12),
                margin: const EdgeInsets.only(bottom: 16),
                decoration: BoxDecoration(
                  color: Colors.red.shade100,
                  borderRadius: BorderRadius.circular(8),
                ),
                child: Text(
                  _error!,
                  style: TextStyle(color: Colors.red.shade900),
                ),
              ),

            if (_message != null)
              Container(
                padding: const EdgeInsets.all(12),
                margin: const EdgeInsets.only(bottom: 16),
                decoration: BoxDecoration(
                  color: Colors.green.shade100,
                  borderRadius: BorderRadius.circular(8),
                ),
                child: Text(
                  _message!,
                  style: TextStyle(color: Colors.green.shade900),
                ),
              ),

            // Credentials list
            if (_credentials.isNotEmpty) ...[
              const Text(
                'Credentials',
                style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold),
              ),
              const SizedBox(height: 8),
              ..._credentials.map(
                (cred) => Card(
                  margin: const EdgeInsets.only(bottom: 8),
                  child: Padding(
                    padding: const EdgeInsets.all(12),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          'ID: ${cred.id}',
                          style: const TextStyle(fontWeight: FontWeight.bold),
                        ),
                        const SizedBox(height: 4),
                        Text('Format: ${cred.format.name}'),
                        const SizedBox(height: 4),
                        Text(
                          'Raw: ${cred.rawCredential.length > 100 ? '${cred.rawCredential.substring(0, 100)}...' : cred.rawCredential}',
                          style: const TextStyle(
                            fontSize: 12,
                            fontFamily: 'monospace',
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }
}
