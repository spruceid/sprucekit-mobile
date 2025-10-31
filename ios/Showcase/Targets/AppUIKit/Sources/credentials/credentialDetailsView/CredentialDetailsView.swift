import SpruceIDMobileSdk
import SpruceIDMobileSdkRs
import SwiftUI

struct CredentialDetails: Hashable {
    var credentialPackId: String
}

struct CredentialDetailsViewTab {
    let image: String
}

enum CredentialMode {
    case NONE
    case SCAN
    case SHARE
}

struct CredentialDetailsView: View {
    @EnvironmentObject private var credentialPackObservable:
    CredentialPackObservable
    @EnvironmentObject private var statusListObservable: StatusListObservable
    @Binding var path: NavigationPath
    let credentialPackId: String
    
    
    @State var credentialTitle: String = ""
    @State var credentialPack: CredentialPack?
    @State var credentialItem: (any ICredentialView)?
   
    @State var currentMode: CredentialMode = CredentialMode.NONE
    @State var hasMdocSupport: Bool = false
    @State var showCredentialOptions: Bool = false
    @State var showDetailsModal: Bool = false
    @State var showActivityLogModal: Bool = false

    func onBack() {
        // Immediatly disable scan mode to cleanup camera faster
        if (currentMode == CredentialMode.SCAN) {
            currentMode = CredentialMode.NONE
        }
        path.removeLast()
    }
    
    func onDelete() {
        Task {
            do {
                if let pack = credentialPack {
                    try await credentialPackObservable.delete(
                        credentialPack: pack
                    )
                    pack.list()
                        .forEach { credential in
                            let credentialInfo = getCredentialIdTitleAndIssuer(
                                credentialPack: pack,
                                credential: credential
                            )
                            _ = WalletActivityLogDataStore.shared.insert(
                                credentialPackId: pack.id.uuidString,
                                credentialId: credentialInfo.0,
                                credentialTitle: credentialInfo.1,
                                issuer: credentialInfo.2,
                                action: "Deleted",
                                dateTime: Date(),
                                additionalInformation: ""
                            )
                        }
                    // Toast?
                }
            } catch {
                // TODO: display error message
                print(error)
            }
        }
    }

    var body: some View {
        ZStack {
            VStack {
                VStack {
                    if (
                        currentMode == CredentialMode.SCAN || currentMode == CredentialMode.SHARE
                    ) {
                        CompactCredentialInfo(
                            credentialPack: credentialPack
                        )
                    } else {
                        if let item = credentialItem {
                            AnyView(
                                item.credentialListItem(withOptions: false)
                            )
                        }
                    }
                }
                .padding(.horizontal, 24)
                .padding(.top, 30)

                ZStack {
                    if (currentMode == CredentialMode.SCAN){
                        ScanModeContent(path: $path, credentialPackId: credentialPackId)
                    } else if (currentMode == CredentialMode.SHARE) {
                        ShareModeContent(
                            credentialPack: credentialPack,
                            genericCredentialDetailsShareQRCode: { pack in
                                GenericCredentialDetailsShareQRCode(credentialPack: pack)
                            }
                        )
                        .padding(.horizontal, 34)
                    } else {
                        ZStack(alignment: .center) {
                            Text("Scan to verify or share your credential")
                                .foregroundColor(Color("ColorStone400"))
                                .frame(alignment: .center)
                        }
                        .padding(.horizontal, 34)
                    }
                }
                .frame(maxHeight: .infinity)

                // Buttons + Close button (footer) - always visible at bottom
                CredentialDetailFooter(
                    selectedTab: currentMode,
                    hasShareSupport: hasMdocSupport,
                    onScanClick: {
                        var transaction = Transaction()
                        transaction.disablesAnimations = true
                        withTransaction(transaction) {
                            if (currentMode == CredentialMode.SCAN) {
                                currentMode = CredentialMode.NONE
                            } else {
                                currentMode = CredentialMode.SCAN
                            }
                        }
                    },
                    onShareClick: {
                        var transaction = Transaction()
                        transaction.disablesAnimations = true
                        withTransaction(transaction) {
                            if (currentMode == CredentialMode.SHARE) {
                                currentMode = CredentialMode.NONE
                            } else {
                                currentMode = CredentialMode.SHARE
                            }
                        }
                    },
                    onDetailsClick: { showDetailsModal = true },
                    onActivityLogClick: { showActivityLogModal = true },
                    onMoreClick: { showCredentialOptions = true },
                    onCloseClick: { onBack() }
                )
                .padding(.horizontal, 34)
               
            }
            
        }
        .background(Color("ColorBase50"))
        .confirmationDialog(
            Text("Credential Options"),
            isPresented: $showCredentialOptions,
            titleVisibility: .visible,
            actions: {
                if let pack = credentialPack {
                    CredentialOptionsDialogActions(
                        onDelete: {
                            onDelete()
                            onBack()
                        },
                        exportFileName: "\(credentialTitle).json",
                        credentialPack: pack
                    )
                }
            }
        )
        .sheet(isPresented: $showDetailsModal) {
            DetailsModal(
                credentialItem: credentialItem,
                statusList: statusListObservable.statusLists[credentialPackId],
                onClose: { showDetailsModal = false },
                onBack: onBack
            )
            .presentationDragIndicator(.visible)
        }
        .sheet(isPresented: $showActivityLogModal) {
            ActivityLogModal(
                credentialPackId: credentialPackId,
                onClose: { showActivityLogModal = false }
            )
            .presentationDragIndicator(.visible)
        }
        .onAppear {
            self.credentialPack =
            credentialPackObservable.getById(
                credentialPackId: credentialPackId
            ) ?? CredentialPack()
            
            self.hasMdocSupport = credentialPackHasMdoc(
                credentialPack: credentialPack!
            )
            
            credentialTitle =
            getCredentialIdTitleAndIssuer(credentialPack: credentialPack!).1
            Task {
                await statusListObservable.fetchAndUpdateStatus(
                    credentialPack: credentialPack!
                )
            }
            self.credentialItem = credentialDisplayerSelector(
                credentialPack: credentialPack!
            )
        }
        .edgesIgnoringSafeArea(.bottom)
        .navigationBarBackButtonHidden(true)
    }
}

struct GenericCredentialDetailsShareQRCode: View {
    let credentialPack: CredentialPack

    var body: some View {
        VStack {
            VStack {
                ShareMdocView(credentialPack: credentialPack)
            }
            .frame(width: 300, height: 300)
            .padding(12)
            .background(Color("ColorBase1"))
            .clipShape(RoundedRectangle(cornerRadius: 12))
            .overlay(
                RoundedRectangle(cornerRadius: 12)
                    .stroke(Color("ColorStone300"), lineWidth: 1)
            )
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}
