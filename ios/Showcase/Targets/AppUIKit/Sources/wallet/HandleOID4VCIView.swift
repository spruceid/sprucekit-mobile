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
    @State var credential: String?
    @State var credentialPack: CredentialPack?

    @Binding var path: NavigationPath
    let url: String
    let onSuccess: (() -> Void)?

    func getCredential(credentialOffer: String) {
        loading = true
        
        // Setup HTTP client.
        let httpClient = Oid4vciAsyncHttpClient()
        
        // Setup signer.
        let jwk = KeyManager.getOrInsertJwk(id: DEFAULT_SIGNING_KEY_ID)
        let didUrl = generateDidJwkUrl(jwk: jwk)
        jwk.setKid(kid: didUrl.description)
        let signer = KeyManagerJwkSigner(id: DEFAULT_SIGNING_KEY_ID, jwk: jwk)
        
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
                
                let state = try await oid4vciClient.acceptOffer(httpClient: httpClient, credentialOffer: credentialOffer)
                
                switch state {
                case .requiresAuthorizationCode(_):
                    err = "Authorization Code Grant not supported"
                case .requiresTxCode(_):
                    err = "Transaction Code not supported"
                case .ready(let credentialToken):
                    let credentialId = try credentialToken.defaultCredentialId()
                
                    // Generate Proof of Possession.
                    let nonce = try await credentialToken.getNonce(httpClient: httpClient)
                    let jwt = try await createJwtProof(issuer: clientId, audience: credentialIssuer, expireInSecs: nil, nonce: nonce, signer: signer)
                    let proofs = Proofs.jwt([jwt])
                
                    // Exchange token against credential.
                    let response = try await oid4vciClient.exchangeCredential(httpClient: httpClient, token: credentialToken, credential: credentialId, proofs: proofs)
                    
                    switch response {
                    case .deferred(_):
                        err = "Deferred credentials not supported"
                    case .immediate(let response):
                        guard let rawCredential = response.credentials.first else {
                            throw NSError(domain: "OID4VCI", code: 0, userInfo: [
                                "CredentialOfferUrl": offerUrl,
                                "CredentialIssuer": credentialIssuer
                            ])
                        }
                        
                        credential = String(decoding: Data(rawCredential.payload), as: UTF8.self)
                        
                        onSuccess?()
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
            } else if credential != nil {
                AddToWalletView(path: _path, rawCredential: credential!)
            }

        }.onAppear(perform: {
            getCredential(credentialOffer: url)
        })
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
            id: DEFAULT_SIGNING_KEY_ID,
            payload: [UInt8](signingBytes)
        )!))
    }
}

func getVCPlaygroundOID4VCIContext() throws -> [String: String] {
    var context: [String: String] = [:]

    var path = Bundle.main.path(
        forResource: "w3id.org_first-responder_v1",
        ofType: "json"
    )
    context["https://w3id.org/first-responder/v1"] = try String(
        contentsOfFile: path!,
        encoding: String.Encoding.utf8
    )

    path = Bundle.main.path(
        forResource: "w3id.org_vdl_aamva_v1",
        ofType: "json"
    )
    context["https://w3id.org/vdl/aamva/v1"] = try String(
        contentsOfFile: path!,
        encoding: String.Encoding.utf8
    )

    path = Bundle.main.path(
        forResource: "w3id.org_citizenship_v3",
        ofType: "json"
    )
    context["https://w3id.org/citizenship/v3"] = try String(
        contentsOfFile: path!,
        encoding: String.Encoding.utf8
    )

    path = Bundle.main.path(
        forResource: "purl.imsglobal.org_spec_ob_v3p0_context-3.0.2",
        ofType: "json"
    )
    context["https://purl.imsglobal.org/spec/ob/v3p0/context-3.0.2.json"] =
        try String(contentsOfFile: path!, encoding: String.Encoding.utf8)

    path = Bundle.main.path(
        forResource: "w3id.org_citizenship_v4rc1",
        ofType: "json"
    )
    context["https://w3id.org/citizenship/v4rc1"] = try String(
        contentsOfFile: path!,
        encoding: String.Encoding.utf8
    )

    path = Bundle.main.path(
        forResource: "w3id.org_vc_render-method_v2rc1",
        ofType: "json"
    )
    context["https://w3id.org/vc/render-method/v2rc1"] = try String(
        contentsOfFile: path!,
        encoding: String.Encoding.utf8
    )

    path = Bundle.main.path(
        forResource:
            "examples.vcplayground.org_contexts_alumni_v2",
        ofType: "json"
    )
    context[
        "https://examples.vcplayground.org/contexts/alumni/v2.json"
    ] = try String(contentsOfFile: path!, encoding: String.Encoding.utf8)

    path = Bundle.main.path(
        forResource:
            "examples.vcplayground.org_contexts_first-responder_v1",
        ofType: "json"
    )
    context[
        "https://examples.vcplayground.org/contexts/first-responder/v1.json"
    ] = try String(contentsOfFile: path!, encoding: String.Encoding.utf8)

    path = Bundle.main.path(
        forResource:
            "examples.vcplayground.org_contexts_shim-render-method-term_v1",
        ofType: "json"
    )
    context[
        "https://examples.vcplayground.org/contexts/shim-render-method-term/v1.json"
    ] = try String(contentsOfFile: path!, encoding: String.Encoding.utf8)

    path = Bundle.main.path(
        forResource:
            "examples.vcplayground.org_contexts_shim-VCv1.1-common-example-terms_v1",
        ofType: "json"
    )
    context[
        "https://examples.vcplayground.org/contexts/shim-VCv1.1-common-example-terms/v1.json"
    ] = try String(contentsOfFile: path!, encoding: String.Encoding.utf8)

    path = Bundle.main.path(
        forResource:
            "examples.vcplayground.org_contexts_utopia-natcert_v1",
        ofType: "json"
    )
    context[
        "https://examples.vcplayground.org/contexts/utopia-natcert/v1.json"
    ] = try String(contentsOfFile: path!, encoding: String.Encoding.utf8)

    path = Bundle.main.path(
        forResource:
            "w3.org_ns_controller_v1",
        ofType: "json"
    )
    context[
        "https://www.w3.org/ns/controller/v1"
    ] = try String(contentsOfFile: path!, encoding: String.Encoding.utf8)

    path = Bundle.main.path(
        forResource:
            "examples.vcplayground.org_contexts_movie-ticket_v2",
        ofType: "json"
    )
    context[
        "https://examples.vcplayground.org/contexts/movie-ticket/v2.json"
    ] = try String(contentsOfFile: path!, encoding: String.Encoding.utf8)

    path = Bundle.main.path(
        forResource:
            "examples.vcplayground.org_contexts_food-safety-certification_v1",
        ofType: "json"
    )
    context[
        "https://examples.vcplayground.org/contexts/food-safety-certification/v1.json"
    ] = try String(contentsOfFile: path!, encoding: String.Encoding.utf8)

    path = Bundle.main.path(
        forResource:
            "examples.vcplayground.org_contexts_academic-course-credential_v1",
        ofType: "json"
    )
    context[
        "https://examples.vcplayground.org/contexts/academic-course-credential/v1.json"
    ] = try String(contentsOfFile: path!, encoding: String.Encoding.utf8)

    path = Bundle.main.path(
        forResource:
            "examples.vcplayground.org_contexts_gs1-8110-coupon_v2",
        ofType: "json"
    )
    context[
        "https://examples.vcplayground.org/contexts/gs1-8110-coupon/v2.json"
    ] = try String(contentsOfFile: path!, encoding: String.Encoding.utf8)

    path = Bundle.main.path(
        forResource:
            "examples.vcplayground.org_contexts_customer-loyalty_v1",
        ofType: "json"
    )
    context[
        "https://examples.vcplayground.org/contexts/customer-loyalty/v1.json"
    ] = try String(contentsOfFile: path!, encoding: String.Encoding.utf8)

    path = Bundle.main.path(
        forResource:
            "examples.vcplayground.org_contexts_movie-ticket-vcdm-v2_v1",
        ofType: "json"
    )
    context[
        "https://examples.vcplayground.org/contexts/movie-ticket-vcdm-v2/v1.json"
    ] = try String(contentsOfFile: path!, encoding: String.Encoding.utf8)

    return context
}
