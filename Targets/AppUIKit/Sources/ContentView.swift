import SwiftUI

public struct ContentView: View {
    @State var path: NavigationPath = .init()

    public init() {}

    public var body: some View {
        ZStack {
            // Bg color
            Rectangle()
                .foregroundColor(Color("Bg"))
                .edgesIgnoringSafeArea(.all)
            NavigationStack(path: $path.animation(.easeOut)) {
                HomeView(path: $path)
                    .navigationDestination(for: Scanning.self) { scanningParams in
                        ScanningView(path: $path, scanningParams: scanningParams)
                    }
                    .navigationDestination(for: VerifyDL.self) { _ in
                        VerifyDLView(path: $path)
                    }
                    .navigationDestination(for: VerifyEA.self) { _ in
                        VerifyEAView(path: $path)
                    }
                    .navigationDestination(for: VerifyVC.self) { _ in
                        VerifyVCView(path: $path)
                    }
                    .navigationDestination(for: VerifyMDoc.self) { _ in
                        VerifyMDocView(path: $path)
                    }
                    .navigationDestination(for: VerifierSettingsHome.self) { _ in
                        VerifierSettingsHomeView(path: $path)
                    }
            }

        }
    }
}

extension UIScreen {
   static let screenWidth = UIScreen.main.bounds.size.width
   static let screenHeight = UIScreen.main.bounds.size.height
   static let screenSize = UIScreen.main.bounds.size
}

#Preview {
    ContentView()
}
