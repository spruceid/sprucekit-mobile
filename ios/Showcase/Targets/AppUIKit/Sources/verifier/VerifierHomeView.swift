import SwiftUI

struct VerifierHomeView: View {
    @Binding var path: NavigationPath

    var body: some View {
        VStack {
            VerifierHomeHeader(path: $path)
            VerifierHomeBody(path: $path)
        }
        .navigationBarBackButtonHidden(true)
    }
}

struct VerifierHomeHeader: View {
    @Binding var path: NavigationPath

    var body: some View {
        HStack {
            Text("Verifier")
                .font(.customFont(font: .inter, style: .bold, size: .h2))
                .padding(.leading, 30)
                .foregroundStyle(Color("ColorStone950"))
            Spacer()
            Button {
                path.append(VerifierSettingsHome())
            } label: {
                ZStack {
                    RoundedRectangle(cornerRadius: 8)
                        .foregroundColor(Color("ColorBase150"))
                        .frame(width: 36, height: 36)
                    Image("Cog")
                        .foregroundColor(Color("ColorStone400"))
                }
            }
            .padding(.trailing, 20)
        }
        .padding(.top, 10)
    }
}

struct VerifierHomeBody: View {
    @Binding var path: NavigationPath

    @State var verificationMethods: [VerificationMethod] = []

    func getBadgeType(verificationType: String) -> VerifierListItemTagType {
        switch verificationType {
        case "DelegatedVerification":
            return VerifierListItemTagType.DISPLAY_QR_CODE
        default:
            return VerifierListItemTagType.SCAN_QR_CODE
        }
    }

    var body: some View {
        ScrollView(.vertical, showsIndicators: false) {
            HStack {
                Text("VERIFICATIONS")
                    .font(.customFont(font: .inter, style: .bold, size: .p))
                    .foregroundStyle(Color("ColorStone400"))
                Spacer()
                Text("+ New Verification")
                    .font(
                        .customFont(font: .inter, style: .semiBold, size: .h4)
                    )
                    .foregroundStyle(Color("ColorBlue600"))
                    .onTapGesture {
                        path.append(AddVerificationMethod())
                    }
            }

            VerifierListItem(
                title: "Driver's License Document",
                description:
                    "Verifies physical driver's licenses issued by the state of Utopia",
                type: VerifierListItemTagType.SCAN_QR_CODE
            ).onTapGesture {
                path.append(VerifyDL())
            }

            VerifierListItem(
                title: "Employment Authorization Document",
                description:
                    "Verifies physical Employment Authorization issued by the state of Utopia",
                type: VerifierListItemTagType.SCAN_QR_CODE
            ).onTapGesture {
                path.append(VerifyEA())
            }

            VerifierListItem(
                title: "Verifiable Credential",
                description:
                    "Verifies a Verifiable credential by reading the Verifiable Presentation QR Code",
                type: VerifierListItemTagType.SCAN_QR_CODE
            ).onTapGesture {
                path.append(VerifyVC())
            }

            VerifierListItem(
                title: "Mobile Driver's License",
                description:
                    "Verifies an ISO formatted mobile driver's license by reading a QR code",
                type: VerifierListItemTagType.SCAN_QR_CODE
            ).onTapGesture {
                path.append(VerifyMDoc())
            }

            VerifierListItem(
                title: "CWT",
                description:
                    "Verifies a CWT by reading a QR code",
                type: VerifierListItemTagType.SCAN_QR_CODE
            ).onTapGesture {
                path.append(VerifyCwt())
            }

            VerifierListItem(
                title: "Mobile Driver's License - Over 18",
                description:
                    "Verifies an ISO formatted mobile driver's license by reading a QR code",
                type: VerifierListItemTagType.SCAN_QR_CODE
            ).onTapGesture {
                path.append(VerifyMDoc(checkAgeOver18: true))
            }

            ForEach(verificationMethods, id: \.self.id) { verificationMethod in
                VerifierListItem(
                    title: verificationMethod.name,
                    description: verificationMethod.description,
                    type: getBadgeType(
                        verificationType: verificationMethod.type)
                ).onTapGesture {
                    path.append(
                        VerifyDelegatedOid4vp(id: verificationMethod.id))
                }
            }

        }
        .onAppear(perform: {
            self.verificationMethods = VerificationMethodDataStore.shared
                .getAllVerificationMethods()
        })
        .padding(.vertical, 20)
        .padding(.horizontal, 30)
    }
}

struct VerifierListItem: View {
    let title: String
    let description: String
    let type: VerifierListItemTagType

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .center) {
                Text(title)
                    .font(
                        .customFont(font: .inter, style: .semiBold, size: .h1)
                    )
                    .foregroundStyle(Color("ColorStone950"))
                Spacer()
                VerifierListItemTag(type: type)
            }
            Text(description)
            Divider()
        }
        .padding(.vertical, 12)
    }
}

enum VerifierListItemTagType {
    case DISPLAY_QR_CODE
    case SCAN_QR_CODE
}

struct VerifierListItemTag: View {
    let type: VerifierListItemTagType

    var body: some View {
        switch type {
        case VerifierListItemTagType.DISPLAY_QR_CODE:
            HStack {
                Image("QRCode")
                    .resizable()
                    .frame(width: 16, height: 16)
                    .foregroundColor(Color.white)
                Text("Display")
                    .font(.customFont(font: .inter, style: .semiBold, size: .p))
                    .foregroundStyle(Color.white)
                Image("ArrowTriangleRight")
            }
            .padding(.vertical, 6)
            .padding(.horizontal, 8)
            .background(Color("ColorPurple600"))
            .cornerRadius(100)
        case VerifierListItemTagType.SCAN_QR_CODE:
            HStack {
                Image("QRCodeReader")
                    .resizable()
                    .frame(width: 16, height: 16)
                    .foregroundColor(Color.white)
                Text("Scan")
                    .font(.customFont(font: .inter, style: .semiBold, size: .p))
                    .foregroundStyle(Color.white)
                Image("ArrowTriangleRight")
            }
            .padding(.vertical, 6)
            .padding(.horizontal, 8)
            .background(Color("ColorTerracotta600"))
            .cornerRadius(100)
        }
    }
}

struct VerifierHomeViewPreview: PreviewProvider {
    @State static var path: NavigationPath = .init()

    static var previews: some View {
        VerifierHomeView(path: $path)
    }
}
