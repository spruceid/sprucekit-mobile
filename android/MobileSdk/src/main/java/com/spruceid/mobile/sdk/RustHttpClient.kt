package com.spruceid.mobile.sdk

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
}
