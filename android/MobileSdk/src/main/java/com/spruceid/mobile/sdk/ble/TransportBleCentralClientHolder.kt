package com.spruceid.mobile.sdk.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import com.spruceid.mobile.sdk.BLESessionStateDelegate
import com.spruceid.mobile.sdk.byteArrayToHex
import java.util.*

/**
 * BLE Central Client (Holder Role) - ISO 18013-5 Section 8.3.3.1.1.4 Table 11
 *
 * Implements the mDL Holder device operating as BLE Central/GATT Client:
 * - Table 11: "mDL Holder Device" configuration for BLE Central role
 * - Section 8.3.3.1.1.3: Device engagement using ident parameter
 * - Section 8.3.3.1.1.5: BLE GATT characteristics management
 * - Section 8.3.3.1.1.6: Data transmission protocol implementation
 *
 * Protocol Flow:
 * 1. Scan for BLE Peripheral (mDL Reader) advertising the service
 * 2. Connect as GATT Client to Reader's GATT Server
 * 3. Discover and validate required GATT characteristics
 * 4. Authenticate using ident value (Section 8.3.3.1.1.3)
 * 5. Exchange mDL data according to characteristic protocol
 * 6. Handle session termination per Section 8.3.3.1.1.7
 *
 * @see ISO 18013-5 Table 11 for BLE Central configuration requirements
 * @see ISO 18013-5 Section 8.3.3.1.1.4 for role-specific implementation details
 */
class TransportBleCentralClientHolder(
    private var application: String,
    private var serviceUUID: UUID,
    private var updateRequestData: (data: ByteArray) -> Unit,
    private var callback: BLESessionStateDelegate?,
) {
    private val stateMachine = BleConnectionStateMachine.getInstance()
    private var bluetoothAdapter: BluetoothAdapter = stateMachine.getBluetoothManager().adapter

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
    fun connect(ident: ByteArray) {
        // Transition to connecting state
        if (!stateMachine.transitionTo(BleConnectionStateMachine.State.CONNECTING)) {
            Log.w(
                "TransportBleCentralClientHolder.connect",
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
                Log.d(
                    "TransportBleCentralClientHolder.gattClientCallback.onPeerConnected",
                    "Peer Connected"
                )
                // Transition to connected state
                if (stateMachine.transitionTo(BleConnectionStateMachine.State.CONNECTED)) {
                    callback?.update(mapOf(Pair("connected", "")))
                } else {
                    Log.w(
                        "TransportBleCentralClientHolder.gattClientCallback.onPeerConnected",
                        "Failed to transition to CONNECTED state"
                    )
                }
            }

            override fun onPeerDisconnected() {
                Log.d(
                    "TransportBleCentralClientHolder.gattClientCallback.onPeerDisconnected",
                    "Peer Disconnected"
                )
                // Transition to disconnected state
                stateMachine.transitionTo(BleConnectionStateMachine.State.DISCONNECTED)
                callback?.update(mapOf(Pair("disconnected", "")))
                gattClient.disconnect()
            }

            override fun onMessageSendProgress(progress: Int, max: Int) {
                Log.d(
                    "TransportBleCentralClientHolder.gattClientCallback.onMessageSendProgress",
                    "progress: $progress max: $max"
                )

                if (progress == max) {
                    callback?.update(mapOf(Pair("success", "")))
                } else {
                    callback?.update(
                        mapOf(
                            Pair(
                                "uploadProgress",
                                mapOf(Pair("curr", progress), Pair("max", max))
                            )
                        )
                    )
                }
            }

            override fun onMessageReceived(data: ByteArray) {
                super.onMessageReceived(data)
                Log.d(
                    "TransportBleCentralClientHolder.gattClientCallback.onMessageReceived",
                    "Message received ${byteArrayToHex(data)}"
                )

                try {
                    updateRequestData(data)
                } catch (e: Error) {
                    Log.e("MDoc", e.toString())
                    // Transition to error state on exception
                    stateMachine.transitionTo(BleConnectionStateMachine.State.ERROR, e.message)
                    callback?.update(mapOf(Pair("error", e)))
                }
            }

            override fun onTransportSpecificSessionTermination() {
                Log.d(
                    "TransportBleCentralClientHolder.gattClientCallback.onTransportSpecificSessionTermination",
                    "Transport Specific Session Terminated"
                )

                gattClient.disconnect()
            }

            override fun onLog(message: String) {
                Log.d("TransportBleCentralClientHolder.gattClientCallback.onLog", message)
            }

            override fun onState(state: String) {
                Log.d("TransportBleCentralClientHolder.gattClientCallback.onState", state)
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
            //TODO: move to error state and log error
            bluetoothAdapter.name = stateMachine.getAdapterName()
        }

        gattClient = GattClient(
            gattClientCallback,
            serviceUUID
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


    private val leScanCallback: ScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)
            stopScan()
            gattClient.connect(result.device, identValue)
        }
    }

    /**
     * Starts to scan for devices/peripherals to connect to - looks for a specific service UUID.
     *
     * Scanning is limited with a timeout to preserve battery life of a device.
     */
    fun scan() {
        val filter: ScanFilter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(serviceUUID))
            .build()

        val filterList: MutableList<ScanFilter> = ArrayList()
        filterList.add(filter)

        val settings: ScanSettings = ScanSettings.Builder()
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        synchronized(scanLock) {
            try {
                if (!scanning) {
                    // Clear any existing timeout before setting new one
                    scanTimeoutRunnable?.let { handler.removeCallbacks(it) }

                    scanTimeoutRunnable = Runnable {
                        synchronized(scanLock) {
                            if (scanning) {
                                scanning = false
                                try {
                                    bluetoothLeScanner.stopScan(leScanCallback)
                                } catch (e: Exception) {
                                    //TODO: failed to stop scan transition state machine to error, logger with e
                                }
                                scanTimeoutRunnable = null

                                //TODO: transition state machine to idle and use logger to talk about timeout
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
                //TODO: add logger
            } catch (error: IllegalStateException) {
                scanning = false
                scanTimeoutRunnable?.let { handler.removeCallbacks(it) }
                scanTimeoutRunnable = null
                //TODO: add logger
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

            if (scanning) {
                bluetoothLeScanner.stopScan(leScanCallback)
                scanning = false
            }
        } catch (error: SecurityException) {
            //TODO: logger error
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
}
