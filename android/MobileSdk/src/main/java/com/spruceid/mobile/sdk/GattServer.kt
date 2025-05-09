package com.spruceid.mobile.sdk


import android.bluetooth.*
import android.content.Context
import android.util.Log
import java.io.ByteArrayOutputStream
import java.util.*
import kotlin.math.min


/**
 * GATT server responsible for emitting data to the GATT client.
 * 18013-5 section 8.3.3.1.1.4 Table 12.
 */
class GattServer(private var callback: GattServerCallback,
                 private var context: Context,
                 private var bluetoothManager: BluetoothManager,
                 private var serviceUuid: UUID,
                 private var characteristicStateUuid: UUID,
                 private var characteristicClient2ServerUuid: UUID,
                 private var characteristicServer2ClientUuid: UUID,
                 private var characteristicIdentUuid: UUID?,
                 private var characteristicL2CAPUuid: UUID?) {

    private var clientCharacteristicConfigUuid =
        UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private var gattServer: BluetoothGattServer? = null
    private var currentConnection: BluetoothDevice? = null

    private var characteristicState: BluetoothGattCharacteristic? = null
    private var characteristicClient2Server: BluetoothGattCharacteristic? = null
    private var characteristicServer2Client: BluetoothGattCharacteristic? = null
    private var characteristicIdent: BluetoothGattCharacteristic? = null
    private var characteristicL2CAP: BluetoothGattCharacteristic? = null

    private var mtu = 0
    private var usingL2CAP = false
    private var writeIsOutstanding = false
    private var writingQueue: Queue<ByteArray> = ArrayDeque()
    private var writingQueueTotalChunks = 0
    private var identValue: ByteArray? = byteArrayOf()
    private var incomingMessage: ByteArrayOutputStream = ByteArrayOutputStream()

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

                Log.d("GattServer.onCharacteristicReadRequest", "Sending value: ${byteArrayToHex(identValue!!)}")
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
                if (!usingL2CAP) {
                    callback.onError(Error("Unexpected read request for L2CAP characteristic, not supported"))
                    return
                }

                /**
                 * Placeholder for L2CAP.
                 */

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

                    /**
                     * Placeholder to close the socket connection by L2CAP.
                     */

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

                incomingMessage.write(value, 1, value.size - 1)

                callback.onLog("Received chunk with ${value.size} bytes " +
                        "(last=${value[0].toInt() == 0x00}), incomingMessage.length=" +
                        "${incomingMessage.toByteArray().size}")

                if (value[0].toInt() == 0x00) {
                    /**
                     * Last message.
                     */
                    val entireMessage: ByteArray = incomingMessage.toByteArray()

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
                        "$characteristic.uuid}"))
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
        /**
         * L2CAP placeholder - when server is implemented send message via socket instead.
         */

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
     *
     */
    fun sendTransportSpecificTermination() {
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
            clientCharacteristicConfigUuid,
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
            clientCharacteristicConfigUuid,
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
         * L2CAP placeholder.
         */

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
    }

    private fun set_mtu(mtu: Int) {
        this.mtu = min(mtu, 515)
    }
}