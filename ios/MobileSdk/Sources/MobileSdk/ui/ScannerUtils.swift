import SwiftUI
import AVKit

var isAuthorized: Bool {
    get async {
        let status = AVCaptureDevice.authorizationStatus(for: .video)

        // Determine if the user previously authorized camera access.
        var isAuthorized = status == .authorized

        // If the system hasn't determined the user's authorization status,
        // explicitly prompt them for approval.
        if status == .notDetermined {
            isAuthorized = await AVCaptureDevice.requestAccess(for: .video)
        }

        return isAuthorized
    }
}

/// Camera View Using AVCaptureVideoPreviewLayer
public struct CameraView: UIViewRepresentable {

    var frameSize: CGSize

    /// Camera Session
    @Binding var session: AVCaptureSession

    public init(frameSize: CGSize, session: Binding<AVCaptureSession>) {
        self.frameSize = frameSize
        self._session = session
    }

    public func makeUIView(context: Context) -> UIView {
        /// Defining camera frame size
        let view = UIViewType(frame: CGRect(origin: .zero, size: frameSize))
        view.backgroundColor = .clear

        let cameraLayer = AVCaptureVideoPreviewLayer(session: session)
        cameraLayer.frame = .init(origin: .zero, size: frameSize)
        cameraLayer.videoGravity = .resizeAspectFill
        cameraLayer.masksToBounds = true
        view.layer.addSublayer(cameraLayer)

        return view
    }

    public func updateUIView(_ uiView: UIViewType, context: Context) {

    }

}

extension UIScreen {
   static let screenWidth = UIScreen.main.bounds.size.width
   static let screenHeight = UIScreen.main.bounds.size.height
   static let screenSize = UIScreen.main.bounds.size
}
