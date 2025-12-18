import 'dart:io';

import 'package:flutter/material.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:qr_flutter/qr_flutter.dart';
import 'package:sprucekit_mobile/sprucekit_mobile.dart';

/// Demo screen for ISO 18013-5 mDL sharing via QR code
class MdlShareDemo extends StatefulWidget {
  const MdlShareDemo({super.key});

  @override
  State<MdlShareDemo> createState() => _MdlShareDemoState();
}

class _MdlShareDemoState extends State<MdlShareDemo>
    with WidgetsBindingObserver
    implements MdlPresentationCallback {
  final _mdlPresentation = MdlPresentation();
  final _spruceUtils = SpruceUtils();

  MdlPresentationState _state = MdlPresentationState.uninitialized;
  String? _qrCodeUri;
  String? _error;
  List<MdlItemsRequest>? _itemsRequests;
  Map<String, Map<String, List<String>>> _selectedNamespaces = {};

  String? _packId;
  String? _credentialId;

  bool _isGeneratingMdl = false;
  bool _bluetoothGranted = false;
  bool _bluetoothEnabled = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    MdlPresentationCallback.setUp(this);
    _checkBluetoothStatus();
  }

  @override
  void dispose() {
    _mdlPresentation.cancel();
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      _checkBluetoothStatus();
    }
  }

  Future<void> _checkBluetoothStatus() async {
    final serviceStatus = await Permission.bluetooth.serviceStatus;

    if (Platform.isAndroid) {
      // Android requires explicit permissions
      final btConnect = await Permission.bluetoothConnect.status;
      final btScan = await Permission.bluetoothScan.status;
      final btAdvertise = await Permission.bluetoothAdvertise.status;

      setState(() {
        _bluetoothGranted =
            btConnect.isGranted && btScan.isGranted && btAdvertise.isGranted;
        _bluetoothEnabled = serviceStatus == ServiceStatus.enabled;
      });
    } else {
      // iOS: Bluetooth permission is requested automatically when used
      // We just check if Bluetooth is enabled
      final btStatus = await Permission.bluetooth.status;
      setState(() {
        _bluetoothGranted =
            btStatus.isGranted || btStatus == PermissionStatus.limited;
        _bluetoothEnabled = serviceStatus == ServiceStatus.enabled;
      });
    }
  }

  Future<void> _requestBluetoothPermission() async {
    if (Platform.isAndroid) {
      final statuses = await [
        Permission.bluetoothConnect,
        Permission.bluetoothScan,
        Permission.bluetoothAdvertise,
      ].request();

      final allGranted = statuses.values.every((s) => s.isGranted);
      setState(() {
        _bluetoothGranted = allGranted;
      });

      if (!allGranted) {
        _showPermissionDeniedDialog();
      }
    } else {
      // iOS: Request bluetooth permission
      final status = await Permission.bluetooth.request();
      setState(() {
        _bluetoothGranted =
            status.isGranted || status == PermissionStatus.limited;
      });

      if (!_bluetoothGranted) {
        _showPermissionDeniedDialog();
      }
    }
  }

  void _showPermissionDeniedDialog() {
    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Bluetooth Permission Required'),
        content: const Text(
          'Bluetooth access is required to share your mDL. '
          'Please enable it in your device settings.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('Cancel'),
          ),
          TextButton(
            onPressed: () {
              Navigator.pop(context);
              openAppSettings();
            },
            child: const Text('Open Settings'),
          ),
        ],
      ),
    );
  }

  Future<void> _generateMockMdl() async {
    setState(() {
      _isGeneratingMdl = true;
      _error = null;
    });

    try {
      final result = await _spruceUtils.generateMockMdl('testMdl');

      if (result is GenerateMockMdlSuccess) {
        setState(() {
          _packId = result.packId;
          _credentialId = result.credentialId;
          _isGeneratingMdl = false;
        });
      } else if (result is GenerateMockMdlError) {
        setState(() {
          _error = result.message;
          _isGeneratingMdl = false;
        });
      }
    } catch (e) {
      setState(() {
        _error = 'Error generating mock mDL: $e';
        _isGeneratingMdl = false;
      });
    }
  }

  Future<void> _startPresentation() async {
    if (_packId == null || _credentialId == null) {
      setState(() {
        _error = 'No mDL available. Generate one first.';
      });
      return;
    }

    // Check permissions first
    if (!_bluetoothGranted) {
      await _requestBluetoothPermission();
      if (!_bluetoothGranted) return;
    }

    // Check if Bluetooth is enabled
    await _checkBluetoothStatus();
    if (!_bluetoothEnabled) {
      _showBluetoothDisabledDialog();
      return;
    }

    setState(() {
      _error = null;
      _state = MdlPresentationState.initializing;
    });

    try {
      final result = await _mdlPresentation.initializeQrPresentation(
        _packId!,
        _credentialId!,
      );

      if (result is MdlPresentationError) {
        setState(() {
          _error = result.message;
          _state = MdlPresentationState.error;
        });
      }
    } catch (e) {
      setState(() {
        _error = 'Error starting presentation: $e';
        _state = MdlPresentationState.error;
      });
    }
  }

  void _showBluetoothDisabledDialog() {
    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Bluetooth Required'),
        content: const Text('Please turn on Bluetooth to share your mDL.'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('Cancel'),
          ),
          TextButton(
            onPressed: () {
              Navigator.pop(context);
              openAppSettings();
            },
            child: const Text('Open Settings'),
          ),
        ],
      ),
    );
  }

  void _cancelPresentation() {
    _mdlPresentation.cancel();
    setState(() {
      _state = MdlPresentationState.uninitialized;
      _qrCodeUri = null;
      _itemsRequests = null;
      _selectedNamespaces = {};
    });
  }

  Future<void> _submitResponse() async {
    if (_selectedNamespaces.isEmpty) {
      setState(() {
        _error = 'Select at least one field to share';
      });
      return;
    }

    try {
      final result = await _mdlPresentation.submitNamespaces(
        _selectedNamespaces,
      );

      if (result is MdlPresentationError) {
        setState(() {
          _error = result.message;
        });
      }
    } catch (e) {
      setState(() {
        _error = 'Error submitting response: $e';
      });
    }
  }

  // MdlPresentationCallback implementation
  @override
  void onStateChange(MdlPresentationStateUpdate update) {
    setState(() {
      _state = update.state;
      _qrCodeUri = update.qrCodeUri;
      _error = update.error;

      if (update.itemsRequests != null) {
        _itemsRequests = update.itemsRequests;
        // Pre-select all fields
        _selectedNamespaces = {};
        for (final itemsRequest in update.itemsRequests!) {
          final docType = itemsRequest.docType;
          _selectedNamespaces[docType] = {};
          for (final nsRequest in itemsRequest.namespaces) {
            _selectedNamespaces[docType]![nsRequest.namespace] = nsRequest.items
                .map((item) => item.name)
                .toList();
          }
        }
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Share mDL Demo'),
        actions: [
          if (_state != MdlPresentationState.uninitialized)
            IconButton(
              icon: const Icon(Icons.close),
              onPressed: _cancelPresentation,
              tooltip: 'Cancel',
            ),
        ],
      ),
      body: _buildContent(),
    );
  }

  Widget _buildContent() {
    // Step 1: No mDL yet - show generate button
    if (_packId == null) {
      return _buildGenerateMdlView();
    }

    // Step 2: Have mDL, not started yet - show start button
    if (_state == MdlPresentationState.uninitialized) {
      return _buildStartPresentationView();
    }

    // Step 3: Initializing
    if (_state == MdlPresentationState.initializing) {
      return const Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            CircularProgressIndicator(),
            SizedBox(height: 16),
            Text('Initializing Bluetooth...'),
          ],
        ),
      );
    }

    // Step 4: QR code ready
    if (_state == MdlPresentationState.engagingQrCode && _qrCodeUri != null) {
      return _buildQrCodeView();
    }

    // Step 5: Connected, waiting for request or selecting namespaces
    if (_state == MdlPresentationState.connected) {
      return const Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            CircularProgressIndicator(),
            SizedBox(height: 16),
            Text('Reader connected, waiting for request...'),
          ],
        ),
      );
    }

    if (_state == MdlPresentationState.selectingNamespaces &&
        _itemsRequests != null) {
      return _buildSelectNamespacesView();
    }

    // Step 6: Sending response
    if (_state == MdlPresentationState.sendingResponse) {
      return const Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            CircularProgressIndicator(),
            SizedBox(height: 16),
            Text('Sending response...'),
          ],
        ),
      );
    }

    // Step 7: Success
    if (_state == MdlPresentationState.success) {
      return _buildSuccessView();
    }

    // Step 8: Timeout
    if (_state == MdlPresentationState.timeout) {
      return _buildTimeoutView();
    }

    // Step 9: Reader disconnected
    if (_state == MdlPresentationState.readerDisconnected) {
      return _buildReaderDisconnectedView();
    }

    // Step 10: Bluetooth required
    if (_state == MdlPresentationState.bluetoothRequired) {
      return _buildBluetoothRequiredView();
    }

    // Step 11: Bluetooth authorization required
    if (_state == MdlPresentationState.bluetoothAuthorizationRequired) {
      return _buildBluetoothAuthorizationRequiredView();
    }

    // Step 12: Error
    if (_state == MdlPresentationState.error) {
      return _buildErrorView();
    }

    return const Center(child: Text('Unknown state'));
  }

  Widget _buildGenerateMdlView() {
    return SingleChildScrollView(
      padding: const EdgeInsets.all(16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          _buildBluetoothCard(),
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
                    'First, generate a mock mobile driver\'s license for testing. '
                    'This creates a self-signed test credential.',
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
          if (_error != null) ...[
            const SizedBox(height: 16),
            Card(
              color: Colors.red.shade50,
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: Row(
                  children: [
                    Icon(Icons.error, color: Colors.red.shade700),
                    const SizedBox(width: 12),
                    Expanded(
                      child: Text(
                        _error!,
                        style: TextStyle(color: Colors.red.shade700),
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ],
        ],
      ),
    );
  }

  Widget _buildStartPresentationView() {
    return SingleChildScrollView(
      padding: const EdgeInsets.all(16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          _buildBluetoothCard(),
          const SizedBox(height: 16),
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
                    'Step 2: Share via QR Code',
                    style: Theme.of(context).textTheme.titleMedium,
                  ),
                  const SizedBox(height: 8),
                  const Text(
                    'Present your mDL to a verifier by showing them a QR code. '
                    'The verifier will scan the code to establish a Bluetooth connection.',
                  ),
                  const SizedBox(height: 16),
                  ElevatedButton.icon(
                    onPressed: _startPresentation,
                    icon: const Icon(Icons.qr_code),
                    label: const Text('Start Sharing'),
                  ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildQrCodeView() {
    return SingleChildScrollView(
      padding: const EdgeInsets.all(16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.center,
        children: [
          Card(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                children: [
                  QrImageView(
                    data: _qrCodeUri!,
                    version: QrVersions.auto,
                    size: 280,
                    backgroundColor: Colors.white,
                  ),
                ],
              ),
            ),
          ),
          const SizedBox(height: 16),
          Text(
            'Present this QR code to a verifier',
            style: Theme.of(context).textTheme.titleMedium,
            textAlign: TextAlign.center,
          ),
          const SizedBox(height: 8),
          const Text(
            'The verifier will scan this code to establish a Bluetooth connection. '
            'You will see a consent dialog after they request your data.',
            textAlign: TextAlign.center,
          ),
          const SizedBox(height: 16),
          OutlinedButton.icon(
            onPressed: _cancelPresentation,
            icon: const Icon(Icons.close),
            label: const Text('Cancel'),
          ),
        ],
      ),
    );
  }

  Widget _buildSelectNamespacesView() {
    return Column(
      children: [
        Expanded(
          child: ListView(
            padding: const EdgeInsets.all(16),
            children: [
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
                      const Text(
                        'Select the fields you want to share with the verifier:',
                      ),
                    ],
                  ),
                ),
              ),
              const SizedBox(height: 16),
              for (final itemsRequest in _itemsRequests!)
                _buildItemsRequestCard(itemsRequest),
            ],
          ),
        ),
        Padding(
          padding: const EdgeInsets.all(16),
          child: Row(
            children: [
              Expanded(
                child: OutlinedButton(
                  onPressed: _cancelPresentation,
                  child: const Text('Deny'),
                ),
              ),
              const SizedBox(width: 16),
              Expanded(
                child: ElevatedButton(
                  onPressed: _submitResponse,
                  child: const Text('Approve'),
                ),
              ),
            ],
          ),
        ),
      ],
    );
  }

  Widget _buildItemsRequestCard(MdlItemsRequest itemsRequest) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              itemsRequest.docType,
              style: Theme.of(context).textTheme.titleSmall,
            ),
            const Divider(),
            for (final nsRequest in itemsRequest.namespaces) ...[
              Text(
                nsRequest.namespace,
                style: Theme.of(
                  context,
                ).textTheme.bodySmall?.copyWith(fontWeight: FontWeight.bold),
              ),
              for (final item in nsRequest.items)
                CheckboxListTile(
                  title: Text(item.name),
                  subtitle: item.intentToRetain
                      ? const Text('Verifier will retain')
                      : null,
                  value:
                      _selectedNamespaces[itemsRequest.docType]?[nsRequest
                              .namespace]
                          ?.contains(item.name) ??
                      false,
                  onChanged: (value) {
                    setState(() {
                      final docType = itemsRequest.docType;
                      final namespace = nsRequest.namespace;
                      _selectedNamespaces[docType] ??= {};
                      _selectedNamespaces[docType]![namespace] ??= [];

                      if (value == true) {
                        if (!_selectedNamespaces[docType]![namespace]!.contains(
                          item.name,
                        )) {
                          _selectedNamespaces[docType]![namespace]!.add(
                            item.name,
                          );
                        }
                      } else {
                        _selectedNamespaces[docType]![namespace]!.remove(
                          item.name,
                        );
                      }
                    });
                  },
                ),
            ],
          ],
        ),
      ),
    );
  }

  Widget _buildSuccessView() {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(32),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(Icons.check_circle, size: 80, color: Colors.green.shade600),
            const SizedBox(height: 24),
            Text('Success!', style: Theme.of(context).textTheme.headlineMedium),
            const SizedBox(height: 8),
            const Text(
              'Your credential was shared successfully.',
              textAlign: TextAlign.center,
            ),
            const SizedBox(height: 32),
            ElevatedButton(
              onPressed: _cancelPresentation,
              child: const Text('Done'),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildTimeoutView() {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(32),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(Icons.timer_off, size: 80, color: Colors.orange.shade600),
            const SizedBox(height: 24),
            Text(
              'Session Timed Out',
              style: Theme.of(context).textTheme.headlineMedium,
            ),
            const SizedBox(height: 8),
            const Text(
              'The session timed out before the verifier could connect.',
              textAlign: TextAlign.center,
            ),
            const SizedBox(height: 32),
            ElevatedButton.icon(
              onPressed: _startPresentation,
              icon: const Icon(Icons.refresh),
              label: const Text('Try Again'),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildReaderDisconnectedView() {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(32),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(Icons.link_off, size: 80, color: Colors.orange.shade600),
            const SizedBox(height: 24),
            Text(
              'Reader Disconnected',
              style: Theme.of(context).textTheme.headlineMedium,
            ),
            const SizedBox(height: 8),
            const Text(
              'The verifier disconnected before the transfer was complete.',
              textAlign: TextAlign.center,
            ),
            const SizedBox(height: 32),
            ElevatedButton.icon(
              onPressed: _startPresentation,
              icon: const Icon(Icons.refresh),
              label: const Text('Try Again'),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildBluetoothRequiredView() {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(32),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(
              Icons.bluetooth_disabled,
              size: 80,
              color: Colors.orange.shade600,
            ),
            const SizedBox(height: 24),
            Text(
              'Bluetooth Required',
              style: Theme.of(context).textTheme.headlineMedium,
            ),
            const SizedBox(height: 8),
            const Text(
              'Please turn on Bluetooth to share your mDL.',
              textAlign: TextAlign.center,
            ),
            const SizedBox(height: 32),
            ElevatedButton.icon(
              onPressed: () {
                openAppSettings();
              },
              icon: const Icon(Icons.settings),
              label: const Text('Open Settings'),
            ),
            const SizedBox(height: 16),
            OutlinedButton.icon(
              onPressed: _cancelPresentation,
              icon: const Icon(Icons.close),
              label: const Text('Cancel'),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildBluetoothAuthorizationRequiredView() {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(32),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(
              Icons.bluetooth_disabled,
              size: 80,
              color: Colors.orange.shade600,
            ),
            const SizedBox(height: 24),
            Text(
              'Bluetooth Authorization Required',
              style: Theme.of(context).textTheme.headlineMedium,
            ),
            const SizedBox(height: 8),
            const Text(
              'Please authorize Bluetooth access in Settings to share your mDL.',
              textAlign: TextAlign.center,
            ),
            const SizedBox(height: 32),
            ElevatedButton.icon(
              onPressed: () {
                openAppSettings();
              },
              icon: const Icon(Icons.settings),
              label: const Text('Open Settings'),
            ),
            const SizedBox(height: 16),
            OutlinedButton.icon(
              onPressed: _cancelPresentation,
              icon: const Icon(Icons.close),
              label: const Text('Cancel'),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildErrorView() {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(32),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(Icons.error, size: 80, color: Colors.red.shade600),
            const SizedBox(height: 24),
            Text('Error', style: Theme.of(context).textTheme.headlineMedium),
            const SizedBox(height: 8),
            Text(
              _error ?? 'An unknown error occurred.',
              textAlign: TextAlign.center,
            ),
            const SizedBox(height: 32),
            ElevatedButton.icon(
              onPressed: _cancelPresentation,
              icon: const Icon(Icons.refresh),
              label: const Text('Try Again'),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildBluetoothCard() {
    final isReady = _bluetoothGranted && _bluetoothEnabled;
    final statusText = !_bluetoothGranted
        ? 'Permission required'
        : !_bluetoothEnabled
        ? 'Bluetooth is off'
        : 'Ready';

    return Card(
      color: isReady ? Colors.green.shade50 : Colors.orange.shade50,
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Row(
          children: [
            Icon(
              isReady ? Icons.bluetooth_connected : Icons.bluetooth_disabled,
              color: isReady ? Colors.green.shade700 : Colors.orange.shade700,
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    'Bluetooth',
                    style: TextStyle(
                      fontWeight: FontWeight.bold,
                      color: isReady
                          ? Colors.green.shade800
                          : Colors.orange.shade800,
                    ),
                  ),
                  Text(
                    statusText,
                    style: TextStyle(
                      fontSize: 12,
                      color: isReady
                          ? Colors.green.shade600
                          : Colors.orange.shade600,
                    ),
                  ),
                ],
              ),
            ),
            if (!_bluetoothGranted)
              TextButton(
                onPressed: _requestBluetoothPermission,
                child: const Text('Grant'),
              )
            else if (!_bluetoothEnabled)
              TextButton(
                onPressed: () => openAppSettings(),
                child: const Text('Enable'),
              ),
          ],
        ),
      ),
    );
  }
}
