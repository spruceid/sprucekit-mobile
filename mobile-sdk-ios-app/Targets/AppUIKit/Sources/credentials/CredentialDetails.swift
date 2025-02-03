import SpruceIDMobileSdk
import SwiftUI

struct CredentialDetails: Hashable {
    var credentialPackId: String
}

struct CredentialDetailsView: View {
    @EnvironmentObject private var credentialPackObservable:
        CredentialPackObservable
    @EnvironmentObject private var statusListObservable: StatusListObservable
    @Binding var path: NavigationPath
    let credentialPackId: String
    @State var credentialPack: CredentialPack?
    @State var credentialItem: (any ICredentialView)?

    func onBack() {
        path.removeLast()
    }

    var body: some View {
        VStack {
            if self.credentialItem != nil {
                HStack {
                    Image("Chevron")
                        .resizable()
                        .aspectRatio(contentMode: .fit)
                        .frame(width: 16, height: 16)
                        .rotationEffect(Angle(degrees: 90))
                        .foregroundColor(Color("ColorStone600"))
                        .onTapGesture {
                            onBack()
                        }
                    AnyView(
                        self.credentialItem!.credentialListItem(
                            withOptions: false))
                }
                .padding(.top, 12)
                Divider()
                    .padding(.top, 20)
                if CredentialStatusList.revoked
                    != statusListObservable.statusLists[credentialPackId]
                {
                    AnyView(self.credentialItem!.credentialDetails())
                } else {
                    AnyView(
                        self.credentialItem!.credentialRevokedInfo(onClose: {
                            onBack()
                        }))
                }

            }
        }
        .padding(.horizontal, 24)
        .onAppear {
            self.credentialPack =
                credentialPackObservable.getById(
                    credentialPackId: credentialPackId) ?? CredentialPack()
            Task {
                await statusListObservable.fetchAndUpdateStatus(
                    credentialPack: credentialPack!)
            }
            self.credentialItem = credentialDisplayerSelector(
                credentialPack: credentialPack!)
        }
        .navigationBarBackButtonHidden(true)
    }
}
