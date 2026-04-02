import SpruceIDMobileSdk
import SpruceIDMobileSdkRs
import SwiftUI

class Draft18Signer: Draft18PresentationSigner {
    private let keyId: String
    private let jwkString: String
    private let didJwk = DidMethodUtils(method: DidMethod.jwk)

    init(keyId: String?) throws {
        self.keyId = keyId ?? DEFAULT_SIGNING_KEY_ID

        if !KeyManager.keyExists(id: self.keyId) {
            _ = KeyManager.generateSigningKey(id: self.keyId)
        }

        guard let jwk = KeyManager.getJwk(id: self.keyId) else {
            throw Oid4vpSignerError.illegalArgumentException(reason: "Invalid kid")
        }

        jwkString = jwk.description
    }

    func sign(payload: Data) async throws -> Data {
        guard let signature = KeyManager.signPayload(id: keyId, payload: [UInt8](payload)) else {
            throw Oid4vpSignerError.illegalArgumentException(reason: "Failed to sign payload")
        }

        return Data(signature)
    }

    func algorithm() -> String {
        let json = getGenericJSON(jsonString: jwkString)
        return json?.dictValue?["alg"]?.toString() ?? "ES256"
    }

    func verificationMethod() async -> String {
        try! await didJwk.vmFromJwk(jwk: jwkString)
    }

    func did() -> String {
        try! didJwk.didFromJwk(jwk: jwkString)
    }

    func cryptosuite() -> String {
        "ecdsa-rdfc-2019"
    }

    func jwk() -> String {
        jwkString
    }
}

struct Draft18CredentialRequirement {
    let descriptorId: String
    let displayName: String
    let credentials: [Draft18PresentableCredential]
}

struct Draft18DataFieldSelector: View {
    let requestedFields: [Draft18RequestedField]
    let selectedCredential: Draft18PresentableCredential
    let currentIndex: Int
    let totalCount: Int
    let onContinue: ([String]) -> Void
    let onCancel: () -> Void
    let allClaims: [String: GenericJSON]

    @State private var selectedFields: [String]
    let requiredFields: [String]
    let supportsSelectiveDisclosure: Bool

    init(
        requestedFields: [Draft18RequestedField],
        selectedCredential: Draft18PresentableCredential,
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
        supportsSelectiveDisclosure = selectedCredential.selectiveDisclosable()
        requiredFields = requestedFields.filter { $0.required() }.map { $0.path() }
        _selectedFields = State(initialValue: requiredFields)
    }

    func toggleBinding(for field: Draft18RequestedField) -> Binding<Bool> {
        Binding {
            selectedFields.contains(field.path())
        } set: { _ in
            if supportsSelectiveDisclosure && !field.required() {
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
                if requestedFields.isEmpty && !supportsSelectiveDisclosure {
                    ForEach(Array(allClaims.keys.sorted()), id: \.self) { claimName in
                        SelectiveDisclosureItem(
                            fieldName: claimName,
                            required: true,
                            isChecked: .constant(true)
                        )
                    }
                } else {
                    ForEach(Array(requestedFields.enumerated()), id: \.offset) { _, field in
                        Draft18SelectiveDisclosureItem(
                            field: field,
                            required: field.required() || !supportsSelectiveDisclosure,
                            isChecked: toggleBinding(for: field)
                        )
                    }
                }
            }

            HStack {
                Button(action: onCancel) {
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
                    onContinue(selectedFields)
                } label: {
                    Text(hasMoreCredentials ? "Next" : "Approve")
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

struct Draft18SelectiveDisclosureItem: View {
    let field: Draft18RequestedField
    let required: Bool
    @Binding var isChecked: Bool

    private var displayName: String {
        field.name()?.camelCaseToWords().capitalized.replaceUnderscores()
            ?? field.inputDescriptorId().camelCaseToWords().capitalized.replaceUnderscores()
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

struct Draft18CredentialSelector: View {
    let requirements: [Draft18CredentialRequirement]
    let credentialClaims: [String: [String: GenericJSON]]
    let getRequestedFields: (Draft18PresentableCredential) -> [Draft18RequestedField]
    let onContinue: ([Draft18PresentableCredential]) -> Void
    let onCancel: () -> Void

    @State private var currentIndex: Int = 0
    @State private var selectedByRequirement: [Int: Draft18PresentableCredential] = [:]

    var currentRequirement: Draft18CredentialRequirement {
        requirements[currentIndex]
    }

    var hasMoreRequirements: Bool {
        currentIndex + 1 < requirements.count
    }

    func selectCredential(_ credential: Draft18PresentableCredential) {
        let credentialId = credential.asParsedCredential().id()
        if let current = selectedByRequirement[currentIndex],
           current.asParsedCredential().id() == credentialId
        {
            selectedByRequirement.removeValue(forKey: currentIndex)
        } else {
            selectedByRequirement[currentIndex] = credential
        }
    }

    func isSelected(_ credential: Draft18PresentableCredential) -> Bool {
        guard let selected = selectedByRequirement[currentIndex] else {
            return false
        }

        return selected.asParsedCredential().id() == credential.asParsedCredential().id()
    }

    func toggleBinding(for credential: Draft18PresentableCredential) -> Binding<Bool> {
        Binding {
            isSelected(credential)
        } set: { _ in
            selectCredential(credential)
        }
    }

    func getSelectedCredentials() -> [Draft18PresentableCredential] {
        requirements.indices.compactMap { selectedByRequirement[$0] }
    }

    func getCredentialTitle(_ credential: Draft18PresentableCredential) -> String {
        let parsedCredential = credential.asParsedCredential()
        if let name = credentialClaims[parsedCredential.id()]?["name"]?.toString() {
            return name
        }

        if let types = credentialClaims[parsedCredential.id()]?["type"]?.arrayValue {
            for type in types where type.toString() != "VerifiableCredential" {
                return type.toString().camelCaseToWords()
            }
        }

        if let mdoc = parsedCredential.asMsoMdoc() {
            return credentialTypeDisplayName(for: mdoc.doctype())
        }

        if let dcSdJwt = parsedCredential.asDcSdJwt() {
            return credentialTypeDisplayName(for: dcSdJwt.vct())
        }

        return currentRequirement.displayName
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
            if requirements.count > 1 {
                HStack {
                    Text("Requirement \(currentIndex + 1) of \(requirements.count)")
                        .font(.customFont(font: .inter, style: .medium, size: .p))
                        .foregroundStyle(Color("ColorStone500"))
                    Spacer()
                }
                .padding(.bottom, 8)
            }

            VStack(spacing: 4) {
                Text("Select a credential for")
                    .font(.customFont(font: .inter, style: .regular, size: .h3))
                    .foregroundStyle(Color("ColorStone700"))

                Text(currentRequirement.displayName)
                    .font(.customFont(font: .inter, style: .bold, size: .h2))
                    .foregroundStyle(Color("ColorBlue600"))
            }
            .multilineTextAlignment(.center)
            .padding(.bottom, 8)

            ScrollView {
                ForEach(Array(currentRequirement.credentials.enumerated()), id: \.offset) { _, credential in
                    Draft18CredentialSelectorItem(
                        credential: credential,
                        requestedFields: getRequestedFields(credential),
                        getCredentialTitle: getCredentialTitle,
                        isChecked: toggleBinding(for: credential)
                    )
                }
            }

            HStack {
                Button(action: onCancel) {
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
                    goToNextOrFinish()
                } label: {
                    Text(hasMoreRequirements ? "Next" : "Continue")
                        .frame(maxWidth: .infinity)
                        .font(.customFont(font: .inter, style: .medium, size: .h4))
                }
                .foregroundColor(.white)
                .padding(.vertical, 13)
                .background(Color("ColorStone600"))
                .clipShape(RoundedRectangle(cornerRadius: 8))
            }
            .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.horizontal, 24)
        .navigationBarBackButtonHidden(true)
    }
}

struct Draft18CredentialSelectorItem: View {
    let credential: Draft18PresentableCredential
    let requestedFields: [String]
    let getCredentialTitle: (Draft18PresentableCredential) -> String
    @Binding var isChecked: Bool

    @State private var expanded = false

    init(
        credential: Draft18PresentableCredential,
        requestedFields: [Draft18RequestedField],
        getCredentialTitle: @escaping (Draft18PresentableCredential) -> String,
        isChecked: Binding<Bool>
    ) {
        self.credential = credential
        self.requestedFields = requestedFields.map {
            ($0.name() ?? $0.inputDescriptorId()).capitalized
        }
        self.getCredentialTitle = getCredentialTitle
        _isChecked = isChecked
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
                        .onTapGesture { expanded = false }
                } else {
                    Image("Expand")
                        .onTapGesture { expanded = true }
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
