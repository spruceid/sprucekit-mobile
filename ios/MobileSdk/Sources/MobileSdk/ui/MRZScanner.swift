import Foundation
import SwiftUI
import Vision
import AVKit
import os.log

public struct MRZScanner: View {
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

    /// QR Code Scanner properties
    @State private var isScanning: Bool = false
    @State private var session: AVCaptureSession = .init()

    /// Camera QR Output delegate
    @State private var videoDataOutputDelegate: AVCaptureVideoDataOutput = .init()
    /// Scanned code
    @State private var scannedCode: String = ""

    /// Output delegate
    @StateObject private var videoOutputDelegate = MRZScannerDelegate()

    @State private var regionOfInterest =  CGSize(width: 0, height: 0)

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

    func calculateRegionOfInterest() {
        let desiredHeightRatio = 0.15
        let desiredWidthRatio = 0.6

        let size = CGSize(width: desiredWidthRatio, height: desiredHeightRatio)

        // Make it centered.
        self.regionOfInterest = size
    }

    public var body: some View {
        ZStack {
            GeometryReader { dimension in
                let viewSize = dimension.size
                let size = UIScreen.screenSize
                ZStack {
                    CameraView(frameSize: CGSize(width: size.width, height: size.height), session: $session)
                    /// Blur layer with clear cut out
                    ZStack {
                        Rectangle()
                            .foregroundColor(Color.black.opacity(backgroundOpacity))
                            .frame(width: size.width, height: UIScreen.screenHeight)
                        Rectangle()
                            .frame(
                                width: size.width * regionOfInterest.height,
                                height: size.height * regionOfInterest.width
                            )
                            .position(CGPoint(x: viewSize.width/2, y: viewSize.height/2))
                            .blendMode(.destinationOut)
                        }
                        .compositingGroup()

                    /// Scan area edges
                    ZStack {
                        ForEach(0...4, id: \.self) { index in
                            let rotation = Double(index) * 90

                            RoundedRectangle(cornerRadius: 2, style: .circular)
                                /// Triming to get Scanner like Edges
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
                        /// Scanner Animation
                        Rectangle()
                            .fill(readerColor)
                            .frame(width: size.height * regionOfInterest.width, height: 2.5)
                            .rotationEffect(Angle(degrees: 90))
                            .offset(x: isScanning ? (size.width * 0.15)/2 : -(size.width * 0.15)/2)
                    }
                    .frame(width: size.width * regionOfInterest.height, height: size.height * regionOfInterest.width)
                    .position(CGPoint(x: viewSize.width/2, y: viewSize.height/2))

                }
                /// Square Shape
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
            HStack {
                VStack(alignment: .leading) {
                    Button(cancelButtonLabel) {
                        onCancel()
                    }
                    .font(cancelButtonFont)
                    .foregroundColor(textColor)
                }
                .rotationEffect(.init(degrees: 90))
                Spacer()
                VStack(alignment: .leading) {
                    Text(title)
                        .font(titleFont)
                        .foregroundColor(textColor)

                    Text(subtitle)
                        .font(subtitleFont)
                        .foregroundColor(textColor)
                }
                .rotationEffect(.init(degrees: 90))

            }
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
                        calculateRegionOfInterest()
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
        .onChange(of: videoOutputDelegate.scannedCode) { newValue in
            if let code = newValue {
                scannedCode = code

                /// When the first code scan is available, immediately stop the camera.
                session.stopRunning()

                /// Stopping scanner animation
                deActivateScannerAnimation()
                /// Clearing the data on delegate
                videoOutputDelegate.scannedCode = nil

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

            session.beginConfiguration()

            /// Camera input
            let input = try AVCaptureDeviceInput(device: device)
            /// Checking whether input can be added to the session
            guard session.canAddInput(input) else {
                os_log("Error: %@", log: .default, type: .error, String("UNKNOWN INPUT ERROR"))
                return
            }
            session.addInput(input)

            /// Camera Output
            videoDataOutputDelegate.alwaysDiscardsLateVideoFrames = true
            videoDataOutputDelegate.setSampleBufferDelegate(videoOutputDelegate, queue: .main)

            videoDataOutputDelegate.videoSettings = [
                kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_420YpCbCr8BiPlanarFullRange
            ]
            videoDataOutputDelegate.connection(with: AVMediaType.video)?.preferredVideoStabilizationMode = .off
            /// Checking whether output can be added to the session
            guard session.canAddOutput(videoDataOutputDelegate) else {
                os_log("Error: %@", log: .default, type: .error, String("UNKNOWN OUTPUT ERROR"))
                return
            }
            session.addOutput(videoDataOutputDelegate)

            // Set zoom and autofocus to help focus on very small text.
            do {
                try device.lockForConfiguration()
                device.videoZoomFactor = 1.5
                device.autoFocusRangeRestriction = .near
                device.unlockForConfiguration()
            } catch {
                print("Could not set zoom level due to error: \(error)")
                return
            }

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

// MRZScannerDelegate and MRZScanner finder were inspired on https://github.com/girayk/MrzScanner

public class MRZScannerDelegate: NSObject, ObservableObject, AVCaptureVideoDataOutputSampleBufferDelegate {

    @Published public var scannedCode: String?
    var mrzFinder = MRZFinder()

    public func captureOutput(
            _ output: AVCaptureOutput,
            didOutput sampleBuffer: CMSampleBuffer,
            from connection: AVCaptureConnection
    ) {
        let request = VNRecognizeTextRequest(completionHandler: recognizeTextHandler)

        // This is implemented in VisionViewController.
        if let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) {
            // Configure for running in real-time.
            request.recognitionLevel = .fast
            // Language correction won't help recognizing phone numbers. It also
            // makes recognition slower.
            request.usesLanguageCorrection = false

            let requestHandler = VNImageRequestHandler(
                cvPixelBuffer: pixelBuffer,
                orientation: CGImagePropertyOrientation.up,
                options: [:]
            )
            do {
                try requestHandler.perform([request])
            } catch {
                print(error)
            }
        }
    }

    func recognizeTextHandler(request: VNRequest, error: Error?) {

        var codes = [String]()

        guard let results = request.results as? [VNRecognizedTextObservation] else {
            return
        }

        let maximumCandidates = 1
        for visionResult in results {
            guard let candidate = visionResult.topCandidates(maximumCandidates).first else { continue }

            if let result = mrzFinder.checkMrz(str: candidate.string) {
                if result != "nil" {
                    codes.append(result)
                }
            }
        }

        mrzFinder.storeAndProcessFrameContent(strings: codes)

        // Check if we have any temporally stable numbers.
        if let sureNumber = mrzFinder.getStableString() {
            mrzFinder.reset(string: sureNumber)
            scannedCode = sureNumber
        }
    }
}

class MRZFinder {
    var frameIndex = 0
    var captureFirst = ""
    var captureSecond = ""
    var captureThird = ""
    var mrz = ""
    var tmpMrz = ""

    typealias StringObservation = (lastSeen: Int, count: Int)

    // Dictionary of seen strings. Used to get stable recognition before
    // displaying anything.
    var seenStrings = [String: StringObservation]()
    var bestCount = 0
    var bestString = ""

    func storeAndProcessFrameContent(strings: [String]) {
        // Store all found strings
        for string in strings {
            if seenStrings[string] == nil {
                seenStrings[string] = (lastSeen: 0, count: -1)
            }
            seenStrings[string]?.lastSeen = frameIndex
            seenStrings[string]?.count += 1
        }

        // Remove all strings that weren't seen in a while
        var obsoleteStrings = [String]()
        for (string, obs) in seenStrings {
            // Remove obsolete text after 30 frames (~1s).
            if obs.lastSeen < frameIndex - 30 {
                obsoleteStrings.append(string)
            }

            // Find the string with the greatest count.
            let count = obs.count
            if !obsoleteStrings.contains(string) && count > bestCount {
                bestCount = count
                bestString = string
            }
        }
        // Remove old strings.
        for string in obsoleteStrings {
            seenStrings.removeValue(forKey: string)
        }

        frameIndex += 1
    }

    func checkMrz(str: String) -> (String)? {
        let firstLineRegex = "(IAUT)(0|O)\\d{10}(SRC)\\d{10}<<"
        let secondLineRegex = "[0-9O]{7}(M|F|<)[0-9O]{7}[A-Z0<]{3}[A-Z0-9<]{11}[0-9O]"
        let thirdLineRegex = "([A-Z0]+<)+<([A-Z0]+<)+<+"
        // swiftlint:disable:next line_length
        let completeMrzRegex = "(IAUT)(0|O)\\d{10}(SRC)\\d{10}<<\n[0-9O]{7}(M|F|<)[0-9O]{7}[A-Z0<]{3}[A-Z0-9<]{11}[0-9O]\n([A-Z0]+<)+<([A-Z0]+<)+<+"

        let firstLine = str.range(of: firstLineRegex, options: .regularExpression, range: nil, locale: nil)
        let secondLine = str.range(of: secondLineRegex, options: .regularExpression, range: nil, locale: nil)
        let thirdLine = str.range(of: thirdLineRegex, options: .regularExpression, range: nil, locale: nil)

        if firstLine != nil {
            if str.count == 30 {
                captureFirst = str
            }
        }
        if secondLine != nil {
            if str.count == 30 {
                captureSecond = str
            }
        }
        if thirdLine != nil {
            if str.count == 30 {
                captureThird = str
            }
        }

        if captureFirst.count == 30 && captureSecond.count == 30 && captureThird.count == 30 {
            let validChars = Set("ABCDEFGHIJKLKMNOPQRSTUVWXYZ1234567890<")
            tmpMrz = (
                captureFirst.filter { validChars.contains($0) } + "\n" +
                captureSecond.filter { validChars.contains($0) } + "\n" +
                captureThird.filter { validChars.contains($0) }
            ).replacingOccurrences(of: " ", with: "<")

            let checkMrz = tmpMrz.range(of: completeMrzRegex, options: .regularExpression, range: nil, locale: nil)
            if checkMrz != nil {
                mrz = tmpMrz
            }
        }

        if mrz == "" {
            return nil
        }

        // Fix IAUT0... prefix
        mrz = mrz.replacingOccurrences(of: "IAUT0", with: "IAUTO")

        return mrz
    }

    func getStableString() -> String? {
        // Require the recognizer to see the same string at least 10 times.
        if bestCount >= 10 {
            return bestString
        } else {
            return nil
        }
    }

    func reset(string: String) {
        seenStrings.removeValue(forKey: string)
        bestCount = 0
        bestString = ""
        captureFirst = ""
        captureSecond = ""
        captureThird = ""
        mrz = ""
        tmpMrz = ""
    }
}
