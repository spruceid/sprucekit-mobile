import SwiftUI

struct WalletSettingsHome: Hashable {}

struct WalletSettingsHomeView: View {
    @Binding var path: NavigationPath
    
    
    func onBack() {
        while !path.isEmpty {
            path.removeLast()
        }
    }
    
    var body: some View {
        VStack {
            WalletSettingsHomeHeader(onBack: onBack)
            WalletSettingsHomeBody(onBack: onBack)
        }
        .navigationBarBackButtonHidden(true)
    }
}

struct WalletSettingsHomeHeader: View {
    var onBack: () -> Void

    var body: some View {
        HStack {
            Image("Chevron")
                .rotationEffect(.degrees(90))
                .padding(.leading, 36)
            Text("Wallet Setting")
                .font(.customFont(font: .inter, style: .bold, size: .h0))
                .padding(.leading, 10)
                .foregroundStyle(Color("TextHeader"))
            Spacer()
        }
        .onTapGesture {
            onBack()
        }
        .padding(.top, 10)
    }
}

struct WalletSettingsHomeBody: View {
    
    var onBack: () -> Void
    
    @ViewBuilder
    var deleteAllCredentials: some View {
        Button {
            _ = CredentialDataStore.shared.deleteAll()
        }  label: {
            Text("Delete all added credentials")
                .frame(width: UIScreen.screenWidth)
                .padding(.horizontal, -20)
                .font(.customFont(font: .inter, style: .medium, size: .h4))
        }
        .foregroundColor(.white)
        .padding(.vertical, 13)
        .background(Color("RedInvalid"))
        .cornerRadius(8)
    }


    var body: some View {
        VStack {
            ScrollView(.vertical, showsIndicators: false) {
                deleteAllCredentials
            }
        }
        .padding(.all, 24)
    }
}
