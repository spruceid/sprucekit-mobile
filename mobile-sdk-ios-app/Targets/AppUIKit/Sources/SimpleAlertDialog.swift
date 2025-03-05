import SwiftUI

struct SimpleAlertDialog: View {
    @Binding var isPresented: Bool
    let message: String?

    var body: some View {
        GeometryReader { geometry in
            ZStack {
                if isPresented {
                    VStack(spacing: 16) {
                        ScrollView {
                            Text(message ?? "")
                                .font(
                                    .customFont(
                                        font: .inter, style: .regular, size: .h4
                                    )
                                )
                                .foregroundStyle(Color("ColorStone950"))
                                .multilineTextAlignment(.center)
                        }
                        HStack {
                            Spacer()
                            Text("Close")
                                .onTapGesture {
                                    isPresented = false
                                }
                                .padding()
                        }
                    }
                    .padding()
                    .frame(maxWidth: 300)
                    .background(Color.white)
                    .cornerRadius(12)
                    .shadow(radius: 5)
                    .position(
                        x: geometry.size.width / 2, y: geometry.size.height / 2)
                }
            }
            .animation(.easeInOut, value: isPresented)
        }
    }
}
