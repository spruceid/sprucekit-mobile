import SwiftUI

struct SettingsHomeItem: View {
    let image: String
    let title: String
    let description: String

    var body: some View {
        HStack(alignment: .top) {
            VStack {
                HStack {
                    Image(image)
                        .foregroundColor(Color("ColorStone950"))
                    Text(title)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .foregroundColor(Color("ColorStone950"))
                        .font(
                            .customFont(
                                font: .inter, style: .bold, size: .h4))
                }
                Text(description)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .multilineTextAlignment(.leading)
                    .foregroundColor(Color("ColorStone600"))
                    .font(
                        .customFont(font: .inter, style: .regular, size: .p)
                    )
            }
            Image("Chevron")
                .rotationEffect(.degrees(-90))
        }
        .padding(.bottom, 20)
    }
}
