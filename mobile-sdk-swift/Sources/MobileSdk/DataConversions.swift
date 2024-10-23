import Foundation

extension Data {
  var base64EncodedUrlSafe: String {
    let string = self.base64EncodedString()

    // Make this URL safe and remove padding
    return string
      .replacingOccurrences(of: "+", with: "-")
      .replacingOccurrences(of: "/", with: "_")
      .replacingOccurrences(of: "=", with: "")
  }
}
