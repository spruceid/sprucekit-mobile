import SpruceIDMobileSdk
import SwiftUI

struct VerifierCredentialSuccessView: View {
    var rawCredential: String
    var onClose: () -> Void
    var logVerification: (String, String, String) -> Void

    @State var credentialPack: CredentialPack?
    @State var credentialStatus: CredentialStatusList?
    @State var title: String?
    @State var issuer: String?

    //    init(rawCredential: String, onClose: @escaping () -> Void, logVerification: @escaping (String, String, String) -> Void) {
    //        self.rawCredential = rawCredential
    //        self.onClose = onClose
    //        do {
    //            let credentialItem = try credentialDisplayerSelector(rawCredential: rawCredential)
    //            self.credentialItem = credentialItem
    //            var credentialStatus = CredentialStatusList.undefined
    //
    //            let semaphore = DispatchSemaphore(value: 0)
    //            Task {
    //                let statusLists = await credentialItem.credentialPack.getStatusListsAsync(
    //                    hasConnection: true)
    //                if !statusLists.isEmpty {
    //                    credentialStatus = statusLists.first?.value ?? CredentialStatusList.unknown
    //                }
    //                semaphore.signal()
    //            }
    //            _ = semaphore.wait(timeout: .distantFuture)
    //
    //            let credential = credentialItem.credentialPack.list().first
    //            let claims = try credentialItem.credentialPack.findCredentialClaims(
    //                claimNames: ["name", "type", "description", "issuer"]
    //            )[credential.unwrap().id()]
    //
    //
    //            var tmpTitle = claims?["name"]?.toString()
    //            if tmpTitle == nil {
    //                claims?["type"]?.arrayValue?.forEach {
    //                    if $0.toString() != "VerifiableCredential" {
    //                        tmpTitle = $0.toString().camelCaseToWords()
    //                        return
    //                    }
    //                }
    //            }
    //            self.title = tmpTitle ?? ""
    //
    //            if let issuerName = claims?["issuer"]?.dictValue?["name"]?.toString() {
    //                self.issuer = issuerName
    //            } else {
    //                self.issuer = ""
    //            }
    //            logVerification(title, issuer, credentialStatus.rawValue)
    //        } catch {
    //            self.credentialItem = nil
    //            self.title = ""
    //            self.issuer = ""
    //        }
    //    }

    var body: some View {
        VStack {
            Text(title ?? "")
                .multilineTextAlignment(.leading)
                .frame(maxWidth: .infinity, alignment: .leading)
                .font(.customFont(font: .inter, style: .semiBold, size: .h0))
                .foregroundStyle(Color("ColorStone950"))
            Text(issuer ?? "")
                .multilineTextAlignment(.leading)
                .frame(maxWidth: .infinity, alignment: .leading)
                .font(.customFont(font: .inter, style: .semiBold, size: .h3))
                .foregroundStyle(Color("ColorStone600"))
            Divider()
            if credentialPack != nil && credentialStatus != nil {
                Card(
                    credentialPack: credentialPack!,
                    rendering: CardRendering.details(
                        CardRenderingDetailsView(
                            fields: [
                                CardRenderingDetailsField(
                                    keys: [],
                                    formatter: { (values) in
                                        let credential =
                                            values.first(where: {
                                                let credential =
                                                credentialPack!.get(
                                                        credentialId: $0.key)
                                                return credential?.asJwtVc()
                                                    != nil
                                                    || credential?.asJsonVc()
                                                        != nil
                                                    || credential?.asSdJwt()
                                                        != nil
                                            }).map { $0.value } ?? [:]

                                        return VStack(
                                            alignment: .leading, spacing: 20
                                        ) {
                                            CredentialStatus(
                                                status: credentialStatus)
                                            CredentialObjectDisplayer(
                                                dict: credential
                                            )
                                            .padding(.horizontal, 4)
                                        }
                                    })
                            ]
                        ))
                )
                .padding(.all, 12)
            }
            Button {
                onClose()
            } label: {
                Text("Close")
                    .frame(width: UIScreen.screenWidth)
                    .padding(.horizontal, -20)
                    .font(.customFont(font: .inter, style: .medium, size: .h4))
            }
            .foregroundColor(.black)
            .padding(.vertical, 13)
            .cornerRadius(8)
            .overlay(
                RoundedRectangle(cornerRadius: 8)
                    .stroke(Color("ColorStone300"), lineWidth: 1)
            )

        }
        .padding(20)
        .padding(.top, 20)
        .navigationBarBackButtonHidden(true)
        .onAppear(perform: {
            Task {
                do {
                    self.credentialPack = try addCredential(
                        credentialPack: CredentialPack(),
                        rawCredential: rawCredential)

                    var credentialStatus = CredentialStatusList.undefined

                    let statusLists = try await credentialPack.unwrap().getStatusListsAsync(
                        hasConnection: true)
                    if !statusLists.isEmpty {
                        credentialStatus =
                            statusLists.first?.value
                            ?? CredentialStatusList.unknown
                    }
                    
                    self.credentialStatus = credentialStatus

                    let credential = try credentialPack.unwrap().list().first
                    let claims = try credentialPack.unwrap().findCredentialClaims(
                        claimNames: ["name", "type", "description", "issuer"]
                    )[credential.unwrap().id()]

                    var tmpTitle = claims?["name"]?.toString()
                    if tmpTitle == nil {
                        claims?["type"]?.arrayValue?.forEach {
                            if $0.toString() != "VerifiableCredential" {
                                tmpTitle = $0.toString().camelCaseToWords()
                                return
                            }
                        }
                    }
                    self.title = tmpTitle ?? ""

                    if let issuerName = claims?["issuer"]?.dictValue?["name"]?
                        .toString()
                    {
                        self.issuer = issuerName
                    } else {
                        self.issuer = ""
                    }
                    logVerification(
                        title ?? "", issuer ?? "", credentialStatus.rawValue)
                } catch {
                    self.title = ""
                    self.issuer = ""
                }
            }
        })
    }
}

struct VerifierGenericCredentialItemSuccess {

}
