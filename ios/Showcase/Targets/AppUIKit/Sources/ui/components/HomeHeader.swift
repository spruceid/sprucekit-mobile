import SpruceIDMobileSdk
import SpruceIDMobileSdkRs
import SwiftUI

struct HeaderButton: Identifiable {
    let id = UUID()
    let icon: Image
    let contentDescription: String
    let onClick: () -> Void
}


struct HomeHeader: View {
    let title: String
    let gradientColors: [Color]
    let buttons: [HeaderButton]
    
    var body: some View {
        ZStack(alignment: .center) {
            VStack (alignment: .leading, spacing: 30){
                HStack(alignment: .center, spacing: 8) {
                    Image("SpruceLogo")
                        .resizable()
                        .renderingMode(.template)
                        .foregroundColor(Color("ColorStone950"))
                        .frame(width: 21, height: 21)
                    
                    Text("SpruceKit")
                        .font(
                            .customFont(
                                font: .inter,
                                style: .bold,
                                size: .h3
                            )
                        )

                        .foregroundColor(Color("ColorStone950"))

                }
                .frame(maxWidth: .infinity, alignment: .leading)
                
                HStack(alignment: .center, spacing: 8) {
                    Text(title)
                        .font(.custom("Inter", size: 30).weight(.bold))
                        .foregroundColor(Color("ColorStone950"))
                        .frame(maxWidth: .infinity, alignment: .leading)

                    HStack(spacing: 8) {
                        ForEach(buttons) { button in
                            Button(action: button.onClick) {
                                button.icon
                                    .resizable()
                                    .renderingMode(.template)
                                    .foregroundColor(Color("ColorStone950"))
                                    .frame(width: 20, height: 20)
                                    .padding(6)
                                    .frame(width: 36, height: 36)
                                    .background(Color.white.opacity(0.3))
                                    .cornerRadius(8)
                                    .overlay(
                                        RoundedRectangle(cornerRadius: 8)
                                            .stroke(Color.white, lineWidth: 0.5)
                                    )
                            }
                            .accessibilityLabel(button.contentDescription)
                        }
                    }
                }
                
            }
            .frame(maxWidth: .infinity)
            .padding(.top, 10)
            .padding(.bottom, 30)
            .padding(.horizontal, 26)

        }
        .background(
            .ellipticalGradient(
                colors: gradientColors,
                center: UnitPoint(x: 0.5, y: -0.1),
                startRadiusFraction: 0,
                endRadiusFraction: 0.95
            )
        )
    }
}
