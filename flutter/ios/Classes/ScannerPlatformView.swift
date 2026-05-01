import Flutter
import SpruceIDMobileSdk
import SwiftUI
import UIKit

private func colorFromArgb(_ argb: Int) -> Color {
    let alpha = Double((argb >> 24) & 0xFF) / 255.0
    let red = Double((argb >> 16) & 0xFF) / 255.0
    let green = Double((argb >> 8) & 0xFF) / 255.0
    let blue = Double(argb & 0xFF) / 255.0
    return Color(.sRGB, red: red, green: green, blue: blue, opacity: alpha)
}

/// Factory for creating scanner platform views
class ScannerPlatformViewFactory: NSObject, FlutterPlatformViewFactory {
    private var messenger: FlutterBinaryMessenger

    init(messenger: FlutterBinaryMessenger) {
        self.messenger = messenger
        super.init()
    }

    func create(
        withFrame frame: CGRect,
        viewIdentifier viewId: Int64,
        arguments args: Any?
    ) -> FlutterPlatformView {
        return ScannerPlatformView(
            frame: frame,
            viewId: viewId,
            messenger: messenger,
            args: args as? [String: Any]
        )
    }

    func createArgsCodec() -> FlutterMessageCodec & NSObjectProtocol {
        return FlutterStandardMessageCodec.sharedInstance()
    }
}

/// Platform view wrapper for the scanner
class ScannerPlatformView: NSObject, FlutterPlatformView {
    private var containerView: UIView
    private var hostingController: UIHostingController<AnyView>?
    private let channel: FlutterMethodChannel
    private let scannerType: String
    private let title: String
    private let subtitle: String
    private let showCancelButton: Bool
    private let backgroundOpacity: Double
    private let guidesColorArgb: Int
    private let readerColorArgb: Int
    private let guidesText: String
    private let instructions: String
    private let scanCooldownMs: Int

    init(
        frame: CGRect,
        viewId: Int64,
        messenger: FlutterBinaryMessenger,
        args: [String: Any]?
    ) {
        self.containerView = UIView(frame: frame)
        self.channel = FlutterMethodChannel(
            name: "com.spruceid.sprucekit_mobile/scanner_\(viewId)",
            binaryMessenger: messenger
        )
        self.scannerType = args?["type"] as? String ?? "qrCode"
        self.title = args?["title"] as? String ?? "Scan QR Code"
        self.subtitle = args?["subtitle"] as? String ?? "Please align within the guides"
        self.showCancelButton = args?["showCancelButton"] as? Bool ?? true
        self.backgroundOpacity = (args?["backgroundOpacity"] as? Double) ?? 1.0
        self.guidesColorArgb = (args?["guidesColor"] as? Int) ?? 0xFF2563EB
        self.readerColorArgb = (args?["readerColor"] as? Int) ?? 0xFFFFFFFF
        self.guidesText = (args?["guidesText"] as? String) ?? "Detecting..."
        self.instructions = (args?["instructions"] as? String) ?? ""
        self.scanCooldownMs = (args?["scanCooldownMs"] as? Int) ?? 0

        super.init()

        createHostingController()

        // iOS uses UIKitView composition where Flutter widgets composite correctly
        // above the platform view, so a real first-frame signal is unnecessary.
        // Fire on next runloop turn so listeners attached after construction still receive it.
        DispatchQueue.main.async { [weak self] in
            self?.channel.invokeMethod("onCameraReady", arguments: nil)
        }
    }

    func view() -> UIView {
        return containerView
    }

    private func createHostingController() {
        let onRead: (String) -> Void = { [weak self] content in
            self?.channel.invokeMethod("onRead", arguments: content)
        }

        let onCancel: () -> Void = { [weak self] in
            self?.channel.invokeMethod("onCancel", arguments: nil)
        }

        let scannerView: AnyView

        let hideCancelButton = !showCancelButton

        switch scannerType {
        case "qrCode":
            scannerView = AnyView(
                QRCodeScanner(
                    title: title,
                    subtitle: subtitle,
                    onRead: onRead,
                    onCancel: onCancel,
                    hideCancelButton: hideCancelButton,
                    guidesColor: colorFromArgb(guidesColorArgb),
                    guidesText: guidesText,
                    readerColor: colorFromArgb(readerColorArgb),
                    backgroundOpacity: backgroundOpacity,
                    instructions: instructions,
                    scanCooldownMs: scanCooldownMs
                )
            )
        case "pdf417":
            scannerView = AnyView(
                PDF417Scanner(
                    title: title,
                    subtitle: subtitle,
                    onRead: onRead,
                    onCancel: onCancel,
                    hideCancelButton: hideCancelButton
                )
            )
        case "mrz":
            scannerView = AnyView(
                MRZScanner(
                    title: title,
                    subtitle: subtitle,
                    onRead: onRead,
                    onCancel: onCancel
                )
            )
        default:
            scannerView = AnyView(
                QRCodeScanner(
                    title: title,
                    subtitle: subtitle,
                    onRead: onRead,
                    onCancel: onCancel,
                    hideCancelButton: hideCancelButton,
                    guidesColor: colorFromArgb(guidesColorArgb),
                    guidesText: guidesText,
                    readerColor: colorFromArgb(readerColorArgb),
                    backgroundOpacity: backgroundOpacity,
                    instructions: instructions,
                    scanCooldownMs: scanCooldownMs
                )
            )
        }

        hostingController = UIHostingController(rootView: scannerView)
        hostingController?.view.backgroundColor = .clear

        if let hostingView = hostingController?.view {
            hostingView.translatesAutoresizingMaskIntoConstraints = false
            containerView.addSubview(hostingView)
            containerView.clipsToBounds = true

            NSLayoutConstraint.activate([
                hostingView.topAnchor.constraint(equalTo: containerView.topAnchor),
                hostingView.bottomAnchor.constraint(equalTo: containerView.bottomAnchor),
                hostingView.leadingAnchor.constraint(equalTo: containerView.leadingAnchor),
                hostingView.trailingAnchor.constraint(equalTo: containerView.trailingAnchor)
            ])
        }
    }
}
