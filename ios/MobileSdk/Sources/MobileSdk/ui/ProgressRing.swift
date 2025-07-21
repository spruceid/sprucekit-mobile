import SwiftUI

public struct ProgressRing: View {
    var size: CGFloat = 20
    var lineWidth: CGFloat = 4
    @State private var rotationAngle = 0.0

    public var body: some View {
        ZStack {
            Circle()
                .stroke(Color.gray.opacity(0.3), lineWidth: lineWidth)
                .frame(width: size, height: size)

            Circle()
                .trim(from: 0, to: 0.75)
                .stroke(
                    Color.white,
                    style: StrokeStyle(lineWidth: lineWidth, lineCap: .round)
                )
                .frame(width: size, height: size)
                .rotationEffect(.degrees(rotationAngle))
                .onAppear {
                    withAnimation(
                        .linear(duration: 1.5)
                            .repeatForever(autoreverses: false)
                    ) {
                        rotationAngle = 360.0
                    }
                }
                .onDisappear{
                    rotationAngle = 0.0
                }
        }
        .frame(width: size, height: size)
    }
}
