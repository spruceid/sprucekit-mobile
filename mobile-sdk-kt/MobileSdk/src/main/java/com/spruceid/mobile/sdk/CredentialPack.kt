package com.spruceid.mobile.sdk

import android.util.Log
import com.spruceid.mobile.sdk.rs.CredentialDecodingException
import com.spruceid.mobile.sdk.rs.JsonVc
import com.spruceid.mobile.sdk.rs.JwtVc
import com.spruceid.mobile.sdk.rs.Mdoc
import com.spruceid.mobile.sdk.rs.ParsedCredential
import com.spruceid.mobile.sdk.rs.Vcdm2SdJwt
import com.spruceid.mobile.sdk.rs.StorageManagerInterface
import com.spruceid.mobile.sdk.rs.Uuid
import com.spruceid.mobile.sdk.rs.VdcCollection
import com.spruceid.mobile.sdk.rs.VdcCollectionException
import org.json.JSONException
import org.json.JSONObject
import java.lang.IllegalArgumentException
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
     *  Find claims from all credentials in this CredentialPack.
     */
    fun findCredentialClaims(claimNames: List<String>): Map<String, JSONObject> =
        this.list()
            .map { credential ->
                var claims: JSONObject
                val mdoc = credential.asMsoMdoc()
                val jwtVc = credential.asJwtVc()
                val jsonVc = credential.asJsonVc()
                val sdJwt = credential.asSdJwt()

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

                return@map Pair(credential.id(), claims)
            }
            .toMap()


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
     *
     *
     */
    @Throws(SavingException::class)
    fun save(storage: StorageManagerInterface) {
        val vdcCollection = VdcCollection(storage)
        try {
            list().forEach {
                if (vdcCollection.get(it.id()) == null) {
                    Log.d("sprucekit", "Saving credential '${it.id()}' " +
                            "to the VdcCollection")
                    vdcCollection.add(it.intoGenericForm())
                } else {
                    Log.d("sprucekit", "Skipped saving credential '${it.id()}' " +
                            "to the VdcCollection as it already exists")
                }
            }
        } catch (e: VdcCollectionException) {
            throw SavingException("failed to store credentials in VdcCollection", e)
        }

        val credentialIds = list()
            .map { it.id() }

        val contents = CredentialPackContents(id, credentialIds)
        try {
            storage.add("${PREFIX}${id}", contents.toBytes())
        } catch (e:Exception) {
            throw SavingException("unable to store or update CredentialPack", e)
        }
    }

    companion object {
        private const val PREFIX = "CredentialPack:"

        /**
         * List all CredentialPacks.
         *
         * These can then be individually loaded. For eager loading of all packs, see `loadPacks`.
         */
        @Throws(LoadingException::class)
        fun listPacks(storage: StorageManagerInterface): List<CredentialPackContents> {
            val contents: Iterable<CredentialPackContents>
            try {
                contents =
                    storage.list()
                        .filter { it.startsWith(PREFIX) }
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
        fun loadPacks(storage: StorageManagerInterface): List<CredentialPack> {
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
    private val ID_KEY = "id"
    private val CREDENTIALS_KEY = "credentials"
    private val json: JSONObject

    constructor(byteArray: ByteArray) {
        this.json = JSONObject(byteArray.decodeToString())
    }

    constructor(id: UUID, credentials: List<Uuid>) {
        this.json = JSONObject(buildMap {
            put(ID_KEY, id)
            put(CREDENTIALS_KEY, credentials)
        })
    }

    /**
     * Unique identifier for the CredentialPack.
     */
    @Throws(LoadingException::class)
    fun id(): UUID {
        try {
            return UUID.fromString(json.getString(ID_KEY))
        } catch (e: JSONException) {
            throw LoadingException("'$ID_KEY' does not exist, or is not a String", e)
        } catch (e: IllegalArgumentException) {
            throw LoadingException("'$ID_KEY' is not a valid UUID", e)
        }
    }

    /**
     * Identifiers for the credentials in this pack.
     */
    @Throws(LoadingException::class)
    fun credentials(): List<Uuid> {
        try {
            val array = json.getJSONArray(CREDENTIALS_KEY)
            return List(array.length(), { array.getString(it) })
        } catch (e: JSONException) {
            throw LoadingException("'$ID_KEY' does not exist, or is not a String", e)
        } catch (e: IllegalArgumentException) {
            throw LoadingException("'$ID_KEY' is not a valid UUID", e)
        }
    }

    /**
     * Loads all of the credentials from the VdcCollection into a CredentialPack.
     */
    @Throws(LoadingException::class)
    fun load(vdcCollection: VdcCollection): CredentialPack {
        val credentials =
            credentials()
                .mapNotNull {
                    try {
                        val credential = vdcCollection.get(it)
                        if (credential == null) {
                            Log.w("sprucekit", "credential '$it' in pack '${id()}'" +
                                    " could not be found")
                            Log.d("sprucekit", "VdcCollection: ${vdcCollection.allEntries()}")
                        }
                        credential
                    } catch (e: Exception) {
                        Log.w("sprucekit" ,"credential '$it' could not be loaded from" +
                                " storage")
                        return@mapNotNull null
                    }
                }
                .mapNotNull {
                    try {
                        return@mapNotNull ParsedCredential.parseFromCredential(it)
                    } catch (e: CredentialDecodingException) {
                        Log.w("sprucekit", "failed to parse credential '${it.id}'" +
                                " as a known variant")
                        return@mapNotNull null
                    }
                }
                .toMutableList()

        return CredentialPack(id(), credentials)
    }

    fun toBytes(): ByteArray = json.toString().toByteArray()
}

class LoadingException(message: String, cause: Throwable) : Exception(message, cause)
class SavingException(message: String, cause: Throwable) : Exception(message, cause)
