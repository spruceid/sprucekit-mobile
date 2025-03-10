import SpruceIDMobileSdk
import SwiftUI

struct GenericCredentialItemPreviewAndDetails: View {
    @EnvironmentObject private var statusListObservable: StatusListObservable
    let credentialPack: CredentialPack
    let goTo: (() -> Void)?
    let onDelete: (() -> Void)?
    let customItemListItem: (() -> any View)?
    let customItemRevokedInfo: (() -> any View)?
    @Binding var sheetOpen: Bool
    @State var optionsOpen: Bool = false

    init(
        credentialPack: CredentialPack,
        goTo: (() -> Void)? = nil,
        onDelete: (() -> Void)? = nil,
        customItemListItem: (() -> any View)? = nil,
        customItemRevokedInfo: (() -> any View)? = nil,
        sheetOpen: Binding<Bool>
    ) {
        self.credentialPack = credentialPack
        self.goTo = goTo
        self.onDelete = onDelete
        self.customItemListItem = customItemListItem
        self.customItemRevokedInfo = customItemRevokedInfo
        self._sheetOpen = sheetOpen
    }

    var body: some View {
        VStack {
            if customItemListItem != nil {
                AnyView(customItemListItem!())
            } else {
                GenericCredentialItemListItem(
                    credentialPack: credentialPack,
                    onDelete: onDelete,
                    withOptions: true
                )
            }
        }
        .padding(.all, 12)
        .onTapGesture {
            if case CredentialStatusList.revoked =
                statusListObservable.statusLists[
                    credentialPack.id.uuidString] ?? .undefined {
                sheetOpen.toggle()
            } else {
                goTo?()
            }
        }
        .sheet(isPresented: $sheetOpen) {
            VStack {
                if customItemRevokedInfo != nil {
                    AnyView(customItemRevokedInfo!())
                } else {
                    GenericCredentialItemRevokedInfo(
                        credentialPack: credentialPack,
                        onClose: {
                            sheetOpen = false
                        }
                    )
                }
            }
            .padding(.top, 36)
            .presentationDetents([.fraction(0.40)])
            .presentationDragIndicator(.visible)
            .presentationBackgroundInteraction(.automatic)
        }
    }
}
