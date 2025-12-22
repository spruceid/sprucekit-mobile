import 'package:flutter/material.dart';
import 'package:sprucekit_mobile/sprucekit_mobile.dart';

/// Demo screen for the Digital Credentials API integration
///
/// This demo shows how to prepare credentials for the DC API:
/// 1. Generate a mock mDL credential
/// 2. Sync credentials to App Group (for iOS Extension access)
/// 3. Register credentials with the platform's identity provider
///
/// Once registered, websites can request credentials via the browser's
/// Digital Credentials API. The request is handled by a native App Extension
/// (iOS) or Activity (Android).
class DcApiDemo extends StatefulWidget {
  const DcApiDemo({super.key});

  @override
  State<DcApiDemo> createState() => _DcApiDemoState();
}

class _DcApiDemoState extends State<DcApiDemo> {
  final _dcApi = DcApi();
  final _spruceUtils = SpruceUtils();

  String _status = 'Ready';
  bool _isSupported = false;
  bool _isLoading = false;
  bool _hasMdl = false;
  bool _isSynced = false;
  bool _isRegistered = false;

  String? _packId;
  String? _credentialId;

  // App Group ID - must match the one configured in iOS
  // Change this to match your app's App Group ID
  static const _appGroupId = 'group.com.spruceid.sprucekit.flutterexampleapp';

  @override
  void initState() {
    super.initState();
    _checkSupport();
  }

  Future<void> _checkSupport() async {
    final supported = await _dcApi.isSupported();
    setState(() {
      _isSupported = supported;
    });
  }

  Future<void> _generateMockMdl() async {
    setState(() {
      _isLoading = true;
      _status = 'Generating mock mDL...';
    });

    try {
      final result = await _spruceUtils.generateMockMdl('dc_api_test_mdl');

      if (result is GenerateMockMdlSuccess) {
        setState(() {
          _packId = result.packId;
          _credentialId = result.credentialId;
          _hasMdl = true;
          _status = 'Mock mDL generated successfully';
          _isLoading = false;
        });
      } else if (result is GenerateMockMdlError) {
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

  Future<void> _syncToAppGroup() async {
    if (_packId == null) return;

    setState(() {
      _isLoading = true;
      _status = 'Syncing to App Group...';
    });

    try {
      final result = await _dcApi.syncCredentialsToAppGroup(_appGroupId, [
        _packId!,
      ]);

      if (result is DcApiSuccess) {
        setState(() {
          _isSynced = true;
          _status = result.message ?? 'Synced successfully';
          _isLoading = false;
        });
      } else if (result is DcApiError) {
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

  Future<void> _registerCredentials() async {
    if (_packId == null) return;

    setState(() {
      _isLoading = true;
      _status = 'Registering with platform...';
    });

    try {
      final result = await _dcApi.registerCredentials([_packId!], 'SpruceKit Example');

      if (result is DcApiSuccess) {
        setState(() {
          _isRegistered = true;
          _status = result.message ?? 'Registered successfully';
          _isLoading = false;
        });
      } else if (result is DcApiError) {
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
    setState(() {
      _hasMdl = false;
      _isSynced = false;
      _isRegistered = false;
      _packId = null;
      _credentialId = null;
      _status = 'Ready';
    });
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('DC API Demo'),
        actions: [
          if (_hasMdl)
            IconButton(
              icon: const Icon(Icons.refresh),
              onPressed: _reset,
              tooltip: 'Reset',
            ),
        ],
      ),
      body: _isLoading
          ? const Center(child: CircularProgressIndicator())
          : SingleChildScrollView(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  _buildSupportCard(),
                  const SizedBox(height: 16),
                  _buildStepsCard(),
                  const SizedBox(height: 16),
                  _buildStatusCard(),
                  if (_isRegistered) ...[
                    const SizedBox(height: 16),
                    _buildReadyCard(),
                  ],
                ],
              ),
            ),
    );
  }

  Widget _buildSupportCard() {
    return Card(
      color: _isSupported ? Colors.green.shade50 : Colors.orange.shade50,
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Row(
          children: [
            Icon(
              _isSupported ? Icons.check_circle : Icons.warning,
              color: _isSupported
                  ? Colors.green.shade700
                  : Colors.orange.shade700,
              size: 32,
            ),
            const SizedBox(width: 16),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    'DC API Support',
                    style: TextStyle(
                      fontWeight: FontWeight.bold,
                      color: _isSupported
                          ? Colors.green.shade800
                          : Colors.orange.shade800,
                    ),
                  ),
                  Text(
                    _isSupported
                        ? 'Supported on this platform'
                        : 'Requires iOS 26+ or Android 14+',
                    style: TextStyle(
                      fontSize: 12,
                      color: _isSupported
                          ? Colors.green.shade600
                          : Colors.orange.shade600,
                    ),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildStepsCard() {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('Setup Steps', style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 16),

            // Step 1: Generate mDL
            _buildStepTile(
              step: 1,
              title: 'Generate Test mDL',
              subtitle: 'Create a mock mobile driver\'s license',
              isCompleted: _hasMdl,
              isEnabled: !_hasMdl,
              onTap: _generateMockMdl,
            ),

            // Step 2: Sync to App Group
            _buildStepTile(
              step: 2,
              title: 'Sync to App Group',
              subtitle: 'Make credentials accessible to DC API Extension',
              isCompleted: _isSynced,
              isEnabled: _hasMdl && !_isSynced,
              onTap: _syncToAppGroup,
            ),

            // Step 3: Register
            _buildStepTile(
              step: 3,
              title: 'Register with Platform',
              subtitle:
                  'Register credentials with iOS/Android identity provider',
              isCompleted: _isRegistered,
              isEnabled: _isSynced && !_isRegistered,
              onTap: _registerCredentials,
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildStepTile({
    required int step,
    required String title,
    required String subtitle,
    required bool isCompleted,
    required bool isEnabled,
    required VoidCallback onTap,
  }) {
    return ListTile(
      leading: CircleAvatar(
        backgroundColor: isCompleted
            ? Colors.green
            : isEnabled
            ? Theme.of(context).colorScheme.primary
            : Colors.grey.shade300,
        child: isCompleted
            ? const Icon(Icons.check, color: Colors.white)
            : Text(
                '$step',
                style: TextStyle(
                  color: isEnabled ? Colors.white : Colors.grey.shade600,
                ),
              ),
      ),
      title: Text(
        title,
        style: TextStyle(
          fontWeight: isEnabled ? FontWeight.bold : FontWeight.normal,
          color: isCompleted
              ? Colors.grey
              : isEnabled
              ? null
              : Colors.grey,
        ),
      ),
      subtitle: Text(
        subtitle,
        style: TextStyle(color: isCompleted || !isEnabled ? Colors.grey : null),
      ),
      trailing: isCompleted
          ? null
          : ElevatedButton(
              onPressed: isEnabled ? onTap : null,
              child: const Text('Run'),
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
            if (_credentialId != null) ...[
              const SizedBox(height: 8),
              Text(
                'Credential ID: ${_credentialId!.substring(0, 8)}...',
                style: const TextStyle(fontSize: 12, color: Colors.grey),
              ),
            ],
          ],
        ),
      ),
    );
  }

  Widget _buildReadyCard() {
    return Card(
      color: Colors.green.shade50,
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(Icons.check_circle, color: Colors.green.shade700),
                const SizedBox(width: 8),
                Text(
                  'Ready for DC API',
                  style: TextStyle(
                    fontWeight: FontWeight.bold,
                    color: Colors.green.shade800,
                    fontSize: 16,
                  ),
                ),
              ],
            ),
            const SizedBox(height: 12),
            Text(
              'Your mDL is now registered and ready to be presented via the '
              'Digital Credentials API.\n\n'
              'To test:\n'
              '1. Open Safari (iOS) or Chrome (Android)\n'
              '2. Visit a website that supports DC API credential requests\n'
              '3. The system will show a credential picker with your mDL',
              style: TextStyle(color: Colors.green.shade700),
            ),
          ],
        ),
      ),
    );
  }
}
