package com.spruceid.mobile.sdk.ble

import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import com.spruceid.mobile.sdk.BLESessionStateDelegate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.*

/**
 * BLE Transport Layer Implementation for ISO 18013-5 mDL (Mobile Driver's License)
 *
 * This class implements the BLE transport protocol as specified in ISO 18013-5:
 * - Section 6.3.2.5 Table 2: Transport Protocol Selection
 * - Section 8.3.3: BLE Data Retrieval Protocol
 * - Section 8.3.3.1.1: BLE Transport Layer
 *
 * Responsibilities:
 * - Manages BLE connection state machine (IDLE → SCANNING → CONNECTING → CONNECTED → DISCONNECTED)
 * - Coordinates between GATT Client/Server roles for Reader/Holder interactions
 * - Handles transport-specific session management and termination
 * - Provides unified interface for BLE operations regardless of device role
 *
 * @see ISO 18013-5 Section 8.3.3.1.1.4 for detailed BLE protocol specification
 */
class Transport(
    bluetoothManager: BluetoothManager,
    context: Context,
    private val config: BleConfiguration = BleConfiguration()
) {

    private val logger = BleLogger.getInstance("Transport", config)
    private val retryManager = BleRetryManager(config, logger)
    private val threadPool = BleThreadPool.getInstance(config)
    val stateMachine = BleConnectionStateMachine.getInstance()

    private lateinit var transportBLE: TransportBle

    init {
        // Store the bluetoothManager and context in the singleton state machine
        stateMachine.setBluetoothManager(bluetoothManager, context)
    }

    /**
     * Initialize BLE Transport according to ISO 18013-5 Section 8.3.3.1.1
     *
     * Implements transport layer initialization for mDL data retrieval:
     * - Section 8.3.3.1.1.4: BLE transport method selection based on device role
     * - Section 8.3.3.1.1.3: Device engagement and ident value validation
     * - Table 11/12: Reader/Holder role-specific BLE configurations
     *
     * @param application "Reader" or "Holder" - defines device role per ISO 18013-5
     * @param serviceUUID BLE GATT service UUID for mDL communication
     * @param deviceRetrieval "BLE" - transport method selection (future: NFC, WiFi Aware)
     * @param deviceRetrievalOption "Central" or "Peripheral" - BLE role per Table 11/12
     * @param ident Identifier value for reader validation (Section 8.3.3.1.1.3)
     * @param updateRequestData Callback for processing incoming data requests
     * @param callback Delegate for BLE session state updates
     * @param encodedEDeviceKeyBytes Encoded device key for reader authentication
     */
    @androidx.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    fun initialize(
        application: String,
        serviceUUID: UUID,
        deviceRetrieval: String,
        deviceRetrievalOption: String,
        ident: ByteArray,
        updateRequestData: ((data: ByteArray) -> Unit)? = null,
        callback: BLESessionStateDelegate?,
        encodedEDeviceKeyBytes: ByteArray = ByteArray(0)
    ) {
        logger.i("Initializing transport: $deviceRetrieval/$deviceRetrievalOption")
        
        // Validate state transition
        if (!stateMachine.canTransitionTo(BleConnectionStateMachine.State.CONNECTING)) {
            logger.e("Invalid state for initialization: ${stateMachine.getState()}")
            callback?.update(mapOf("error" to "Invalid state for connection"))
            return
        }
        
        stateMachine.transitionTo(BleConnectionStateMachine.State.CONNECTING)

        /**
         * ISO 18013-5 Section 6.3.2.5 Table 2: Transport Protocol Selection
         *
         * Supported Device Retrieval Methods:
         * - BLE: Bluetooth Low Energy (implemented - Section 8.3.3)
         * - NFC: Near Field Communication (future implementation)
         * - Wi-Fi Aware: Wi-Fi Neighbor Awareness Networking (future implementation)
         *
         * Current implementation focuses on BLE as primary transport method
         * for mDL Reader-Holder communication as specified in Section 8.3.3.1.1
         */
        if (deviceRetrieval == "BLE") {
            logger.d("Selecting BLE Retrieval per ISO 18013-5 Section 8.3.3")

            transportBLE = TransportBle(stateMachine.getBluetoothManager(), config)
            // Use thread pool for initialization with retry manager
            threadPool.launchIO {
                val result = retryManager.executeWithRetryAndTimeout(
                    "BLE transport initialization",
                    config.connectionTimeoutMs
                )  {
                    transportBLE.initialize(
                        application,
                        serviceUUID,
                        deviceRetrievalOption,
                        ident,
                        updateRequestData,
                        callback,
                        encodedEDeviceKeyBytes
                    )
                }
                
                result.fold(
                    onSuccess = {
                        stateMachine.transitionTo(BleConnectionStateMachine.State.CONNECTED)
                        logger.i("BLE transport initialized successfully")
                    },
                    onFailure = { e ->
                        logger.e("Failed to initialize BLE transport after retries", e)
                        stateMachine.transitionTo(BleConnectionStateMachine.State.ERROR, e.message)
                        callback?.update(mapOf("error" to (e.message ?: "Unknown error")))
                    }
                )
            }
        }

        /**
         * Server Retrieval
         *
         * WebAPI
         * OIDC
         */
    }

    /**
     * Send mDL data via BLE transport - ISO 18013-5 Section 8.3.3.1.1.6
     *
     * Transmits the mobile driving license data to the reader device using
     * the established BLE connection. Data is sent according to the protocol
     * specified in Section 8.3.3.1.1.6 for BLE data transmission.
     *
     * @param payload mDL response data encoded as per ISO 18013-5
     */
    fun send(payload: ByteArray) {
        // Check state before sending
        if (!stateMachine.isInState(BleConnectionStateMachine.State.CONNECTED)) {
            logger.w("Cannot send data - not connected (state: ${stateMachine.getState()})")
            return
        }

        if (this::transportBLE.isInitialized) {
            logger.logDataTransfer("Sending", payload.size)
            transportBLE.send(payload)
        }
    }

    /**
     * Terminate BLE Transport Session - ISO 18013-5 Section 8.3.3.1.1.7
     *
     * Performs orderly session termination as specified in Section 8.3.3.1.1.7:
     * - Sends transport-specific session termination message
     * - Properly disconnects BLE GATT connections
     * - Transitions state machine to DISCONNECTED state
     *
     * This ensures compliance with the protocol's session management requirements.
     */
    fun terminate() {
        logger.i("Terminating transport")
        
        if (stateMachine.transitionTo(BleConnectionStateMachine.State.DISCONNECTING)) {
            try {
                if (this::transportBLE.isInitialized) {
                    transportBLE.terminate()
                }
                stateMachine.transitionTo(BleConnectionStateMachine.State.DISCONNECTED)
            } catch (e: Exception) {
                logger.e("Error during termination", e)
                stateMachine.transitionTo(BleConnectionStateMachine.State.ERROR, e.message)
            }
        }
    }

    /**
     * Emergency Hard Reset - Clean State Recovery
     *
     * Performs forceful termination and cleanup of all BLE resources:
     * - Bypasses normal termination protocol if necessary
     * - Resets internal state machine to IDLE
     * - Clears all pending operations and connections
     *
     * Use this method for error recovery or when normal termination fails.
     * Not part of ISO 18013-5 spec but necessary for robust implementation.
     */
    fun hardReset() {
        logger.w("Performing hard reset")
        
        try {
            if (this::transportBLE.isInitialized) {
                transportBLE.hardReset()
            }
        } catch (e: Exception) {
            logger.e("Error during hard reset", e)
        } finally {
            stateMachine.reset()
        }
    }
    
    /**
     * Get current connection state - useful for debugging and monitoring
     */
    fun getConnectionState(): BleConnectionStateMachine.State {
        return stateMachine.getState()
    }
    
    /**
     * Check if transport is ready to send data
     */
    fun isConnected(): Boolean {
        return stateMachine.isInState(BleConnectionStateMachine.State.CONNECTED)
    }
}