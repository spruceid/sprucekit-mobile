import SwiftUI
import SpruceIDMobileSdk
import SpruceIDMobileSdkRs
import CoreImage.CIFilterBuiltins

struct GenericCredentialListItem: View {
    let credentialPack = CredentialPack()
    let vc: String
    @State var sheetOpen: Bool = false

    init(vc: String) {
        self.vc = vc
        do {
            _ = try credentialPack.addW3CVC(credentialString: vc)
        } catch {
            print(error.localizedDescription)
        }
    }

    @ViewBuilder
    func descriptionFormatter(values: [String: [String: GenericJSON]]) -> some View {
        let w3cvc = values
            .first(where: { credentialPack.get(credentialId: $0.key) is W3CVC })
            .map { $0.value } ?? [:]

        VStack(alignment: .leading, spacing: 12) {
            Text(w3cvc["description"]?.toString() ?? "")
                .font(.customFont(font: .inter, style: .regular, size: .p))
                .foregroundStyle(Color("TextBody"))
                .padding(.top, 6)
            Spacer()
            if w3cvc["valid"]?.toString() == "true" {
                HStack {
                    Image("Valid")
                    Text("Valid")
                        .font(.customFont(font: .inter, style: .medium, size: .xsmall))
                        .foregroundStyle(Color("GreenValid"))
                }
            }
        }
        .padding(.leading, 12)
    }

    @ViewBuilder
    var cardList: some View {
        Card(
            credentialPack: credentialPack,
            rendering: CardRendering.list(CardRenderingListView(
                titleKeys: ["name"],
                titleFormatter: { (values) in
                    let w3cvc = values
                        .first(where: { credentialPack.get(credentialId: $0.key) is W3CVC })
                        .map { $0.value } ?? [:]
                    return VStack(alignment: .leading, spacing: 12) {
//                        HStack {
//                            Spacer()
//                            Image("ThreeDotsHorizontal")
//                        }
                        Text(w3cvc["name"]?.toString() ?? "")
                            .font(.customFont(font: .inter, style: .semiBold, size: .h1))
                            .foregroundStyle(Color("TextHeader"))
                    }
                    .padding(.leading, 12)

                },
                descriptionKeys: ["description", "valid"],
                descriptionFormatter: descriptionFormatter,
                leadingIconKeys: ["issuer"],
                leadingIconFormatter: { (values) in
                    let w3cvc = values
                        .first(where: { credentialPack.get(credentialId: $0.key) is W3CVC })
                        .map { $0.value } ?? [:]
                    let img = w3cvc["issuer"]?.dictValue?["image"]?.toString() ?? ""

                    return Image(base64String: img.replacingOccurrences(of: "data:image/png;base64,", with: ""))
                            .frame(width: 70, height: 0)
                            .scaleEffect(CGSize(width: 0.2, height: 0.2))

                }
            ))
        )

    }

    @ViewBuilder
    var cardDetails: some View {
        Card(
            credentialPack: credentialPack,
            rendering: CardRendering.details(CardRenderingDetailsView(
                fields: [
                    CardRenderingDetailsField(
                        keys: ["credentialSubject"],
                        formatter: { (values) in
                            let w3cvc = values
                                .first(where: { credentialPack.get(credentialId: $0.key) is W3CVC })
                                .map { $0.value } ?? [:]
                            let credentialSubject = w3cvc["credentialSubject"]?.dictValue ?? [:]

                            var portrait = ""
                            var firstName = ""
                            var lastName = ""
                            var birthDate = ""

                            if credentialSubject["driversLicense"] != nil {
                                let dl = credentialSubject["driversLicense"]?.dictValue

                                portrait = dl?["portrait"]?.toString() ?? ""
                                firstName = dl?["given_name"]?.toString() ?? ""
                                lastName = dl?["family_name"]?.toString() ?? ""
                                birthDate = dl?["birth_date"]?.toString() ?? ""

                            } else {
                                portrait = credentialSubject["image"]?.toString() ?? ""
                                firstName = credentialSubject["givenName"]?.toString() ?? ""
                                lastName = credentialSubject["familyName"]?.toString() ?? ""
                                birthDate = credentialSubject["birthDate"]?.toString() ?? ""
                            }

                            return HStack {
                                VStack(alignment: .leading) {
                                    Text("Portrait")
                                        .font(.customFont(font: .inter, style: .regular, size: .p))
                                        .foregroundStyle(Color("TextBody"))
                                    Image(base64String: portrait.replacingOccurrences(of: "data:image/png;base64,", with: ""))
                                            .frame(width: 100, height: 140)
                                            .scaleEffect(CGSize(width: 0.4, height: 0.4))
                                }
                                Spacer()
                                VStack(alignment: .leading, spacing: 20) {
                                    VStack(alignment: .leading) {
                                        Text("First Name")
                                            .font(.customFont(font: .inter, style: .regular, size: .p))
                                            .foregroundStyle(Color("TextBody"))
                                        Text(firstName)
                                    }

                                    VStack(alignment: .leading) {
                                        Text("Last Name")
                                            .font(.customFont(font: .inter, style: .regular, size: .p))
                                            .foregroundStyle(Color("TextBody"))
                                        Text(lastName)
                                    }

                                    VStack(alignment: .leading) {
                                        Text("Birth Date")
                                            .font(.customFont(font: .inter, style: .regular, size: .p))
                                            .foregroundStyle(Color("TextBody"))
                                        Text(birthDate)
                                    }
                                }
                                Spacer()
                            }
                            .padding(.horizontal, 40)
                        }),
                    CardRenderingDetailsField(
                        keys: ["issuanceDate"],
                        formatter: { (values) in
                            let w3cvc = values
                                .first(where: { credentialPack.get(credentialId: $0.key) is W3CVC })
                                .map { $0.value } ?? [:]
                            return HStack {
                                VStack(alignment: .leading) {
                                    Text("Issuance Date")
                                        .font(.customFont(font: .inter, style: .regular, size: .p))
                                        .foregroundStyle(Color("TextBody"))
                                    Text(w3cvc["issuanceDate"]?.toString() ?? "")
                                }
                                Spacer()
                            }
                            .padding(.horizontal, 40)
                            .padding(.top, 12)
                        })
                ]
            ))
        )
        .padding(.all, 12)
    }

    var body: some View {
        VStack {
            VStack {
                cardList
                    .padding(.top, 12)
                    .padding(.horizontal, 12)
                    .onTapGesture {
                        sheetOpen.toggle()
                    }
                GenericCredentialListItemQRCode()
            }
            .overlay(
                RoundedRectangle(cornerRadius: 8)
                    .stroke(Color("CredentialBorder"), lineWidth: 1)
            )
            .padding(.all, 12)

        }
        .sheet(isPresented: $sheetOpen) {

        } content: {
            VStack {
                Text("Review Info")
                    .font(.customFont(font: .inter, style: .bold, size: .h0))
                    .foregroundStyle(Color("TextHeader"))
                    .padding(.top, 25)
                cardList
                    .frame(height: 120)
                    .padding(.top, 12)
                    .padding(.horizontal, 12)
                    .overlay(
                        RoundedRectangle(cornerRadius: 8)
                            .stroke(Color("CredentialBorder"), lineWidth: 1)
                    )
                    .padding(.all, 12)
                cardDetails
            }

            .presentationDetents([.fraction(0.85)])
            .presentationDragIndicator(.automatic)
            .presentationBackgroundInteraction(.automatic)

        }
    }

}

struct GenericCredentialListItemQRCode: View {
    let vc: String
    @State private var showingQRCode = false
    @State private var vp: String?

    let context = CIContext()
    let filter = CIFilter.qrCodeGenerator()
    let transform = CGAffineTransform(scaleX: 20, y: 20)

    init() {
        self.vc = small_vc
    }

    func generateQRCode(from string: String) -> UIImage {
        filter.message = Data(string.utf8)

        if let outputImage = filter.outputImage?.transformed(by: transform) {
            if let cgImage = context.createCGImage(outputImage, from: outputImage.extent) {
                return UIImage(cgImage: cgImage)
            }
        }

        return UIImage(systemName: "xmark.circle") ?? UIImage()
    }

    var body: some View {
        ZStack {
            Rectangle()
                .foregroundColor(Color("Primary"))
                .edgesIgnoringSafeArea(.all)
            VStack(spacing: 0) {
                HStack {
                    Spacer()
                    HStack {
                        Image("QRCode")
                        Text(showingQRCode ? "Hide QR code" : "Show QR code")
                            .font(.customFont(font: .inter, style: .regular, size: .xsmall))
                            .foregroundStyle(Color("TextBody"))
                    }
                    Spacer()
                }
                .padding(.vertical, 12)
                .onTapGesture {
                    Task {
                        do {
                            vp = try await vcToSignedVp(vc: vc, keyStr: ed25519_2020_10_18)
                        } catch {
                            print(error)
                        }

                    }
                    showingQRCode.toggle()
                }
                if showingQRCode && vp != nil {
                    Image(uiImage: generateQRCode(from: vp!))
                        .resizable()
                        .scaledToFit()
                        .frame(width: 290, height: 290)

                    Text("Shares your credential online or \n in-person, wherever accepted.")
                        .font(.customFont(font: .inter, style: .regular, size: .small))
                        .foregroundStyle(Color("TextOnPrimary"))
                        .padding(.vertical, 12)
                }
            }
            .padding(.horizontal, 12)
        }
    }
}
