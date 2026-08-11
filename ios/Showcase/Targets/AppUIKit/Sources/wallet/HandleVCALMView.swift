import SpruceIDMobileSdk
import SpruceIDMobileSdkRs
import SwiftUI

struct HandleVCALM: Hashable {
    var url: String
}

enum VcalmSignerError: Error {
    case illegalArgumentException(reason: String)
}

// Used to surface when underlying offer acceptance (holder.acceptOffer) fails
enum VcalmOfferAcceptanceError: Error {
    case failed
}

final class VCALMSigner: PresentationSigner {
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
            throw VcalmSignerError.illegalArgumentException(
                reason: "Invalid kid"
            )
        } else {
            self._jwk = jwk!.description
        }
    }

    func sign(payload: Data) async throws -> Data {
        let signature = KeyManager.signPayload(
            id: keyId,
            payload: [UInt8](payload)
        )
        if signature == nil {
            throw VcalmSignerError.illegalArgumentException(
                reason: "Failed to sign payload"
            )
        } else {
            return Data(signature!)
        }
    }

    func algorithm() -> String {
        // Parse the jwk as a JSON object and return the "alg" field
        let json = getGenericJSON(jsonString: _jwk)
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

class VcalmDisplayError {
    let title: String
    let details: String

    init(title: String, details: String) {
        self.title = title
        self.details = details
    }
}

struct VcalmDomainMismatch {
    let domain: String
    let channel: String
}

struct VcalmRequirement {
    let queryIndex: UInt32
    let label: String
    let candidates: [ParsedCredential]
    let fields: [VcalmRequestedField]
}

/// Groups requested fields and matched credentials by query index into a list
/// of requirements. Iterates the union of both so a requested query with no
/// matching credential still becomes a requirement with empty candidates,
/// instead of being silently dropped.
func buildVcalmRequirements(
    requestedFields: [VcalmRequestedField],
    matched: [VcalmMatchedCredentials]
) -> [VcalmRequirement] {
    // The QBE example's structural keys carry no user data and don't map to
    // a credential claim, so they're excluded from the requirement's fields.
    var fieldsByQuery: [UInt32: [VcalmRequestedField]] = [:]
    for field in requestedFields
    where field.path != "type" && field.path != "@context" {
        fieldsByQuery[field.queryIndex, default: []].append(field)
    }

    var candidatesByQuery: [UInt32: [ParsedCredential]] = [:]
    for match in matched {
        candidatesByQuery[match.queryIndex] = match.credentials.map {
            $0.credential
        }
    }

    var typesByQuery: [UInt32: String] = [:]
    for field in requestedFields where field.path == "type" {
        typesByQuery[field.queryIndex] = field.value
    }

    let queryIndices = Set(fieldsByQuery.keys).union(candidatesByQuery.keys)
        .sorted()

    return queryIndices.map { queryIndex in
        let fields = fieldsByQuery[queryIndex] ?? []
        let purposeLabel = fields.compactMap { $0.purpose }.first {
            !$0.isEmpty
        }
        let typeLabel = typesByQuery[queryIndex].flatMap {
            vcalmRequirementLabel(fromType: $0)
        }
        return VcalmRequirement(
            queryIndex: queryIndex,
            label: typeLabel ?? purposeLabel ?? "Credential",
            candidates: candidatesByQuery[queryIndex] ?? [],
            fields: fields
        )
    }
}

// Turn a `type` field's raw value into a short display label,
// skipping the generic "VerifiableCredential" entry
func vcalmRequirementLabel(fromType rawValue: String) -> String? {
    let trimmed = rawValue.trimmingCharacters(in: .whitespacesAndNewlines)
    var entries = [trimmed]
    if trimmed.hasPrefix("["), trimmed.hasSuffix("]"),
        let data = trimmed.data(using: .utf8),
        let array = try? JSONDecoder().decode([String].self, from: data)
    {
        entries = array
    }
    guard
        let match = entries.first(where: {
            !$0.isEmpty && $0 != "VerifiableCredential"
        })
    else {
        return nil
    }
    return match.camelCaseToWords()
}

func vcalmCredentialTitle(
    _ parsedCredential: ParsedCredential,
    credentialClaims: [String: [String: GenericJSON]] = [:]
) -> String {
    if let name = credentialClaims[parsedCredential.id()]?["name"]?.toString(),
        !name.isEmpty
    {
        return name
    }
    if let types = credentialClaims[parsedCredential.id()]?["type"]?.arrayValue
    {
        var title = ""
        types.forEach {
            if title.isEmpty && $0.toString() != "VerifiableCredential" {
                title = $0.toString().camelCaseToWords()
            }
        }
        if !title.isEmpty {
            return title
        }
    }
    if let mdoc = parsedCredential.asMsoMdoc() {
        return credentialTypeDisplayName(for: mdoc.doctype())
    } else if let dcSdJwt = parsedCredential.asDcSdJwt() {
        return credentialTypeDisplayName(for: dcSdJwt.vct())
    }
    return "Credential"
}

// Returns formatted issue date for a credential card, pulled from `validFrom` (VC 2.0),
// `issuanceDate` VC 1.1 (), or `null` if neither claim is available.
func vcalmCredentialIssuedDate(
    _ parsedCredential: ParsedCredential,
    credentialClaims: [String: [String: GenericJSON]]
) -> String? {
    guard let claims = credentialClaims[parsedCredential.id()] else {
        return nil
    }
    var raw = claims["validFrom"]?.toString() ?? ""
    if raw.isEmpty {
        raw = claims["issuanceDate"]?.toString() ?? ""
    }
    guard !raw.isEmpty else { return nil }
    if let tIndex = raw.firstIndex(of: "T") {
        return String(raw[..<tIndex])
    }
    return raw
}

/// Normalizes the exchange endpoint into the bare `https` vcapi exchange URL
/// the holder POSTs to.
/// 1. CHAPI deep link — the web switchboard wraps the endpoint in the app's
///    `interaction:` scheme, percent-encoded (`interaction:<enc-https-url>`).
///    => Strip the scheme and decode.
/// 2. Playground landing URL — vcplayground.org serves the endpoint inside an
///    HTML page at `https://…/interactions/<url-encoded-exchange-url>?iuv=1`;
///    POSTing to it returns HTML, not vcapi JSON.
///    => Extract the percent-encoded inner URL.
func unwrap(_ originalUrl: String) -> String {
    var url = originalUrl
    let scheme = "interaction:"
    let marker = "/interactions/"

    if url.hasPrefix(scheme) {
        var remainder = String(url.dropFirst(scheme.count))
        while remainder.hasPrefix("/") {
            remainder.removeFirst()
        }
        if let decoded = remainder.removingPercentEncoding,
            decoded.hasPrefix("http://") || decoded.hasPrefix("https://")
        {
            url = decoded
        }
    }

    guard let markerRange = url.range(of: marker) else {
        return url
    }
    var encoded = String(url[markerRange.upperBound...])
    if let questionMark = encoded.firstIndex(of: "?") {
        encoded = String(encoded[..<questionMark])
    }
    if let decoded = encoded.removingPercentEncoding,
        decoded.hasPrefix("http://") || decoded.hasPrefix("https://")
    {
        return decoded
    }
    return url
}

struct HandleVCALMView: View {
    @EnvironmentObject private var credentialPackObservable:
        CredentialPackObservable
    @Binding var path: NavigationPath
    let url: String

    @State private var err: VcalmDisplayError?
    @State private var loading: Bool = false
    @State private var holder: VcalmHolder?

    @State private var requirements: [VcalmRequirement]?
    @State private var readyForFieldSelection: Bool = false
    @State private var picks: [UInt32: ParsedCredential] = [:]
    @State private var redirectUrl: String?
    @State private var credentialClaims: [String: [String: GenericJSON]] = [:]
    @State private var pendingWalletCredentials: [String]?
    @State private var offerAcceptResult: StepResult?
    @State private var offerAcceptError: Bool = false
    @State private var domainMismatch: VcalmDomainMismatch?
    @State private var pendingSelection: [ParsedCredential] = []

    func back() {
        while !path.isEmpty {
            path.removeLast()
        }
    }

    private var domainMismatchPresented: Binding<Bool> {
        Binding(
            get: { domainMismatch != nil },
            set: { isPresented in
                if !isPresented { cancelDomainMismatch() }
            }
        )
    }

    func cancelDomainMismatch() {
        domainMismatch = nil
        err = VcalmDisplayError(
            title: "Presentation flow canceled",
            details:
                "The selected credentials were not presented due to user cancellation."
        )
    }

    // Submit presentation after automatically/manually selecting credentials to fit requirements
    func trySubmitPresentation(
        selected: [ParsedCredential],
        allowDomainMismatch: Bool
    ) async {
        guard let holder else { return }
        do {
            let result = try await holder.submitPresentation(
                selectedCredentials: selected,
                allowDomainMismatch: allowDomainMismatch
            )
            requirements = nil
            domainMismatch = nil
            await handleStep(result)
        } catch VcalmError.DomainChannelMismatch(let domain, let channel) {
            pendingSelection = selected
            domainMismatch = VcalmDomainMismatch(
                domain: domain,
                channel: channel
            )
        } catch {
            err = VcalmDisplayError(
                title: "Error Submitting Presentation",
                details: "Couldn't submit presentation. Error: \(error)"
            )
        }
    }

    func onRequest(_ vpr: Vpr) async {
        guard let holder else { return }
        do {
            let matched = try await holder.matchedCredentials()
            let requestedFields = try await holder.requestedFields()

            if requestedFields.isEmpty {
                // No fields requested — this is a DID-authentication-only request
                // Can submit immediately
                await trySubmitPresentation(
                    selected: [],
                    allowDomainMismatch: false
                )
                return
            }

            let built = buildVcalmRequirements(
                requestedFields: requestedFields,
                matched: matched
            )
            if built.isEmpty || built.allSatisfy({ $0.candidates.isEmpty }) {
                err = VcalmDisplayError(
                    title: "No matching credential(s)",
                    details:
                        "You don't have a credential in your wallet that satisfies this verifier's request."
                )
                return
            }

            // When there is only one candidate choice for a requirement, auto select it
            var autoPicks: [UInt32: ParsedCredential] = [:]
            for requirement in built where requirement.candidates.count == 1 {
                autoPicks[requirement.queryIndex] = requirement.candidates.first
            }
            picks = autoPicks
            requirements = built
            // If no requirement has more than one candidate, skip straight to selective disclosure
            readyForFieldSelection = built.allSatisfy {
                $0.candidates.count <= 1
            }
        } catch {
            err = VcalmDisplayError(
                title: "Error Handling Verifier Request",
                details:
                    "Couldn't process the verifier's request. Error: \(error)"
            )
        }
    }

    func handleStep(_ result: StepResult) async {
        switch result {
        case .request(let vpr):
            await onRequest(vpr)
        case .offer:
            do {
                let offered = try await holder?.offeredCredentials() ?? []
                // The offer itself isn't accepted at the protocol
                // level until the user taps "Add to Wallet" in AddToWalletView
                offerAcceptResult = nil
                offerAcceptError = false
                pendingWalletCredentials = offered.map { $0.rawCredential }
            } catch {
                err = VcalmDisplayError(
                    title: "Error Loading Offer",
                    details:
                        "Couldn't load the offered credential(s). Error: \(error)"
                )
            }
        case .redirect(let redirectUrl):
            self.redirectUrl = redirectUrl
        case .complete:
            ToastManager.shared.showSuccess(message: "Shared successfully")
            back()
        case .problem(let details):
            err = VcalmDisplayError(
                title: "Verifier reported a problem",
                details: details.title ?? details.detail ?? details.problemType
            )
        }
    }

    func submitPicks() async {
        let selected = (requirements ?? []).compactMap { picks[$0.queryIndex] }
        loading = true
        await trySubmitPresentation(
            selected: selected,
            allowDomainMismatch: false
        )
        loading = false
    }

    func acceptOffer() async {
        guard offerAcceptResult == nil, !offerAcceptError else { return }
        do {
            let result = try await holder!.acceptOffer()
            offerAcceptResult = result
            if case .problem = result {
                // Surface the error, don't let AddToWalletView store anything
                offerAcceptError = true
                await handleStep(result)
            }
        } catch {
            offerAcceptError = true
            err = VcalmDisplayError(
                title: "Error Accepting Offer",
                details:
                    "Couldn't accept the offered credential(s). Error: \(error)"
            )
        }
    }

    func declineOffer() async {
        loading = true
        do {
            let result = try await holder!.rejectOffer()
            await handleStep(result)
        } catch {
            // Servers may throw 4xx on further POSTs once offer is delivered as terminal step
            // Treat this as exchange ended, and navigate to home screen
            ToastManager.shared.showSuccess(message: "Offer declined")
            back()
        }
        loading = false
    }

    func startExchange() async {
        loading = true
        do {
            let vdcCollection = VdcCollection(
                engine: credentialPackObservable.storageManager
            )
            let signer = try VCALMSigner(keyId: "vcalm_holder_key")

            let holder = try await VcalmHolder.newSession(
                vdcCollection: vdcCollection,
                trustedDids: trustedDids,
                signer: signer,
                contextMap: nil,
                keystore: nil,
            )
            self.holder = holder

            let credentials = credentialPackObservable.credentialPacks.flatMap {
                pack in
                pack.list()
            }
            credentialPackObservable.credentialPacks.forEach { pack in
                credentialClaims = credentialClaims.merging(
                    pack.findCredentialClaims(claimNames: [
                        "name", "type", "validFrom", "issuanceDate",
                    ])
                ) { (_, new) in new }
            }
            await holder.provideCredentials(credentials: credentials)

            let result = try await holder.startExchange(
                input: unwrap(url),
                authHeader: nil
            )
            await handleStep(result)
        } catch {
            err = VcalmDisplayError(
                title: "Error Adding Credential",
                details: "Couldn't complete exchange \(url). Error: \(error)"
            )
        }
        loading = false
    }

    var body: some View {
        ZStack {
            if loading {
                LoadingView(loadingText: "Loading...")
            } else if let err {
                ErrorView(
                    errorTitle: err.title,
                    errorDetails: err.details,
                    onClose: back
                )
            } else if let pendingWalletCredentials {
                AddToWalletView(
                    path: $path,
                    rawCredentials: pendingWalletCredentials,
                    onSuccess: {
                        self.pendingWalletCredentials = nil
                        guard let result = offerAcceptResult else {
                            Task { await declineOffer() }
                            return
                        }
                        switch result {
                        case .complete:
                            back()
                        case .problem:
                            // Already surfaced inside acceptOffer().
                            break
                        default:
                            // Chained — the exchange isn't done yet
                            Task { await handleStep(result) }
                        }
                    },
                    navigateHomeOnSuccess: false,
                    onAcceptCredential: { raw in
                        // Accept the offer at the protocol level idempotently before
                        // storing this one locally.
                        await acceptOffer()
                        if offerAcceptError {
                            throw VcalmOfferAcceptanceError.failed
                        }
                        _ = try await acceptRawCredentialIntoWallet(
                            rawCredential: raw,
                            credentialPackObservable: credentialPackObservable
                        )
                    }
                )
            } else if let requirements, !readyForFieldSelection {
                VcalmCredentialSelector(
                    requirements: requirements,
                    picks: picks,
                    credentialClaims: credentialClaims,
                    onPick: { queryIndex, credential in
                        picks[queryIndex] = credential
                    },
                    onContinue: {
                        readyForFieldSelection = true
                    },
                    onCancel: back
                )
            } else if let requirements {
                VcalmFieldsSelector(
                    requirements: requirements,
                    picks: picks,
                    credentialClaims: credentialClaims,
                    onSubmit: {
                        Task {
                            await submitPicks()
                        }
                    },
                    onCancel: back
                )
            } else {
                LoadingView(loadingText: "Loading...")
            }
        }
        .navigationBarBackButtonHidden(true)
        .task {
            await startExchange()
        }
        .onChange(of: redirectUrl) { newValue in
            // Opening the redirect happens once per URL.
            guard let target = newValue, let targetUrl = URL(string: target)
            else { return }
            UIApplication.shared.open(targetUrl)
            ToastManager.shared.showSuccess(
                message: "Continue in your browser to finish this exchange."
            )
            back()
        }
        .sheet(isPresented: domainMismatchPresented) {
            if let domainMismatch {
                VcalmDomainMismatchSheet(
                    domain: domainMismatch.domain,
                    channel: domainMismatch.channel,
                    onCancel: { cancelDomainMismatch() },
                    onContinueAnyway: {
                        let selection = pendingSelection
                        self.domainMismatch = nil
                        Task {
                            loading = true
                            await trySubmitPresentation(
                                selected: selection,
                                allowDomainMismatch: true
                            )
                            loading = false
                        }
                    }
                )
            }
        }
    }
}

struct VcalmCredentialSelector: View {
    let requirements: [VcalmRequirement]
    let picks: [UInt32: ParsedCredential]
    let credentialClaims: [String: [String: GenericJSON]]
    let onPick: (UInt32, ParsedCredential) -> Void
    let onContinue: () -> Void
    let onCancel: () -> Void

    @State private var currentIndex: Int = 0

    var currentRequirement: VcalmRequirement {
        requirements[currentIndex]
    }

    var hasMoreRequirements: Bool {
        currentIndex + 1 < requirements.count
    }

    var currentSelectionValid: Bool {
        currentRequirement.candidates.isEmpty
            || picks[currentRequirement.queryIndex] != nil
    }

    func candidateBinding(_ candidate: ParsedCredential) -> Binding<Bool> {
        Binding {
            picks[currentRequirement.queryIndex]?.id() == candidate.id()
        } set: { _ in
            onPick(currentRequirement.queryIndex, candidate)
        }
    }

    func goToNextOrFinish() {
        if hasMoreRequirements {
            currentIndex += 1
        } else {
            onContinue()
        }
    }

    var body: some View {
        VStack {
            if requirements.count > 1 {
                HStack {
                    Text(
                        "Requirement \(currentIndex + 1) of \(requirements.count)"
                    )
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

                Text(currentRequirement.label)
                    .font(.customFont(font: .inter, style: .bold, size: .h2))
                    .foregroundStyle(Color("ColorBlue600"))
            }
            .multilineTextAlignment(.center)
            .padding(.bottom, 8)

            ScrollView {
                if currentRequirement.candidates.isEmpty {
                    Text("No matching credential(s)")
                        .font(
                            .customFont(
                                font: .inter,
                                style: .regular,
                                size: .h4
                            )
                        )
                        .foregroundStyle(Color("ColorRose600"))
                } else {
                    ForEach(
                        Array(currentRequirement.candidates.enumerated()),
                        id: \.offset
                    ) { _, candidate in
                        VcalmCredentialSelectorItem(
                            candidate: candidate,
                            requestedFields: currentRequirement.fields,
                            credentialClaims: credentialClaims,
                            isChecked: candidateBinding(candidate)
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

struct VcalmCredentialSelectorItem: View {
    let candidate: ParsedCredential
    let requestedFields: [String]
    let title: String
    @Binding var isChecked: Bool

    @State var expanded = false

    init(
        candidate: ParsedCredential,
        requestedFields: [VcalmRequestedField],
        credentialClaims: [String: [String: GenericJSON]],
        isChecked: Binding<Bool>
    ) {
        self.candidate = candidate
        self.requestedFields = requestedFields.map {
            $0.path.camelCaseToWords().capitalized.replaceUnderscores()
        }
        self.title = vcalmCredentialTitle(
            candidate,
            credentialClaims: credentialClaims
        )
        self._isChecked = isChecked
    }

    var body: some View {
        VStack {
            HStack {
                Toggle(isOn: $isChecked) {
                    Text(title)
                        .font(
                            .customFont(
                                font: .inter,
                                style: .semiBold,
                                size: .h3
                            )
                        )
                        .foregroundStyle(Color("ColorStone950"))
                }
                .toggleStyle(iOSCheckboxToggleStyle())
                Spacer()
                if !requestedFields.isEmpty {
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
            }
            VStack(alignment: .leading) {
                ForEach(requestedFields, id: \.self) { field in
                    Text("• \(field)")
                        .font(
                            .customFont(
                                font: .inter,
                                style: .regular,
                                size: .h4
                            )
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

struct VcalmFieldsSelector: View {
    let requirements: [VcalmRequirement]
    let picks: [UInt32: ParsedCredential]
    let credentialClaims: [String: [String: GenericJSON]]
    let onSubmit: () -> Void
    let onCancel: () -> Void

    @State private var currentIndex: Int = 0

    var currentRequirement: VcalmRequirement {
        requirements[currentIndex]
    }

    var hasMoreRequirements: Bool {
        currentIndex + 1 < requirements.count
    }

    var body: some View {
        VStack {
            if requirements.count > 1 {
                HStack {
                    Text(
                        "Credential \(currentIndex + 1) of \(requirements.count)"
                    )
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
                VcalmFieldsSelectorFields(
                    requirement: currentRequirement,
                    currentCredential: picks[currentRequirement.queryIndex],
                    credentialClaims: credentialClaims
                )
                .id(currentRequirement.queryIndex)
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
                    if hasMoreRequirements {
                        currentIndex += 1
                    } else {
                        onSubmit()
                    }
                } label: {
                    Text(hasMoreRequirements ? "Next" : "Approve")
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

struct VcalmFieldsSelectorFields: View {
    let requirement: VcalmRequirement
    let currentCredential: ParsedCredential?
    let credentialClaims: [String: [String: GenericJSON]]

    // Optional fields are pre-selected but can be unchecked, mandatory
    // fields are always selected but disabled
    @State private var selectedFields: Set<String>

    init(
        requirement: VcalmRequirement,
        currentCredential: ParsedCredential?,
        credentialClaims: [String: [String: GenericJSON]]
    ) {
        self.requirement = requirement
        self.currentCredential = currentCredential
        self.credentialClaims = credentialClaims
        self._selectedFields = State(
            initialValue: Set(requirement.fields.map { $0.path })
        )
    }

    func fieldBinding(_ field: VcalmRequestedField) -> Binding<Bool> {
        Binding {
            selectedFields.contains(field.path) || field.required
        } set: { checked in
            if checked {
                selectedFields.insert(field.path)
            } else {
                selectedFields.remove(field.path)
            }
        }
    }

    var body: some View {
        if requirement.fields.isEmpty {
            // No specific fields requested, show all claims from the credential
            let allClaims =
                currentCredential.flatMap { credentialClaims[$0.id()] } ?? [:]
            ForEach(Array(allClaims.keys.sorted()), id: \.self) { claimName in
                SelectiveDisclosureItem(
                    fieldName: claimName,
                    required: true,
                    isChecked: .constant(true)
                )
            }
        } else {
            ForEach(Array(requirement.fields.enumerated()), id: \.offset) {
                _,
                field in
                SelectiveDisclosureItem(
                    fieldName: field.path,
                    required: field.required,
                    isChecked: fieldBinding(field)
                )
            }
        }
    }
}

struct VcalmDomainMismatchSheet: View {
    let domain: String
    let channel: String
    let onCancel: () -> Void
    let onContinueAnyway: () -> Void

    var body: some View {
        AppBottomSheet(
            title: "Verifier domain mismatch",
            subtitle:
                "This verifier's request domain (\(domain)) doesn't match the "
                + "exchange's channel (\(channel)). Only continue if you recognize and trust both sites.",
            onCancel: onCancel
        ) {
            Button {
                onContinueAnyway()
            } label: {
                Text("Continue Anyway")
                    .frame(maxWidth: .infinity)
                    .font(.customFont(font: .inter, style: .medium, size: .h4))
            }
            .foregroundColor(.white)
            .padding(.vertical, 13)
            .background(Color("ColorRose600"))
            .clipShape(RoundedRectangle(cornerRadius: 8))
        }
    }
}
