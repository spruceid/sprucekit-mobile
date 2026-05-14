package com.spruceid.mobile.sdk.nfc

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.spruceid.mobile.sdk.rs.ReaderHandover

/** UI-facing phase for an NFC reader engagement. */
sealed class NfcReaderPhase {
    /** NFC hardware is not present on this device. */
    object Unsupported : NfcReaderPhase()

    /** NFC is turned off in system settings. */
    object Disabled : NfcReaderPhase()

    /** Reader mode is armed; waiting for a holder tap. */
    object WaitingForTag : NfcReaderPhase()

    /** A tap has been detected; the APDU exchange is in progress. */
    object Exchanging : NfcReaderPhase()

    /** Protocol-level failure. Reader mode stays armed; the next tap retries. */
    data class ProtocolError(val cause: Throwable) : NfcReaderPhase()
}

/**
 * One-stop Compose entry point for reader-side NFC engagement.
 *
 * Handles activity discovery, lifecycle binding, NFC adapter state, and
 * event-to-phase translation. Typical use from a verifier screen:
 *
 * ```
 * val phase by rememberNfcReaderEngagement(
 *     onHandover = { handover -> startBleSession(handover) },
 *     active = onNfcTab && state == State.SCANNING,
 * )
 * when (phase) {
 *     NfcReaderPhase.Unsupported -> Text("No NFC on this device")
 *     NfcReaderPhase.Disabled -> Text("Turn NFC on in settings")
 *     NfcReaderPhase.WaitingForTag -> Text("Tap the holder's phone")
 *     NfcReaderPhase.Exchanging -> CircularProgressIndicator()
 *     is NfcReaderPhase.ProtocolError -> Text(phase.cause.message ?: "Error")
 * }
 * ```
 *
 * Flip [active] off whenever the UI is not actively soliciting a tap
 * (different tab, transmitting over BLE, results screen). Reader mode
 * stays on so the device keeps the NFC controller in initiator role
 * (suppressing wallet pickers, foreign HCE services, and OS tag
 * dispatchers from triggering on stray taps) but events are not emitted
 * while inactive.
 *
 * If the hosting context is not a [ComponentActivity] (e.g. Compose
 * previews) the returned state stays at [NfcReaderPhase.Unsupported].
 */
@Composable
fun rememberNfcReaderEngagement(
    onHandover: (ReaderHandover) -> Unit,
    active: Boolean = true,
): State<NfcReaderPhase> {
    val context = LocalContext.current
    val activity = remember(context) { context.findComponentActivity() }
    val lifecycleOwner = LocalLifecycleOwner.current
    val onHandoverState = rememberUpdatedState(onHandover)

    val phase = remember { mutableStateOf<NfcReaderPhase>(NfcReaderPhase.WaitingForTag) }

    if (activity == null) {
        phase.value = NfcReaderPhase.Unsupported
        return phase
    }

    val engagement = remember(activity) {
        NfcReaderEngagement(activity) { event ->
            when (event) {
                is NfcReaderEngagement.Event.NfcUnsupported ->
                    phase.value = NfcReaderPhase.Unsupported
                is NfcReaderEngagement.Event.NfcDisabled ->
                    phase.value = NfcReaderPhase.Disabled
                is NfcReaderEngagement.Event.WaitingForTag ->
                    phase.value = NfcReaderPhase.WaitingForTag
                is NfcReaderEngagement.Event.Exchanging ->
                    phase.value = NfcReaderPhase.Exchanging
                is NfcReaderEngagement.Event.TransientError -> {
                    // WaitingForTag follows automatically; nothing to do.
                }
                is NfcReaderEngagement.Event.ProtocolError ->
                    phase.value = NfcReaderPhase.ProtocolError(event.cause)
                is NfcReaderEngagement.Event.Success ->
                    onHandoverState.value(event.handover)
            }
        }
    }

    DisposableEffect(engagement, lifecycleOwner) {
        // Apply the initial `active` value synchronously before binding to
        // the lifecycle: bindToLifecycle may fire ON_RESUME immediately if
        // the activity is already resumed, and we want the first tap (if
        // any) to honor the caller's `active=false` rather than the SDK
        // default of true.
        engagement.setActive(active)
        engagement.bindToLifecycle(lifecycleOwner)
        onDispose { engagement.release() }
    }

    LaunchedEffect(engagement, active) {
        engagement.setActive(active)
    }

    return phase
}

private tailrec fun Context.findComponentActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findComponentActivity()
    else -> null
}
