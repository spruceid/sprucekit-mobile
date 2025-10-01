package com.spruceid.mobile.sdk.ble


import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Build
import android.util.Log
import com.spruceid.mobile.sdk.byteArrayToHex
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.*
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedTransferQueue
import kotlin.math.min
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission


/**
 * mDL BLE GATT Server - Configurable for Reader or Holder Role
 *
 * Implements GATT Server for mDL communication with role-specific characteristics:
 *
 * **Reader Server (Table 12 - UUIDs 00000005-8):**
 * - State: Receive Start (0x01) from Holder, send End (0x02) for termination
 * - Client2Server: Receive mDL responses from Holder
 * - Server2Client: Send mDL requests to Holder
 * - Ident: Provide HKDF(EdeviceKeyBytes, "BLEIdent") for Holder verification
 * - L2CAP: Provide PSM for direct socket communication
 *
 * **Holder Server (Table 11 - UUIDs 00000001-3):**
 * - State: Receive connection signals and termination
 * - Client2Server: Receive mDL requests from Reader
 * - Server2Client: Send mDL responses to Reader
 * - L2CAP: Provide PSM for high-bandwidth data transfer
 *
 * Supports both GATT characteristic-based chunked transfer and L2CAP direct sockets.
 *
 * @param isReaderServer true for Reader role (Table 12), false for Holder role (Table 11)
 */
class GattServer(
    private var callback: GattServerCallback,
    private var serviceUuid: UUID,
    private var isReaderServer: Boolean = false,
    private val config: BleConfiguration = BleConfiguration()
) {

    private val logger = BleLogger.getInstance("GattServer")
    private val stateMachine = BleConnectionStateMachine.getInstance(BleConnectionStateMachineInstanceType.SERVER)
    // Lazy initialization to avoid accessing state machine before it's started
    private val bluetoothManager: BluetoothManager by lazy { stateMachine.getBluetoothManager() }
    private val context: Context by lazy { stateMachine.getContext() }
    private val errorHandler = BleErrorHandler(logger)
    private val threadPool = BleThreadPool.getInstance(config)

    // Get the appropriate UUIDs based on server type
    private val characteristicStateUuid =
        if (isReaderServer) BleConstants.Reader.STATE_UUID else BleConstants.Holder.STATE_UUID
    private val characteristicClient2ServerUuid =
        if (isReaderServer) BleConstants.Reader.CLIENT_TO_SERVER_UUID else BleConstants.Holder.CLIENT_TO_SERVER_UUID
    private val characteristicServer2ClientUuid =
        if (isReaderServer) BleConstants.Reader.SERVER_TO_CLIENT_UUID else BleConstants.Holder.SERVER_TO_CLIENT_UUID
    private val characteristicIdentUuid =
        if (isReaderServer) BleConstants.Reader.IDENT_UUID else null
    private val characteristicL2CAPUuid =
        if (isReaderServer) BleConstants.Reader.L2CAP_UUID else BleConstants.Holder.L2CAP_UUID

    private var gattServer: BluetoothGattServer? = null
    private var currentConnection: BluetoothDevice? = null

    private var characteristicState: BluetoothGattCharacteristic? = null
    private var characteristicClient2Server: BluetoothGattCharacteristic? = null
    private var characteristicServer2Client: BluetoothGattCharacteristic? = null
    private var characteristicIdent: BluetoothGattCharacteristic? = null
    private var characteristicL2CAP: BluetoothGattCharacteristic? = null

    private var mtu = 0
    private var usingL2CAP = true

    @Volatile
    private var writeIsOutstanding = false
    private val writingQueue: Queue<ByteArray> = ArrayDeque()
    private val queueLock = Any()
    private var writingQueueTotalChunks = 0
    private var identValue: ByteArray? = byteArrayOf()
    private val incomingMessage: ByteArrayOutputStream = ByteArrayOutputStream()
    private val messageLock = Any()

    // Timing variables for transfer performance measurement
    private var transferStartTime: Long = 0
    private var isReceivingData: Boolean = false
    private var transferMode: String = "GATT" // "GATT" or "L2CAP"

    // L2CAP server properties
    private var l2capServerSocket: BluetoothServerSocket? = null
    private var l2capSocket: BluetoothSocket? = null
    private var l2capPSM: Int = 0

    // L2CAP operations now handled by thread pool
    private val l2capResponseQueue: BlockingQueue<ByteArray> = LinkedTransferQueue()
    private val L2CAP_BUFFER_SIZE = 8192 // 8KB

    private val bluetoothGattServerCallback: BluetoothGattServerCallback =
        object : BluetoothGattServerCallback() {
            override fun onConnectionStateChange(
                device: BluetoothDevice,
                status: Int,
                newState: Int
            ) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    if (stateMachine.transitionTo(BleConnectionStateMachine.State.CONNECTED)) {
                        callback.onState(BleStates.GattServerConnected.string)
                        logger.i("Gatt Server is connected to ${device.address}")
                    } else {
                        logger.e("Invalid state transition to connected")
                    }
                }

                if (newState == BluetoothProfile.STATE_DISCONNECTED
                    && currentConnection != null
                    && device.address.equals(currentConnection!!.address)
                ) {
                    logger.i("Device ${currentConnection!!.address} disconnected")

                    stateMachine.transitionTo(BleConnectionStateMachine.State.DISCONNECTED)
                    currentConnection = null
                    callback.onPeerDisconnected()
                }
            }

            override fun onCharacteristicReadRequest(
                device: BluetoothDevice, requestId: Int, offset: Int,
                characteristic: BluetoothGattCharacteristic
            ) {

                callback.onLog(
                    "onCharacteristicReadRequest, address=${device.address} requestId=$requestId " +
                            "offset=$offset uuid=${characteristic.uuid}"
                )

                if ((characteristicIdentUuid != null &&
                            characteristic.uuid.equals(characteristicIdentUuid))
                ) {

                    logger.d(
                        "Sending value: ${
                            byteArrayToHex(
                                identValue!!
                            )
                        }"
                    )
                    try {
                        gattServer!!.sendResponse(
                            device,
                            requestId,
                            BluetoothGatt.GATT_SUCCESS,
                            0,
                            identValue
                        )
                    } catch (error: SecurityException) {
                        callback.onError(error)
                        logger.e("${error.message}")
                    }
                } else if ((characteristicL2CAP != null &&
                            characteristic.uuid.equals(characteristicL2CAPUuid))
                ) {
                    if (l2capPSM == 0) {
                        callback.onError(Error("L2CAP PSM not yet available"))
                        logger.e("L2CAP PSM not yet available")
                        return
                    }

                    // Encode the 16-bit L2CAP PSM as a 2-byte little-endian array, per Bluetooth Core Spec
                    // Byte 0: Least significant byte (LSB) = PSM & 0xFF
                    // Byte 1: Most significant byte (MSB) = (PSM >> 8) & 0xFF
                    // Ex: 0x1234 -> [0x34, 0x12]
                    // Send L2CAP PSM value to client
                    val psmBytes = byteArrayOf(
                        (l2capPSM and 0xFF).toByte(),
                        ((l2capPSM shr 8) and 0xFF).toByte()
                    )

                    logger.i("Sending L2CAP PSM: $l2capPSM")
                    try {
                        gattServer!!.sendResponse(
                            device,
                            requestId,
                            BluetoothGatt.GATT_SUCCESS,
                            0,
                            psmBytes
                        )
                    } catch (error: SecurityException) {
                        callback.onError(error)
                        logger.e("${error.message}")
                    }

                } else {
                    callback.onError(
                        Error(
                            "Read on unexpected characteristic with " +
                                    "UUID ${characteristic.uuid}"
                        )
                    )
                    logger.e("Read on unexpected characteristic with UUID ${characteristic.uuid}")
                }
            }

            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice, requestId: Int,
                characteristic: BluetoothGattCharacteristic,
                preparedWrite: Boolean, responseNeeded: Boolean,
                offset: Int, value: ByteArray
            ) {

                val charUuid = characteristic.uuid

                logger.i(
                    "onCharacteristicWriteRequest, address=${device.address} " +
                            "uuid=${characteristic.uuid} offset=$offset value=$value"
                )

                /**
                 * If we are connected to a device, ignore write from any other device.
                 */
                if (currentConnection != null && !device.address.equals(currentConnection!!.address)) {
                    logger.i(
                        "Ignoring characteristic write request from ${device.address} since we're " +
                                "already connected to ${currentConnection!!.address}"
                    )
                    return
                }

                if (charUuid.equals(characteristicStateUuid) && value.size == 1) {
                    if (value[0].toInt() == 0x01) {
                        // State 0x01 = START signal - establish GATT connection
                        // L2CAP negotiation happens AFTER this, not before

                        if (currentConnection != null) {
                            logger.i(
                                "Ignoring duplicate START (0x01) from ${device.address} since we're " +
                                        "already connected to ${currentConnection!!.address}"
                            )
                            // Don't call onPeerConnected() again if already connected
                            return
                        } else {
                            currentConnection = device

                            logger.i(
                                "Received START signal (state 0x01) from ${device.address}"
                            )

                            callback.onPeerConnected()
                        }
                    } else if (value[0].toInt() == 0x02) {
                        callback.onTransportSpecificSessionTermination()
                    } else {
                        callback.onError(Error("Invalid byte ${value[0]} for state characteristic"))
                        logger.e("Invalid byte ${value[0]} for state characteristic")
                    }
                } else if (charUuid.equals(characteristicClient2ServerUuid)) {
                    if (value.isEmpty()) {
                        callback.onError(Error("Invalid empty value"))
                        logger.e("Invalid empty value")
                        return
                    }

                    if (currentConnection == null) {
                        callback.onError(Error("Write on Client2Server but not connected yet"))
                        logger.e("Write on Client2Server but not connected yet")
                        return
                    }

                    // If L2CAP is active, GATT characteristics should not receive data
                    if (usingL2CAP && l2capSocket != null && l2capSocket!!.isConnected) {
                        logger.w("Ignoring GATT data write - L2CAP transport is active")
                        // Send success response to avoid client errors, but ignore the data
                        if (responseNeeded) {
                            try {
                                gattServer!!.sendResponse(
                                    device,
                                    requestId,
                                    BluetoothGatt.GATT_SUCCESS,
                                    0,
                                    null
                                )
                            } catch (error: SecurityException) {
                                callback.onError(error)
                            }
                        }
                        return
                    }

                    // Track timing for first chunk
                    if (!isReceivingData) {
                        transferStartTime = System.currentTimeMillis()
                        isReceivingData = true
                        transferMode = "GATT"
                        if (!config.randomizeResponseTiming) {
                            logger.i("Starting GATT data transfer")
                        }
                    }

                    synchronized(messageLock) {
                        incomingMessage.write(value, 1, value.size - 1)

                        if (!config.randomizeResponseTiming) {
                            logger.i(
                                "Received chunk with ${value.size} bytes " +
                                        "(last=${value[0].toInt() == 0x00}), incomingMessage.length=" +
                                        "${incomingMessage.toByteArray().size}"
                            )
                        }

                        if (value[0].toInt() == 0x00) {
                            val finalMessage: ByteArray = incomingMessage.toByteArray()
                            // Calculate and log transfer time (without sensitive timing info)
                            if (isReceivingData && !config.randomizeResponseTiming) {
                                val transferTime = System.currentTimeMillis() - transferStartTime
                                logger.i("GATT transfer completed: ${finalMessage.size} bytes in ${transferTime}ms")
                                isReceivingData = false
                            }

                            incomingMessage.reset()
                            callback.onMessageReceived(finalMessage)
                        }
                    } // End synchronized block

                    if (value[0].toInt() == 0x01) {
                        if (value.size > mtu - 3) {
                            callback.onError(
                                Error(
                                    "Invalid size ${value.size} of data written Client2Server " +
                                            "characteristic, expected maximum size ${mtu - 3}"
                                )
                            )
                            logger.e(
                                "Invalid size ${value.size} of data written Client2Server " +
                                        "characteristic, expected maximum size ${mtu - 3}"
                            )
                            return
                        }
                    } else {
                        callback.onError(
                            Error(
                                "Invalid first byte ${value[0].toInt()} in Client2Server " +
                                        "data chunk, expected 0 or 1"
                            )
                        )
                        logger.e(
                            "Invalid first byte ${value[0].toInt()} in Client2Server " +
                                    "data chunk, expected 0 or 1"
                        )
                        return
                    }
                    if (responseNeeded) {
                        try {
                            gattServer!!.sendResponse(
                                device,
                                requestId,
                                BluetoothGatt.GATT_SUCCESS,
                                0,
                                null
                            )
                        } catch (error: SecurityException) {
                            callback.onError(error)
                        }
                    }
                } else {
                    callback.onError(
                        Error(
                            "Write on unexpected characteristic with UUID " +
                                    "${characteristic.uuid}"
                        )
                    )
                    logger.e(

                        "Write on unexpected characteristic with UUID " +
                                "${characteristic.uuid}"
                    )
                }
            }

            override fun onDescriptorReadRequest(
                device: BluetoothDevice, requestId: Int, offset: Int,
                descriptor: BluetoothGattDescriptor
            ) {

                logger.i(
                    "onDescriptorReadRequest, address=${device.address} " +
                            "uuid=${descriptor.characteristic.uuid} offset=$offset"
                )
            }

            override fun onDescriptorWriteRequest(
                device: BluetoothDevice, requestId: Int,
                descriptor: BluetoothGattDescriptor,
                preparedWrite: Boolean, responseNeeded: Boolean,
                offset: Int, value: ByteArray
            ) {

                logger.i(
                    "onDescriptorWriteRequest, address=${device.address} " +
                            "uuid=${descriptor.characteristic.uuid} offset=$offset value=$value " +
                            "responseNeeded=$responseNeeded"
                )

                if (responseNeeded) {
                    try {
                        gattServer!!.sendResponse(
                            device, requestId, BluetoothGatt.GATT_SUCCESS, 0,
                            null
                        )
                    } catch (error: SecurityException) {
                        callback.onError(error)
                        logger.e("${error.message}")
                    }
                }
            }

            override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
                this@GattServer.setMtu(mtu)
                logger.i("Negotiated MTU changed to $mtu for ${device.address}.")
            }

            override fun onNotificationSent(device: BluetoothDevice, status: Int) {
                logger.i("onNotificationSent, status=$status address=${device.address}")

                if (status != BluetoothGatt.GATT_SUCCESS) {
                    callback.onError(Error("Error in onNotificationSent status=$status"))
                    logger.e("Error in onNotificationSent status=$status")
                    return
                }

                if (writingQueueTotalChunks > 0) {
                    if (writingQueue.isEmpty()) {
                        callback.onMessageSendProgress(
                            writingQueueTotalChunks,
                            writingQueueTotalChunks
                        )
                        writingQueueTotalChunks = 0
                    } else {
                        callback.onMessageSendProgress(
                            writingQueueTotalChunks - writingQueue.size,
                            writingQueueTotalChunks
                        )
                    }
                }

                synchronized(queueLock) {
                    writeIsOutstanding = false
                }
                drainWritingQueue()
            }
        }

    /**
     * Process GATT Notification Queue - Send Next Data Chunk
     *
     * Sends next queued data chunk via Server2Client characteristic notification.
     * Each chunk prefixed with continuation flag and limited to (MTU-4) bytes.
     * Tracks transmission progress for callback updates.
     */
    private fun drainWritingQueue() {
        synchronized(queueLock) {
            logger.i("drainWritingQueue $writeIsOutstanding")

            if (writeIsOutstanding) {
                return
            }

            val chunk: ByteArray = writingQueue.poll() ?: return

            logger.i("Sending chunk with ${chunk.size} bytes (last=${chunk[0].toInt() == 0x00})")
            characteristicServer2Client!!.value = chunk

            try {
                if (!gattServer!!.notifyCharacteristicChanged(
                        currentConnection, characteristicServer2Client, false
                    )
                ) {
                    callback.onError(Error("Error calling notifyCharacteristicsChanged on Server2Client"))
                    logger.e("Error calling notifyCharacteristicsChanged on Server2Client")
                    return
                }
            } catch (error: SecurityException) {
                callback.onError(error)
                logger.e("${error.message}")
                return
            }

            writeIsOutstanding = true
        }
    }

    /**
     * Send mDL Data via Optimal Transport Method
     *
     * Transmits SessionEstablishment or SessionData messages via best method:
     * - L2CAP: Direct socket write for high throughput
     * - GATT: Chunked notifications via Server2Client characteristic
     *
     * GATT chunking: Splits into (MTU-4) byte chunks with continuation flags:
     * - 0x01: More data follows (continuation flag + 3-byte BLE overhead)
     * - 0x00: Final chunk
     *
     * @param data SessionEstablishment or SessionData message per 9.1.1.4
     */
    fun sendMessage(data: ByteArray) {
        // Validate state
        if (!stateMachine.isInState(BleConnectionStateMachine.State.CONNECTED)) {
            logger.w("Cannot send message - not connected (state: ${stateMachine.getState()})")
            return
        }

        if (currentConnection == null) {
            logger.w("Cannot send message - no active connection")
            return
        }

        val finalData = data

        // Use L2CAP if available and connected
        if (usingL2CAP && l2capSocket != null && l2capSocket!!.isConnected) {
            try {
                logger.logDataTransfer("Sending via L2CAP", finalData.size)

                // Write encrypted/final data to L2CAP socket
                l2capSocket!!.outputStream.write(finalData)
                l2capSocket!!.outputStream.flush()
                callback.onMessageSendProgress(1, 1) // Indicate completion
                return
            } catch (e: IOException) {
                logger.e("L2CAP send failed: ${e.message}, falling back to GATT")
                usingL2CAP = false
                // Fall through to GATT
            }
        }

        // GATT-based chunked transfer
        synchronized(queueLock) {
            if (mtu == 0) {
                logger.w("MTU not negotiated, defaulting to ${config.defaultMtu}. Performance will suffer.")
                mtu = config.defaultMtu
            }

            // Three less the MTU but we also need room for the leading 0x00 or 0x01.
            // Message size is three less than MTU, as opcode and attribute handle take up 3 bytes.
            // (mtu - 3) - oneForLeadingByte == mtu - 4
            val maxChunkSize: Int = mtu - 4
            var offset = 0

            do {
                val moreChunksComing = offset + maxChunkSize < finalData.size
                var size = finalData.size - offset

                if (size > maxChunkSize) {
                    size = maxChunkSize
                }

                val chunk = ByteArray(size + 1)

                chunk[0] = if (moreChunksComing) 0x01.toByte() else 0x00.toByte()

                System.arraycopy(finalData, offset, chunk, 1, size)
                writingQueue.add(chunk)
                offset += size
            } while (offset < finalData.size)
            writingQueueTotalChunks = writingQueue.size
        }

        drainWritingQueue()
    }

    /**
     * Send Session End Signal - Notify Client via State Characteristic
     *
     * For GATT: Sends 0x02 via State characteristic notification
     * For L2CAP: Schedules delayed socket closure (no GATT signaling)
     * Indicates transaction completion to connected Client device.
     */
    fun sendTransportSpecificTermination() {
        // L2CAP doesn't use transport-specific termination via GATT
        if (usingL2CAP) {
            logger.i("L2CAP doesn't use transport-specific termination, will close after delay")
            // For L2CAP, schedule close after a delay using thread pool
            threadPool.scheduleDelayed(1000L) {
                closeL2CAP()
            }
            return
        }

        // GATT-based termination
        if (currentConnection == null) {
            logger.i("No current connection to send termination to")
            return
        }

        val terminationCode = byteArrayOf(0x02.toByte())
        characteristicState!!.value = terminationCode

        try {
            if (gattServer != null && !gattServer!!.notifyCharacteristicChanged(
                    currentConnection,
                    characteristicState, false
                )
            ) {
                callback.onError(Error("Error calling notifyCharacteristicsChanged on State"))
                logger.e("Error calling notifyCharacteristicsChanged on State")
            }
        } catch (error: SecurityException) {
            callback.onError(error)
            logger.e("${error.message}")
        }
    }

    /**
     * Initialize GATT Server with Role-Specific Characteristics
     *
     * Creates BLE GATT service with characteristics for assigned role:
     * - State: Connection state management (Write Without Response + Notify)
     * - Client2Server: Incoming data channel (Write Without Response)
     * - Server2Client: Outgoing data channel (Notify)
     * - Ident: Authentication value (Read) - Reader role only
     * - L2CAP: PSM exchange for direct sockets (Read) - if supported
     *
     * Starts L2CAP server socket if enabled for high-throughput transfers.
     *
     * @param ident Authentication value for Ident characteristic (Reader role)
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun start(ident: ByteArray?) {
        identValue = ident
        this.reset()

        try {
            logger.i("Opening GattServer")
            gattServer = bluetoothManager.openGattServer(context, bluetoothGattServerCallback)
        } catch (error: SecurityException) {
            stateMachine.transitionTo(BleConnectionStateMachine.State.ERROR, error.message)
            errorHandler.handleError(error, "start") {
                callback.onError(it)
            }
            return
        }

        if (gattServer == null) {
            val error = BleException.ResourceException("GattServer", "failed to open")
            stateMachine.transitionTo(BleConnectionStateMachine.State.ERROR, error.message)
            errorHandler.handleError(error, "start") {
                callback.onError(it)
            }
            return
        }

        /**
         * Service
         */
        val service = BluetoothGattService(serviceUuid, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val identCharacteristic: BluetoothGattCharacteristic

        /**
         * State
         */
        val stateCharacteristic = BluetoothGattCharacteristic(
            characteristicStateUuid,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY
                    or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        val stateDescriptor = BluetoothGattDescriptor(
            BleConstants.CLIENT_CHARACTERISTIC_CONFIG_UUID,
            BluetoothGattDescriptor.PERMISSION_WRITE
        )

        stateDescriptor.value = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
        stateCharacteristic.addDescriptor(stateDescriptor)
        service.addCharacteristic(stateCharacteristic)
        characteristicState = stateCharacteristic

        /**
         * Client2Server
         */
        val clientServerCharacteristic = BluetoothGattCharacteristic(
            characteristicClient2ServerUuid,
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        service.addCharacteristic(clientServerCharacteristic)
        characteristicClient2Server = clientServerCharacteristic

        /**
         * Server2Client
         */
        val serverClientCharacteristic = BluetoothGattCharacteristic(
            characteristicServer2ClientUuid,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        val serverClientDescriptor = BluetoothGattDescriptor(
            BleConstants.CLIENT_CHARACTERISTIC_CONFIG_UUID,
            BluetoothGattDescriptor.PERMISSION_WRITE
        )

        serverClientDescriptor.value = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
        serverClientCharacteristic.addDescriptor(serverClientDescriptor)
        service.addCharacteristic(serverClientCharacteristic)
        characteristicServer2Client = serverClientCharacteristic

        /**
         * Ident
         */
        if (characteristicIdentUuid != null) {
            identCharacteristic = BluetoothGattCharacteristic(
                characteristicIdentUuid,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ
            )

            service.addCharacteristic(identCharacteristic)
            characteristicIdent = identCharacteristic
        }

        /**
         * L2CAP characteristic for PSM exchange
         */
        if (usingL2CAP && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            characteristicL2CAP = BluetoothGattCharacteristic(
                characteristicL2CAPUuid,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ
            )

            service.addCharacteristic(characteristicL2CAP)

            // Initialize L2CAP server socket
            setupL2CAPServer()
        }

        try {
            gattServer!!.addService(service)
        } catch (error: SecurityException) {
            callback.onError(error)
            return
        }
    }

    /**
     * Shutdown GATT Server - Complete Resource Cleanup
     *
     * Systematic shutdown sequence:
     * 1. Close L2CAP sockets and interrupt worker threads
     * 2. Cancel Client connections
     * 3. Close GATT server
     * 4. Clear transmission queues
     * 5. Reset all resource references
     */
    fun stop() {
        try {
            // Close L2CAP connections
            closeL2CAP()

            if (currentConnection != null) {
                gattServer?.cancelConnection(currentConnection)
            }

            gattServer?.close()

            synchronized(queueLock) {
                writingQueue.clear()
            }

            callback.onState(BleStates.StopGattServer.string)
            logger.i("Gatt Server stopped.")
        } catch (error: SecurityException) {
            callback.onError(error)
        } finally {
            // Ensure resources are cleaned up
            gattServer = null
            currentConnection = null
        }
    }

    fun reset() {
        synchronized(queueLock) {
            mtu = 0
            writingQueueTotalChunks = 0
            writingQueue.clear()
        }
        synchronized(messageLock) {
            incomingMessage.reset()
        }
        l2capResponseQueue.clear()
        isReceivingData = false
        transferStartTime = 0
    }

    private fun setMtu(mtu: Int) {
        synchronized(queueLock) {
            this.mtu = min(mtu, config.preferredMtu)
            logger.d("MTU set to ${this.mtu} (requested: $mtu, max: ${config.preferredMtu})")
        }
    }

    /**
     * Setup L2CAP Server Socket - Direct Data Channel Alternative
     *
     * Creates L2CAP server socket for high-bandwidth data transfer.
     * Bypasses GATT MTU limitations with direct socket communication.
     *
     * Process:
     * 1. Create secure L2CAP server socket (fallback to insecure)
     * 2. Validate generated PSM (must be odd, valid range)
     * 3. Start background accept thread
     * 4. Expose PSM via L2CAP characteristic for Client discovery
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun setupL2CAPServer() {
        if (!usingL2CAP) {
            logger.i("L2CAP disabled by configuration")
            return
        }

        try {
            val adapter = bluetoothManager.adapter
            if (adapter == null) {
                val error = BleException.ResourceException("BluetoothAdapter", "not available")
                errorHandler.handleError(error, "setupL2CAPServer") {
                    callback.onError(it)
                }
                return
            }

            // Create L2CAP server socket with security considerations
            // Try secure connection first
            try {
                l2capServerSocket = adapter.listenUsingL2capChannel()
            } catch (e: Exception) {
                logger.e("Secure L2CAP not available, connection denied", e)
                l2capServerSocket = adapter.listenUsingInsecureL2capChannel()
            }

            l2capPSM = l2capServerSocket!!.psm

            // Validate PSM range (must be odd and in valid range)
            if (l2capPSM <= 0 || l2capPSM > 0xFFFF) {
                throw BleException.ValidationException("Invalid L2CAP PSM: $l2capPSM")
            }

            logger.i("L2CAP server started with PSM: $l2capPSM")

            // Start accepting connections using thread pool
            threadPool.launchIO {
                acceptL2CAPConnection()
            }

        } catch (e: IOException) {
            val error =
                BleException.L2CAPException("Failed to create L2CAP server: ${e.message}", e)
            errorHandler.handleError(error, "setupL2CAPServer") {
                callback.onError(it)
            }
            usingL2CAP = false
        } catch (e: SecurityException) {
            val error = BleException.SecurityException(
                "Not authorized to create L2CAP server: ${e.message}",
                e
            )
            errorHandler.handleError(error, "setupL2CAPServer") {
                callback.onError(it)
            }
            usingL2CAP = false
        } catch (e: BleException) {
            errorHandler.handleError(e, "setupL2CAPServer") {
                callback.onError(it)
            }
            usingL2CAP = false
        }
    }

    /**
     * L2CAP Connection Acceptance Handler
     *
     * Manages incoming L2CAP socket connections from mDL Client devices.
     * Starts dedicated read thread for stream-based data reception and
     * notifies the application layer of successful L2CAP establishment.
     */
    private fun acceptL2CAPConnection() {
        try {
            logger.i("Waiting for L2CAP connection on PSM $l2capPSM")

            // Accept connection (blocking call, waits until a connection is established)
            l2capSocket = l2capServerSocket?.accept()

            if (l2capSocket != null) {
                logger.i("L2CAP socket accepted from ${l2capSocket!!.remoteDevice.address}")

                // Track if this is the first connection signal (State 0x01 not yet received)
                val isFirstConnection = (currentConnection == null)

                // Verify this is from the same device we have a GATT connection with
                if (currentConnection == null) {
                    logger.w("L2CAP connection before GATT State 0x01")
                    logger.i("Some implementations use L2CAP connection as readiness signal instead of State 0x01")

                    // Set currentConnection from L2CAP
                    currentConnection = l2capSocket!!.remoteDevice
                    logger.i("Set currentConnection from L2CAP: ${currentConnection!!.address}")
                } else if (!l2capSocket!!.remoteDevice.address.equals(currentConnection!!.address)) {
                    logger.e("L2CAP connection from different device ${l2capSocket!!.remoteDevice.address}, " +
                            "expected ${currentConnection!!.address} - rejecting")
                    l2capSocket?.close()
                    l2capSocket = null
                    return
                } else {
                    logger.i("L2CAP connection from correct device: ${currentConnection!!.address}")
                }

                // Start read thread for incoming data
                threadPool.launchIO {
                    readL2CAPData()
                }

                // Mark that L2CAP transport is now active
                // Data will be sent/received via L2CAP instead of GATT characteristics
                logger.i("Switching to L2CAP transport for data transfer")
                callback.onState("L2CAP Connected")

                // If this is the first connection signal (no State 0x01 yet), trigger onPeerConnected
                // Some clients (especially iOS) use L2CAP connection as readiness signal
                if (isFirstConnection) {
                    logger.i("Triggering onPeerConnected from L2CAP connection (no State 0x01 received)")
                    callback.onPeerConnected()
                }
            }
        } catch (e: IOException) {
            if (!Thread.currentThread().isInterrupted) {
                callback.onError(Error("L2CAP accept failed: ${e.message}"))
            }
        }
    }

    /**
     * L2CAP Stream Data Reader - High-Throughput Reception
     *
     * Handles continuous data reception from L2CAP socket streams.
     * Implements message boundary detection using timing-based approach
     * since L2CAP streams don't provide inherent framing information.
     *
     * Features:
     * - Performance measurement and logging
     * - Timeout-based message completion detection
     * - Graceful thread interruption handling
     * - Comprehensive error recovery
     */
    @SuppressLint("DefaultLocale")
    private fun readL2CAPData() {
        val buffer = ByteArray(L2CAP_BUFFER_SIZE)
        val inputStream = try {
            l2capSocket?.inputStream
        } catch (e: IOException) {
            callback.onError(Error("Failed to get L2CAP input stream: ${e.message}"))
            return
        }

        if (inputStream == null) {
            callback.onError(Error("L2CAP input stream is null"))
            return
        }

        val messageBuffer = ByteArrayOutputStream()
        var lastDataTime = System.currentTimeMillis()
        var l2capTransferStartTime: Long = 0
        var l2capDataStarted = false
        var firstDataTime: Long = 0  // Track when first data arrived for accurate timing

        try {
            while (!Thread.currentThread().isInterrupted && l2capSocket?.isConnected == true) {
                // Use available() to check if data is ready without blocking indefinitely
                if (inputStream.available() > 0) {
                    val bytesRead =
                        inputStream.read(buffer, 0, minOf(buffer.size, inputStream.available()))

                    // returns -1 if there is no more data because the end of the stream has been reached.
                    if (bytesRead == -1) {
                        logger.i("L2CAP connection closed by peer")
                        break
                    }

                    if (bytesRead > 0) {
                        // Track timing for first data
                        if (!l2capDataStarted) {
                            l2capTransferStartTime = System.currentTimeMillis()
                            firstDataTime = l2capTransferStartTime
                            l2capDataStarted = true
                            transferMode = "L2CAP"
                            logger.i("Starting L2CAP data transfer at $l2capTransferStartTime")
                        }

                        messageBuffer.write(buffer, 0, bytesRead)
                        lastDataTime = System.currentTimeMillis()
                        logger.i("L2CAP received chunk: $bytesRead bytes, total: ${messageBuffer.size()} bytes")
                    }
                } else {
                    // Check if we have data and enough time has passed since last data
                    if (messageBuffer.size() > 0) {
                        val timeSinceLastData = System.currentTimeMillis() - lastDataTime
                        if (timeSinceLastData > 500) { // 500ms timeout
                            // Complete message received
                            val message = messageBuffer.toByteArray()

                            // Calculate and log transfer time (excluding the timeout)
                            if (l2capDataStarted) {
                                // Transfer time is from start to last data received (not including the wait)
                                val transferTime = lastDataTime - l2capTransferStartTime
                                val transferRate = if (transferTime > 0) {
                                    (message.size * 1000.0 / transferTime) // bytes per second
                                } else 0.0
                                logger.i(
                                    "L2CAP transfer completed: ${message.size} bytes in ${transferTime}ms (${
                                        String.format(
                                            "%.2f",
                                            transferRate
                                        )
                                    } bytes/sec)"
                                )
                                l2capDataStarted = false
                            }

                            logger.i("L2CAP message complete: ${message.size} bytes")
                            callback.onMessageReceived(message)
                            messageBuffer.reset()
                        }
                    }
                    // Small sleep to avoid busy waiting
                    try {
                        Thread.sleep(10)
                    } catch (e: InterruptedException) {
                        // Thread was interrupted, exit gracefully
                        Thread.currentThread().interrupt()
                        break
                    }
                }
            }
        } catch (e: InterruptedException) {
            // Thread was interrupted, this is expected during shutdown
            Thread.currentThread().interrupt()
            logger.i("L2CAP read thread interrupted")
        } catch (e: IOException) {
            if (!Thread.currentThread().isInterrupted) {
                callback.onError(Error("L2CAP read error: ${e.message}"))
            }
        } finally {
            // Process any remaining data
            if (messageBuffer.size() > 0) {
                val message = messageBuffer.toByteArray()

                // Calculate and log transfer time if we were receiving data
                if (l2capDataStarted) {
                    // Use lastDataTime to exclude any wait time after last data
                    val transferTime = lastDataTime - firstDataTime
                    val transferRate = if (transferTime > 0) {
                        (message.size * 1000.0 / transferTime) // bytes per second
                    } else 0.0
                    logger.i(
                        "L2CAP transfer completed (final): ${message.size} bytes in ${transferTime}ms (${
                            String.format(
                                "%.2f",
                                transferRate
                            )
                        } bytes/sec)"
                    )
                }

                logger.i("L2CAP final message: ${message.size} bytes")
                callback.onMessageReceived(message)
            }
            logger.i("L2CAP read thread ending")
        }
    }

    /**
     * L2CAP Connection Cleanup - Resource Management
     *
     * Performs comprehensive cleanup of L2CAP resources:
     * - Gracefully interrupts and joins worker threads
     * - Closes L2CAP sockets with proper error handling
     * - Ensures all resources are freed to prevent memory leaks
     * - Resets L2CAP state for potential future connections
     */
    private fun closeL2CAP() {
        try {
            logger.i("Closing L2CAP connections...")

            // Thread pool operations will be cancelled automatically when scope is cancelled

            // Close sockets
            try {
                l2capSocket?.close()
            } catch (e: IOException) {
                logger.i("Error closing L2CAP socket: ${e.message}")
            }

            try {
                l2capServerSocket?.close()
            } catch (e: IOException) {
                logger.i("Error closing L2CAP server socket: ${e.message}")
            }

            logger.i("L2CAP connections closed")
        } catch (e: Exception) {
            logger.i("Error during L2CAP cleanup: ${e.message}")
        } finally {
            // Always clear references
            l2capSocket = null
            l2capServerSocket = null
            // Thread references no longer needed with thread pool
            usingL2CAP = false
        }
    }
}
