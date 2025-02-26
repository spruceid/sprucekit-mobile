import RiveRuntime
import SwiftUI

struct LoadingView: View {
    var loadingText: String
    var cancelButtonLabel: String?
    var onCancel: (() -> Void)?

    var body: some View {
        ZStack {
            VStack {
                RiveViewModel(fileName: "LoadingSpinner")
                    .view()
                    .frame(width: 60, height: 60)
                Text(loadingText)
                    .font(
                        .customFont(font: .inter, style: .semiBold, size: .h0)
                    )
                    .foregroundStyle(Color("ColorBase800"))
            }
            if onCancel != nil {
                VStack {
                    Spacer()
                    Button {
                        onCancel?()
                    } label: {
                        Text(cancelButtonLabel ?? "Cancel")
                            .frame(width: UIScreen.screenWidth)
                            .padding(.horizontal, -20)
                            .font(
                                .customFont(
                                    font: .inter, style: .medium, size: .h4))
                    }
                    .foregroundColor(.black)
                    .padding(.vertical, 13)
                    .cornerRadius(8)
                    .overlay(
                        RoundedRectangle(cornerRadius: 8)
                            .stroke(Color("ColorStone300"), lineWidth: 1)
                    )
                }
            }
        }
        .navigationBarBackButtonHidden(true)
    }
}

struct LoadingViewPreview: PreviewProvider {
    static var previews: some View {
        LoadingView(
            loadingText: "Loading...",
            cancelButtonLabel: "Cancel Label",
            onCancel: {}
        )
    }
}
