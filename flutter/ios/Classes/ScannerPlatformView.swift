import Flutter
import SpruceIDMobileSdk
import SwiftUI
import UIKit

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
    private var hostingController: UIHostingController<AnyView>?
    private let channel: FlutterMethodChannel
    private let scannerType: String
    private let title: String
    private let subtitle: String

    init(
        frame: CGRect,
        viewId: Int64,
        messenger: FlutterBinaryMessenger,
        args: [String: Any]?
    ) {
        self.channel = FlutterMethodChannel(
            name: "com.spruceid.sprucekit_mobile/scanner_\(viewId)",
            binaryMessenger: messenger
        )
        self.scannerType = args?["type"] as? String ?? "qrCode"
        self.title = args?["title"] as? String ?? "Scan QR Code"
        self.subtitle = args?["subtitle"] as? String ?? "Please align within the guides"

        super.init()

        createHostingController()
    }

    func view() -> UIView {
        return hostingController?.view ?? UIView()
    }

    private func createHostingController() {
        let onRead: (String) -> Void = { [weak self] content in
            self?.channel.invokeMethod("onRead", arguments: content)
        }

        let onCancel: () -> Void = { [weak self] in
            self?.channel.invokeMethod("onCancel", arguments: nil)
        }

        let scannerView: AnyView

        switch scannerType {
        case "qrCode":
            scannerView = AnyView(
                QRCodeScanner(
                    title: title,
                    subtitle: subtitle,
                    onRead: onRead,
                    onCancel: onCancel
                )
            )
        case "pdf417":
            scannerView = AnyView(
                PDF417Scanner(
                    title: title,
                    subtitle: subtitle,
                    onRead: onRead,
                    onCancel: onCancel
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
                    onCancel: onCancel
                )
            )
        }

        hostingController = UIHostingController(rootView: scannerView)
        hostingController?.view.backgroundColor = .clear
    }
}
