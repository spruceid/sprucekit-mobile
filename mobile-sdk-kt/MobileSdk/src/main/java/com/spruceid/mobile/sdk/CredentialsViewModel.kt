package com.spruceid.mobile.sdk

import android.app.Application
import android.bluetooth.BluetoothManager
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import com.spruceid.mobile.sdk.rs.ItemsRequest
import com.spruceid.mobile.sdk.rs.MdlPresentationSession
import com.spruceid.mobile.sdk.rs.Mdoc
import com.spruceid.mobile.sdk.rs.ParsedCredential
import com.spruceid.mobile.sdk.rs.initializeMdlPresentationFromBytes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import java.security.KeyStore
import java.security.Signature
import java.util.UUID

class CredentialsViewModel(application: Application) : AndroidViewModel(application) {

    private val _credentials = MutableStateFlow<ArrayList<ParsedCredential>>(arrayListOf())
    val credentials = _credentials.asStateFlow()

    private val _currState = MutableStateFlow(PresentmentState.UNINITIALIZED)
    val currState = _currState.asStateFlow()

    private val _session = MutableStateFlow<MdlPresentationSession?>(null)
    val session = _session.asStateFlow()

    private val _error = MutableStateFlow<Error?>(null)
    val error = _error.asStateFlow()

    private val _itemsRequests = MutableStateFlow<List<ItemsRequest>>(listOf())
    val itemsRequest = _itemsRequests.asStateFlow()

    private val _allowedNamespaces =
        MutableStateFlow<Map<String, Map<String, List<String>>>>(mapOf())
    val allowedNamespaces = _allowedNamespaces.asStateFlow()

    private val _uuid = MutableStateFlow<UUID>(UUID.randomUUID())

    private val _transport = MutableStateFlow<Transport?>(null)

    fun storeCredential(credential: ParsedCredential) {
        _credentials.value.add(credential)
    }

    private fun firstMdoc(): Mdoc {
        val mdoc = _credentials.value
            .map { credential -> credential.asMsoMdoc() }
            .firstOrNull()
        if (mdoc == null) {
            throw Exception("no mdoc found")
        }
        return mdoc
    }

    fun toggleAllowedNamespace(docType: String, specName: String, fieldName: String) {
        if (_allowedNamespaces.value.isEmpty()) {
            _allowedNamespaces.value = mapOf(Pair(docType, mapOf(Pair(specName, listOf()))))
        }
        val allowedForSpec = _allowedNamespaces.value[docType]!![specName]

        if (!allowedForSpec!!.contains(fieldName)) {
            _allowedNamespaces.value = mapOf(
                Pair(
                    docType,
                    mapOf(Pair(specName, allowedForSpec.plus(fieldName)))
                )
            )
        } else {
            _allowedNamespaces.value = mapOf(
                Pair(
                    docType,
                    mapOf(Pair(specName, allowedForSpec.minus(fieldName)))
                )
            )
        }
    }

    private fun updateRequestData(data: ByteArray) {
        _itemsRequests.value = _session.value!!.handleRequest(data)
        val namespaces =
            _itemsRequests.value.map { itemsRequest -> itemsRequest.namespaces }
        Log.d(
            "CredentialsViewModel.updateRequestData",
            "Updating requestData: \nitemRequests ${_itemsRequests.value.map { itemsRequest -> itemsRequest.docType }} namespaces: $namespaces"
        )
        _currState.value = PresentmentState.SELECT_NAMESPACES
    }

    suspend fun present(bluetoothManager: BluetoothManager) {
        Log.d("CredentialsViewModel.present", "Credentials: ${_credentials.value}")
        _uuid.value = UUID.randomUUID()
        val mdoc = this.firstMdoc()
        _session.value = initializeMdlPresentationFromBytes(mdoc, _uuid.value.toString())
        _currState.value = PresentmentState.ENGAGING_QR_CODE
        _transport.value = Transport(bluetoothManager)
        _transport.value!!
            .initialize(
                "Holder",
                _uuid.value,
                "BLE",
                "Central",
                _session.value!!.getBleIdent(),
                ::updateRequestData,
                getApplication<Application>().applicationContext,
                null
            )
    }

    fun cancel() {
        _uuid.value = UUID.randomUUID()
        _session.value = null
        _currState.value = PresentmentState.UNINITIALIZED
        _transport.value = null
    }

    fun submitNamespaces(allowedNamespaces: Map<String, Map<String, List<String>>>) {
        val mdoc = this.firstMdoc()
        if (allowedNamespaces.isEmpty()) {
            val e = Error("Select at least one namespace")
            Log.e("CredentialsViewModel.submitNamespaces", e.toString())
            _currState.value = PresentmentState.ERROR
            _error.value = e
            throw e
        }
        val payload = _session.value!!.generateResponse(
            allowedNamespaces
        )

        val ks: KeyStore = KeyStore.getInstance(
            "AndroidKeyStore"
        )

        ks.load(
            null
        )

        val entry = ks.getEntry(mdoc.keyAlias(), null)
        if (entry !is KeyStore.PrivateKeyEntry) {
            throw IllegalStateException("No such private key under the alias <${mdoc.keyAlias()}>")
        }

        try {
            val signer = Signature.getInstance("SHA256withECDSA")
            signer.initSign(entry.privateKey)
            signer.update(payload)
            val signature = signer.sign()
            val response = _session.value!!.submitResponse(signature)
            _transport.value!!.send(response)
            _currState.value = PresentmentState.SUCCESS
        } catch (e: Error) {
            Log.e("CredentialsViewModel.submitNamespaces", e.toString())
            _currState.value = PresentmentState.ERROR
            _error.value = e
            throw e
        }
    }
}
