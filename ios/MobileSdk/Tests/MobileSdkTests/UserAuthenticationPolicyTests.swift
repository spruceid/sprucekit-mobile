import LocalAuthentication
import XCTest

@testable import MobileSdk

final class UserAuthenticationPolicyTests: XCTestCase {
    func testDefaults() {
        let policy = UserAuthenticationPolicy()
        XCTAssertEqual(policy.timeoutSeconds, 30)
        XCTAssertTrue(policy.allowDeviceCredential)
    }

    func testDeviceCredentialUsesUserPresence() {
        let policy = UserAuthenticationPolicy(allowDeviceCredential: true)
        XCTAssertEqual(policy.accessControlFlags, [.privateKeyUsage, .userPresence])
        XCTAssertEqual(policy.evaluationPolicy, .deviceOwnerAuthentication)
    }

    func testBiometricOnlyUsesBiometryCurrentSet() {
        let policy = UserAuthenticationPolicy(allowDeviceCredential: false)
        XCTAssertEqual(policy.accessControlFlags, [.privateKeyUsage, .biometryCurrentSet])
        XCTAssertEqual(policy.evaluationPolicy, .deviceOwnerAuthenticationWithBiometrics)
    }

    func testReuseDurationIsCapped() {
        XCTAssertEqual(UserAuthenticationPolicy(timeoutSeconds: 30).reuseDuration, 30)
        XCTAssertEqual(UserAuthenticationPolicy(timeoutSeconds: -5).reuseDuration, 0)
        XCTAssertEqual(
            UserAuthenticationPolicy(timeoutSeconds: 100_000).reuseDuration,
            LATouchIDAuthenticationMaximumAllowableReuseDuration
        )
    }

    func testMakeContextAppliesReuseDuration() {
        let context = UserAuthenticationPolicy(timeoutSeconds: 10).makeContext()
        XCTAssertEqual(context.touchIDAuthenticationAllowableReuseDuration, 10)
    }

    func testRetryableFailuresAreClassifiedAsRequired() {
        let laCodes: [LAError.Code] = [
            .userCancel, .userFallback, .systemCancel, .appCancel, .authenticationFailed,
            .notInteractive, .invalidContext
        ]
        for code in laCodes {
            XCTAssertEqual(
                UserAuthenticationFailureKind.classify(domain: LAErrorDomain, code: code.rawValue),
                .required, "LAError \(code.rawValue)")
        }
        for status in [errSecUserCanceled, errSecAuthFailed] {
            XCTAssertEqual(
                UserAuthenticationFailureKind.classify(
                    domain: NSOSStatusErrorDomain, code: Int(status)),
                .required, "OSStatus \(status)")
        }
    }

    func testDeviceConditionsAreClassifiedAsUnavailable() {
        let laCodes: [LAError.Code] = [
            .passcodeNotSet, .biometryNotEnrolled, .biometryNotAvailable, .biometryLockout
        ]
        for code in laCodes {
            XCTAssertEqual(
                UserAuthenticationFailureKind.classify(domain: LAErrorDomain, code: code.rawValue),
                .unavailable, "LAError \(code.rawValue)")
        }
        XCTAssertEqual(
            UserAuthenticationFailureKind.classify(
                domain: NSOSStatusErrorDomain, code: Int(errSecInteractionNotAllowed)),
            .unavailable)
    }

    func testOtherErrorsAreNotUserAuthenticationFailures() {
        XCTAssertNil(
            UserAuthenticationFailureKind.classify(
                domain: NSOSStatusErrorDomain, code: Int(errSecItemNotFound)))
        XCTAssertNil(
            UserAuthenticationFailureKind.classify(
                domain: NSOSStatusErrorDomain, code: Int(errSecParam)))
        XCTAssertNil(UserAuthenticationFailureKind.classify(domain: "Other", code: -128))
    }

    func testUserAuthenticationErrorFromNSError() {
        let canceled = NSError(domain: LAErrorDomain, code: LAError.Code.userCancel.rawValue)
        XCTAssertTrue(
            KeyManager.userAuthenticationError(alias: "a", underlying: canceled)
                is UserAuthenticationRequiredError)
        let noPasscode = NSError(domain: LAErrorDomain, code: LAError.Code.passcodeNotSet.rawValue)
        XCTAssertTrue(
            KeyManager.userAuthenticationError(alias: "a", underlying: noPasscode)
                is UserAuthenticationUnavailableError)
        let notFound = NSError(domain: NSOSStatusErrorDomain, code: Int(errSecItemNotFound))
        XCTAssertNil(KeyManager.userAuthenticationError(alias: "a", underlying: notFound))
    }

    func testProbeTreatsOnlyAMissingKeyAsUnprotected() {
        XCTAssertFalse(KeyManager.probeRequiresUserAuthentication(KeyManError.missing))
        XCTAssertTrue(
            KeyManager.probeRequiresUserAuthentication(UserAuthenticationRequiredError(alias: "a")))
        XCTAssertTrue(
            KeyManager.probeRequiresUserAuthentication(
                UserAuthenticationUnavailableError(alias: "a")))
        XCTAssertTrue(
            KeyManager.probeRequiresUserAuthentication(KeyManError.internalError("lookup failed")))
    }

    func testProbePayloadIsNeitherASigStructureNorAJwsSigningInput() {
        let payload = KeyManager.userAuthenticationProbePayload
        // A COSE Sig_structure is a CBOR array: its first byte is 0x80-0x9f.
        XCTAssertFalse((0x80...0x9f).contains(payload.first ?? 0))
        // A JWS signing input is "header.payload".
        XCTAssertFalse(payload.contains(UInt8(ascii: ".")))
    }

    func testUserAuthenticationMessagesHavePrefixes() {
        let required = UserAuthenticationRequiredError(alias: "alias")
        XCTAssertTrue(required.message.hasPrefix("User authentication required"))
        XCTAssertEqual(required.message, required.errorDescription)
        let unavailable = UserAuthenticationUnavailableError(alias: "alias")
        XCTAssertTrue(unavailable.message.hasPrefix("User authentication unavailable"))
        XCTAssertEqual(unavailable.message, unavailable.errorDescription)
        XCTAssertEqual("\(unavailable)", unavailable.message)
    }
}
