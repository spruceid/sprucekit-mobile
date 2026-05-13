import SpruceIDMobileSdk
import SwiftUI

/// Pure UI for the NFC verifier tab. Engagement lifecycle (reader sessions,
/// APDU exchange) is owned by the parent `VerifyMDocView` via
/// `NfcReaderObservable`.
struct VerifyMDocNfcTab: View {
    let phase: NfcReaderPhase
    var onCancel: () -> Void
    var onRetry: () -> Void

    var body: some View {
        VStack {
            Spacer()
            content
                .multilineTextAlignment(.center)
                .padding(.horizontal, 24)
            Spacer()
            Button(action: onCancel) {
                Text("Cancel")
                    .font(.customFont(font: .inter, style: .semiBold, size: .h4))
                    .foregroundColor(Color("ColorStone950"))
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 14)
                    .overlay(
                        RoundedRectangle(cornerRadius: 5)
                            .stroke(Color("ColorStone300"), lineWidth: 1)
                    )
            }
            .padding(.horizontal, 24)
            .padding(.bottom, 24)
        }
    }

    @ViewBuilder
    private var content: some View {
        switch phase {
        case .unsupported:
            Text("This device does not support NFC.")
                .font(.customFont(font: .inter, style: .regular, size: .p))
                .foregroundColor(Color("ColorStone950"))

        case .idle:
            VStack(spacing: 12) {
                Text("Tap the holder's phone to share their credential.")
                    .font(.customFont(font: .inter, style: .medium, size: .h4))
                    .foregroundColor(Color("ColorStone950"))
                Text("The system NFC sheet will appear once you start scanning.")
                    .font(.customFont(font: .inter, style: .regular, size: .small))
                    .foregroundColor(Color("ColorStone500"))
                Button(action: onRetry) {
                    Text("Start scanning")
                        .font(.customFont(font: .inter, style: .semiBold, size: .h4))
                        .foregroundColor(.white)
                        .padding(.horizontal, 24)
                        .padding(.vertical, 12)
                        .background(Color("ColorBlue600"))
                        .cornerRadius(5)
                }
                .padding(.top, 8)
            }

        case .waitingForTag:
            VStack(spacing: 12) {
                Text("Tap the holder's phone to share their credential.")
                    .font(.customFont(font: .inter, style: .medium, size: .h4))
                    .foregroundColor(Color("ColorStone950"))
                Text("Hold the phones back-to-back until the share completes.")
                    .font(.customFont(font: .inter, style: .regular, size: .small))
                    .foregroundColor(Color("ColorStone500"))
            }

        case .exchanging:
            VStack(spacing: 16) {
                ProgressView()
                    .tint(Color("ColorBlue600"))
                Text("Negotiating handover…")
                    .font(.customFont(font: .inter, style: .regular, size: .p))
                    .foregroundColor(Color("ColorStone500"))
            }

        case .protocolError(let error):
            VStack(spacing: 16) {
                Text(error.localizedDescription)
                    .font(.customFont(font: .inter, style: .regular, size: .p))
                    .foregroundColor(.red)
                Button(action: onRetry) {
                    Text("Tap to retry")
                        .font(.customFont(font: .inter, style: .semiBold, size: .h4))
                        .foregroundColor(.white)
                        .padding(.horizontal, 24)
                        .padding(.vertical, 12)
                        .background(Color("ColorBlue600"))
                        .cornerRadius(5)
                }
            }
        }
    }
}
