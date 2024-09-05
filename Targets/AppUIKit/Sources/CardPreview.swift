import SwiftUI
import SpruceIDMobileSdk

struct CardPreviewData {
    var credentialPack = CredentialPack()

    init() {
        do {
            _ = try credentialPack.addW3CVC(credentialString: jsonstring)
            _ = try credentialPack.addMDoc(mdocBase64: mdocBase64)

        } catch {
            print(error.localizedDescription)
        }
    }
}

struct CardPreview: PreviewProvider {
    static var previewData = CardPreviewData()

    static var listRendering = CardRendering.list(CardRenderingListView(
        titleKeys: ["name"],
        descriptionKeys: ["created", "issuanceDate"],
        leadingIconFormatter: { (_) in
            Image(systemName: "scribble")
        }
    ))

    static var detailsRendering = CardRendering.details(CardRenderingDetailsView(
        fields: [
            CardRenderingDetailsField(
                keys: ["name"]
            ),
            CardRenderingDetailsField(
                keys: ["issuanceDate", "expirationDate"],
                formatter: { (values: [String: [String: GenericJSON]]) in
                    let w3cvc = values
                        .first(where: { previewData.credentialPack.get(credentialId: $0.key) is W3CVC })
                        .map { $0.value } ?? [:]

                    return Text("\(w3cvc["issuanceDate"]?.toString() ?? "") - \(w3cvc["expirationDate"]?.toString() ?? "")")
                }
            )
        ]
    ))

    static var previews: some View {
        VStack {
            Card(credentialPack: previewData.credentialPack, rendering: listRendering)
                .padding(12)
            Card(credentialPack: previewData.credentialPack, rendering: detailsRendering)
        }

    }
}
