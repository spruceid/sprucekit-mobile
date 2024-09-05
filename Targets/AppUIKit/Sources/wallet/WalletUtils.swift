import CryptoKit
import SwiftUI
import SpruceIDMobileSdk

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

func generateMDoc() -> MDoc? {
    do {
        let mdocData = Data(base64Encoded: mdocBase64)!
        let key = try P256.Signing.PrivateKey(pemRepresentation: keyPEM)

        let attributes = [kSecAttrKeyType: kSecAttrKeyTypeECSECPrimeRandom,
                         kSecAttrKeyClass: kSecAttrKeyClassPrivate] as [String: Any]
        let secKey = SecKeyCreateWithData(key.x963Representation as CFData,
                                          attributes as CFDictionary,
                                          nil)!
        let query = [kSecClass: kSecClassKey,
      kSecAttrApplicationLabel: "mdoc_key",
            kSecAttrAccessible: kSecAttrAccessibleWhenUnlocked,
 kSecUseDataProtectionKeychain: true,
                  kSecValueRef: secKey] as [String: Any]
        SecItemDelete(query as CFDictionary)
        _ = SecItemAdd(query as CFDictionary, nil)
        return MDoc(fromMDoc: mdocData, namespaces: [:], keyAlias: "mdoc_key")!
    } catch {
        print("\(error)")
        return nil
    }
}
