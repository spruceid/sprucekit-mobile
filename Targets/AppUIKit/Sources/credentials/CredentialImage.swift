import SwiftUI

struct CredentialImage: View {
    var image: String
    
    var body: some View {
        if image.contains("https://") {
            return AnyView(AsyncImage(url: URL(string: image)) { image in
                image
                    .resizable()
                    .aspectRatio(contentMode: .fit)
                    .frame(width: 60, height: 60)
            } placeholder: {})
        } else {
            return AnyView(Image(
                    base64String: image
                        .replacingOccurrences(of: "data:image/png;base64,", with: "")
                        .replacingOccurrences(of: "data:image/jpeg;base64,", with: "")
                )?
                .resizable()
                .aspectRatio(contentMode: .fit)
                .frame(width: 60, height: 60))
        }
    }
}
