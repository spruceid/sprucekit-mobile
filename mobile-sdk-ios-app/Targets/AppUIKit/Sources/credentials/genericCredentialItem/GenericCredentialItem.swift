import SpruceIDMobileSdk
import SpruceIDMobileSdkRs
import SwiftUI

struct GenericCredentialItem: ICredentialView {
    @EnvironmentObject private var statusListObservable: StatusListObservable
    let credentialPack: CredentialPack
    let goTo: (() -> Void)?
    let onDelete: (() -> Void)?

    @State var sheetOpen: Bool = false

    init(
        rawCredential: String,
        goTo: (() -> Void)? = nil,
        onDelete: (() -> Void)? = nil
    ) {
        self.goTo = goTo
        self.onDelete = onDelete
        do {
            self.credentialPack = try addCredential(
                credentialPack: CredentialPack(),
                rawCredential: rawCredential
            )
        } catch {
            print(error)
            self.credentialPack = CredentialPack()
        }
    }

    init(
        credentialPack: CredentialPack,
        goTo: (() -> Void)? = nil,
        onDelete: (() -> Void)? = nil
    ) {
        self.onDelete = onDelete
        self.goTo = goTo
        self.credentialPack = credentialPack
    }

    @ViewBuilder
    public func credentialDetails() -> any View {
        GenericCredentialItemDetails(credentialPack: credentialPack)
    }

    @ViewBuilder
    public func credentialListItem(withOptions: Bool = false) -> any View {
        GenericCredentialItemListItem(
            credentialPack: credentialPack,
            onDelete: onDelete,
            withOptions: withOptions
        )
    }

    @ViewBuilder
    public func credentialReviewInfo() -> any View {
        GenericCredentialItemReviewInfo(credentialPack: credentialPack)
    }

    @ViewBuilder
    func credentialRevokedInfo(onClose: @escaping () -> Void) -> any View {
        GenericCredentialItemRevokedInfo(
            credentialPack: credentialPack,
            onClose: onClose
        )
    }

    @ViewBuilder
    public func credentialPreviewAndDetails() -> any View {
        GenericCredentialItemPreviewAndDetails(
            credentialPack: credentialPack,
            goTo: goTo,
            onDelete: onDelete,
            sheetOpen: $sheetOpen
        )
    }

    var body: some View {
        AnyView(credentialPreviewAndDetails())
    }
}
