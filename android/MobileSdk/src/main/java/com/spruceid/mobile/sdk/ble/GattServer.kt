package com.spruceid.mobile.sdk.ble


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


/**
 * GATT server responsible for emitting data to the GATT client.
 * 18013-5 section 8.3.3.1.1.4 Table 12.
 */
class GattServer(private var callback: GattServerCallback,
                 private var context: Context,
                 private var bluetoothManager: BluetoothManager,
                 private var serviceUuid: UUID,
                 private var isReaderServer: Boolean = false) {

    // Get the appropriate UUIDs based on server type
    private val characteristicStateUuid = if (isReaderServer) BleConstants.Reader.STATE_UUID else BleConstants.Holder.STATE_UUID
    private val characteristicClient2ServerUuid = if (isReaderServer) BleConstants.Reader.CLIENT_TO_SERVER_UUID else BleConstants.Holder.CLIENT_TO_SERVER_UUID
    private val characteristicServer2ClientUuid = if (isReaderServer) BleConstants.Reader.SERVER_TO_CLIENT_UUID else BleConstants.Holder.SERVER_TO_CLIENT_UUID
    private val characteristicIdentUuid = if (isReaderServer) BleConstants.Reader.IDENT_UUID else null
    private val characteristicL2CAPUuid = if (isReaderServer) BleConstants.Reader.L2CAP_UUID else BleConstants.Holder.L2CAP_UUID

    private var gattServer: BluetoothGattServer? = null
    private var currentConnection: BluetoothDevice? = null

    private var characteristicState: BluetoothGattCharacteristic? = null
    private var characteristicClient2Server: BluetoothGattCharacteristic? = null
    private var characteristicServer2Client: BluetoothGattCharacteristic? = null
    private var characteristicIdent: BluetoothGattCharacteristic? = null
    private var characteristicL2CAP: BluetoothGattCharacteristic? = null

    private var mtu = 0
    private var usingL2CAP = true // L2Cap Enabled by default
    private var writeIsOutstanding = false
    private var writingQueue: Queue<ByteArray> = ArrayDeque()
    private var writingQueueTotalChunks = 0
    private var identValue: ByteArray? = byteArrayOf()
    private var incomingMessage: ByteArrayOutputStream = ByteArrayOutputStream()
    
    // Timing variables for transfer performance measurement
    private var transferStartTime: Long = 0
    private var isReceivingData: Boolean = false
    private var transferMode: String = "GATT" // "GATT" or "L2CAP"
    
    // L2CAP server properties
    private var l2capServerSocket: BluetoothServerSocket? = null
    private var l2capSocket: BluetoothSocket? = null
    private var l2capPSM: Int = 0
    private var l2capAcceptThread: Thread? = null
    private var l2capReadThread: Thread? = null
    private val l2capResponseQueue: BlockingQueue<ByteArray> = LinkedTransferQueue()
    private val L2CAP_BUFFER_SIZE = (1 shl 16) // 64K or 65536 bytes

    private val bluetoothGattServerCallback: BluetoothGattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int , newState: Int) {

            callback.onLog("onConnectionStateChange: ${device.address} $status $newState")

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                callback.onState(BleStates.GattServerConnected.string)
                callback.onLog("Gatt Server is connected.")
            }

            if (newState == BluetoothProfile.STATE_DISCONNECTED
                && currentConnection != null
                && device.address.equals(currentConnection!!.address)) {
                callback.onLog("Device ${currentConnection!!.address} which we're currently connected " +
                        "to, has disconnected")

                currentConnection = null
                callback.onPeerDisconnected()
            }
        }

        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int,
                                                 characteristic: BluetoothGattCharacteristic) {

            callback.onLog("onCharacteristicReadRequest, address=${device.address} requestId=$requestId " +
                    "offset=$offset uuid=${characteristic.uuid}")

            if ((characteristicIdentUuid != null &&
                        characteristic.uuid.equals(characteristicIdentUuid))) {

                Log.d("GattServer.onCharacteristicReadRequest", "Sending value: ${
                    byteArrayToHex(
                        identValue!!
                    )
                }")
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
                }
            } else if ((characteristicL2CAP != null &&
                        characteristic.uuid.equals(characteristicL2CAPUuid))
            ) {
                if (l2capPSM == 0) {
                    callback.onError(Error("L2CAP PSM not yet available"))
                    return
                }

                // Send L2CAP PSM value to client
                val psmBytes = byteArrayOf(
                    (l2capPSM and 0xFF).toByte(),
                    ((l2capPSM shr 8) and 0xFF).toByte()
                )
                
                callback.onLog("Sending L2CAP PSM: $l2capPSM")
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
                }

            } else {
                callback.onError(Error("Read on unexpected characteristic with " +
                        "UUID ${characteristic.uuid}"))
            }
        }

        override fun onCharacteristicWriteRequest(device: BluetoothDevice, requestId: Int,
                                                  characteristic: BluetoothGattCharacteristic,
                                                  preparedWrite: Boolean, responseNeeded: Boolean,
                                                  offset: Int, value: ByteArray) {

            val charUuid = characteristic.uuid

            callback.onLog("onCharacteristicWriteRequest, address=${device.address} " +
                    "uuid=${characteristic.uuid} offset=$offset value=$value")

            /**
             * If we are connected to a device, ignore write from any other device.
             */
            if (currentConnection != null && !device.address.equals(currentConnection!!.address)) {
                callback.onLog("Ignoring characteristic write request from ${device.address} since we're " +
                        "already connected to ${currentConnection!!.address}")
                return
            }

            if (charUuid.equals(characteristicStateUuid) && value.size == 1) {
                if (value[0].toInt() == 0x01) {
                    // Close L2CAP socket if switching back to GATT
                    if (usingL2CAP && l2capSocket != null) {
                        try {
                            l2capSocket?.close()
                            l2capSocket = null
                            l2capReadThread?.interrupt()
                            l2capReadThread = null
                        } catch (e: IOException) {
                            callback.onLog("Error closing L2CAP socket: $e")
                        }
                    }

                    if (currentConnection != null) {
                        callback.onLog("Ignoring connection attempt from ${device.address} since we're " +
                                "already connected to ${currentConnection!!.address}")
                    } else {
                        currentConnection = device

                        callback.onLog("Received connection (state 0x01 on State characteristic) from " +
                                currentConnection!!.address)
                    }

                    callback.onPeerConnected()
                } else if (value[0].toInt() == 0x02) {
                    callback.onTransportSpecificSessionTermination()
                } else {
                    callback.onError(Error("Invalid byte ${value[0]} for state characteristic"))
                }
            } else if (charUuid.equals(characteristicClient2ServerUuid)) {
                if (value.isEmpty()) {
                    callback.onError(Error("Invalid value with length $value"))
                    return
                }

                if (currentConnection == null) {
                    /**
                     * We expect a write 0x01 on the State characteristic before we consider
                     * the device to be connected.
                     */
                    callback.onError(Error("Write on Client2Server but not connected yet"))
                    return
                }

                // Track timing for first chunk
                if (!isReceivingData) {
                    transferStartTime = System.currentTimeMillis()
                    isReceivingData = true
                    transferMode = "GATT"
                    callback.onLog("Starting GATT data transfer at $transferStartTime")
                }
                
                incomingMessage.write(value, 1, value.size - 1)

                callback.onLog("Received chunk with ${value.size} bytes " +
                        "(last=${value[0].toInt() == 0x00}), incomingMessage.length=" +
                        "${incomingMessage.toByteArray().size}")

                if (value[0].toInt() == 0x00) {
                    /**
                     * Last message.
                     */
                    val entireMessage: ByteArray = incomingMessage.toByteArray()
                    
                    // Calculate and log transfer time
                    if (isReceivingData) {
                        val transferTime = System.currentTimeMillis() - transferStartTime
                        val transferRate = if (transferTime > 0) {
                            (entireMessage.size * 1000.0 / transferTime) // bytes per second
                        } else 0.0
                        callback.onLog("GATT transfer completed: ${entireMessage.size} bytes in ${transferTime}ms (${String.format("%.2f", transferRate)} bytes/sec)")
                        isReceivingData = false
                    }

                    incomingMessage.reset()
                    callback.onMessageReceived(entireMessage)
                } else if (value[0].toInt() == 0x01) {
                    if (value.size > mtu - 3) {
                        callback.onError(Error("Invalid size ${value.size} of data written Client2Server " +
                                "characteristic, expected maximum size ${mtu - 3}"))
                        return
                    }
                } else {
                    callback.onError(Error("Invalid first byte ${value[0].toInt()} in Client2Server " +
                            "data chunk, expected 0 or 1"))
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
                callback.onError(Error("Write on unexpected characteristic with UUID " +
                        "${characteristic.uuid}"))
            }
        }

        override fun onDescriptorReadRequest(device: BluetoothDevice, requestId: Int, offset: Int,
                                             descriptor: BluetoothGattDescriptor) {

            callback.onLog("onDescriptorReadRequest, address=${device.address} " +
                    "uuid=${descriptor.characteristic.uuid} offset=$offset")
        }

        override fun onDescriptorWriteRequest(device: BluetoothDevice, requestId: Int,
                                              descriptor: BluetoothGattDescriptor,
                                              preparedWrite: Boolean, responseNeeded: Boolean,
                                              offset: Int, value: ByteArray) {

            callback.onLog("onDescriptorWriteRequest, address=${device.address} " +
                    "uuid=${descriptor.characteristic.uuid} offset=$offset value=$value " +
                    "responseNeeded=$responseNeeded")

            if (responseNeeded) {
                try {
                    gattServer!!.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0,
                        null)
                } catch (error: SecurityException) {
                    callback.onError(error)
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            this@GattServer.set_mtu(mtu)
            callback.onLog("Negotiated MTU changed to $mtu for ${device.address}.")
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            callback.onLog("onNotificationSent, status=$status address=${device.address}")

            if (status != BluetoothGatt.GATT_SUCCESS) {
                callback.onError(Error("Error in onNotificationSent status=$status"))
                return
            }

            if (writingQueueTotalChunks > 0) {
                if (writingQueue.size == 0) {
                    callback.onMessageSendProgress(writingQueueTotalChunks, writingQueueTotalChunks)
                    writingQueueTotalChunks = 0
                } else {
                    callback.onMessageSendProgress(writingQueueTotalChunks - writingQueue.size,
                        writingQueueTotalChunks)
                }
            }

            writeIsOutstanding = false
            drainWritingQueue()
        }
    }

    /**
     * Draining writing queue.
     */
    private fun drainWritingQueue() {
        callback.onLog("drainWritingQueue $writeIsOutstanding")

        if (writeIsOutstanding) {
            return
        }

        val chunk: ByteArray = writingQueue.poll() ?: return

        callback.onLog("Sending chunk with ${chunk.size} bytes (last=${chunk[0].toInt() == 0x00})")
        characteristicServer2Client!!.value = chunk

        try {
            if (!gattServer!!.notifyCharacteristicChanged(
                    currentConnection, characteristicServer2Client, false)) {
                callback.onError(Error("Error calling notifyCharacteristicsChanged on Server2Client"))
                return
            }
        } catch (error: SecurityException) {
            callback.onError(error)
            return
        }

        writeIsOutstanding = true
    }

    fun sendMessage(data: ByteArray) {
        // Use L2CAP if available and connected
        if (usingL2CAP && l2capSocket != null && l2capSocket!!.isConnected) {
            try {
                callback.onLog("Sending message via L2CAP: ${data.size} bytes")
                l2capSocket!!.outputStream.write(data)
                l2capSocket!!.outputStream.flush()
                callback.onMessageSendProgress(1, 1) // Indicate completion
                return
            } catch (e: IOException) {
                callback.onError(Error("L2CAP send failed: ${e.message}, falling back to GATT"))
                usingL2CAP = false
                // Fall through to GATT
            }
        }

        // GATT-based chunked transfer
        if (mtu == 0) {
            callback.onLog("MTU not negotiated, defaulting to 23. Performance will suffer.")
            mtu = 23
        }

        // Three less the MTU but we also need room for the leading 0x00 or 0x01.
        // Message size is three less than MTU, as opcode and attribute handle take up 3 bytes.
        // (mtu - 3) - oneForLeadingByte == mtu - 4
        val maxChunkSize: Int = mtu - 4
        var offset = 0

        do {
            val moreChunksComing = offset + maxChunkSize < data.size
            var size = data.size - offset

            if (size > maxChunkSize) {
                size = maxChunkSize
            }

            val chunk = ByteArray(size + 1)

            chunk[0] = if (moreChunksComing) 0x01.toByte() else 0x00.toByte()

            System.arraycopy(data, offset, chunk, 1, size)
            writingQueue.add(chunk)
            offset += size
        } while (offset < data.size)
        writingQueueTotalChunks = writingQueue.size
        drainWritingQueue()
    }

    /**
     * When using L2CAP it doesn't support characteristics notification.
     */
    fun supportsTransportSpecificTerminationMessage(): Boolean {
        return !usingL2CAP
    }

    /**
     * Send transport-specific termination message
     */
    fun sendTransportSpecificTermination() {
        // L2CAP doesn't use transport-specific termination via GATT
        if (usingL2CAP) {
            callback.onLog("L2CAP doesn't use transport-specific termination, will close after delay")
            // For L2CAP, schedule close after a delay
            Thread {
                Thread.sleep(1000) // 1 second delay to allow data to flush
                closeL2CAP()
            }.start()
            return
        }
        
        // GATT-based termination
        if (currentConnection == null) {
            callback.onLog("No current connection to send termination to")
            return
        }
        
        val terminationCode = byteArrayOf(0x02.toByte())
        characteristicState!!.value = terminationCode

        try {
            if (gattServer != null && !gattServer!!.notifyCharacteristicChanged(currentConnection,
                    characteristicState, false)) {
                callback.onError(Error("Error calling notifyCharacteristicsChanged on State"))
            }
        } catch (error: SecurityException) {
            callback.onError(error)
        }
    }

    /**
     * Primary GATT server setup.
     */
    fun start(ident: ByteArray?) {
        identValue = ident

        this.reset()

        try {
            gattServer = bluetoothManager.openGattServer(context, bluetoothGattServerCallback)
        } catch (error: SecurityException) {
            callback.onError(error)
        }

        if (gattServer == null) {
            callback.onError(Error("GATT server failed to open."))
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
        if (usingL2CAP && characteristicL2CAPUuid != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
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
     * Primary GATT server stop.
     */
    fun stop() {
        try {
            // Close L2CAP connections
            closeL2CAP()
            
            if (currentConnection != null) {
                gattServer?.cancelConnection(currentConnection)
            }

            gattServer?.close()

            writingQueue.clear()

            callback.onState(BleStates.StopGattServer.string)
            callback.onLog("Gatt Server stopped.")
        } catch (error: SecurityException) {
            callback.onError(error)
        }
    }

    fun reset() {
        mtu = 0
        writingQueueTotalChunks = 0
        writingQueue.clear()
        incomingMessage.reset()
        l2capResponseQueue.clear()
        isReceivingData = false
        transferStartTime = 0
    }

    private fun set_mtu(mtu: Int) {
        this.mtu = min(mtu, 515)
    }
    
    /**
     * L2CAP Server Implementation
     */
    private fun setupL2CAPServer() {
        if (!usingL2CAP) {
            callback.onLog("L2CAP disabled by configuration")
            return
        }
        
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            callback.onLog("L2CAP not supported on this Android version")
            return
        }
        
        try {
            val adapter = bluetoothManager.adapter
            if (adapter == null) {
                callback.onError(Error("Bluetooth adapter not available"))
                return
            }
            
            // Create L2CAP server socket
            l2capServerSocket = adapter.listenUsingInsecureL2capChannel()
            l2capPSM = l2capServerSocket!!.psm
            
            callback.onLog("L2CAP server started with PSM: $l2capPSM")
            
            // Set PSM value in characteristic
            // Encode the 16-bit L2CAP PSM as a 2-byte little-endian array, per Bluetooth Core Spec
            // Byte 0: Least significant byte (LSB) = PSM & 0xFF
            // Byte 1: Most significant byte (MSB) = (PSM >> 8) & 0xFF
            // Ex: 0x1234 -> [0x34, 0x12]
            characteristicL2CAP?.value = byteArrayOf(
                (l2capPSM and 0xFF).toByte(),
                ((l2capPSM shr 8) and 0xFF).toByte()
            )
            
            // Start accepting connections in background thread
            l2capAcceptThread = Thread {
                acceptL2CAPConnection()
            }
            l2capAcceptThread?.start()
            
        } catch (e: IOException) {
            callback.onError(Error("Failed to create L2CAP server: ${e.message}"))
            usingL2CAP = false
        } catch (e: SecurityException) {
            callback.onError(Error("Not authorized to create L2CAP server: ${e.message}"))
            usingL2CAP = false
        }
    }
    
    /**
     * Accept L2CAP connections
     */
    private fun acceptL2CAPConnection() {
        try {
            callback.onLog("Waiting for L2CAP connection on PSM $l2capPSM")
            
            // Accept connection (blocking call, waits until a connection is established)
            l2capSocket = l2capServerSocket?.accept()
            
            if (l2capSocket != null) {
                callback.onLog("L2CAP connection established")

                // Start read thread for incoming data
                l2capReadThread = Thread {
                    readL2CAPData()
                }
                l2capReadThread?.start()
                
                // Notify that we're using L2CAP
                callback.onState("L2CAP Connected")
                
                // Trigger onPeerConnected so the application layer can send initial data
                callback.onPeerConnected()
            }
        } catch (e: IOException) {
            if (!Thread.currentThread().isInterrupted) {
                callback.onError(Error("L2CAP accept failed: ${e.message}"))
            }
        }
    }
    
    /**
     * Read data from L2CAP socket
     */
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
                    val bytesRead = inputStream.read(buffer, 0, minOf(buffer.size, inputStream.available()))

                    // returns -1 if there is no more data because the end of the stream has been reached.
                    if (bytesRead == -1) {
                        callback.onLog("L2CAP connection closed by peer")
                        break
                    }
                    
                    if (bytesRead > 0) {
                        // Track timing for first data
                        if (!l2capDataStarted) {
                            l2capTransferStartTime = System.currentTimeMillis()
                            firstDataTime = l2capTransferStartTime
                            l2capDataStarted = true
                            transferMode = "L2CAP"
                            callback.onLog("Starting L2CAP data transfer at $l2capTransferStartTime")
                        }
                        
                        messageBuffer.write(buffer, 0, bytesRead)
                        lastDataTime = System.currentTimeMillis()
                        callback.onLog("L2CAP received chunk: $bytesRead bytes, total: ${messageBuffer.size()} bytes")
                    }
                } else {
                    // Check if we have data and enough time has passed since last data
                    if (messageBuffer.size() > 0) {
                        val timeSinceLastData = System.currentTimeMillis() - lastDataTime
                        if (timeSinceLastData > 500) { // 500ms timeout
                            // Complete message received
                            val message = messageBuffer.toByteArray()
                            
                            // Calculate and log transfer time (excluding the 500ms timeout)
                            if (l2capDataStarted) {
                                // Transfer time is from start to last data received (not including the 500ms wait)
                                val transferTime = lastDataTime - l2capTransferStartTime
                                val transferRate = if (transferTime > 0) {
                                    (message.size * 1000.0 / transferTime) // bytes per second
                                } else 0.0
                                callback.onLog("L2CAP transfer completed: ${message.size} bytes in ${transferTime}ms (${String.format("%.2f", transferRate)} bytes/sec)")
                                l2capDataStarted = false
                            }
                            
                            callback.onLog("L2CAP message complete: ${message.size} bytes")
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
            callback.onLog("L2CAP read thread interrupted")
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
                    callback.onLog("L2CAP transfer completed (final): ${message.size} bytes in ${transferTime}ms (${String.format("%.2f", transferRate)} bytes/sec)")
                }
                
                callback.onLog("L2CAP final message: ${message.size} bytes")
                callback.onMessageReceived(message)
            }
            callback.onLog("L2CAP read thread ending")
        }
    }
    
    /**
     * Close L2CAP connections
     */
    private fun closeL2CAP() {
        try {
            callback.onLog("Closing L2CAP connections...")
            
            // Interrupt threads gracefully
            l2capReadThread?.interrupt()
            l2capAcceptThread?.interrupt()
            
            // Give threads time to finish
            try {
                l2capReadThread?.join(100)
                l2capAcceptThread?.join(100)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            
            // Close sockets
            try {
                l2capSocket?.close()
            } catch (e: IOException) {
                callback.onLog("Error closing L2CAP socket: ${e.message}")
            }
            
            try {
                l2capServerSocket?.close()
            } catch (e: IOException) {
                callback.onLog("Error closing L2CAP server socket: ${e.message}")
            }
            
            // Clear references
            l2capSocket = null
            l2capServerSocket = null
            l2capAcceptThread = null
            l2capReadThread = null
            usingL2CAP = false
            
            callback.onLog("L2CAP connections closed")
        } catch (e: Exception) {
            callback.onLog("Error during L2CAP cleanup: ${e.message}")
        }
    }
}
