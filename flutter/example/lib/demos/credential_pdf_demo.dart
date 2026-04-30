import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:path_provider/path_provider.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:share_plus/share_plus.dart';
import 'package:sprucekit_mobile/sprucekit_mobile.dart';

/// Maximum consecutive scan-then-verify failures before we give up and
/// surface the error. At V40 QR density occasional bit-flips slip past the
/// QR error correction layer; those get retried silently. Anything that
/// fails this many times in a row is almost certainly a real problem
/// (wrong QR, expired credential, corrupted issuer signature, etc.).
const int _kMaxScanAttempts = 5;

/// Fields the wallet will hide from the QR-embedded VP token.
///
/// `portrait` is the only field that genuinely cannot fit in a QR (a base64
/// JPEG runs to multiple kilobytes on its own). Every other claim stays in
/// the VP so the verifier sees a meaningful payload. Selective disclosure
/// preserves the issuer signature for the remaining claims.
const _kHiddenFieldsForQr = <String>['portrait'];

/// Which barcode the step-3 scanner is currently reading.
///
/// QR uses the high-density (ML Kit / Vision) scanner and runs the scanned
/// payload through `verifySdJwtVp`. PDF-417 uses the dedicated PDF-417
/// scanner and just stashes the decoded AAMVA bytes for inspection — no
/// cryptographic verification (the AAMVA DL subfile is plain text).
enum _ScanMode { qr, pdf417 }

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

  // ── Step 3: Scan + verify the QR we just embedded ──────────────────────
  bool _isScanning = false;
  _ScanMode _scanMode = _ScanMode.qr;
  bool? _verifySuccess;
  String? _verifyMessage;
  int _scanFailureCount = 0;

  /// Raw text decoded from a PDF-417 scan — typically AAMVA-format bytes
  /// starting with `@\n\x1e\rANSI …`. Shown verbatim in a monospace block
  /// so the user can eyeball the DL subfile fields.
  String? _scannedPdf417Content;

  /// Build the QR + PDF-417 supplements that get embedded in the PDF.
  ///
  /// **QR**: A real SD-JWT VP token (generated from a test mDL credential,
  /// disclosed claims minus `portrait`, then deflate+base10 compressed for
  /// QR numeric mode). This matches the iOS / Android Showcase flow exactly,
  /// so the same PDF can be exercised across all three platforms — and the
  /// Showcase Android `VerifySdJwtView` can scan and verify it.
  ///
  /// **PDF-417**: Real AAMVA DL subfile bytes derived from the wallet's
  /// stored mDL via [SpruceUtils.generateAamvaPdf417Bytes]. `vcBarcode = null`
  /// keeps us in DL-only mode (no signed ZZ subfile); when CA DMV starts
  /// issuing VC Barcodes the wallet will fetch those bytes and pass them
  /// through here. On encode failure, falls back to a mock so the PDF still
  /// renders.
  Future<List<PdfSupplement>> _buildDemoSupplements() async {
    if (!_includeBarcodes) return [];

    // ── QR: real SD-JWT VP, hide portrait, compress for QR ────────────────
    final testSdJwt = await _spruceUtils.generateTestMdlSdJwtCompact();
    final qrBytes = await _spruceUtils.generateCompressedVpToken(
      testSdJwt,
      VpTokenParams(
        disclosure: DisclosureSelection(
          type: DisclosureSelectionType.hideOnly,
          fields: _kHiddenFieldsForQr,
        ),
        // No live verifier in this demo — KB-JWT isn't signed today, so
        // these are reserved fields. Set them to recognisable placeholders
        // so logs / debug breakpoints make it obvious where they came from.
        audience: 'https://demo.spruceid.com',
        nonce: null,
      ),
    );

    // ── PDF-417: real AAMVA DL bytes from the stored mDL ──────────────────
    Uint8List pdf417Bytes;
    try {
      pdf417Bytes = await _spruceUtils.generateAamvaPdf417Bytes(
        _rawCredential!,
        null,
      );
    } catch (_) {
      const fallback =
          'DAQ DL-123456789\n'
          'DCS Doe\n'
          'DCT John\n'
          'DBB 01151990\n'
          'DBA 01152029\n';
      pdf417Bytes = Uint8List.fromList(utf8.encode(fallback));
    }

    return [
      PdfSupplement(
        type: PdfSupplementType.barcode,
        data: qrBytes,
        barcodeType: PdfBarcodeType.qrCode,
      ),
      PdfSupplement(
        type: PdfSupplementType.barcode,
        data: pdf417Bytes,
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
      final supplements = await _buildDemoSupplements();
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

  /// Step 3 entry point: ensure camera permission, then open a scanner of
  /// the requested type ([_ScanMode.qr] or [_ScanMode.pdf417]). The native
  /// [SpruceScanner] platform view does not request permission itself
  /// (unlike the showcase's `ScanningComponent`), so the Flutter side has
  /// to do it before the view goes on-screen — otherwise the camera
  /// silently never starts.
  Future<void> _startScan(_ScanMode mode) async {
    final status = await Permission.camera.request();
    if (!status.isGranted) {
      setState(() {
        _verifySuccess = false;
        _verifyMessage = status.isPermanentlyDenied
            ? 'Camera permission permanently denied — enable it in Settings.'
            : 'Camera permission denied — cannot scan.';
      });
      if (status.isPermanentlyDenied && mounted) {
        // Best-effort nudge to settings so the user has a path forward.
        await openAppSettings();
      }
      return;
    }
    setState(() {
      _resetScanState();
      _scanMode = mode;
      _isScanning = true;
    });
  }

  /// Called when the high-density scanner reads a QR. The scanned payload is
  /// the `"9"`-prefixed compressed VP token; [verifySdJwtVp] auto-detects
  /// the prefix and decompresses transparently before verifying.
  ///
  /// At V40 QR density, ML Kit / Vision occasionally return a "successfully
  /// decoded" string that has a few flipped bytes — passes the QR-level CRC
  /// but fails downstream signature verification. To make that invisible to
  /// the user, we keep the scanner running on failure and silently retry
  /// the next frame, only dismissing on success or [_kMaxScanAttempts]
  /// consecutive failures (which would indicate a real problem rather than
  /// noise — e.g. wrong QR, expired credential, etc.).
  Future<void> _onScanRead(String content) async {
    // PDF-417 path: AAMVA DL bytes are plain text — no signature to check,
    // first successful read is what we want.
    if (_scanMode == _ScanMode.pdf417) {
      setState(() {
        _isScanning = false;
        _scannedPdf417Content = content;
      });
      return;
    }

    // QR path: SD-JWT VP — verify issuer signature; retry on transient
    // QR-density bit flips.
    try {
      await _spruceUtils.verifySdJwtVp(content);
      // Success: dismiss scanner and show result.
      _scanFailureCount = 0;
      setState(() {
        _isScanning = false;
        _verifySuccess = true;
        _verifyMessage = 'Valid SD-JWT VP — issuer signature verified.';
      });
    } catch (e) {
      _scanFailureCount++;
      if (_scanFailureCount >= _kMaxScanAttempts) {
        // Repeated failures → likely a real verification issue (not just
        // a flaky frame). Surface the error so the user knows.
        _scanFailureCount = 0;
        setState(() {
          _isScanning = false;
          _verifySuccess = false;
          _verifyMessage =
              'Verification failed after $_kMaxScanAttempts attempts: $e';
        });
      }
      // Otherwise: silent retry — scanner stays open, next frame gets a shot.
    }
  }

  /// Reset attempt counter and verify state when the scanner reopens. Called
  /// from [_startScan] before flipping `_isScanning = true`.
  void _resetScanState() {
    _scanFailureCount = 0;
    _verifySuccess = null;
    _verifyMessage = null;
    _scannedPdf417Content = null;
  }

  void _cancelScan() {
    setState(() => _isScanning = false);
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
    // While the scanner is active, replace the demo body with a full-screen
    // SpruceScanner. Returning to the demo on read / cancel goes through
    // _onScanRead / _cancelScan, which clear _isScanning.
    if (_isScanning) {
      final isPdf417 = _scanMode == _ScanMode.pdf417;
      return Scaffold(
        body: SafeArea(
          child: SpruceScanner(
            type: isPdf417 ? ScannerType.pdf417 : ScannerType.qrCodeHighDensity,
            title: isPdf417 ? 'Scan PDF-417' : 'Scan PDF QR',
            subtitle: isPdf417
                ? 'Frame the PDF-417 barcode at the bottom of the PDF'
                : 'Frame the QR on the generated PDF',
            onRead: _onScanRead,
            onCancel: _cancelScan,
          ),
        ),
      );
    }

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

            // Step 3: Scan & verify any SD-JWT VP QR (PDF-generated or not).
            // Always shown — the scanner doesn't depend on this demo's earlier
            // steps; you can scan a QR from another device, the iOS / Android
            // showcase, or a saved PDF on screen.
            const SizedBox(height: 16),
            Card(
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      'Step 3: Scan & Verify',
                      style: Theme.of(context).textTheme.titleMedium,
                    ),
                    const SizedBox(height: 8),
                    const Text(
                      'Two scanners — point either at the corresponding '
                      'barcode on the generated PDF (or any other source).\n\n'
                      '• QR: high-density (ML Kit / Vision) scanner that '
                      'verifies the SD-JWT VP signature.\n'
                      '• PDF-417: dedicated PDF-417 scanner that decodes the '
                      'AAMVA DL subfile to plain text (no signature to '
                      'check at this stage).',
                    ),
                    const SizedBox(height: 16),
                    ElevatedButton.icon(
                      onPressed: () => _startScan(_ScanMode.qr),
                      icon: const Icon(Icons.qr_code_scanner),
                      label: const Text('Scan QR & Verify'),
                    ),
                    const SizedBox(height: 8),
                    OutlinedButton.icon(
                      onPressed: () => _startScan(_ScanMode.pdf417),
                      icon: const Icon(Icons.barcode_reader),
                      label: const Text('Scan PDF-417'),
                    ),
                    if (_verifyMessage != null) ...[
                      const SizedBox(height: 12),
                      Container(
                        padding: const EdgeInsets.all(12),
                        decoration: BoxDecoration(
                          color: _verifySuccess == true
                              ? Colors.green.shade50
                              : Colors.red.shade50,
                          borderRadius: BorderRadius.circular(8),
                        ),
                        child: Row(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Icon(
                              _verifySuccess == true
                                  ? Icons.check_circle
                                  : Icons.error,
                              color: _verifySuccess == true
                                  ? Colors.green.shade700
                                  : Colors.red.shade700,
                            ),
                            const SizedBox(width: 12),
                            Expanded(
                              child: Text(
                                _verifyMessage!,
                                style: TextStyle(
                                  color: _verifySuccess == true
                                      ? Colors.green.shade700
                                      : Colors.red.shade700,
                                ),
                              ),
                            ),
                          ],
                        ),
                      ),
                    ],
                    if (_scannedPdf417Content != null) ...[
                      const SizedBox(height: 12),
                      Container(
                        padding: const EdgeInsets.all(12),
                        decoration: BoxDecoration(
                          color: Colors.blue.shade50,
                          borderRadius: BorderRadius.circular(8),
                        ),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Row(
                              children: [
                                Icon(
                                  Icons.barcode_reader,
                                  color: Colors.blue.shade700,
                                ),
                                const SizedBox(width: 8),
                                Text(
                                  'PDF-417 decoded (${_scannedPdf417Content!.length} bytes)',
                                  style: TextStyle(
                                    fontWeight: FontWeight.w600,
                                    color: Colors.blue.shade700,
                                  ),
                                ),
                              ],
                            ),
                            const SizedBox(height: 8),
                            SelectableText(
                              _scannedPdf417Content!,
                              style: const TextStyle(
                                fontFamily: 'monospace',
                                fontSize: 11,
                              ),
                            ),
                          ],
                        ),
                      ),
                    ],
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
