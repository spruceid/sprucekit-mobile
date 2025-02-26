import SwiftUI
import AVKit
import os.log

public class AVMetadataObjectScannerDelegate: NSObject, ObservableObject, AVCaptureMetadataOutputObjectsDelegate {

    @Published public var scannedCode: String?
    public func metadataOutput(
        _ output: AVCaptureMetadataOutput,
        didOutput metadataObjects: [AVMetadataObject],
        from connection: AVCaptureConnection
    ) {
        if let metaObject = metadataObjects.first {
            guard let readableObject = metaObject as? AVMetadataMachineReadableCodeObject else {return}
            guard let scannedCode = readableObject.stringValue else {return}
            self.scannedCode = scannedCode
        }
    }
}

public struct AVMetadataObjectScanner: View {
    /// QR Code Scanner properties
    @State private var isScanning: Bool = false
    @State private var session: AVCaptureSession = .init()

    /// QR scanner AV Output
    @State private var qrOutput: AVCaptureMetadataOutput = .init()

    /// Camera QR Output delegate
    @StateObject private var qrDelegate = AVMetadataObjectScannerDelegate()

    /// Scanned code
    @State private var scannedCode: String = ""

    var metadataObjectTypes: [AVMetadataObject.ObjectType]
    var title: String
    var subtitle: String
    var cancelButtonLabel: String
    var onCancel: () -> Void
    var onRead: (String) -> Void
    var titleFont: Font?
    var subtitleFont: Font?
    var cancelButtonFont: Font?
    var readerColor: Color
    var textColor: Color
    var backgroundOpacity: Double
    var regionOfInterest: CGSize
    var scannerGuides: (any View)?

    public init(
        metadataObjectTypes: [AVMetadataObject.ObjectType] = [.qr],
        title: String = "Scan QR Code",
        subtitle: String = "Please align within the guides",
        cancelButtonLabel: String = "Cancel",
        onRead: @escaping (String) -> Void,
        onCancel: @escaping () -> Void,
        titleFont: Font? = nil,
        subtitleFont: Font? = nil,
        cancelButtonFont: Font? = nil,
        readerColor: Color = .white,
        textColor: Color = .white,
        backgroundOpacity: Double = 0.75,
        regionOfInterest: CGSize = CGSize(width: 0, height: 0),
        scannerGuides: (any View)? = nil
    ) {
        self.metadataObjectTypes = metadataObjectTypes
        self.title = title
        self.subtitle = subtitle
        self.cancelButtonLabel = cancelButtonLabel
        self.onCancel = onCancel
        self.onRead = onRead
        self.titleFont = titleFont
        self.subtitleFont = subtitleFont
        self.cancelButtonFont = cancelButtonFont
        self.readerColor = readerColor
        self.textColor = textColor
        self.backgroundOpacity = backgroundOpacity
        self.regionOfInterest = regionOfInterest
        self.scannerGuides = scannerGuides
    }

    public var body: some View {
        ZStack(alignment: .top) {
            GeometryReader {_ in
                let size = UIScreen.screenSize

                ZStack {
                    CameraView(frameSize: CGSize(width: size.width, height: size.height), session: $session)
                    /// Blur layer with clear cut out
                    ZStack {
                        Rectangle()
                            .foregroundColor(Color.black.opacity(backgroundOpacity))
                            .frame(width: size.width, height: size.height)
                        Rectangle()
                            .frame(width: regionOfInterest.width, height: regionOfInterest.height)
                                .blendMode(.destinationOut)
                            }
                            .compositingGroup()

                    /// Scan area edges
                    ZStack {
                        if scannerGuides != nil {
                            AnyView(scannerGuides!)
                        }

                        /// Scanner Animation
                        Rectangle()
                            .fill(readerColor)
                            .frame(height: 2.5)
                            .offset(y: isScanning ? (regionOfInterest.height)/2 : -(regionOfInterest.height)/2)
                    }
                    .frame(width: regionOfInterest.width, height: regionOfInterest.height)

                }
                /// Square Shape
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }

            VStack(alignment: .leading) {
                Text(title)
                    .font(titleFont)
                    .foregroundColor(textColor)

                Text(subtitle)
                    .font(subtitleFont)
                    .foregroundColor(textColor)

                Spacer()

                Button(cancelButtonLabel) {
                    onCancel()
                }
                .font(cancelButtonFont)
                .foregroundColor(textColor)
            }
            .padding(.vertical, 80)
        }
        /// Checking camera permission, when the view is visible
        .onAppear(perform: {
            Task {
                guard await isAuthorized else { return }

                switch AVCaptureDevice.authorizationStatus(for: .video) {
                case .authorized:
                    if session.inputs.isEmpty {
                        /// New setup
                        setupCamera()
                    } else {
                        /// Already existing one
                        reactivateCamera()
                    }
                default: break
                }
            }
        })

        .onDisappear {
            session.stopRunning()
        }

        .onChange(of: qrDelegate.scannedCode) { newValue in
            if let code = newValue {
                scannedCode = code

                /// When the first code scan is available, immediately stop the camera.
                session.stopRunning()

                /// Stopping scanner animation
                deActivateScannerAnimation()
                /// Clearing the data on delegate
                qrDelegate.scannedCode = nil

                onRead(code)
            }

        }

    }

    func reactivateCamera() {
        DispatchQueue.global(qos: .background).async { [session] in // probably not the right way of doing it
            session.startRunning()
        }
    }

    /// Activating Scanner Animation Method
    func activateScannerAnimation() {
        /// Adding Delay for each reversal
        withAnimation(.easeInOut(duration: 0.85).delay(0.1).repeatForever(autoreverses: true)) {
            isScanning = true
        }
    }

    /// DeActivating scanner animation method
    func deActivateScannerAnimation() {
        /// Adding Delay for each reversal
        withAnimation(.easeInOut(duration: 0.85)) {
            isScanning = false
        }
    }

    /// Setting up camera
    func setupCamera() {
        do {
            /// Finding back camera
            guard let device = AVCaptureDevice.DiscoverySession(
                    deviceTypes: [.builtInWideAngleCamera],
                    mediaType: .video, position: .back)
                .devices.first
            else {
                os_log("Error: %@", log: .default, type: .error, String("UNKNOWN DEVICE ERROR"))
                return
            }

            /// Camera input
            let input = try AVCaptureDeviceInput(device: device)
            /// For Extra Safety
            /// Checking whether input & output can be added to the session
            guard session.canAddInput(input), session.canAddOutput(qrOutput) else {
                os_log("Error: %@", log: .default, type: .error, String("UNKNOWN INPUT/OUTPUT ERROR"))
                return
            }

            /// Adding input & output to camera session
            session.beginConfiguration()
            session.addInput(input)
            session.addOutput(qrOutput)
            /// Setting output config to read qr codes
            qrOutput.metadataObjectTypes = [.qr, .pdf417]
            /// Adding delegate to retreive the fetched qr code from camera
            qrOutput.setMetadataObjectsDelegate(qrDelegate, queue: .main)
            session.commitConfiguration()
            /// Note session must be started on background thread

            DispatchQueue.global(qos: .background).async { [session] in // probably not the right way of doing it
                session.startRunning()
            }
            activateScannerAnimation()
        } catch {
            os_log("Error: %@", log: .default, type: .error, error.localizedDescription)
        }
    }
}
