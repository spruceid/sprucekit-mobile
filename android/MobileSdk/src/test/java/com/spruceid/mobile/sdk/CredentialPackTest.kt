package com.spruceid.mobile.sdk

import com.spruceid.mobile.sdk.ble.mockAndroidLog
import com.spruceid.mobile.sdk.rs.ParsedCredential
import com.spruceid.mobile.toolkit.StorageManagerException
import com.spruceid.mobile.toolkit.StorageManagerInterface
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

class CredentialPackTest {
    /** In-memory storage whose `get` throws [StorageManagerException.CouldNotDecryptValue] for [undecryptable] keys. */
    private class FakeStorage(
        val entries: MutableMap<String, ByteArray> = mutableMapOf(),
        val undecryptable: Set<String> = emptySet(),
        val listFails: Boolean = false,
        val listCancelled: Boolean = false,
        val listOrder: List<String>? = null,
    ) : StorageManagerInterface {
        override suspend fun add(key: String, value: ByteArray) {
            entries[key] = value
        }

        override suspend fun get(key: String): ByteArray? {
            if (key in undecryptable) throw StorageManagerException.CouldNotDecryptValue()
            return entries[key]
        }

        override suspend fun list(): List<String> {
            if (listCancelled) throw CancellationException("cancelled")
            if (listFails) throw Exception("cannot list stored objects")
            return listOrder ?: (entries.keys.toList() + undecryptable)
        }

        override suspend fun remove(key: String) {
            entries.remove(key)
        }
    }

    private fun packBytes(id: UUID, credentials: List<String>): ByteArray =
        JSONObject(mapOf("id" to id.toString(), "credentials" to credentials))
            .toString().toByteArray()

    @Before
    fun setUp() {
        mockAndroidLog()
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun constructContentsFromUuidAndCredentials() {
        val uuid = UUID.randomUUID()
        val credentialId = UUID.randomUUID().toString()
        val credentials = listOf(credentialId)
        val packContents = CredentialPackContents(uuid, credentials)

        try {
            assertEquals(uuid, packContents.id)
            assertEquals(credentials, packContents.credentials)
        } catch (e: Exception) {
            e.printStackTrace()
            throw e
        }
    }

    @Test
    fun constructContentsFromBytes() {
        val uuid = UUID.randomUUID()
        val credentials = listOf(UUID.randomUUID().toString(), UUID.randomUUID().toString())

        val packContents = CredentialPackContents(packBytes(uuid, credentials))

        assertEquals(uuid, packContents.id)
        assertEquals(credentials, packContents.credentials)
    }

    @Test
    fun constructContentsFromInvalidJsonThrowsLoadingException() {
        assertThrows(LoadingException::class.java) {
            CredentialPackContents("not json".toByteArray())
        }
    }

    @Test
    fun constructContentsWithoutCredentialsThrowsLoadingException() {
        val bytes = JSONObject(mapOf("id" to UUID.randomUUID().toString()))
            .toString().toByteArray()

        val e = assertThrows(LoadingException::class.java) { CredentialPackContents(bytes) }
        assertTrue(e.message!!.contains("credentials"))
    }

    @Test
    fun listPacksSkipsEntriesThatFailToLoad() {
        val good1 = UUID.randomUUID()
        val good2 = UUID.randomUUID()
        val storage = FakeStorage(
            entries = mutableMapOf(
                "CredentialPack:$good1" to packBytes(good1, listOf("a")),
                "CredentialPack:$good2" to packBytes(good2, listOf("b", "c")),
                "CredentialPack:${UUID.randomUUID()}" to "{not json".toByteArray(),
            ),
            undecryptable = setOf("CredentialPack:${UUID.randomUUID()}"),
        )

        val packs = runBlocking { CredentialPack.listPacks(storage) }

        assertEquals(setOf(good1, good2), packs.map { it.id }.toSet())
        assertEquals(2, packs.size)
    }

    @Test
    fun listPacksSkipsBadEntriesListedBeforeGoodOnes() {
        val good = UUID.randomUUID()
        val badJson = "CredentialPack:${UUID.randomUUID()}"
        val undecryptable = "CredentialPack:${UUID.randomUUID()}"
        val goodKey = "CredentialPack:$good"
        val storage = FakeStorage(
            entries = mutableMapOf(
                badJson to "{not json".toByteArray(),
                goodKey to packBytes(good, listOf("a")),
            ),
            undecryptable = setOf(undecryptable),
            listOrder = listOf(undecryptable, badJson, goodKey),
        )

        val packs = runBlocking { CredentialPack.listPacks(storage) }

        assertEquals(listOf(good), packs.map { it.id })
    }

    @Test
    fun listPacksPropagatesCancellation() {
        val storage = FakeStorage(listCancelled = true)

        assertThrows(CancellationException::class.java) {
            runBlocking { CredentialPack.listPacks(storage) }
        }
    }

    @Test
    fun listPacksFiltersByScope() {
        val unscoped = UUID.randomUUID()
        val scopedA = UUID.randomUUID()
        val scopedB = UUID.randomUUID()
        val storage = FakeStorage(
            entries = mutableMapOf(
                "CredentialPack:$unscoped" to packBytes(unscoped, listOf()),
                "user-a-CredentialPack:$scopedA" to packBytes(scopedA, listOf()),
                "user-b-CredentialPack:$scopedB" to packBytes(scopedB, listOf()),
                // Not a pack: must be ignored rather than parsed.
                UUID.randomUUID().toString() to "credential".toByteArray(),
            ),
            undecryptable = setOf("user-a-CredentialPack:${UUID.randomUUID()}"),
        )

        val unscopedPacks = runBlocking { CredentialPack.listPacks(storage) }
        val scopeAPacks = runBlocking { CredentialPack.listPacks(storage, "a") }
        val scopeBPacks = runBlocking { CredentialPack.listPacks(storage, "b") }

        assertEquals(listOf(unscoped), unscopedPacks.map { it.id })
        assertEquals(listOf(scopedA), scopeAPacks.map { it.id })
        assertEquals(listOf(scopedB), scopeBPacks.map { it.id })
    }

    @Test
    fun listPacksThrowsLoadingExceptionWhenListFails() {
        val storage = FakeStorage(listFails = true)

        assertThrows(LoadingException::class.java) {
            runBlocking { CredentialPack.listPacks(storage) }
        }
    }

    @Test
    fun loadMissingPackReturnsNull() {
        val storage = FakeStorage()

        assertNull(runBlocking { CredentialPack.load(storage, UUID.randomUUID()) })
    }

    @Test
    fun loadUndecryptablePackThrowsLoadingException() {
        val id = UUID.randomUUID()
        val storage = FakeStorage(undecryptable = setOf("CredentialPack:$id"))

        val e = assertThrows(LoadingException::class.java) {
            runBlocking { CredentialPack.load(storage, id) }
        }
        assertTrue(e.cause is StorageManagerException.CouldNotDecryptValue)
    }

    @Test
    fun loadMalformedPackThrowsLoadingException() {
        val id = UUID.randomUUID()
        val storage = FakeStorage(
            entries = mutableMapOf("CredentialPack:$id" to "{not json".toByteArray())
        )

        assertThrows(LoadingException::class.java) {
            runBlocking { CredentialPack.load(storage, id) }
        }
    }

    private fun parsedCredential(credentialId: String): ParsedCredential =
        mockk { every { id() } returns credentialId }

    @Test
    fun publicConstructorsHaveNoUnloadedCredentials() {
        val id = UUID.randomUUID()
        val pack = CredentialPack(id, mutableListOf(parsedCredential("a")))

        assertEquals(listOf("a"), pack.intoContents().credentials)
        assertEquals(listOf<String>(), CredentialPack().intoContents().credentials)
    }

    @Test
    fun contentsListLoadedThenUnloadedCredentialsWithoutDuplicates() {
        val id = UUID.randomUUID()
        val pack = CredentialPack(
            id,
            mutableListOf(parsedCredential("b"), parsedCredential("a")),
            listOf("d", "a", "c", "d"),
        )

        val contents = pack.intoContents()

        assertEquals(id, contents.id)
        assertEquals(listOf("b", "a", "d", "c"), contents.credentials)
        // The loaded list is unchanged: it shows the loaded credentials only.
        assertEquals(listOf("b", "a"), pack.list().map { it.id() })
    }

    @Test
    fun savedManifestKeepsUnloadedCredentials() {
        val id = UUID.randomUUID()
        val storage = FakeStorage()
        val pack = CredentialPack(id, mutableListOf(parsedCredential("a")), listOf("b", "a"))

        runBlocking { pack.intoContents().save(storage, "alice") }

        val saved = CredentialPackContents(storage.entries["user-alice-CredentialPack:$id"]!!)
        assertEquals(listOf("a", "b"), saved.credentials)
    }
}
