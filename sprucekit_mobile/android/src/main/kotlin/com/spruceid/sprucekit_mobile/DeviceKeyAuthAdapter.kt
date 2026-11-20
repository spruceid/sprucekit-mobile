package com.spruceid.sprucekit_mobile

import android.util.Log
import androidx.fragment.app.FragmentActivity
import com.spruceid.mobile.sdk.UserAuthenticationPolicy
import com.spruceid.mobile.sdk.authenticateUser
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Plugin-wide user-authentication policy for the device keys the plugin creates
 * (OID4VCI, OID4VP and mock mDL keys). Null when disabled, the default.
 */
object DeviceKeyAuthState {
    @Volatile
    var policy: UserAuthenticationPolicy? = null
}

/**
 * DeviceKeyAuth Pigeon implementation.
 *
 * [authenticate] shows a `BiometricPrompt` on the host activity, which must be a
 * [FragmentActivity] (for example `FlutterFragmentActivity`). It is single-flight: a call while
 * a prompt is showing joins it and gets its result. When the plugin detaches from the activity
 * (or the activity changes) while a prompt is showing, the prompt is canceled and the pending
 * calls complete with false.
 *
 * Pigeon host calls and the [ActivityPluginBinding] callbacks run on the main thread, so the
 * pending state is only touched there.
 */
class DeviceKeyAuthAdapter : DeviceKeyAuth {
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var activityBinding: ActivityPluginBinding? = null

    /** The authentication in progress, or null. */
    private var inFlight: Job? = null

    /** Callbacks waiting on [inFlight]. */
    private val pendingCallbacks = mutableListOf<(Result<Boolean>) -> Unit>()

    fun setActivityBinding(binding: ActivityPluginBinding?) {
        if (binding?.activity !== activityBinding?.activity) {
            cancelInFlight()
        }
        activityBinding = binding
    }

    fun dispose() {
        cancelInFlight()
        mainScope.cancel()
        activityBinding = null
    }

    private fun cancelInFlight() {
        if (inFlight == null) return
        // Canceling the coroutine cancels the prompt (see authenticateUser).
        inFlight?.cancel()
        completePending(Result.success(false))
    }

    private fun completePending(result: Result<Boolean>) {
        val callbacks = pendingCallbacks.toList()
        pendingCallbacks.clear()
        inFlight = null
        callbacks.forEach { it(result) }
    }

    override fun setPolicy(enabled: Boolean, timeoutSeconds: Long, allowDeviceCredential: Boolean) {
        if (!enabled) {
            DeviceKeyAuthState.policy = null
            return
        }
        if (timeoutSeconds <= 0 || timeoutSeconds > Int.MAX_VALUE) {
            throw DeviceKeyAuthFlutterError(
                "invalid_timeout",
                "timeoutSeconds must be between 1 and ${Int.MAX_VALUE}"
            )
        }
        DeviceKeyAuthState.policy = UserAuthenticationPolicy(
            timeoutSeconds = timeoutSeconds.toInt(),
            allowDeviceCredential = allowDeviceCredential,
        )
    }

    override fun authenticate(reason: String, callback: (Result<Boolean>) -> Unit) {
        if (inFlight != null) {
            pendingCallbacks += callback
            return
        }
        val activity = activityBinding?.activity as? FragmentActivity
        if (activity == null) {
            callback(
                Result.failure(
                    DeviceKeyAuthFlutterError(
                        "no_fragment_activity",
                        "The host activity must be a FragmentActivity (e.g. FlutterFragmentActivity) to show BiometricPrompt"
                    )
                )
            )
            return
        }
        val authenticators = DeviceKeyAuthState.policy?.promptAuthenticators()
            ?: UserAuthenticationPolicy.DEFAULT_PROMPT_AUTHENTICATORS
        pendingCallbacks += callback
        inFlight = mainScope.launch {
            val result = try {
                Result.success(
                    authenticateUser(
                        activity,
                        title = "Verify your identity",
                        subtitle = reason,
                        allowedAuthenticators = authenticators,
                    )
                )
            } catch (e: CancellationException) {
                // cancelInFlight already completed the pending callbacks.
                throw e
            } catch (e: Exception) {
                Log.e("DeviceKeyAuthAdapter", "authentication failed", e)
                Result.failure(DeviceKeyAuthFlutterError("authentication_error", e.localizedMessage))
            }
            completePending(result)
        }
    }
}
