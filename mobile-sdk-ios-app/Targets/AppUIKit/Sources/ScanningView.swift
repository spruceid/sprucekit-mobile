import AVKit
import SpruceIDMobileSdk
import SwiftUI

enum ScanningType {
    case qrcode, pdf417, mrz
}

struct Scanning: Hashable, Equatable {
    var id: UUID
    var scanningType: ScanningType
    var title: String
    var subtitle: String
    var onCancel: (() -> Void)?
    var onRead: (String) -> Void

    init(
        title: String = "Scan QR Code",
        subtitle: String = "Please align within the guides",
        scanningType: ScanningType,
        onCancel: @escaping () -> Void,
        onRead: @escaping (String) -> Void
    ) {
        self.id = UUID()
        self.scanningType = scanningType
        self.title = title
        self.subtitle = subtitle
        self.onCancel = onCancel
        self.onRead = onRead
    }

    func hash(into hasher: inout Hasher) {
        hasher.combine(id)
        hasher.combine(scanningType)
    }

    static func == (s1: Scanning, s2: Scanning) -> Bool {
        return s1.id == s2.id
    }

}

///  Camera permission enum
enum Permission: String {
    case idle = "Not Determined"
    case approved = "Access Granted"
    case denied = "Access Denied"
}

struct ScanningView: View {

    @Binding var path: NavigationPath

    var scanningParams: Scanning

    var body: some View {
        ScanningComponent(path: $path, scanningParams: scanningParams)
    }

}

struct ScanningComponent: View {
    @Binding var path: NavigationPath

    var scanningParams: Scanning

    /// QR Code Scanner properties
    @State private var cameraPermission: Permission = .idle

    /// Error properties
    @State private var errorMessage: String = ""
    @State private var showError: Bool = false
    @Environment(\.openURL) private var openURL

    func onCancel() {
        if scanningParams.onCancel != nil {
            scanningParams.onCancel!()
        } else {
            while !path.isEmpty {
                path.removeLast()
            }
        }
    }

    /// Checking camera permission
    func checkCameraPermisssion() {
        Task {
            switch AVCaptureDevice.authorizationStatus(for: .video) {
            case .authorized:
                cameraPermission = .approved
            case .notDetermined:
                /// Requesting camera access
                if await AVCaptureDevice.requestAccess(for: .video) {
                    /// Permission Granted
                    cameraPermission = .approved
                } else {
                    /// Permission Denied
                    cameraPermission = .denied
                    /// Presenting Error message
                    presentError("Please provide access to your camera")

                }
            case .denied, .restricted:
                cameraPermission = .denied
                /// Presenting Error message
                presentError("Please provide access to your camera")
            default: break
            }
        }
    }

    /// Presenting Error
    func presentError(_ message: String) {
        errorMessage = message
        showError.toggle()
    }

    var body: some View {
        ZStack {
            if cameraPermission == Permission.approved {
                switch scanningParams.scanningType {
                case .qrcode:
                    QRCodeScanner(
                        title: scanningParams.title,
                        subtitle: scanningParams.subtitle,
                        onRead: scanningParams.onRead,
                        onCancel: onCancel,
                        titleFont: .customFont(
                            font: .inter, style: .bold, size: .h0),
                        subtitleFont: .customFont(
                            font: .inter, style: .bold, size: .h4),
                        cancelButtonFont: .customFont(
                            font: .inter, style: .medium, size: .h3),
                        readerColor: .white
                    )
                case .pdf417:
                    PDF417Scanner(
                        title: scanningParams.title,
                        subtitle: scanningParams.subtitle,
                        onRead: scanningParams.onRead,
                        onCancel: onCancel,
                        titleFont: .customFont(
                            font: .inter, style: .bold, size: .h0),
                        subtitleFont: .customFont(
                            font: .inter, style: .bold, size: .h4),
                        cancelButtonFont: .customFont(
                            font: .inter, style: .medium, size: .h3),
                        readerColor: .white
                    )
                case .mrz:
                    MRZScanner(
                        title: scanningParams.title,
                        subtitle: scanningParams.subtitle,
                        onRead: scanningParams.onRead,
                        onCancel: onCancel,
                        titleFont: .customFont(
                            font: .inter, style: .bold, size: .h0),
                        subtitleFont: .customFont(
                            font: .inter, style: .bold, size: .h4),
                        cancelButtonFont: .customFont(
                            font: .inter, style: .medium, size: .h3),
                        readerColor: .white
                    )
                }

            }
        }
        .onAppear(perform: checkCameraPermisssion)
        .alert(isPresented: $showError) {
            Alert(
                title: Text(errorMessage),
                message: nil,
                primaryButton: .default(
                    Text("Settings"),
                    action: {
                        let settingString = UIApplication.openSettingsURLString
                        if let settingURL = URL(string: settingString) {
                            /// Opening Apps setting, using openURL SwiftUI API
                            openURL(settingURL)
                        }
                    }
                ),
                secondaryButton: .destructive(
                    Text("Cancel"),
                    action: onCancel
                )
            )
        }
        .navigationBarBackButtonHidden(true)
    }
}
