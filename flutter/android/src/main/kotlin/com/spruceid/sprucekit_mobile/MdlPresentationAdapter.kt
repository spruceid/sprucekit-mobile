package com.spruceid.sprucekit_mobile

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import com.spruceid.mobile.sdk.BLESessionStateDelegate
import com.spruceid.mobile.sdk.CredentialPresentData
import com.spruceid.mobile.sdk.IsoMdlPresentation
import com.spruceid.mobile.sdk.getBluetoothManager
import com.spruceid.mobile.sdk.rs.ItemsRequest
import com.spruceid.mobile.sdk.rs.Mdoc
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Adapter implementing the MdlPresentation Pigeon interface for Android
 */
internal class MdlPresentationAdapter(
    private val context: Context,
    private val credentialPackAdapter: CredentialPackAdapter
) : MdlPresentation {

    private val coroutineScope = CoroutineScope(Dispatchers.Main)

    private var presentation: IsoMdlPresentation? = null
    private var flutterCallback: MdlPresentationCallback? = null
    private var currentState: MdlPresentationStateUpdate = MdlPresentationStateUpdate(state = MdlPresentationState.UNINITIALIZED)
    private var itemsRequests: List<ItemsRequest> = emptyList()
    private var mdoc: Mdoc? = null

    fun setCallback(callback: MdlPresentationCallback) {
        flutterCallback = callback
    }

    @SuppressLint("MissingPermission")
    override fun initializeQrPresentation(
        packId: String,
        credentialId: String,
        callback: (Result<MdlPresentationResult>) -> Unit
    ) {
        // Cancel any existing session
        presentation?.terminate()
        presentation = null
        itemsRequests = emptyList()

        try {
            // Get the credential pack
            val pack = credentialPackAdapter.getNativePack(packId)
            if (pack == null) {
                callback(Result.success(MdlPresentationError("Credential pack not found: $packId")))
                return
            }

            // Get the credential and extract the mDoc
            val credential = pack.getCredentialById(credentialId)
            if (credential == null) {
                callback(Result.success(MdlPresentationError("Credential not found: $credentialId")))
                return
            }

            val mdoc = credential.asMsoMdoc()
            if (mdoc == null) {
                callback(Result.success(MdlPresentationError("Credential is not an mDoc: $credentialId")))
                return
            }
            this.mdoc = mdoc

            // Get Bluetooth manager
            val bluetoothManager = getBluetoothManager(context)
            if (bluetoothManager == null) {
                callback(Result.success(MdlPresentationError("Bluetooth not available")))
                return
            }

            // Create the presentation callback
            val presentationCallback = object : BLESessionStateDelegate() {
                override fun update(state: Map<String, Any>) {
                    handleStateUpdate(state)
                }

                override fun error(error: Exception) {
                    Log.e("MdlPresentationAdapter", "Presentation error: ${error.message}", error)
                    updateState(MdlPresentationStateUpdate(
                        state = MdlPresentationState.ERROR,
                        error = error.message ?: "Unknown error"
                    ))
                }
            }

            // Create the presentation
            presentation = IsoMdlPresentation(
                callback = presentationCallback,
                mdoc = mdoc,
                keyAlias = mdoc.keyAlias(),
                bluetoothManager = bluetoothManager,
                context = context,
            )

            updateState(MdlPresentationStateUpdate(state = MdlPresentationState.INITIALIZING))

            // Initialize the presentation with QR mode
            coroutineScope.launch {
                try {
                    presentation?.initialize(CredentialPresentData.Qr())
                } catch (e: Exception) {
                    Log.e("MdlPresentationAdapter", "Failed to initialize presentation", e)
                    updateState(MdlPresentationStateUpdate(
                        state = MdlPresentationState.ERROR,
                        error = e.message ?: "Failed to initialize presentation"
                    ))
                }
            }

            callback(Result.success(MdlPresentationSuccess("Presentation initialized")))
        } catch (e: Exception) {
            Log.e("MdlPresentationAdapter", "Failed to initialize presentation", e)
            callback(Result.success(MdlPresentationError("Failed to initialize presentation: ${e.message}")))
        }
    }

    override fun getQrCodeUri(): String? {
        return currentState.qrCodeUri
    }

    override fun getCurrentState(): MdlPresentationStateUpdate {
        return currentState
    }

    override fun submitNamespaces(
        selectedNamespaces: Map<String, Map<String, List<String>>>,
        callback: (Result<MdlPresentationResult>) -> Unit
    ) {
        if (presentation == null) {
            callback(Result.success(MdlPresentationError("No active presentation session")))
            return
        }

        // Check if any fields are selected
        val hasSelectedFields = selectedNamespaces.values.any { docTypeNamespaces ->
            docTypeNamespaces.values.any { fields -> fields.isNotEmpty() }
        }

        if (!hasSelectedFields) {
            callback(Result.success(MdlPresentationError("Select at least one attribute to share")))
            return
        }

        try {
            updateState(MdlPresentationStateUpdate(state = MdlPresentationState.SENDING_RESPONSE))
            presentation?.submitNamespaces(selectedNamespaces)
            updateState(MdlPresentationStateUpdate(state = MdlPresentationState.SUCCESS))
            callback(Result.success(MdlPresentationSuccess("Response submitted")))
        } catch (e: Exception) {
            Log.e("MdlPresentationAdapter", "Failed to submit namespaces", e)
            updateState(MdlPresentationStateUpdate(
                state = MdlPresentationState.ERROR,
                error = e.message ?: "Failed to submit namespaces"
            ))
            callback(Result.success(MdlPresentationError("Failed to submit namespaces: ${e.message}")))
        }
    }

    override fun cancel() {
        presentation?.terminate()
        presentation = null
        itemsRequests = emptyList()
        mdoc = null
        updateState(MdlPresentationStateUpdate(state = MdlPresentationState.UNINITIALIZED))
    }

    // MARK: - Internal methods

    private fun updateState(state: MdlPresentationStateUpdate) {
        currentState = state
        // Ensure callback is invoked on the main thread as required by Flutter
        coroutineScope.launch {
            flutterCallback?.onStateChange(state) { }
        }
    }

    private fun handleStateUpdate(state: Map<String, Any>) {
        when {
            state.containsKey("timeout") -> {
                updateState(MdlPresentationStateUpdate(state = MdlPresentationState.TIMEOUT))
            }

            state.containsKey("engagingQRCode") -> {
                val qrCodeUri = state["engagingQRCode"] as String
                updateState(MdlPresentationStateUpdate(
                    state = MdlPresentationState.ENGAGING_QR_CODE,
                    qrCodeUri = qrCodeUri
                ))
            }

            state.containsKey("selectNamespaces") -> {
                @Suppress("UNCHECKED_CAST")
                itemsRequests = state["selectNamespaces"] as List<ItemsRequest>

                // Convert ItemsRequest to MdlItemsRequest
                val mdlItemsRequests = itemsRequests.map { itemsRequest ->
                    val namespaceRequests = itemsRequest.namespaces.map { (namespace, fields) ->
                        val items = fields.map { (fieldName, intentToRetain) ->
                            MdlNamespaceItem(name = fieldName, intentToRetain = intentToRetain)
                        }
                        MdlNamespaceRequest(namespace = namespace, items = items)
                    }
                    MdlItemsRequest(docType = itemsRequest.docType, namespaces = namespaceRequests)
                }

                updateState(MdlPresentationStateUpdate(
                    state = MdlPresentationState.SELECTING_NAMESPACES,
                    itemsRequests = mdlItemsRequests
                ))
            }

            state.containsKey("error") -> {
                updateState(MdlPresentationStateUpdate(
                    state = MdlPresentationState.ERROR,
                    error = state["error"].toString()
                ))
            }
        }
    }
}
