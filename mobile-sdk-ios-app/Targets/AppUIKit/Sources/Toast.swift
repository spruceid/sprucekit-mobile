import SwiftUI

public enum ToastType {
    case success
}

class ToastManager: ObservableObject {
    static let shared = ToastManager()

    @Published var message: String? = nil
    @Published var type: ToastType = .success
    @Published var isShowing: Bool = false

    func showSuccess(message: String, duration: TimeInterval = 3.0) {
        self.message = message
        self.type = .success
        self.isShowing = true

        DispatchQueue.main.asyncAfter(deadline: .now() + duration) {
            self.isShowing = false
        }
    }
}

struct Toast: View {
    @ObservedObject var toastManager = ToastManager.shared

    var body: some View {
        if toastManager.isShowing, let message = toastManager.message {
            switch toastManager.type {
            case .success:
                VStack {
                    HStack {
                        Image("ToastSuccess")
                            .resizable()
                            .aspectRatio(contentMode: .fit)
                            .frame(width: 20, height: 20)
                        Text(message)
                            .font(.customFont(font: .inter, style: .regular, size: .h4))
                            .foregroundColor(Color("ColorEmerald900"))
                    }
                    .padding(.vertical, 8)
                    .frame(maxWidth: .infinity)
                    .background(Color("ColorEmerald50"))
                    .cornerRadius(6)
                    .overlay(
                        RoundedRectangle(cornerRadius: 6)
                            .stroke(Color("ColorEmerald200"), lineWidth: 1)
                    )
                    .padding(.horizontal, 12)
                    Spacer()
                }
                .transition(.opacity)
                .animation(.easeInOut, value: toastManager.isShowing)
            }
        }
    }
}
