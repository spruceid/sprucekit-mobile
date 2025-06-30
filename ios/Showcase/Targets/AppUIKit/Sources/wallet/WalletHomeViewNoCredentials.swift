import SwiftUI

struct WalletHomeViewNoCredentials: View {
    let onButtonClick: () async -> Void

    var body: some View {
        ZStack {
            VStack {
                Section {
                    // No-credential Card
                    VStack(spacing: 36) {
                        // Title and Subtitle
                        VStack(spacing: 8){
                            Text("Welcome!")
                                .font(.customFont(font: .inter, style: .bold, size: .h0))
                                .foregroundColor(Color("ColorBlue600"))
                            Text("You currently have no credentials in your wallet")
                                .font(.customFont(font: .inter, style: .medium, size: .p))
                                .foregroundColor(Color("ColorStone600"))
                                .lineLimit(1)
                                .truncationMode(.tail)
                                .minimumScaleFactor(0.8)
                        }
                        // MDL Image
                        Image("mDLImage")
                            .resizable()
                            .scaledToFit()
                        // Button
                        Button {
                            Task {
                                await onButtonClick()
                            }
                        } label: {
                            HStack(spacing: 6) {
                                Image("GenerateMockMdl")
                                    .renderingMode(.template)
                                    .foregroundColor(.white)
                                    .frame(width: 20, height: 20)

                                Text("Generate a Spruce mDL")
                                    .font(
                                        .customFont(font: .inter, style: .semiBold, size: .h4)
                                    )
                            }
                            .frame(maxWidth: .infinity, alignment: .center)
                            .frame(height: 25)
                            .padding(.vertical, 8)
                            .padding(.horizontal, 20)
                        }
                        .background(Color("ColorBlue600"))
                        .cornerRadius(100)
                        .foregroundColor(.white)
                        .overlay(
                            RoundedRectangle(cornerRadius: 100)
                            .strokeBorder(
                                LinearGradient(
                                    gradient: Gradient(stops: [
                                        .init(
                                            color: Color.white.opacity(0.2),
                                            location: 0.0
                                        ),
                                        .init(
                                            color: Color("ColorBlue800"),
                                            location: 0.4
                                        ),
                                        .init(
                                            color: Color("ColorBlue900"),
                                            location: 1.0
                                        ),
                                    ]),
                                    startPoint: .top,
                                    endPoint: .bottom
                                ),
                                lineWidth: 2
                            )
                        )
                    }
                    .padding(EdgeInsets(top: 24, leading: 20, bottom: 16, trailing: 20))
                    .background(
                        LinearGradient(
                            colors: [Color("ColorBase100"), Color("ColorBlue100")],
                            startPoint: .top,
                            endPoint: .bottom
                        )
                    )
                    .cornerRadius(12)
                    .overlay(
                        RoundedRectangle(cornerRadius: 12)
                            .stroke(Color.white, lineWidth: 2)
                    )
                    .shadow(color: Color.black.opacity(0.05), radius: 6, x: 0, y: 4)
                    .shadow(color: Color.black.opacity(0.10), radius: 6, x: 0, y: 10)
                }
                Spacer()
            }
            .padding(.top, 20)
            .padding(.horizontal, 20)
        }
        .transition(
            .asymmetric(
                insertion: .identity,
                removal: .opacity
            )
        )
    }
}
