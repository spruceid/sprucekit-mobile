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
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.security.GeneralSecurityException

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

    @Test
    fun legacyFormatThrowsCouldNotDecryptValue() {
        storeRaw("abc;def")

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
}
