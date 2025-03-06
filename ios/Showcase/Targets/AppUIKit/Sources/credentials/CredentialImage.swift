import SpruceIDMobileSdk
import SwiftUI

struct CredentialImage: View {
    var image: String

    var body: some View {
        if image.contains("https://") {
            return AnyView(
                AsyncImage(url: URL(string: image)) { image in
                    image
                        .resizable()
                        .aspectRatio(contentMode: .fit)
                        .frame(width: 60, height: 60)
                } placeholder: {
                })
        } else {
            return AnyView(
                Image(
                    base64String:
                        image
                        .replacingOccurrences(
                            of: "data:image/png;base64,", with: ""
                        )
                        .replacingOccurrences(
                            of: "data:image/jpeg;base64,", with: "")
                )?
                .resizable()
                .aspectRatio(contentMode: .fit)
                .frame(width: 60, height: 60))
        }
    }
}

struct CredentialGenericJSONArrayImage: View {
    var image: [GenericJSON]

    func convertGenericJSONToBytes(_ jsonArray: [GenericJSON]) -> [UInt8]? {
        var byteArray: [UInt8] = []

        for json in jsonArray {
            if case let .number(value) = json {
                let clampedValue = min(max(value, 0), 255)
                byteArray.append(UInt8(clampedValue))
            } else {
                return nil
            }
        }
        return byteArray
    }

    var body: some View {
        if let byteArray = convertGenericJSONToBytes(image),
            let image = UIImage(data: Data(byteArray)) {
            Image(uiImage: image)
                .resizable()
                .aspectRatio(contentMode: .fit)
                .frame(width: 60, height: 60)
        }
    }
}
