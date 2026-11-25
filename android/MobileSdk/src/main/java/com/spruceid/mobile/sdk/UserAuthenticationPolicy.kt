package com.spruceid.mobile.sdk

import android.annotation.SuppressLint
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL

/**
 * User-authentication requirement for a signing key, fixed when the key is generated.
 *
 * A key generated with a policy can only sign within [timeoutSeconds] of a successful strong
 * user authentication (a class 3 biometric, or the device PIN, pattern or password when
 * [allowDeviceCredential] is true). Any such authentication counts, including unlocking the
 * lock screen. Signing outside that window fails with [UserAuthenticationRequiredException];
 * authenticate first, for example with [authenticateUser], then retry.
 *
 * The policy can't be added to or removed from an existing key: keys generated without one stay
 * unprotected until they are replaced.
 *
 * @property timeoutSeconds how long, in seconds, a successful authentication authorizes the key.
 * Must be greater than 0: per-use keys (which need a `BiometricPrompt.CryptoObject` for every
 * signature) are not supported.
 * @property allowDeviceCredential whether the device PIN, pattern or password authorizes the
 * key, in addition to a class 3 biometric. On API 26–29 the platform can't restrict the
 * authenticator type, so any lock-screen authentication authorizes the key regardless of this
 * flag.
 */
data class UserAuthenticationPolicy(
    val timeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS,
    val allowDeviceCredential: Boolean = true,
) {
    init {
        require(timeoutSeconds > 0) { "timeoutSeconds must be greater than 0" }
    }

    /**
     * The `KeyProperties.AUTH_*` flags for `KeyGenParameterSpec.Builder.setUserAuthenticationParameters`
     * (API 30+).
     */
    // The AUTH_* constants are inlined at compile time and only passed to the API 30+ setter.
    @SuppressLint("InlinedApi")
    fun keystoreAuthenticators(): Int =
        if (allowDeviceCredential) {
            KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL
        } else {
            KeyProperties.AUTH_BIOMETRIC_STRONG
        }

    /**
     * The `BiometricManager.Authenticators` flags for a `BiometricPrompt` that authorizes a key
     * with this policy. `BIOMETRIC_WEAK` is never used: a class 2 biometric doesn't authorize
     * keystore keys.
     */
    fun promptAuthenticators(): Int =
        if (allowDeviceCredential) {
            DEFAULT_PROMPT_AUTHENTICATORS
        } else {
            BIOMETRIC_STRONG
        }

    /** Applies this policy to a key spec being built for [sdkInt]. */
    // The API 30 setter is guarded by the injectable `sdkInt`, which lint can't follow.
    @SuppressLint("NewApi")
    internal fun applyTo(builder: KeyGenParameterSpec.Builder, sdkInt: Int = Build.VERSION.SDK_INT) {
        builder.setUserAuthenticationRequired(true)
        if (sdkInt >= Build.VERSION_CODES.R) {
            builder.setUserAuthenticationParameters(timeoutSeconds, keystoreAuthenticators())
        } else {
            // API 26–29: the deprecated setter can't restrict the authenticator type.
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(timeoutSeconds)
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_SECONDS = 30

        /** `BIOMETRIC_STRONG or DEVICE_CREDENTIAL`. */
        const val DEFAULT_PROMPT_AUTHENTICATORS = BIOMETRIC_STRONG or DEVICE_CREDENTIAL
    }
}

/**
 * Thrown when signing with a key that requires user authentication and the user hasn't
 * authenticated within the key's validity window. Authenticate (for example with
 * [authenticateUser]) and retry.
 */
class UserAuthenticationRequiredException(val alias: String, cause: Throwable? = null) :
    Exception("$MESSAGE_PREFIX: key '$alias' needs a recent user authentication", cause) {
    companion object {
        /** Prefix of the message, also used when the error crosses into Rust as a `CryptoException`. */
        const val MESSAGE_PREFIX = "User authentication required"
    }
}

/**
 * Thrown when the device can't authenticate the user for a key with a [UserAuthenticationPolicy],
 * so retrying doesn't help:
 * - the key can't be generated, usually because the device has no secure lock screen, or no
 *   class 3 biometric is enrolled and the policy doesn't allow the device credential (no key is
 *   created without the policy in that case);
 * - the key was permanently invalidated ([android.security.keystore.KeyPermanentlyInvalidatedException]),
 *   because the secure lock screen was removed or the enrolled biometrics changed.
 *
 * The message starts with [MESSAGE_PREFIX].
 */
class UserAuthenticationUnavailableException(
    val alias: String,
    cause: Throwable? = null,
    detail: String = "can't be created with user authentication: set a secure lock screen " +
            "(and enroll a strong biometric if the device credential isn't allowed)",
) : Exception("$MESSAGE_PREFIX: key '$alias' $detail", cause) {
    companion object {
        /** Prefix of the message, also used when the error crosses into Rust as a `CryptoException`. */
        const val MESSAGE_PREFIX = "User authentication unavailable"

        internal const val INVALIDATED_DETAIL =
            "was permanently invalidated (the secure lock screen was removed or the enrolled " +
                    "biometrics changed) and can't sign anymore"
    }
}
