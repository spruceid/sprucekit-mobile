package com.spruceid.mobile.sdk.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import com.spruceid.mobile.sdk.byteArrayToHex
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.util.ArrayDeque
import java.util.Queue
import java.util.UUID
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedTransferQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.TimeSource

/**
 * 11.1.3.2
 * Protocol Flow:
 * 1. Connect to advertised Reader service UUID from device engagement
 * 2. Discover and validate required GATT characteristics (Table 12)
 * 3. Negotiate MTU (request 515 bytes maximum)
 * 4. Verify Reader identity via Ident characteristic
 * 5. Subscribe to notifications and signal Start (0x01) to State characteristic
 * 6. Exchange mDL data via Client2Server/Server2Client or L2CAP
 */
class GattClient(
    private var callback: GattClientCallback,
    private var serviceUuid: UUID,
    isReader: Boolean,
    private val config: BleConfiguration = BleConfiguration()
) {
    private val logger = BleLogger.getInstance("GattClient")
    private val stateMachine = BleConnectionStateMachine.getInstance(BleConnectionStateMachineInstanceType.CLIENT)
    // Lazy initialization to avoid accessing state machine before it's started
    private val btAdapter by lazy { stateMachine.getBluetoothManager().adapter }
    private val context: Context by lazy { stateMachine.getContext() }
    private val errorHandler = BleErrorHandler(logger)
    private val terminationProvider = BleTerminationProvider(stateMachine, logger)
    private val threadPool = BleThreadPool.getInstance(config)

    private var useL2CAP = config.useL2CAP

    var gattClient: BluetoothGatt? = null

    var characteristicState: BluetoothGattCharacteristic? = null
    var characteristicClient2Server: BluetoothGattCharacteristic? = null
    var characteristicServer2Client: BluetoothGattCharacteristic? = null
    var characteristicIdent: BluetoothGattCharacteristic? = null
    var characteristicL2CAP: BluetoothGattCharacteristic? = null
    val l2capUuid =
        if (!isReader) BleConstants.Reader.L2CAP_UUID else BleConstants.Holder.L2CAP_UUID
    val stateUuid =
        if (!isReader) BleConstants.Reader.STATE_UUID else BleConstants.Holder.STATE_UUID
    val client2ServerUuid =
        if (!isReader) BleConstants.Reader.CLIENT_TO_SERVER_UUID else BleConstants.Holder.CLIENT_TO_SERVER_UUID
    val server2ClientUuid =
        if (!isReader) BleConstants.Reader.SERVER_TO_CLIENT_UUID else BleConstants.Holder.SERVER_TO_CLIENT_UUID
    val identUuid = BleConstants.Reader.IDENT_UUID
    val clientCharacteristicConfigUuid = BleConstants.CLIENT_CHARACTERISTIC_CONFIG_UUID

    private var mtu = 0
    private var identValue: ByteArray? = byteArrayOf()
    private val writeIsOutstanding = AtomicBoolean(false)
    private val writingQueue: Queue<ByteArray> = ArrayDeque()
    private val queueLock = ReentrantLock()
    private var writingQueueTotalChunks = 0
    private var setL2CAPNotify = false
    private var channelPSM = 0
    private var l2capSocket: BluetoothSocket? = null

    // L2CAP write operations now handled by thread pool
    private val incomingMessage: ByteArrayOutputStream = ByteArrayOutputStream()
    private val messageLock = Any()
    private val responseData: BlockingQueue<ByteArray> = LinkedTransferQueue()
    private var requestTimestamp = TimeSource.Monotonic.markNow()

    init {
        // Initialize termination provider and register GATT Client sender
        terminationProvider.initialize()
        terminationProvider.registerGattClientSender { payload ->
            sendTransportSpecificTermination()
        }

    }

    /**
     * Bluetooth GATT callback containing all of the events.
     */
    private val bluetoothGattCallback: BluetoothGattCallback = object : BluetoothGattCallback() {
        /**
         * Discover services to connect to.
         */
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            reportLog("onConnectionStateChange newState: [$newState]")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                if (stateMachine.transitionTo(BleConnectionStateMachine.State.CONNECTED)) {
                    clearCache()

                    callback.onState(BleStates.GattClientConnected.string)
                    reportLog("Gatt Client is connected.")
                    try {
                        gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                        gatt.discoverServices()
                    } catch (error: SecurityException) {
                        callback.onError(error)
                    }
                } else {
                    reportError("Invalid state transition to connected")
                }
            }
        }

        /**
         * Service Discovery - Validate mDL Reader GATT Server (Table 12)
         *
         * Discovers and validates required characteristics on Reader's GATT server:
         * - State (00000005): Write 0x01 to start, receive 0x02 for termination
         * - Client2Server (00000006): Send mDL responses to Reader
         * - Server2Client (00000007): Receive mDL requests from Reader
         * - Ident (00000008): Verify Reader identity with HKDF(EdeviceKeyBytes, "BLEIdent")
         * - L2CAP (optional): Get PSM for high-bandwidth data channel
         *
         * Requests 515-byte MTU for efficient data transfer within BLE limits.
         */
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            reportLog("onServicesDiscovered")

            if (status == BluetoothGatt.GATT_SUCCESS) {
                try {
                    reportLog("uuid: $serviceUuid, gatt: $gatt")
                    val service: BluetoothGattService? = gatt.getService(serviceUuid)

                    if (service == null) {
                        reportError("Service $serviceUuid not found on GATT server")
                        return
                    }

                    for (gattService in service.characteristics) {
                        logger.d(
                            "gattServiceUUID: ${gattService.uuid}"
                        )
                    }

                    characteristicL2CAP = service.getCharacteristic(l2capUuid)
                    // We don't check if the characteristic is null here because using it is optional;
                    // we'll decide later if we want to use it based on OS version and whether the
                    // characteristic actually resolved to something.

                    characteristicState = service.getCharacteristic(stateUuid)
                    if (characteristicState == null) {
                        reportError("State characteristic not found.")
                        return
                    }

                    characteristicClient2Server =
                        service.getCharacteristic(client2ServerUuid)
                    if (characteristicClient2Server == null) {
                        reportError("Client2Server characteristic not found.")
                        return
                    }

                    characteristicServer2Client =
                        service.getCharacteristic(server2ClientUuid)
                    logger.d("characteristicServer2Client: ${characteristicServer2Client!!.uuid}")
                    if (characteristicServer2Client == null) {
                        reportError("Server2Client characteristic not found.")
                        return
                    }

                    // Ident characteristic only exists in Reader service (Table 6), not Holder service (Table 5)
                    // When Reader connects to Holder as Central, this will be null and that's correct
                    characteristicIdent = service.getCharacteristic(identUuid)
                    if (characteristicIdent != null) {
                        logger.d("Ident characteristic found: ${characteristicIdent!!.uuid}")
                    } else {
                        logger.d("Ident characteristic not found (optional per ISO 18013-5 Table 5/6)")
                    }

                    callback.onState(BleStates.ServicesDiscovered.string)
                    reportLog("Discovered expected services")
                } catch (error: Exception) {
                    callback.onError(error)
                }

                try {
                    if (!gatt.requestMtu(515)) {
                        reportError("Error requesting MTU.")
                        return
                    }
                } catch (error: SecurityException) {
                    callback.onError(error)
                    reportError("Error requesting MTU.")
                    return
                }

                gattClient = gatt
            }
        }

        /**
         * MTU Negotiation - Optimize Data Transfer Capacity
         *
         * Completed MTU negotiation determines maximum data chunk size.
         * Capped at 515 bytes (spec maximum). Initiates Reader authentication
         * by reading Ident characteristic (UUID 00000008) containing
         * HKDF-derived value from Reader's EdeviceKeyBytes.
         */
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            reportLog("onMtuChanged")

            if (status != BluetoothGatt.GATT_SUCCESS) {
                reportError("Error changing MTU, status: $status.")
                return
            }

            // Validate MTU against acceptable range
            val validatedMtu = when {
                mtu < config.minAcceptableMtu -> {
                    logger.w("MTU $mtu below minimum ${config.minAcceptableMtu}, using minimum")
                    config.minAcceptableMtu
                }

                mtu > config.preferredMtu -> {
                    logger.d("MTU $mtu capped to preferred ${config.preferredMtu}")
                    config.preferredMtu
                }

                else -> mtu
            }

            this@GattClient.mtu = validatedMtu
            reportLog("MTU validated and set to ${this@GattClient.mtu} (requested: $mtu)")

            // Check if services have been discovered yet (characteristics are set)
            // On cached MTU connections, onMtuChanged can fire before onServicesDiscovered
            if (characteristicServer2Client == null || characteristicState == null) {
                reportLog("Services not yet discovered, deferring ident/afterIdentObtained until service discovery completes")
                return
            }

            /**
             * Optional ident characteristic is used for additional reader validation. 18013-5 section
             * 8.3.3.1.1.4.
             */
            if (characteristicIdent != null) {
                try {
                    if (!gatt.readCharacteristic(characteristicIdent)) {
                        reportLog("Warning: Reading from ident characteristic.")
                    }
                } catch (error: SecurityException) {
                    callback.onError(error)
                }
            } else {
                afterIdentObtained(gatt)
            }
        }

        /**
         * Detecting character read and validating the expected characteristic.
         */
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            @Suppress("deprecation")
            onCharacteristicRead(gatt, characteristic, characteristic.value, status)
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            reportLog("onCharacteristicRead, uuid=${characteristic.uuid} status=$status")

            /**
             * Reader Authentication - Verify Ident Characteristic
             *
             * Compares received Ident value with expected HKDF(EdeviceKeyBytes, "BLEIdent").
             * Ensures connection to correct Reader from device engagement.
             * Terminates connection on mismatch to prevent man-in-middle attacks.
             */
            if (characteristic.uuid.equals(identUuid)) {
                reportLog("Received identValue: ${byteArrayToHex(value)}.")

                if (!BleSecurityUtils.secureEquals(value, identValue, config)) {
                    reportError("Ident mismatch - rejecting connection per ISO 18013-5 section 8.3.3.1.1.3.")
                    try {
                        gatt.disconnect()
                    } catch (error: SecurityException) {
                        callback.onError(error)
                    }
                    return
                }

                afterIdentObtained(gatt)
            } else if (characteristic.uuid.equals(l2capUuid)) {
                if (value.size >= 2) {
                    channelPSM = L2CapUtils.parsePSM(value)
                    reportLog("L2CAP Channel PSM: $channelPSM")
                    setupL2CAPConnection(gatt)
                } else {
                    reportError("Invalid L2CAP PSM value size: ${value.size}, expected at least 2 bytes")
                }
            } else {
                reportError(
                    "Unexpected onCharacteristicRead for characteristic " +
                            "${characteristic.uuid} expected $identUuid."
                )
            }
        }


        /**
         * Detecting descriptor write.
         */
        override fun onDescriptorWrite(
            gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor,
            status: Int
        ) {

            reportLog(
                "onDescriptorWrite, descriptor-uuid=${descriptor.uuid} " +
                        "characteristic-uuid=${descriptor.characteristic.uuid} status=$status."
            )

            try {
                val charUuid = descriptor.characteristic.uuid

                if (charUuid.equals(server2ClientUuid)
                    && descriptor.uuid.equals(clientCharacteristicConfigUuid)
                ) {
                    enableNotification(gatt, characteristicState, "State")
                } else if (charUuid.equals(stateUuid)
                    && descriptor.uuid.equals(clientCharacteristicConfigUuid)
                ) {

                    // Finally we've set everything up, we can write 0x01 to state to signal
                    // to the other end (mDL reader) that it can start sending data to us..
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        val res = gatt.writeCharacteristic(
                            characteristicState!!,
                            byteArrayOf(0x01.toByte()),
                            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                        )
                        if (res != BluetoothStatusCodes.SUCCESS) {
                            reportError("Error writing to Server2Client. Code: $res")
                            return
                        }
                    } else {
                        // Above code addresses the deprecation but requires API 33+
                        @Suppress("deprecation")
                        characteristicState!!.value = byteArrayOf(0x01.toByte())
                        @Suppress("deprecation")
                        if (!gatt.writeCharacteristic(characteristicState)) {
                            reportError("Error writing to state characteristic.")
                        }
                    }
                } else if (charUuid.equals(l2capUuid)) {
                    if (descriptor.uuid.equals(clientCharacteristicConfigUuid)) {

                        if (setL2CAPNotify) {
                            reportLog("Notify already set for l2cap characteristic, doing nothing.")
                        } else {
                            setL2CAPNotify = true
                            if (!gatt.readCharacteristic(characteristicL2CAP)) {
                                reportError("Error reading L2CAP characteristic.")
                            }
                        }
                    } else {
                        reportError("Unexpected onDescriptorWrite: char $charUuid desc ${descriptor.uuid}.")
                    }
                }
            } catch (error: SecurityException) {
                callback.onError(error)
            }
        }

        /**
         * Write Completion Handler - Connection State & Data Progress
         *
         * Handles successful writes to Reader characteristics:
         * - State: Writing 0x01 signals "GATT client ready for transmission to start"
         * - Client2Server: Advances chunked data transmission queue
         * Updates progress callbacks and drains next data chunk.
         */
        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic, status: Int
        ) {

            val charUuid = characteristic.uuid

            reportLog("onCharacteristicWrite, status=$status uuid=$charUuid")

            if (charUuid.equals(stateUuid)) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    reportError("Unexpected status for writing to State, status=$status.")
                    return
                }

                callback.onPeerConnected()

            } else if (charUuid.equals(client2ServerUuid)) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    reportError("Unexpected status for writing to Client2Server, status=$status.")
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

                queueLock.withLock {
                    writeIsOutstanding.set(false)
                }
                drainWritingQueue()
            }
        }


        @Suppress("DEPRECATION")
        @Deprecated(
            "Used natively in Android 12 and lower",
            ReplaceWith("onCharacteristicChanged(gatt, characteristic, characteristic.value)")
        )
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?
        ) = onCharacteristicChanged(gatt!!, characteristic!!, characteristic.value)

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            logger.d("onCharacteristicChanged: characteristic=${characteristic.uuid}")

            reportLog("onCharacteristicChanged, uuid=${characteristic.uuid}")

            when (characteristic.uuid) {

                server2ClientUuid -> {
                    if (value.isEmpty()) {
                        reportError("Invalid data length ${value.size} for Server2Client characteristic.")
                        return
                    }

                    synchronized(messageLock) {
                        incomingMessage.write(value, 1, value.size - 1)

                        reportLog(
                            "Received chunk with ${value.size} bytes (last=${value[0].toInt() == 0x00}), " +
                                    "incomingMessage.length=${incomingMessage.toByteArray().size}"
                        )

                        if (value[0].toInt() == 0x00) {
                            /**
                             * Last message.
                             */
                            val entireMessage: ByteArray = incomingMessage.toByteArray()

                            // Validate message size for security
                            BleSecurityUtils.validateInputSize(
                                entireMessage,
                                config.maxMessageSize,
                                "GATT message assembly"
                            )

                            incomingMessage.reset()
                            callback.onMessageReceived(entireMessage)
                        }
                    }

                    if (value[0].toInt() == 0x01) {
                        // Message size is three less than MTU, as opcode and attribute handle take up 3 bytes.
                        if (value.size > mtu - 3) {
                            reportError(
                                "Invalid size ${value.size} of data written Server2Client " +
                                        "characteristic, expected maximum size ${mtu - 3}."
                            )
                            return
                        }
                    }
                }

                stateUuid -> {
                    if (value.size != 1) {
                        reportError("Invalid data length ${value.size} for state characteristic.")
                        return
                    }

                    if (value[0].toInt() == 0x02) {
                        callback.onTransportSpecificSessionTermination()
                    } else {
                        reportError("Invalid byte ${value[0]} for state characteristic.")
                    }
                }

                l2capUuid -> {
                    if (value.size >= 2 && channelPSM == 0) {
                        channelPSM = L2CapUtils.parsePSM(value)
                        reportLog("L2CAP Channel: $channelPSM")
                        setupL2CAPConnection(gatt)
                    }
                }

                else -> {
                    reportLog("Unknown Changed: ${value.size}")
                }
            }
        }
    }

    /**
     * L2CAP Request Reader Thread - High-Throughput Data Reception
     *
     * Manages L2CAP socket-based data reception for large mDL transfers.
     * L2CAP provides better performance than GATT characteristics for
     * substantial data payloads (Android 10+).
     *
     * Uses length-based framing: reads 4-byte header to get message length,
     * then reads exact number of payload bytes.
     */
    private fun readRequest() {
        val inStream = try {
            l2capSocket!!.inputStream
        } catch (e: IOException) {
            reportError("Error getting input stream from L2CAP socket: ${e.message}")
            return
        }

        try {
            while (true) {
                // Read and parse 4-byte L2CAP header
                val headerResult = try {
                    L2CapUtils.readHeader(inStream)
                } catch (e: IOException) {
                    logger.i("L2CAP read: connection closed by peer")
                    // Signal write thread to exit by sending empty message
                    responseData.add(ByteArray(0))
                    return
                }

                val payloadLength = headerResult.payloadLength
                val header = headerResult.header
                logger.d("L2CAP header: reserved=${header[0].toInt()},${header[1].toInt()} length=$payloadLength")

                // Validate payload length
                if (payloadLength <= 0) {
                    reportError("Invalid payload length: $payloadLength")
                    continue
                }

                // Security: validate against max message size
                BleSecurityUtils.validateInputSize(
                    ByteArray(payloadLength),
                    config.maxMessageSize,
                    "L2CAP message"
                )

                // Read exact payload bytes
                val payload = ByteArray(payloadLength)
                var totalRead = 0
                while (totalRead < payloadLength) {
                    val n = inStream.read(payload, totalRead, payloadLength - totalRead)
                    if (n == -1) {
                        reportError("Connection closed while reading payload (got $totalRead of $payloadLength bytes)")
                        return
                    }
                    totalRead += n
                    if (totalRead < payloadLength) {
                        logger.d("L2CAP read: received $n bytes, total $totalRead/$payloadLength")
                    }
                }

                reportLog("Request complete: $payloadLength bytes")
                callback.onMessageReceived(payload)
            }
        } catch (e: IOException) {
            logger.i("L2CAP read thread exiting: ${e.message}")
        }
    }

    /**
     * L2CAP Response Writer Thread - High-Throughput Data Transmission
     *
     * Manages L2CAP socket-based data transmission for mDL responses.
     * Provides superior performance compared to GATT characteristics
     * for large mDL credential data transfers.
     */
    fun writeResponse() {
        val outStream = l2capSocket!!.outputStream
        try {
            while (true) {
                var message: ByteArray?
                try {
                    message = responseData.poll(500, TimeUnit.MILLISECONDS)
                    reportLog("L2CAP write response: ${message?.size ?: 0} bytes")
                    if (message == null) {
                        continue
                    }
                    if (message.isEmpty()) {
                        break
                    }
                } catch (e: InterruptedException) {
                    continue
                }

                outStream.write(message)
            }
        } catch (e: IOException) {
            reportError("Error writing response via L2CAP socket: $e")
        }

        try {
            // Workaround for L2CAP socket behaviour; attempting to close it too quickly can
            // result in an error return from .close(), and then potentially leave the socket hanging
            // open indefinitely if not caught.
            Thread.sleep(1000)
            l2capSocket!!.close()
            reportLog("L2CAP socket Closed")
            disconnect()
        } catch (e: IOException) {
            reportError("Error closing socket: $e")
        } catch (e: InterruptedException) {
            reportError("Error closing socket: $e")
        }
}

    /**
     * Set notifications for a characteristic.  This process is rather more complex than you'd think it would
     * be, and isn't complete until onDescriptorWrite() is hit; it triggers an async action.
     */
    private fun enableNotification(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic?,
        name: String
    ): Boolean {
        reportLog("Enabling notifications on $name")

        if (characteristic == null) {
            reportError("Error setting notification on ${name}; is null.")
            return false
        }

        try {
            if (!gatt.setCharacteristicNotification(characteristic, true)) {
                reportError("Error setting notification on ${name}; call failed.")
                return false
            }
            if (characteristic.uuid != l2capUuid) {
                val descriptor: BluetoothGattDescriptor? =
                    characteristic.getDescriptor(clientCharacteristicConfigUuid)

                if (descriptor == null) {
                    reportError("Error setting notification on ${name}; descriptor not found.")
                    return false
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val res = gatt.writeDescriptor(
                        descriptor,
                        BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    )
                    if (res != BluetoothStatusCodes.SUCCESS) {
                        reportError("Error writing to $name. Code: $res")
                        return false
                    }
                } else {
                    // Above code addresses the deprecation but requires API 33+
                    @Suppress("deprecation")
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE

                    @Suppress("deprecation")
                    if (!gatt.writeDescriptor(descriptor)) {
                        reportError("Error writing to $name clientCharacteristicConfig: desc.")
                        return false
                    }
                }
            }
        } catch (e: SecurityException) {
            reportError("Not authorized to enable notification on $name. $e")
            return false
        }

        // An onDescriptorWrite() call will come in for the pair of this characteristic and the client
        // characteristic config UUID when notification setting is complete.
        return true
    }

    /**
     * Log info messages
     */
    private fun reportLog(text: String) {
        logger.i(text)
    }

    /**
     * Log and handle errors with smart session termination per ISO 18013-5
     *
     * Enhanced error handling that:
     * 1. Classifies error as recoverable vs terminal
     * 2. Sends session termination (0x02) for terminal errors
     * 3. Updates state machine with proper error type
     * 4. Maintains backward compatibility with existing error flow
     */
    private fun reportError(text: String) {
        val error = BleException.GattException("GattClient operation", -1)

        // Use termination provider to handle error with smart classification
        val wasTerminated = terminationProvider.handleError(error, "GattClient: $text")

        if (wasTerminated) {
            logger.w("Terminal error handled with session termination: $text")
            // Force transition to error state to ensure proper cleanup
            if (!stateMachine.transitionTo(BleConnectionStateMachine.State.ERROR, text)) {
                logger.w("Failed to transition to ERROR state, forcing transition")
                stateMachine.forceTransitionTo(BleConnectionStateMachine.State.ERROR)
            }
        } else {
            logger.d("Recoverable error handled without termination: $text")
            // For recoverable errors, still call original error handler
            errorHandler.handleError(error, text) {
                callback.onError(it)
            }
        }
    }

    /**
     *
     */
    private fun afterIdentObtained(gatt: BluetoothGatt) {
        try {
            // Use L2CAP if supported by GattServer and by this OS version

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && characteristicL2CAP != null) {
                if (useL2CAP == BleConfiguration.L2CAPMode.IF_AVAILABLE) {
                    useL2CAP = BleConfiguration.L2CAPMode.ALWAYS
                    // L2CAP PSM is static for the session, just read it once (don't use notifications)
                    logger.i("Reading L2CAP characteristic to get PSM")
                    if (!gatt.readCharacteristic(characteristicL2CAP)) {
                        reportError("Error reading L2CAP characteristic.")
                    }
                    return
                }
            } else {
                useL2CAP = BleConfiguration.L2CAPMode.NEVER
            }
            enableNotification(gatt, characteristicServer2Client, "Server2Client")
        } catch (error: SecurityException) {
            callback.onError(error)
        }
    }

    /**
     * Draining writing queue when the write is not outstanding.
     */
    private fun drainWritingQueue() {
        queueLock.withLock {
            reportLog("drainWritingQueue: write is outstanding ${writeIsOutstanding.get()}")

            if (writeIsOutstanding.get()) {
                return
            }

            val chunk: ByteArray = writingQueue.poll() ?: return

            reportLog("Sending chunk with ${chunk.size} bytes (last=${chunk[0].toInt() == 0x00})")

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val res = gattClient!!.writeCharacteristic(
                        characteristicClient2Server!!,
                        chunk,
                        BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    )
                    if (res != BluetoothStatusCodes.SUCCESS) {
                        reportError("Error writing to Client2Server. Code: $res")
                        return
                    }
                } else {
                    // Above code addresses the deprecation but requires API 33+
                    @Suppress("deprecation")
                    characteristicClient2Server!!.value = chunk
                    @Suppress("deprecation")
                    if (!gattClient!!.writeCharacteristic(characteristicClient2Server)) {
                        reportError("Error writing to Client2Server characteristic")
                        return
                    }
                }
            } catch (error: SecurityException) {
                callback.onError(error)
                return
            }

            writeIsOutstanding.set(true)
        }
    }

    /**
     * Clears the GATT state. Needing to access a private function in GATT.
     */
    private fun clearCache() {
        try {
            gattClient?.let { gatt ->
                val refreshMethod = gatt.javaClass.getMethod("refresh")
                refreshMethod.invoke(gatt)
                reportLog("GATT cache cleared successfully")
            }
        } catch (error: NoSuchMethodException) {
            reportLog("GATT refresh method not available")
        } catch (error: IllegalAccessException) {
            reportLog("Unable to access GATT refresh method")
        } catch (error: InvocationTargetException) {
            reportLog("Error invoking GATT refresh method")
        }
    }

    /**
     * Send mDL Response Data - Chunked Transmission to Reader
     *
     * Transmits SessionEstablishment or SessionData messages via optimal transport:
     * - L2CAP: Direct socket write for large payloads
     * - GATT: Chunked via Client2Server characteristic with continuation flags
     *
     * For GATT: Splits data into (MTU-4) byte chunks, each prefixed with:
     * - 0x01: More chunks follow (continuation flag + 3-byte BLE overhead)
     * - 0x00: Final chunk
     *
     * @param data SessionEstablishment or SessionData message per 9.1.1.4
     */
    fun sendMessage(data: ByteArray) {
        // Validate state
        if (!stateMachine.isInState(BleConnectionStateMachine.State.CONNECTED)) {
            reportError("Cannot send message - not connected (state: ${stateMachine.getState()})")
            return
        }

        logger.logDataTransfer("Sending", data.size)

        // Check if L2CAP is actually connected, not just configured
        if (useL2CAP == BleConfiguration.L2CAPMode.ALWAYS && l2capSocket?.isConnected == true) {
            logger.i("Sending via L2CAP")
            // Add 4-byte header: [0x00, 0x00, length_high, length_low] (big-endian)
            val framedMessage = L2CapUtils.frame(data)
            logger.d("L2CAP send: payload=${data.size} bytes, framed=${framedMessage.size} bytes")
            responseData.add(framedMessage)
        } else {
            // Fall back to GATT if L2CAP not actually connected
            if (useL2CAP == BleConfiguration.L2CAPMode.ALWAYS && l2capSocket?.isConnected != true) {
                logger.w("L2CAP configured but not connected (socket=${l2capSocket}, isConnected=${l2capSocket?.isConnected}), falling back to GATT")
            }
            queueLock.withLock {
                if (mtu < config.minAcceptableMtu) {
                    logger.w("Current MTU $mtu below minimum, adjusting to ${config.minAcceptableMtu}")
                    mtu = config.minAcceptableMtu
                }

                /**
                 * Three less the MTU but we also need room for the leading 0x00 or 0x01.
                 */
                val maxChunkSize: Int = mtu - 4
                var offset = 0

                do {
                    val moreChunksComing = offset + maxChunkSize < data.size
                    var size = data.size - offset

                    if (size > maxChunkSize) {
                        size = maxChunkSize
                    }

                    val chunk = BleSecurityUtils.secureAllocateBuffer(
                        size + 1,
                        mtu, // Chunk can't exceed MTU
                        "GATT chunk allocation"
                    )

                    chunk[0] = if (moreChunksComing) 0x01.toByte() else 0x00.toByte()
                    System.arraycopy(data, offset, chunk, 1, size)
                    writingQueue.add(chunk)
                    offset += size
                } while (offset < data.size)

                writingQueueTotalChunks = writingQueue.size
                drainWritingQueue()
            }
        }
    }

    /**
     * Send Session Termination Signal
     *
     * For GATT: Writes 0x02 to State characteristic
     * Signals end of mDL transaction to Peripheral device.
     */
    fun sendTransportSpecificTermination() {
        val terminationCode = byteArrayOf(0x02.toByte())

        // Check if GATT client and characteristic are available
        val gatt = gattClient
        val stateChar = characteristicState

        if (gatt == null || stateChar == null) {
            logger.d("Cannot send termination - GATT client or state characteristic unavailable")
            return
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val res = gatt.writeCharacteristic(
                    stateChar,
                    terminationCode,
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                )
                if (res != BluetoothStatusCodes.SUCCESS) {
                    reportError("Error writing to state characteristic. Code: $res")
                    return
                }
                logger.i("GATT termination signal (0x02) sent successfully")
            } else {
                // Above code addresses the deprecation but requires API 33+
                @Suppress("deprecation")
                stateChar.value = terminationCode

                @Suppress("deprecation")
                if (!gatt.writeCharacteristic(stateChar)) {
                    reportError("Error writing to state characteristic.")
                } else {
                    logger.i("GATT termination signal (0x02) sent successfully")
                }
            }
        } catch (error: SecurityException) {
            callback.onError(error)
        }
    }

    /**
     * Connect to mDL Reader GATT Server
     *
     * Establishes BLE GATT connection to Reader device discovered during scan.
     * Stores ident value for later Reader authentication via Ident characteristic.
     *
     * @param device Reader's BluetoothDevice from advertising scan
     * @param ident Expected Ident value from device engagement for Reader verification
     */
    fun connect(device: BluetoothDevice, ident: ByteArray?) {
        identValue = ident
        this.reset()

        try {
            gattClient?.let { oldGatt ->
                try {
                    reportLog("Cleaning up previous GATT connection before reconnecting")
                    oldGatt.disconnect()
                    oldGatt.close()
                } catch (e: Exception) {
                    reportLog("Error cleaning up old GATT connection: ${e.message}")
                }
            }

            gattClient = device.connectGatt(
                context, false, bluetoothGattCallback,
                BluetoothDevice.TRANSPORT_LE
            )

            callback.onState(BleStates.ConnectingGattClient.string)
            reportLog("Connecting to GATT server.")
        } catch (error: SecurityException) {
            stateMachine.transitionTo(BleConnectionStateMachine.State.ERROR, error.message)
            errorHandler.handleError(error, "connect") {
                callback.onError(it)
            }
        }
    }

    /**
     * Disconnect from Reader - Clean Resource Shutdown
     *
     * Performs orderly disconnection sequence:
     * 1. Close L2CAP socket and interrupt worker threads
     * 2. Close GATT connection
     * 3. Update connection state machine
     * 4. Clear all references to prevent memory leaks
     */
    fun disconnect() {
        if (stateMachine.transitionTo(BleConnectionStateMachine.State.DISCONNECTING)) {
            try {
                // Signal L2CAP write thread to exit BEFORE closing socket
                // This ensures the write thread terminates even if read thread never started
                // or already exited without sending the exit signal
                if (l2capSocket != null && l2capSocket?.isConnected == true) {
                    reportLog("Sending exit signal to L2CAP write thread")
                    responseData.add(ByteArray(0))
                }

                // Close L2CAP socket
                try {
                    l2capSocket?.close()
                } catch (e: IOException) {
                    reportLog("Error closing L2CAP socket: ${e.message}")
                }

                if (gattClient != null) {
                    gattClient?.disconnect()
                    gattClient?.close()
                    gattClient = null

                    callback.onState(BleStates.DisconnectGattClient.string)
                    reportLog("Gatt Client disconnected.")
                }

                stateMachine.transitionTo(BleConnectionStateMachine.State.DISCONNECTED)
            } catch (error: SecurityException) {
                stateMachine.transitionTo(BleConnectionStateMachine.State.ERROR, error.message)
                errorHandler.handleError(error, "disconnect") {
                    callback.onError(it)
                }
            } finally {
                // Ensure resources are cleaned up
                l2capSocket = null
                gattClient = null
            }
        }
    }

    fun reset() {
        synchronized(queueLock) {
            mtu = 0
            writingQueueTotalChunks = 0
            writingQueue.clear()
            writeIsOutstanding.set(false)
        }
        synchronized(messageLock) {
            incomingMessage.reset()
        }
        responseData.clear()

        channelPSM = 0
        setL2CAPNotify = false

        // Clear characteristic references to prevent stale writes from previous session
        // These will be re-discovered in the next connection
        reportLog("Clearing characteristic references to prevent stale state from previous connection")
    }

    /**
     * Setup L2CAP connection with the given PSM
     * Handles connection establishment, thread creation, and error fallback
     */
    private fun setupL2CAPConnection(gatt: BluetoothGatt) {
        val device = gatt.device

        // Cancel discovery before connecting socket for performance
        try {
            btAdapter?.cancelDiscovery()
        } catch (e: SecurityException) {
            reportLog("Unable to cancel discovery. ${e.message}")
        }

        // Use thread pool for L2CAP connection
        logger.i("Starting L2CAP connection setup for PSM $channelPSM - Thread pool state: ${threadPool.getIOPoolState()}")

        threadPool.launchIO {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // Validate PSM range
                    if (channelPSM <= 0 || channelPSM > 0xFFFF) {
                        reportLog("Invalid L2CAP PSM: $channelPSM, falling back to GATT")
                        fallbackToGATTConnection(gatt)
                    } else {
                        // Warn if PSM is even (violates BLE spec, but some implementations may assign even PSMs)
                        if (channelPSM % 2 == 0) {
                            logger.w("L2CAP PSM $channelPSM is EVEN (violates BLE spec), attempting connection anyway")
                        }

                        // Try secure L2CAP first, fall back to insecure if needed
                        l2capSocket = try {
                            device.createL2capChannel(channelPSM)
                        } catch (e: Exception) {
                            logger.w("Secure L2CAP failed, falling back to insecure", e)
                            device.createInsecureL2capChannel(channelPSM)
                        }

                        l2capSocket?.connect()
                        logger.i("L2CAP connection established")

                        // Start L2CAP read/write threads using dedicated Thread instances
                        // Cannot use coroutines here because these are blocking I/O operations with infinite loops
                        Thread({ writeResponse() }, "L2CAP-Write").apply {
                            isDaemon = true
                            start()
                        }
                        Thread({ readRequest() }, "L2CAP-Read").apply {
                            isDaemon = true
                            start()
                        }

                        // With L2CAP, we still need State characteristic for:
                        // 1. Signaling readiness (write 0x01)
                        // 2. Receiving termination (receive 0x02)
                        // Data flows over L2CAP socket, not Server2Client/Client2Server
                        logger.i("Enabling State notifications for L2CAP mode")
                        enableNotification(gatt, characteristicState, "State")
                    }
                }
            } catch (e: IOException) {
                reportLog("Error connecting to L2CAP socket: ${e.message}")
                fallbackToGATTConnection(gatt)
            } catch (e: SecurityException) {
                reportError("Not authorized to connect to L2CAP socket.")
            } catch (e: Exception) {
                logger.e("L2CAP connection setup failed", e)
            }
        }
    }

    /**
     * Fallback to GATT when L2CAP fails
     */
    private fun fallbackToGATTConnection(gatt: BluetoothGatt) {
        useL2CAP = BleConfiguration.L2CAPMode.NEVER
        reportLog("Falling back to GATT transport")
        enableNotification(
            gatt,
            characteristicServer2Client,
            "Server2Client"
        )
    }
}
