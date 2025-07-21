import AVKit
import SwiftUI

struct HStackHeightKey: PreferenceKey {
    static var defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) {
        value = nextValue()
    }
}

struct VStackHeightKey: PreferenceKey {
    static var defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) {
        value = nextValue()
    }
}

public struct QRCodeScanner: View {

    @State private var hstackHeight: CGFloat = 0
    @State private var vstackHeight: CGFloat = 0
    var metadataObjectTypes: [AVMetadataObject.ObjectType] = [.qr]
    var title: String
    var subtitle: String
    var cancelButtonLabel: String
    var onCancel: () -> Void
    var onRead: (String) -> Void
    var titleFont: Font?
    var subtitleFont: Font?
    var cancelButtonFont: Font?
    var guidesColor: Color
    var guidesText: String
    var readerColor: Color
    var titleColor: Color
    var subtitleColor: Color
    var buttonColor: Color
    var buttonBorderColor: Color
    var backgroundColor: Color
    var backgroundOpacity: Double
    var instructions: String
    var instructionsFont: Font?
    var instructionsDefaultColor: Color

    public init(
        title: String = "Scan QR Code",
        subtitle: String = "Please align within the guides",
        cancelButtonLabel: String = "Cancel",
        onRead: @escaping (String) -> Void,
        onCancel: @escaping () -> Void,
        titleFont: Font? = nil,
        subtitleFont: Font? = nil,
        cancelButtonFont: Font? = nil,
        guidesColor: Color = .blue,
        guidesText: String = "Detecting...",
        readerColor: Color = .white,
        titleColor: Color = .black,
        subtitleColor: Color = .black,
        buttonColor: Color = .black,
        buttonBorderColor: Color = .gray,
        backgroundColor: Color = .white,
        backgroundOpacity: Double = 1,
        instructions: String = "",
        instructionsFont: Font? = nil,
        instructionsDefaultColor: Color = .gray,
    ) {
        self.title = title
        self.subtitle = subtitle
        self.cancelButtonLabel = cancelButtonLabel
        self.onCancel = onCancel
        self.onRead = onRead
        self.titleFont = titleFont
        self.subtitleFont = subtitleFont
        self.cancelButtonFont = cancelButtonFont
        self.guidesColor = guidesColor
        self.guidesText = guidesText
        self.readerColor = readerColor
        self.titleColor = titleColor
        self.subtitleColor = subtitleColor
        self.buttonColor = buttonColor
        self.buttonBorderColor = buttonBorderColor
        self.backgroundColor = backgroundColor
        self.backgroundOpacity = backgroundOpacity
        self.instructions = instructions
        self.instructionsFont = instructionsFont
        self.instructionsDefaultColor = instructionsDefaultColor
    }

    func calculateRegionOfInterest() -> CGSize {
        let size = UIScreen.screenSize

        return CGSize(width: size.width * 0.6, height: size.width * 0.6)
    }

    public var body: some View {
        AVMetadataObjectScanner(
            metadataObjectTypes: metadataObjectTypes,
            title: title,
            subtitle: subtitle,
            cancelButtonLabel: cancelButtonLabel,
            onRead: onRead,
            onCancel: onCancel,
            titleFont: titleFont,
            subtitleFont: subtitleFont,
            cancelButtonFont: cancelButtonFont,
            readerColor: readerColor,
            titleColor: titleColor,
            subtitleColor: subtitleColor,
            buttonColor: buttonColor,
            buttonBorderColor: buttonBorderColor,
            backgroundColor: backgroundColor,
            backgroundOpacity: backgroundOpacity,
            regionOfInterest: calculateRegionOfInterest(),
            scannerGuides: ZStack(alignment: .bottom) {
                RoundedRectangle(cornerRadius: 6, style: .circular)
                    .stroke(
                        guidesColor,
                        style: StrokeStyle(
                            lineWidth: 4,
                            lineCap: .round,
                            lineJoin: .round
                        )
                    )
                VStack {
                    HStack {
                        ProgressRing()
                        Text(guidesText)
                            .foregroundColor(.white)
                    }
                    .padding(.horizontal, 20)
                    .padding(.vertical, 10)
                    .background(
                        guidesColor.clipShape(
                            RoundedRectangle(cornerRadius: 100)
                        )
                    )
                    .background(
                        GeometryReader { geometry in
                            Color.clear
                                .preference(
                                    key: HStackHeightKey.self,
                                    value: geometry.size.height
                                )
                        }
                    )
                    .onPreferenceChange(HStackHeightKey.self) { height in
                        hstackHeight = height
                    }

                    VStack(spacing: 4) {
                        Text(instructions)
                    }
                    .font(instructionsFont)
                    .foregroundColor(instructionsDefaultColor)
                    .multilineTextAlignment(.center)
                    .padding(.top, 8)
                }
                .background(
                    GeometryReader { geometry in
                        Color.clear
                            .preference(
                                key: VStackHeightKey.self,
                                value: geometry.size.height
                            )
                    }
                )
                .onPreferenceChange(VStackHeightKey.self) { height in
                    vstackHeight = height
                }
                .offset(y: vstackHeight - (hstackHeight / 2))
            }
        )
    }
}
