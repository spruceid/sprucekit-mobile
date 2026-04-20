package com.spruceid.sprucekit_mobile

import android.content.Context
import android.util.Base64
import com.spruceid.mobile.sdk.KeyManager
import com.spruceid.mobile.sdk.rs.generateCredentialPdf
import com.spruceid.mobile.sdk.rs.generateTestMdl
import com.spruceid.mobile.sdk.rs.Mdoc
import com.spruceid.mobile.sdk.rs.ParsedCredential
import com.spruceid.mobile.sdk.rs.PdfSupplement as RustPdfSupplement
import com.spruceid.mobile.sdk.rs.BarcodeType as RustBarcodeType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * SpruceUtils Pigeon Adapter
 *
 * Utility functions for credential operations
 */
internal class SpruceUtilsAdapter(
    private val context: Context,
    private val credentialPackAdapter: CredentialPackAdapter
) : SpruceUtils {

    override fun generateCredentialPdf(
        rawMdoc: String,
        supplements: List<PdfSupplement>,
        callback: (Result<ByteArray>) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // rawMdoc is standard Base64-encoded CBOR Document bytes
                // (from parsedCredential.intoGenericForm().payload)
                val documentBytes = Base64.decode(rawMdoc, Base64.DEFAULT)
                val mdoc = Mdoc.fromCborEncodedDocument(documentBytes, "pdf")
                val credential = ParsedCredential.newMsoMdoc(mdoc)

                // Convert Pigeon supplements to Rust PdfSupplement
                val rustSupplements: List<RustPdfSupplement> = supplements.mapNotNull { sup ->
                    when (sup.type) {
                        PdfSupplementType.BARCODE -> {
                            val data = sup.data ?: return@mapNotNull null
                            val barcodeType = sup.barcodeType ?: return@mapNotNull null
                            val rustBarcodeType = when (barcodeType) {
                                PdfBarcodeType.QR_CODE -> RustBarcodeType.QR_CODE
                                PdfBarcodeType.PDF417 -> RustBarcodeType.PDF417
                            }
                            RustPdfSupplement.Barcode(data = data, barcodeType = rustBarcodeType)
                        }
                    }
                }

                val pdfBytes = generateCredentialPdf(credential, rustSupplements)
                callback(Result.success(pdfBytes))
            } catch (e: Exception) {
                callback(Result.failure(e))
            }
        }
    }

    override fun generateMockMdl(
        keyAlias: String?,
        callback: (Result<GenerateMockMdlResult>) -> Unit
    ) {
        val alias = keyAlias ?: "testMdl"

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val keyManager = KeyManager()

                // Generate or retrieve the signing key
                if (!keyManager.keyExists(alias)) {
                    keyManager.generateSigningKey(alias)
                }

                // Generate the test mDL
                val mdl = generateTestMdl(keyManager, alias)

                // Create a new CredentialPack and add the mDL
                val packId = credentialPackAdapter.createPack()
                val pack = credentialPackAdapter.getNativePack(packId)
                if (pack == null) {
                    callback(Result.success(GenerateMockMdlError(message = "Failed to create credential pack")))
                    return@launch
                }

                // Get the raw credential bytes for storage
                val parsedCredential = ParsedCredential.newMsoMdoc(mdl)
                val genericCredential = parsedCredential.intoGenericForm()
                val rawCredentialBase64 = Base64.encodeToString(
                    genericCredential.payload,
                    Base64.NO_WRAP
                )

                // Add the mDL to the pack
                val credentials = pack.addMdoc(mdl)
                val credential = credentials.firstOrNull()
                if (credential == null) {
                    callback(Result.success(GenerateMockMdlError(message = "Failed to add mDL to pack")))
                    return@launch
                }

                callback(Result.success(GenerateMockMdlSuccess(
                    packId = packId,
                    credentialId = credential.id(),
                    rawCredential = rawCredentialBase64,
                    keyAlias = alias
                )))
            } catch (e: Exception) {
                callback(Result.success(GenerateMockMdlError(
                    message = "Failed to generate mock mDL: ${e.localizedMessage}"
                )))
            }
        }
    }

}
