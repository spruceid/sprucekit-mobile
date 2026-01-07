/// DC API Extension View
///
/// This view handles the credential presentation flow for DC API requests.
/// It loads credentials from App Group shared storage and presents them
/// to the user for approval before sending to the requesting website.

#if canImport(IdentityDocumentServices) && canImport(IdentityDocumentServicesUI)
import IdentityDocumentServices
import IdentityDocumentServicesUI
import SwiftUI
import SpruceIDMobileSdk
import SpruceIDMobileSdkRs
import Foundation

// MARK: - Configuration

/// Get App Group ID from Info.plist (storageAppGroup key)
/// This must match the App Group ID configured in entitlements
private var appGroupId: String? {
    Bundle.main.object(forInfoDictionaryKey: "storageAppGroup") as? String
}

// MARK: - State

enum DcApiExtensionState {
    case loading
    case error(title: String, details: String)
    case selectCredential([RequestMatch180137])
    case selectFields(RequestMatch180137, [ParsedCredential], URL)
}

// MARK: - Main View

@available(iOS 26.0, *)
public struct DocumentProviderExtensionView: View {
    @State private var state: DcApiExtensionState = .loading
    let context: ISO18013MobileDocumentRequestContext

    public init(context: ISO18013MobileDocumentRequestContext) {
        self.context = context
    }

    public var body: some View {
        Group {
            switch state {
            case .loading:
                loadingView
            case .error(let title, let details):
                ErrorView(title: title, details: details) {
                    context.cancel()
                }
            case .selectCredential(let matches):
                CredentialSelectorView(matches: matches) { selected in
                    if let origin = context.requestingWebsiteOrigin {
                        Task {
                            do {
                                let credentials = try await loadCredentialsAsync()
                                state = .selectFields(selected, credentials, origin)
                            } catch {
                                state = .error(
                                    title: "Failed to load credentials",
                                    details: error.localizedDescription
                                )
                            }
                        }
                    }
                } onCancel: {
                    context.cancel()
                }
            case .selectFields(let match, let credentials, let origin):
                FieldSelectorView(match: match) { approvedResponse in
                    Task {
                        await submitResponse(
                            match: match,
                            credentials: credentials,
                            origin: origin,
                            approvedResponse: approvedResponse
                        )
                    }
                } onCancel: {
                    context.cancel()
                }
            }
        }
    }

    private var loadingView: some View {
        VStack(spacing: 16) {
            ProgressView()
            Text("Loading credentials...")
                .foregroundColor(.secondary)
        }
        .task {
            await loadAndMatchCredentials()
        }
    }

    private func loadAndMatchCredentials() async {
        do {
            // Load credentials from StorageManager (App Group)
            let credentials = try await loadCredentialsAsync()

            // Convert iOS request to SDK format
            let presentmentRequests = context.request.presentmentRequests.map { presentmentRequest in
                let requestSets = presentmentRequest.documentRequestSets.map { documentRequestSet in
                    let requests = documentRequestSet.requests.map { request in
                        let namespaces = Dictionary(uniqueKeysWithValues: request.namespaces.map { (key, value) in
                            let v = Dictionary(uniqueKeysWithValues: value.map { (key, value) in
                                let v = Iosiso18013MobileDocumentRequestElementInfo(isRetaining: value.isRetaining)
                                return (key, v)
                            })
                            return (key, v)
                        })
                        return Iosiso18013MobileDocumentRequestDocumentRequest(
                            documentType: request.documentType,
                            namespaces: namespaces
                        )
                    }
                    return Iosiso18013MobileDocumentRequestDocumentRequestSet(requests: requests)
                }
                return Iosiso18013MobileDocumentRequestPresentmentRequest(
                    documentRequestSets: requestSets,
                    isMandatory: presentmentRequest.isMandatory
                )
            }

            let documentRequest = Iosiso18013MobileDocumentRequest(presentmentRequests: presentmentRequests)
            let matches = documentRequest.toMatches(parsedCredentials: credentials)

            if matches.isEmpty {
                state = .error(
                    title: "No matching credentials",
                    details: "No credentials match the verifier's request."
                )
            } else {
                state = .selectCredential(matches)
            }
        } catch {
            state = .error(
                title: "Failed to load credentials",
                details: error.localizedDescription
            )
        }
    }

    private func submitResponse(
        match: RequestMatch180137,
        credentials: [ParsedCredential],
        origin: URL,
        approvedResponse: ApprovedResponse180137
    ) async {
        do {
            try await context.sendResponse { rawRequest in
                var originString = origin.absoluteString
                if originString.last == "/" {
                    originString.removeLast()
                }

                let responseData = try await buildAnnexCResponse(
                    request: rawRequest.requestData,
                    origin: originString,
                    selectedMatch: match,
                    parsedCredentials: credentials,
                    approvedResponse: approvedResponse,
                    keyStore: KeyManager()
                )

                return ISO18013MobileDocumentResponse(responseData: responseData)
            }
        } catch {
            state = .error(
                title: "Failed to submit response",
                details: error.localizedDescription
            )
        }
    }
}

// MARK: - Credential Loading

/// Loads credentials from StorageManager using App Group
private func loadCredentialsAsync() async throws -> [ParsedCredential] {
    guard let groupId = appGroupId else {
        return []
    }

    let storageManager = StorageManager(appGroupId: groupId)
    let credentialPacks = try await CredentialPack.loadAll(storageManager: storageManager)

    return credentialPacks.flatMap { $0.list() }
}


// MARK: - Helper Views

@available(iOS 26.0, *)
struct ErrorView: View {
    let title: String
    let details: String
    let onClose: () -> Void

    @State private var showDetails = false

    var body: some View {
        VStack(spacing: 20) {
            Image(systemName: "exclamationmark.triangle.fill")
                .font(.system(size: 48))
                .foregroundColor(.red)

            Text(title)
                .font(.title2)
                .fontWeight(.bold)
                .foregroundColor(.primary)

            Button("View Details") {
                showDetails = true
            }
            .foregroundColor(.secondary)

            Spacer()

            Button(action: onClose) {
                Text("Close")
                    .frame(maxWidth: .infinity)
                    .padding()
                    .background(Color.gray.opacity(0.2))
                    .cornerRadius(10)
            }
            .foregroundColor(.primary)
        }
        .padding()
        .sheet(isPresented: $showDetails) {
            NavigationView {
                ScrollView {
                    Text(details)
                        .font(.system(.body, design: .monospaced))
                        .padding()
                }
                .navigationTitle("Error Details")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Done") {
                            showDetails = false
                        }
                    }
                }
            }
        }
    }
}

@available(iOS 26.0, *)
struct CredentialSelectorView: View {
    let matches: [RequestMatch180137]
    let onContinue: (RequestMatch180137) -> Void
    let onCancel: () -> Void

    @State private var selectedIndex: Int?

    var body: some View {
        VStack(spacing: 16) {
            Text("Select Credential")
                .font(.title2)
                .fontWeight(.bold)

            Text("Choose which credential to share with the verifier")
                .font(.subheadline)
                .foregroundColor(.secondary)
                .multilineTextAlignment(.center)

            ScrollView {
                VStack(spacing: 12) {
                    ForEach(0..<matches.count, id: \.self) { index in
                        CredentialCard(
                            match: matches[index],
                            isSelected: selectedIndex == index
                        )
                        .onTapGesture {
                            selectedIndex = index
                        }
                    }
                }
                .padding(.horizontal)
            }

            HStack(spacing: 12) {
                Button(action: onCancel) {
                    Text("Cancel")
                        .frame(maxWidth: .infinity)
                        .padding()
                        .background(Color.red.opacity(0.1))
                        .foregroundColor(.red)
                        .cornerRadius(10)
                }

                Button {
                    if let index = selectedIndex {
                        onContinue(matches[index])
                    }
                } label: {
                    Text("Continue")
                        .frame(maxWidth: .infinity)
                        .padding()
                        .background(selectedIndex != nil ? Color.blue : Color.gray)
                        .foregroundColor(.white)
                        .cornerRadius(10)
                }
                .disabled(selectedIndex == nil)
            }
            .padding(.horizontal)
        }
        .padding(.vertical)
    }
}

@available(iOS 26.0, *)
struct CredentialCard: View {
    let match: RequestMatch180137
    let isSelected: Bool

    var body: some View {
        HStack {
            Image(systemName: isSelected ? "checkmark.circle.fill" : "circle")
                .foregroundColor(isSelected ? .blue : .gray)
                .font(.title2)

            VStack(alignment: .leading, spacing: 4) {
                Text("Mobile Driver's License")
                    .fontWeight(.semibold)

                Text("\(match.requestedFields().count) fields requested")
                    .font(.caption)
                    .foregroundColor(.secondary)
            }

            Spacer()
        }
        .padding()
        .background(isSelected ? Color.blue.opacity(0.1) : Color.gray.opacity(0.1))
        .cornerRadius(12)
        .overlay(
            RoundedRectangle(cornerRadius: 12)
                .stroke(isSelected ? Color.blue : Color.clear, lineWidth: 2)
        )
    }
}

@available(iOS 26.0, *)
struct FieldSelectorView: View {
    let match: RequestMatch180137
    let onContinue: (ApprovedResponse180137) -> Void
    let onCancel: () -> Void

    @State private var selectedFieldIds: Set<String>

    init(
        match: RequestMatch180137,
        onContinue: @escaping (ApprovedResponse180137) -> Void,
        onCancel: @escaping () -> Void
    ) {
        self.match = match
        self.onContinue = onContinue
        self.onCancel = onCancel

        // Pre-select required fields
        let requiredIds = match.requestedFields()
            .filter { $0.required || !$0.selectivelyDisclosable }
            .map { $0.id }
        self._selectedFieldIds = State(initialValue: Set(requiredIds))
    }

    var body: some View {
        VStack(spacing: 16) {
            Text("Review Information")
                .font(.title2)
                .fontWeight(.bold)

            Text("Select which information to share")
                .font(.subheadline)
                .foregroundColor(.secondary)

            ScrollView {
                VStack(spacing: 8) {
                    ForEach(match.requestedFields(), id: \.id) { field in
                        FieldRow(
                            field: field,
                            isSelected: selectedFieldIds.contains(field.id),
                            onToggle: {
                                if field.selectivelyDisclosable && !field.required {
                                    if selectedFieldIds.contains(field.id) {
                                        selectedFieldIds.remove(field.id)
                                    } else {
                                        selectedFieldIds.insert(field.id)
                                    }
                                }
                            }
                        )
                    }
                }
                .padding(.horizontal)
            }

            HStack(spacing: 12) {
                Button(action: onCancel) {
                    Text("Cancel")
                        .frame(maxWidth: .infinity)
                        .padding()
                        .background(Color.red.opacity(0.1))
                        .foregroundColor(.red)
                        .cornerRadius(10)
                }

                Button {
                    let approved = ApprovedResponse180137(
                        credentialId: match.credentialId(),
                        approvedFields: Array(selectedFieldIds)
                    )
                    onContinue(approved)
                } label: {
                    Text("Share")
                        .frame(maxWidth: .infinity)
                        .padding()
                        .background(Color.blue)
                        .foregroundColor(.white)
                        .cornerRadius(10)
                }
            }
            .padding(.horizontal)
        }
        .padding(.vertical)
    }
}

@available(iOS 26.0, *)
struct FieldRow: View {
    let field: RequestedField180137
    let isSelected: Bool
    let onToggle: () -> Void

    var isLocked: Bool {
        field.required || !field.selectivelyDisclosable
    }

    var body: some View {
        HStack {
            Image(systemName: isSelected ? "checkmark.square.fill" : "square")
                .foregroundColor(isLocked ? .gray : (isSelected ? .blue : .gray))
                .font(.title3)

            VStack(alignment: .leading, spacing: 2) {
                HStack {
                    Text(field.displayableName)
                        .fontWeight(.medium)

                    if field.required {
                        Text("Required")
                            .font(.caption2)
                            .padding(.horizontal, 6)
                            .padding(.vertical, 2)
                            .background(Color.orange.opacity(0.2))
                            .foregroundColor(.orange)
                            .cornerRadius(4)
                    }
                }

                if field.intentToRetain {
                    Text("Will be stored by verifier")
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
            }

            Spacer()
        }
        .padding()
        .background(Color.gray.opacity(0.1))
        .cornerRadius(8)
        .onTapGesture {
            if !isLocked {
                onToggle()
            }
        }
    }
}

#endif
