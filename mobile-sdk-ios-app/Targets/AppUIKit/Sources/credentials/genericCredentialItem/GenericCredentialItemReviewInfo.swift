import SpruceIDMobileSdk
import SwiftUI

struct GenericCredentialItemReviewInfo: View {
    @EnvironmentObject private var statusListObservable: StatusListObservable
    let credentialPack: CredentialPack
    let customItemListItem: (() -> any View)?
    let customCredentialItemDetails: (() -> any View)?

    init(
        credentialPack: CredentialPack,
        customItemListItem: (() -> any View)? = nil,
        customCredentialItemDetails: (() -> any View)? = nil
    ) {
        self.credentialPack = credentialPack
        self.customItemListItem = customItemListItem
        self.customCredentialItemDetails = customCredentialItemDetails
    }

    var body: some View {
        VStack {
            Text("Review Info")
                .font(.customFont(font: .inter, style: .bold, size: .h0))
                .padding(.horizontal, 20)
                .foregroundStyle(Color("ColorStone950"))
            VStack {
                if customItemListItem != nil {
                    AnyView(customItemListItem!())
                } else {
                    GenericCredentialItemListItem(
                        credentialPack: credentialPack,
                        onDelete: nil,
                        withOptions: false
                    )
                }
            }
            .frame(height: 100)
            .padding(.horizontal, 12)
            ScrollView(.vertical, showsIndicators: false) {
                VStack {
                    if customCredentialItemDetails != nil {
                        AnyView(customCredentialItemDetails!())
                    } else {
                        GenericCredentialItemDetails(
                            credentialPack: credentialPack)
                    }
                }
            }
        }
    }
}
