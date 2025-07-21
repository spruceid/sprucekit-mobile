import SwiftUI
import AVKit

public struct PDF417Scanner: View {

    var metadataObjectTypes: [AVMetadataObject.ObjectType] = [.pdf417]
    var title: String
    var subtitle: String
    var cancelButtonLabel: String
    var onCancel: () -> Void
    var hideCancelButton: Bool
    var onRead: (String) -> Void
    var titleFont: Font?
    var subtitleFont: Font?
    var cancelButtonFont: Font?
    var readerColor: Color
    var titleColor: Color
    var subtitleColor: Color
    var buttonColor: Color
    var backgroundOpacity: Double

    public init(
        title: String = "Scan QR Code",
        subtitle: String = "Please align within the guides",
        cancelButtonLabel: String = "Cancel",
        onRead: @escaping (String) -> Void,
        onCancel: @escaping () -> Void,
        hideCancelButton: Bool = false,
        titleFont: Font? = nil,
        subtitleFont: Font? = nil,
        cancelButtonFont: Font? = nil,
        readerColor: Color = .white,
        titleColor: Color = .white,
        subtitleColor: Color = .white,
        buttonColor: Color = .white,
        backgroundOpacity: Double = 0.75
    ) {
        self.title = title
        self.subtitle = subtitle
        self.cancelButtonLabel = cancelButtonLabel
        self.onCancel = onCancel
        self.hideCancelButton = hideCancelButton
        self.onRead = onRead
        self.titleFont = titleFont
        self.subtitleFont = subtitleFont
        self.cancelButtonFont = cancelButtonFont
        self.readerColor = readerColor
        self.titleColor = titleColor
        self.subtitleColor = subtitleColor
        self.buttonColor = buttonColor
        self.backgroundOpacity = backgroundOpacity
    }

    func calculateRegionOfInterest() -> CGSize {
        let size = UIScreen.screenSize

        return CGSize(width: size.width * 0.8, height: size.width * 0.4)
    }

    public var body: some View {
        AVMetadataObjectScanner(
            metadataObjectTypes: metadataObjectTypes,
            title: title,
            subtitle: subtitle,
            cancelButtonLabel: cancelButtonLabel,
            onRead: onRead,
            onCancel: onCancel,
            hideCancelButton: hideCancelButton,
            titleFont: titleFont,
            subtitleFont: subtitleFont,
            cancelButtonFont: cancelButtonFont,
            readerColor: readerColor,
            titleColor: titleColor,
            subtitleColor: subtitleColor,
            buttonColor: buttonColor,
            backgroundOpacity: backgroundOpacity,
            regionOfInterest: calculateRegionOfInterest()
        )
    }
}
