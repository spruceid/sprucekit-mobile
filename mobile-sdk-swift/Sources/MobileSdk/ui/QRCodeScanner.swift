import SwiftUI
import AVKit

public struct QRCodeScanner: View {

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
    var readerColor: Color
    var textColor: Color
    var backgroundOpacity: Double

    public init(
        title: String = "Scan QR Code",
        subtitle: String = "Please align within the guides",
        cancelButtonLabel: String = "Cancel",
        onRead: @escaping (String) -> Void,
        onCancel: @escaping () -> Void,
        titleFont: Font? = nil,
        subtitleFont: Font? = nil,
        cancelButtonFont: Font? = nil,
        guidesColor: Color = .white,
        readerColor: Color = .white,
        textColor: Color = .white,
        backgroundOpacity: Double = 0.75
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
        self.readerColor = readerColor
        self.textColor = textColor
        self.backgroundOpacity = backgroundOpacity

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
            textColor: textColor,
            backgroundOpacity: backgroundOpacity,
            regionOfInterest: calculateRegionOfInterest(),
            scannerGuides: ForEach(0...4, id: \.self) { index in
                                let rotation = Double(index) * 90
                                RoundedRectangle(cornerRadius: 2, style: .circular)
                                    .trim(from: 0.61, to: 0.64)
                                    .stroke(
                                        guidesColor,
                                        style: StrokeStyle(
                                            lineWidth: 5,
                                            lineCap: .round,
                                            lineJoin: .round
                                            )
                                        )
                                    .rotationEffect(.init(degrees: rotation))
                            }
        )
    }
}
