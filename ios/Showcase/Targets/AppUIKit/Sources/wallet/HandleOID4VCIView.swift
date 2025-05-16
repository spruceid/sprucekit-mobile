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
        let client = Oid4vciAsyncHttpClient()
        let oid4vciSession = Oid4vci.newWithAsyncClient(client: client)
        Task {
            do {
                try await oid4vciSession.initiateWithOffer(
                    credentialOffer: credentialOffer,
                    clientId: "skit-demo-wallet",
                    redirectUrl: "https://spruceid.com"
                )

                let nonce = try await oid4vciSession.exchangeToken()

                let metadata = try oid4vciSession.getMetadata()

                if !KeyManager.keyExists(id: DEFAULT_SIGNING_KEY_ID) {
                    _ = KeyManager.generateSigningKey(
                        id: DEFAULT_SIGNING_KEY_ID
                    )
                }

                let jwk = KeyManager.getJwk(id: DEFAULT_SIGNING_KEY_ID)

                let signingInput =
                    try await SpruceIDMobileSdkRs.generatePopPrepare(
                        audience: metadata.issuer(),
                        nonce: nonce,
                        didMethod: .jwk,
                        publicJwk: jwk!,
                        durationInSecs: nil
                    )

                let signature = KeyManager.signPayload(
                    id: DEFAULT_SIGNING_KEY_ID,
                    payload: [UInt8](signingInput)
                )

                let pop = try SpruceIDMobileSdkRs.generatePopComplete(
                    signingInput: signingInput,
                    signatureDer: Data(signature!)
                )

                try oid4vciSession.setContextMap(
                    values: getVCPlaygroundOID4VCIContext()
                )

                self.credentialPack = CredentialPack()
                let credentials = try await oid4vciSession.exchangeCredential(
                    proofsOfPossession: [pop],
                    options: Oid4vciExchangeOptions(verifyAfterExchange: false)
                )

                credentials.forEach {
                    let cred = String(decoding: Data($0.payload), as: UTF8.self)
                    self.credential = cred
                }

                onSuccess?()

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
