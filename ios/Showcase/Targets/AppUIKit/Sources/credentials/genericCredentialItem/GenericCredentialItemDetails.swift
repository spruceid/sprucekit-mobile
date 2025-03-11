import SpruceIDMobileSdk
import SwiftUI

struct GenericCredentialItemDetails: View {
    @EnvironmentObject private var statusListObservable: StatusListObservable
    let credentialPack: CredentialPack
    var body: some View {
        Card(
            credentialPack: credentialPack,
            rendering: CardRendering.details(
                CardRenderingDetailsView(
                    fields: [
                        CardRenderingDetailsField(
                            keys: [],
                            formatter: { (values) in
                                let credential =
                                    values.first(where: {
                                        let credential = credentialPack.get(
                                            credentialId: $0.key)
                                        return credential?.asJwtVc() != nil
                                            || credential?.asJsonVc() != nil
                                            || credential?.asSdJwt() != nil
                                            || credential?.asMsoMdoc() != nil
                                            || credential?.asCwt() != nil
                                    }).map { $0.value } ?? [:]

                                return VStack(alignment: .leading, spacing: 20) {
                                    CredentialStatus(
                                        status:
                                            statusListObservable.statusLists[
                                                credentialPack.id.uuidString]
                                    )
                                    CredentialObjectDisplayer(dict: credential)
                                        .padding(.horizontal, 4)
                                }
                            })
                    ]
                ))
        )
        .padding(.all, 12)
        .padding(.horizontal, 8)
    }
}
