package com.spruceid.mobile.sdk

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.coroutines.resume

private const val TAG = "UserAuthentication"

/**
 * Shows a `BiometricPrompt` and suspends until the user authenticates or dismisses it.
 *
 * Use it before signing with a key generated with a [UserAuthenticationPolicy]: a successful
 * strong authentication authorizes the key for the policy's `timeoutSeconds`. No
 * `CryptoObject` is used, so the signature must happen inside that window.
 *
 * Below API 30, `BiometricPrompt` can't offer the device credential in some cases (see
 * [promptMethod]); there, when the device credential is allowed and no class 3 biometric is
 * enrolled, the system "confirm device credential" screen is shown instead.
 *
 * Returns false, and dismisses the prompt, if [activity] is destroyed while it is showing.
 *
 * @param activity the activity that hosts the prompt.
 * @param title the prompt title.
 * @param subtitle the prompt subtitle, for example who receives the data.
 * @param allowedAuthenticators the `BiometricManager.Authenticators` the user may use. Defaults
 * to `BIOMETRIC_STRONG or DEVICE_CREDENTIAL`. Don't include `BIOMETRIC_WEAK`: a class 2
 * biometric doesn't authorize keystore keys.
 * @param negativeButtonText the cancel button label, shown when `DEVICE_CREDENTIAL` isn't allowed.
 * @return true if the user authenticated, false if they canceled, authentication failed, the
 * device has no secure lock screen (device credential fallback), or [activity] was destroyed.
 */
suspend fun authenticateUser(
    activity: FragmentActivity,
    title: String,
    subtitle: String? = null,
    allowedAuthenticators: Int = UserAuthenticationPolicy.DEFAULT_PROMPT_AUTHENTICATORS,
    negativeButtonText: String = "Cancel",
): Boolean = withContext(Dispatchers.Main) {
    if (activity.lifecycle.currentState == Lifecycle.State.DESTROYED) {
        return@withContext false
    }
    val strongBiometricAvailable = BiometricManager.from(activity)
        .canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS
    when (val method = promptMethod(allowedAuthenticators, Build.VERSION.SDK_INT, strongBiometricAvailable)) {
        is PromptMethod.Biometric ->
            showBiometricPrompt(activity, title, subtitle, method.authenticators, negativeButtonText)

        PromptMethod.ConfirmDeviceCredential -> confirmDeviceCredential(activity, title, subtitle)
    }
}

private suspend fun showBiometricPrompt(
    activity: FragmentActivity,
    title: String,
    subtitle: String?,
    authenticators: Int,
    negativeButtonText: String,
): Boolean {
    val promptInfo = BiometricPrompt.PromptInfo.Builder()
        .setTitle(title)
        .apply {
            if (subtitle != null) {
                setSubtitle(subtitle)
            }
            if ((authenticators and DEVICE_CREDENTIAL) == 0) {
                setNegativeButtonText(negativeButtonText)
            }
        }
        .setConfirmationRequired(false)
        .setAllowedAuthenticators(authenticators)
        .build()

    return awaitUnlessDestroyed(activity) { resume ->
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                // onAuthenticationFailed is a single rejected attempt; the prompt stays open, so
                // only the terminal callbacks resume.
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    Log.d(TAG, "authentication error ($errorCode): $errString")
                    resume(false)
                }

                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    resume(true)
                }
            })
        prompt.authenticate(promptInfo)
        return@awaitUnlessDestroyed { prompt.cancelAuthentication() }
    }
}

/**
 * Shows the system "confirm device credential" screen. Used below API 30, where
 * `BiometricPrompt` can't offer the device credential without a class 3 biometric.
 */
private suspend fun confirmDeviceCredential(
    activity: FragmentActivity,
    title: String,
    subtitle: String?,
): Boolean {
    val keyguard = activity.getSystemService(KeyguardManager::class.java)
    if (keyguard == null || !keyguard.isDeviceSecure) {
        Log.d(TAG, "no secure lock screen: can't confirm the device credential")
        return false
    }
    @Suppress("DEPRECATION")
    val intent: Intent = keyguard.createConfirmDeviceCredentialIntent(title, subtitle) ?: return false

    var launcher: ActivityResultLauncher<Intent>? = null
    try {
        return awaitUnlessDestroyed(activity) { resume ->
            launcher = activity.activityResultRegistry.register(
                "com.spruceid.mobile.sdk.confirmDeviceCredential.${UUID.randomUUID()}",
                ActivityResultContracts.StartActivityForResult()
            ) { result -> resume(result.resultCode == Activity.RESULT_OK) }
                .also { it.launch(intent) }
            // The confirm screen is another activity and can't be dismissed from here.
            return@awaitUnlessDestroyed {}
        }
    } finally {
        launcher?.unregister()
    }
}

/**
 * Starts an authentication with [start] and suspends until it reports a result, or until
 * [activity] is destroyed (result false). [start] gets a callback for the result (only the first
 * call counts) and returns a function that cancels the authentication; that function runs on
 * destroy and on coroutine cancellation.
 */
private suspend fun awaitUnlessDestroyed(
    activity: FragmentActivity,
    start: (resume: (Boolean) -> Unit) -> () -> Unit,
): Boolean {
    var observer: LifecycleEventObserver? = null
    try {
        return suspendCancellableCoroutine { continuation ->
            val resume = { result: Boolean ->
                if (continuation.isActive) continuation.resume(result)
            }
            val cancel = start(resume)
            val destroyObserver = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_DESTROY) {
                    cancel()
                    resume(false)
                }
            }
            observer = destroyObserver
            activity.lifecycle.addObserver(destroyObserver)
            continuation.invokeOnCancellation { cancel() }
        }
    } finally {
        observer?.let { activity.lifecycle.removeObserver(it) }
    }
}

/** How [authenticateUser] asks the user to authenticate. */
internal sealed interface PromptMethod {
    /** A `BiometricPrompt` with these `BiometricManager.Authenticators`. */
    data class Biometric(val authenticators: Int) : PromptMethod

    /** The `KeyguardManager` "confirm device credential" screen. */
    object ConfirmDeviceCredential : PromptMethod
}

/**
 * Picks how to prompt for [requested] authenticators on [sdkInt].
 *
 * `BiometricPrompt` doesn't support `DEVICE_CREDENTIAL` alone below API 30, nor
 * `BIOMETRIC_STRONG or DEVICE_CREDENTIAL` on API 28–29. In those cases, with a class 3 biometric
 * enrolled ([strongBiometricAvailable]) and `BIOMETRIC_STRONG` requested, the prompt asks for the
 * biometric alone; otherwise the device credential is confirmed with `KeyguardManager`. Never
 * falls back to `BIOMETRIC_WEAK`: a class 2 biometric doesn't authorize keystore keys.
 */
internal fun promptMethod(requested: Int, sdkInt: Int, strongBiometricAvailable: Boolean): PromptMethod {
    val wantsDeviceCredential = (requested and DEVICE_CREDENTIAL) != 0
    if (!wantsDeviceCredential || sdkInt >= Build.VERSION_CODES.R) {
        return PromptMethod.Biometric(requested)
    }
    val wantsStrongBiometric = (requested and BIOMETRIC_STRONG) == BIOMETRIC_STRONG
    val promptSupportsCombination = sdkInt < Build.VERSION_CODES.P
    return when {
        wantsStrongBiometric && promptSupportsCombination -> PromptMethod.Biometric(requested)
        wantsStrongBiometric && strongBiometricAvailable -> PromptMethod.Biometric(BIOMETRIC_STRONG)
        else -> PromptMethod.ConfirmDeviceCredential
    }
}
