import SpruceIDMobileSdk
import SpruceIDMobileSdkRs
import SwiftUI

/// One verified document, prepared for display.
private struct MdocResultSection: Identifiable {
    let id: Int
    let title: String
    let issuer: String
    let elements: [String: GenericJSON]
}

struct VerifierMdocResultView: View {
    let documents: [VerifiedDocument]
    let failedDocTypes: [String]
    let responseProcessingErrors: String?
    var onClose: () -> Void
    var logVerification: (String, String, String) -> Void

    /// One section per verified document, in the order the holder returned them. Kept apart
    /// rather than merged into one map: two documents may share a namespace, and merging would
    /// let one silently overwrite the other's elements.
    // `private` because `MdocResultSection` is file-private: an internal property cannot
    // expose a private type.
    private let sections: [MdocResultSection]
    let title: String
    var issuer: String

    @State var showResponseProcessingErrors = false

    init(
        documents: [VerifiedDocument],
        failedDocTypes: [String],
        responseProcessingErrors: String?,
        onClose: @escaping () -> Void,
        logVerification: @escaping (String, String, String) -> Void
    ) {
        self.documents = documents
        self.failedDocTypes = failedDocTypes
        self.responseProcessingErrors = responseProcessingErrors
        self.onClose = onClose
        self.logVerification = logVerification
        self.sections = documents.enumerated().map { index, document in
            let elements = convertToGenericJSON(map: document.namespaces).dictValue ?? [:]
            // Try to find issuing_authority from any namespace. This is the document's own
            // claim about its issuer, digest-verified along with every other element.
            var foundIssuer = ""
            for (_, namespaceValue) in elements {
                if let authority = namespaceValue.dictValue?["issuing_authority"]?.toString(),
                   !authority.isEmpty {
                    foundIssuer = authority
                    break
                }
            }
            return MdocResultSection(
                id: index,
                title: credentialTypeDisplayName(for: document.docType),
                issuer: foundIssuer,
                elements: elements
            )
        }
        // A response where every document failed has nothing verified to name, so fall back to
        // the claimed labels -- otherwise the failure that matters most renders untitled.
        self.title = sections.first?.title
            ?? credentialTypeDisplayName(for: failedDocTypes.first ?? "")
        self.issuer = sections.first?.issuer ?? ""
        // One log entry per response, as before, named after the first verified document.
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
                ForEach(sections) { section in
                    // A single document is already named by the header, so only label sections
                    // when there is more than one to tell apart.
                    if sections.count > 1 {
                        Text(section.title)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .font(.customFont(font: .inter, style: .semiBold, size: .h3))
                            .foregroundStyle(Color("ColorStone950"))
                    }
                    CredentialObjectDisplayer(dict: section.elements)
                }
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
