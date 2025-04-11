import SpruceIDMobileSdk
import SpruceIDMobileSdkRs
import SwiftUI

struct HacApplicationListItem: View {
    @EnvironmentObject var hacApplicationObservable: HacApplicationObservable

    let application: HacApplication?
    let startIssuance: (String) -> Void
    @State var credentialOfferUrl: String?
    @State var credentialStatus: CredentialStatusList = .undefined

    var body: some View {
        HStack {
            VStack(alignment: .leading) {
                VStack(alignment: .leading, spacing: 12) {
                    Text("Mobile Drivers License")
                        .font(
                            .customFont(
                                font: .inter,
                                style: .semiBold,
                                size: .h1
                            )
                        )
                        .foregroundStyle(Color("ColorStone950"))
                    CredentialStatusSmall(
                        status: credentialStatus
                    )
                }
                .padding(.leading, 12)
            }
            Spacer()
        }
        .padding(12)
        .background(
            RoundedRectangle(cornerRadius: 8)
                .fill(Color.white)
                .shadow(color: .black.opacity(0.03), radius: 5)
        )
        .overlay(
            RoundedRectangle(cornerRadius: 8)
                .stroke(Color("ColorBase300"), lineWidth: 1)
        )
        .padding(12)
        .onTapGesture {
            if let credentialOfferUrl = credentialOfferUrl {
                startIssuance(credentialOfferUrl)
            }
        }
        .onAppear {
            if let application = application {
                Task {
                    do {
                        let walletAttestation =
                            try await hacApplicationObservable
                            .getWalletAttestation()
                            .unwrap()

                        let status =
                            try await hacApplicationObservable.issuanceClient
                            .checkStatus(
                                issuanceId: application.issuanceId,
                                walletAttestation: walletAttestation
                            )
                        if status.state == "ReadyToProvision" {
                            credentialStatus = .ready
                        }
                        credentialOfferUrl = status.openidCredentialOffer
                    } catch {
                        print(error.localizedDescription)
                    }
                }
            } else {
                credentialStatus = .pending
            }
        }
    }
}
