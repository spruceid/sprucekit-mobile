package com.spruceid.sprucekit_mobile

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import com.spruceid.mobile.sdk.KeyManager
import com.spruceid.mobile.sdk.StorageManager
import com.spruceid.mobile.sdk.dcapi.Registry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * DC API Adapter for Android
 *
 * Handles registering mDoc credentials with Android CredentialManager
 * for the Digital Credentials API.
 */
internal class DcApiAdapter(
    private val context: Context,
    private val credentialPackAdapter: CredentialPackAdapter
) : DcApi {

    private val application: Application
        get() = context.applicationContext as Application

    private val registeredCredentials = ConcurrentHashMap<String, RegisteredCredentialInfo>()
    private var registry: Registry? = null
    private val storageManager by lazy { StorageManager(context) }

    companion object {
        private const val TAG = "DcApiAdapter"
        // Icon file name in the app's assets folder (e.g., "icon.ico")
        // Apps must provide this file in: android/app/src/main/assets/
        private const val DEFAULT_ICON = "icon.ico"
    }

    override fun syncCredentialsToAppGroup(
        appGroupId: String,
        packIds: List<String>,
        callback: (Result<DcApiResult>) -> Unit
    ) {
        // On Android, we don't use App Groups like iOS
        // Instead, CredentialManager handles credential storage and access
        // This method is a no-op on Android but returns success for API compatibility
        callback(Result.success(DcApiSuccess(
            message = "App Group sync not needed on Android (handled by CredentialManager)"
        )))
    }

    override fun registerCredentials(
        packIds: List<String>,
        walletName: String?,
        callback: (Result<DcApiResult>) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Initialize registry
                // Note: walletName parameter reserved for future use when SDK supports it
                registry = Registry(application, DEFAULT_ICON)

                // Collect all credential packs
                val nativePacks = packIds.mapNotNull { packId ->
                    credentialPackAdapter.getNativePack(packId)
                }

                if (nativePacks.isEmpty()) {
                    callback(Result.success(DcApiError(
                        message = "No valid credential packs found"
                    )))
                    return@launch
                }

                // Save packs to StorageManager so the DC API Activity can load them
                // This is required because the Activity runs in a separate process
                // and needs to access credentials via StorageManager
                for (pack in nativePacks) {
                    try {
                        pack.save(storageManager)
                        Log.d(TAG, "Saved pack ${pack.id()} to StorageManager")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to save pack ${pack.id()} to StorageManager", e)
                    }
                }

                // Register with CredentialManager
                registry?.register(nativePacks)

                // Track registered credentials
                var registeredCount = 0
                for (packId in packIds) {
                    val credentials = credentialPackAdapter.getNativeCredentials(packId)
                    for (credential in credentials) {
                        val mdoc = credential.asMsoMdoc() ?: continue
                        val credentialId = credential.id()
                        val docType = mdoc.doctype()

                        registeredCredentials[credentialId] = RegisteredCredentialInfo(
                            credentialId = credentialId,
                            docType = docType,
                            isRegistered = true
                        )
                        registeredCount++
                    }
                }

                callback(Result.success(DcApiSuccess(
                    message = "Registered $registeredCount credentials with CredentialManager"
                )))

            } catch (e: Exception) {
                callback(Result.success(DcApiError(
                    message = "Failed to register credentials: ${e.localizedMessage}"
                )))
            }
        }
    }

    override fun unregisterCredentials(
        credentialIds: List<String>,
        callback: (Result<DcApiResult>) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                var unregisteredCount = 0

                for (credentialId in credentialIds) {
                    // Remove from tracking
                    registeredCredentials.remove(credentialId)
                    unregisteredCount++
                }

                // Note: Android CredentialManager doesn't have a direct unregister API
                // The credentials are re-registered on each call to registerCredentials
                // So we just clear our tracking here

                callback(Result.success(DcApiSuccess(
                    message = "Unregistered $unregisteredCount credentials"
                )))

            } catch (e: Exception) {
                callback(Result.success(DcApiError(
                    message = "Failed to unregister credentials: ${e.localizedMessage}"
                )))
            }
        }
    }

    override fun getRegisteredCredentials(): List<RegisteredCredentialInfo> {
        return registeredCredentials.values.toList()
    }

    override fun isSupported(): Boolean {
        // DC API on Android requires API level 34 (Android 14) or higher
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
    }
}
