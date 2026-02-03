import CoreFoundation
import Foundation
import Security
import SpruceIDMobileSdkRs

public class KeyManager: NSObject, SpruceIDMobileSdkRs.KeyStore, ObservableObject,
    @unchecked
    Sendable {
    /// Migrate keys between access groups. For more information see
    /// https://developer.apple.com/documentation/Security/kSecAttrAccessGroup
    public func migrateToAccessGroup(oldAccessGroup: String, newAccessGroup: String) throws {
        let searchAttrs: [String: Any] = [
            kSecClass as String: kSecClassKey,
            kSecAttrAccessGroup as String: oldAccessGroup
        ]
        let targetAttrs: [String: Any] = [
            kSecAttrAccessGroup as String: newAccessGroup
        ]
        let result = SecItemUpdate(searchAttrs as CFDictionary, targetAttrs as CFDictionary)
        if result != errSecSuccess {
            let errorMessage =
                SecCopyErrorMessageString(result, nil) as String? ?? result.description
            throw KeyManError.internalError("Could not migrate keychain: \(errorMessage)")
        }
    }

    public func updateKeychainGroupForKey(id: String, accessGroup: String) -> Bool {
        let tag = id.data(using: .utf8)!
        var query: [String: Any] = [
            kSecClass as String: kSecClassKey,
            kSecAttrApplicationTag as String: tag,
            kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom
        ]

        let attributes: [String: Any] = [
            kSecAttrAccessGroup as String: accessGroup
        ]

        let status = SecItemUpdate(query as CFDictionary, attributes as CFDictionary)

        guard status != errSecItemNotFound else {
            print("updateKeychainGroupForKey: Item not found!")
            return false
        }

        guard status == errSecSuccess else {
            print("updateKeychainGroupForKey: Unhandled error: \(status)!")
            return false
        }

        return true
    }

    public func getSigningKey(alias: SpruceIDMobileSdkRs.KeyAlias) throws -> any SpruceIDMobileSdkRs
        .SigningKey {
        guard let jwk = Self.getJwk(id: alias) else {
            throw KeyManError.missing
        }
            return P256SigningKey(alias: alias, jwkString: jwk.description)
    }

    /**
     * Resets the key store by removing all of the keys.
     */
    public static func reset() -> Bool {
        let query: [String: Any] = [
            kSecClass as String: kSecClassKey
        ]

        let ret = SecItemDelete(query as CFDictionary)
        return ret == errSecSuccess
    }

    /**
     * Checks to see if a secret key exists based on the id/alias.
     */
    public static func keyExists(id: String, accessGroup: String? = nil) -> Bool {
        let tag = id.data(using: .utf8)!
        var query: [String: Any] = [
            kSecClass as String: kSecClassKey,
            kSecAttrApplicationTag as String: tag,
            kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom,
            kSecReturnRef as String: true
        ]

        if let accessGroup = accessGroup {
            query[kSecAttrAccessGroup as String] = accessGroup
        }

        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        return status == errSecSuccess
    }

    /**
     * Returns a secret key - based on the id of the key.
     */
    public static func getSecretKey(id: String, accessGroup: String? = nil) -> SecKey? {
        let tag = id.data(using: .utf8)!
        var query: [String: Any] = [
            kSecClass as String: kSecClassKey,
            kSecAttrApplicationTag as String: tag,
            kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom,
            kSecReturnRef as String: true
        ]

        if let accessGroup = accessGroup {
            query[kSecAttrAccessGroup as String] = accessGroup
        }

        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)

        guard status == errSecSuccess else { return nil }

        // swiftlint:disable force_cast
        let key = item as! SecKey
        // swiftlint:enable force_cast

        return key
    }

    /**
     * Generates a secp256r1 signing key by id
     */
    public static func generateSigningKey(id: String, accessGroup: String? = nil) -> Bool {
        let tag = id.data(using: .utf8)!

        let access = SecAccessControlCreateWithFlags(
            kCFAllocatorDefault,
            kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
            .privateKeyUsage,
            nil)!

        var attributes: [String: Any] = [
            kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom,
            kSecAttrKeyClass as String: kSecAttrKeyClassPrivate,
            kSecAttrKeySizeInBits as String: NSNumber(value: 256),
            kSecAttrTokenID as String: kSecAttrTokenIDSecureEnclave,
            kSecPrivateKeyAttrs as String: [
                kSecAttrIsPermanent as String: true,
                kSecAttrApplicationTag as String: tag,
                kSecAttrAccessControl as String: access
            ]
        ]

        // Set the access group, if it exists.
        if let accessGroup = accessGroup {
            attributes[kSecAttrAccessGroup as String] = accessGroup
        }

        var error: Unmanaged<CFError>?
        SecKeyCreateRandomKey(attributes as CFDictionary, &error)
        if error != nil { print(error!) }
        return error == nil
    }

    /**
     * Returns a JWK for a particular sec
     */
    public static func getJwk(id: String, accessGroup: String? = nil) -> Jwk? {
        guard let key = getSecretKey(id: id, accessGroup: accessGroup) else { return nil }

        guard let publicKey = SecKeyCopyPublicKey(key) else {
            return nil
        }

        var error: Unmanaged<CFError>?
        guard let data = SecKeyCopyExternalRepresentation(publicKey, &error) as? Data else {
            return nil
        }

        let fullData: Data = data.subdata(in: 1..<data.count)
        let xDataRaw: Data = fullData.subdata(in: 0..<32)
        let yDataRaw: Data = fullData.subdata(in: 32..<64)

        return jwkFromPublicP256(x: xDataRaw, y: yDataRaw)
    }
        
    /**
     * Returns the public key of the given key pair as a JWK.
     *
     * Creates the key if it doesn't exist.
     */
    public static func getOrInsertJwk(id: String) -> Jwk {
        if !KeyManager.keyExists(id: id) {
            _ = KeyManager.generateSigningKey(id: id)
        }

        return KeyManager.getJwk(id: id)!
    }

    /**
     * Returns the public key as a CBOR-encoded COSE key byte array
     */
    public static func coseKeyEc2P256PubKey(id: String, accessGroup: String? = nil) -> Data? {
        guard let key = getSecretKey(id: id, accessGroup: accessGroup),
            let publicKey = SecKeyCopyPublicKey(key)
        else {
            return nil
        }

        var error: Unmanaged<CFError>?
        guard let data = SecKeyCopyExternalRepresentation(publicKey, &error) as? Data else {
            return nil
        }

        let fullData: Data = data.subdata(in: 1..<data.count)
        let xDataRaw: Data = fullData.subdata(in: 0..<32)
        let yDataRaw: Data = fullData.subdata(in: 32..<64)

        do {
            return try coseKeyEc2P256PublicKey(x: xDataRaw, y: yDataRaw, kid: Data(id.utf8))
        } catch {
            return nil
        }
    }

    /**
     * Signs the provided payload with a ecdsaSignatureMessageX962SHA256 private key.
     */
    public static func signPayload(id: String, payload: [UInt8], accessGroup: String? = nil)
        -> [UInt8]? {
        guard let key = getSecretKey(id: id, accessGroup: accessGroup) else { return nil }

        guard let data = CFDataCreate(kCFAllocatorDefault, payload, payload.count) else {
            return nil
        }

        let algorithm: SecKeyAlgorithm = .ecdsaSignatureMessageX962SHA256
        var error: Unmanaged<CFError>?
        guard
            let signature = SecKeyCreateSignature(
                key,
                algorithm,
                data,
                &error
            ) as Data?
        else {
            print(error ?? "no error")
            return nil
        }

        return [UInt8](signature)
    }

    /**
     * Generates an encryption key with a provided id in the Secure Enclave.
     */
    public static func generateEncryptionKey(id: String, accessGroup: String? = nil) -> Bool {
        let tag = id.data(using: .utf8)!

        let access = SecAccessControlCreateWithFlags(
            kCFAllocatorDefault,
            kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
            .privateKeyUsage,
            nil)!

        var attributes: [String: Any] = [
            kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom,
            kSecAttrKeyClass as String: kSecAttrKeyClassPrivate,
            kSecAttrKeySizeInBits as String: NSNumber(value: 256),
            kSecAttrTokenID as String: kSecAttrTokenIDSecureEnclave,
            kSecPrivateKeyAttrs as String: [
                kSecAttrIsPermanent as String: true,
                kSecAttrApplicationTag as String: tag,
                kSecAttrAccessControl as String: access
            ]
        ]

        // Set the access group, if it exists.
        if let accessGroup = accessGroup {
            attributes[kSecAttrAccessGroup as String] = accessGroup
        }

        var error: Unmanaged<CFError>?
        SecKeyCreateRandomKey(attributes as CFDictionary, &error)
        if error != nil { print(error ?? "no error") }
        return error == nil
    }

    /**
     * Encrypts payload by a key referenced by key id.
     */
    public static func encryptPayload(id: String, payload: [UInt8], accessGroup: String? = nil) -> (
        [UInt8], [UInt8]
    )? {
        guard let key = getSecretKey(id: id, accessGroup: accessGroup) else { return nil }

        guard let publicKey = SecKeyCopyPublicKey(key) else {
            return nil
        }

        guard let data = CFDataCreate(kCFAllocatorDefault, payload, payload.count) else {
            return nil
        }

        let algorithm: SecKeyAlgorithm = .eciesEncryptionCofactorX963SHA512AESGCM
        var error: Unmanaged<CFError>?

        guard
            let encrypted = SecKeyCreateEncryptedData(
                publicKey,
                algorithm,
                data,
                &error
            ) as Data?
        else {
            return nil
        }

        return ([0], [UInt8](encrypted))
    }

    /**
     * Decrypts the provided payload by a key id and initialization vector.
     */
    public static func decryptPayload(id: String, payload: [UInt8], accessGroup: String? = nil)
        -> [UInt8]? {
        guard let key = getSecretKey(id: id, accessGroup: accessGroup) else { return nil }

        guard let data = CFDataCreate(kCFAllocatorDefault, payload, payload.count) else {
            return nil
        }

        let algorithm: SecKeyAlgorithm = .eciesEncryptionCofactorX963SHA512AESGCM
        var error: Unmanaged<CFError>?
        guard
            let decrypted = SecKeyCreateDecryptedData(
                key,
                algorithm,
                data,
                &error
            ) as Data?
        else {
            return nil
        }

        return [UInt8](decrypted)
    }
}

public class P256SigningKey: SpruceIDMobileSdkRs.SigningKey, @unchecked Sendable {
    private let alias: String
    private let jwkString: String
    private let accessGroup: String?

    init(alias: String, jwkString: String, accessGroup: String? = nil) {
        self.alias = alias
        self.jwkString = jwkString
        self.accessGroup = accessGroup
    }

    public func jwk() throws -> String {
        return jwkString
    }

    public func sign(payload: Data) throws -> Data {
        guard
            let signature: [UInt8] = KeyManager.signPayload(
                id: alias, payload: [UInt8](payload), accessGroup: accessGroup)
        else {
            throw KeyManError.signing
        }
        guard
            let normalizedSignature: Data =
                CryptoCurveUtils.secp256r1().ensureRawFixedWidthSignatureEncoding(
                    bytes: Data(signature))
        else {
            throw KeyManError.signatureFormat
        }
        return normalizedSignature
    }
}

public enum KeyManError: Error {
    /// keypair could not be found
    case missing
    /// an error occured during signing
    case signing
    /// the signature format was not recognized
    case signatureFormat
    /// unexpected error
    case internalError(String)
}
