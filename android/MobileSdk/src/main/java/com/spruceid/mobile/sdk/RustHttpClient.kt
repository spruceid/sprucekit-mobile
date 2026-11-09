package com.spruceid.mobile.sdk

import android.util.Log
import com.spruceid.mobile.sdk.rs.configureHttpClient
import com.spruceid.mobile.toolkit.AsyncHttpClient

/**
 * Routes every HTTP request that the Rust layer makes through a native client.
 *
 * The native client obeys the proxy settings and the trust store of the device.
 * Call [configure] once when the app starts. A later call replaces the client.
 * Without this call the Rust layer uses its own HTTP client, and that client
 * does not read the device proxy settings.
 */
object RustHttpClient {
    fun configure(client: AsyncHttpClient = Oid4vciAsyncHttpClient()) {
        configureHttpClient(client)
    }

    // TEMPORARY (ACC-2246): an SDK API that no published release has. The
    // Flutter plugin calls it, so the plugin CI passes only against this tree.
    // Remove before merge.
    fun ciCheckDebugLog() {
        Log.d("sprucekit", "ACC-2246 CI check: plugin uses the Android SDK from this tree")
    }
}
