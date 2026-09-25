package com.spruceid.sprucekit_mobile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NfcEngagementCoordinatorTest {
    private val coordinator = NfcEngagementCoordinator()

    @Test
    fun `starts idle and ignores taps`() {
        assertEquals(NfcEngagementCoordinator.Phase.IDLE, coordinator.phase)
        assertFalse(coordinator.onCarrierInfo())
        assertFalse(coordinator.onNegotiationFailed())
        assertFalse(coordinator.onNfcTurnedOff())
    }

    @Test
    fun `arm accepts one tap and ignores the next`() {
        assertTrue(coordinator.arm())
        assertEquals(NfcEngagementCoordinator.Phase.ARMED, coordinator.phase)
        assertTrue(coordinator.onCarrierInfo())
        assertEquals(NfcEngagementCoordinator.Phase.ENGAGED, coordinator.phase)
        // A reader that polls again after the handover must not restart the session.
        assertFalse(coordinator.onCarrierInfo())
    }

    @Test
    fun `arm twice is a no-op`() {
        assertTrue(coordinator.arm())
        assertFalse(coordinator.arm())
        assertEquals(NfcEngagementCoordinator.Phase.ARMED, coordinator.phase)
    }

    @Test
    fun `cancel returns to idle from every phase`() {
        coordinator.arm()
        coordinator.cancel()
        assertEquals(NfcEngagementCoordinator.Phase.IDLE, coordinator.phase)
        coordinator.arm()
        coordinator.onCarrierInfo()
        coordinator.cancel()
        assertEquals(NfcEngagementCoordinator.Phase.IDLE, coordinator.phase)
    }

    @Test
    fun `a failed handover disarms and is reported once`() {
        coordinator.arm()
        assertTrue(coordinator.onNegotiationFailed())
        assertEquals(NfcEngagementCoordinator.Phase.IDLE, coordinator.phase)
        assertFalse(coordinator.onNegotiationFailed())
    }

    @Test
    fun `nfc turned off while armed disarms and is reported`() {
        coordinator.arm()
        assertTrue(coordinator.onNfcTurnedOff())
        assertEquals(NfcEngagementCoordinator.Phase.IDLE, coordinator.phase)
    }

    @Test
    fun `a tap after the session ended is not answered`() {
        coordinator.arm()
        coordinator.onCarrierInfo()
        assertTrue(coordinator.isListening)
        val engaged = coordinator.engagedGeneration()
        assertTrue(engaged != null)
        assertTrue(coordinator.onSessionEnded(engaged!!))
        assertFalse(coordinator.isListening)
        assertEquals(NfcEngagementCoordinator.Phase.IDLE, coordinator.phase)
        assertFalse(coordinator.onCarrierInfo())
        // A second end is not reported again.
        assertFalse(coordinator.onSessionEnded(engaged))
    }

    @Test
    fun `session end is ignored before the handover`() {
        assertEquals(null, coordinator.engagedGeneration())
        assertFalse(coordinator.onSessionEnded(coordinator.generation))
        coordinator.arm()
        assertEquals(null, coordinator.engagedGeneration())
        assertFalse(coordinator.onSessionEnded(coordinator.generation))
        assertTrue(coordinator.isListening)
    }

    @Test
    fun `a session end for an earlier attempt does not touch the next one`() {
        coordinator.arm()
        coordinator.onCarrierInfo()
        val first = coordinator.engagedGeneration()!!
        // The wallet cancels and arms again, and the new attempt gets its tap.
        coordinator.cancel()
        coordinator.arm()
        coordinator.onCarrierInfo()
        assertFalse(coordinator.onSessionEnded(first))
        assertEquals(NfcEngagementCoordinator.Phase.ENGAGED, coordinator.phase)
        assertTrue(coordinator.isListening)
        // The current attempt still ends normally.
        assertTrue(coordinator.onSessionEnded(coordinator.engagedGeneration()!!))
    }

    @Test
    fun `every phase change moves the generation`() {
        val start = coordinator.generation
        coordinator.arm()
        val armed = coordinator.generation
        assertTrue(armed != start)
        coordinator.onCarrierInfo()
        val engaged = coordinator.generation
        assertTrue(engaged != armed)
        coordinator.cancel()
        assertTrue(coordinator.generation != engaged)
        // A cancel from idle is not a change.
        val idle = coordinator.generation
        coordinator.cancel()
        assertEquals(idle, coordinator.generation)
    }

    @Test
    fun `nfc turned off after the handover is not reported`() {
        coordinator.arm()
        coordinator.onCarrierInfo()
        // The BLE session is live. NFC state no longer matters.
        assertFalse(coordinator.onNfcTurnedOff())
        assertEquals(NfcEngagementCoordinator.Phase.ENGAGED, coordinator.phase)
    }
}
