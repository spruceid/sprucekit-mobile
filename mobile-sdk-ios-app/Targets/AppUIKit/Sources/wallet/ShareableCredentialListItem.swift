import SwiftUI
import SpruceIDMobileSdk
import SpruceIDMobileSdkRs
import CoreImage.CIFilterBuiltins
import CryptoKit

struct ShareableCredentialListItem: View {
    let credentialPack = CredentialPack()
    let mdoc: String
    let mdocId: String?
    @State var sheetOpen: Bool = false
    
    init(mdoc: String) {
        self.mdoc = mdoc
        do {
            let keyAlias = UUID().uuidString
            let key = try P256.Signing.PrivateKey(pemRepresentation: keyPEM)
            let attributes = [kSecAttrKeyType: kSecAttrKeyTypeECSECPrimeRandom,
                             kSecAttrKeyClass: kSecAttrKeyClassPrivate] as [String: Any]
            let secKey = SecKeyCreateWithData(key.x963Representation as CFData,
                                              attributes as CFDictionary,
                                              nil)!
            let query = [kSecClass: kSecClassKey,
          kSecAttrApplicationLabel: keyAlias,
                kSecAttrAccessible: kSecAttrAccessibleWhenUnlocked,
     kSecUseDataProtectionKeychain: true,
                      kSecValueRef: secKey] as [String: Any]
            SecItemDelete(query as CFDictionary)
            _ = SecItemAdd(query as CFDictionary, nil)
            let credentials = try credentialPack.addMDoc(mdoc: Mdoc.fromStringifiedDocument(stringifiedDocument: mdoc, keyAlias: keyAlias))
            let mdoc = credentials.first(where: { $0.asMsoMdoc() != nil })
            self.mdocId = mdoc?.id()
        } catch {
            print(error.localizedDescription)
            self.mdocId = nil
        }
    }
    
    var body: some View {
        VStack {
            VStack {
                Text(mdocId!)
                    .padding(.top, 12)
                    .padding(.horizontal, 12)
                    .onTapGesture {
                        sheetOpen.toggle()
                    }
                ShareableCredentialListItemQRCode(credentials: credentialPack.get(credentialsIds: [mdocId!]))
            }
            .overlay(
                RoundedRectangle(cornerRadius: 8)
                    .stroke(Color("ColorBase300"), lineWidth: 1)
            )
            .padding(.all, 12)
            
        }
        .sheet(isPresented: $sheetOpen) {
            
        } content: {
            VStack {
                Text("Review Info")
                    .font(.customFont(font: .inter, style: .bold, size: .h0))
                    .foregroundStyle(Color("ColorStone950"))
                    .padding(.top, 25)
                Text(mdocId!)
            }
            
            .presentationDetents([.fraction(0.85)])
            .presentationDragIndicator(.automatic)
            .presentationBackgroundInteraction(.automatic)
            
        }
    }
    
}

struct ShareableCredentialListItemQRCode: View {
    let credentials: [ParsedCredential]
    @State private var showingQRCode = false
    @State private var qrSheetView: QRSheetView? = nil
    
    func getQRSheetView() async -> QRSheetView {
        return await QRSheetView(credentials: credentials)
    }
    
    var body: some View {
        ZStack {
            Rectangle()
                .foregroundColor(Color("ColorBase150"))
                .edgesIgnoringSafeArea(.all)
            VStack(spacing: 0) {
                HStack {
                    Spacer()
                    HStack {
                        Image("QRCode")
                        Text(showingQRCode ? "Hide QR code" : "Show QR code")
                            .font(.customFont(font: .inter, style: .regular, size: .xsmall))
                            .foregroundStyle(Color("ColorStone600"))
                    }
                    Spacer()
                }
                .padding(.vertical, 12)
                .onTapGesture {
                    showingQRCode.toggle()
                }
                if showingQRCode {
                    qrSheetView
                    
                    Text("Shares your credential online or \n in-person, wherever accepted.")
                        .font(.customFont(font: .inter, style: .regular, size: .small))
                        .foregroundStyle(Color("ColorStone400"))
                        .padding(.vertical, 12)
                        .task {
                            qrSheetView = await getQRSheetView()
                        }
                }
            }
            .padding(.horizontal, 12)
        }
    }
}

struct ShareableCredentialListItemPreview: PreviewProvider {
    static var previews: some View {
        ShareableCredentialListItem(mdoc: mdocBase64)
    }
}
