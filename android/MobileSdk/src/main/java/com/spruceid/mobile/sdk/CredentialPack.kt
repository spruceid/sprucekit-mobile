package com.spruceid.mobile.sdk

import android.util.Log
import com.spruceid.mobile.sdk.rs.CredentialDecodingException
import com.spruceid.mobile.sdk.rs.Cwt
import com.spruceid.mobile.sdk.rs.JsonVc
import com.spruceid.mobile.sdk.rs.JwtVc
import com.spruceid.mobile.sdk.rs.Mdoc
import com.spruceid.mobile.sdk.rs.ParsedCredential
import com.spruceid.mobile.sdk.rs.StorageManagerInterface
import com.spruceid.mobile.sdk.rs.Uuid
import com.spruceid.mobile.sdk.rs.Vcdm2SdJwt
import com.spruceid.mobile.sdk.rs.VdcCollection
import com.spruceid.mobile.sdk.rs.VdcCollectionException
import org.json.JSONException
import org.json.JSONObject
import java.util.UUID

/**
 * A collection of ParsedCredentials with methods to interact with all instances.
 *
 * A CredentialPack is a semantic grouping of Credentials for display in the wallet. For example,
 * the CredentialPack could represent:
 * - multiple copies of the same credential (for one-time use),
 * - different encodings of the same credential (JwtVC & JsonVC),
 * - multiple instances of the same credential type (vehicle title credentials for more than 1
 *   vehicle).
 */
class CredentialPack {
    private val id: UUID
    private val credentials: MutableList<ParsedCredential>

    constructor() {
        id = UUID.randomUUID()
        credentials = mutableListOf()
    }

    constructor(id: UUID, credentialsArray: MutableList<ParsedCredential>) {
        this.id = id
        this.credentials = credentialsArray
    }

    fun id(): UUID {
        return this.id
    }

    /**
     * Try to add a credential and throws a ParsingException if not possible
     */
    @Throws(ParsingException::class)
    fun tryAddRawCredential(rawCredential: String): List<ParsedCredential> {
        try {
            return this.addJsonVc(JsonVc.newFromJson(rawCredential))
        } catch (_: Exception) {
        }

        try {
            return this.addSdJwt(Vcdm2SdJwt.newFromCompactSdJwt(rawCredential))
        } catch (_: Exception) {
        }

        try {
            return this.addJwtVc(JwtVc.newFromCompactJws(rawCredential))
        } catch (_: Exception) {
        }

        try {
            return this.addCwt(Cwt.newFromBase10(rawCredential))
        } catch (_: Exception) {
        }

        throw ParsingException(
            message = "The credential format is not supported. Credential = $rawCredential",
            cause = null
        )
    }

    /**
     * Try to add a raw mDoc and throws a ParsingException if not possible
     */
    @Throws(ParsingException::class)
    fun tryAddRawMdoc(rawCredential: String, keyAlias: String): List<ParsedCredential> {
        val keyManager = KeyManager()
        if (!keyManager.keyExists(keyAlias)) {
            keyManager.generateSigningKey(keyAlias)
        }

        try {
            return this.addMdoc(Mdoc.fromStringifiedDocument(rawCredential, keyAlias))
        } catch (_: Exception) {
        }

        try {
            return this.addMdoc(
                Mdoc.newFromBase64urlEncodedIssuerSigned(
                    rawCredential,
                    keyAlias
                )
            )
        } catch (_: Exception) {
        }

        throw ParsingException(
            message = "The mdoc format is not supported. Credential = $rawCredential",
            cause = null
        )
    }

    /**
     * Try to add a credential in any supported format (standard credential or mdoc).
     * Attempts to parse as standard credential first, then as mdoc if that fails.
     * 
     * @param rawCredential The raw credential data as a string
     * @param mdocKeyAlias The key alias to use if parsing as mdoc is needed
     * @return List of parsed credentials
     * @throws ParsingException if the credential cannot be parsed in any supported format
     */
    @Throws(ParsingException::class)
    fun tryAddAnyFormat(rawCredential: String, mdocKeyAlias: String): List<ParsedCredential> {
        try {
            return tryAddRawCredential(rawCredential)
        } catch (e: Exception) {
            try {
                return tryAddRawMdoc(rawCredential, mdocKeyAlias)
            } catch (innerE: Exception) {
                throw ParsingException(
                    message = "The credential format is not supported in any format. Credential = $rawCredential",
                    cause = innerE
                )
            }
        }
    }

    /**
     * Add a JwtVc to the CredentialPack.
     */
    fun addJwtVc(jwtVc: JwtVc): List<ParsedCredential> {
        credentials.add(ParsedCredential.newJwtVcJson(jwtVc))
        return credentials
    }

    /**
     * Add a JsonVc to the CredentialPack.
     */
    fun addJsonVc(jsonVc: JsonVc): List<ParsedCredential> {
        credentials.add(ParsedCredential.newLdpVc(jsonVc))
        return credentials
    }

    /**
     * Add an Mdoc to the CredentialPack.
     */
    fun addMdoc(mdoc: Mdoc): List<ParsedCredential> {
        credentials.add(ParsedCredential.newMsoMdoc(mdoc))
        return credentials
    }

    /**
     * Add a SD-JWT to the CredentialPack.
     */
    fun addSdJwt(sdJwt: Vcdm2SdJwt): List<ParsedCredential> {
        credentials.add(ParsedCredential.newSdJwt(sdJwt))
        return credentials
    }

    /**
     * Add a CWT to the CredentialPack.
     */
    fun addCwt(cwt: Cwt): List<ParsedCredential> {
        credentials.add(ParsedCredential.newCwt(cwt))
        return credentials
    }

    /**
     * Get all status from all credentials async
     */
    suspend fun getStatusListsAsync(hasConnection: Boolean): Map<Uuid, CredentialStatusList> {
        val res = mutableMapOf<Uuid, CredentialStatusList>()
        credentials.forEach { credential ->
            val credentialId = credential.id()

            credential.asSdJwt()?.let {
                if (hasConnection) {
                    try {
                        val status = it.status()
                        res[credentialId] = CredentialStatusList.VALID
                        status.forEach {
                            if (it.isRevoked()) {
                                res[credentialId] = CredentialStatusList.REVOKED
                                return@forEach
                            } else if (it.isSuspended()) {
                                res[credentialId] = CredentialStatusList.SUSPENDED
                            }
                        }
                    } catch (_: Exception) {
                        res[credentialId] = CredentialStatusList.UNDEFINED
                    }
                } else {
                    res[credentialId] = CredentialStatusList.UNKNOWN
                }
            }
            credential.asJsonVc()?.let {
                if (hasConnection) {
                    try {
                        val status = it.status()
                        if (status.isRevoked()) {
                            res[credentialId] = CredentialStatusList.REVOKED
                        } else if (status.isSuspended()) {
                            res[credentialId] = CredentialStatusList.SUSPENDED
                        } else {
                            res[credentialId] = CredentialStatusList.VALID
                        }
                    } catch (_: Exception) {
                        res[credentialId] = CredentialStatusList.UNDEFINED
                    }
                } else {
                    res[credentialId] = CredentialStatusList.UNKNOWN
                }
            }
        }
        return res
    }

    /**
     *  Find claims from all credentials in this CredentialPack.
     */
    fun findCredentialClaims(claimNames: List<String>): Map<String, JSONObject> =
        this.list()
            .map { credential ->
                val claims = getCredentialClaims(credential, claimNames)
                return@map Pair(credential.id(), claims)
            }
            .toMap()
    /**
     * Find credential claims from a specific credential.
     */
    fun getCredentialClaims(credential: ParsedCredential, claimNames: List<String>): JSONObject {
        val claims: JSONObject
        val mdoc = credential.asMsoMdoc()
        val jwtVc = credential.asJwtVc()
        val jsonVc = credential.asJsonVc()
        val sdJwt = credential.asSdJwt()
        val cwt = credential.asCwt()

        if (mdoc != null) {
            claims = if (claimNames.isNotEmpty()) {
                mdoc.jsonEncodedDetailsFiltered(claimNames)
            } else {
                mdoc.jsonEncodedDetailsAll()
            }
        } else if (jwtVc != null) {
            claims = if (claimNames.isNotEmpty()) {
                jwtVc.credentialClaimsFiltered(claimNames)
            } else {
                jwtVc.credentialClaims()
            }
        } else if (jsonVc != null) {
            claims = if (claimNames.isNotEmpty()) {
                jsonVc.credentialClaimsFiltered(claimNames)
            } else {
                jsonVc.credentialClaims()
            }
        } else if (cwt != null) {
            claims = if (claimNames.isNotEmpty()) {
                cwt.credentialClaimsFiltered(claimNames)
            } else {
                cwt.credentialClaims()
            }
        } else if (sdJwt != null) {
            claims = if (claimNames.isNotEmpty()) {
                sdJwt.credentialClaimsFiltered(claimNames)
            } else {
                sdJwt.credentialClaims()
            }
        } else {
            var type: String
            try {
                type = credential.intoGenericForm().type
            } catch (e: Error) {
                type = "unknown"
            }
            Log.e("sprucekit", "unsupported credential type: $type")
            claims = JSONObject()
        }
        return claims
    }


    /**
     * Get credentials by id.
     */
    fun getCredentialsByIds(credentialsIds: List<String>): List<ParsedCredential> =
        this.list().filter { credential -> credentialsIds.contains(credential.id()) }


    /**
     * Get a credential by id.
     */
    fun getCredentialById(credentialId: String): ParsedCredential? =
        this.list().find { credential -> credential.id() == credentialId }


    /**
     * List all of the credentials in the CredentialPack.
     */
    fun list(): List<ParsedCredential> = this.credentials

    /**
     * Persists the CredentialPack in the StorageManager, and persists all Credentials in the
     * VdcCollection.
     *
     * If a Credential already exists in the VdcCollection (matching on id), then
     * it will be skipped without updating.
     */
    @Throws(SavingException::class)
    suspend fun save(storage: StorageManagerInterface) {
        val vdcCollection = VdcCollection(storage)
        try {
            list().forEach {
                if (vdcCollection.get(it.id()) == null) {
                    Log.d(
                        "sprucekit", "Saving credential '${it.id()}' " +
                                "to the VdcCollection"
                    )
                    vdcCollection.add(it.intoGenericForm())
                } else {
                    Log.d(
                        "sprucekit", "Skipped saving credential '${it.id()}' " +
                                "to the VdcCollection as it already exists"
                    )
                }
            }
        } catch (e: VdcCollectionException) {
            throw SavingException("failed to store credentials in VdcCollection", e)
        }

        intoContents().save(storage)
    }

    /**
     * Remove this CredentialPack from the StorageManager.
     *
     * Credentials that are in this pack __are__ removed from the VdcCollection.
     */
    @Throws(SavingException::class)
    suspend fun remove(storage: StorageManagerInterface) {
        intoContents().remove(storage)
    }

    private fun intoContents(): CredentialPackContents =
        CredentialPackContents(id, list().map { it.id() })

    companion object {
        /**
         * Clears all stored CredentialPacks.
         */
        suspend fun clearPacks(storage: StorageManagerInterface) {
            try {
                storage.list()
                    .filter { it.startsWith(CredentialPackContents.STORAGE_PREFIX) }
                    .forEach { storage.remove(it) }
            } catch (e: Exception) {
                throw ClearingException("unable to clear CredentialPacks", e)
            }
        }

        /**
         * List all CredentialPacks.
         *
         * These can then be individually loaded. For eager loading of all packs, see `loadPacks`.
         */
        @Throws(LoadingException::class)
        suspend fun listPacks(storage: StorageManagerInterface): List<CredentialPackContents> {
            val contents: Iterable<CredentialPackContents>
            try {
                contents =
                    storage.list()
                        .filter { it.startsWith(CredentialPackContents.STORAGE_PREFIX) }
                        .mapNotNull { storage.get(it) }
                        .map { CredentialPackContents(it) }
            } catch (e: Exception) {
                throw LoadingException("unable to list CredentialPacks", e)
            }
            return contents
        }

        /**
         * Loads all CredentialPacks.
         */
        suspend fun loadPacks(storage: StorageManagerInterface): List<CredentialPack> {
            val vdcCollection = VdcCollection(storage)
            return listPacks(storage)
                .map { it.load(vdcCollection) }
        }
    }
}

/**
 * Metadata for a CredentialPack, as loaded from the StorageManager.
 */
class CredentialPackContents {
    companion object {
        internal const val STORAGE_PREFIX = "CredentialPack:"
        private const val ID_KEY = "id"
        private const val CREDENTIALS_KEY = "credentials"
    }

    val id: UUID
    val credentials: List<Uuid>

    @Throws(LoadingException::class)
    constructor(byteArray: ByteArray) {
        val json = JSONObject(byteArray.decodeToString())

        try {
            id = UUID.fromString(json.getString(ID_KEY))
        } catch (e: JSONException) {
            throw LoadingException("'$ID_KEY' does not exist, or is not a String", e)
        } catch (e: IllegalArgumentException) {
            throw LoadingException("'$ID_KEY' is not a valid UUID", e)
        }

        try {
            val array = json.getJSONArray(CREDENTIALS_KEY)
            credentials = List(array.length(), { array.getString(it) })
        } catch (e: JSONException) {
            throw LoadingException("'$ID_KEY' does not exist, or is not a String", e)
        } catch (e: IllegalArgumentException) {
            throw LoadingException("'$ID_KEY' is not a valid UUID", e)
        }
    }

    constructor(id: UUID, credentials: List<Uuid>) {
        this.id = id
        this.credentials = credentials
    }

    /**
     * Loads all of the credentials from the VdcCollection into a CredentialPack.
     */
    @Throws(LoadingException::class)
    suspend fun load(vdcCollection: VdcCollection): CredentialPack {
        val credentials =
            credentials
                .mapNotNull {
                    try {
                        val credential = vdcCollection.get(it)
                        if (credential == null) {
                            Log.w(
                                "sprucekit", "credential '$it' in pack '${id}'" +
                                        " could not be found"
                            )
                            Log.d("sprucekit", "VdcCollection: ${vdcCollection.allEntries()}")
                        }
                        credential
                    } catch (e: Exception) {
                        Log.w(
                            "sprucekit", "credential '$it' could not be loaded from" +
                                    " storage"
                        )
                        return@mapNotNull null
                    }
                }
                .mapNotNull {
                    try {
                        return@mapNotNull ParsedCredential.parseFromCredential(it)
                    } catch (e: CredentialDecodingException) {
                        Log.w(
                            "sprucekit", "failed to parse credential '${it.id}'" +
                                    " as a known variant"
                        )
                        return@mapNotNull null
                    }
                }
                .toMutableList()

        return CredentialPack(id, credentials)
    }

    @Throws(SavingException::class)
    internal suspend fun save(storage: StorageManagerInterface) {
        try {
            storage.add(storageKey(), toBytes())
        } catch (e: Exception) {
            throw SavingException("unable to store or update CredentialPack", e)
        }
    }

    @Throws(SavingException::class)
    internal suspend fun remove(storage: StorageManagerInterface) {
        val vdcCollection = VdcCollection(storage)
        credentials.forEach {
            try {
                Log.d("sprucekit", "removing Credential '$it'")
                vdcCollection.delete(it)
            } catch (e: Exception) {
                Log.w("sprucekit", "failed to remove Credential '$it': $e")
            }
        }

        try {
            storage.remove(storageKey())
        } catch (e: Exception) {
            throw SavingException("unable to remove CredentialPack", e)
        }
    }

    private fun storageKey(): String = "$STORAGE_PREFIX${id}"

    private fun toBytes(): ByteArray = JSONObject(buildMap {
        put(ID_KEY, id)
        put(CREDENTIALS_KEY, credentials)
    }).toString().toByteArray()
}

class ParsingException(message: String, cause: Throwable?) : Exception(message, cause)
class LoadingException(message: String, cause: Throwable) : Exception(message, cause)
class SavingException(message: String, cause: Throwable) : Exception(message, cause)
class ClearingException(message: String, cause: Throwable) : Exception(message, cause)

enum class CredentialStatusList {
    /**
     * Valid credential
     */
    VALID,

    /**
     * Credential revoked
     */
    REVOKED,

    /**
     * Credential suspended
     */
    SUSPENDED,

    /**
     * No connection
     */
    UNKNOWN,

    /**
     * Invalid credential
     */
    INVALID,

    /**
     * Credential doesn't have status list
     */
    UNDEFINED,

    /**
     * Credential is pending approval
     */
    PENDING,

    /**
     * Credential is ready to be claimed
     */
    READY
}

fun credentialStatusListFromString(value: String): CredentialStatusList {
    return enumValues<CredentialStatusList>().find { it.name.equals(value, ignoreCase = true) }
        ?: CredentialStatusList.UNDEFINED
}
