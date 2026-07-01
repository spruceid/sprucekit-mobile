package com.spruceid.mobile.sdk.ble

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.annotation.RequiresPermission
import com.spruceid.mobile.sdk.BLESessionStateDelegate
import com.spruceid.mobile.sdk.byteArrayToHex
import com.spruceid.mobile.sdk.rs.RequestException
import java.util.*
import java.util.concurrent.atomic.AtomicLong

/**
 * BLE Central Client - ISO 18013-5 Section 8.3.3.1.1.4 Table 11
 *
 * Implements both mDL Holder and Reader devices operating as BLE Central/GATT Client:
 * - Table 11: Device configuration for BLE Central role
 * - Section 8.3.3.1.1.3: Device engagement using ident parameter
 * - Section 8.3.3.1.1.5: BLE GATT characteristics management
 * - Section 8.3.3.1.1.6: Data transmission protocol implementation
 *
 * Protocol Flow for Holder (application="Holder"):
 * 1. Scan for BLE Peripheral (mDL Reader) advertising the service
 * 2. Connect as GATT Client to Reader's GATT Server
 * 3. Discover and validate required GATT characteristics
 * 4. Authenticate using ident value (Section 8.3.3.1.1.3)
 * 5. Receive mDL request from Reader
 * 6. Send mDL response using send() method
 * 7. Handle session termination per Section 8.3.3.1.1.7
 *
 * Protocol Flow for Reader (application="Reader"):
 * 1. Scan for BLE Peripheral (mDL Holder) advertising the service
 * 2. Connect as GATT Client to Holder's GATT Server
 * 3. Discover and validate required GATT characteristics
 * 4. Authenticate using ident value (Section 8.3.3.1.1.3)
 * 5. Automatically send mDL request (requestData) after connection
 * 6. Receive mDL response from Holder
 * 7. Handle session termination per Section 8.3.3.1.1.7
 *
 * @see ISO 18013-5 Table 11 for BLE Central configuration requirements
 * @see ISO 18013-5 Section 8.3.3.1.1.4 for role-specific implementation details
 */
class TransportBleCentralClient(
    private var application: String,
    private var serviceUUID: UUID,

    private var updateRequestData: ((data: ByteArray) -> Boolean)? = null,
    internal var callback: BLESessionStateDelegate?,
    private var requestData: ByteArray? = null
) {
    private val stateMachine =
        BleConnectionStateMachine.getInstance(BleConnectionStateMachineInstanceType.CLIENT)

    // Lazy initialization to avoid accessing state machine before it's started
    private val bluetoothAdapter: BluetoothAdapter by lazy {
        stateMachine.getBluetoothManager().adapter
    }
    private var logger = BleLogger.getInstance("TransportBleCentralClient")
    private val isReader = application == "Reader"

    // Forwards session events to the host delegate (see BleSessionEmitter).
    private val emitter = BleSessionEmitter(callback)

    private lateinit var gattClient: GattClient
    private lateinit var identValue: ByteArray

    private val bluetoothLeScanner = bluetoothAdapter.bluetoothLeScanner
    private var scanning = false
    private val handler = Handler(Looper.getMainLooper())
    private var scanTimeoutRunnable: Runnable? = null
    private val scanLock = Any()

    // Limits scanning to 30 seconds per ISO 18013-5 recommendations for power efficiency
    private val scanPeriod: Long = 30000

    /**
     * Initialize BLE Central Connection - ISO 18013-5 Section 8.3.3.1.1.4
     *
     * Establishes connection as BLE Central (GATT Client) to mDL Reader device:
     * 1. Validates ident parameter per Section 8.3.3.1.1.3 device engagement
     * 2. Initiates BLE scanning for Reader's advertised service UUID
     * 3. Connects to discovered Reader device as GATT Client
     * 4. Manages connection state transitions and error handling
     *
     * @param ident Device engagement identifier for Reader authentication (Section 8.3.3.1.1.3)
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun connect(ident: ByteArray) {
        // Transition to connecting state
        if (!stateMachine.transitionTo(BleConnectionStateMachine.State.CONNECTING)) {
            logger.w(
                "Failed to transition to CONNECTING state"
            )
        }

        /**
         * Should be generated based on the 18013-5 section 8.3.3.1.1.3.
         */
        identValue = ident

        /**
         * GATT client callback.
         */
        val gattClientCallback: GattClientCallback = object : GattClientCallback() {
            override fun onPeerConnected() {
                logger.d("Peer Connected")
                emitter.connected()

                // Reader as Central: Send the mDL request to Holder after connection
                if (isReader && requestData != null) {
                    logger.d("Sending mDL request: ${requestData!!.size} bytes")
                    gattClient.sendMessage(requestData!!)
                }
            }

            override fun onPeerDisconnected() {
                logger.d("Peer Disconnected")
                // Transition to disconnected state
                stateMachine.transitionTo(BleConnectionStateMachine.State.DISCONNECTED)
                emitter.disconnected()
                gattClient.disconnect()
            }

            override fun onMessageSendProgress(progress: Int, max: Int) {
                logger.d(
                    "progress: $progress max: $max"
                )

                if (BleSessionUpdates.isComplete(progress, max)) {
                    // Only send success callback for Holder role, Reader waits for mDL response
                    if (!isReader) {
                        emitter.success()
                    } else {
                        logger.d("mDL request sent successfully")
                    }
                } else {
                    emitter.uploadProgress(progress, max)
                }
            }

            override fun onMessageReceived(data: ByteArray) {
                super.onMessageReceived(data)
                logger.d(
                    "Message received ${byteArrayToHex(data)}"
                )

                try {
                    if (isReader) {
                        // Reader mode: Forward the mDL response to the application
                        logger.d("Received mDL response: ${data.size} bytes")
                        emitter.mdl(data)
                    } else {
                        // Holder mode: Process the request data
                        val cont = updateRequestData?.invoke(data) ?: true
                        if(!cont) {
                            logger.d(
                                "Got disconnect signal, trying to detach"
                            )

                            // Session terminated.
                            onTransportSpecificSessionTermination()
                        }
                    }
                } catch (e: Error) {
                    logger.e("${e.message}")
                    // Transition to error state on exception
                    stateMachine.transitionTo(BleConnectionStateMachine.State.ERROR, e.message)
                    emitter.error(e)
                } catch (e: RequestException) {
                    logger.e("${e.message}")
                    // this is a workaround for now investigate eReaderDevice key missing on last message
                    disconnect()
                }
            }

            override fun onTransportSpecificSessionTermination() {
                logger.d(
                    "Transport Specific Session Terminated"
                )

                gattClient.disconnect()
            }

            override fun onLog(message: String) {
                logger.d(message)
            }

            override fun onState(state: String) {
                logger.d(state)
            }
        }

        /**
         * Setting up device name for easier identification after connection - too large to be in
         * advertisement data.
         */
        try {
            if (bluetoothAdapter.name != null) {
                bluetoothAdapter.name = "mDL $application Device"
            }
        } catch (error: SecurityException) {
            logger.e("${error.message}")
            bluetoothAdapter.name = stateMachine.getAdapterName()
        }

        gattClient = GattClient(
            gattClientCallback, serviceUUID, isReader
        )

        scan()
    }

    /**
     * For sending the mDL.
     */
    fun send(payload: ByteArray) {
        gattClient.sendMessage(payload)
    }

    fun disconnect() {
        gattClient.sendTransportSpecificTermination()
        stopScan()
        gattClient.disconnect()
    }


    /**
     * Tracks scan-failed retry state so the SDK can auto-recover from
     * `SCAN_FAILED_SCANNING_TOO_FREQUENTLY` since Android 30+ enforces ≤5 scans
     * per 30s per app.
     */
    private var scanThrottleRetryAttempt = 0
    private val maxScanThrottleRetries = 1
    private var scanThrottleRetryRunnable: Runnable? = null

    private val leScanCallback: ScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)
            scanThrottleRetryAttempt = 0
            stopScan()
            gattClient.connect(result.device, identValue)
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            logger.e("BLE scan failed (code=$errorCode)")

            // Stop tracking the failed scan so we can retry cleanly.
            synchronized(scanLock) {
                scanning = false
                scanTimeoutRunnable?.let { handler.removeCallbacks(it) }
                scanTimeoutRunnable = null
            }

            if (errorCode == SCAN_FAILED_SCANNING_TOO_FREQUENTLY &&
                scanThrottleRetryAttempt < maxScanThrottleRetries) {
                scanThrottleRetryAttempt++
                logger.w(
                    "Scan throttled (SCANNING_TOO_FREQUENTLY); backing off " +
                        "${SCAN_THROTTLE_BACKOFF_MS}ms then retrying " +
                        "(attempt $scanThrottleRetryAttempt/$maxScanThrottleRetries).",
                )
                // Surface a transient signal so the host UI can show a
                // "please wait" hint instead of leaving the user staring
                // at a blank scanning indicator.
                emitter.scanThrottled()
                val retry = Runnable {
                    try {
                        scanThrottleRetryRunnable = null
                        scan()
                    } catch (e: Exception) {
                        logger.e("Scan retry after throttle failed: ${e.message}")
                        emitter.error("Scan retry failed: ${e.message}")
                    }
                }
                scanThrottleRetryRunnable = retry
                handler.postDelayed(retry, SCAN_THROTTLE_BACKOFF_MS)
                return
            }

            // Non-recoverable or retries exhausted — surface to caller.
            val message = when (errorCode) {
                SCAN_FAILED_ALREADY_STARTED -> "Scan already started."
                SCAN_FAILED_APPLICATION_REGISTRATION_FAILED ->
                    "Scan application registration failed."
                SCAN_FAILED_INTERNAL_ERROR -> "Scan internal error."
                SCAN_FAILED_FEATURE_UNSUPPORTED -> "BLE scan feature unsupported."
                SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES ->
                    "BLE scan out of hardware resources."
                SCAN_FAILED_SCANNING_TOO_FREQUENTLY ->
                    "Scan throttled by system (5/30s limit) and retry exhausted."
                else -> "Scan failed (code=$errorCode)."
            }
            emitter.error(message)
        }
    }

    /**
     * Starts to scan for devices/peripherals to connect to - looks for a specific service UUID.
     *
     * Scanning is limited with a timeout to preserve battery life of a device.
     */
    fun scan() {
        val filter: ScanFilter =
            ScanFilter.Builder().setServiceUuid(ParcelUuid(serviceUUID)).build()
        logger.d("BleCentralClient Scanning")

        val filterList: MutableList<ScanFilter> = ArrayList()
        filterList.add(filter)

        val settings: ScanSettings =
            ScanSettings.Builder().setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()

        synchronized(scanLock) {
            try {
                if (!scanning) {
                    // Clear any existing timeout before setting new one
                    scanTimeoutRunnable?.let { handler.removeCallbacks(it) }

                    val armedGeneration = scanGenerationCounter.incrementAndGet()

                    scanTimeoutRunnable = Runnable {
                        synchronized(scanLock) {
                            val current = scanGenerationCounter.get()
                            if (armedGeneration != current) {
                                logger.d(
                                    "Stale scan timeout runnable " +
                                        "(armed=$armedGeneration, current=$current); ignoring.",
                                )
                                return@Runnable
                            }
                            if (scanning) {
                                scanning = false
                                try {
                                    bluetoothLeScanner.stopScan(leScanCallback)
                                } catch (e: Exception) {
                                    logger.e("${e.message}")
                                }
                                scanTimeoutRunnable = null
                                logger.i("connection timeout")
                                emitter.timeout()
                                disconnect()
                            }
                        }
                    }
                    handler.postDelayed(scanTimeoutRunnable!!, scanPeriod)
                    scanning = true
                    bluetoothLeScanner.startScan(filterList, settings, leScanCallback)
                } else {
                    stopScanInternal()
                }
            } catch (error: SecurityException) {
                scanning = false
                scanTimeoutRunnable?.let { handler.removeCallbacks(it) }
                scanTimeoutRunnable = null
                logger.e("${error.message}")
            } catch (error: IllegalStateException) {
                scanning = false
                scanTimeoutRunnable?.let { handler.removeCallbacks(it) }
                scanTimeoutRunnable = null
                logger.e("${error.message}")
            }
        }
    }

    /**
     * Stops scanning for devices/peripherals.
     */
    fun stopScan() {
        synchronized(scanLock) {
            stopScanInternal()
        }
    }

    private fun stopScanInternal() {
        try {
            // Remove pending timeout callback to prevent memory leak
            scanTimeoutRunnable?.let { handler.removeCallbacks(it) }
            scanTimeoutRunnable = null

            scanThrottleRetryRunnable?.let { handler.removeCallbacks(it) }
            scanThrottleRetryRunnable = null

            if (scanning) {
                bluetoothLeScanner.stopScan(leScanCallback)
                scanning = false
            }
        } catch (error: SecurityException) {
            logger.e("${error.message}")
        } catch (error: IllegalStateException) {
        } finally {
            scanning = false
            scanTimeoutRunnable = null
        }
    }

    /**
     * Terminates and resets all connections to ensure a clean state.
     */
    fun hardReset() {
        stopScan()
        gattClient.disconnect()
        gattClient.reset()

        // Force reset to idle state
        stateMachine.reset()
    }

    private companion object {
        // 30s rolling window per Android's ScanThrottle;
        const val SCAN_THROTTLE_BACKOFF_MS = 31_000L

        /**
         * Process-wide atomic counter used to fence scan-timeout
         * runnables against stale instances.
         */
        val scanGenerationCounter = AtomicLong(0)
    }
}
