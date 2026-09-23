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
        assertTrue(coordinator.onSessionEnded())
        assertFalse(coordinator.isListening)
        assertEquals(NfcEngagementCoordinator.Phase.IDLE, coordinator.phase)
        assertFalse(coordinator.onCarrierInfo())
        // A second end is not reported again.
        assertFalse(coordinator.onSessionEnded())
    }

    @Test
    fun `session end is ignored before the handover`() {
        assertFalse(coordinator.onSessionEnded())
        coordinator.arm()
        assertFalse(coordinator.onSessionEnded())
        assertTrue(coordinator.isListening)
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
