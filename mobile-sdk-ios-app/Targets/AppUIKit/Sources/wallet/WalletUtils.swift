import CryptoKit
import SpruceIDMobileSdk
import SwiftUI

extension Image {
    init?(base64String: String) {
        guard let data = Data(base64Encoded: base64String) else { return nil }
        #if os(macOS)
            guard let image = NSImage(data: data) else { return nil }
            self.init(nsImage: image)
        #elseif os(iOS)
            guard let image = UIImage(data: data) else { return nil }
            self.init(uiImage: image)
        #else
            return nil
        #endif
    }
}

func getGenericJSON(jsonString: String) -> GenericJSON? {
    if let data = jsonString.data(using: .utf8) {
        do {
            return try JSONDecoder().decode(GenericJSON.self, from: data)
        } catch let error as NSError {
            print(error)
        }
    }
    return nil
}

extension String {
    func camelCaseToWords() -> String {
        return unicodeScalars.reduce("") {
            if CharacterSet.uppercaseLetters.contains($1) {
                if $0.count > 0 {
                    return ($0 + " " + String($1))
                }
            }
            return $0 + String($1)
        }
    }

    func replaceUnderscores() -> String {
        return self.replacingOccurrences(of: "_", with: " ")
    }

    func replaceCommas() -> String {
        return self.replacingOccurrences(of: ",", with: " ")
    }

    func replaceEscaping() -> String {
        return self.replacingOccurrences(of: "\\/", with: "/")
    }
}

extension Data {
    var base64EncodedUrlSafe: String {
        let string = self.base64EncodedString()

        // Make this URL safe and remove padding
        return
            string
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}
