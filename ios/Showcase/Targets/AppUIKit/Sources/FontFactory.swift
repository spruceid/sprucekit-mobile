import Foundation
import SwiftUI
import UIKit

enum CustomFonts: String {
    case inter = "Inter"
}

enum CustomFontStyle: String {
    case black = "-Black"
    case blackItalic = "-BlackItalic"
    case bold = "-Bold"
    case boldItalic = "-BoldItalic"
    case semiBold = "-SemiBold"
    case semiBoldItalic = "-SemiBoldItalic"
    case italic = "-Italic"
    case light = "-Light"
    case lightItalic = "-LightItalic"
    case medium = "-Medium"
    case mediumItalic = "-MediumItalic"
    case regular = "-Regular"
    case thin = "-Thin"
    case thinItalic = "-ThinItalic"
}

enum CustomFontSize: CGFloat {
    case h0 = 24.0
    case h1 = 22.0
    case h2 = 20.0
    case h3 = 18.0
    case h4 = 16.0
    case p = 14.0
    case small = 12.0
    case xsmall = 10.0

}

extension UIFont {

    /// Choose your font to set up
    /// - Parameters:
    ///   - font: Choose one of your font
    ///   - style: Make sure the style is available
    ///   - size: Use prepared sizes for your app
    ///   - isScaled: Check if your app accessibility prepared
    /// - Returns: UIFont ready to show
    static func customFont(
        font: CustomFonts,
        style: CustomFontStyle,
        size: CustomFontSize,
        isScaled: Bool = true
    ) -> UIFont {

        let fontName: String = font.rawValue + style.rawValue

        guard let font = UIFont(name: fontName, size: size.rawValue) else {
            debugPrint("Font can't be loaded")
            return UIFont.systemFont(ofSize: size.rawValue)
        }

        return isScaled ? UIFontMetrics.default.scaledFont(for: font) : font
    }
}

extension Font {

    /// Choose your font to set up
    /// - Parameters:
    ///   - font: Choose one of your font
    ///   - style: Make sure the style is available
    ///   - size: Use prepared sizes for your app
    ///   - isScaled: Check if your app accessibility prepared
    /// - Returns: Font ready to show
    static func customFont(
        font: CustomFonts,
        style: CustomFontStyle,
        size: CustomFontSize,
        isScaled: Bool = true
    ) -> Font {

        let fontName: String = font.rawValue + style.rawValue

        return Font.custom(fontName, size: size.rawValue)
    }
}

let monospacedFont =
    Font
    .system(size: 16)
    .monospaced()
