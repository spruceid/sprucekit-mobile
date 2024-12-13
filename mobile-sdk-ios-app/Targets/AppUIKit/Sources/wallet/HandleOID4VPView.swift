import SpruceIDMobileSdkRs
import SpruceIDMobileSdk
import SwiftUI

struct HandleOID4VP: Hashable {
    var url: String
}

enum Oid4vpSignerError: Error {
    /// Illegal argument
    case illegalArgumentException(reason: String)
}

class Signer: PresentationSigner {
    private let keyId: String
    private let _jwk: String
    private let didJwk = DidMethodUtils(method: DidMethod.jwk)
        
    init(keyId: String?) throws {
        self.keyId = if (keyId == nil) { "reference-app/default-signing" } else { keyId! }
        let jwk = KeyManager.getJwk(id: self.keyId)
        if jwk == nil {
            throw Oid4vpSignerError.illegalArgumentException(reason: "Invalid kid")
        } else {
            self._jwk = jwk!
        }
    }

    func sign(payload: Data) async throws -> Data {
        let signature = KeyManager.signPayload(id: keyId, payload: [UInt8](payload))
        if signature == nil {
            throw Oid4vpSignerError.illegalArgumentException(reason:"Failed to sign payload")
        } else {
            return Data(signature!)
        }
    }

    func algorithm() -> String {
        // Parse the jwk as a JSON object and return the "alg" field
        var json = getGenericJSON(jsonString: _jwk)
        return json?.dictValue?["alg"]?.toString() ?? ""
    }

    func verificationMethod() async -> String {
        return try! await didJwk.vmFromJwk(jwk: _jwk)
    }

    func did() -> String {
        return try! didJwk.didFromJwk(jwk: _jwk)
    }

    func jwk() -> String {
        return _jwk
    }

    func cryptosuite() -> String {
        // TODO: Add an uniffi enum type for crypto suites.
        return "ecdsa-rdfc-2019"
    }
}

struct HandleOID4VPView: View {
    @Binding var path: NavigationPath
    var url: String

    @State private var holder: Holder?
    @State private var permissionRequest: PermissionRequest?
    @State private var permissionResponse: PermissionResponse?
    @State private var selectedCredential: ParsedCredential?
    @State private var credentialClaims: [String: [String: GenericJSON]] = [:]
    @State private var credentialPacks: [CredentialPack] = []

    @State private var err: String?
    
    let storageManager = StorageManager()

    func presentCredential() async {
        do {
            credentialPacks = try CredentialPack.loadAll(storageManager: storageManager)
            var credentials: [ParsedCredential] = []
            credentialPacks.forEach { credentialPack in
                credentials += credentialPack.list()
                credentialClaims = credentialClaims.merging(
                    credentialPack.findCredentialClaims(claimNames: ["name", "type"])
                ) { (_, new) in new }
            }
            
            let signer = try Signer(keyId: "reference-app/default-signing")

            holder = try await Holder.newWithCredentials(
                providedCredentials: credentials,
                trustedDids: trustedDids,
                signer: signer,
                contextMap: getVCPlaygroundOID4VCIContext()
            )

            permissionRequest = try await holder!.authorizationRequest(url: Url(url))
        } catch {
            err = error.localizedDescription
        }
    }

    func back() {
        while !path.isEmpty {
            path.removeLast()
        }
    }

    var body: some View {
        if err != nil {
            ErrorView(
                errorTitle: "Error Presenting Credential",
                errorDetails: err!,
                onClose: back
            )
        } else {
            if permissionRequest == nil {
                LoadingView(loadingText: "Loading...")
                .task {
                    await presentCredential()
                }
            } else if permissionResponse == nil {
                if !(permissionRequest?.credentials().isEmpty ?? false) {
                    CredentialSelector(
                        credentials: permissionRequest!.credentials(),
                        credentialClaims: credentialClaims,
                        getRequestedFields: { credential in
                            return permissionRequest!.requestedFields(credential: credential)
                        },
                        onContinue: { selectedCredentials in
                            Task {
                                do {
                                    selectedCredential = selectedCredentials.first
                                    permissionResponse = try await permissionRequest!.createPermissionResponse(
                                        selectedCredentials: selectedCredentials
                                    )
                                } catch {
                                    err = error.localizedDescription
                                }
                            }
                        },
                        onCancel: back
                    )
                } else {
                    ErrorView(
                        errorTitle: "No matching credential(s)",
                        errorDetails: "There are no credentials in your wallet that match the verification request you have scanned",
                        closeButtonLabel: "Cancel",
                        onClose: back
                    )
                }
            } else {
                DataFieldSelector(
                    requestedFields: permissionRequest!.requestedFields(credential: selectedCredential!),
                    onContinue: {
                        Task {
                            do {
                                _ = try await holder?.submitPermissionResponse(response: permissionResponse!)
                                let credentialPack = credentialPacks.first(where: { credentialPack in
                                    return credentialPack.get(credentialId: selectedCredential?.id() ?? "") != nil
                                })!
                                let credentialInfo = getCredentialIdTitleAndIssuer(credentialPack: credentialPack)
                                _ = WalletActivityLogDataStore.shared.insert(
                                    credentialPackId: credentialPack.id.uuidString,
                                    credentialId: credentialInfo.0,
                                    credentialTitle: credentialInfo.1,
                                    issuer: credentialInfo.2,
                                    action: "Verification",
                                    dateTime: Date(),
                                    additionalInformation: ""
                                )
                                back()
                            } catch {
                                err = error.localizedDescription
                            }
                        }
                    },
                    onCancel: back
                )
            }
        }
    }
}

struct DataFieldSelector: View {
    let requestedFields: [String]
    let onContinue: () -> Void
    let onCancel: () -> Void

    init(requestedFields: [RequestedField], onContinue: @escaping () -> Void, onCancel: @escaping () -> Void) {
        self.requestedFields = requestedFields.map { field in
            field.name()!.capitalized
        }
        self.onContinue = onContinue
        self.onCancel = onCancel
    }

    var body: some View {
        VStack {
            Group {
                Text("Verifier ")
                    .font(.customFont(font: .inter, style: .bold, size: .h2))
                    .foregroundColor(Color("ColorBlue600")) +
                Text("is requesting access to the following information")
                    .font(.customFont(font: .inter, style: .bold, size: .h2))
                    .foregroundColor(Color("ColorStone950"))
            }
            .multilineTextAlignment(.center)

            ScrollView {
                ForEach(requestedFields, id: \.self) { field in
                    Text("• \(field)")
                        .font(.customFont(font: .inter, style: .regular, size: .h4))
                        .foregroundStyle(Color("ColorStone950"))
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
            }

            HStack {
                Button {
                    onCancel()
                }  label: {
                    Text("Cancel")
                        .frame(maxWidth: .infinity)
                        .font(.customFont(font: .inter, style: .medium, size: .h4))
                }
                .foregroundColor(Color("ColorStone950"))
                .padding(.vertical, 13)
                .overlay(
                    RoundedRectangle(cornerRadius: 8)
                        .stroke(Color("ColorStone300"), lineWidth: 1)
                )

                Button {
                    onContinue()
                }  label: {
                    Text("Approve")
                        .frame(maxWidth: .infinity)
                        .font(.customFont(font: .inter, style: .medium, size: .h4))
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

struct CredentialSelector: View {
    let credentials: [ParsedCredential]
    let credentialClaims: [String: [String: GenericJSON]]
    let getRequestedFields: (ParsedCredential) -> [RequestedField]
    let onContinue: ([ParsedCredential]) -> Void
    let onCancel: () -> Void
    var allowMultiple: Bool = false

    @State private var selectedCredentials: [ParsedCredential] = []

    func selectCredential(credential: ParsedCredential) {
        if allowMultiple {
            selectedCredentials.append(credential)
        } else {
            selectedCredentials.removeAll()
            selectedCredentials.append(credential)
        }
    }

    func getCredentialTitle(credential: ParsedCredential) -> String {
        if let name = credentialClaims[credential.id()]?["name"]?.toString() {
            return name
        } else if let types = credentialClaims[credential.id()]?["type"]?.arrayValue {
            var title = ""
            types.forEach {
                if $0.toString() != "VerifiableCredential" {
                    title = $0.toString().camelCaseToWords()
                    return
                }
            }
            return title
        } else {
            return ""
        }
    }

    func toggleBinding(for credential: ParsedCredential) -> Binding<Bool> {
        Binding {
            selectedCredentials.contains(where: { $0.id() == credential.id()})
        } set: { _ in
            // TODO: update when allowing multiple
            selectCredential(credential: credential)
        }
    }

    var body: some View {
        VStack {
            Text("Select the credential\(allowMultiple ? "(s)" : "") to share")
                .font(.customFont(font: .inter, style: .bold, size: .h2))
                .foregroundStyle(Color("ColorStone950"))

            // TODO: Add select all when implement allowMultiple

            ScrollView {
                ForEach(0..<credentials.count, id: \.self) { idx in

                    let credential = credentials[idx]

                    CredentialSelectorItem(
                        credential: credential,
                        requestedFields: getRequestedFields(credential),
                        getCredentialTitle: { credential in
                            getCredentialTitle(credential: credential)
                        },
                        isChecked: toggleBinding(for: credential)
                    )
                }
            }

            HStack {
                Button {
                    onCancel()
                }  label: {
                    Text("Cancel")
                        .frame(maxWidth: .infinity)
                        .font(.customFont(font: .inter, style: .medium, size: .h4))
                }
                .foregroundColor(Color("ColorStone950"))
                .padding(.vertical, 13)
                .overlay(
                    RoundedRectangle(cornerRadius: 8)
                        .stroke(Color("ColorStone300"), lineWidth: 1)
                )

                Button {
                    if !selectedCredentials.isEmpty {
                        onContinue(selectedCredentials)
                    }
                }  label: {
                    Text("Continue")
                        .frame(maxWidth: .infinity)
                        .font(.customFont(font: .inter, style: .medium, size: .h4))
                }
                .foregroundColor(.white)
                .padding(.vertical, 13)
                .background(Color("ColorStone600"))
                .clipShape(RoundedRectangle(cornerRadius: 8))
                .opacity(selectedCredentials.isEmpty ? 0.6 : 1)
            }
            .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.horizontal, 24)
        .navigationBarBackButtonHidden(true)
    }
}

struct CredentialSelectorItem: View {
    let credential: ParsedCredential
    let requestedFields: [String]
    let getCredentialTitle: (ParsedCredential) -> String
    @Binding var isChecked: Bool

    @State var expanded = false

    init(
        credential: ParsedCredential,
        requestedFields: [RequestedField],
        getCredentialTitle: @escaping (ParsedCredential) -> String,
        isChecked: Binding<Bool>
    ) {
        self.credential = credential
        self.requestedFields = requestedFields.map { field in
            field.name()!.capitalized
        }
        self.getCredentialTitle = getCredentialTitle
        self._isChecked = isChecked
    }

    var body: some View {
        VStack {
            HStack {
                Toggle(isOn: $isChecked) {
                    Text(getCredentialTitle(credential))
                        .font(.customFont(font: .inter, style: .semiBold, size: .h3))
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
                        .font(.customFont(font: .inter, style: .regular, size: .h4))
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
