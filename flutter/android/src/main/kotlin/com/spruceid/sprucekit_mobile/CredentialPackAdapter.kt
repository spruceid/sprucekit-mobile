package com.spruceid.sprucekit_mobile

import android.content.Context
import com.spruceid.mobile.sdk.CredentialPack as SdkCredentialPack
import com.spruceid.mobile.sdk.credentialClaims
import com.spruceid.mobile.sdk.jsonEncodedDetailsAll
import com.spruceid.mobile.sdk.rs.ParsedCredential
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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

    override fun deletePack(
        packId: String,
        callback: (Result<CredentialOperationResult>) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
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

    /**
     * Extension function to convert ParsedCredential to ParsedCredentialData
     */
    private fun ParsedCredential.toData(): ParsedCredentialData {
        val format: CredentialFormat
        var rawCredential = ""

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
                format = CredentialFormat.MSO_MDOC
                rawCredential = this.asMsoMdoc()!!.jsonEncodedDetailsAll().toString()
            }
            this.asCwt() != null -> {
                format = CredentialFormat.CWT
                rawCredential = this.asCwt()!!.credentialClaims().toString()
            }
            else -> {
                format = CredentialFormat.JWT_VC
            }
        }

        return ParsedCredentialData(
            id = this.id(),
            format = format,
            rawCredential = rawCredential
        )
    }
}
