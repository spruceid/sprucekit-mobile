import SwiftUI

struct VerifierSettingsHome: Hashable {}

enum VerifierSubSettings {
    case verificationActivityLog
}
struct VerifierSettingsHomeView: View {
    @Binding var path: NavigationPath
    
    @State private var subpage: VerifierSubSettings?
    
    func onBack() {
        if(subpage != nil) {
            subpage = nil
        } else {
            while !path.isEmpty {
                path.removeLast()
            }
        }
    }
    
    var body: some View {
        VStack {
            VerifierSettingsHomeHeader(onBack: onBack)
            VerifierSettingsHomeBody(
                subpage: $subpage,
                onBack: onBack
            )
        }
        .navigationBarBackButtonHidden(true)
    }
}

struct VerifierSettingsHomeHeader: View {
    var onBack: () -> Void
    
    var body: some View {
        HStack {
            Image("Chevron")
                .rotationEffect(.degrees(90))
                .padding(.leading, 36)
            Text("Verifier Setting")
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

struct VerifierSettingsHomeBody: View {
    @Binding var subpage: VerifierSubSettings?
    
    var onBack: () -> Void
    
    @ViewBuilder
    var activityLogButton: some View {
        Button {
            subpage = VerifierSubSettings.verificationActivityLog
        } label: {
            HStack(alignment: .top) {
                VStack {
                    HStack {
                        Image("List")
                        Text("Verification Activity Log").frame(maxWidth: .infinity, alignment: .leading)
                            .foregroundColor(Color("TextHeader"))
                            .font(.customFont(font: .inter, style: .medium, size: .p))
                    }
                    Text("View and export verification history").frame(maxWidth: .infinity, alignment: .leading)
                        .foregroundColor(Color("TextBody"))
                        .font(.customFont(font: .inter, style: .regular, size: .p))
                        .padding(.top, 1.0)
                }
                Image("Chevron")
                    .rotationEffect(.degrees(-90))
            }
            .padding(.all, 20.0)
        }
    }
    
    
    var body: some View {
        if subpage == nil {
            VStack {
                ScrollView(.vertical, showsIndicators: false) {
                    activityLogButton
                }
            }
            .padding(.all, 24)
        } else if subpage == VerifierSubSettings.verificationActivityLog {
            VerificationActivityLogView(onBack: onBack)
        }
    }
}
