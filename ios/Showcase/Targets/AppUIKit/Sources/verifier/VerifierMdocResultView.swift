import SpruceIDMobileSdk
import SpruceIDMobileSdkRs
import SwiftUI

struct VerifierMdocResultView: View {
    var result: [String: [String: MDocItem]]
    let docTypes: [String]
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
        responseProcessingErrors: String?,
        onClose: @escaping () -> Void,
        logVerification: @escaping (String, String, String) -> Void
    ) {
        self.result = result
        self.docTypes = docTypes
        self.responseProcessingErrors = responseProcessingErrors
        self.onClose = onClose
        self.logVerification = logVerification
        let mdoc = convertToGenericJSON(map: result)
        self.mdoc = mdoc.dictValue ?? [:]
        self.title = credentialTypeDisplayName(for: docTypes.first ?? "")
        // Try to find the issuing authority from any namespace. The element identifier is
        // namespace-specific: ISO 18013-5 uses `issuing_authority`, ISO 23220-2 uses
        // `issuing_authority_unicode`.
        var foundIssuer = ""
        outer: for (_, namespaceValue) in self.mdoc {
            for identifier in ["issuing_authority", "issuing_authority_unicode"] {
                if let authority = namespaceValue.dictValue?[identifier]?.toString(),
                   !authority.isEmpty {
                    foundIssuer = authority
                    break outer
                }
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
                // Whatever was verified is always shown; anything that went wrong is always
                // reported alongside it. Elements come only from documents that passed every
                // check, and a document that failed always contributes at least one error here.
                if responseProcessingErrors != nil {
                    ToastError(message: "Verification errors")
                        .onTapGesture {
                            showResponseProcessingErrors = true
                        }
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
