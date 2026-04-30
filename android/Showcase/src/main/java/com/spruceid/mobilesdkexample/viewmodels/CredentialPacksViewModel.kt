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
import com.spruceid.mobile.sdk.rs.DisclosureSelection
import com.spruceid.mobile.sdk.rs.ParsedCredential
import com.spruceid.mobile.sdk.rs.PdfSupplement
import com.spruceid.mobile.sdk.rs.Vcdm2SdJwt
import com.spruceid.mobile.sdk.rs.VpTokenParams
import com.spruceid.mobile.sdk.rs.compressVpForQr
import com.spruceid.mobile.sdk.rs.generateCredentialVpToken
import com.spruceid.mobile.sdk.rs.generateTestMdlSdJwtCompact
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
     * Returns demo PDF supplements:
     *   - QR: a real, verifiable **SD-JWT VP** with `portrait` selectively
     *     hidden, generated end-to-end on this device (test fixture issuer
     *     -> VP token -> bytes).
     *   - PDF-417: AAMVA-style mock string (real AAMVA encoder integration
     *     is on a parallel PR — `generateAamvaPdf417Bytes`).
     *
     * ## Swap to a real CA DMV credential
     * Replace the `generateTestMdlSdJwtCompact()` call below with the
     * SD-JWT compact string fetched from the wallet's stored credentials
     * (e.g. once the Alice/Tiago microservice PR ships and the wallet
     * receives `format == "vc+sd-jwt"` from the OID4VCI `/credential`
     * endpoint). Everything downstream stays identical.
     *
     * See `vcdm2_sd_jwt.rs::generate_test_mdl_sd_jwt` for the full swap
     * recipe.
     */
    suspend fun getDemoSupplements(): List<PdfSupplement> {
        // 1. Get a self-signed test SD-JWT (REPLACE WITH REAL CREDENTIAL).
        val sdJwtCompact = generateTestMdlSdJwtCompact()

        // 2. Parse into a ParsedCredential the SDK can work with.
        val sdJwt = Vcdm2SdJwt.newFromCompactSdJwt(sdJwtCompact)
        val credential = ParsedCredential.newSdJwt(sdJwt)

        // 3. Generate the SD-JWT VP that hides `portrait`.
        val vpParams = VpTokenParams(
            disclosure = DisclosureSelection.HideOnly(fields = listOf("portrait")),
            audience = "https://demo.spruceid.com",
            nonce = null
        )
        val vpBytes = generateCredentialVpToken(credential, vpParams)

        // 4. Compress for QR numeric-mode encoding. The raw SD-JWT VP is
        //    too large for QR byte mode (~2.95 KB cap @ V40 L-EC); the
        //    Colorado deflate+base10+"9"-prefix scheme produces an
        //    all-digit payload that QR auto-encodes in numeric mode
        //    (~7089 digits cap), where it fits comfortably.
        //    Verifier (verifySdJwtVp) auto-detects the leading "9" and
        //    decompresses before signature checking.
        val qrBytes = compressVpForQr(vpBytes)

        // 4. PDF-417 payload — still a mock AAMVA-style string. The real
        //    AAMVA encoder (generateAamvaPdf417Bytes) is on a parallel PR.
        val pdf417Payload = "DAQ DL-123456789\nDCS Doe\nDCT John\nDBB 01151990\nDBA 01152029".toByteArray()

        return listOf(
            PdfSupplement.Barcode(data = qrBytes, barcodeType = BarcodeType.QR_CODE),
            PdfSupplement.Barcode(data = pdf417Payload, barcodeType = BarcodeType.PDF417)
        )
    }
}
