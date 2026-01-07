import 'package:flutter/material.dart';
import 'package:sprucekit_mobile/sprucekit_mobile.dart';

/// App Group ID for sharing credentials with the iOS DC API extension
/// Must match the App Group ID in Runner.entitlements and DcApiExtension.entitlements
const String _appGroupId = 'group.com.spruceid.sprucekit.flutterexampleapp';

/// Demo screen for the Digital Credentials API integration
///
/// This demo shows how to prepare credentials for the DC API:
/// 1. Generate a mock mDL credential (automatically registers with iOS ID Provider)
/// 2. Save to persistent storage (App Group on iOS)
/// 3. Register with DC API (Android only)
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
  final _credentialPack = CredentialPack();

  String _status = 'Ready';
  bool _isSupported = false;
  bool _isLoading = false;

  // Track all packs and their states
  List<_PackInfo> _packs = [];

  @override
  void initState() {
    super.initState();
    _initialize();
  }

  Future<void> _initialize() async {
    await _checkSupport();
    await _loadPersistedPacks();
  }

  Future<void> _loadPersistedPacks() async {
    setState(() {
      _isLoading = true;
      _status = 'Loading saved credentials...';
    });

    try {
      // Load all packs from StorageManager (uses App Group on iOS)
      final packIds = await _credentialPack.loadAllPacks(_appGroupId);

      if (packIds.isNotEmpty) {
        // Get credential info for each pack
        final loadedPacks = <_PackInfo>[];
        for (final packId in packIds) {
          final credentials = await _credentialPack.listCredentials(packId);
          final hasMdl = credentials.any(
            (c) => c.format == CredentialFormat.msoMdoc,
          );
          loadedPacks.add(
            _PackInfo(
              packId: packId,
              credentialCount: credentials.length,
              hasMdl: hasMdl,
              isRegistered: true, // Assume registered if persisted
            ),
          );
        }

        setState(() {
          _packs = loadedPacks;
          _status = 'Loaded ${packIds.length} saved credential pack(s)';
          _isLoading = false;
        });
      } else {
        setState(() {
          _status = 'Ready';
          _isLoading = false;
        });
      }
    } catch (e) {
      debugPrint('Failed to load persisted packs: $e');
      setState(() {
        _status = 'Ready';
        _isLoading = false;
      });
    }
  }

  Future<void> _checkSupport() async {
    final supported = await _dcApi.isSupported();
    setState(() {
      _isSupported = supported;
    });
  }

  Future<void> _generateAndRegisterMockMdl() async {
    setState(() {
      _isLoading = true;
      _status = 'Generating mock mDL...';
    });

    try {
      // Step 1: Generate mock mDL
      final keyAlias =
          'dc_api_test_mdl_${DateTime.now().millisecondsSinceEpoch}';
      final result = await _spruceUtils.generateMockMdl(keyAlias);

      if (result is GenerateMockMdlError) {
        setState(() {
          _status = 'Error: ${result.message}';
          _isLoading = false;
        });
        return;
      }

      final success = result as GenerateMockMdlSuccess;
      final packId = success.packId;

      // Add to our list (already registered via addMDoc)
      final newPack = _PackInfo(
        packId: packId,
        credentialCount: 1,
        hasMdl: true,
        isRegistered: true,
      );
      _packs.add(newPack);

      setState(() {
        _status = 'Saving to storage...';
      });

      // Step 2: Save pack to StorageManager (uses App Group on iOS)
      final saveResult = await _credentialPack.savePack(packId, _appGroupId);
      if (saveResult is CredentialOperationError) {
        setState(() {
          _status = 'Save error: ${saveResult.message}';
          _isLoading = false;
        });
        return;
      }

      setState(() {
        _status = 'Registering with DC API...';
      });

      // Step 3: Register with DC API
      final allPackIds = _packs.map((p) => p.packId).toList();
      final registerResult = await _dcApi.registerCredentials(
        allPackIds,
        'SpruceKit Flutter Example',
      );

      if (registerResult is DcApiError) {
        setState(() {
          _status = 'Registration error: ${registerResult.message}';
          _isLoading = false;
        });
        return;
      }

      setState(() {
        _status = 'mDL created and registered!';
        _isLoading = false;
      });
    } catch (e) {
      setState(() {
        _status = 'Error: $e';
        _isLoading = false;
      });
    }
  }

  Future<void> _deletePack(String packId) async {
    setState(() {
      _isLoading = true;
      _status = 'Deleting pack...';
    });

    try {
      // Step 1: Unregister credential from iOS ID Provider
      final credentials = await _credentialPack.listCredentials(packId);
      final credentialIds = credentials.map((c) => c.id).toList();
      if (credentialIds.isNotEmpty) {
        await _dcApi.unregisterCredentials(credentialIds);
      }

      // Step 2: Delete the pack from storage
      final result = await _credentialPack.deletePack(packId, _appGroupId);
      if (result is CredentialOperationError) {
        setState(() {
          _status = 'Delete error: ${result.message}';
          _isLoading = false;
        });
        return;
      }

      _packs.removeWhere((p) => p.packId == packId);

      // Step 3: Re-register remaining packs with DC API
      // This updates Android's CredentialManager registry with the current list
      final remainingPackIds = _packs.map((p) => p.packId).toList();
      if (remainingPackIds.isNotEmpty) {
        setState(() {
          _status = 'Updating registry...';
        });
        await _dcApi.registerCredentials(
          remainingPackIds,
          'SpruceKit Flutter Example',
        );
      }

      setState(() {
        _status = 'Pack deleted';
        _isLoading = false;
      });
    } catch (e) {
      setState(() {
        _status = 'Error: $e';
        _isLoading = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('DC API Demo')),
      body: _isLoading
          ? const Center(child: CircularProgressIndicator())
          : SingleChildScrollView(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  _buildSupportCard(),
                  const SizedBox(height: 16),
                  _buildActionsCard(),
                  const SizedBox(height: 16),
                  _buildStatusCard(),
                  const SizedBox(height: 16),
                  _buildPacksCard(),
                  if (_packs.any((p) => p.isRegistered)) ...[
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

  Widget _buildActionsCard() {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('Actions', style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 16),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                ElevatedButton.icon(
                  onPressed: _generateAndRegisterMockMdl,
                  icon: const Icon(Icons.add),
                  label: const Text('Generate & Register mDL'),
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
            Text(_status),
          ],
        ),
      ),
    );
  }

  Widget _buildPacksCard() {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                Text(
                  'Credential Packs (${_packs.length})',
                  style: Theme.of(context).textTheme.titleMedium,
                ),
                if (_packs.isNotEmpty)
                  Text(
                    '${_packs.where((p) => p.isRegistered).length} registered',
                    style: TextStyle(
                      color: Colors.green.shade700,
                      fontSize: 12,
                    ),
                  ),
              ],
            ),
            const SizedBox(height: 8),
            if (_packs.isEmpty)
              const Padding(
                padding: EdgeInsets.symmetric(vertical: 16),
                child: Center(
                  child: Text(
                    'No credential packs yet.\nTap "Generate mDL" to create one.',
                    textAlign: TextAlign.center,
                    style: TextStyle(color: Colors.grey),
                  ),
                ),
              )
            else
              ListView.separated(
                shrinkWrap: true,
                physics: const NeverScrollableScrollPhysics(),
                itemCount: _packs.length,
                separatorBuilder: (context, index) => const Divider(),
                itemBuilder: (context, index) {
                  final pack = _packs[index];
                  return _buildPackTile(pack);
                },
              ),
          ],
        ),
      ),
    );
  }

  Widget _buildPackTile(_PackInfo pack) {
    return ListTile(
      contentPadding: EdgeInsets.zero,
      leading: CircleAvatar(
        backgroundColor: pack.isRegistered
            ? Colors.green.shade100
            : Colors.grey.shade100,
        child: Icon(
          pack.hasMdl ? Icons.badge : Icons.credit_card,
          color: pack.isRegistered
              ? Colors.green.shade700
              : Colors.grey.shade700,
        ),
      ),
      title: Text(
        pack.hasMdl ? 'Mobile Driver\'s License' : 'Credential Pack',
        style: const TextStyle(fontWeight: FontWeight.w500),
      ),
      subtitle: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            'ID: ${pack.packId.substring(0, 8)}...',
            style: const TextStyle(fontSize: 11, fontFamily: 'monospace'),
          ),
          const SizedBox(height: 4),
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
            decoration: BoxDecoration(
              color: pack.isRegistered
                  ? Colors.green.shade100
                  : Colors.grey.shade200,
              borderRadius: BorderRadius.circular(4),
            ),
            child: Text(
              pack.isRegistered ? 'Registered' : 'Pending',
              style: TextStyle(
                fontSize: 10,
                color: pack.isRegistered
                    ? Colors.green.shade700
                    : Colors.grey.shade700,
                fontWeight: FontWeight.bold,
              ),
            ),
          ),
        ],
      ),
      trailing: IconButton(
        icon: const Icon(Icons.delete_outline, color: Colors.red),
        onPressed: () => _deletePack(pack.packId),
        tooltip: 'Delete pack',
      ),
    );
  }

  Widget _buildReadyCard() {
    final registeredCount = _packs.where((p) => p.isRegistered).length;
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
              '$registeredCount credential(s) registered and ready to be presented '
              'via the Digital Credentials API.\n\n'
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

/// Information about a credential pack
class _PackInfo {
  final String packId;
  final int credentialCount;
  final bool hasMdl;
  bool isRegistered;

  _PackInfo({
    required this.packId,
    required this.credentialCount,
    required this.hasMdl,
    this.isRegistered = false,
  });
}
