import SwiftUI

/// Shared chrome for bottom sheets in the wallet: a centered title/subtitle
/// header — title bolder and slightly bigger than the subtitle, matching
/// Kotlin's `AppBottomSheet` — plus the padding, drag indicator, and
/// presentation detents every sheet should share, and a Cancel button
/// that's always pinned to the bottom of the sheet. `content` is given all
/// the leftover vertical space (`maxHeight: .infinity`, bottom-aligned) so
/// both Cancel and whatever `content` puts directly above it land at the
/// bottom of the sheet, whether `content` fills that space itself (e.g. a
/// long scrolling list) or not (e.g. a short warning with a single
/// button).
struct AppBottomSheet<Content: View>: View {
    let title: String
    var subtitle: String? = nil
    let onCancel: () -> Void
    @ViewBuilder var content: () -> Content

    var body: some View {
        VStack {
            Text(title)
                .font(.customFont(font: .inter, style: .semiBold, size: .h2))
                .foregroundStyle(Color("ColorStone950"))
                .multilineTextAlignment(.center)
                .frame(maxWidth: .infinity)

            if let subtitle {
                Text(subtitle)
                    .font(.customFont(font: .inter, style: .regular, size: .h4))
                    .foregroundStyle(Color("ColorStone700"))
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: .infinity)
                    .padding(.top, 4)
                    .padding(.bottom, 8)
            }

            content()
                .frame(
                    maxWidth: .infinity,
                    maxHeight: .infinity,
                    alignment: .bottom
                )

            Button {
                onCancel()
            } label: {
                Text("Cancel")
                    .frame(maxWidth: .infinity)
                    .font(.customFont(font: .inter, style: .medium, size: .h4))
            }
            .foregroundColor(Color("ColorStone950"))
            .padding(.vertical, 13)
            .overlay(
                RoundedRectangle(cornerRadius: 8)
                    .stroke(Color("ColorStone300"), lineWidth: 1)
            )
            .padding(.top, 12)
        }
        .padding(.horizontal, 20)
        .padding(.top, 36)
        .presentationDetents([.fraction(0.85)])
        .presentationDragIndicator(.visible)
    }
}
