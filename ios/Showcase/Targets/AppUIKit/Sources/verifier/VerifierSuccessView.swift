import SwiftUI

struct VerifierSuccessView: View {
    @Binding var path: NavigationPath

    var success: Bool
    var content: (any View)

    var body: some View {
        VStack(alignment: .leading) {
            if success {
                ZStack(alignment: .leading) {
                    RoundedRectangle(cornerRadius: 8)
                        .foregroundColor(Color("ColorEmerald900"))
                        .frame(height: 250)
                    VStack {
                        Spacer()
                        HStack {
                            Image("ValidCheck")
                            Text("True")
                                .font(.customFont(font: .inter, style: .semiBold, size: .h0))
                                .foregroundStyle(Color.white)
                        }

                    }
                    .padding(.all, 20)
                }
                .frame(height: 250)
            } else {
                ZStack(alignment: .leading) {
                    RoundedRectangle(cornerRadius: 8)
                        .foregroundColor(Color("ColorRose700"))
                        .frame(width: .infinity, height: 250)
                    VStack {
                        Spacer()
                        HStack {
                            Image("InvalidCheck")
                            Text("False")
                                .font(.customFont(font: .inter, style: .semiBold, size: .h0))
                                .foregroundStyle(Color.white)
                        }

                    }
                    .padding(.all, 20)
                }
                .frame(height: 250)
            }

            AnyView(content)

            Spacer()

            Button {
                while !path.isEmpty {
                    path.removeLast()
                }
            }  label: {
                Text("Close")
                    .frame(width: UIScreen.screenWidth)
                    .padding(.horizontal, -20)
                    .font(.customFont(font: .inter, style: .medium, size: .h4))
            }
            .foregroundColor(.white)
            .padding(.vertical, 13)
            .background(Color("ColorStone700"))
            .cornerRadius(8)
        }
        .frame(maxWidth: .infinity)
        .padding(.horizontal, 15)
        .padding(.vertical, 30)
        .navigationBarBackButtonHidden(true)
    }
}

struct VerifierSuccessViewPreview: PreviewProvider {
    @State static var path: NavigationPath = .init()

    static var previews: some View {
        VerifierSuccessView(
            path: $path,
            success: true,
            content: Text("Valid Verifiable Credential")
                .font(.customFont(font: .inter, style: .semiBold, size: .h1))
                .foregroundStyle(Color("ColorStone950"))
                .padding(.top, 20)
        )
    }
}
