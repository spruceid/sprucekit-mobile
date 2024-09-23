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
            Text("Spruce Verifier")
                .font(.customFont(font: .inter, style: .bold, size: .h0))
                .padding(.leading, 36)
                .foregroundStyle(Color("TextHeader"))
            Spacer()
            Button {
                path.append(VerifierSettingsHome())
            } label: {
                ZStack {
                    RoundedRectangle(cornerRadius: 8)
                        .foregroundColor(Color("Primary"))
                        .frame(width: 36, height: 36)
                    Image("User")
                }
            }
            .padding(.trailing, 20)
        }
        .padding(.top, 10)
    }
}

struct VerifierHomeBody: View {
    @Binding var path: NavigationPath

    var body: some View {
            ScrollView(.vertical, showsIndicators: false) {
                HStack {
                    Text("REQUESTS")
                        .font(.customFont(font: .inter, style: .bold, size: .p))
                        .foregroundStyle(Color("TextOnPrimary"))
                    Spacer()
                }

                // SprucePass
                VerifierListItem(
                    title: "Driver's License Document",
                    description: "Verifies physical driver's licenses issued by the state of Utopia",
                    binary: true,
                    fields: 0
                ).onTapGesture {
                    path.append(VerifyDL())
                }

                // Over 21
                VerifierListItem(
                    title: "Employment Authorization Document",
                    description: "Verifies physical Employment Authorization issued by the state of Utopia",
                    binary: true,
                    fields: 0
                ).onTapGesture {
                    path.append(VerifyEA())
                }

                // VC
                VerifierListItem(
                    title: "Verifiable Credential",
                    description: "Verifies a Verifiable credential by reading the Verifiable Presentation QR Code",
                    binary: true,
                    fields: 0
                ).onTapGesture {
                    path.append(VerifyVC())
                }

                // MDoc
                VerifierListItem(
                    title: "MDoc",
                    description: "Verifies a MDoc by reading the Presentation QR Code",
                    binary: true,
                    fields: 0
                ).onTapGesture {
                    path.append(VerifyMDoc())
                }

            }
        .padding(.all, 24)
    }
}

struct VerifierListItem: View {

    let title: String
    let description: String
    let binary: Bool
    let fields: Int

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .center) {
                Text(title)
                    .font(.customFont(font: .inter, style: .semiBold, size: .h1))
                    .foregroundStyle(Color("TextHeader"))
                VerifierListItemTag(binary: binary, fields: fields)
                Spacer()
                Image("ArrowRight")
            }
            Text(description)
            Divider()
        }
        .padding(.vertical, 12)
    }
}

struct VerifierListItemTag: View {
    let binary: Bool
    let fields: Int

    var body: some View {
        if binary {
            Text("Binary")
                .foregroundStyle(Color("VerifierRequestBadgeBinaryText"))
                .padding(.vertical, 4)
                .padding(.horizontal, 12)
                .background(Color("VerifierRequestBadgeBinaryFill"))
                .cornerRadius(8)
                .overlay(
                    RoundedRectangle(cornerRadius: 8)
                        .stroke(Color("VerifierRequestBadgeBinaryBorder"), lineWidth: 1)
                )
        } else {
            Text("\(fields) Fields")
                .foregroundStyle(Color("VerifierRequestBadgeFieldText"))
                .padding(.vertical, 4)
                .padding(.horizontal, 12)
                .background(Color("VerifierRequestBadgeFieldFill"))
                .cornerRadius(8)
                .overlay(
                    RoundedRectangle(cornerRadius: 8)
                        .stroke(Color("VerifierRequestBadgeFieldBorder"), lineWidth: 1)
                )
        }

    }
}

struct VerifierHomeViewPreview: PreviewProvider {
    @State static var path: NavigationPath = .init()

    static var previews: some View {
        VerifierHomeView(path: $path)
    }
}
