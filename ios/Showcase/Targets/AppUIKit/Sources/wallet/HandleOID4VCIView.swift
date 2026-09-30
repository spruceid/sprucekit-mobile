import AuthenticationServices
import SpruceIDMobileSdk
import SpruceIDMobileSdkRs
import SwiftUI

struct HandleOID4VCI: Hashable {
    var url: String, onSuccess: (() -> Void)? = nil

    static func == (lhs: HandleOID4VCI, rhs: HandleOID4VCI) -> Bool {
        lhs.url == rhs.url
    }

    func hash(into hasher: inout Hasher) {
        hasher.combine(url)
    }
}

struct HandleOID4VCIView: View {
    @State var loading: Bool = false
    @State var err: String?
    @State var credentials: [String] = []
    /// Key for this issuance session, shared by the whole offer because one key
    /// backs the client id that every proof is signed against.
    @State var credentialKeyAlias: String?
    @State var credentialPack: CredentialPack?

    @State var showPinAlert: Bool = false
    @State var pinInput: String = ""
    @State var pendingTxState: TxCodeRequired?

    // Hoisted so the PIN-submit callback can reach them after the initial Task completes.
    @State var hoistedHttpClient: Oid4vciAsyncHttpClient?
    @State var hoistedOid4vciClient: Oid4vciClient?
    @State var hoistedClientId: String?
    @State var hoistedCredentialIssuer: String?
    @State var hoistedSigner: JwsSigner?
    @State var hoistedConfigIds: [String]?

    @Binding var path: NavigationPath
    let url: String
    let onSuccess: (() -> Void)?

    // Exchanges every credential in the offer against the token, one request
    // per credential_configuration_id (each requires its own fresh nonce/proof).
    // Deferred credentials are skipped rather than aborting the whole batch,
    // so other credentials in the same offer still get issued.
    func completeIssuance(
        token: CredentialToken,
        httpClient: Oid4vciAsyncHttpClient,
        oid4vciClient: Oid4vciClient,
        clientId: String,
        credentialIssuer: String,
        signer: JwsSigner,
        configIds: [String]
    ) async throws -> [String] {
        var results: [String] = []

        for configId in configIds {
            let nonce = try await token.getNonce(httpClient: httpClient)
            let jwt = try await createJwtProof(issuer: clientId, audience: credentialIssuer, expireInSecs: nil, nonce: nonce, signer: signer)
            let proofs = Proofs.jwt([jwt])

            let credentialId = CredentialOrConfigurationId.configuration(configId)
            let response = try await oid4vciClient.exchangeCredential(httpClient: httpClient, token: token, credential: credentialId, proofs: proofs)

            switch response {
            case .deferred(_):
                continue
            case .immediate(let immediate):
                guard let rawCredential = immediate.credentials.first else {
                    throw NSError(domain: "OID4VCI", code: 0, userInfo: [
                        "CredentialIssuer": credentialIssuer
                    ])
                }
                results.append(String(decoding: Data(rawCredential.payload), as: UTF8.self))
            }
        }

        return results
    }

    func getCredential(credentialOffer: String) {
        loading = true

        // Setup HTTP client.
        let httpClient = Oid4vciAsyncHttpClient()

        // Setup signer.
        let keyAlias = "credential/" + UUID().uuidString
        _ = KeyManager.generateSigningKey(id: keyAlias)
        credentialKeyAlias = keyAlias
        let jwk = KeyManager.getJwk(id: keyAlias)!.copy()
        let didUrl = generateDidJwkUrl(jwk: jwk)
        jwk.setKid(kid: didUrl.description)
        let signer = KeyManagerJwkSigner(id: keyAlias, jwk: jwk)

        let clientId = didUrl.did().description
        let oid4vciClient = Oid4vciClient(clientId: clientId)

        Task {
            do {
                let offerUrl = if url.starts(with: "openid-credential-offer://") {
                    url
                } else {
                    "openid-credential-offer://\(url)"
                }

                let credentialOffer = try await oid4vciClient.resolveOfferUrl(httpClient: httpClient, credentialOfferUrl: offerUrl)
                let credentialIssuer = credentialOffer.credentialIssuer()
                let configIds = credentialOffer.credentialConfigurationIds()

                self.hoistedHttpClient = httpClient
                self.hoistedOid4vciClient = oid4vciClient
                self.hoistedClientId = clientId
                self.hoistedCredentialIssuer = credentialIssuer
                self.hoistedSigner = signer
                self.hoistedConfigIds = configIds

                let state = try await oid4vciClient.acceptOffer(httpClient: httpClient, credentialOffer: credentialOffer)

                switch state {
                case .requiresAuthorizationCode(let authState):
                    let redirectUrl = "sk-showcase-oid4vci-redirect://callback"
                    let waiting = try await authState.proceed(httpClient: httpClient, redirectUrl: redirectUrl)
                    let authUrl = URL(string: waiting.redirectUrl())!

                    let redirectUri: URL? = try await withCheckedThrowingContinuation { cont in
                        let session = ASWebAuthenticationSession(
                            url: authUrl,
                            callbackURLScheme: "sk-showcase-oid4vci-redirect"
                        ) { callbackURL, error in
                            if let _ = error { cont.resume(returning: nil); return }
                            cont.resume(returning: callbackURL)
                        }
                        session.presentationContextProvider = WebAuthPresentationProvider.shared
                        session.start()
                    }

                    if let uri = redirectUri,
                       let comps = URLComponents(url: uri, resolvingAgainstBaseURL: false) {
                        let errorParam = comps.queryItems?.first(where: { $0.name == "error" })?.value
                        let codeParam = comps.queryItems?.first(where: { $0.name == "code" })?.value
                        if let errorParam {
                            err = "Authorization error: \(errorParam)"
                        } else if let codeParam, !codeParam.isEmpty {
                            let token = try await waiting.proceed(httpClient: httpClient, authorizationCode: codeParam)
                            let creds = try await completeIssuance(
                                token: token,
                                httpClient: httpClient,
                                oid4vciClient: oid4vciClient,
                                clientId: clientId,
                                credentialIssuer: credentialIssuer,
                                signer: signer,
                                configIds: configIds
                            )
                            if !creds.isEmpty {
                                credentials = creds
                                onSuccess?()
                            } else {
                                err = "Deferred credentials not supported"
                            }
                        } else {
                            err = "Missing authorization code in callback"
                        }
                    } else {
                        err = "Sign-in canceled"
                    }
                case .requiresTxCode(let txState):
                    self.pendingTxState = txState
                    self.showPinAlert = true
                    loading = false
                    return
                case .ready(let credentialToken):
                    let creds = try await completeIssuance(
                        token: credentialToken,
                        httpClient: httpClient,
                        oid4vciClient: oid4vciClient,
                        clientId: clientId,
                        credentialIssuer: credentialIssuer,
                        signer: signer,
                        configIds: configIds
                    )
                    if !creds.isEmpty {
                        credentials = creds
                        onSuccess?()
                    } else {
                        err = "Deferred credentials not supported"
                    }
                }
            } catch {
                err = error.localizedDescription
                print(error)
            }
            loading = false
        }
    }

    func back() {
        while !path.isEmpty {
            path.removeLast()
        }
    }

    var body: some View {
        ZStack {
            if loading {
                LoadingView(loadingText: "Loading...")
            } else if err != nil {
                ErrorView(
                    errorTitle: "Error Adding Credential",
                    errorDetails: err!
                ) {
                    back()
                }
            } else if !credentials.isEmpty {
                AddToWalletView(path: _path, rawCredentials: credentials, keyAlias: credentialKeyAlias)
            }

        }
        .onAppear(perform: {
            getCredential(credentialOffer: url)
        })
        .alert("Enter Transaction Code", isPresented: $showPinAlert) {
            TextField("PIN", text: $pinInput)
                .keyboardType(.numberPad)
            Button("Submit") {
                let pin = pinInput
                let txState = pendingTxState
                let httpClient = hoistedHttpClient
                let oid4vciClient = hoistedOid4vciClient
                let clientId = hoistedClientId
                let credentialIssuer = hoistedCredentialIssuer
                let signer = hoistedSigner
                let configIds = hoistedConfigIds
                pendingTxState = nil
                pinInput = ""

                guard let txState, let httpClient, let oid4vciClient,
                      let clientId, let credentialIssuer, let signer, let configIds
                else {
                    err = "Internal error: missing PIN context"
                    return
                }

                loading = true
                Task {
                    do {
                        let token = try await txState.proceed(httpClient: httpClient, txCode: pin)
                        let creds = try await completeIssuance(
                            token: token,
                            httpClient: httpClient,
                            oid4vciClient: oid4vciClient,
                            clientId: clientId,
                            credentialIssuer: credentialIssuer,
                            signer: signer,
                            configIds: configIds
                        )
                        if !creds.isEmpty {
                            credentials = creds
                            onSuccess?()
                        } else {
                            err = "Deferred credentials not supported"
                        }
                    } catch {
                        err = error.localizedDescription
                    }
                    loading = false
                }
            }
            Button("Cancel", role: .cancel) {
                pendingTxState = nil
                pinInput = ""
                err = "Transaction code canceled"
            }
        } message: {
            Text("Please enter the PIN provided with the QR code.")
        }
    }
}

/// Anchor provider for `ASWebAuthenticationSession`. Resolves the topmost
/// active window so the auth session can present from anywhere in the
/// navigation stack.
final class WebAuthPresentationProvider: NSObject, ASWebAuthenticationPresentationContextProviding {
    static let shared = WebAuthPresentationProvider()

    func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        let scene = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .first { $0.activationState == .foregroundActive }
        return scene?.keyWindow ?? ASPresentationAnchor()
    }
}

class KeyManagerJwkSigner: JwsSigner, @unchecked Sendable {
    let id: String
    let jwk: Jwk

    init(id: String, jwk: Jwk) {
        self.id = id
        self.jwk = jwk
    }

    func fetchInfo() async throws -> JwsSignerInfo {
        return try await jwk.fetchInfo()
    }

    func signBytes(signingBytes: Data) async throws -> Data {
        return try decodeDerSignature(signatureDer: Data(KeyManager.signPayload(
            id: self.id,
            payload: [UInt8](signingBytes)
        )!))
    }
}
