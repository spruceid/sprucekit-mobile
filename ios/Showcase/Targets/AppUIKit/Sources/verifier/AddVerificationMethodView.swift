import SpruceIDMobileSdkRs
import SwiftUI

struct AddVerificationMethod: Hashable {}

struct AddVerificationMethodView: View {
    @Binding var path: NavigationPath

    @State var err: String?
    @State var qrcode: String?

    func onBack() {
        path.removeLast()
    }

    func onRead(content: String) {
        do {
            let jsonArray = getGenericJSON(jsonString: content)?.arrayValue
            for (_, item) in try jsonArray.unwrap().enumerated() {
                let credentialName =
                    item.dictValue?["credential_name"]?.toString() ?? ""
                _ = VerificationMethodDataStore.shared.insert(
                    type: item.dictValue?["type"]?.toString() ?? "",
                    name: credentialName,
                    description: "Verifies \(credentialName) Credentials",
                    verifierName: item.dictValue?["verifier_name"]?.toString()
                        ?? "",
                    url: item.dictValue?["url"]?.toString() ?? ""
                )
            }
            onBack()
        } catch {
            err =
                "Couldn't parse QR Code payload. Error: \(error.localizedDescription)"
        }

    }

    var body: some View {
        ZStack {
            if err != nil {
                ErrorView(
                    errorTitle: "Error adding verification method",
                    errorDetails: err!,
                    onClose: onBack
                )
            } else if qrcode == nil {
                ScanningComponent(
                    path: $path,
                    scanningParams: Scanning(
                        subtitle: "Scan Verification QR Code",
                        scanningType: .qrcode,
                        onCancel: onBack,
                        onRead: onRead
                    )
                )
            } else {
                LoadingView(
                    loadingText: "Storing Verification Method..."
                )
            }
        }
        .navigationBarBackButtonHidden(true)
    }
}
