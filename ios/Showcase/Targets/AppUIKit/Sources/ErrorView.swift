import SwiftUI

struct ErrorView: View {
    let errorTitle: String
    let errorDetails: String
    var closeButtonLabel: String = "Close"
    let onClose: () -> Void

    @State var sheetOpen: Bool = false

    var body: some View {
        ZStack {
            VStack {
                Image("Error")
                    .padding(.top, 30)
                Text(errorTitle)
                    .font(.customFont(font: .inter, style: .bold, size: .h1))
                    .foregroundColor(Color("ColorRose700"))
                    .padding(.vertical, 10)
                Text("View technical details")
                    .font(.customFont(font: .inter, style: .regular, size: .h4))
                    .underline()
                    .multilineTextAlignment(.center)
                    .foregroundColor(Color("ColorStone600"))
                    .onTapGesture {
                        sheetOpen = true
                    }
            }
            .padding(.horizontal, 20)

            VStack {
                Spacer()
                Button {
                    onClose()
                } label: {
                    Text(closeButtonLabel)
                        .frame(width: UIScreen.screenWidth)
                        .padding(.horizontal, -20)
                        .font(
                            .customFont(font: .inter, style: .medium, size: .h4)
                        )
                }
                .foregroundColor(Color("ColorStone950"))
                .padding(.vertical, 13)
                .overlay(
                    RoundedRectangle(cornerRadius: 8)
                        .stroke(Color("ColorStone300"), lineWidth: 1)
                )
                .padding(.top, 10)
            }

        }.sheet(isPresented: $sheetOpen) {

        } content: {
            VStack {
                ScrollView {
                    Text(errorDetails)
                        .font(monospacedFont)
                        .foregroundColor(Color.black)
                        .lineLimit(nil)
                        .padding(.horizontal, 10)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 20)
                .background(Color("ColorStone50"))
                .overlay(
                    RoundedRectangle(cornerRadius: 8)
                        .stroke(Color("ColorStone300"), lineWidth: 1)
                )

                Button {
                    sheetOpen = false
                } label: {
                    Text("Close")
                        .frame(width: UIScreen.screenWidth)
                        .padding(.horizontal, -20)
                        .font(
                            .customFont(font: .inter, style: .medium, size: .h4)
                        )
                }
                .foregroundColor(Color("ColorStone950"))
                .padding(.vertical, 13)
                .overlay(
                    RoundedRectangle(cornerRadius: 8)
                        .stroke(Color("ColorStone300"), lineWidth: 1)
                )
                .padding(.top, 10)

            }
            .padding(.horizontal, 20)
            .padding(.top, 30)
            .presentationDetents([.fraction(0.85)])
            .presentationDragIndicator(.visible)
            .presentationBackgroundInteraction(.automatic)
        }
        .navigationBarBackButtonHidden(true)
    }
}

struct ErrorViewPreview: PreviewProvider {
    static var previews: some View {
        ErrorView(
            errorTitle: "Error title",
            errorDetails: "Error technical details"
        ) {

        }
    }
}
