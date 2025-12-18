import 'package:flutter/material.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:sprucekit_mobile/sprucekit_mobile.dart';

/// Demo screen for the Scanner widget
class ScannerDemo extends StatefulWidget {
  const ScannerDemo({super.key});

  @override
  State<ScannerDemo> createState() => _ScannerDemoState();
}

class _ScannerDemoState extends State<ScannerDemo> {
  ScannerType _selectedType = ScannerType.qrCode;
  String? _scannedContent;
  bool _showScanner = false;
  PermissionStatus _cameraPermission = PermissionStatus.denied;

  @override
  void initState() {
    super.initState();
    _checkCameraPermission();
  }

  Future<void> _checkCameraPermission() async {
    final status = await Permission.camera.status;
    setState(() {
      _cameraPermission = status;
    });
  }

  Future<void> _requestCameraPermission() async {
    final status = await Permission.camera.request();
    setState(() {
      _cameraPermission = status;
    });

    if (status.isPermanentlyDenied) {
      if (mounted) {
        _showPermissionDeniedDialog();
      }
    }
  }

  void _showPermissionDeniedDialog() {
    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Camera Permission Required'),
        content: const Text(
          'Camera access is required to scan codes. '
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

  Future<void> _startScanning() async {
    if (_cameraPermission.isGranted) {
      setState(() {
        _scannedContent = null;
        _showScanner = true;
      });
    } else {
      await _requestCameraPermission();
      if (_cameraPermission.isGranted) {
        setState(() {
          _scannedContent = null;
          _showScanner = true;
        });
      }
    }
  }

  void _onRead(String content) {
    setState(() {
      _scannedContent = content;
      _showScanner = false;
    });
  }

  void _onCancel() {
    setState(() {
      _showScanner = false;
    });
  }

  @override
  Widget build(BuildContext context) {
    if (_showScanner) {
      return Scaffold(
        body: SpruceScanner(
          type: _selectedType,
          title: _getTitleForType(_selectedType),
          subtitle: _getSubtitleForType(_selectedType),
          onRead: _onRead,
          onCancel: _onCancel,
        ),
      );
    }

    return Scaffold(
      appBar: AppBar(title: const Text('Scanner Demo')),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            // Permission status card
            _buildPermissionCard(),
            const SizedBox(height: 16),
            const Text(
              'Scanner Type',
              style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold),
            ),
            const SizedBox(height: 8),
            SegmentedButton<ScannerType>(
              segments: const [
                ButtonSegment(
                  value: ScannerType.qrCode,
                  label: Text('QR Code'),
                ),
                ButtonSegment(value: ScannerType.pdf417, label: Text('PDF417')),
                ButtonSegment(value: ScannerType.mrz, label: Text('MRZ')),
              ],
              selected: {_selectedType},
              onSelectionChanged: (Set<ScannerType> selected) {
                setState(() {
                  _selectedType = selected.first;
                });
              },
            ),
            const SizedBox(height: 24),
            ElevatedButton.icon(
              onPressed: _startScanning,
              icon: const Icon(Icons.qr_code_scanner),
              label: Text('Start ${_selectedType.name.toUpperCase()} Scanner'),
            ),
            const SizedBox(height: 24),
            if (_scannedContent != null) ...[
              const Text(
                'Scanned Content:',
                style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold),
              ),
              const SizedBox(height: 8),
              Container(
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                  color: Colors.grey.shade100,
                  borderRadius: BorderRadius.circular(8),
                  border: Border.all(color: Colors.grey.shade300),
                ),
                child: SelectableText(
                  _scannedContent!,
                  style: const TextStyle(fontFamily: 'monospace', fontSize: 14),
                ),
              ),
              const SizedBox(height: 16),
              OutlinedButton.icon(
                onPressed: () {
                  setState(() {
                    _scannedContent = null;
                  });
                },
                icon: const Icon(Icons.clear),
                label: const Text('Clear'),
              ),
            ],
            const SizedBox(height: 32),
            const Divider(),
            const SizedBox(height: 16),
            const Text(
              'About Scanner Types',
              style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold),
            ),
            const SizedBox(height: 8),
            _buildInfoCard(
              'QR Code',
              'Standard QR codes. Used for credential offers, '
                  'verification requests, and general data encoding.',
            ),
            const SizedBox(height: 8),
            _buildInfoCard(
              'PDF417',
              '2D barcode commonly found on driver\'s licenses '
                  'and ID cards. Contains encoded personal information.',
            ),
            const SizedBox(height: 8),
            _buildInfoCard(
              'MRZ',
              'Machine Readable Zone found on passports and some ID cards. '
                  'Contains biographical data in a specific format.',
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildPermissionCard() {
    final isGranted = _cameraPermission.isGranted;
    final isPermanentlyDenied = _cameraPermission.isPermanentlyDenied;

    return Card(
      color: isGranted ? Colors.green.shade50 : Colors.orange.shade50,
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Row(
          children: [
            Icon(
              isGranted ? Icons.check_circle : Icons.warning,
              color: isGranted ? Colors.green : Colors.orange,
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    'Camera Permission',
                    style: TextStyle(
                      fontWeight: FontWeight.bold,
                      color: isGranted
                          ? Colors.green.shade800
                          : Colors.orange.shade800,
                    ),
                  ),
                  Text(
                    isGranted
                        ? 'Granted'
                        : isPermanentlyDenied
                        ? 'Denied - Open settings to enable'
                        : 'Not granted',
                    style: TextStyle(
                      fontSize: 12,
                      color: isGranted
                          ? Colors.green.shade600
                          : Colors.orange.shade600,
                    ),
                  ),
                ],
              ),
            ),
            if (!isGranted)
              TextButton(
                onPressed: isPermanentlyDenied
                    ? openAppSettings
                    : _requestCameraPermission,
                child: Text(isPermanentlyDenied ? 'Settings' : 'Grant'),
              ),
          ],
        ),
      ),
    );
  }

  Widget _buildInfoCard(String title, String description) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(title, style: const TextStyle(fontWeight: FontWeight.bold)),
            const SizedBox(height: 4),
            Text(
              description,
              style: TextStyle(color: Colors.grey.shade600, fontSize: 13),
            ),
          ],
        ),
      ),
    );
  }

  String _getTitleForType(ScannerType type) {
    switch (type) {
      case ScannerType.qrCode:
        return 'Scan QR Code';
      case ScannerType.pdf417:
        return 'Scan Barcode';
      case ScannerType.mrz:
        return 'Scan MRZ';
    }
  }

  String _getSubtitleForType(ScannerType type) {
    switch (type) {
      case ScannerType.qrCode:
        return 'Align the QR code within the frame';
      case ScannerType.pdf417:
        return 'Align the barcode within the frame';
      case ScannerType.mrz:
        return 'Align the document within the frame';
    }
  }
}
