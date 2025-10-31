import SwiftUI

struct CredentialDetailFooter: View {
    let selectedTab: CredentialMode
    let hasShareSupport: Bool
    let onScanClick: () -> Void
    let onShareClick: () -> Void
    let onDetailsClick: () -> Void
    let onActivityLogClick: () -> Void
    let onMoreClick: () -> Void
    let onCloseClick: () -> Void

    var body: some View {
        VStack(spacing: 16) {
            ScanShareButtons(
                selectedTab: selectedTab,
                hasShareSupport: hasShareSupport,
                onScanClick: onScanClick,
                onShareClick: onShareClick
            )

            MiddleMenuSection(
                onDetailsClick: onDetailsClick,
                onActivityLogClick: onActivityLogClick,
                onMoreClick: onMoreClick
            )

            CloseButtonSection(onCloseClick: onCloseClick)
        }
        .background(Color("ColorBase50"))
    }
}

private struct ScanShareButtons: View {
    let selectedTab: CredentialMode
    let hasShareSupport: Bool
    let onScanClick: () -> Void
    let onShareClick: () -> Void

    var body: some View {
        HStack(spacing: 4) {
            ModeButton(
                isSelected: selectedTab == CredentialMode.SCAN,
                isEnabled: true,
                icon: "QRCodeReader",
                text: "Scan",
                onClick: onScanClick
            )

            ModeButton(
                isSelected: selectedTab == CredentialMode.SHARE,
                isEnabled: hasShareSupport,
                icon: "QRCode",
                text: "Share",
                onClick: onShareClick
            )
            
        }
        .frame(maxWidth: .infinity)
        .padding(4)
        .background(Color("ColorBase1"))
        .overlay(
            RoundedRectangle(cornerRadius: 24)
                .stroke(Color("ColorStone200"), lineWidth: 1)
        )
        .cornerRadius(24)
        .shadow(color: Color.black.opacity(0.25), radius: 1, x: 0, y: 0)
    }
}

private struct ModeButton: View {
    let isSelected: Bool
    let isEnabled: Bool
    let icon: String
    let text: String
    let onClick: () -> Void

    var textColor: Color {
        if !isEnabled {
            return Color("ColorStone600").opacity(0.4)
        } else if isSelected {
            return Color.black
        } else {
            return Color("ColorStone600")
        }
    }

    var body: some View {
        Button(action: onClick) {
            HStack(spacing: 8) {
                Image(icon)
                    .resizable()
                    .aspectRatio(contentMode: .fit)
                    .frame(width: 20, height: 20)
                    .foregroundColor(textColor)
                Text(text)
                    .font(.customFont(font: .inter, style: .medium, size: .h4))
                    .foregroundColor(textColor)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 10)
            .padding(.horizontal, 2)
            .background(
                isSelected && isEnabled
                    ? AnyView(RoundedRectangle(cornerRadius: 22)
                        .fill(Color("ColorBlue200"))
                        .overlay(
                            RoundedRectangle(cornerRadius: 22)
                                .stroke(Color("ColorBlue300"), lineWidth: 1)
                        ))
                    : AnyView(RoundedRectangle(cornerRadius: 22).fill(Color.clear))
            )
        }
        .disabled(!isEnabled)
        .transaction { transaction in
            transaction.animation = nil
        }
    }
}

private struct MiddleMenuSection: View {
    let onDetailsClick: () -> Void
    let onActivityLogClick: () -> Void
    let onMoreClick: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            MenuRow(
                icon: "Info",
                text: "Details",
                onClick: onDetailsClick
            )

            Divider()
                .background(Color("ColorStone100"))

            MenuRow(
                icon: "List",
                text: "Activity Log",
                onClick: onActivityLogClick
            )

            Divider()
                .background(Color("ColorStone100"))

            MenuRow(
                icon: "ThreeDotsHorizontal",
                text: "More",
                onClick: onMoreClick
            )
        }
        .padding(.vertical, 6)
        .background(Color("ColorBase1"))
        .overlay(
            RoundedRectangle(cornerRadius: 12)
                .stroke(Color("ColorStone200"), lineWidth: 1)
        )
        .cornerRadius(12)
        .shadow(color: Color.black.opacity(0.25), radius: 1, x: 0, y: 0)
    }
}

private struct MenuRow: View {
    let icon: String
    let text: String
    let onClick: () -> Void

    var body: some View {
        Button(action: onClick) {
            HStack(spacing: 12) {
                Image(icon)
                    .renderingMode(.template)
                    .resizable()
                    .aspectRatio(contentMode: .fit)
                    .frame(width: 22, height: 22)
                    .foregroundColor(Color("ColorStone950"))

                Text(text)
                    .font(.customFont(font: .inter, style: .medium, size: .h4))
                    .foregroundColor(Color("ColorStone950"))

                Spacer()

                Image("Chevron")
                    .renderingMode(.template)
                    .resizable()
                    .aspectRatio(contentMode: .fit)
                    .frame(width: 14, height: 14)
                    .foregroundColor(Color("ColorStone400"))
                    .rotationEffect(.degrees(-90))
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 10)
        }
        .frame(height: 48)
    }
}

private struct CloseButtonSection: View {
    let onCloseClick: () -> Void

    var body: some View {
        Button(action: onCloseClick) {
            HStack(spacing: 8) {
                Image("Invalid")
                    .renderingMode(.template)
                    .resizable()
                    .aspectRatio(contentMode: .fit)
                    .frame(width: 22, height: 22)
                    .foregroundColor(Color.black)
                Text("Close")
                    .font(.customFont(font: .inter, style: .medium, size: .h4))
                    .foregroundColor(Color("ColorStone950"))
            }
            .padding(.vertical, 8)
            .padding(.horizontal, 16)
        }
        .background(
            RoundedRectangle(cornerRadius: 6)
                .fill(Color.clear)
        )
        .padding(.bottom, 30)
    }
}
