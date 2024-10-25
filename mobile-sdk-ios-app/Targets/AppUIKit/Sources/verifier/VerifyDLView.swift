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
                        print(code)
                        Task {
                            do {
                                try await verifyPdf417Barcode(payload: code)
                                success = true
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
                    .foregroundStyle(Color("TextHeader"))
                    .padding(.top, 20)
            )
        }

    }
}
