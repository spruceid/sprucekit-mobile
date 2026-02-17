import SpruceIDMobileSdk
import SpruceIDMobileSdkRs
import SwiftUI

struct HandleOID4VP: Hashable {
    var url: String
    var credentialPackId: String?
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
            self._jwk = jwk!.description
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
    var credentialPackId: String?
    var url: String

    @State private var holder: Holder?
    @State private var permissionRequest: PermissionRequest?
    @State private var permissionResponse: PermissionResponse?
    @State private var lSelectedCredentials: [PresentableCredential]?
    @State private var credentialClaims: [String: [String: GenericJSON]] = [:]
    @State private var credentialPacks: [CredentialPack] = []

    // Track selective disclosure progress for multiple credentials
    @State private var currentDisclosureIndex: Int = 0
    @State private var allSelectedFields: [[String]] = []

    @State private var err: OID4VPError?
    @State private var state = OID4VPState.none

    func presentCredential() async {
        do {
            if let id = credentialPackId,
               let pack = credentialPackObservable.getById(credentialPackId: id) {
                credentialPacks = [pack]
            } else {
                credentialPacks = credentialPackObservable.credentialPacks
            }
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
                contextMap: getVCPlaygroundOID4VCIContext(),
                keystore: KeyManager()
            )
            let newurl = url.replacing("authorize", with: "")
            let tmpPermissionRequest = try await holder!.authorizationRequest(
                req: Url(newurl))
            let permissionRequestCredentials =
                tmpPermissionRequest.credentials()

            permissionRequest = tmpPermissionRequest
            let requirements = tmpPermissionRequest.credentialRequirements()

            if !permissionRequestCredentials.isEmpty {
                // Check if we can skip credential selection:
                // Only skip if there's exactly one requirement with exactly one credential
                let canSkipSelection =
                    requirements.count == 1 && requirements.first?.credentials.count == 1

                if canSkipSelection {
                    lSelectedCredentials = permissionRequestCredentials
                    // Initialize disclosure tracking for single credential
                    currentDisclosureIndex = 0
                    allSelectedFields = []
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

    func submitResponse() async {
        do {
            permissionResponse = try await permissionRequest?
                .createPermissionResponse(
                    selectedCredentials: lSelectedCredentials!,
                    selectedFields: allSelectedFields,
                    responseOptions: ResponseOptions(
                        forceArraySerialization: false
                    )
                )
            _ = try await holder?.submitPermissionResponse(
                response: permissionResponse!)

            // Log activity for each credential
            for credential in lSelectedCredentials! {
                if let credentialPack = credentialPacks.first(where: { pack in
                    pack.get(credentialId: credential.asParsedCredential().id()) != nil
                }) {
                    let credentialInfo = getCredentialIdTitleAndIssuer(
                        credentialPack: credentialPack)
                    _ = WalletActivityLogDataStore.shared.insert(
                        credentialPackId: credentialPack.id.uuidString,
                        credentialId: credentialInfo.0,
                        credentialTitle: credentialInfo.1,
                        issuer: credentialInfo.2,
                        action: "Verification",
                        dateTime: Date(),
                        additionalInformation: ""
                    )
                }
            }

            ToastManager.shared.showSuccess(message: "Shared successfully")
            back()
        } catch {
            err = OID4VPError(
                title: "Failed to submit presentation",
                details: error.localizedDescription
            )
            state = .err
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
                requirements: permissionRequest!.credentialRequirements(),
                credentialClaims: credentialClaims,
                getRequestedFields: { credential in
                    return permissionRequest!.requestedFields(
                        credential: credential)
                },
                onContinue: { selectedCredentials in
                    lSelectedCredentials = selectedCredentials
                    // Reset disclosure tracking for multi-credential flow
                    currentDisclosureIndex = 0
                    allSelectedFields = []
                    state = .selectiveDisclosure
                },
                onCancel: back
            )
        case .selectiveDisclosure:
            let currentCredential = lSelectedCredentials![currentDisclosureIndex]
            let totalCredentials = lSelectedCredentials!.count

            // Get ALL claims for this credential
            let currentCredentialPack = credentialPacks.first { pack in
                pack.get(credentialId: currentCredential.asParsedCredential().id()) != nil
            }
            let allClaimsForCredential = currentCredentialPack?.getCredentialClaims(
                credential: currentCredential.asParsedCredential(),
                claimNames: []
            ) ?? [:]

            DataFieldSelector(
                requestedFields: permissionRequest!.requestedFields(
                    credential: currentCredential),
                selectedCredential: currentCredential,
                currentIndex: currentDisclosureIndex,
                totalCount: totalCredentials,
                onContinue: { selectedFields in
                    // Append the selected fields for this credential
                    allSelectedFields.append(selectedFields)

                    // Check if there are more credentials to process
                    if currentDisclosureIndex + 1 < totalCredentials {
                        // Move to next credential
                        currentDisclosureIndex += 1
                        // Force view refresh by toggling state
                        state = .loading
                        DispatchQueue.main.async {
                            state = .selectiveDisclosure
                        }
                    } else {
                        // All credentials processed, submit response
                        Task {
                            await submitResponse()
                        }
                    }
                },
                onCancel: back,
                allClaims: allClaimsForCredential
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
    let currentIndex: Int
    let totalCount: Int
    let onContinue: ([String]) -> Void
    let onCancel: () -> Void
    let allClaims: [String: GenericJSON]

    @State private var selectedFields: [String]
    let requiredFields: [String]

    init(
        requestedFields: [RequestedField],
        selectedCredential: PresentableCredential,
        currentIndex: Int = 0,
        totalCount: Int = 1,
        onContinue: @escaping ([String]) -> Void,
        onCancel: @escaping () -> Void,
        allClaims: [String: GenericJSON] = [:]
    ) {
        self.requestedFields = requestedFields
        self.selectedCredential = selectedCredential
        self.currentIndex = currentIndex
        self.totalCount = totalCount
        self.onContinue = onContinue
        self.onCancel = onCancel
        self.allClaims = allClaims
        self.requiredFields =
            requestedFields
            .filter { $0.required() }
            .map { $0.path() }
        self.selectedFields = self.requiredFields
    }

    func toggleBinding(for field: RequestedField) -> Binding<Bool> {
        Binding {
            selectedFields.contains(where: { $0 == field.path() })
        } set: { _ in
            if selectedCredential.selectiveDisclosable() && !field.required() {
                if selectedFields.contains(field.path()) {
                    selectedFields.removeAll(where: { $0 == field.path() })
                } else {
                    selectedFields.append(field.path())
                }
            }
        }
    }

    var hasMoreCredentials: Bool {
        currentIndex + 1 < totalCount
    }

    var body: some View {
        VStack {
            // Progress indicator for multi-credential flow
            if totalCount > 1 {
                HStack {
                    Text("Credential \(currentIndex + 1) of \(totalCount)")
                        .font(.customFont(font: .inter, style: .medium, size: .p))
                        .foregroundStyle(Color("ColorStone500"))
                    Spacer()
                }
                .padding(.bottom, 8)
            }

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
                if requestedFields.isEmpty && !selectedCredential.selectiveDisclosable() {
                    // No specific fields requested, show all claims from the credential
                    ForEach(Array(allClaims.keys.sorted()), id: \.self) { claimName in
                        SelectiveDisclosureItem(
                            fieldName: claimName,
                            required: true,
                            isChecked: .constant(true)
                        )
                    }
                } else {
                    ForEach(requestedFields, id: \.self) { field in
                        SelectiveDisclosureItem(
                            field: field,
                            required: field.required() || !selectedCredential.selectiveDisclosable(),
                            isChecked: toggleBinding(for: field)
                        )
                    }
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
                    onContinue(selectedFields)
                } label: {
                    Text(hasMoreCredentials ? "Next" : "Approve")
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
    let field: RequestedField?
    let fieldName: String?
    let required: Bool
    @Binding var isChecked: Bool

    init(field: RequestedField, required: Bool, isChecked: Binding<Bool>) {
        self.field = field
        self.fieldName = nil
        self.required = required
        self._isChecked = isChecked
    }

    init(fieldName: String, required: Bool, isChecked: Binding<Bool>) {
        self.field = nil
        self.fieldName = fieldName
        self.required = required
        self._isChecked = isChecked
    }

    private var displayName: String {
        if let field = field {
            return field.name()?.camelCaseToWords().capitalized.replaceUnderscores() ?? ""
        } else if let fieldName = fieldName {
            return fieldName.camelCaseToWords().capitalized.replaceUnderscores()
        }
        return ""
    }

    var body: some View {
        HStack {
            Toggle(isOn: $isChecked) {
                Text(displayName)
                    .font(.customFont(font: .inter, style: .regular, size: .h4))
                    .foregroundStyle(Color("ColorStone950"))
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .toggleStyle(iOSCheckboxToggleStyle(enabled: !required))
        }
    }
}

struct CredentialSelector: View {
    let requirements: [CredentialRequirement]
    let credentialClaims: [String: [String: GenericJSON]]
    let getRequestedFields: (PresentableCredential) -> [RequestedField]
    let onContinue: ([PresentableCredential]) -> Void
    let onCancel: () -> Void

    // Track current requirement index (step-by-step flow)
    @State private var currentIndex: Int = 0
    // Track selected credential per requirement (by index)
    @State private var selectedByRequirement: [Int: PresentableCredential] = [:]

    var currentRequirement: CredentialRequirement {
        requirements[currentIndex]
    }

    var hasMoreRequirements: Bool {
        currentIndex + 1 < requirements.count
    }

    var currentSelectionValid: Bool {
        !currentRequirement.required || selectedByRequirement[currentIndex] != nil
    }

    func selectCredential(credential: PresentableCredential) {
        let credId = credential.asParsedCredential().id()
        if let current = selectedByRequirement[currentIndex],
           current.asParsedCredential().id() == credId
        {
            // Deselect if tapping the same credential
            selectedByRequirement.removeValue(forKey: currentIndex)
        } else {
            // Select this credential for this requirement
            selectedByRequirement[currentIndex] = credential
        }
    }

    func getCredentialTitle(credential: PresentableCredential) -> String {
        if let name = credentialClaims[credential.asParsedCredential().id()]?[
            "name"]?.toString()
        {
            return name
        } else if let types = credentialClaims[
            credential.asParsedCredential().id()]?["type"]?
            .arrayValue
        {
            var title = ""
            types.forEach {
                if $0.toString() != "VerifiableCredential" {
                    title = $0.toString().camelCaseToWords()
                    return
                }
            }
            return title
        } else if let mdoc = credential.asParsedCredential().asMsoMdoc() {
            return mdocDisplayName(for: mdoc.doctype())
        } else {
            return ""
        }
    }

    func isSelected(credential: PresentableCredential) -> Bool {
        guard let selected = selectedByRequirement[currentIndex] else { return false }
        return selected.asParsedCredential().id() == credential.asParsedCredential().id()
    }

    func toggleBinding(for credential: PresentableCredential) -> Binding<Bool> {
        Binding {
            isSelected(credential: credential)
        } set: { _ in
            selectCredential(credential: credential)
        }
    }

    /// Get selected credentials in the order of the requirements
    func getSelectedCredentials() -> [PresentableCredential] {
        requirements.indices.compactMap { index in
            selectedByRequirement[index]
        }
    }

    func goToNextOrFinish() {
        if hasMoreRequirements {
            currentIndex += 1
        } else {
            onContinue(getSelectedCredentials())
        }
    }

    var body: some View {
        VStack {
            // Progress indicator
            if requirements.count > 1 {
                HStack {
                    Text("Requirement \(currentIndex + 1) of \(requirements.count)")
                        .font(.customFont(font: .inter, style: .medium, size: .p))
                        .foregroundStyle(Color("ColorStone500"))
                    Spacer()
                }
                .padding(.bottom, 8)
            }

            // Header with requirement name
            VStack(spacing: 4) {
                Text("Select a credential for")
                    .font(.customFont(font: .inter, style: .regular, size: .h3))
                    .foregroundStyle(Color("ColorStone700"))

                HStack {
                    Text(currentRequirement.displayName)
                        .font(.customFont(font: .inter, style: .bold, size: .h2))
                        .foregroundStyle(Color("ColorBlue600"))

                    if !currentRequirement.required {
                        Text("(Optional)")
                            .font(.customFont(font: .inter, style: .regular, size: .p))
                            .foregroundStyle(Color("ColorStone400"))
                    }
                }
            }
            .multilineTextAlignment(.center)
            .padding(.bottom, 8)

            ScrollView {
                ForEach(
                    Array(currentRequirement.credentials.enumerated()), id: \.offset
                ) { _, credential in
                    CredentialSelectorItem(
                        credential: credential,
                        requestedFields: getRequestedFields(credential),
                        getCredentialTitle: { cred in
                            getCredentialTitle(credential: cred)
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
                    if currentSelectionValid {
                        goToNextOrFinish()
                    }
                } label: {
                    Text(hasMoreRequirements ? "Next" : "Continue")
                        .frame(maxWidth: .infinity)
                        .font(
                            .customFont(font: .inter, style: .medium, size: .h4)
                        )
                }
                .foregroundColor(.white)
                .padding(.vertical, 13)
                .background(Color("ColorStone600"))
                .clipShape(RoundedRectangle(cornerRadius: 8))
                .opacity(currentSelectionValid ? 1 : 0.6)
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
