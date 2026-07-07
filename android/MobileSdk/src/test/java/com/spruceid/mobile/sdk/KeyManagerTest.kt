package com.spruceid.mobile.sdk

import org.junit.Test

import org.junit.Assert.*

/**
 * Tests for KeyManager supporting functions.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class KeyManagerTest {

    @Test
    fun clampOrFill() {
        val keyManager = KeyManager()

        // Greater than 32
        val inputMoreThan = ByteArray(33) { it.toByte() }
        val expectedMoreThan = inputMoreThan.drop(1).toByteArray()
        val resultMoreThan = keyManager.clampOrFill(inputMoreThan)

        assertArrayEquals(expectedMoreThan, resultMoreThan)

        // Less than 32.
        val inputLessThan = ByteArray(30) { it.toByte() }
        val expectedLessThan = ByteArray(2) { 0.toByte() } + inputLessThan
        val result = keyManager.clampOrFill(inputLessThan)

        assertArrayEquals(expectedLessThan, result)

        // Equal to 32.
        val inputEqualTo = ByteArray(32) { it.toByte() }
        val resultEqualTo = keyManager.clampOrFill(inputEqualTo)

        assertArrayEquals(inputEqualTo, resultEqualTo)
    }

    @Test
    fun directKeyEncryptionRoundtrip() {
        val keyManager = KeyManager()
        val key = ByteArray(32) { it.toByte() }
        val data = "some payload to protect".toByteArray()

        val (iv, encrypted) = keyManager.encryptWithDirectKey(key, data)
        val decrypted = keyManager.decryptWithDirectKey(key, iv, encrypted)

        assertArrayEquals(data, decrypted)
    }

    @Test
    fun directKeyEncryptionRoundtripEmptyPayload() {
        val keyManager = KeyManager()
        val key = ByteArray(32) { it.toByte() }

        val (iv, encrypted) = keyManager.encryptWithDirectKey(key, ByteArray(0))
        val decrypted = keyManager.decryptWithDirectKey(key, iv, encrypted)

        assertArrayEquals(ByteArray(0), decrypted)
    }

    @Test
    fun directKeyEncryptionUses96BitIv() {
        val keyManager = KeyManager()
        val key = ByteArray(32) { it.toByte() }

        val (iv, _) = keyManager.encryptWithDirectKey(key, "data".toByteArray())

        assertEquals(12, iv.size)
    }

    @Test
    fun directKeyEncryptionGeneratesUniqueIvs() {
        val keyManager = KeyManager()
        val key = ByteArray(32) { it.toByte() }
        val data = "same data".toByteArray()

        val ivs = (1..10).map { keyManager.encryptWithDirectKey(key, data).first.toList() }

        assertEquals(10, ivs.toSet().size)
    }

    @Test
    fun directKeyDecryptionFailsOnTamperedCiphertext() {
        val keyManager = KeyManager()
        val key = ByteArray(32) { it.toByte() }

        val (iv, encrypted) = keyManager.encryptWithDirectKey(key, "data".toByteArray())
        encrypted[0] = (encrypted[0].toInt() xor 0x01).toByte()

        assertThrows(Exception::class.java) {
            keyManager.decryptWithDirectKey(key, iv, encrypted)
        }
    }

    @Test
    fun directKeyDecryptionFailsWithWrongKey() {
        val keyManager = KeyManager()
        val key = ByteArray(32) { it.toByte() }
        val wrongKey = ByteArray(32) { (it + 1).toByte() }

        val (iv, encrypted) = keyManager.encryptWithDirectKey(key, "data".toByteArray())

        assertThrows(Exception::class.java) {
            keyManager.decryptWithDirectKey(wrongKey, iv, encrypted)
        }
    }
}
