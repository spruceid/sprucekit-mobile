package com.spruceid.mobile.sdk.ble

import org.junit.Test

import org.junit.Assert.*

/**
 * Tests for [classifyClient2ServerChunk], the framing rules for chunks written to the
 * Client2Server GATT characteristic.
 */
class GattServerTest {
    private val mtu = 512

    /**
     * Regression: a final chunk's `0x00` leading byte must classify as [Client2ServerChunk.Final],
     * not be rejected as a self-contradictory "invalid first byte 0, expected 0 or 1". That spurious
     * error drove the session to ERROR after the message had already been delivered.
     */
    @Test
    fun finalChunkByteIsAccepted() {
        assertEquals(Client2ServerChunk.Final, classifyClient2ServerChunk(0x00, 5, mtu))
    }

    @Test
    fun finalChunkIsAcceptedRegardlessOfSize() {
        assertEquals(Client2ServerChunk.Final, classifyClient2ServerChunk(0x00, mtu * 4, mtu))
    }

    @Test
    fun intermediateChunkWithinMtuIsAccepted() {
        assertEquals(
            Client2ServerChunk.Intermediate,
            classifyClient2ServerChunk(0x01, mtu - 3, mtu)
        )
    }

    @Test
    fun intermediateChunkExceedingMtuIsInvalid() {
        assertTrue(classifyClient2ServerChunk(0x01, mtu - 2, mtu) is Client2ServerChunk.Invalid)
    }

    @Test
    fun unknownLeadingByteIsInvalid() {
        assertTrue(classifyClient2ServerChunk(0x02, 5, mtu) is Client2ServerChunk.Invalid)
    }
}
