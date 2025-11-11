package com.spruceid.mobile.sdk.ble

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.util.Log
import androidx.annotation.RequiresPermission
import java.util.*

/**
 * The responsibility of this class is to advertise data and be available for connection. AKA Holder.
 * 18013-5 section 8.3.3.1.1.4 Table 12.
 */
class TransportBlePeripheralServerHolder(
    private var application: String,
    private var serviceUUID: UUID,
    private var updateRequestData: ((data: ByteArray) -> Unit)?
) {

    private val stateMachine = BleConnectionStateMachine.getInstance(BleConnectionStateMachineInstanceType.SERVER)
    // Lazy initialization to avoid accessing state machine before it's started
    private val bluetoothAdapter: BluetoothAdapter by lazy {
        stateMachine.getBluetoothManager().adapter
    }
    private var logger = BleLogger.getInstance("TransportBlePeripheralServerHolder")

    private lateinit var blePeripheral: BlePeripheral
    private lateinit var gattServer: GattServer

    /**
     * Sets up peripheral with GATT server mode.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun start() {
        // Transition to connecting state
        if (!stateMachine.transitionTo(BleConnectionStateMachine.State.CONNECTING)) {
            logger.w(
                "Failed to transition to CONNECTING state"
            )
        }

        /**
         * BLE Peripheral callback.
         */
        val blePeripheralCallback: BlePeripheralCallback = object : BlePeripheralCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                logger.d("blePeripheralCallback.onStartSuccess")
            }

            override fun onStartFailure(errorCode: Int) {
                logger.d("blePeripheralCallback.onStartFailure")
            }

            override fun onState(state: String) {
                logger.d(state)
            }
        }

        /**
         * GATT server callback.
         */
        val gattServerCallback: GattServerCallback = object : GattServerCallback() {
            override fun onPeerConnected() {
                logger.d(
                    "Peer Connected"
                )
                // Transition to connected state
                if (!stateMachine.transitionTo(BleConnectionStateMachine.State.CONNECTED)) {
                    logger.w(
                        "Failed to transition to CONNECTED state"
                    )
                }
            }

            override fun onPeerDisconnected() {
                logger.d(
                    "Peer Disconnected"
                )
                // Transition to disconnected state
                stateMachine.transitionTo(BleConnectionStateMachine.State.DISCONNECTED)
                gattServer.stop()
            }

            override fun onMessageSendProgress(progress: Int, max: Int) {
                logger.d(
                    "progress:$progress max:$max"
                )

                blePeripheral.stopAdvertise()
            }

            override fun onTransportSpecificSessionTermination() {
                // Transition to disconnected state on termination
                stateMachine.transitionTo(BleConnectionStateMachine.State.DISCONNECTED)
                gattServer.stop()
            }

            override fun onMessageReceived(data: ByteArray) {
                logger.d("Received request data: ${data.size} bytes")
                // Forward the request data to IsoMdlPresentation
                updateRequestData?.invoke(data)
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
            logger.e(error.toString())
        }

        gattServer = GattServer(
            gattServerCallback, serviceUUID, false
        )

        blePeripheral = BlePeripheral(blePeripheralCallback, serviceUUID)
        try {
            blePeripheral.advertise()
            gattServer.start(null)
        } catch (error: Exception) {
            logger.e(error.toString())
            stateMachine.transitionTo(BleConnectionStateMachine.State.ERROR, error.message)
            throw error
        }
    }

    /**
     * For sending the mDL.
     */
    fun send(payload: ByteArray) {
        gattServer.sendMessage(payload)
    }

    fun stop() {
        try {
            bluetoothAdapter.name = stateMachine.getAdapterName()
        } catch (error: SecurityException) {
            logger.e(error.toString())
        }

        gattServer.sendTransportSpecificTermination()
        blePeripheral.stopAdvertise()
        gattServer.stop()
        logger.i("Resources cleaned up successfully")

        // Update state machine if possible (best-effort, non-blocking)
        if (stateMachine.transitionTo(BleConnectionStateMachine.State.DISCONNECTING)) {
            stateMachine.transitionTo(BleConnectionStateMachine.State.DISCONNECTED)
            logger.i("State transitioned to DISCONNECTED")
        } else {
            logger.i("State transition skipped (current: ${stateMachine.getState()}), but resources cleaned up")
        }
    }

    /**
     * Terminates and resets all connections to ensure a clean state.
     */
    fun hardReset() {
        blePeripheral.stopAdvertise()
        gattServer.stop()
        gattServer.reset()

        // Force reset to idle state
        stateMachine.reset()
    }
}
