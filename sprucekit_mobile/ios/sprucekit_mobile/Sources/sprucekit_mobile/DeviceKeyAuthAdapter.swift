import Foundation
import LocalAuthentication
import SpruceIDMobileSdk
import SpruceIDMobileSdkRs

/// Plugin-wide user-authentication state for device keys: the policy applied to new device
/// keys, and the `LAContext` from the last successful `DeviceKeyAuth.authenticate`.
final class DeviceKeyAuthState: @unchecked Sendable {
    static let shared = DeviceKeyAuthState()

    private let lock = NSLock()
    private var _policy: UserAuthenticationPolicy?
    private var context: LAContext?
    private var evaluatedAt: Date?

    /// The policy for device keys created from now on; nil when disabled.
    var policy: UserAuthenticationPolicy? {
        get {
            lock.lock()
            defer { lock.unlock() }
            return _policy
        }
        set {
            lock.lock()
            defer { lock.unlock() }
            _policy = newValue
        }
    }

    /// Completions of the `authenticate` calls waiting on the prompt that is showing; nil when
    /// no prompt is showing.
    private var pendingAuthentications: [(Bool) -> Void]?

    /// Stores a context the user just authenticated.
    ///
    /// The previous context is only dropped, not invalidated: a signing key or a queued
    /// presentation may still hold it.
    func setContext(_ context: LAContext) {
        lock.lock()
        defer { lock.unlock() }
        self.context = context
        evaluatedAt = Date()
    }

    /// The last authenticated context, or nil if there is none or it is older than the
    /// policy's timeout (the default timeout when the policy is disabled).
    func currentContext() -> LAContext? {
        lock.lock()
        defer { lock.unlock() }
        guard let context = context, let evaluatedAt = evaluatedAt else { return nil }
        let timeout = TimeInterval(
            _policy?.timeoutSeconds ?? UserAuthenticationPolicy.defaultTimeoutSeconds)
        if Date().timeIntervalSince(evaluatedAt) > timeout {
            // Dropped, not invalidated: a signing key may still hold it.
            self.context = nil
            self.evaluatedAt = nil
            return nil
        }
        return context
    }

    /// Registers `completion` for the prompt that is showing, or for a new one. Returns true
    /// when the caller must show the prompt (no prompt was showing).
    func joinAuthentication(_ completion: @escaping (Bool) -> Void) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        if pendingAuthentications != nil {
            pendingAuthentications?.append(completion)
            return false
        }
        pendingAuthentications = [completion]
        return true
    }

    /// Ends the prompt that is showing and returns the completions waiting on it.
    func finishAuthentication() -> [(Bool) -> Void] {
        lock.lock()
        defer { lock.unlock() }
        let completions = pendingAuthentications ?? []
        pendingAuthentications = nil
        return completions
    }
}

/// A key store whose signing keys use the current `DeviceKeyAuthState` context, looked up when
/// the key is fetched (so a context evaluated after the flow started is still used).
final class DeviceKeyStore: SpruceIDMobileSdkRs.KeyStore, @unchecked Sendable {
    func getSigningKey(alias: SpruceIDMobileSdkRs.KeyAlias) throws -> any SpruceIDMobileSdkRs.SigningKey {
        try KeyManager(context: DeviceKeyAuthState.shared.currentContext())
            .getSigningKey(alias: alias)
    }
}

/// DeviceKeyAuth Pigeon implementation
class DeviceKeyAuthAdapter: DeviceKeyAuth {
    func setPolicy(enabled: Bool, timeoutSeconds: Int64, allowDeviceCredential: Bool) throws {
        guard enabled else {
            DeviceKeyAuthState.shared.policy = nil
            return
        }
        guard timeoutSeconds > 0 else {
            throw DeviceKeyAuthPigeonError(
                code: "invalid_timeout",
                message: "timeoutSeconds must be greater than 0",
                details: nil
            )
        }
        DeviceKeyAuthState.shared.policy = UserAuthenticationPolicy(
            timeoutSeconds: Int(timeoutSeconds),
            allowDeviceCredential: allowDeviceCredential
        )
    }

    func authenticate(reason: String, completion: @escaping (Result<Bool, Error>) -> Void) {
        // Single-flight: a call while a prompt is showing joins it and gets its result.
        let mustPrompt = DeviceKeyAuthState.shared.joinAuthentication { success in
            completion(.success(success))
        }
        guard mustPrompt else { return }

        let policy = DeviceKeyAuthState.shared.policy
        let context = policy?.makeContext() ?? LAContext()
        // Biometric-only keys (.biometryCurrentSet) need a biometric evaluation; a passcode
        // fallback wouldn't authorize them.
        let laPolicy = policy?.evaluationPolicy ?? .deviceOwnerAuthentication
        context.evaluatePolicy(laPolicy, localizedReason: reason) { success, error in
            if success {
                DeviceKeyAuthState.shared.setContext(context)
            } else if let error = error {
                print("DeviceKeyAuth: authentication failed: \(error.localizedDescription)")
            }
            let completions = DeviceKeyAuthState.shared.finishAuthentication()
            DispatchQueue.main.async {
                completions.forEach { $0(success) }
            }
        }
    }
}
