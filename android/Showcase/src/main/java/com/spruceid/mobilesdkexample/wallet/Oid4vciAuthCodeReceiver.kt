package com.spruceid.mobilesdkexample.wallet

import android.net.Uri
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/// Process-wide bridge from MainActivity.onNewIntent (deep-link callback from
/// the issuer's authorization browser session) to the Compose
/// HandleOID4VCIView that is waiting for the authorization code.
///
/// A replay-zero, capacity-1 shared flow: a redirect URI delivered before the
/// view subscribes is buffered; subsequent late subscribers see nothing
/// (each new auth-code flow starts a fresh await).
object Oid4vciAuthCodeReceiver {
    private val _flow = MutableSharedFlow<Uri>(replay = 0, extraBufferCapacity = 1)
    val flow = _flow.asSharedFlow()

    fun publish(uri: Uri) {
        _flow.tryEmit(uri)
    }
}
