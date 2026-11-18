import CoreFoundation
import Foundation
import LocalAuthentication
import Security
import SpruceIDMobileSdkRs

public class KeyManager: NSObject, SpruceIDMobileSdkRs.KeyStore, ObservableObject,
    @unchecked
    Sendable {
    /// Authentication context used by the signing keys this instance hands out.
    private let context: LAContext?

    override public init() {
        self.context = nil
        super.init()
    }

    /// A key store whose signing keys sign with `context`.
    ///
    /// Pass a context already evaluated with `.deviceOwnerAuthentication` so that keys generated
    /// with a `UserAuthenticationPolicy` sign without showing the system prompt again (for
    /// example in `responder.respond(keyStore:)` or other Rust-driven flows). Without a context,
    /// the system prompts on its own when a protected key signs.
    public init(context: LAContext?) {
        self.context = context
        super.init()
    }

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
        // CryptoError is the error type the Rust KeyStore trait declares; any other error
        // crossing the callback makes the Rust side panic.
        // Look the key up first so a classified failure (for example a locked device) keeps its
        // marker instead of reading as a missing key.
        do {
            _ = try Self.getSecretKeyThrowing(id: alias, context: context)
        } catch let error as UserAuthenticationError {
            throw CryptoError.General(error.message)
        } catch {
            throw CryptoError.General("No signing key '\(alias)': \(error)")
        }
        guard let jwk = Self.getJwk(id: alias) else {
            throw CryptoError.General("No signing key '\(alias)'")
        }
        return P256SigningKey(alias: alias, jwkString: jwk.description, context: context)
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
     *
     * When `context` is given, operations on the returned key use it, so a key that requires
     * user authentication doesn't prompt again if the context was already evaluated.
     */
    public static func getSecretKey(
        id: String, accessGroup: String? = nil, context: LAContext? = nil
    ) -> SecKey? {
        return try? getSecretKeyThrowing(id: id, accessGroup: accessGroup, context: context)
    }

    /**
     * Looks up a secret key like `getSecretKey`, but throws instead of returning nil.
     *
     * Throws `KeyManError.missing` only when there is no key with this id
     * (`errSecItemNotFound`), `UserAuthenticationRequiredError` or
     * `UserAuthenticationUnavailableError` when the lookup failed for user authentication (see
     * `UserAuthenticationFailureKind.classify`), and `KeyManError.internalError` otherwise.
     */
    static func getSecretKeyThrowing(
        id: String, accessGroup: String? = nil, context: LAContext? = nil
    ) throws -> SecKey {
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

        if let context = context {
            query[kSecUseAuthenticationContext as String] = context
        }

        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)

        switch status {
        case errSecSuccess:
            break
        case errSecItemNotFound:
            throw KeyManError.missing
        default:
            let nsError = NSError(domain: NSOSStatusErrorDomain, code: Int(status))
            if let authError = userAuthenticationError(alias: id, underlying: nsError) {
                throw authError
            }
            throw KeyManError.internalError("Could not look up key '\(id)': OSStatus \(status)")
        }

        // swiftlint:disable force_cast
        let key = item as! SecKey
        // swiftlint:enable force_cast

        return key
    }

    /**
     * Generates a secp256r1 signing key by id
     *
     * When `userAuthentication` is set, the key needs user presence (`.userPresence`) or the
     * currently enrolled biometrics (`.biometryCurrentSet`) for every signature. The app (and
     * any extension that signs with the key) must then declare `NSFaceIDUsageDescription` in its
     * Info.plist, or iOS only offers the device passcode.
     */
    public static func generateSigningKey(
        id: String, accessGroup: String? = nil,
        userAuthentication: UserAuthenticationPolicy? = nil
    ) -> Bool {
        let tag = id.data(using: .utf8)!

        let access = SecAccessControlCreateWithFlags(
            kCFAllocatorDefault,
            kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
            userAuthentication?.accessControlFlags ?? .privateKeyUsage,
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
        guard SecKeyCreateRandomKey(attributes as CFDictionary, &error) != nil else {
            let cfError = error?.takeRetainedValue()
            print("generateSigningKey: could not create key '\(id)': \(cfError.map { "\($0)" } ?? "unknown error")")
            return false
        }
        return true
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
     * Returns the public key of the given key pair as a JWK, creating the key if it doesn't exist.
     */
    public static func getOrInsertJwk(id: String) -> Jwk {
        if !KeyManager.keyExists(id: id) {
            _ = KeyManager.generateSigningKey(id: id)
        }

        return KeyManager.getJwk(id: id)!
    }

    /**
     * Returns the public key of the given key pair as a JWK, creating the key if it doesn't exist.
     *
     * `userAuthentication` only applies to a newly created key; an existing key keeps the
     * protection it was created with. Creating a protected key fails when the device can't
     * satisfy the policy (no passcode set, or no biometrics enrolled when the device passcode
     * isn't allowed); this throws `UserAuthenticationUnavailableError` then, and no key is
     * created. Without a policy, a failure throws `KeyManError.internalError`.
     */
    public static func getOrInsertJwk(
        id: String, userAuthentication: UserAuthenticationPolicy?
    ) throws -> Jwk {
        if !KeyManager.keyExists(id: id) {
            guard KeyManager.generateSigningKey(id: id, userAuthentication: userAuthentication)
            else {
                if userAuthentication != nil {
                    throw UserAuthenticationUnavailableError(alias: id)
                }
                throw KeyManError.internalError("Could not create key '\(id)'")
            }
        }

        guard let jwk = KeyManager.getJwk(id: id) else {
            throw KeyManError.missing
        }
        return jwk
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
     *
     * For a key generated with a `UserAuthenticationPolicy`, pass a `context` already evaluated
     * with `.deviceOwnerAuthentication` to sign without another prompt; without one, the system
     * prompts on its own.
     */
    public static func signPayload(
        id: String, payload: [UInt8], accessGroup: String? = nil, context: LAContext? = nil
    ) -> [UInt8]? {
        do {
            return try signPayloadThrowing(
                id: id, payload: payload, accessGroup: accessGroup, context: context)
        } catch {
            print(error)
            return nil
        }
    }

    /**
     * Signs like `signPayload`, but throws instead of returning nil.
     *
     * Throws `UserAuthenticationRequiredError` when the key needs user authentication and the
     * user canceled or failed it (or the context couldn't authorize the key; authenticate and
     * retry), `UserAuthenticationUnavailableError` when the device can't authenticate the user
     * (no passcode, no biometrics enrolled or available, biometrics locked out, device locked),
     * `KeyManError.missing` when there is no key with this id, and `KeyManError.internalError`
     * otherwise. The key lookup and the signature are classified the same way.
     */
    public static func signPayloadThrowing(
        id: String, payload: [UInt8], accessGroup: String? = nil, context: LAContext? = nil
    ) throws -> [UInt8] {
        let key = try getSecretKeyThrowing(id: id, accessGroup: accessGroup, context: context)

        guard let data = CFDataCreate(kCFAllocatorDefault, payload, payload.count) else {
            throw KeyManError.internalError("Could not copy the payload")
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
            guard let cfError = error?.takeRetainedValue() else {
                throw KeyManError.signing
            }
            let nsError = cfError as Error as NSError
            if let authError = userAuthenticationError(alias: id, underlying: nsError) {
                throw authError
            }
            throw KeyManError.internalError("Could not sign with key '\(id)': \(nsError)")
        }

        return [UInt8](signature)
    }

    /// Payload signed by `isUserAuthenticationRequired`. It is neither a COSE Sig_structure (a
    /// CBOR array) nor a JWS signing input, so the signature can't pass for an mdoc device
    /// signature or a JWS.
    static let userAuthenticationProbePayload = [UInt8]("user-authentication-probe".utf8)

    /**
     * Whether the key needs the user to authenticate before it signs, that is, whether it was
     * generated with a `UserAuthenticationPolicy`.
     *
     * The Security framework has no public API to read the flags back from a key's access
     * control, so this signs a fixed probe payload with an `LAContext` whose
     * `interactionNotAllowed` is set: no prompt is shown, and a protected key fails with a user
     * authentication error. Returns false when the probe signs or there is no key with this id,
     * and true for any other failure (a protected key, or a lookup or signing error; prompting
     * then is harmless). It can also return true for an unprotected key while the device is
     * locked. The probe signature is discarded.
     */
    public static func isUserAuthenticationRequired(id: String, accessGroup: String? = nil) -> Bool {
        let context = LAContext()
        context.interactionNotAllowed = true
        do {
            _ = try signPayloadThrowing(
                id: id, payload: userAuthenticationProbePayload, accessGroup: accessGroup,
                context: context)
            return false
        } catch {
            return probeRequiresUserAuthentication(error)
        }
    }

    /// How `isUserAuthenticationRequired` reads the probe's error: false for a missing key, true
    /// otherwise.
    static func probeRequiresUserAuthentication(_ error: Error) -> Bool {
        if case KeyManError.missing = error {
            return false
        }
        return true
    }

    /// The `UserAuthenticationRequiredError` or `UserAuthenticationUnavailableError` for a
    /// keychain or signing error, or nil when the error isn't about user authentication.
    static func userAuthenticationError(alias: String, underlying: NSError) -> Error? {
        switch UserAuthenticationFailureKind.classify(
            domain: underlying.domain, code: underlying.code) {
        case .required:
            return UserAuthenticationRequiredError(alias: alias, underlying: underlying)
        case .unavailable:
            return UserAuthenticationUnavailableError(alias: alias, underlying: underlying)
        case nil:
            return nil
        }
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
    private let context: LAContext?

    init(alias: String, jwkString: String, accessGroup: String? = nil, context: LAContext? = nil) {
        self.alias = alias
        self.jwkString = jwkString
        self.accessGroup = accessGroup
        self.context = context
    }

    public func jwk() throws -> String {
        return jwkString
    }

    /// Throws `CryptoError.General`, the error type the Rust `SigningKey` trait declares: any
    /// other error crossing the callback makes the Rust side panic. When signing failed for user
    /// authentication, the message starts with `UserAuthenticationRequiredError.messagePrefix`
    /// (authenticate and retry) or `UserAuthenticationUnavailableError.messagePrefix` (the
    /// device can't authenticate the user).
    public func sign(payload: Data) throws -> Data {
        let signature: [UInt8]
        do {
            signature = try KeyManager.signPayloadThrowing(
                id: alias, payload: [UInt8](payload), accessGroup: accessGroup, context: context)
        } catch {
            throw CryptoError.General(
                (error as? UserAuthenticationError)?.message
                    ?? "Could not sign with key '\(alias)': \(error)")
        }
        guard
            let normalizedSignature: Data =
                CryptoCurveUtils.secp256r1().ensureRawFixedWidthSignatureEncoding(
                    bytes: Data(signature))
        else {
            throw CryptoError.General("Unrecognized signature format from key '\(alias)'")
        }
        return normalizedSignature
    }
}

/// An error from a key that requires user authentication: `UserAuthenticationRequiredError` or
/// `UserAuthenticationUnavailableError`. `message` starts with the type's `messagePrefix`, and
/// `errorDescription` and `description` are `message`, so the marker survives whichever way the
/// error is turned into text (for example by Pigeon or uniffi).
public protocol UserAuthenticationError: LocalizedError, CustomStringConvertible {
    var message: String { get }
}

extension UserAuthenticationError {
    public var errorDescription: String? { message }
    public var description: String { message }
}

/// Why using a key that requires user authentication failed.
public enum UserAuthenticationFailureKind: Equatable, Sendable {
    /// The user canceled or failed authentication, or the `LAContext` couldn't authorize the
    /// key. Authenticate and retry.
    case required
    /// The device can't authenticate the user now: no passcode set, no biometrics enrolled or
    /// available, biometrics locked out, or the device is locked. Retrying doesn't help until
    /// the user sets a passcode, enrolls biometrics or unlocks the device.
    case unavailable

    /// Classifies a keychain or signing error by domain and code, or returns nil when the error
    /// isn't about user authentication.
    ///
    /// - `required`: `LAError` `.userCancel`, `.userFallback`, `.systemCancel`, `.appCancel`,
    ///   `.authenticationFailed`, `.notInteractive`, `.invalidContext` (and any other `LAError`
    ///   code not listed below), `errSecUserCanceled`, `errSecAuthFailed`.
    /// - `unavailable`: `LAError` `.passcodeNotSet`, `.biometryNotEnrolled`,
    ///   `.biometryNotAvailable`, `.biometryLockout`, and `errSecInteractionNotAllowed` (device
    ///   locked).
    public static func classify(domain: String, code: Int) -> UserAuthenticationFailureKind? {
        switch domain {
        case LAErrorDomain:
            switch LAError.Code(rawValue: code) {
            case .passcodeNotSet, .biometryNotEnrolled, .biometryNotAvailable, .biometryLockout:
                return .unavailable
            default:
                return .required
            }
        case NSOSStatusErrorDomain:
            switch OSStatus(truncatingIfNeeded: code) {
            case errSecUserCanceled, errSecAuthFailed:
                return .required
            case errSecInteractionNotAllowed:
                return .unavailable
            default:
                return nil
            }
        default:
            return nil
        }
    }
}

/// Thrown when signing with a key that requires user authentication fails because the user
/// canceled or failed authentication, or the given `LAContext` couldn't authorize the key.
/// Authenticate (for example by evaluating a `UserAuthenticationPolicy.makeContext()`) and retry.
public struct UserAuthenticationRequiredError: Error, UserAuthenticationError {
    /// Prefix of `message`, also used when the error crosses into Rust. Same as Android's
    /// `UserAuthenticationRequiredException.MESSAGE_PREFIX`.
    public static let messagePrefix = "User authentication required"

    public let alias: String
    public let underlying: NSError?

    public init(alias: String, underlying: NSError? = nil) {
        self.alias = alias
        self.underlying = underlying
    }

    public var message: String {
        "\(Self.messagePrefix): key '\(alias)' needs user authentication"
            + (underlying.map { " (\($0.domain) \($0.code))" } ?? "")
    }
}

/// Thrown when a key that requires user authentication can't be created or used because the
/// device can't authenticate the user: no passcode set, no biometrics enrolled or available,
/// biometrics locked out, or the device is locked. Retrying doesn't help; ask the user to set a
/// device passcode or enroll biometrics (or to unlock the device).
public struct UserAuthenticationUnavailableError: Error, UserAuthenticationError {
    /// Prefix of `message`, also used when the error crosses into Rust. Same as Android's
    /// `UserAuthenticationUnavailableException.MESSAGE_PREFIX`.
    public static let messagePrefix = "User authentication unavailable"

    public let alias: String
    public let underlying: NSError?

    public init(alias: String, underlying: NSError? = nil) {
        self.alias = alias
        self.underlying = underlying
    }

    public var message: String {
        "\(Self.messagePrefix): key '\(alias)' needs user authentication, which the device"
            + " can't provide; set a device passcode or enroll biometrics"
            + (underlying.map { " (\($0.domain) \($0.code))" } ?? "")
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
