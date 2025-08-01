import SwiftUI

public enum ToastType {
    case success, warning, error
}

class ToastManager: ObservableObject {
    static let shared = ToastManager()

    @Published var message: String?
    @Published var type: ToastType = .success
    @Published var isShowing: Bool = false
    
    private var hideToastWorkItem: DispatchWorkItem?

    func showSuccess(message: String, duration: TimeInterval = 3.0) {
        self.message = message
        self.type = .success
        self.isShowing = true
        
        scheduleHideToast(duration: duration)
    }

    func showWarning(message: String, duration: TimeInterval = 3.0) {
        self.message = message
        self.type = .warning
        self.isShowing = true
        
        scheduleHideToast(duration: duration)
    }

    func showError(message: String, duration: TimeInterval = 3.0) {
        self.message = message
        self.type = .error
        self.isShowing = true
        
        scheduleHideToast(duration: duration)
    }
    
    private func scheduleHideToast(duration: TimeInterval) {
        hideToastWorkItem?.cancel()
        
        hideToastWorkItem = DispatchWorkItem { [weak self] in
            DispatchQueue.main.async {
                self?.isShowing = false
            }
        }
        
        if let workItem = hideToastWorkItem {
            DispatchQueue.main.asyncAfter(deadline: .now() + duration, execute: workItem)
        }
    }
    
    func hideToast() {
        hideToastWorkItem?.cancel()
        isShowing = false
    }
}

struct Toast: View {
    @ObservedObject var toastManager = ToastManager.shared

    var body: some View {
        if toastManager.isShowing, let message = toastManager.message {
            VStack {
                HStack {
                    switch toastManager.type {
                    case .success:
                        ToastSuccess(message: toastManager.message)
                            .transition(.opacity)
                            .animation(.easeInOut, value: toastManager.isShowing)
                    case .warning:
                        ToastWarning(message: toastManager.message)
                            .transition(.opacity)
                            .animation(.easeInOut, value: toastManager.isShowing)
                    case .error:
                        ToastError(message: toastManager.message)
                            .transition(.opacity)
                            .animation(.easeInOut, value: toastManager.isShowing)
                    }
                }
                .padding(.horizontal, 12)
                Spacer()
            }
            .onTapGesture {
                toastManager.isShowing = false
            }
            .onLongPressGesture {
                UIPasteboard.general.string = toastManager.message
                toastManager.showSuccess(message: "Copied to Clipboard!")
            }
        }
    }
}

struct ToastSuccess: View {
    let message: String?
    var body: some View {
        HStack {
            Image("ToastSuccess")
                .resizable()
                .aspectRatio(contentMode: .fit)
                .frame(width: 20, height: 20)
            Text(message ?? "")
                .font(.customFont(font: .inter, style: .regular, size: .h4))
                .foregroundColor(Color("ColorEmerald900"))
        }
        .padding(.all, 8)
        .frame(maxWidth: .infinity)
        .background(Color("ColorEmerald50"))
        .cornerRadius(6)
        .overlay(
            RoundedRectangle(cornerRadius: 6)
                .stroke(Color("ColorEmerald200"), lineWidth: 1)
        )
    }
}

struct ToastWarning: View {
    let message: String?
    var body: some View {
        HStack {
            Image("ToastWarning")
                .resizable()
                .aspectRatio(contentMode: .fit)
                .frame(width: 20, height: 20)
            Text(message ?? "")
                .font(.customFont(font: .inter, style: .regular, size: .h4))
                .foregroundColor(Color("ColorAmber900"))
        }
        .padding(.all, 8)
        .frame(maxWidth: .infinity)
        .background(Color("ColorAmber50"))
        .cornerRadius(6)
        .overlay(
            RoundedRectangle(cornerRadius: 6)
                .stroke(Color("ColorAmber200"), lineWidth: 1)
        )
    }
}

struct ToastError: View {
    let message: String?
    var body: some View {
        HStack {
            Image("ToastError")
                .resizable()
                .aspectRatio(contentMode: .fit)
                .frame(width: 20, height: 20)
            Text(message ?? "")
                .font(.customFont(font: .inter, style: .regular, size: .h4))
                .foregroundColor(Color("ColorRose900"))
        }
        .padding(.all, 8)
        .frame(maxWidth: .infinity)
        .background(Color("ColorRose50"))
        .cornerRadius(6)
        .overlay(
            RoundedRectangle(cornerRadius: 6)
                .stroke(Color("ColorRose200"), lineWidth: 1)
        )
    }
}
