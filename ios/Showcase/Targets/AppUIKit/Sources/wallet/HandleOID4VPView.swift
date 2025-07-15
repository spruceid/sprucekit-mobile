import SpruceIDMobileSdk
import SpruceIDMobileSdkRs
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
        self.keyId =
            if keyId == nil { DEFAULT_SIGNING_KEY_ID } else { keyId! }
        if !KeyManager.keyExists(id: self.keyId) {
            _ = KeyManager.generateSigningKey(id: self.keyId)
        }
        let jwk = KeyManager.getJwk(id: self.keyId)
        if jwk == nil {
            throw Oid4vpSignerError.illegalArgumentException(
                reason: "Invalid kid")
        } else {
            self._jwk = jwk!
        }
    }

    func sign(payload: Data) async throws -> Data {
        let signature = KeyManager.signPayload(
            id: keyId, payload: [UInt8](payload))
        if signature == nil {
            throw Oid4vpSignerError.illegalArgumentException(
                reason: "Failed to sign payload")
        } else {
            return Data(signature!)
        }
    }

    func algorithm() -> String {
        // Parse the jwk as a JSON object and return the "alg" field
        var json = getGenericJSON(jsonString: _jwk)
        return json?.dictValue?["alg"]?.toString() ?? "ES256"
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

public enum OID4VPState {
    case err, selectCredential, selectiveDisclosure, loading, none
}

public class OID4VPError {
    let title: String
    let details: String

    init(title: String, details: String) {
        self.title = title
        self.details = details
    }
}

struct HandleOID4VPView: View {
    @EnvironmentObject private var credentialPackObservable:
        CredentialPackObservable
    @Binding var path: NavigationPath
    var url: String

    @State private var holder: Holder?
    @State private var permissionRequest: PermissionRequest?
    @State private var permissionResponse: PermissionResponse?
    @State private var lSelectedCredentials: [PresentableCredential]?
    @State private var selectedCredential: PresentableCredential?
    @State private var credentialClaims: [String: [String: GenericJSON]] = [:]
    @State private var credentialPacks: [CredentialPack] = []

    @State private var err: OID4VPError?
    @State private var state = OID4VPState.none

    func presentCredential() async {
        do {
            credentialPacks = credentialPackObservable.credentialPacks
            var credentials: [ParsedCredential] = []
            credentialPacks.forEach { credentialPack in
                credentials += credentialPack.list()
                credentialClaims = credentialClaims.merging(
                    credentialPack.findCredentialClaims(claimNames: [
                        "name", "type"
                    ])
                ) { (_, new) in new }
            }

            let signer = try Signer(keyId: DEFAULT_SIGNING_KEY_ID)

            holder = try await Holder.newWithCredentials(
                providedCredentials: credentials,
                trustedDids: trustedDids,
                signer: signer,
                contextMap: getVCPlaygroundOID4VCIContext()
            )
            let newurl = url.replacing("authorize", with: "")
            let tmpPermissionRequest = try await holder!.authorizationRequest(
                req: Url(newurl))
            let permissionRequestCredentials =
                tmpPermissionRequest.credentials()

            if permissionRequestCredentials.count == 1 {
                selectedCredential = permissionRequestCredentials.first
            }

            permissionRequest = tmpPermissionRequest
            if !permissionRequestCredentials.isEmpty {
                if permissionRequestCredentials.count == 1 {
                    lSelectedCredentials = permissionRequestCredentials
                    selectedCredential =
                        permissionRequestCredentials.first
                    state = .selectiveDisclosure
                } else {
                    state = OID4VPState.selectCredential
                }
            } else {
                err = OID4VPError(
                    title: "No matching credential(s)",
                    details:
                        "There are no credentials in your wallet that match the verification request you have scanned"
                )
                state = .err
            }
        } catch {
            err = OID4VPError(
                title: "No matching credential(s)",
                details: error.localizedDescription)
            state = .err
        }
    }

    func back() {
        while !path.isEmpty {
            path.removeLast()
        }
    }

    var body: some View {
        switch state {
        case .err:
            ErrorView(
                errorTitle: err!.title,
                errorDetails: err!.details,
                onClose: back
            )
        case .selectCredential:
            CredentialSelector(
                credentials: permissionRequest!.credentials(),
                credentialClaims: credentialClaims,
                getRequestedFields: { credential in
                    return permissionRequest!.requestedFields(
                        credential: credential)
                },
                onContinue: { selectedCredentials in
                    lSelectedCredentials = selectedCredentials
                    selectedCredential =
                        selectedCredentials.first
                    state = .selectiveDisclosure
                },
                onCancel: back
            )
        case .selectiveDisclosure:
            DataFieldSelector(
                requestedFields: permissionRequest!.requestedFields(
                    credential: selectedCredential!),
                selectedCredential: selectedCredential!,
                onContinue: { selectedFields in
                    Task {
                        do {
                            permissionResponse = try await permissionRequest?
                                .createPermissionResponse(
                                    selectedCredentials: lSelectedCredentials!,
                                    selectedFields: selectedFields,
                                    responseOptions: ResponseOptions(
                                        shouldStripQuotes: false,
                                        forceArraySerialization: false,
                                        removeVpPathPrefix: false
                                    )
                                )
                            _ = try await holder?.submitPermissionResponse(
                                response: permissionResponse!)
                            let credentialPack = credentialPacks.first(
                                where: {
                                    credentialPack in
                                    return credentialPack.get(
                                        credentialId: selectedCredential!
                                            .asParsedCredential()
                                            .id()) != nil
                                })!
                            let credentialInfo =
                                getCredentialIdTitleAndIssuer(
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
                            ToastManager.shared.showSuccess(
                                message: "Shared successfully")
                            back()
                        } catch {
                            err = OID4VPError(
                                title: "Failed to selective disclose fields",
                                details: error.localizedDescription
                            )
                            state = .err
                        }
                    }
                },
                onCancel: back
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

struct DataFieldSelector: View {
    let requestedFields: [RequestedField]
    let selectedCredential: PresentableCredential
    let onContinue: ([[String]]) -> Void
    let onCancel: () -> Void

    @State private var selectedFields: [String]
    let requiredFields: [String]

    init(
        requestedFields: [RequestedField],
        selectedCredential: PresentableCredential,
        onContinue: @escaping ([[String]]) -> Void,
        onCancel: @escaping () -> Void
    ) {
        self.requestedFields = requestedFields
        self.onContinue = onContinue
        self.onCancel = onCancel
        self.requiredFields =
            requestedFields
            .filter { $0.required() }
            .map { $0.path() }
        self.selectedFields = self.requiredFields
        self.selectedCredential = selectedCredential
    }

    func toggleBinding(for field: RequestedField) -> Binding<Bool> {
        Binding {
            selectedFields.contains(where: { $0 == field.path() })
        } set: { _ in
            // TODO: update when allowing multiple
            if selectedCredential.selectiveDisclosable() && !field.required() {
                if selectedFields.contains(field.path()) {
                    selectedFields.removeAll(where: { $0 == field.path() })
                } else {
                    selectedFields.append(field.path())
                }
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
                ForEach(requestedFields, id: \.self) { field in
                    SelectiveDisclosureItem(
                        field: field,
                        required: field.required(),
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
                    onContinue([selectedFields])
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

struct SelectiveDisclosureItem: View {
    let field: RequestedField
    let required: Bool
    @Binding var isChecked: Bool

    var body: some View {
        HStack {
            Toggle(isOn: $isChecked) {
                Text(field.name()?.capitalized ?? "")
                    .font(.customFont(font: .inter, style: .regular, size: .h4))
                    .foregroundStyle(Color("ColorStone950"))
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .toggleStyle(iOSCheckboxToggleStyle(enabled: !required))
        }
    }
}

struct CredentialSelector: View {
    let credentials: [PresentableCredential]
    let credentialClaims: [String: [String: GenericJSON]]
    let getRequestedFields: (PresentableCredential) -> [RequestedField]
    let onContinue: ([PresentableCredential]) -> Void
    let onCancel: () -> Void
    var allowMultiple: Bool = false

    @State private var selectedCredentials: [PresentableCredential] = []

    func selectCredential(credential: PresentableCredential) {
        if selectedCredentials.contains(where: {
            $0.asParsedCredential().id() == credential.asParsedCredential().id()
        }) {
            selectedCredentials.removeAll(where: {
                $0.asParsedCredential().id()
                    == credential.asParsedCredential().id()
            })
        } else {
            if allowMultiple {
                selectedCredentials.append(credential)
            } else {
                selectedCredentials.removeAll()
                selectedCredentials.append(credential)
            }
        }
    }

    func getCredentialTitle(credential: PresentableCredential) -> String {
        if let name = credentialClaims[credential.asParsedCredential().id()]?[
            "name"]?.toString() {
            return name
        } else if let types = credentialClaims[
            credential.asParsedCredential().id()]?["type"]?
            .arrayValue {
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

    func toggleBinding(for credential: PresentableCredential) -> Binding<Bool> {
        Binding {
            selectedCredentials.contains(where: {
                $0.asParsedCredential().id()
                    == credential.asParsedCredential().id()
            })
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
                    if !selectedCredentials.isEmpty {
                        onContinue(selectedCredentials)
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
                .opacity(selectedCredentials.isEmpty ? 0.6 : 1)
            }
            .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.horizontal, 24)
        .navigationBarBackButtonHidden(true)
    }
}

struct CredentialSelectorItem: View {
    let credential: PresentableCredential
    let requestedFields: [String]
    let getCredentialTitle: (PresentableCredential) -> String
    @Binding var isChecked: Bool

    @State var expanded = false

    init(
        credential: PresentableCredential,
        requestedFields: [RequestedField],
        getCredentialTitle: @escaping (PresentableCredential) -> String,
        isChecked: Binding<Bool>
    ) {
        self.credential = credential
        self.requestedFields = requestedFields.map { field in
            (field.name() ?? "").capitalized
        }
        self.getCredentialTitle = getCredentialTitle
        self._isChecked = isChecked
    }

    var body: some View {
        VStack {
            HStack {
                Toggle(isOn: $isChecked) {
                    Text(getCredentialTitle(credential))
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
