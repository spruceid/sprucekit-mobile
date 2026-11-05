//! Android-only JNI entry points that UniFFI can't express.

use jni::{
    errors::ThrowRuntimeExAndDefault,
    objects::{JClass, JObject},
    EnvUnowned,
};

/// Called by `com.spruceid.mobile.sdk.rs.RustlsPlatformVerifier.init`, which
/// the SDK's AndroidX Startup initializer runs when the app starts.
///
/// rustls-platform-verifier checks certificates through the Android trust
/// store and panics on the first TLS handshake if this hasn't run. Repeated
/// calls are no-ops: the verifier keeps the first JVM and Context it gets.
/// Errors and panics are thrown as a `RuntimeException`.
#[no_mangle]
pub extern "system" fn Java_com_spruceid_mobile_sdk_rs_RustlsPlatformVerifier_nativeInit<
    'caller,
>(
    mut env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    context: JObject<'caller>,
) {
    env.with_env(|env| rustls_platform_verifier::android::init_with_env(env, context))
        .resolve::<ThrowRuntimeExAndDefault>()
}
