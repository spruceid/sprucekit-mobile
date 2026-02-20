import SpruceIDMobileSdk
import SpruceIDMobileSdkRs
import SwiftUI

struct VerifierMdocResultView: View {
    var result: [String: [String: MDocItem]]
    let docTypes: [String]
    let issuerAuthenticationStatus: AuthenticationStatus
    let deviceAuthenticationStatus: AuthenticationStatus
    let responseProcessingErrors: String?
    var onClose: () -> Void
    var logVerification: (String, String, String) -> Void

    let mdoc: [String: GenericJSON]
    let title: String
    var issuer: String

    @State var showResponseProcessingErrors = false

    init(
        result: [String: [String: MDocItem]],
        docTypes: [String],
        issuerAuthenticationStatus: AuthenticationStatus,
        deviceAuthenticationStatus: AuthenticationStatus,
        responseProcessingErrors: String?,
        onClose: @escaping () -> Void,
        logVerification: @escaping (String, String, String) -> Void
    ) {
        self.result = result
        self.docTypes = docTypes
        self.issuerAuthenticationStatus = issuerAuthenticationStatus
        self.deviceAuthenticationStatus = deviceAuthenticationStatus
        self.responseProcessingErrors = responseProcessingErrors
        self.onClose = onClose
        self.logVerification = logVerification
        let mdoc = convertToGenericJSON(map: result)
        self.mdoc = mdoc.dictValue ?? [:]
        self.title = credentialTypeDisplayName(for: docTypes.first ?? "")
        // Try to find issuing_authority from any namespace
        var foundIssuer = ""
        for (_, namespaceValue) in self.mdoc {
            if let authority = namespaceValue.dictValue?["issuing_authority"]?.toString(),
               !authority.isEmpty {
                foundIssuer = authority
                break
            }
        }
        self.issuer = foundIssuer
        // @TODO: Log verification with real status
        logVerification(title, issuer, "VALID")
    }

    var body: some View {
        VStack {
            Text(title)
                .multilineTextAlignment(.leading)
                .frame(maxWidth: .infinity, alignment: .leading)
                .font(.customFont(font: .inter, style: .semiBold, size: .h0))
                .foregroundStyle(Color("ColorStone950"))
            Text(issuer)
                .multilineTextAlignment(.leading)
                .frame(maxWidth: .infinity, alignment: .leading)
                .font(.customFont(font: .inter, style: .semiBold, size: .h3))
                .foregroundStyle(Color("ColorStone600"))
            Divider()
            ScrollView(.vertical, showsIndicators: false) {
                VStack {
                    switch deviceAuthenticationStatus {
                    case .valid:
                        EmptyView()
                    case .invalid:
                        ToastError(message: "Device not authenticated")
                    case .unchecked:
                        ToastWarning(message: "Device not checked")
                    }
                    switch issuerAuthenticationStatus {
                    case .valid:
                        EmptyView()
                    case .invalid:
                        ToastError(message: "Issuer not authenticated")
                    case .unchecked:
                        ToastWarning(message: "Issuer not checked")
                    }
                }
                .onTapGesture {
                    showResponseProcessingErrors = true
                }
                CredentialObjectDisplayer(dict: mdoc)
            }
            Button {
                onClose()
            } label: {
                Text("Close")
                    .frame(width: UIScreen.screenWidth)
                    .padding(.horizontal, -20)
                    .font(.customFont(font: .inter, style: .medium, size: .h4))
            }
            .foregroundColor(.black)
            .padding(.vertical, 13)
            .cornerRadius(8)
            .overlay(
                RoundedRectangle(cornerRadius: 8)
                    .stroke(Color("ColorStone300"), lineWidth: 1)
            )

        }
        .navigationBarBackButtonHidden(true)
        .overlay(content: {
            SimpleAlertDialog(
                isPresented: $showResponseProcessingErrors,
                message: responseProcessingErrors
            )
        })
    }
}
