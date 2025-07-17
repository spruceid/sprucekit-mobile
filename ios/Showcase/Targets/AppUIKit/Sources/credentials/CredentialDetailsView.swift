import SpruceIDMobileSdk
import SwiftUI

struct CredentialDetails: Hashable {
    var credentialPackId: String
}

struct CredentialDetailsViewTab {
    let image: String
}

struct CredentialDetailsView: View {
    @EnvironmentObject private var credentialPackObservable: CredentialPackObservable
    @EnvironmentObject private var statusListObservable: StatusListObservable
    @Binding var path: NavigationPath
    let credentialPackId: String
    @State var credentialTitle: String = ""
    @State var credentialPack: CredentialPack?
    @State var credentialItem: (any ICredentialView)?
    @State var credentialDetailsViewTabs = [
        CredentialDetailsViewTab(image: "Info")
    ]
    @State private var selectedTab = 0

    func onBack() {
        path.removeLast()
    }

    var body: some View {
        VStack {
            HStack {
                Image("Chevron")
                    .resizable()
                    .aspectRatio(contentMode: .fit)
                    .frame(width: 16, height: 16)
                    .rotationEffect(Angle(degrees: 90))
                    .foregroundColor(Color("ColorStone600"))
                Text(credentialTitle)
                    .font(
                        .customFont(
                            font: .inter, style: .semiBold,
                            size: .h1)
                    )
                    .foregroundStyle(Color("ColorStone950"))
                Spacer()
            }
            .onTapGesture {
                onBack()
            }
            .padding([.horizontal, .bottom], 20)
            .padding(.top, 40)

            VStack {
                Divider()
                TabView(selection: $selectedTab) {
                    ForEach(
                        Array(credentialDetailsViewTabs.enumerated()),
                        id: \.offset
                    ) { index, _ in
                        tabContent(for: index)
                            .tag(index)
                    }
                }
                .background(Color("ColorBase50"))
                .tabViewStyle(PageTabViewStyle(indexDisplayMode: .never))

                if credentialDetailsViewTabs.count > 1 {
                    tabIndicators
                }
            }
            .background(Color("ColorBase50"))
        }
        .onAppear {
            setupCredentialData()
        }
        .edgesIgnoringSafeArea(.bottom)
        .navigationBarBackButtonHidden(true)
    }

    @ViewBuilder
    private func tabContent(for index: Int) -> some View {
        if index == 0 {
            detailsTabContent
        } else if index == 1, let credPack = credentialPack {
            ShareMdocView(credentialPack: credPack, engagementType: .qr)
        } else if index == 2, let credPack = credentialPack {
            ShareMdocView(credentialPack: credPack, engagementType: .nfc)
        }
    }

    @ViewBuilder
    private var detailsTabContent: some View {
        VStack {
            if let credentialItem = credentialItem {
                if CredentialStatusList.revoked
                    != statusListObservable.statusLists[credentialPackId]
                {
                    AnyView(credentialItem.credentialDetails())
                } else {
                    AnyView(credentialItem.credentialRevokedInfo(onClose: { onBack() }))
                }
            }
        }
    }

    private var tabIndicators: some View {
        HStack {
            Spacer()
            ForEach(
                Array(credentialDetailsViewTabs.enumerated()),
                id: \.offset
            ) { index, tab in
                Button(action: { selectedTab = index }) {
                    Image(tab.image)
                        .resizable()
                        .frame(width: 32, height: 32)
                        .foregroundColor(
                            selectedTab == index
                                ? Color("ColorBlue600")
                                : Color("ColorBase600")
                        )
                        .overlay(
                            Rectangle()
                                .frame(height: 4)
                                .foregroundColor(
                                    selectedTab == index
                                        ? Color("ColorBlue600")
                                        : Color("ColorBase50")
                                )
                                .offset(y: -4),
                            alignment: .top
                        )
                }
                .padding(.horizontal, 12)
            }
            Spacer()
        }
        .frame(height: 50)
        .padding(.bottom, 20)
    }

    private func setupCredentialData() {
        self.credentialPack =
            credentialPackObservable.getById(
                credentialPackId: credentialPackId) ?? CredentialPack()
        if credentialPackHasMdoc(credentialPack: credentialPack!) {
            credentialDetailsViewTabs.append(
                CredentialDetailsViewTab(image: "QRCode")
            )
            credentialDetailsViewTabs.append(
                CredentialDetailsViewTab(image: "NFC")
            )
        }
        credentialTitle =
            getCredentialIdTitleAndIssuer(credentialPack: credentialPack!).1
        Task {
            await statusListObservable.fetchAndUpdateStatus(
                credentialPack: credentialPack!)
        }
        self.credentialItem = credentialDisplayerSelector(
            credentialPack: credentialPack!)
    }
}
