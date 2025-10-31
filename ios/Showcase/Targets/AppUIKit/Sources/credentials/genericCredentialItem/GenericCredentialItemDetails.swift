import SpruceIDMobileSdk
import SwiftUI

struct GenericCredentialItemDetails: View {
    @EnvironmentObject private var statusListObservable: StatusListObservable
    let credentialPack: CredentialPack

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                let credential = credentialPack.findCredentialClaims(claimNames: [])
                    .first(where: {
                        let cred = credentialPack.get(credentialId: $0.key)
                        return cred?.asJwtVc() != nil
                            || cred?.asJsonVc() != nil
                            || cred?.asSdJwt() != nil
                            || cred?.asMsoMdoc() != nil
                            || cred?.asCwt() != nil
                    })?.value ?? [:]

                CredentialStatus(
                    status: statusListObservable.statusLists[credentialPack.id.uuidString]
                )

                let displayViews = flattenedRowDisplayer(
                    object: credential,
                    filter: [
                        "type", "hashed", "salt", "proof", "renderMethod", "@context",
                        "credentialStatus", "-65537"
                    ]
                )

                ForEach(0..<displayViews.count, id: \.self) { index in
                    displayViews[index]
                }
            }
            .padding(.horizontal, 8)
        }
    }
}
