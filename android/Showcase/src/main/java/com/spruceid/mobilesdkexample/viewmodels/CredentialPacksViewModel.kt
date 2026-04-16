package com.spruceid.mobilesdkexample.viewmodels


import android.app.Application
import android.content.Context
import androidx.compose.runtime.toMutableStateList
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.spruceid.mobile.sdk.CredentialPack
import com.spruceid.mobile.sdk.dcapi.Registry
import com.spruceid.mobile.sdk.StorageManager
import com.spruceid.mobile.sdk.rs.BarcodeType
import com.spruceid.mobile.sdk.rs.PdfSupplement
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CredentialPacksViewModel @Inject constructor(
    application: Application,
    private val dcApiRegistry: Registry
) : AndroidViewModel(application) {
    private val storageManager = StorageManager(context = (application as Context))
    private val _credentialPacks = MutableStateFlow(listOf<CredentialPack>())
    val credentialPacks = _credentialPacks.asStateFlow()
    private val _loading = MutableStateFlow(false)
    val loading = _loading.asStateFlow()

    init {
        viewModelScope.launch {
            _loading.value = true
            this.async(Dispatchers.Default) {
                _credentialPacks.value = CredentialPack.loadPacks(storageManager)
                dcApiRegistry.register(credentialPacks.value)
            }.await()
            _loading.value = false

            // Listen for credential pack updates and update the registry.
            _credentialPacks.collect { packs -> dcApiRegistry.register(packs) }
        }
    }

    suspend fun saveCredentialPack(credentialPack: CredentialPack) {
        credentialPack.save(storageManager)
        val tmpCredentialPacksList = _credentialPacks.value.toMutableStateList()
        tmpCredentialPacksList.add(credentialPack)
        _credentialPacks.value = tmpCredentialPacksList
    }

    suspend fun deleteAllCredentialPacks(onDeleteCredentialPack: (suspend (CredentialPack) -> Unit)? = null) {
        _credentialPacks.value.forEach { credentialPack ->
            credentialPack.remove(storageManager)
            onDeleteCredentialPack?.invoke(credentialPack)
        }
        _credentialPacks.value = emptyList()
        dcApiRegistry.register(emptyList())
    }

    suspend fun deleteCredentialPack(credentialPack: CredentialPack) {
        credentialPack.remove(storageManager)
        val tmpCredentialPacksList = _credentialPacks.value.toMutableStateList()
        tmpCredentialPacksList.remove(credentialPack)
        _credentialPacks.value = tmpCredentialPacksList
        dcApiRegistry.register(_credentialPacks.value)
    }

    fun getById(credentialPackId: String): CredentialPack? {
        return _credentialPacks.value.firstOrNull { credentialPack ->
            credentialPack.id().toString() == credentialPackId
        }
    }

    /**
     * Returns demo PDF supplements with mock barcode data.
     * In production, QR would be a VP Token and PDF-417 would be AAMVA data.
     */
    fun getDemoSupplements(): List<PdfSupplement> {
        val qrPayload = """{"type":"mDL","source":"SpruceKit Showcase"}""".toByteArray()
        val pdf417Payload = "DAQ DL-123456789\nDCS Doe\nDCT John\nDBB 01151990\nDBA 01152029".toByteArray()
        return listOf(
            PdfSupplement.Barcode(
                data = qrPayload,
                barcodeType = BarcodeType.QR_CODE
            ),
            PdfSupplement.Barcode(
                data = pdf417Payload,
                barcodeType = BarcodeType.PDF417
            )
        )
    }
}
