import SwiftUI
import SpruceIDMobileSdk
import SpruceIDMobileSdkRs

struct HandleOID4VCI: Hashable {
    var url: String
}

struct HandleOID4VCIView: View {
    @State var loading: Bool = false
    @State var err: String?
    @State var credential: String?
    @State var credentialPack: CredentialPack?
    
    @Binding var path: NavigationPath
    let url: String
    
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
                
                _ = KeyManager.generateSigningKey(id: "reference-app/default-signing")
                let jwk = KeyManager.getJwk(id: "reference-app/default-signing")
                
                let signingInput = try await SpruceIDMobileSdkRs.generatePopPrepare(
                    audience: metadata.issuer(),
                    nonce: nonce,
                    didMethod: .jwk,
                    publicJwk: jwk!,
                    durationInSecs: nil
                )
                
                let signature = KeyManager.signPayload(id: "reference-app/default-signing", payload: [UInt8](signingInput))
                
                let pop = try SpruceIDMobileSdkRs.generatePopComplete(
                    signingInput: signingInput,
                    signature: Data(Data(signature!).base64EncodedUrlSafe.utf8)
                )
                
                try oid4vciSession.setContextMap(values: getVCPlaygroundOID4VCIContext())
                
                self.credentialPack = CredentialPack()
                let credentials = try await oid4vciSession.exchangeCredential(proofsOfPossession: [pop])
                
                try credentials.forEach {
                    let cred = String(decoding: Data($0.payload), as: UTF8.self)
                    _ = try self.credentialPack?.addJsonVc(jsonVc: JsonVc.newFromJson(utf8JsonString: cred))
                    self.credential = cred
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

func getVCPlaygroundOID4VCIContext() throws -> [String: String] {
    var context: [String: String] = [:]
    
    var path = Bundle.main.path(forResource: "contexts.vcplayground.org_examples_alumni_v1", ofType: "json")
    context["https://contexts.vcplayground.org/examples/alumni/v1.json"] = try String(contentsOfFile: path!, encoding: String.Encoding.utf8)
    
    path = Bundle.main.path(forResource: "w3id.org_first-responder_v1", ofType: "json")
    context["https://w3id.org/first-responder/v1"] = try String(contentsOfFile: path!, encoding: String.Encoding.utf8)
    
    path = Bundle.main.path(forResource: "w3id.org_vdl_aamva_v1", ofType: "json")
    context["https://w3id.org/vdl/aamva/v1"] = try String(contentsOfFile: path!, encoding: String.Encoding.utf8)
    
    path = Bundle.main.path(forResource: "w3id.org_citizenship_v3", ofType: "json")
    context["https://w3id.org/citizenship/v3"] = try String(contentsOfFile: path!, encoding: String.Encoding.utf8)
    
    path = Bundle.main.path(forResource: "contexts.vcplayground.org_examples_movie-ticket_v1", ofType: "json")
    context["https://contexts.vcplayground.org/examples/movie-ticket/v1.json"] = try String(contentsOfFile: path!, encoding: String.Encoding.utf8)
    
    path = Bundle.main.path(forResource: "purl.imsglobal.org_spec_ob_v3p0_context-3.0.2", ofType: "json")
    context["https://purl.imsglobal.org/spec/ob/v3p0/context-3.0.2.json"] = try String(contentsOfFile: path!, encoding: String.Encoding.utf8)
    
    path = Bundle.main.path(forResource: "contexts.vcplayground.org_examples_food-safety-certification_v1", ofType: "json")
    context["https://contexts.vcplayground.org/examples/food-safety-certification/v1.json"] = try String(contentsOfFile: path!, encoding: String.Encoding.utf8)
    
    path = Bundle.main.path(forResource: "contexts.vcplayground.org_examples_gs1-8110-coupon_v2", ofType: "json")
    context["https://contexts.vcplayground.org/examples/gs1-8110-coupon/v2.json"] = try String(contentsOfFile: path!, encoding: String.Encoding.utf8)
    
    path = Bundle.main.path(forResource: "contexts.vcplayground.org_examples_customer-loyalty_v1", ofType: "json")
    context["https://contexts.vcplayground.org/examples/customer-loyalty/v1.json"] = try String(contentsOfFile: path!, encoding: String.Encoding.utf8)
    
    return context
}
