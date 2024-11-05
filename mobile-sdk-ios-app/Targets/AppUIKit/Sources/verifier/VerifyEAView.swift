import SwiftUI
import SpruceIDMobileSdkRs

struct VerifyEA: Hashable {}

struct VerifyEAView: View {
    
    @State var stepOneValue: String?
    @State var success: Bool?
    
    @Binding var path: NavigationPath
    
    var body: some View {
        if success == nil {
            if stepOneValue == nil {
                ScanningComponent(
                    path: $path,
                    scanningParams: Scanning(
                        subtitle: "Scan the front of your\nemployment authorization",
                        scanningType: .qrcode,
                        onCancel: {
                            path.removeLast()
                        },
                        onRead: { code in
                            stepOneValue = code
                        }
                    )
                )
            } else {
                ScanningComponent(
                    path: $path,
                    scanningParams: Scanning(
                        title: "Scan MRZ",
                        subtitle: "Scan the back of your document",
                        scanningType: .mrz,
                        onCancel: {
                            path.removeLast()
                        },
                        onRead: { code in
                            Task {
                                do {
                                    try await verifyVcbQrcodeAgainstMrz(mrzPayload: code, qrPayload: stepOneValue!)
                                    success = true
                                } catch {
                                    print(error)
                                    success = false
                                }
                            }
                        }
                    )
                )
            }
        } else {
            VerifierSuccessView(
                path: $path,
                success: success!,
                content: Text(success! ? "Valid Employment Authorization" : "Invalid Employment Authorization")
                    .font(.customFont(font: .inter, style: .semiBold, size: .h1))
                    .foregroundStyle(Color("TextHeader"))
                    .padding(.top, 20)
            )
        }
    }
}
