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
}
