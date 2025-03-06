import SwiftUI
import SpruceIDMobileSdkRs

struct VerifyDL: Hashable {}

struct VerifyDLView: View {

    @State var success: Bool?

    @Binding var path: NavigationPath

    var body: some View {
        if success == nil {
            ScanningComponent(
                path: $path,
                scanningParams: Scanning(
                    subtitle: "Scan the\nback of your driver's license",
                    scanningType: .pdf417,
                    onCancel: {
                        path.removeLast()
                    },
                    onRead: { code in
                        Task {
                            do {
                                try await verifyPdf417Barcode(payload: code)
                                success = true
                                _ = VerificationActivityLogDataStore.shared.insert(
                                    credentialTitle: "Driver's License",
                                    issuer: "Utopia Department of Motor Vehicles",
                                    status: "VALID",
                                    verificationDateTime: Date(),
                                    additionalInformation: ""
                                )
                            } catch {
                                print(error)
                                success = false
                            }
                        }
                    }
                )
            )
        } else {
            VerifierSuccessView(
                path: $path,
                success: success!,
                content: Text(success! ? "Valid Driver's License" : "Invalid Driver's License")
                    .font(.customFont(font: .inter, style: .semiBold, size: .h1))
                    .foregroundStyle(Color("ColorStone950"))
                    .padding(.top, 20)
            )
        }

    }
}
