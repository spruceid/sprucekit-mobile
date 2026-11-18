import Foundation
import LocalAuthentication
import Security

/// User-authentication requirement for a signing key, fixed when the key is generated.
///
/// A key generated with a policy needs the user to authenticate before each signature. Without
/// an evaluated `LAContext` the system shows its own prompt when the key signs; pass a context
/// evaluated with `.deviceOwnerAuthentication` (see `KeyManager(context:)`) to sign without a
/// second prompt.
///
/// The policy can't be added to or removed from an existing key: keys generated without one
/// stay unprotected until they are replaced.
///
/// Apps that use a policy must declare `NSFaceIDUsageDescription` in the Info.plist of the main
/// app and of any extension that signs with the key (for example a Digital Credentials API
/// extension). Without it, iOS doesn't offer Face ID and asks for the device passcode instead.
public struct UserAuthenticationPolicy: Equatable, Sendable {
    public static let defaultTimeoutSeconds = 30

    /// How long, in seconds, a recent device unlock lets an `LAContext` evaluation succeed
    /// without prompting again (`touchIDAuthenticationAllowableReuseDuration`), capped at
    /// `LATouchIDAuthenticationMaximumAllowableReuseDuration`. On Android this is the key's
    /// validity window.
    public var timeoutSeconds: Int
    /// Whether the device passcode authorizes the key (`.userPresence`). When false, only the
    /// currently enrolled biometrics do (`.biometryCurrentSet`), and changing the enrolled
    /// biometrics makes the key unusable.
    public var allowDeviceCredential: Bool

    public init(
        timeoutSeconds: Int = UserAuthenticationPolicy.defaultTimeoutSeconds,
        allowDeviceCredential: Bool = true
    ) {
        self.timeoutSeconds = timeoutSeconds
        self.allowDeviceCredential = allowDeviceCredential
    }

    /// The access-control flags for a Secure Enclave signing key with this policy.
    public var accessControlFlags: SecAccessControlCreateFlags {
        allowDeviceCredential
            ? [.privateKeyUsage, .userPresence]
            : [.privateKeyUsage, .biometryCurrentSet]
    }

    /// The `LAPolicy` to evaluate before signing with a key that has this policy.
    public var evaluationPolicy: LAPolicy {
        allowDeviceCredential
            ? .deviceOwnerAuthentication
            : .deviceOwnerAuthenticationWithBiometrics
    }

    /// `timeoutSeconds` as an `LAContext` reuse duration, within the range the system accepts.
    public var reuseDuration: TimeInterval {
        min(
            max(TimeInterval(timeoutSeconds), 0),
            LATouchIDAuthenticationMaximumAllowableReuseDuration
        )
    }

    /// Creates an `LAContext` configured with this policy's reuse duration, for the caller to
    /// evaluate before signing.
    public func makeContext() -> LAContext {
        let context = LAContext()
        context.touchIDAuthenticationAllowableReuseDuration = reuseDuration
        return context
    }
}
