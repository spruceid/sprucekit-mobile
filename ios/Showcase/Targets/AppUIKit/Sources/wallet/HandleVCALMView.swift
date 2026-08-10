import SpruceIDMobileSdk
import SpruceIDMobileSdkRs
import SwiftUI

struct HandleVCALM: Hashable {
    var url: String
}

enum VcalmSignerError: Error {
    case illegalArgumentException(reason: String)
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
                reason: "Invalid kid")
        } else {
            self._jwk = jwk!.description
        }
    }

    func sign(payload: Data) async throws -> Data {
        let signature = KeyManager.signPayload(
            id: keyId, payload: [UInt8](payload))
        if signature == nil {
            throw VcalmSignerError.illegalArgumentException(
                reason: "Failed to sign payload")
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

/// A single VCALM query requirement: the credential candidates the holder has
/// that satisfy a given query index, ready to be shown to the user for
/// selection. `queryIndex` ties back to `VcalmRequestedField.queryIndex` /
/// `VcalmMatchedCredentials.queryIndex`. `fields` carries the (non-structural)
/// fields the verifier is requesting for this query, so the picker can tell
/// the user whether they're all required or which ones will be shared.
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
    for field in requestedFields where field.path != "type" && field.path != "@context" {
        fieldsByQuery[field.queryIndex, default: []].append(field)
    }

    var candidatesByQuery: [UInt32: [ParsedCredential]] = [:]
    for match in matched {
        candidatesByQuery[match.queryIndex] = match.credentials.map { $0.credential }
    }

    let queryIndices = Set(fieldsByQuery.keys).union(candidatesByQuery.keys).sorted()

    return queryIndices.map { queryIndex in
        let fields = fieldsByQuery[queryIndex] ?? []
        let purposeLabel = fields.compactMap { $0.purpose }.first { !$0.isEmpty }
        return VcalmRequirement(
            queryIndex: queryIndex,
            label: purposeLabel ?? "Credential",
            candidates: candidatesByQuery[queryIndex] ?? [],
            fields: fields
        )
    }
}

/// Best-effort display title for a credential, mirroring the title
/// derivation `HandleOID4VPView.swift`'s `credentialTitle` uses: prefer a
/// claimed `name`, then the credential's non-`VerifiableCredential` `type`
/// entry, then an mdoc's doctype or an SD-JWT's vct, only falling back to a
/// generic label if none of those are available.
func vcalmCredentialTitle(
    _ parsedCredential: ParsedCredential,
    credentialClaims: [String: [String: GenericJSON]] = [:]
) -> String {
    if let name = credentialClaims[parsedCredential.id()]?["name"]?.toString(), !name.isEmpty {
        return name
    }
    if let types = credentialClaims[parsedCredential.id()]?["type"]?.arrayValue {
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
    guard let claims = credentialClaims[parsedCredential.id()] else { return nil }
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
           decoded.hasPrefix("http://") || decoded.hasPrefix("https://") {
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
       decoded.hasPrefix("http://") || decoded.hasPrefix("https://") {
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
    @State private var step: StepResult?
    @State private var holder: VcalmHolder?

    @State private var requirements: [VcalmRequirement]?
    @State private var picks: [UInt32: ParsedCredential] = [:]
    @State private var offeredCredentials: [VcalmOfferedCredential] = []
    @State private var redirectUrl: String?
    @State private var successMessage: String?
    @State private var credentialClaims: [String: [String: GenericJSON]] = [:]
    // Set only when the exchange reaches `.complete` after a presentation submission
    @State private var presentedCredentials: [ParsedCredential]?
    // Set only once an offer is accepted AND the exchange is fully done
    // (`.complete`). Hand off to AddToWalletView
    @State private var pendingWalletCredentials: [String]?
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
                if !isPresented { domainMismatch = nil }
            }
        )
    }

    // Submit presentation after automatically/manually selecting credentials to fit requirements
    func trySubmitPresentation(selected: [ParsedCredential], allowDomainMismatch: Bool) async {
        guard let holder else { return }
        do {
            let result = try await holder.submitPresentation(
                selectedCredentials: selected, allowDomainMismatch: allowDomainMismatch)
            requirements = nil
            domainMismatch = nil
            if case .complete = result {
                presentedCredentials = selected
            }
            await handleStep(result)
        } catch VcalmError.DomainChannelMismatch(let domain, let channel) {
            pendingSelection = selected
            domainMismatch = VcalmDomainMismatch(domain: domain, channel: channel)
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
                await trySubmitPresentation(selected: [], allowDomainMismatch: false)
                return
            }

            let built = buildVcalmRequirements(requestedFields: requestedFields, matched: matched)
            if built.isEmpty || built.allSatisfy({ $0.candidates.isEmpty }) {
                err = VcalmDisplayError(
                    title: "No matching credential(s)",
                    details: "You don't have a credential in your wallet that satisfies this verifier's request."
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
        } catch {
            err = VcalmDisplayError(
                title: "Error Handling Verifier Request",
                details: "Couldn't process the verifier's request. Error: \(error)"
            )
        }
    }

    func handleStep(_ result: StepResult) async {
        step = result
        switch result {
        case .request(let vpr):
            await onRequest(vpr)
        case .offer:
            do {
                offeredCredentials = try await holder?.offeredCredentials() ?? []
            } catch {
                err = VcalmDisplayError(
                    title: "Error Loading Offer",
                    details: "Couldn't load the offered credential(s). Error: \(error)"
                )
            }
        case .redirect(let redirectUrl):
            self.redirectUrl = redirectUrl
        case .complete:
            successMessage = "Successfully shared."
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
        await trySubmitPresentation(selected: selected, allowDomainMismatch: false)
        loading = false
    }

    func acceptOffer() async {
        loading = true
        do {
            let rawCredentials = offeredCredentials.map { $0.rawCredential }
            let result = try await holder!.acceptOffer()
            offeredCredentials = []

            switch result {
            case .complete:
                // Hand off to AddToWalletView
                step = result
                pendingWalletCredentials = rawCredentials
            case .problem:
                // Surface the error, don't store credentials yet
                await handleStep(result)
            default:
                // The exchange is chained, not complete yet. Store credential locally
                // with the same shared helper AddToWalletView uses, then continue
                for raw in rawCredentials {
                    do {
                        _ = try await acceptRawCredentialIntoWallet(
                            rawCredential: raw,
                            credentialPackObservable: credentialPackObservable
                        )
                    } catch {
                        // Treat a save failure like a decline for this
                        // credential rather than blocking the rest of the
                        // flow.
                        print(error)
                    }
                }
                await handleStep(result)
            }
        } catch {
            err = VcalmDisplayError(
                title: "Error Accepting Offer",
                details: "Couldn't accept the offered credential(s). Error: \(error)"
            )
        }
        loading = false
    }

    func declineOffer() async {
        loading = true
        do {
            let result = try await holder!.rejectOffer()
            offeredCredentials = []
            await handleStep(result)
        } catch {
            err = VcalmDisplayError(
                title: "Error Declining Offer",
                details: "Couldn't decline the offered credential(s). Error: \(error)"
            )
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

            let credentials = credentialPackObservable.credentialPacks.flatMap { pack in
                pack.list()
            }
            credentialPackObservable.credentialPacks.forEach { pack in
                credentialClaims = credentialClaims.merging(
                    pack.findCredentialClaims(claimNames: [
                        "name", "type", "validFrom", "issuanceDate"
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
                    rawCredentials: pendingWalletCredentials
                )
            } else if let requirements {
                VcalmRequirementPicker(
                    requirements: requirements,
                    picks: picks,
                    credentialClaims: credentialClaims,
                    onPick: { queryIndex, credential in
                        picks[queryIndex] = credential
                    },
                    onSubmit: {
                        Task {
                            await submitPicks()
                        }
                    }
                )
            } else if !offeredCredentials.isEmpty {
                VcalmOfferView(
                    offered: offeredCredentials,
                    onAccept: {
                        Task {
                            await acceptOffer()
                        }
                    },
                    onDecline: {
                        Task {
                            await declineOffer()
                        }
                    }
                )
            } else if let successMessage {
                VcalmSuccessView(
                    message: successMessage,
                    presentedCredentials: presentedCredentials,
                    credentialClaims: credentialClaims,
                    onDone: back
                )
            } else if step != nil {
                Text("\(String(describing: step))")
            }
        }
        .navigationBarBackButtonHidden(true)
        .task {
            await startExchange()
        }
        .onChange(of: redirectUrl) { newValue in
            // Opening the redirect happens once per URL; the resulting
            // "continue in the browser" message is shown via
            // successMessage below.
            guard let target = newValue, let targetUrl = URL(string: target) else { return }
            UIApplication.shared.open(targetUrl)
            successMessage = "Continue in your browser to finish this presentation."
        }
        .sheet(isPresented: domainMismatchPresented) {
            if let domainMismatch {
                VcalmDomainMismatchSheet(
                    domain: domainMismatch.domain,
                    channel: domainMismatch.channel,
                    onCancel: { self.domainMismatch = nil },
                    onContinueAnyway: {
                        let selection = pendingSelection
                        self.domainMismatch = nil
                        Task {
                            loading = true
                            await trySubmitPresentation(selected: selection, allowDomainMismatch: true)
                            loading = false
                        }
                    }
                )
            }
        }
    }
}

struct VcalmRequirementRow: View {
    let requirement: VcalmRequirement
    let picks: [UInt32: ParsedCredential]
    let credentialClaims: [String: [String: GenericJSON]]
    let onPick: (UInt32, ParsedCredential) -> Void

    // Optional fields are pre-selected but can be unchecked,
    // mandatory fields are always selected but disabled
    @State private var selectedFields: Set<String> = []

    var allRequired: Bool {
        requirement.fields.isEmpty || requirement.fields.allSatisfy { $0.required }
    }

    func candidateBinding(_ candidate: ParsedCredential) -> Binding<Bool> {
        Binding {
            picks[requirement.queryIndex]?.id() == candidate.id()
        } set: { _ in
            onPick(requirement.queryIndex, candidate)
        }
    }

    func fieldBinding(_ field: VcalmRequestedField) -> Binding<Bool> {
        Binding {
            field.required || selectedFields.contains(field.path)
        } set: { checked in
            if checked {
                selectedFields.insert(field.path)
            } else {
                selectedFields.remove(field.path)
            }
        }
    }

    func fieldLabel(_ field: VcalmRequestedField) -> String {
        field.path.camelCaseToWords().replacingOccurrences(of: "_", with: " ")
    }

    var body: some View {
        VStack(alignment: .leading) {
            Text(requirement.label)
                .font(.customFont(font: .inter, style: .semiBold, size: .h3))
                .foregroundStyle(Color("ColorStone950"))
                .padding(.bottom, 4)

            if !requirement.fields.isEmpty {
                Text(allRequired ? "These fields are required by the verifier" : "Select which fields to share:")
                    .font(.customFont(font: .inter, style: .regular, size: .h4))
                    .foregroundStyle(Color("ColorStone500"))

                ForEach(Array(requirement.fields.enumerated()), id: \.offset) { _, field in
                    Toggle(isOn: fieldBinding(field)) {
                        Text("\(fieldLabel(field))\(field.required ? "" : " (optional)")")
                            .font(.customFont(font: .inter, style: .regular, size: .h4))
                            .foregroundStyle(Color("ColorStone500"))
                    }
                    .toggleStyle(iOSCheckboxToggleStyle(enabled: !field.required))
                    .disabled(field.required)
                }
                .padding(.bottom, 8)
            }

            if requirement.candidates.isEmpty {
                Text("No matching credential(s)")
                    .font(.customFont(font: .inter, style: .regular, size: .h4))
                    .foregroundStyle(Color("ColorRose600"))
            } else if requirement.candidates.count == 1, let only = requirement.candidates.first {
                // Pre-select credential if only one matches
                Text("Using: \(vcalmCredentialTitle(only, credentialClaims: credentialClaims))")
                    .font(.customFont(font: .inter, style: .regular, size: .h4))
                    .foregroundStyle(Color("ColorStone950"))
            } else {
                ForEach(Array(requirement.candidates.enumerated()), id: \.offset) { _, candidate in
                    HStack {
                        Toggle(isOn: candidateBinding(candidate)) {
                            Text(vcalmCredentialTitle(candidate, credentialClaims: credentialClaims))
                                .font(.customFont(font: .inter, style: .regular, size: .h4))
                                .foregroundStyle(Color("ColorStone950"))
                        }
                        .toggleStyle(iOSCheckboxToggleStyle())
                    }
                    .padding(.vertical, 4)
                }
            }

            if requirement.fields.isEmpty {
                Text("All fields are required by the verifier")
                    .font(.customFont(font: .inter, style: .regular, size: .h4))
                    .foregroundStyle(Color("ColorStone500"))
            }
        }
        .padding(.bottom, 16)
    }
}

struct VcalmRequirementPicker: View {
    let requirements: [VcalmRequirement]
    let picks: [UInt32: ParsedCredential]
    let credentialClaims: [String: [String: GenericJSON]]
    let onPick: (UInt32, ParsedCredential) -> Void
    let onSubmit: () -> Void

    var allResolved: Bool {
        requirements.allSatisfy { $0.candidates.isEmpty || picks[$0.queryIndex] != nil }
    }

    var body: some View {
        VStack {
            Text("Review Details")
                .font(.customFont(font: .inter, style: .bold, size: .h2))
                .foregroundStyle(Color("ColorStone950"))
                .padding(.bottom, 8)

            ScrollView {
                ForEach(Array(requirements.enumerated()), id: \.offset) { _, requirement in
                    VcalmRequirementRow(
                        requirement: requirement,
                        picks: picks,
                        credentialClaims: credentialClaims,
                        onPick: onPick
                    )
                }
            }

            Button {
                onSubmit()
            } label: {
                Text("Continue")
                    .frame(maxWidth: .infinity)
                    .font(.customFont(font: .inter, style: .medium, size: .h4))
            }
            .foregroundColor(.white)
            .padding(.vertical, 13)
            .background(Color("ColorEmerald900"))
            .clipShape(RoundedRectangle(cornerRadius: 8))
            .opacity(allResolved ? 1 : 0.6)
            .disabled(!allResolved)
        }
        .padding(.horizontal, 24)
        .navigationBarBackButtonHidden(true)
    }
}

struct VcalmOfferView: View {
    let offered: [VcalmOfferedCredential]
    let onAccept: () -> Void
    let onDecline: () -> Void

    var body: some View {
        VStack {
            Text("Credential offer")
                .font(.customFont(font: .inter, style: .bold, size: .h2))
                .foregroundStyle(Color("ColorStone950"))
                .padding(.bottom, 8)

            ScrollView {
                ForEach(Array(offered.enumerated()), id: \.offset) { _, credential in
                    VStack(alignment: .leading) {
                        Text(credential.types.last.map { credentialTypeDisplayName(for: $0) } ?? "Credential")
                            .font(.customFont(font: .inter, style: .semiBold, size: .h3))
                            .foregroundStyle(Color("ColorStone950"))

                        if let issuer = credential.issuer {
                            Text("Issuer: \(issuer)")
                                .font(.customFont(font: .inter, style: .regular, size: .h4))
                                .foregroundStyle(Color("ColorStone700"))
                        }

                        // Time bounded credentials will still be stored, so surface
                        // the warning associated with it before user makes a decision.
                        // When the credential's validity is blocking (ex: unverifiable), the
                        // error is surfaced through ErrorView
                        if credential.validity == .timeBounded {
                            Text("This credential may be premature or expired.")
                                .font(.customFont(font: .inter, style: .regular, size: .h4))
                                .foregroundStyle(Color("ColorStone500"))
                                .padding(.top, 4)
                        }
                    }
                    .padding(16)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .overlay(
                        RoundedRectangle(cornerRadius: 8)
                            .stroke(Color("ColorBase300"), lineWidth: 1)
                    )
                    .padding(.vertical, 4)
                }
            }

            Spacer()

            VStack {
                Button {
                    onAccept()
                } label: {
                    Text("Accept")
                        .frame(maxWidth: .infinity)
                        .font(.customFont(font: .inter, style: .medium, size: .h4))
                }
                .foregroundColor(.white)
                .padding(.vertical, 13)
                .background(Color("ColorEmerald900"))
                .clipShape(RoundedRectangle(cornerRadius: 8))

                Button {
                    onDecline()
                } label: {
                    Text("Decline")
                        .frame(maxWidth: .infinity)
                        .font(.customFont(font: .inter, style: .medium, size: .h4))
                }
                .foregroundColor(Color("ColorRose600"))
                .padding(.vertical, 13)
                .overlay(
                    RoundedRectangle(cornerRadius: 8)
                        .stroke(Color("ColorStone300"), lineWidth: 1)
                )
            }
        }
        .padding(.horizontal, 24)
        .navigationBarBackButtonHidden(true)
    }
}

struct VcalmSuccessView: View {
    let message: String
    var presentedCredentials: [ParsedCredential]?
    var credentialClaims: [String: [String: GenericJSON]] = [:]
    let onDone: () -> Void

    var body: some View {
        VStack {
            Text(message)
                .font(.customFont(font: .inter, style: .semiBold, size: .h3))
                .foregroundStyle(Color("ColorStone950"))
                .multilineTextAlignment(.center)
                .padding(.top, 16)

            if let presentedCredentials, !presentedCredentials.isEmpty {
                Text(presentedCredentials.count > 1 ? "Credentials presented:" : "Credential presented:")
                    .font(.customFont(font: .inter, style: .medium, size: .h4))
                    .foregroundStyle(Color("ColorStone500"))
                    .padding(.top, 16)

                ScrollView {
                    ForEach(Array(presentedCredentials.enumerated()), id: \.offset) { _, credential in
                        VStack(alignment: .leading, spacing: 4) {
                            Text(vcalmCredentialTitle(credential, credentialClaims: credentialClaims))
                                .font(.customFont(font: .inter, style: .semiBold, size: .h4))
                                .foregroundStyle(Color("ColorStone950"))
                            Text("ID: \(credential.id())")
                                .font(.customFont(font: .inter, style: .regular, size: .h4))
                                .foregroundStyle(Color("ColorStone500"))
                            if let issuedDate = vcalmCredentialIssuedDate(credential, credentialClaims: credentialClaims) {
                                Text("Valid from: \(issuedDate)")
                                    .font(.customFont(font: .inter, style: .regular, size: .h4))
                                    .foregroundStyle(Color("ColorStone500"))
                            }
                        }
                        .padding(12)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .overlay(
                            RoundedRectangle(cornerRadius: 8)
                                .stroke(Color("ColorBase300"), lineWidth: 1)
                        )
                        .padding(.vertical, 4)
                    }
                }
            }

            Spacer()
            Button {
                onDone()
            } label: {
                Text("Done")
                    .frame(maxWidth: .infinity)
                    .font(.customFont(font: .inter, style: .medium, size: .h4))
            }
            .foregroundColor(.white)
            .padding(.vertical, 13)
            .background(Color("ColorEmerald900"))
            .clipShape(RoundedRectangle(cornerRadius: 8))
        }
        .padding(24)
        .navigationBarBackButtonHidden(true)
    }
}

struct VcalmDomainMismatchSheet: View {
    let domain: String
    let channel: String
    let onCancel: () -> Void
    let onContinueAnyway: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Verifier domain mismatch")
                .font(.customFont(font: .inter, style: .bold, size: .h2))
                .foregroundStyle(Color("ColorStone950"))

            Text(
                "This verifier's request domain (\(domain)) doesn't match the " +
                "exchange's channel (\(channel)). This is a known issue with some " +
                "vcplayground.org demos — continuing anyway sends your presentation " +
                "even though this anti-replay check failed."
            )
            .font(.customFont(font: .inter, style: .regular, size: .h4))
            .foregroundStyle(Color("ColorStone700"))

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

            Button {
                onCancel()
            } label: {
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
        }
        .padding(.horizontal, 20)
        .padding(.top, 30)
        .presentationDetents([.fraction(0.5)])
        .presentationDragIndicator(.visible)
    }
}
