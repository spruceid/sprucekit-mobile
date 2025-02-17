package com.spruceid.mobile.sdk

import android.bluetooth.BluetoothAdapter
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
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.util.ArrayDeque
import java.util.Arrays
import java.util.Queue
import java.util.UUID
import java.util.concurrent.BlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.LinkedTransferQueue
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * GATT client responsible for consuming data sent from a GATT server.
 * 18013-5 section 8.3.3.1.1.4 Table 11.
 */
class GattClient(
    private var callback: GattClientCallback,
    private var context: Context,
    private var btAdapter: BluetoothAdapter?,
    private var serviceUuid: UUID,
    private var characteristicStateUuid: UUID,
    private var characteristicClient2ServerUuid: UUID,
    private var characteristicServer2ClientUuid: UUID,
    private var characteristicIdentUuid: UUID?,
    private var characteristicL2CAPUuid: UUID?
) {

    private val clientCharacteristicConfigUuid =
        UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private val L2CAP_BUFFER_SIZE = (1 shl 16) // 64K

    enum class UseL2CAP { IfAvailable, Yes, No }

    private var useL2CAP = UseL2CAP.IfAvailable

    var gattClient: BluetoothGatt? = null

    var characteristicState: BluetoothGattCharacteristic? = null
    var characteristicClient2Server: BluetoothGattCharacteristic? = null
    var characteristicServer2Client: BluetoothGattCharacteristic? = null
    var characteristicIdent: BluetoothGattCharacteristic? = null
    var characteristicL2CAP: BluetoothGattCharacteristic? = null

    private var mtu = 0
    private var identValue: ByteArray? = byteArrayOf()
    private var writeIsOutstanding = false
    private var writingQueue: Queue<ByteArray> = ArrayDeque()
    private var writingQueueTotalChunks = 0
    private var setL2CAPNotify = false
    private var channelPSM = 0
    private var l2capSocket: BluetoothSocket? = null
    private var l2capWriteThread: Thread? = null
    private var incomingMessage: ByteArrayOutputStream = ByteArrayOutputStream()
    private val responseData: BlockingQueue<ByteArray> = LinkedTransferQueue()
    private var requestTimestamp = TimeSource.Monotonic.markNow()

    /**
     * Bluetooth GATT callback containing all of the events.
     */
    private val bluetoothGattCallback: BluetoothGattCallback = object : BluetoothGattCallback() {
        /**
         * Discover services to connect to.
         */
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            reportLog("onConnectionStateChange")

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                clearCache()

                callback.onState(BleStates.GattClientConnected.string)
                reportLog("Gatt Client is connected.")

                try {
                    gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    gatt.discoverServices()
                } catch (error: SecurityException) {
                    callback.onError(error)
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                callback.onPeerDisconnected()
                reportLog("GATT Server disconnected.")
            }
        }

        /**
         * Validating services characteristics and setting MTU limit. Assuming maximum payload of 515
         * bytes, adjusting for the Bluetooth Core specification Part F section 3.2.9 and
         * 18013-5 section 8.3.3.1.1.6.
         */
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            reportLog("onServicesDiscovered")

            if (status == BluetoothGatt.GATT_SUCCESS) {
                try {
                    Log.d("GattClient.onServicesDiscovered", "uuid: $serviceUuid, gatt: $gatt")
                    val service: BluetoothGattService = gatt.getService(serviceUuid)

                    for (gattService in service.characteristics) {
                        Log.d(
                            "GattClient.onServicesDiscovered",
                            "gattServiceUUID: ${gattService.uuid}"
                        )
                    }

                    if (characteristicL2CAPUuid != null) {
                        characteristicL2CAP = service.getCharacteristic(characteristicL2CAPUuid)

                        // We don't check if the characteristic is null here because using it is optional;
                        // we'll decide later if we want to use it based on OS version and whether the
                        // characteristic actually resolved to something.
                    }

                    characteristicState = service.getCharacteristic(characteristicStateUuid)
                    if (characteristicState == null) {
                        reportError("State characteristic not found.")
                        return
                    }

                    characteristicClient2Server =
                        service.getCharacteristic(characteristicClient2ServerUuid)
                    if (characteristicClient2Server == null) {
                        reportError("Client2Server characteristic not found.")
                        return
                    }

                    characteristicServer2Client =
                        service.getCharacteristic(characteristicServer2ClientUuid)
                    if (characteristicServer2Client == null) {
                        reportError("Server2Client characteristic not found.")
                        return
                    }

                    if (characteristicIdentUuid != null) {
                        characteristicIdent = service.getCharacteristic(characteristicIdentUuid)
                        if (characteristicIdent == null) {
                            reportError("Ident characteristic not found.")
                            return
                        }
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
                    return
                }

                gattClient = gatt
            }
        }

        /**
         * Detecting the MTU limit adjustment.
         */
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            reportLog("onMtuChanged")

            this@GattClient.set_mtu(mtu)

            if (status != BluetoothGatt.GATT_SUCCESS) {
                reportError("Error changing MTU, status: $status.")
                return
            }

            reportLog("Negotiated MTU changed to $mtu.")

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
            //@Suppress("deprecation")
            /**
             * 18013-5 section 8.3.3.1.1.3.
             */
            if (characteristic.uuid.equals(characteristicIdentUuid)) {
                reportLog("Received identValue: ${byteArrayToHex(value)}.")

                if (!Arrays.equals(value, identValue)) {
                    reportLog("Warning: Received ident does not match expected ident.")
                }

                afterIdentObtained(gatt)
            } else if (characteristic.uuid.equals(characteristicL2CAPUuid)) {
                Log.d(
                    "[GattClient]",
                    "L2CAP read! '${value.size}' ${status == BluetoothGatt.GATT_SUCCESS}"
                )
                if (value.size == 2) {
                    // This doesn't appear to happen in practice; we get the data back in
                    // onCharacteristicChanged() instead.
                    dprint("L2CAP channel PSM read via onCharacteristicRead()")
                    //gatt.readCharacteristic(characteristicL2CAP)
                }
            } else {
                reportError(
                    "Unexpected onCharacteristicRead for characteristic " +
                            "${characteristic.uuid} expected $characteristicIdentUuid."
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

                if (charUuid.equals(characteristicServer2ClientUuid)
                    && descriptor.uuid.equals(clientCharacteristicConfigUuid)
                ) {
                    enableNotification(gatt, characteristicState, "State")
                } else if (charUuid.equals(characteristicStateUuid)
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
                } else if (charUuid.equals(characteristicL2CAPUuid)) {
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
         * Observe state fully connected or messages writing progress.
         */
        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic, status: Int
        ) {

            val charUuid = characteristic.uuid

            reportLog("onCharacteristicWrite, status=$status uuid=$charUuid")

            if (charUuid.equals(characteristicStateUuid)) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    reportError("Unexpected status for writing to State, status=$status.")
                    return
                }

                callback.onPeerConnected()

            } else if (charUuid.equals(characteristicClient2ServerUuid)) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    reportError("Unexpected status for writing to Client2Server, status=$status.")
                    return
                }

                if (writingQueueTotalChunks > 0) {
                    if (writingQueue.size == 0) {
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

                writeIsOutstanding = false
                drainWritingQueue()
            }
        }

        /**
         * Detect characteristic change and inspect incoming message.
         */
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {

            reportLog("onCharacteristicChanged, uuid=${characteristic.uuid}")
            @Suppress("deprecation")
            val value = characteristic.value

            when (characteristic.uuid) {

                characteristicServer2ClientUuid -> {
                    if (value.isEmpty()) {
                        reportError("Invalid data length ${value.size} for Server2Client characteristic.")
                        return
                    }

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

                        incomingMessage.reset()
                        callback.onMessageReceived(entireMessage)
                    } else if (value[0].toInt() == 0x01) {
                        // Message size is three less than MTU, as opcode and attribute handle take up 3 bytes.
                        if (value.size > mtu - 3) {
                            reportError(
                                "Invalid size ${value.size} of data written Server2Client " +
                                        "characteristic, expected maximum size ${mtu - 3}."
                            )
                            return
                        }
                    } else {
                        reportError("Invalid first byte ${value[0]} in Server2Client data chunk, expected 0 or 1.")
                        return
                    }
                }

                characteristicStateUuid -> {
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

                characteristicL2CAPUuid -> {
                    if (value.size == 2) {
                        if (channelPSM == 0) {
                            channelPSM =
                                (((value[1].toULong() and 0xFFu) shl 8) or (value[0].toULong() and 0xFFu)).toInt()
                            reportLog("L2CAP Channel: ${channelPSM}")

                            val device = gatt.getDevice()

                            // The android docs recommend cancelling discovery before connecting a socket for
                            // perfomance reasons.

                            try {
                                btAdapter?.cancelDiscovery()
                            } catch (e: SecurityException) {
                                reportLog("Unable to cancel discovery.")
                            }

                            val connectThread: Thread = object : Thread() {
                                override fun run() {
                                    try {
                                        // createL2capChannel() requires/initiates pairing, so we have to use
                                        // the "insecure" version.  This requires at least API 29, which we did
                                        // check elsewhere (we'd never have got this far on a lower API), but
                                        // the linter isn't smart enough to know that, and we have PR merging
                                        // gated on a clean bill of health from the linter...
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                            l2capSocket =
                                                device.createInsecureL2capChannel(channelPSM)
                                            l2capSocket?.connect()
                                        }
                                    } catch (e: IOException) {
                                        reportError("Error connecting to L2CAP socket: ${e.message}")

                                        // Something went wrong.  Fall back to the old flow, don't try L2CAP
                                        // again for this run.
                                        useL2CAP = UseL2CAP.No
                                        enableNotification(
                                            gatt,
                                            characteristicServer2Client,
                                            "Server2Client"
                                        )

                                        return
                                    } catch (e: SecurityException) {
                                        reportError("Not authorized to connect to L2CAP socket.")
                                        return
                                    }

                                    l2capWriteThread = Thread { writeResponse() }
                                    l2capWriteThread!!.start()

                                    // Let the app know we're connected.
                                    //reportPeerConnected()

                                    // Reuse this thread for reading
                                    readRequest()
                                }
                            }
                            connectThread.start()
                        }
                    }
                }

                else -> {
                    reportLog("Unknown Changed: ${value.size}")
                }
            }
        }
    }

    /**
     * Thread for reading the request via L2CAP.
     */
    private fun readRequest() {
        val payload = ByteArrayOutputStream()

        // Keep listening to the InputStream until an exception occurs.
        val inStream = try {
            l2capSocket!!.inputStream
        } catch (e: IOException) {
            reportError("Error on listening input stream from socket L2CAP: ${e}")
            return
        }

        while (true) {
            val buf = ByteArray(L2CAP_BUFFER_SIZE)
            try {
                val numBytesRead = inStream.read(buf)
                if (numBytesRead == -1) {
                    reportError("Failure reading request, peer disconnected.")
                    return
                }
                payload.write(buf, 0, buf.count())

                dprint("Currently have ${buf.count()} bytes.")

                // We are receiving this data over a stream socket and do not know how large the
                // message is; there is no framing information provided, the only way we have to
                // know whether we have the full message is whether any more data comes in after.
                // To determine this, we take a timestamp, and schedule an event for half a second
                // later; if nothing has come in the interim, we assume that to be the full
                // message.
                //
                // Technically, we could also attempt to decode the message (it's CBOR-encoded)
                // to see if it decodes properly.  Unfortunately, this is potentially subject to
                // false positives; CBOR has several primitives which have unbounded length. For
                // messages unsing those primitives, the message length is inferred from the
                // source data length, so if the (incomplete) message end happened to fall on a
                // primitive boundary (which is quite likely if a higher MTU isn't negotiated) an
                // incomplete message could "cleanly" decode.

                requestTimestamp = TimeSource.Monotonic.markNow()

                Executors.newSingleThreadScheduledExecutor()
                    .schedule({
                        val curtime = TimeSource.Monotonic.markNow()
                        if ((curtime - requestTimestamp) > 500.milliseconds) {
                            val message = payload.toByteArray()

                            reportLog("Request complete: ${message.count()} bytes.")
                            callback.onMessageReceived(message)
                        }
                    }, 500, TimeUnit.MILLISECONDS)

            } catch (e: IOException) {
                reportError("Error on listening input stream from socket L2CAP: ${e}")
                return
            }
        }
    }

    /**
     * Thread for writing the response via L2CAP.
     */
    fun writeResponse() {
        val outStream = l2capSocket!!.outputStream
        try {
            while (true) {
                var message: ByteArray?
                try {
                    message = responseData.poll(500, TimeUnit.MILLISECONDS)
                    reportLog("????? ${message}")
                    if (message == null) {
                        continue
                    }
                    if (message.size == 0) {
                        break
                    }
                } catch (e: InterruptedException) {
                    continue
                }
                outStream.write(message)
                break
            }
        } catch (e: IOException) {
            reportError("Error writing response via L2CAP socket: ${e}")
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
            reportError("Error closing socket: ${e}")
        } catch (e: InterruptedException) {
            reportError("Error closing socket: ${e}")
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
    ) {
        reportLog("Enabling notifications on ${name}")

        if (characteristic == null) {
            reportError("Error setting notification on ${name}; is null.")
            return
        }

        try {
            if (!gatt.setCharacteristicNotification(characteristic, true)) {
                reportError("Error setting notification on ${name}; call failed.")
                return
            }

            val descriptor: BluetoothGattDescriptor =
                characteristic.getDescriptor(clientCharacteristicConfigUuid)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val res = gatt.writeDescriptor(
                    descriptor,
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                )
                if (res != BluetoothStatusCodes.SUCCESS) {
                    reportError("Error writing to ${name}. Code: $res")
                    return
                }
            } else {
                // Above code addresses the deprecation but requires API 33+
                @Suppress("deprecation")
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE

                @Suppress("deprecation")
                if (!gatt.writeDescriptor(descriptor)) {
                    reportError("Error writing to ${name} clientCharacteristicConfig: desc.")
                    return
                }
            }
        } catch (e: SecurityException) {
            reportError("Not authorized to enable notification on ${name}")
        }

        // An onDescriptorWrite() call will come in for the pair of this characteristic and the client
        // characteristic config UUID when notification setting is complete.
    }

    /**
     * Log stuff to the console without hitting the callback.
     */
    private fun dprint(text: String) {
        Log.d("[GattClient]", text)
    }

    /**
     * Log stuff both to the callback and to the console.
     */
    private fun reportLog(text: String) {
        Log.d("[GattClient]", "${text}")

        //callback.onLog(text) // Appears to mess with transfer timing, disabled for now.
    }

    /**
     * Log an error both to the callback and the console.
     */
    private fun reportError(text: String) {
        Log.e("[GattClient]", "ERROR: ${text}")
        callback.onError(Error(text))
    }

    /**
     *
     */
    private fun afterIdentObtained(gatt: BluetoothGatt) {
        try {
            // Use L2CAP if supported by GattServer and by this OS version

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && characteristicL2CAP != null) {
                if (useL2CAP == UseL2CAP.IfAvailable) {
                    useL2CAP = UseL2CAP.Yes
                }
            } else {
                useL2CAP = UseL2CAP.No
            }

            if (useL2CAP == UseL2CAP.Yes) {
                enableNotification(gatt, characteristicL2CAP, "L2CAP")

                reportLog("Using L2CAP: $useL2CAP")

                //// value is returned async above in onCharacteristicRead()

                return
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
        reportLog("drainWritingQueue: write is outstanding $writeIsOutstanding")

        if (writeIsOutstanding) {
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

        writeIsOutstanding = true
    }

    /**
     * Clears the GATT state. Needing to access a private function in GATT.
     */
    private fun clearCache() {
        try {
        } catch (error: NoSuchMethodException) {
            callback.onError(error)
        } catch (error: IllegalAccessException) {
            callback.onError(error)
        } catch (error: InvocationTargetException) {
            callback.onError(error)
        }
    }

    /**
     * Sends a message to the other device - can be any payload that is converted into
     * a byte array.
     */
    fun sendMessage(data: ByteArray) {
        reportLog("Sending message: $data; using L2CAP = $useL2CAP")
        if (useL2CAP == UseL2CAP.Yes) {
            responseData.add(data)
        } else {

            if (mtu == 0) {
                reportLog("MTU not negotiated, defaulting to 23. Performance will suffer.")
                mtu = 23
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

                val chunk = ByteArray(size + 1)

                chunk[0] = if (moreChunksComing) 0x01.toByte() else 0x00.toByte()
                System.arraycopy(data, offset, chunk, 1, size)
                writingQueue.add(chunk)
                offset += size
            } while (offset < data.size)

            writingQueueTotalChunks = writingQueue.size
            drainWritingQueue()
        }
    }

    /**
     * When using L2CAP it doesn't support characteristics notification.
     */
    fun supportsTransportSpecificTerminationMessage(): Boolean {
        return useL2CAP != UseL2CAP.Yes
    }

    /**
     * Sends termination message to the other device to terminate session
     * and disconnect.
     */
    fun sendTransportSpecificTermination() {
        val terminationCode = byteArrayOf(0x02.toByte())
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val res = gattClient!!.writeCharacteristic(
                    characteristicState!!,
                    terminationCode,
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                )
                if (res != BluetoothStatusCodes.SUCCESS) {
                    reportError("Error writing to state characteristic. Code: $res")
                    return
                }
            } else {
                // Above code addresses the deprecation but requires API 33+
                if (characteristicState != null) {
                    @Suppress("deprecation")
                    characteristicState!!.value = terminationCode
                }

                @Suppress("deprecation")
                if (gattClient != null && !gattClient!!.writeCharacteristic(characteristicState)) {
                    reportError("Error writing to state characteristic.")
                }
            }
        } catch (error: SecurityException) {
            callback.onError(error)
        }
    }

    /**
     * Primary GATT connection setup.
     */
    fun connect(device: BluetoothDevice, ident: ByteArray?) {
        identValue = ident

        this.reset()

        try {
            gattClient = device.connectGatt(
                context, false, bluetoothGattCallback,
                BluetoothDevice.TRANSPORT_LE
            )

            callback.onState(BleStates.ConnectingGattClient.string)
            reportLog("Connecting to GATT server.")
        } catch (error: SecurityException) {
            callback.onError(error)
        }
    }

    /**
     * Primary GATT disconnect setup.
     */
    fun disconnect() {
        try {
            if (gattClient != null) {
                gattClient?.close()
                gattClient?.disconnect()
                gattClient = null

                callback.onState(BleStates.DisconnectGattClient.string)
                reportLog("Gatt Client disconnected.")
            }
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

    fun set_mtu(mtu: Int) {
        this.mtu = min(515, mtu)
    }
}
