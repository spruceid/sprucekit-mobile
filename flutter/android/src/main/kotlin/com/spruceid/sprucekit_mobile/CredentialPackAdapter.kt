package com.spruceid.sprucekit_mobile

import android.content.Context
import android.util.Log
import com.spruceid.mobile.sdk.CredentialPack as SdkCredentialPack
import com.spruceid.mobile.sdk.CredentialStatusList
import com.spruceid.mobile.sdk.StorageManager
import com.spruceid.mobile.sdk.credentialClaims
import com.spruceid.mobile.sdk.jsonEncodedDetailsAll
import com.spruceid.mobile.sdk.rs.Mdoc
import com.spruceid.mobile.sdk.rs.ParsedCredential
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * CredentialPack Pigeon Adapter
 *
 * Manages collections of credentials with parsing capabilities
 */
internal class CredentialPackAdapter(private val context: Context) : CredentialPack {

    // In-memory store of credential packs
    private val packs = ConcurrentHashMap<String, SdkCredentialPack>()

    override fun createPack(): String {
        val pack = SdkCredentialPack()
        val packId = pack.id().toString()
        packs[packId] = pack
        return packId
    }

    override fun getPack(packId: String): CredentialPackData? {
        val pack = packs[packId] ?: return null
        return CredentialPackData(
            id = packId,
            credentials = pack.list().map { it.toData() }
        )
    }

    override fun addRawCredential(
        packId: String,
        rawCredential: String,
        callback: (Result<AddCredentialResult>) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val pack = packs[packId]
                if (pack == null) {
                    callback(Result.success(AddCredentialError(message = "Pack not found: $packId")))
                    return@launch
                }

                val credentials = pack.tryAddRawCredential(rawCredential)
                callback(Result.success(AddCredentialSuccess(
                    credentials = credentials.map { it.toData() }
                )))
            } catch (e: Exception) {
                callback(Result.success(AddCredentialError(
                    message = e.localizedMessage ?: "Failed to add credential"
                )))
            }
        }
    }

    override fun addRawMdoc(
        packId: String,
        rawCredential: String,
        keyAlias: String,
        callback: (Result<AddCredentialResult>) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val pack = packs[packId]
                if (pack == null) {
                    callback(Result.success(AddCredentialError(message = "Pack not found: $packId")))
                    return@launch
                }

                val credentials = pack.tryAddRawMdoc(rawCredential, keyAlias)
                callback(Result.success(AddCredentialSuccess(
                    credentials = credentials.map { it.toData() }
                )))
            } catch (e: Exception) {
                callback(Result.success(AddCredentialError(
                    message = e.localizedMessage ?: "Failed to add mDoc"
                )))
            }
        }
    }

    override fun addAnyFormat(
        packId: String,
        rawCredential: String,
        mdocKeyAlias: String,
        callback: (Result<AddCredentialResult>) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val pack = packs[packId]
                if (pack == null) {
                    callback(Result.success(AddCredentialError(message = "Pack not found: $packId")))
                    return@launch
                }

                val credentials = pack.tryAddAnyFormat(rawCredential, mdocKeyAlias)
                callback(Result.success(AddCredentialSuccess(
                    credentials = credentials.map { it.toData() }
                )))
            } catch (e: Exception) {
                callback(Result.success(AddCredentialError(
                    message = e.localizedMessage ?: "Failed to add credential"
                )))
            }
        }
    }

    override fun parseRawCredential(
        rawCredential: String,
        format: CredentialFormat,
        callback: (Result<ParsedCredentialPreview>) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Dummy alias: parsing does not bind the credential to any key.
                // On accept, the wallet re-parses with a real alias before persisting.
                val previewAlias = "preview-only-${UUID.randomUUID()}"

                val parsed = if (format == CredentialFormat.MSO_MDOC) {
                    val mdoc = try {
                        Mdoc.fromStringifiedDocument(rawCredential, previewAlias)
                    } catch (_: Exception) {
                        Mdoc.newFromBase64urlEncodedIssuerSigned(rawCredential, previewAlias)
                    }
                    ParsedCredential.newMsoMdoc(mdoc)
                } else {
                    ParsedCredential.newFromStringWithFormat(
                        format = format.toRustFormatString(),
                        credential = rawCredential,
                        keyAlias = previewAlias,
                    )
                }

                var doctype: String? = null
                var vct: String? = null
                val claimsJson: String = when {
                    parsed.asMsoMdoc() != null -> {
                        val mdoc = parsed.asMsoMdoc()!!
                        doctype = mdoc.doctype()
                        mdoc.jsonEncodedDetailsAll().toString()
                    }
                    parsed.asJwtVc() != null -> parsed.asJwtVc()!!.credentialClaims().toString()
                    parsed.asJsonVc() != null -> parsed.asJsonVc()!!.credentialClaims().toString()
                    parsed.asSdJwt() != null -> parsed.asSdJwt()!!.credentialClaims().toString()
                    parsed.asDcSdJwt() != null -> {
                        val dcSdJwt = parsed.asDcSdJwt()!!
                        vct = dcSdJwt.vct()
                        dcSdJwt.credentialClaims().toString()
                    }
                    parsed.asCwt() != null -> parsed.asCwt()!!.credentialClaims().toString()
                    else -> throw IllegalStateException(
                        "Parsed credential did not match any known format accessor"
                    )
                }

                callback(Result.success(ParsedCredentialPreview(
                    format = format,
                    doctype = doctype,
                    vct = vct,
                    claimsJson = claimsJson,
                )))
            } catch (e: Exception) {
                callback(Result.failure(e))
            }
        }
    }

    override fun listCredentials(packId: String): List<ParsedCredentialData> {
        val pack = packs[packId] ?: return emptyList()
        return pack.list().map { it.toData() }
    }

    override fun getCredentialClaims(
        packId: String,
        credentialId: String,
        claimNames: List<String>
    ): String? {
        val pack = packs[packId] ?: return null
        val credential = pack.getCredentialById(credentialId) ?: return null
        val claims = pack.getCredentialClaims(credential, claimNames)
        return claims.toString()
    }

    override fun getStatusLists(
        packId: String,
        hasConnection: Boolean,
        callback: (Result<Map<String, CredentialStatus>>) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val pack = packs[packId]
                if (pack == null) {
                    callback(Result.success(emptyMap()))
                    return@launch
                }
                val statuses = pack.getStatusListsAsync(hasConnection)
                val res = statuses.entries.associate { (id, status) ->
                    id.toString() to status.toPigeon()
                }
                callback(Result.success(res))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to resolve status lists: ${e.message}", e)
                callback(Result.success(emptyMap()))
            }
        }
    }

    override fun deletePack(
        packId: String,
        appGroupId: String?,
        userHash: String?,
        callback: (Result<CredentialOperationResult>) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val storageManager = StorageManager(context)
                val pack = packs[packId]
                if (pack != null) {
                    pack.remove(storageManager, userHash)
                } else {
                    // Pack not in memory — load from storage so we can remove its credentials too
                    val uuid = runCatching { UUID.fromString(packId) }.getOrNull()
                    if (uuid != null) {
                        SdkCredentialPack.load(storageManager, uuid, userHash)
                            ?.remove(storageManager, userHash)
                    }
                }
                packs.remove(packId)
                callback(Result.success(CredentialOperationSuccess(unused = null)))
            } catch (e: Exception) {
                callback(Result.success(CredentialOperationError(
                    message = e.localizedMessage ?: "Failed to delete pack"
                )))
            }
        }
    }

    override fun listPacks(): List<String> {
        return packs.keys.toList()
    }

    override fun savePack(
        packId: String,
        appGroupId: String?,
        userHash: String?,
        callback: (Result<CredentialOperationResult>) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val pack = packs[packId]
                if (pack == null) {
                    callback(Result.success(CredentialOperationError(message = "Pack not found: $packId")))
                    return@launch
                }

                val storageManager = StorageManager(context)
                pack.save(storageManager, userHash)

                Log.d(TAG, "Saved pack $packId to storage (userHash=${userHash?.take(8)})")
                callback(Result.success(CredentialOperationSuccess(unused = null)))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save pack: ${e.message}", e)
                callback(Result.success(CredentialOperationError(
                    message = "Failed to save pack: ${e.localizedMessage}"
                )))
            }
        }
    }

    override fun loadPack(
        packId: String,
        appGroupId: String?,
        userHash: String?,
        callback: (Result<CredentialOperationResult>) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val uuid = runCatching { UUID.fromString(packId) }.getOrNull()
                if (uuid == null) {
                    callback(Result.success(CredentialOperationError(message = "Invalid pack id: $packId")))
                    return@launch
                }
                val storageManager = StorageManager(context)
                val pack = SdkCredentialPack.load(storageManager, uuid, userHash)
                if (pack == null) {
                    callback(Result.success(CredentialOperationError(message = "Pack not found: $packId")))
                    return@launch
                }
                packs[packId] = pack
                Log.d(TAG, "Loaded pack $packId from storage (userHash=${userHash?.take(8)})")
                callback(Result.success(CredentialOperationSuccess(unused = null)))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load pack: ${e.message}", e)
                callback(Result.success(CredentialOperationError(
                    message = "Failed to load pack: ${e.localizedMessage}"
                )))
            }
        }
    }

    override fun loadAllPacks(
        appGroupId: String?,
        userHash: String?,
        callback: (Result<List<String>>) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val storageManager = StorageManager(context)
                val loadedPacks = SdkCredentialPack.loadPacks(storageManager, userHash)
                val packIds = mutableListOf<String>()
                for (pack in loadedPacks) {
                    val packId = pack.id().toString()
                    packs[packId] = pack
                    packIds.add(packId)
                }
                Log.d(TAG, "Loaded ${packIds.size} packs (userHash=${userHash?.take(8)})")
                callback(Result.success(packIds))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load packs: ${e.message}", e)
                callback(Result.success(emptyList()))
            }
        }
    }

    companion object {
        private const val TAG = "CredentialPackAdapter"
    }

    // Internal methods for other adapters

    /**
     * Get native ParsedCredential objects for use by other adapters
     */
    fun getNativeCredentials(packId: String): List<ParsedCredential> {
        val pack = packs[packId] ?: return emptyList()
        return pack.list()
    }

    /**
     * Get native CredentialPack for use by other adapters
     */
    fun getNativePack(packId: String): SdkCredentialPack? {
        return packs[packId]
    }

    /**
     * Extension function to convert ParsedCredential to ParsedCredentialData
     */
    private fun ParsedCredential.toData(): ParsedCredentialData {
        val format: CredentialFormat
        var rawCredential = ""
        var doctype: String? = null
        var vct: String? = null

        when {
            this.asJwtVc() != null -> {
                format = CredentialFormat.JWT_VC
                rawCredential = this.asJwtVc()!!.credentialClaims().toString()
            }
            this.asJsonVc() != null -> {
                format = CredentialFormat.JSON_VC
                rawCredential = this.asJsonVc()!!.credentialClaims().toString()
            }
            this.asSdJwt() != null -> {
                format = CredentialFormat.SD_JWT
                rawCredential = this.asSdJwt()!!.credentialClaims().toString()
            }
            this.asMsoMdoc() != null -> {
                val mdoc = this.asMsoMdoc()!!
                format = CredentialFormat.MSO_MDOC
                rawCredential = mdoc.jsonEncodedDetailsAll().toString()
                doctype = mdoc.doctype()
            }
            this.asCwt() != null -> {
                format = CredentialFormat.CWT
                rawCredential = this.asCwt()!!.credentialClaims().toString()
            }
            this.asDcSdJwt() != null -> {
                val dcSdJwt = this.asDcSdJwt()!!
                format = CredentialFormat.DC_SD_JWT
                rawCredential = dcSdJwt.credentialClaims().toString()
                vct = dcSdJwt.vct()
            }
            this.asOpticalBarcodeCredential() != null -> {
                val optical = this.asOpticalBarcodeCredential()!!
                format = CredentialFormat.OPTICAL_BARCODE
                rawCredential = optical.rawJsonld()
            }
            else -> {
                format = CredentialFormat.JWT_VC
            }
        }

        return ParsedCredentialData(
            id = this.id(),
            format = format,
            rawCredential = rawCredential,
            doctype = doctype,
            vct = vct
        )
    }
}

/// Map the Pigeon [CredentialFormat] enum to the format string accepted by
/// `ParsedCredential.newFromStringWithFormat`. Mirrors the canonical strings
/// defined in `rust/src/credential/mod.rs::CredentialFormat::Display`.
private fun CredentialFormat.toRustFormatString(): String = when (this) {
    CredentialFormat.MSO_MDOC -> "mso_mdoc"
    CredentialFormat.JWT_VC -> "jwt_vc_json"
    CredentialFormat.JSON_VC -> "ldp_vc"
    CredentialFormat.SD_JWT -> "vcdm2_sd_jwt"
    CredentialFormat.DC_SD_JWT -> "dc+sd-jwt"
    CredentialFormat.CWT -> "cwt"
    CredentialFormat.OPTICAL_BARCODE -> "optical_barcode_credential"
}

/// Map the native SDK [CredentialStatusList] to the Pigeon [CredentialStatus] enum.
private fun CredentialStatusList.toPigeon(): CredentialStatus = when (this) {
    CredentialStatusList.VALID -> CredentialStatus.VALID
    CredentialStatusList.REVOKED -> CredentialStatus.REVOKED
    CredentialStatusList.SUSPENDED -> CredentialStatus.SUSPENDED
    CredentialStatusList.UNKNOWN -> CredentialStatus.UNKNOWN
    CredentialStatusList.INVALID -> CredentialStatus.INVALID
    CredentialStatusList.UNDEFINED -> CredentialStatus.UNDEFINED
    CredentialStatusList.PENDING -> CredentialStatus.PENDING
    CredentialStatusList.READY -> CredentialStatus.READY
}
