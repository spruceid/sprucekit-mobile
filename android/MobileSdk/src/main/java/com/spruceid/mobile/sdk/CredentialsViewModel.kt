package com.spruceid.mobile.sdk

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothManager
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.lifecycle.AndroidViewModel
import com.spruceid.mobile.sdk.rs.ItemsRequest
import com.spruceid.mobile.sdk.rs.Mdoc
import com.spruceid.mobile.sdk.rs.ParsedCredential
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class CredentialsViewModel(application: Application) : AndroidViewModel(application) {

    private val _credentials = MutableStateFlow<ArrayList<ParsedCredential>>(arrayListOf())
    val credentials = _credentials.asStateFlow()

    private val _currState = MutableStateFlow(PresentmentState.UNINITIALIZED)
    val currState = _currState.asStateFlow()

    private val _error = MutableStateFlow<Error?>(null)
    val error = _error.asStateFlow()

    private val _itemsRequests = MutableStateFlow<List<ItemsRequest>>(listOf())
    val itemsRequest = _itemsRequests.asStateFlow()

    private val _qrCodeUri = MutableStateFlow<String>("")
    val qrCodeUri = _qrCodeUri.asStateFlow()

    private val _bluetoothPermissionsGranted = MutableStateFlow<Boolean>(false)
    val bluetoothPermissionsGranted = _bluetoothPermissionsGranted.asStateFlow()
    fun setBluetoothPermissionsGranted(granted: Boolean) {
        _bluetoothPermissionsGranted.value = granted
    }

    private val _allowedNamespaces =
        MutableStateFlow<Map<String, Map<String, List<String>>>>(
            mapOf(
                Pair(
                    "org.iso.18013.5.1.mDL",
                    mapOf(
                        Pair("org.iso.18013.5.1", listOf()),
                        Pair("org.iso.18013.5.1.aamva", listOf())
                    )
                )
            )
        )
    val allowedNamespaces = _allowedNamespaces.asStateFlow()

    // Use IsoMdlPresentation instead of managing Transport/Session directly
    private var presentation: IsoMdlPresentation? = null

    // Callback to handle IsoMdlPresentation state changes
    private val presentationCallback = object : BLESessionStateDelegate() {
        override fun update(state: Map<String, Any>) {
            when {
                state.containsKey("engagingQRCode") -> {
                    _qrCodeUri.value = state["engagingQRCode"] as String
                    _currState.value = PresentmentState.ENGAGING_QR_CODE
                }
                state.containsKey("selectNamespaces") -> {
                    @Suppress("UNCHECKED_CAST")
                    _itemsRequests.value = state["selectNamespaces"] as List<ItemsRequest>
                    _currState.value = PresentmentState.SELECT_NAMESPACES
                }
                state.containsKey("error") -> {
                    _currState.value = PresentmentState.ERROR
                    _error.value = Error(state["error"].toString())
                }
            }
        }

        override fun error(error: Exception) {
            Log.e("CredentialsViewModel", "Presentation error: ${error.message}", error)
            _currState.value = PresentmentState.ERROR
            _error.value = Error(error.message ?: "Unknown error")
        }
    }

    fun storeCredential(credential: ParsedCredential) {
        _credentials.value.add(credential)
    }

    fun toggleAllowedNamespace(docType: String, specName: String, fieldName: String) {
        val allowedForSpec = _allowedNamespaces.value[docType]!![specName]
        if (!allowedForSpec!!.contains(fieldName)) {
            _allowedNamespaces.value = _allowedNamespaces.value.toMutableMap().apply {
                this[docType] = this[docType]?.toMutableMap()?.apply {
                    this[specName] = (this[specName] ?: emptyList()) + fieldName
                } ?: mapOf(specName to listOf(fieldName))
            }
        } else {
            _allowedNamespaces.value = _allowedNamespaces.value.toMutableMap().apply {
                this[docType] = this[docType]?.toMutableMap()?.apply {
                    this[specName] = this[specName]?.filter { it != fieldName } ?: emptyList()
                } ?: mapOf(specName to listOf())
            }
        }
    }

    fun addAllAllowedNamespaces(
        docType: String,
        namespace: Map<String, Map<String, Boolean>>
    ) {
        _allowedNamespaces.value = _allowedNamespaces.value.toMutableMap().apply {
            val existingSpecs = this[docType]?.toMutableMap() ?: mutableMapOf()

            namespace.forEach { (specName, fields) ->
                val existingFields = existingSpecs[specName]?.toMutableList() ?: mutableListOf()

                // Add to the list ignoring the boolean value
                existingFields.addAll(fields.keys.filter { it !in existingFields })

                existingSpecs[specName] = existingFields
            }

            this[docType] = existingSpecs
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    suspend fun present(bluetoothManager: BluetoothManager, mdoc: Mdoc) {
        Log.d("CredentialsViewModel.present", "Credentials: ${_credentials.value}")

        // Create IsoMdlPresentation with callback to handle state changes
        presentation = IsoMdlPresentation(
            callback = presentationCallback,
            mdoc = mdoc,
            keyAlias = mdoc.keyAlias(),
            bluetoothManager = bluetoothManager,
            context = getApplication<Application>().applicationContext,
            bleMode = "Peripheral"
        )

        // Initialize will trigger the callback with engagingQRCode state
        presentation?.initialize()
    }

    fun cancel() {
        presentation?.terminate()
        presentation = null
        _currState.value = PresentmentState.UNINITIALIZED
    }

    fun submitNamespaces(allowedNamespaces: Map<String, Map<String, List<String>>>) {
        // Check if any fields are actually selected
        val hasSelectedFields = allowedNamespaces.values.any { docTypeNamespaces ->
            docTypeNamespaces.values.any { fields -> fields.isNotEmpty() }
        }

        if (!hasSelectedFields) {
            val e = Error("Select at least one attribute to share")
            Log.e("CredentialsViewModel.submitNamespaces", e.toString())
            _currState.value = PresentmentState.ERROR
            _error.value = e
            throw e
        }

        try {
            presentation?.submitNamespaces(allowedNamespaces)
            _currState.value = PresentmentState.SUCCESS
        } catch (e: Error) {
            Log.e("CredentialsViewModel.submitNamespaces", e.toString())
            _currState.value = PresentmentState.ERROR
            _error.value = e
            throw e
        }
    }
}
