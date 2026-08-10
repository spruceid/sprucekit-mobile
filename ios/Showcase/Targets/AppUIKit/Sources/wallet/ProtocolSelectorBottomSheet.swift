import SwiftUI
import SpruceIDMobileSdkRs

struct ProtocolSelectorBottomSheet: View {
    // The scheme for the OID4VP QR code.
    let OID4VP_SCHEME = "openid4vp://"
    // The scheme for the OID4VCI QR code.
    let OID4VCI_SCHEME = "openid-credential-offer://"
    // The scheme for the Mdoc OID4VP QR code.
    let MDOC_OID4VP_SCHEME = "mdoc-openid4vp://"

    @Binding var protocols: [String: String]
    @Binding var sheetOpen: Bool
    @Binding var path: NavigationPath
    var credentialPackId: String?
    let order: [String] = [
        "OID4VCI",
        "OID4VP",
        "vcapi",
    ]
    let SUPPORTED_PROTOCOLS = [
        "OID4VCI",
        "OID4VP",
        "vcapi",
    ]
    let PROTOCOL_DESCRIPTIONS = [
        "OID4VCI": "OpenID for Verifiable Credential Issuance",
        "OID4VP": "OpenID for Verifiable Presentations",
        "vcapi": "VCALM exchange (VC-API)",
    ]
    var keys: [String] {
        let orderLower = order.map { $0.lowercased() }
        let protocolKeysLower = Set(protocols.keys.map { $0.lowercased() })

        let fullList =
            order.filter { protocolKeysLower.contains($0.lowercased()) }
            + protocols.keys.filter { !orderLower.contains($0.lowercased()) }

        return fullList.filter { $0.lowercased() != "inviterequest" }
    }

    func handleProtocolSelection(_ protocolName: String, _ uri: String) {
        sheetOpen = false
        switch protocolName {
        case "OID4VCI":
            path.append(HandleOID4VCI(url: uri))
        case "OID4VP":
            if uri.hasPrefix(OID4VP_SCHEME) {
                path.append(
                    HandleOID4VP(
                        url: uri,
                        credentialPackId: credentialPackId
                    )
                )
            } else if uri.hasPrefix(MDOC_OID4VP_SCHEME) {
                path.append(
                    HandleMdocOID4VP(
                        url: uri,
                        credentialPackId: credentialPackId
                    )
                )
            }
        case "vcapi", _:
            path.append(HandleVCALM(url: uri))
        }
    }

    var body: some View {

        VStack {
            ScrollView {
                Text("Choose how to continue")
                .foregroundStyle(Color("ColorStone950"))
                Text(
                    "This issuer supports more than one way to continue. Pick one."
                )
                VStack(spacing: 12) {
                    ForEach(keys, id: \.self) {
                        name in
                        let isSupported = SUPPORTED_PROTOCOLS.contains(name)
                        Button(action: {
                            handleProtocolSelection(name, protocols[name] ?? "")
                        }) {
                            
                            HStack(spacing: 12) {
                                VStack(alignment: .leading, spacing: 4) {
                                    Text(name)
                                    .foregroundStyle(
                                        isSupported
                                            ? Color("ColorStone700")
                                            : Color("ColorStone200")
                                    )

                                    if let desc = PROTOCOL_DESCRIPTIONS[
                                        name
                                    ] {
                                        Text(desc)
                                        .foregroundStyle(
                                            isSupported
                                                ? Color("ColorStone700")
                                                : Color("ColorStone200")
                                        )
                                    }
                                }
                                Spacer(minLength: 0)
                                Image(systemName: "chevron.right")
                                    .font(
                                        .system(size: 24, weight: .regular)
                                    )
                                    .foregroundColor(
                                        isSupported
                                            ? Color("ColorStone700")
                                            : Color("ColorStone200")
                                    )

                            }
                            .padding(20)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .cornerRadius(8)
                            .background(Color("ColorStone50"))
                            .overlay(
                                RoundedRectangle(cornerRadius: 8)
                                    .stroke(
                                        Color("ColorStone300"),
                                        lineWidth: 1
                                    )
                            )
                        }.disabled(
                            !isSupported
                        )
                    }
                }
                .padding(.horizontal, 10)
            }

            Button {
                sheetOpen = false
            } label: {
                Text("Cancel")
                    .frame(width: UIScreen.screenWidth)
                    .padding(.horizontal, -20)
            }
            .foregroundColor(Color("ColorStone950"))
            .padding(.vertical, 13)
            .overlay(
                RoundedRectangle(cornerRadius: 8)
                    .stroke(Color("ColorStone300"), lineWidth: 1)
            )
            .padding(.top, 10)

        }
    }
}
