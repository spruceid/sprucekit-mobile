import 'package:flutter/material.dart';
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
  String? _result;
  String? _error;

  @override
  void dispose() {
    _offerController.dispose();
    _clientIdController.dispose();
    _redirectUrlController.dispose();
    _keyIdController.dispose();
    super.dispose();
  }

  Future<void> _runIssuance() async {
    final offer = _offerController.text.trim();
    if (offer.isEmpty) {
      setState(() => _error = 'Please enter a credential offer URL');
      return;
    }

    setState(() {
      _loading = true;
      _result = null;
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
            _result =
                'Success! Received ${credentials.length} credential(s):\n\n';
            for (final cred in credentials) {
              _result =
                  '${_result}Format: ${cred.format}\nPayload: ${cred.payload.substring(0, 100)}...\n\n';
            }
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
            ElevatedButton(
              onPressed: _loading ? null : _runIssuance,
              child: _loading
                  ? const SizedBox(
                      height: 20,
                      width: 20,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    )
                  : const Text('Run Issuance'),
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
            if (_result != null)
              Container(
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                  color: Colors.green.shade100,
                  borderRadius: BorderRadius.circular(8),
                ),
                child: Text(
                  _result!,
                  style: TextStyle(color: Colors.green.shade900),
                ),
              ),
          ],
        ),
      ),
    );
  }
}
