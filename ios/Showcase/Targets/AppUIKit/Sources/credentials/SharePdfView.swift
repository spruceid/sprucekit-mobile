import SpruceIDMobileSdk
import SpruceIDMobileSdkRs
import SwiftUI
import UIKit

struct SharePdfView: View {
    let credentialPack: CredentialPack?

    @State private var isGenerating = false
    @State private var errorMessage: String?

    var body: some View {
        VStack(spacing: 16) {
            RoundedRectangle(cornerRadius: 12)
                .strokeBorder(Color("ColorStone300"), lineWidth: 1)
                .background(
                    RoundedRectangle(cornerRadius: 12)
                        .fill(Color("ColorBase1"))
                )
                .overlay(
                    VStack(spacing: 16) {
                        Image("Export")
                            .resizable()
                            .frame(width: 48, height: 48)
                            .foregroundColor(Color("ColorBlue600"))
                        Button(action: generateAndShare) {
                            Text(isGenerating ? "Generating..." : "Generate & Share PDF")
                                .font(.customFont(font: .inter, style: .semiBold, size: .p))
                                .foregroundColor(.white)
                                .padding(.horizontal, 24)
                                .padding(.vertical, 10)
                                .background(
                                    RoundedRectangle(cornerRadius: 8)
                                        .fill(Color("ColorBlue600"))
                                )
                        }
                        .disabled(credentialPack == nil || isGenerating)
                        if let msg = errorMessage {
                            Text(msg)
                                .font(.customFont(font: .inter, style: .regular, size: .small))
                                .foregroundColor(.red)
                                .multilineTextAlignment(.center)
                        }
                    }
                    .padding(24)
                )
                .frame(maxWidth: .infinity)
                .padding(.horizontal, 20)

            Text("Generate a PDF representation of this credential and share it.")
                .font(.customFont(font: .inter, style: .regular, size: .p))
                .foregroundColor(Color("ColorStone500"))
                .multilineTextAlignment(.center)
                .padding(.horizontal, 24)
        }
    }

    private func generateAndShare() {
        guard let pack = credentialPack,
              let credential = pack.list().first
        else { return }

        isGenerating = true
        errorMessage = nil

        Task {
            do {
                // Demo: include QR Code and PDF-417 barcodes with mock data.
                // In production, QR would be a VP Token and PDF-417 would be AAMVA data.
                let qrPayload = #"{"type":"mDL","source":"SpruceKit Showcase"}"#
                let pdf417Payload = "DAQ DL-123456789\nDCS Doe\nDCT John\nDBB 01151990\nDBA 01152029"
                let demoSupplements: [PdfSupplement] = [
                    .barcode(
                        data: Data(qrPayload.utf8),
                        barcodeType: .qrCode
                    ),
                    .barcode(
                        data: Data(pdf417Payload.utf8),
                        barcodeType: .pdf417
                    )
                ]
                let pdfBytes = try generateCredentialPdf(credential: credential, supplements: demoSupplements)
                await sharePdf(Data(pdfBytes))
            } catch {
                await MainActor.run {
                    errorMessage = error.localizedDescription
                }
            }
            await MainActor.run { isGenerating = false }
        }
    }

    @MainActor
    private func sharePdf(_ data: Data) {
        let tmpUrl = FileManager.default.temporaryDirectory
            .appendingPathComponent("credential.pdf")
        try? data.write(to: tmpUrl)

        let activityVC = UIActivityViewController(
            activityItems: [tmpUrl],
            applicationActivities: nil
        )
        UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .first?.windows.first?.rootViewController?
            .present(activityVC, animated: true)
    }
}
