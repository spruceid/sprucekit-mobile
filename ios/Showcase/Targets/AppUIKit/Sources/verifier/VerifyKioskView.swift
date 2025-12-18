import AVFoundation
import SpruceIDMobileSdk
import SwiftUI

struct VerifyKiosk: Hashable {}

let kioskElements = [
    "org.iso.18013.5.1": [
        "family_name": false,
        "given_name": false,
        "birth_date": false,
        "issue_date": false,
        "expiry_date": false,
    ]
]

public struct VerifyKioskView: View {
    @Binding var path: NavigationPath
    @State private var scanned: String?

    var trustedCertificates = TrustedCertificatesDataStore.shared.getAllCertificates()

    public var body: some View {
        if scanned == nil {
            KioskScanView(
                onCancel: onCancel,
                onRead: { code in self.scanned = code }
            )
        } else {
            KioskReaderView(
                uri: scanned!,
                requestedItems: kioskElements,
                trustAnchorRegistry: trustedCertificates.map { $0.content },
                onCancel: onCancel,
                onStartOver: { self.scanned = nil },
                path: $path
            )
        }
    }

    func onCancel() {
        self.scanned = nil
        path.removeLast()
    }
}

// MARK: - Scan View

struct KioskScanView: View {
    var onCancel: () -> Void
    var onRead: (String) -> Void

    @State private var cameraPermission: Permission = .idle

    private var scannerSize: CGFloat {
        UIScreen.main.bounds.width * 0.6
    }

    var body: some View {
        VStack(spacing: 0) {
            KioskHeader()

            Spacer().frame(height: 100)

            // Title
            VStack(spacing: 0) {
                Text("Scan QR Code")
                    .font(.system(size: 28, weight: .bold))
                    .foregroundColor(Color("ColorBlue600"))

                Spacer().frame(height: 8)

                Text("Present your digital ID QR code")
                    .font(.system(size: 16))
                    .foregroundColor(Color("ColorStone600"))

                Spacer().frame(height: 24)

                // Scanner
                ZStack {
                    if cameraPermission == .approved {
                        KioskCameraView(onRead: onRead)
                            .frame(width: scannerSize, height: scannerSize)
                            .clipShape(RoundedRectangle(cornerRadius: 12))
                    } else {
                        RoundedRectangle(cornerRadius: 12)
                            .fill(Color("ColorStone200"))
                            .frame(width: scannerSize, height: scannerSize)
                        ProgressView()
                    }

                    // Border
                    RoundedRectangle(cornerRadius: 12)
                        .stroke(Color("ColorBlue600"), lineWidth: 4)
                        .frame(width: scannerSize, height: scannerSize)

                    // Badge
                    VStack {
                        Spacer()
                        HStack(spacing: 8) {
                            ProgressView()
                                .progressViewStyle(CircularProgressViewStyle(tint: .white))
                                .scaleEffect(0.8)
                            Text("Detecting...")
                                .font(.system(size: 14, weight: .medium))
                                .foregroundColor(.white)
                        }
                        .padding(.horizontal, 16)
                        .padding(.vertical, 8)
                        .background(Color("ColorBlue600"))
                        .cornerRadius(100)
                        .offset(y: 20)
                    }
                    .frame(width: scannerSize, height: scannerSize)
                }
            }

            Spacer()

            // Cancel button
            Button(action: onCancel) {
                Text("Cancel")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundColor(.black)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 12)
                    .overlay(
                        RoundedRectangle(cornerRadius: 8)
                            .stroke(Color("ColorStone300"), lineWidth: 1)
                    )
            }
        }
        .padding(30)
        .background(Color("ColorBase1"))
        .ignoresSafeArea(edges: .bottom)
        .onAppear {
            Task {
                switch AVCaptureDevice.authorizationStatus(for: .video) {
                case .authorized:
                    cameraPermission = .approved
                case .notDetermined:
                    cameraPermission = await AVCaptureDevice.requestAccess(for: .video) ? .approved : .denied
                default:
                    cameraPermission = .denied
                }
            }
        }
        .navigationBarBackButtonHidden(true)
    }
}

// MARK: - Camera View

struct KioskCameraView: View {
    var onRead: (String) -> Void

    @State private var session = AVCaptureSession()
    @State private var qrOutput = AVCaptureMetadataOutput()
    @StateObject private var delegate = AVMetadataObjectScannerDelegate()

    var body: some View {
        GeometryReader { geometry in
            CameraView(
                frameSize: CGSize(width: geometry.size.width, height: geometry.size.height),
                session: $session
            )
        }
        .ignoresSafeArea()
        .onAppear(perform: setupCamera)
        .onDisappear { session.stopRunning() }
        .onChange(of: delegate.scannedCode) { newValue in
            if let code = newValue {
                session.stopRunning()
                delegate.scannedCode = nil
                onRead(code)
            }
        }
    }

    private func setupCamera() {
        do {
            guard let device = AVCaptureDevice.DiscoverySession(
                deviceTypes: [.builtInWideAngleCamera],
                mediaType: .video,
                position: .back
            ).devices.first else { return }

            let input = try AVCaptureDeviceInput(device: device)
            guard session.canAddInput(input), session.canAddOutput(qrOutput) else { return }

            session.beginConfiguration()
            session.addInput(input)
            session.addOutput(qrOutput)
            qrOutput.metadataObjectTypes = [.qr]
            qrOutput.setMetadataObjectsDelegate(delegate, queue: .main)
            session.commitConfiguration()

            DispatchQueue.global(qos: .background).async {
                session.startRunning()
            }
        } catch {
            print("Camera setup error: \(error.localizedDescription)")
        }
    }
}

// MARK: - Reader View

public struct KioskReaderView: View {
    @StateObject var delegate: KioskScanViewDelegate
    @Binding var path: NavigationPath
    var onCancel: () -> Void
    var onStartOver: () -> Void

    init(
        uri: String,
        requestedItems: [String: [String: Bool]],
        trustAnchorRegistry: [String]?,
        onCancel: @escaping () -> Void,
        onStartOver: @escaping () -> Void,
        path: Binding<NavigationPath>
    ) {
        self._delegate = StateObject(
            wrappedValue: KioskScanViewDelegate(
                uri: uri,
                requestedItems: requestedItems,
                trustAnchorRegistry: trustAnchorRegistry
            )
        )
        self.onCancel = onCancel
        self.onStartOver = onStartOver
        self._path = path
    }

    public var body: some View {
        VStack {
            switch self.delegate.state {
            case .initializing, .connecting, .connected:
                LoadingView(
                    loadingText: "Connecting...",
                    cancelButtonLabel: "Cancel",
                    onCancel: { self.cancel() }
                )
            case .sendingRequest, .sentRequest, .receivingResponse:
                LoadingView(
                    loadingText: "Processing...",
                    cancelButtonLabel: "Cancel",
                    onCancel: { self.cancel() }
                )
            case .receivedResponse:
                KioskSuccessView(onStartOver: onStartOver)
            case .mdocDisconnected, .error:
                KioskFailureView(onStartOver: onStartOver)
            case .action(.authorizeBluetoothForApp):
                if let url = URL(string: UIApplication.openSettingsURLString) {
                    Button("Authorize Bluetooth to continue") {
                        UIApplication.shared.open(url)
                    }
                    .buttonStyle(.bordered)
                }
            case .action(.turnOnBluetooth):
                Text("Turn on Bluetooth to continue.")
            }
        }
        .padding(.all, 30)
        .navigationBarBackButtonHidden(true)
    }

    func cancel() {
        self.delegate.cancel()
        self.onCancel()
    }
}

// MARK: - Delegate

class KioskScanViewDelegate: ObservableObject & MdocProximityReader.Delegate {
    @Published var state: MdocProximityReader.State = .initializing
    private var mdocReader: MdocProximityReader?

    init(
        uri: String,
        requestedItems: [String: [String: Bool]],
        trustAnchorRegistry: [String]?
    ) {
        self.mdocReader = MdocProximityReader(
            fromHolderQrCode: uri,
            delegate: self,
            requestedItems: requestedItems,
            trustAnchorRegistry: trustAnchorRegistry,
            l2capUsage: .disableL2CAP
        )
    }

    func cancel() {
        self.mdocReader?.disconnect()
    }

    func connectionState(changedTo: SpruceIDMobileSdk.MdocProximityReader.State) {
        DispatchQueue.main.async {
            self.state = changedTo
        }
    }
}

// MARK: - Header

struct KioskHeader: View {
    var body: some View {
        HStack(spacing: 8) {
            Image("SpruceLogo")
                .resizable()
                .frame(width: 28, height: 28)
            Text("Spruce County")
                .font(.system(size: 24, weight: .bold))
                .foregroundColor(Color("ColorStone950"))
        }
        .padding(.top, 15)
    }
}

// MARK: - Success View

struct KioskSuccessView: View {
    var onStartOver: () -> Void

    var body: some View {
        VStack(spacing: 24) {
            KioskHeader()

            Spacer()

            // Icon
            ZStack {
                Circle()
                    .fill(
                        LinearGradient(
                            colors: [Color("ColorBase50"), Color("ColorBlue200")],
                            startPoint: .top,
                            endPoint: .bottom
                        )
                    )
                    .frame(width: 80, height: 80)
                    .overlay(Circle().stroke(Color("ColorBlue300"), lineWidth: 2))
                    .shadow(color: Color.black.opacity(0.15), radius: 4, x: 0, y: 2)
                Image(systemName: "checkmark")
                    .font(.system(size: 40, weight: .medium))
                    .foregroundColor(Color("ColorBlue600"))
            }

            Text("Welcome!")
                .font(.system(size: 36, weight: .bold))
                .foregroundColor(Color("ColorStone950"))

            Text("Your identity has been successfully verified using your mobile driver's license, and a confirmation has been sent to your email.")
                .font(.system(size: 18))
                .foregroundColor(Color("ColorStone600"))
                .multilineTextAlignment(.center)
                .padding(.horizontal, 20)

            Spacer()
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .onTapGesture { onStartOver() }
    }
}

// MARK: - Failure View

struct KioskFailureView: View {
    var onStartOver: () -> Void

    var body: some View {
        VStack(spacing: 24) {
            KioskHeader()

            Spacer()

            // Icon
            ZStack {
                Circle()
                    .fill(
                        LinearGradient(
                            colors: [Color("ColorRose50"), Color("ColorRose200")],
                            startPoint: .top,
                            endPoint: .bottom
                        )
                    )
                    .frame(width: 80, height: 80)
                    .overlay(Circle().stroke(Color("ColorRose300"), lineWidth: 2))
                    .shadow(color: Color.black.opacity(0.15), radius: 4, x: 0, y: 2)
                Image(systemName: "xmark")
                    .font(.system(size: 40, weight: .medium))
                    .foregroundColor(Color("ColorRose600"))
            }

            Text("Invalid")
                .font(.system(size: 36, weight: .bold))
                .foregroundColor(Color("ColorRose700"))

            Text("Your ID has not been accepted. Please try again or check in with reception for verification.")
                .font(.system(size: 18))
                .foregroundColor(Color("ColorStone600"))
                .multilineTextAlignment(.center)
                .padding(.horizontal, 20)

            Spacer()

            Button(action: onStartOver) {
                HStack {
                    Image(systemName: "arrow.left")
                    Text("Start over")
                }
                .font(.system(size: 16, weight: .medium))
                .foregroundColor(Color("ColorBlue800"))
                .padding(.horizontal, 40)
                .padding(.vertical, 14)
                .background(Color("ColorBlue100"))
                .overlay(
                    RoundedRectangle(cornerRadius: 30)
                        .stroke(Color("ColorBlue400"), lineWidth: 1)
                )
                .cornerRadius(30)
            }

            Spacer().frame(height: 60)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}
