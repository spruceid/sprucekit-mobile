import SpruceIDMobileSdk
import SpruceIDMobileSdkRs
import SwiftUI

struct CompactCredentialInfo: View {
    let credentialPack: CredentialPack?
    @EnvironmentObject private var statusListObservable: StatusListObservable

    var body: some View {
        if let pack = credentialPack {
            let credentialInfo = getCredentialIdTitleAndIssuer(credentialPack: pack)
            let name = credentialInfo.1
            let issuer = credentialInfo.2 ?? "Unknown Issuer"
            let packId = pack.id.uuidString

            VStack(alignment: .leading, spacing: 6) {
                Text(name)
                    .font(.customFont(font: .inter, style: .semiBold, size: .h3))
                    .foregroundColor(Color("ColorStone950"))

                HStack {
                    Text(issuer)
                        .font(.customFont(font: .inter, style: .regular, size: .p))
                        .foregroundColor(Color("ColorStone600"))
                        .lineLimit(1)
                        .truncationMode(.tail)

                    Spacer()

                    let statusList = statusListObservable.statusLists[packId]
                    let displayStatus = (statusList == nil || statusList == .undefined) ? .valid : statusList!

                    CredentialStatusSmall(status: displayStatus)
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color("ColorBase50"))
            .cornerRadius(12)
        }
    }
}
