package com.spruceid.mobilesdkexample.viewmodels


import android.app.Application
import android.content.Context
import androidx.compose.runtime.toMutableStateList
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.spruceid.mobile.sdk.CredentialPack
import com.spruceid.mobile.sdk.dcapi.Registry
import com.spruceid.mobile.sdk.StorageManager
import com.spruceid.mobile.sdk.rs.OpticalBarcodeCred
import com.spruceid.mobile.sdk.rs.ParsedCredential
import com.spruceid.mobile.sdk.rs.PdfSupplement
import com.spruceid.mobile.sdk.rs.generateTestOpticalBarcodeCredential
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
     * Returns demo PDF supplements: a single
     * [PdfSupplement.OpticalBarcodeCredential] carrying a freshly-signed test
     * **W3C VCB** (OpticalBarcodeCredential JSON-LD).  The SDK's
     * `generateCredentialPdf` will CBOR-LD encode it, embed it as the ZZA
     * field of an AAMVA ZZ subfile alongside the DL subfile, and render the
     * resulting PDF-417 into the PDF.
     *
     * ## Swap to a real CA DMV VCB
     * Replace the `generateTestOpticalBarcodeCredential()` call with the
     * JSON-LD VCB fetched from the wallet's stored credentials, once the
     * DMV microservice issues VCBs alongside mDLs. Everything downstream
     * stays identical — the SDK doesn't care whether the JSON-LD came from
     * a test fixture or a live issuer.
     *
     * QR section is intentionally omitted in this demo.  The
     * `BarcodeType.QR_CODE` primitive remains available for non-mDL flows.
     */
    suspend fun getDemoSupplements(@Suppress("UNUSED_PARAMETER") mdocCredential: ParsedCredential): List<PdfSupplement> {
        val jsonld = generateTestOpticalBarcodeCredential()
        val vcbInner = OpticalBarcodeCred(jsonld)
        val vcbCredential = ParsedCredential.newOpticalBarcodeCredential(vcbInner)
        return listOf(PdfSupplement.OpticalBarcodeCredential(credential = vcbCredential))
    }
}
