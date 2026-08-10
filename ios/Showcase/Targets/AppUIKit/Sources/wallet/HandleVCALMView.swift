import SpruceIDMobileSdkRs
import SwiftUI

struct HandleVCALM: Hashable {
    var url: String
    //    var credentialPackId: String?
}

struct HandleVCALMView: View {
    @EnvironmentObject private var credentialPackObservable:
        CredentialPackObservable
    @State var err: String?

    @Binding var path: NavigationPath
    let url: String

    func onBack() {
        path.removeLast()
    }

    func startExchange() async {
        do {
//            Start exchange
            let vdcCollection = VdcCollection(
                engine: credentialPackObservable.storageManager
            )
            let signer = try Signer(keyId: "vcalm_holder_key")

            let holder = try await VcalmHolder.newSession(
                vdcCollection: vdcCollection,
                trustedDids: [],
                signer: signer,
                contextMap: nil,
                keystore: nil
            )

            let credentials = credentialPackObservable.credentialPacks.flatMap({
                pack in
                pack.list()
            })

            await holder.provideCredentials(credentials: credentials)

            let step: StepResult = try await holder.startExchange(
                input: url,
                authHeader: nil
            )
//            Handle response
            switch(step) {
            case .request(let vpr):
                print("VCALM verifier request: domain=", vpr.domain, "queries=", vpr.query)
                await onRequest(vpr)
            case .offer(let vcs, let nextVpr, let redirectUrl): // next and redirect might be null
                print(try await holder.offeredCredentials())
            case .redirect(let url):
//                offer redirect w url
            case .complete:
//                Exchange was completed with no request/nothing to present
                err = "Error on VCALM start: exchange completed with no request/offer (VcalmComplete) — nothing to present"
            case .problem(let details):
                err = "Error on VCALM start: server problem type=" + details.problemType + "title=" + details.title
            }
            
        } catch {
            print("err")
        }
    }

    var body: some View {
        ZStack {
            if err != nil {
                ErrorView(
                    errorTitle: "Error using VCALM protocol",
                    errorDetails: err!,
                    onClose: onBack
                )
            } else {
                Text("Fetching Protocols")
            }
        }.task {
            await startExchange()
        }.navigationBarBackButtonHidden(true)
    }
}
