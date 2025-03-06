import SpruceIDMobileSdk
import SpruceIDMobileSdkRs
import SwiftUI

struct HandleMdocOID4VP: Hashable {
    var url: String
}

public enum MdocOID4VPState {
    case err, selectCredential, selectiveDisclosure, loading, none
}

public class MdocOID4VPError {
    let title: String
    let details: String

    init(title: String, details: String) {
        self.title = title
        self.details = details
    }
}

struct HandleMdocOID4VPView: View {
    @EnvironmentObject private var credentialPackObservable:
        CredentialPackObservable
    @Binding var path: NavigationPath
    var url: String

    @State private var handler: Oid4vp180137?
    @State private var request: InProgressRequest180137?
    @State private var selectedMatch: RequestMatch180137?
    @State private var credentialPacks: [CredentialPack] = []

    @State private var err: MdocOID4VPError?
    @State private var state = MdocOID4VPState.none

    func presentCredential() async {
        do {
            credentialPacks = credentialPackObservable.credentialPacks

            var credentials: [Mdoc] = []
            credentialPacks.forEach { credentialPack in
                credentialPack.list().forEach { credential in
                    if let mdoc = credential.asMsoMdoc() {
                        credentials.append(mdoc)
                    }
                }
            }

            if !credentials.isEmpty {
                let handlerRef = try Oid4vp180137(
                    credentials: credentials,
                    keystore: KeyManager()
                )
                handler = handlerRef
                request = try await handlerRef.processRequest(url: url)
                state = .selectCredential
            } else {
                err = MdocOID4VPError(
                    title: "No matching credential(s)",
                    details:
                        "There are no credentials in your wallet that match the verification request you have scanned"
                )
                state = .err
            }
        } catch {
            err = MdocOID4VPError(
                title: "No matching credential(s)",
                details: error.localizedDescription)
            state = .err
        }
    }

    func back(url: Url? = nil) {
        while !path.isEmpty {
            path.removeLast()
        }
        if url != nil {
            if let url = URL(string: url!) {
                UIApplication.shared.open(url)
            }
        }
    }

    var body: some View {
        switch state {
        case .err:
            ErrorView(
                errorTitle: err!.title,
                errorDetails: err!.details,
                onClose: {
                    back()
                }
            )
        case .selectCredential:
            MdocSelector(
                matches: request!.matches(),
                onContinue: { match in
                    selectedMatch = match
                    state = .selectiveDisclosure
                },
                onCancel: {
                    back()
                }
            )
        case .selectiveDisclosure:
            MdocFieldSelector(
                match: selectedMatch!,
                onContinue: { approvedResponse in
                    Task {
                        do {
                            let redirect = try await request!.respond(
                                approvedResponse: approvedResponse)
                            let credentialPack = credentialPacks.first {
                                credentialPack in
                                return credentialPack.get(
                                    credentialId: (selectedMatch?.credentialId())!
                                ) != nil
                            }!
                            let credentialInfo = getCredentialIdTitleAndIssuer(
                                credentialPack: credentialPack)
                            _ = WalletActivityLogDataStore.shared.insert(
                                credentialPackId: credentialPack.id
                                    .uuidString,
                                credentialId: credentialInfo.0,
                                credentialTitle: credentialInfo.1,
                                issuer: credentialInfo.2,
                                action: "Verification",
                                dateTime: Date(),
                                additionalInformation: ""
                            )
                            back(url: redirect)
                        } catch {
                            err = MdocOID4VPError(
                                title: "Failed to selective disclose fields",
                                details: error.localizedDescription
                            )
                            state = .err
                        }
                    }
                },
                onCancel: {
                    back()
                }
            )
        case .loading:
            LoadingView(loadingText: "Loading...")
        case .none:
            LoadingView(loadingText: "Loading...")
                .task {
                    await presentCredential()
                }
        }
    }
}

struct MdocFieldSelector: View {
    let match: RequestMatch180137
    let onContinue: (ApprovedResponse180137) -> Void
    let onCancel: () -> Void

    @State private var selectedFields: [String]
    let requiredFields: [String]

    init(
        match: RequestMatch180137,
        onContinue: @escaping (ApprovedResponse180137) -> Void,
        onCancel: @escaping () -> Void
    ) {
        self.match = match
        self.onContinue = onContinue
        self.onCancel = onCancel
        self.requiredFields = match.requestedFields()
            .filter { $0.required || $0.selectivelyDisclosable }
            .map { $0.id }
        self.selectedFields = self.requiredFields
    }

    func toggleBinding(for field: RequestedField180137) -> Binding<Bool> {
        Binding {
            selectedFields.contains(where: { $0 == field.id })
        } set: { _ in
            if selectedFields.contains(field.id) {
                selectedFields.removeAll(where: { $0 == field.id })
            } else {
                selectedFields.append(field.id)
            }
        }
    }

    var body: some View {
        VStack {
            Group {
                Text("Verifier ")
                    .font(.customFont(font: .inter, style: .bold, size: .h2))
                    .foregroundColor(Color("ColorBlue600"))
                    + Text("is requesting access to the following information")
                    .font(.customFont(font: .inter, style: .bold, size: .h2))
                    .foregroundColor(Color("ColorStone950"))
            }
            .multilineTextAlignment(.center)

            ScrollView {
                ForEach(match.requestedFields(), id: \.self) { field in
                    return MdocFieldSelectorItem(
                        field: field,
                        required: false,
                        isChecked: toggleBinding(for: field)
                    )
                }
            }

            HStack {
                Button {
                    onCancel()
                } label: {
                    Text("Cancel")
                        .frame(maxWidth: .infinity)
                        .font(
                            .customFont(font: .inter, style: .medium, size: .h4)
                        )
                }
                .foregroundColor(Color("ColorStone950"))
                .padding(.vertical, 13)
                .overlay(
                    RoundedRectangle(cornerRadius: 8)
                        .stroke(Color("ColorStone300"), lineWidth: 1)
                )
                Button {
                    let approvedResponse = ApprovedResponse180137(
                        credentialId: match.credentialId(),
                        approvedFields: selectedFields
                    )
                    onContinue(approvedResponse)
                } label: {
                    Text("Approve")
                        .frame(maxWidth: .infinity)
                        .font(
                            .customFont(font: .inter, style: .medium, size: .h4)
                        )
                }
                .foregroundColor(.white)
                .padding(.vertical, 13)
                .background(Color("ColorEmerald900"))
                .clipShape(RoundedRectangle(cornerRadius: 8))
            }
            .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.horizontal, 24)
        .navigationBarBackButtonHidden(true)
    }
}

struct MdocFieldSelectorItem: View {
    let field: RequestedField180137
    let required: Bool
    @Binding var isChecked: Bool

    var body: some View {
        HStack {
            Toggle(isOn: $isChecked) {
                Text(field.displayableName)
                    .font(.customFont(font: .inter, style: .regular, size: .h4))
                    .foregroundStyle(Color("ColorStone950"))
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .toggleStyle(iOSCheckboxToggleStyle(enabled: !required))
        }
    }
}

struct MdocSelector: View {
    let matches: [RequestMatch180137]
    let onContinue: (RequestMatch180137) -> Void
    let onCancel: () -> Void

    @State private var selectedCredential: RequestMatch180137?

    func selectCredential(credential: RequestMatch180137) {
        if selectedCredential?.credentialId() == credential.credentialId() {
            selectedCredential = nil
        } else {
            selectedCredential = credential
        }
    }

    func toggleBinding(for credential: RequestMatch180137) -> Binding<Bool> {
        Binding {
            selectedCredential?.credentialId() == credential.credentialId()
        } set: { _ in
            selectCredential(credential: credential)
        }
    }

    var body: some View {
        VStack {
            Text("Select the credential to share")
                .font(.customFont(font: .inter, style: .bold, size: .h2))
                .foregroundStyle(Color("ColorStone950"))
            ScrollView {
                ForEach(0..<matches.count, id: \.self) { idx in

                    let match = matches[idx]

                    MdocSelectorItem(
                        match: match,
                        isChecked: toggleBinding(for: match)
                    )
                }
            }

            HStack {
                Button {
                    onCancel()
                } label: {
                    Text("Cancel")
                        .frame(maxWidth: .infinity)
                        .font(
                            .customFont(font: .inter, style: .medium, size: .h4)
                        )
                }
                .foregroundColor(Color("ColorStone950"))
                .padding(.vertical, 13)
                .overlay(
                    RoundedRectangle(cornerRadius: 8)
                        .stroke(Color("ColorStone300"), lineWidth: 1)
                )

                Button {
                    if selectedCredential != nil {
                        onContinue(selectedCredential!)
                    }
                } label: {
                    Text("Continue")
                        .frame(maxWidth: .infinity)
                        .font(
                            .customFont(font: .inter, style: .medium, size: .h4)
                        )
                }
                .foregroundColor(.white)
                .padding(.vertical, 13)
                .background(Color("ColorStone600"))
                .clipShape(RoundedRectangle(cornerRadius: 8))
                .opacity(selectedCredential == nil ? 0.6 : 1)
            }
            .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.horizontal, 24)
        .navigationBarBackButtonHidden(true)
    }
}

struct MdocSelectorItem: View {
    let match: RequestMatch180137
    @Binding var isChecked: Bool

    @State var expanded = false
    @State var requestedFields: [String]

    init(
        match: RequestMatch180137,
        isChecked: Binding<Bool>
    ) {
        self.match = match
        self.requestedFields = match.requestedFields().map { field in
            field.displayableName
        }
        self._isChecked = isChecked
    }

    var body: some View {
        VStack {
            HStack {
                Toggle(isOn: $isChecked) {
                    Text("Mobile Drivers License")
                        .font(
                            .customFont(
                                font: .inter, style: .semiBold, size: .h3)
                        )
                        .foregroundStyle(Color("ColorStone950"))
                }
                .toggleStyle(iOSCheckboxToggleStyle())
                Spacer()
                if expanded {
                    Image("Collapse")
                        .onTapGesture {
                            expanded = false
                        }
                } else {
                    Image("Expand")
                        .onTapGesture {
                            expanded = true
                        }
                }
            }
            VStack(alignment: .leading) {
                ForEach(requestedFields, id: \.self) { field in
                    Text("• \(field)")
                        .font(
                            .customFont(
                                font: .inter, style: .regular, size: .h4)
                        )
                        .foregroundStyle(Color("ColorStone950"))
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
            .hide(if: !expanded)
        }
        .padding(16)
        .overlay(
            RoundedRectangle(cornerRadius: 8)
                .stroke(Color("ColorBase300"), lineWidth: 1)
        )
        .padding(.vertical, 6)
    }
}
