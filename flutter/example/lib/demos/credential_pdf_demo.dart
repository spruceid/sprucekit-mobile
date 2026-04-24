import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:path_provider/path_provider.dart';
import 'package:share_plus/share_plus.dart';
import 'package:sprucekit_mobile/sprucekit_mobile.dart';

/// Demo screen for generating a PDF from an mDL credential
class CredentialPdfDemo extends StatefulWidget {
  const CredentialPdfDemo({super.key});

  @override
  State<CredentialPdfDemo> createState() => _CredentialPdfDemoState();
}

class _CredentialPdfDemoState extends State<CredentialPdfDemo> {
  final _spruceUtils = SpruceUtils();

  String? _rawCredential;
  String? _error;
  String? _message;
  bool _isGeneratingMdl = false;
  bool _isGeneratingPdf = false;
  String? _pdfFilePath;
  bool _includeBarcodes = true;

  /// Build demo barcode supplements from mock credential data.
  ///
  /// In a real app, the QR data would be a VP Token and the PDF-417 data
  /// would be AAMVA-encoded bytes. Here we use representative demo content
  /// so the barcodes are scannable and visually verify the rendering.
  List<PdfSupplement> _buildDemoSupplements() {
    if (!_includeBarcodes) return [];

    // QR Code: JSON with key credential fields (simulates a VP Token)
    final qrPayload = jsonEncode({
      'type': 'mDL',
      'given_name': 'John',
      'family_name': 'Doe',
      'birth_date': '1990-01-15',
      'document_number': 'DL-123456789',
      'expiry_date': '2029-01-15',
    });

    // PDF-417: key=value pairs (simulates AAMVA barcode data)
    const pdf417Payload =
        'DAQ DL-123456789\n'
        'DCS Doe\n'
        'DCT John\n'
        'DBB 01151990\n'
        'DBA 01152029\n';

    return [
      PdfSupplement(
        type: PdfSupplementType.barcode,
        data: Uint8List.fromList(utf8.encode(qrPayload)),
        barcodeType: PdfBarcodeType.qrCode,
      ),
      PdfSupplement(
        type: PdfSupplementType.barcode,
        data: Uint8List.fromList(utf8.encode(pdf417Payload)),
        barcodeType: PdfBarcodeType.pdf417,
      ),
    ];
  }

  Future<void> _generateMockMdl() async {
    setState(() {
      _isGeneratingMdl = true;
      _error = null;
      _message = null;
      _pdfFilePath = null;
    });

    try {
      final result = await _spruceUtils.generateMockMdl('testMdlPdf');

      setState(() {
        _isGeneratingMdl = false;
        if (result is GenerateMockMdlSuccess) {
          _rawCredential = result.rawCredential;
          _message =
              'Mock mDL generated successfully!\n\nPack ID: ${result.packId}\nCredential ID: ${result.credentialId}';
        } else if (result is GenerateMockMdlError) {
          _error = result.message;
        }
      });
    } catch (e) {
      setState(() {
        _isGeneratingMdl = false;
        _error = 'Error generating mock mDL: $e';
      });
    }
  }

  Future<void> _generatePdf() async {
    if (_rawCredential == null) {
      setState(() => _error = 'Generate a mock mDL first');
      return;
    }

    setState(() {
      _isGeneratingPdf = true;
      _error = null;
      _pdfFilePath = null;
    });

    try {
      final supplements = _buildDemoSupplements();
      final Uint8List pdfBytes = await _spruceUtils.generateCredentialPdf(
        _rawCredential!,
        supplements,
      );

      // Write PDF to temporary directory
      final tempDir = await getTemporaryDirectory();
      final pdfFile = File('${tempDir.path}/credential.pdf');
      await pdfFile.writeAsBytes(pdfBytes);

      setState(() {
        _isGeneratingPdf = false;
        _pdfFilePath = pdfFile.path;
        _message =
            'PDF generated successfully! (${pdfBytes.length} bytes)'
            '${_includeBarcodes ? '\nIncludes QR Code + PDF-417 barcodes' : ''}';
      });
    } catch (e) {
      setState(() {
        _isGeneratingPdf = false;
        _error = 'Error generating PDF: $e';
      });
    }
  }

  Future<void> _sharePdf() async {
    if (_pdfFilePath == null) return;

    final box = context.findRenderObject() as RenderBox?;
    await Share.shareXFiles(
      [XFile(_pdfFilePath!, mimeType: 'application/pdf')],
      subject: 'Credential PDF',
      sharePositionOrigin: box != null
          ? box.localToGlobal(Offset.zero) & box.size
          : null,
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Credential PDF')),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            // Step 1: Generate mock mDL
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
                      'Generate a mock mobile driver\'s license to use as '
                      'the source credential for PDF generation.',
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
                        _isGeneratingMdl
                            ? 'Generating...'
                            : _rawCredential != null
                            ? 'Regenerate Test mDL'
                            : 'Generate Test mDL',
                      ),
                    ),
                  ],
                ),
              ),
            ),

            const SizedBox(height: 16),

            // Step 2: Generate PDF
            Card(
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      'Step 2: Generate PDF',
                      style: Theme.of(context).textTheme.titleMedium,
                    ),
                    const SizedBox(height: 8),
                    const Text(
                      'Convert the mDL credential into a PDF document '
                      'that can be shared or printed.',
                    ),
                    const SizedBox(height: 12),
                    SwitchListTile(
                      title: const Text('Include Barcodes'),
                      subtitle: const Text(
                        'Add QR Code and PDF-417 barcodes with demo data',
                      ),
                      value: _includeBarcodes,
                      onChanged: (value) {
                        setState(() => _includeBarcodes = value);
                      },
                      contentPadding: EdgeInsets.zero,
                    ),
                    const SizedBox(height: 8),
                    ElevatedButton.icon(
                      onPressed: _rawCredential == null || _isGeneratingPdf
                          ? null
                          : _generatePdf,
                      icon: _isGeneratingPdf
                          ? const SizedBox(
                              width: 16,
                              height: 16,
                              child: CircularProgressIndicator(strokeWidth: 2),
                            )
                          : const Icon(Icons.picture_as_pdf),
                      label: Text(
                        _isGeneratingPdf ? 'Generating PDF...' : 'Generate PDF',
                      ),
                    ),
                  ],
                ),
              ),
            ),

            const SizedBox(height: 16),

            // Step 3: Share PDF
            if (_pdfFilePath != null)
              Card(
                color: Colors.green.shade50,
                child: Padding(
                  padding: const EdgeInsets.all(16),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Row(
                        children: [
                          Icon(
                            Icons.check_circle,
                            color: Colors.green.shade700,
                          ),
                          const SizedBox(width: 8),
                          Text(
                            'PDF Ready',
                            style: Theme.of(context).textTheme.titleMedium
                                ?.copyWith(color: Colors.green.shade700),
                          ),
                        ],
                      ),
                      const SizedBox(height: 12),
                      Text(
                        'File: ${_pdfFilePath!.split('/').last}',
                        style: const TextStyle(
                          fontFamily: 'monospace',
                          fontSize: 12,
                        ),
                      ),
                      const SizedBox(height: 16),
                      ElevatedButton.icon(
                        onPressed: _sharePdf,
                        icon: const Icon(Icons.share),
                        label: const Text('Share PDF'),
                      ),
                    ],
                  ),
                ),
              ),

            // Error message
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

            // Success message
            if (_message != null && _error == null) ...[
              const SizedBox(height: 16),
              Card(
                color: Colors.blue.shade50,
                child: Padding(
                  padding: const EdgeInsets.all(16),
                  child: Row(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Icon(Icons.info, color: Colors.blue.shade700),
                      const SizedBox(width: 12),
                      Expanded(
                        child: Text(
                          _message!,
                          style: TextStyle(color: Colors.blue.shade700),
                        ),
                      ),
                    ],
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
