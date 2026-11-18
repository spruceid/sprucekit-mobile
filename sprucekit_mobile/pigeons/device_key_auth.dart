import 'package:pigeon/pigeon.dart';

@ConfigurePigeon(
  PigeonOptions(
    dartOut: 'lib/pigeon/device_key_auth.g.dart',
    dartOptions: DartOptions(),
    kotlinOut:
        'android/src/main/kotlin/com/spruceid/sprucekit_mobile/DeviceKeyAuth.g.kt',
    kotlinOptions: KotlinOptions(
      errorClassName: 'DeviceKeyAuthFlutterError',
      package: 'com.spruceid.sprucekit_mobile',
    ),
    swiftOut:
        'ios/sprucekit_mobile/Sources/sprucekit_mobile/DeviceKeyAuth.g.swift',
    swiftOptions: SwiftOptions(errorClassName: 'DeviceKeyAuthPigeonError'),
    dartPackageName: 'sprucekit_mobile',
  ),
)
/// User authentication for device keys
///
/// Lets the app require user authentication (biometrics or the device
/// passcode) for the signing keys the plugin creates as device keys: the
/// OID4VCI key (`Oid4vci`), the OID4VP keys (`Oid4vp`, `Oid4vpMdoc`) and the
/// keys of mock mDLs (`SpruceUtils`). VCALM keys are not affected.
///
/// The setting is off by default. Apps typically enable it at startup, and
/// leave it off in debug builds:
///
/// ```dart
/// await DeviceKeyAuth().setPolicy(!kDebugMode, 30, true);
/// ```
///
/// Key protection is fixed when a key is created. Keys created before the
/// policy was enabled stay unprotected, and keys created while it was enabled
/// stay protected after it is disabled. Replacing a key means re-issuing the
/// credentials bound to it.
///
/// Before signing with a protected key:
/// - Android: call [authenticate] (or run a `BiometricPrompt` with
///   `BIOMETRIC_STRONG`, plus `DEVICE_CREDENTIAL` if allowed) and sign within
///   `timeoutSeconds`. Signing outside that window fails.
/// - iOS: call [authenticate] so the signature uses the evaluated context
///   without a second prompt. Without it, the system prompts on its own when
///   a protected key signs.
///
/// On iOS, declare `NSFaceIDUsageDescription` in the Info.plist of the app
/// and of any extension that signs with the keys (for example a DC API
/// extension). Without it, iOS doesn't offer Face ID and asks for the device
/// passcode instead.
///
/// Errors from protected keys carry one of two markers. The error message
/// *contains* the marker; it doesn't necessarily start with it, because the
/// native and Rust layers can wrap the message.
/// - "User authentication required": the user didn't authenticate (canceled,
///   failed, or the Android validity window passed). Call [authenticate]
///   and retry.
/// - "User authentication unavailable": the device can't authenticate the
///   user, so a retry doesn't help: no device passcode or secure lock screen,
///   no biometrics enrolled (when [setPolicy]'s `allowDeviceCredential` is
///   false), biometrics locked out, or (iOS) the device is locked. On
///   Android it also means a key was permanently invalidated because the lock
///   screen was removed or the enrolled biometrics changed. Ask the user to
///   set a passcode or enroll biometrics. Creating a protected key fails with
///   this marker, for example when an OID4VCI session or an OID4VP holder
///   is created.
@HostApi()
abstract class DeviceKeyAuth {
  /// Set the user-authentication policy for device keys the plugin creates
  /// from now on.
  ///
  /// @param enabled Whether new device keys require user authentication
  /// @param timeoutSeconds Android: how long a successful authentication
  ///   authorizes the key (must be > 0). iOS: how long the context from
  ///   [authenticate] is reused, and how recent a device unlock may be to
  ///   skip the prompt (capped by the system).
  /// @param allowDeviceCredential Whether the device PIN, pattern or passcode
  ///   authorizes the key, in addition to strong biometrics. Below Android 11
  ///   (API 30) the platform can't restrict a key to biometrics, so there the
  ///   device PIN, pattern or password also authorizes a key created with
  ///   `allowDeviceCredential` false.
  void setPolicy(bool enabled, int timeoutSeconds, bool allowDeviceCredential);

  /// Ask the user to authenticate before a signing operation.
  ///
  /// Android: shows a `BiometricPrompt` on the host activity, which must be
  /// a `FragmentActivity` (for example `FlutterFragmentActivity`); otherwise
  /// this fails with an error. On Android 9 and 10, when the device
  /// credential is allowed and no strong biometric is enrolled, the system
  /// "confirm device credential" screen is shown instead. If the plugin
  /// detaches from the activity while the prompt is showing (for example the
  /// activity is destroyed), the prompt is canceled and this returns false.
  /// iOS: evaluates an `LAContext` and keeps it for the plugin's signing
  /// operations within `timeoutSeconds`.
  ///
  /// Only one prompt shows at a time: a call made while a prompt is showing
  /// doesn't show a second one, and completes with the result of the prompt
  /// that is showing (its `reason` is not shown).
  ///
  /// @param reason Shown to the user, for example who receives the data
  /// @return true if the user authenticated, false if they canceled,
  ///   authentication failed, or the prompt was canceled
  @async
  bool authenticate(String reason);
}
