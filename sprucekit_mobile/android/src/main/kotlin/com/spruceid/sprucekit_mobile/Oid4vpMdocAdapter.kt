package com.spruceid.sprucekit_mobile

import android.content.Context
import android.util.Log
import com.spruceid.mobile.sdk.KeyManager
import com.spruceid.mobile.sdk.rs.ApprovedResponse180137
import com.spruceid.mobile.sdk.rs.InProgressRequest180137
import com.spruceid.mobile.sdk.rs.Mdoc
import com.spruceid.mobile.sdk.rs.Oid4vp180137
import com.spruceid.mobile.sdk.rs.RequestMatch180137
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * OID4VP mDoc (ISO 18013-7) Pigeon Adapter for Android
 *
 * Handles OpenID for Verifiable Presentation with mDoc credentials
 */
internal class Oid4vpMdocAdapter(
    private val context: Context,
    private val credentialPackAdapter: CredentialPackAdapter
) : Oid4vpMdoc {

    // Session state
    private var handler: Oid4vp180137? = null
    private var request: InProgressRequest180137? = null
    private var matches: List<RequestMatch180137> = emptyList()
    private var keyManager: KeyManager? = null

    override fun initialize(
        credentialPackIds: List<String>,
        callback: (Result<Oid4vpMdocResult>) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Get mDoc credentials from packs
                val mdocs = mutableListOf<Mdoc>()
                for (packId in credentialPackIds) {
                    val packCredentials = credentialPackAdapter.getNativeCredentials(packId)
                    for (credential in packCredentials) {
                        val mdoc = credential.asMsoMdoc()
                        if (mdoc != null) {
                            mdocs.add(mdoc)
                        }
                    }
                }

                if (mdocs.isEmpty()) {
                    callback(Result.success(Oid4vpMdocError("No mDoc credentials found in provided packs")))
                    return@launch
                }

                // Create KeyManager instance
                val keyMgr = KeyManager()

                // Create the handler
                val newHandler = Oid4vp180137(mdocs, keyMgr)

                synchronized(this@Oid4vpMdocAdapter) {
                    this@Oid4vpMdocAdapter.handler = newHandler
                    this@Oid4vpMdocAdapter.keyManager = keyMgr
                }

                callback(Result.success(Oid4vpMdocSuccess("Handler initialized with ${mdocs.size} mDoc(s)")))
            } catch (e: Exception) {
                Log.e("Oid4vpMdocAdapter", "Failed to initialize handler", e)
                callback(Result.success(Oid4vpMdocError(e.localizedMessage ?: "Failed to initialize handler")))
            }
        }
    }

    override fun processRequest(
        url: String,
        callback: (Result<ProcessRequestResult>) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val currentHandler = synchronized(this@Oid4vpMdocAdapter) { handler }
                if (currentHandler == null) {
                    callback(Result.success(ProcessRequestError("Handler not initialized. Call initialize first.")))
                    return@launch
                }

                // Process the request
                val inProgressRequest = currentHandler.processRequest(url)
                val requestMatches = inProgressRequest.matches()

                synchronized(this@Oid4vpMdocAdapter) {
                    this@Oid4vpMdocAdapter.request = inProgressRequest
                    this@Oid4vpMdocAdapter.matches = requestMatches
                }

                if (requestMatches.isEmpty()) {
                    callback(Result.success(ProcessRequestError(
                        "No matching credentials found for this verification request"
                    )))
                    return@launch
                }

                // Convert to Pigeon types
                val matchesData = requestMatches.mapIndexed { index, match ->
                    val fieldsData = match.requestedFields().map { field ->
                        RequestedField180137Data(
                            id = field.id,
                            displayableName = field.displayableName,
                            displayableValue = field.displayableValue,
                            selectivelyDisclosable = field.selectivelyDisclosable,
                            intentToRetain = field.intentToRetain,
                            required = field.required,
                            purpose = field.purpose
                        )
                    }
                    RequestMatch180137Data(
                        index = index.toLong(),
                        credentialId = match.credentialId().toString(),
                        requestedFields = fieldsData
                    )
                }

                val info = Oid4vpMdocRequestInfo(
                    requestedBy = inProgressRequest.requestedBy(),
                    matches = matchesData
                )

                callback(Result.success(ProcessRequestSuccess(info)))
            } catch (e: Exception) {
                Log.e("Oid4vpMdocAdapter", "Failed to process request", e)
                callback(Result.success(ProcessRequestError(e.localizedMessage ?: "Failed to process request")))
            }
        }
    }

    override fun submitResponse(
        matchIndex: Long,
        approvedFieldIds: List<String>,
        callback: (Result<Oid4vpMdocResult>) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val currentRequest = synchronized(this@Oid4vpMdocAdapter) { request }
                val currentMatches = synchronized(this@Oid4vpMdocAdapter) { matches }

                if (currentRequest == null) {
                    callback(Result.success(Oid4vpMdocError("No active request. Call processRequest first.")))
                    return@launch
                }

                if (matchIndex < 0 || matchIndex >= currentMatches.size) {
                    callback(Result.success(Oid4vpMdocError("Invalid match index")))
                    return@launch
                }

                val selectedMatch = currentMatches[matchIndex.toInt()]

                // Create approved response
                val approvedResponse = ApprovedResponse180137(
                    selectedMatch.credentialId(),
                    approvedFieldIds
                )

                // Submit and get redirect URL
                val redirectUrl = currentRequest.respond(approvedResponse)

                callback(Result.success(Oid4vpMdocSuccess(
                    message = "Presentation submitted successfully",
                    redirectUrl = redirectUrl
                )))
            } catch (e: Exception) {
                Log.e("Oid4vpMdocAdapter", "Failed to submit response", e)
                callback(Result.success(Oid4vpMdocError(e.localizedMessage ?: "Failed to submit response")))
            }
        }
    }

    override fun cancel() {
        synchronized(this) {
            handler = null
            request = null
            matches = emptyList()
            keyManager = null
        }
    }
}
