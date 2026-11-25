package com.spruceid.mobile.sdk

import android.content.Context
import android.util.Base64
import com.spruceid.mobile.sdk.ble.mockAndroidLog
import com.spruceid.mobile.toolkit.StorageManagerException
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.nio.file.Files
import java.security.GeneralSecurityException
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class StorageManagerTest {
    private val key = "test-key"
    private val filename = "sprucekit:datastore:$key"
    private val context = mockk<Context>()
    private lateinit var storedFile: File

    @Before
    fun setUp() {
        mockAndroidLog()
        mockkStatic(Base64::class)
        every { Base64.decode(any<String>(), any()) } answers {
            java.util.Base64.getUrlDecoder().decode(firstArg<String>())
        }
        mockkConstructor(KeyManager::class)
        every { anyConstructed<KeyManager>().keyExists(any()) } returns true
        storedFile = File.createTempFile("storage-manager-test", null)
    }

    @After
    fun tearDown() {
        storedFile.delete()
        unmockkAll()
    }

    private fun storeRaw(contents: String) {
        storedFile.writeText(contents)
        every { context.openFileInput(filename) } answers { FileInputStream(storedFile) }
    }

    private fun b64(bytes: ByteArray): String =
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun hybrid(): String =
        "HYBRID|${b64(ByteArray(12))}|${b64(ByteArray(48))}|${b64(ByteArray(12))}|${b64(ByteArray(16))}"

    private fun assertCouldNotDecrypt() {
        assertThrows(StorageManagerException.CouldNotDecryptValue::class.java) {
            runBlocking { StorageManager(context).get(key) }
        }
    }

    @Test
    fun missingFileReturnsNull() {
        every { context.openFileInput(filename) } throws FileNotFoundException()

        assertNull(runBlocking { StorageManager(context).get(key) })
    }

    private fun storeRawBytes(contents: ByteArray) {
        storedFile.writeBytes(contents)
        every { context.openFileInput(filename) } answers { FileInputStream(storedFile) }
    }

    private fun legacy(): String = "${b64(ByteArray(12))};${b64(ByteArray(16))}"

    @Test
    fun validLegacyValueIsDecrypted() {
        val plaintext = "stored value".toByteArray()
        every {
            anyConstructed<KeyManager>().decryptPayload(any(), any(), any())
        } returns plaintext
        storeRaw(legacy())

        assertArrayEquals(plaintext, runBlocking { StorageManager(context).get(key) })
        verify(exactly = 1) {
            anyConstructed<KeyManager>().decryptPayload(any(), match { it.size == 12 }, any())
        }
    }

    @Test
    fun legacyValueWithoutSeparatorThrowsCouldNotDecryptValue() {
        storeRaw("not a stored value")

        assertCouldNotDecrypt()
        verify(exactly = 0) { anyConstructed<KeyManager>().decryptPayload(any(), any(), any()) }
    }

    @Test
    fun legacyValueWithTooManyPartsThrowsCouldNotDecryptValue() {
        storeRaw("${b64(ByteArray(12))};${b64(ByteArray(16))};${b64(ByteArray(16))}")

        assertCouldNotDecrypt()
        verify(exactly = 0) { anyConstructed<KeyManager>().decryptPayload(any(), any(), any()) }
    }

    @Test
    fun legacyValueWithInvalidBase64ThrowsCouldNotDecryptValue() {
        // A single Base64 character is invalid for both the JVM and the Android decoder.
        storeRaw("A;A")

        assertCouldNotDecrypt()
        verify(exactly = 0) { anyConstructed<KeyManager>().decryptPayload(any(), any(), any()) }
    }

    /// Stands in for the Keystore key with a fixed software AES key, so legacy values go through
    /// real AES-GCM decryption.
    private val softwareKey = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")

    private fun useSoftwareKeystoreKey() {
        every {
            anyConstructed<KeyManager>().decryptPayload(any(), any(), any())
        } answers {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, softwareKey, GCMParameterSpec(128, secondArg<ByteArray>()))
            cipher.doFinal(thirdArg<ByteArray>())
        }
    }

    /// A legacy value as SDK versions before 0.11.2 wrote it: `<iv>;<ciphertext>`, both Base64url
    /// without padding.
    private fun realLegacy(plaintext: ByteArray, iv: ByteArray = ByteArray(12) { 7 }): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, softwareKey, GCMParameterSpec(128, iv))
        return "${b64(iv)};${b64(cipher.doFinal(plaintext))}"
    }

    @Test
    fun realLegacyCiphertextIsDecrypted() {
        val plaintext = "stored value".toByteArray()
        useSoftwareKeystoreKey()
        storeRaw(realLegacy(plaintext))

        assertArrayEquals(plaintext, runBlocking { StorageManager(context).get(key) })
    }

    @Test
    fun legacyValueWithEmptyIvThrowsCouldNotDecryptValue() {
        useSoftwareKeystoreKey()
        storeRaw(";" + realLegacy("stored value".toByteArray()).substringAfter(";"))

        assertCouldNotDecrypt()
    }

    @Test
    fun legacyValueWithWrongIvThrowsCouldNotDecryptValue() {
        useSoftwareKeystoreKey()
        val ciphertext = realLegacy("stored value".toByteArray()).substringAfter(";")
        storeRaw("${b64(ByteArray(12) { 8 })};$ciphertext")

        assertCouldNotDecrypt()
    }

    @Test
    fun legacyValueWithTamperedCiphertextThrowsCouldNotDecryptValue() {
        useSoftwareKeystoreKey()
        val (iv, ciphertext) = realLegacy("stored value".toByteArray()).split(";")
        val tampered = java.util.Base64.getUrlDecoder().decode(ciphertext)
        tampered[0] = (tampered[0].toInt() xor 1).toByte()
        storeRaw("$iv;${b64(tampered)}")

        assertCouldNotDecrypt()
    }

    @Test
    fun legacyKeystoreDecryptFailureThrowsCouldNotDecryptValue() {
        every {
            anyConstructed<KeyManager>().decryptPayload(any(), any(), any())
        } throws GeneralSecurityException("tag mismatch")
        storeRaw(legacy())

        assertCouldNotDecrypt()
    }

    @Test
    fun legacyValueWithoutKeystoreKeyThrowsCouldNotDecryptValue() {
        every { anyConstructed<KeyManager>().keyExists(any()) } returns false
        storeRaw(legacy())

        assertCouldNotDecrypt()
        verify(exactly = 0) { anyConstructed<KeyManager>().decryptPayload(any(), any(), any()) }
    }

    @Test
    fun randomBytesThrowCouldNotDecryptValue() {
        // 64 bytes with no ';' and no "HYBRID|" prefix, like a corrupted file.
        storeRawBytes(ByteArray(64) { (0x80 + it).toByte() })

        assertCouldNotDecrypt()
    }

    @Test
    fun hybridFormatWithWrongPartCountThrowsCouldNotDecryptValue() {
        storeRaw("HYBRID|${b64(ByteArray(12))}|${b64(ByteArray(48))}")

        assertCouldNotDecrypt()
    }

    @Test
    fun invalidBase64ThrowsCouldNotDecryptValue() {
        storeRaw("HYBRID|!!|!!|!!|!!")

        assertCouldNotDecrypt()
    }

    @Test
    fun missingKeystoreKeyThrowsCouldNotDecryptValue() {
        every { anyConstructed<KeyManager>().keyExists(any()) } returns false
        storeRaw(hybrid())

        assertCouldNotDecrypt()
        verify(exactly = 0) { anyConstructed<KeyManager>().decryptPayload(any(), any(), any()) }
    }

    @Test
    fun keystoreDecryptFailureThrowsCouldNotDecryptValue() {
        every {
            anyConstructed<KeyManager>().decryptPayload(any(), any(), any())
        } throws GeneralSecurityException("keystore failure")
        storeRaw(hybrid())

        assertCouldNotDecrypt()
        verify(exactly = 1) { anyConstructed<KeyManager>().decryptPayload(any(), any(), any()) }
    }

    @Test
    fun dataDecryptFailureThrowsCouldNotDecryptValue() {
        every {
            anyConstructed<KeyManager>().decryptPayload(any(), any(), any())
        } returns ByteArray(32)
        every {
            anyConstructed<KeyManager>().decryptWithDirectKey(any(), any(), any())
        } throws GeneralSecurityException("tag mismatch")
        storeRaw(hybrid())

        assertCouldNotDecrypt()
        verify(exactly = 1) {
            anyConstructed<KeyManager>().decryptWithDirectKey(any(), any(), any())
        }
    }

    @Test
    fun validHybridValueIsDecrypted() {
        val plaintext = "stored value".toByteArray()
        every {
            anyConstructed<KeyManager>().decryptPayload(any(), any(), any())
        } returns ByteArray(32)
        every {
            anyConstructed<KeyManager>().decryptWithDirectKey(any(), any(), any())
        } returns plaintext
        storeRaw(hybrid())

        assertArrayEquals(plaintext, runBlocking { StorageManager(context).get(key) })
    }

    @Test
    fun listPacksReadsLegacyPacksAndSkipsMalformedOnes() {
        val dir = Files.createTempDirectory("storage-manager-test").toFile()
        try {
            every { context.filesDir } returns dir
            every { context.openFileInput(any()) } answers {
                FileInputStream(File(dir, firstArg<String>()))
            }
            val goodId = UUID.randomUUID()
            val badId = UUID.randomUUID()
            File(dir, "sprucekit:datastore:CredentialPack:$goodId").writeText(legacy())
            File(dir, "sprucekit:datastore:CredentialPack:$badId").writeText("not a stored value")
            every {
                anyConstructed<KeyManager>().decryptPayload(any(), any(), any())
            } returns JSONObject(mapOf("id" to goodId.toString(), "credentials" to listOf<String>()))
                .toString().toByteArray()

            val packs = runBlocking { CredentialPack.listPacks(StorageManager(context)) }

            assertEquals(listOf(goodId), packs.map { it.id })
        } finally {
            dir.deleteRecursively()
        }
    }
}
