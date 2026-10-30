package com.spruceid.mobile.sdk.rs

import android.content.Context
import android.util.Log
import androidx.startup.Initializer

/**
 * Sets up certificate verification for the Rust layer's HTTPS clients that
 * use rustls-platform-verifier (reqwest's `rustls` backend from reqwest 0.13).
 *
 * rustls-platform-verifier asks the Android trust store, including the app's
 * `network_security_config`. It needs the application [Context] before its
 * first TLS handshake, or that handshake fails.
 *
 * The SDK does this at app startup through [RustlsPlatformVerifierInitializer],
 * so apps don't need to call anything. Apps that turn off androidx.startup's
 * automatic initialization, or use the SDK in another process than the main
 * one, call [init] themselves, for example in `Application.onCreate`.
 */
object RustlsPlatformVerifier {
    /** Initializes the verifier. Safe to call more than once; later calls do nothing. */
    @JvmStatic
    fun init(context: Context) {
        // UniFFI loads the library through JNA, which doesn't make its JNI
        // functions visible to the JVM; loading it here does. Both get the
        // same library instance.
        System.loadLibrary("mobile_sdk_rs")
        nativeInit(context.applicationContext)
    }

    @JvmStatic
    private external fun nativeInit(context: Context)
}

/** Runs [RustlsPlatformVerifier.init] at app startup through androidx.startup. */
class RustlsPlatformVerifierInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        // Don't stop the app from starting: without the verifier, only the
        // Rust layer's HTTPS calls fail.
        try {
            RustlsPlatformVerifier.init(context)
        } catch (e: UnsatisfiedLinkError) {
            Log.e("RustlsPlatformVerifier", "initialization failed", e)
        } catch (e: RuntimeException) {
            Log.e("RustlsPlatformVerifier", "initialization failed", e)
        }
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}
