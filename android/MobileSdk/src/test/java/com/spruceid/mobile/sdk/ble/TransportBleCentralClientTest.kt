package com.spruceid.mobile.sdk.ble

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for [scanTimeoutAction], the decision a fired scan-timeout runnable makes in
 * [TransportBleCentralClient].
 */
class TransportBleCentralClientTest {

    /**
     * Regression guard (#316 side-effect): a STALE timeout — one whose armed generation no longer
     * matches the process-wide counter because a newer scan started — must still unregister its own
     * scan (STOP_SCAN_ONLY), never NONE. Skipping the stopScan orphaned the scan registration;
     * repeated presentations then exhausted Android's per-app scanner limit and surfaced as
     * SCAN_FAILED_APPLICATION_REGISTRATION_FAILED, which fails every subsequent scan until a
     * BLE/device restart. The teardown (disconnect/timeout) is still skipped so a newer session's
     * shared state machine is left untouched.
     */
    @Test
    fun staleTimeoutStillStopsScanButSkipsTeardown() {
        assertEquals(
            ScanTimeoutAction.STOP_SCAN_ONLY,
            scanTimeoutAction(wasScanning = true, armedGeneration = 1, currentGeneration = 2),
        )
    }

    /** A fresh (non-stale) timeout that caught an in-progress scan stops the scan AND tears down. */
    @Test
    fun freshTimeoutStopsScanAndTearsDown() {
        assertEquals(
            ScanTimeoutAction.STOP_SCAN_AND_TEARDOWN,
            scanTimeoutAction(wasScanning = true, armedGeneration = 7, currentGeneration = 7),
        )
    }

    /** If the scan already ended before the timeout fired, do nothing — regardless of staleness. */
    @Test
    fun timeoutWithNoActiveScanDoesNothing() {
        assertEquals(
            ScanTimeoutAction.NONE,
            scanTimeoutAction(wasScanning = false, armedGeneration = 1, currentGeneration = 1),
        )
        assertEquals(
            ScanTimeoutAction.NONE,
            scanTimeoutAction(wasScanning = false, armedGeneration = 1, currentGeneration = 2),
        )
    }
}
