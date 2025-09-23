package com.spruceid.mobile.sdk.ble

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

/**
 * Message framing for L2CAP and GATT communication per ISO 18013-5
 *
 * Implements proper message boundary detection for BLE transport:
 * - L2CAP: 4-byte length prefix for stream-based communication
 * - GATT: Continuation flag-based chunking (0x01=more, 0x00=final)
 * - CBOR validation for message completion detection
 *
 * Replaces unreliable timing-based heuristics with deterministic framing.
 */
class BleMessageFramer(
    private val config: BleConfiguration = BleConfiguration(),
    private val logger: BleLogger = BleLogger.getInstance("BleMessageFramer", config)
) {

    /**
     * Message assembly state for each connection
     */
    private data class AssemblyState(
        val buffer: ByteArrayOutputStream = ByteArrayOutputStream(),
        var expectedLength: Int? = null,
        var startTime: Long = System.currentTimeMillis()
    )

    private val assemblyStates = ConcurrentHashMap<String, AssemblyState>()

    companion object {
        // L2CAP framing constants
        const val L2CAP_LENGTH_PREFIX_SIZE = 4
        const val MAX_MESSAGE_SIZE = 65536 // 64KB max message size

        // GATT framing constants
        const val GATT_CONTINUATION_FLAG_MORE = 0x01.toByte()
        const val GATT_CONTINUATION_FLAG_FINAL = 0x00.toByte()

        // CBOR major types for validation
        const val CBOR_MAJOR_TYPE_UINT = 0
        const val CBOR_MAJOR_TYPE_NEGATIVE = 1
        const val CBOR_MAJOR_TYPE_BYTES = 2
        const val CBOR_MAJOR_TYPE_TEXT = 3
        const val CBOR_MAJOR_TYPE_ARRAY = 4
        const val CBOR_MAJOR_TYPE_MAP = 5
        const val CBOR_MAJOR_TYPE_TAG = 6
        const val CBOR_MAJOR_TYPE_SIMPLE = 7
    }

    /**
     * Frame a message for L2CAP transport with 4-byte length prefix
     */
    fun frameForL2CAP(data: ByteArray): ByteArray {
        if (data.size > MAX_MESSAGE_SIZE) {
            throw IllegalArgumentException("Message size ${data.size} exceeds maximum $MAX_MESSAGE_SIZE")
        }

        val framedMessage = ByteArrayOutputStream(data.size + L2CAP_LENGTH_PREFIX_SIZE)

        // Add 4-byte length prefix in little-endian format (Bluetooth standard)
        val lengthBuffer = ByteBuffer.allocate(L2CAP_LENGTH_PREFIX_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(data.size)
            .array()

        framedMessage.write(lengthBuffer)
        framedMessage.write(data)

        logger.d("Framed L2CAP message: ${data.size} bytes -> ${framedMessage.size()} bytes")
        return framedMessage.toByteArray()
    }

    /**
     * Process incoming L2CAP data and extract complete messages
     *
     * @param connectionId Unique identifier for this connection
     * @param data Incoming data chunk
     * @return List of complete messages (empty if no complete messages yet)
     */
    fun processL2CAPData(connectionId: String, data: ByteArray): List<ByteArray> {
        val state = assemblyStates.getOrPut(connectionId) { AssemblyState() }
        val completedMessages = mutableListOf<ByteArray>()

        synchronized(state) {
            state.buffer.write(data)
            logger.d("L2CAP buffer for $connectionId now has ${state.buffer.size()} bytes")

            // Process all complete messages in buffer
            while (true) {
                // Need at least length prefix to proceed
                if (state.buffer.size() < L2CAP_LENGTH_PREFIX_SIZE) {
                    break
                }

                val bufferBytes = state.buffer.toByteArray()

                // Extract expected message length if not known
                if (state.expectedLength == null) {
                    val lengthBuffer = ByteBuffer.wrap(bufferBytes, 0, L2CAP_LENGTH_PREFIX_SIZE)
                        .order(ByteOrder.LITTLE_ENDIAN)
                    state.expectedLength = lengthBuffer.getInt()

                    // Validate message length
                    if (state.expectedLength!! <= 0 || state.expectedLength!! > MAX_MESSAGE_SIZE) {
                        logger.e("Invalid L2CAP message length: ${state.expectedLength}")
                        // Reset state on invalid length
                        state.buffer.reset()
                        state.expectedLength = null
                        break
                    }

                    logger.d("Expecting L2CAP message of ${state.expectedLength} bytes")
                }

                val totalNeeded = L2CAP_LENGTH_PREFIX_SIZE + state.expectedLength!!

                // Check if we have complete message
                if (bufferBytes.size >= totalNeeded) {
                    // Extract complete message (without length prefix)
                    val message = bufferBytes.sliceArray(
                        L2CAP_LENGTH_PREFIX_SIZE until totalNeeded
                    )

                    // Validate CBOR if enabled
                    if (config.validateCborMessages) {
                        if (!isValidCBOR(message)) {
                            logger.w("Received invalid CBOR message, may be corrupted")
                        }
                    }

                    completedMessages.add(message)
                    logger.i("Extracted complete L2CAP message: ${message.size} bytes")

                    // Remove processed message from buffer
                    val remaining = bufferBytes.sliceArray(totalNeeded until bufferBytes.size)
                    state.buffer.reset()
                    state.buffer.write(remaining)
                    state.expectedLength = null

                    // Update timestamp
                    state.startTime = System.currentTimeMillis()
                } else {
                    // Not enough data yet
                    logger.d("Need $totalNeeded bytes, have ${bufferBytes.size}")
                    break
                }
            }

            // Check for timeout
            val elapsed = System.currentTimeMillis() - state.startTime
            if (elapsed > config.messageTimeoutMs && state.buffer.size() > 0) {
                logger.w("L2CAP message assembly timeout for $connectionId after ${elapsed}ms")
                // Don't clear buffer - might still receive rest of message
            }
        }

        return completedMessages
    }

    /**
     * Frame a message for GATT transport with chunking
     *
     * @param data Message to send
     * @param mtu Maximum transmission unit
     * @return List of chunks with continuation flags
     */
    fun frameForGATT(data: ByteArray, mtu: Int): List<ByteArray> {
        if (mtu < 4) {
            throw IllegalArgumentException("MTU too small: $mtu (minimum 4)")
        }

        val chunks = mutableListOf<ByteArray>()
        val maxChunkDataSize = mtu - 4 // Reserve space for continuation flag and BLE overhead

        var offset = 0
        while (offset < data.size) {
            val remainingBytes = data.size - offset
            val chunkSize = min(maxChunkDataSize, remainingBytes)
            val isLastChunk = (offset + chunkSize) >= data.size

            // Create chunk with continuation flag
            val chunk = ByteArray(chunkSize + 1)
            chunk[0] = if (isLastChunk) GATT_CONTINUATION_FLAG_FINAL else GATT_CONTINUATION_FLAG_MORE

            // Copy data
            System.arraycopy(data, offset, chunk, 1, chunkSize)

            chunks.add(chunk)
            offset += chunkSize
        }

        logger.d("Framed GATT message: ${data.size} bytes -> ${chunks.size} chunks (MTU=$mtu)")
        return chunks
    }

    /**
     * Process incoming GATT data chunk with continuation flag
     *
     * @param connectionId Unique identifier for this connection
     * @param chunk Data chunk including continuation flag
     * @return Complete message if this was final chunk, null otherwise
     */
    fun processGATTChunk(connectionId: String, chunk: ByteArray): ByteArray? {
        if (chunk.isEmpty()) {
            logger.e("Received empty GATT chunk")
            return null
        }

        val state = assemblyStates.getOrPut(connectionId) { AssemblyState() }

        synchronized(state) {
            val continuationFlag = chunk[0]
            val data = chunk.sliceArray(1 until chunk.size)

            state.buffer.write(data)
            logger.d("GATT buffer for $connectionId now has ${state.buffer.size()} bytes")

            when (continuationFlag) {
                GATT_CONTINUATION_FLAG_FINAL -> {
                    // Final chunk - return complete message
                    val completeMessage = state.buffer.toByteArray()
                    state.buffer.reset()
                    state.startTime = System.currentTimeMillis()

                    logger.i("Assembled complete GATT message: ${completeMessage.size} bytes")
                    return completeMessage
                }
                GATT_CONTINUATION_FLAG_MORE -> {
                    // More chunks coming
                    logger.d("Expecting more GATT chunks")
                    return null
                }
                else -> {
                    logger.e("Invalid GATT continuation flag: 0x${continuationFlag.toString(16)}")
                    // Reset state on invalid flag
                    state.buffer.reset()
                    return null
                }
            }
        }
    }

    /**
     * Basic CBOR validation to check if message is structurally complete
     *
     * This is a simplified validator that checks:
     * - Valid major type in first byte
     * - Correct length encoding
     * - No truncation in middle of value
     */
    private fun isValidCBOR(data: ByteArray): Boolean {
        if (data.isEmpty()) return false

        try {
            var position = 0

            fun readCBORValue(): Boolean {
                if (position >= data.size) return false

                val initialByte = data[position].toInt() and 0xFF
                val majorType = (initialByte shr 5) and 0x07
                val additionalInfo = initialByte and 0x1F

                position++

                // Calculate length based on additional info
                val length = when (additionalInfo) {
                    in 0..23 -> additionalInfo.toLong()
                    24 -> {
                        if (position >= data.size) return false
                        (data[position++].toInt() and 0xFF).toLong()
                    }
                    25 -> {
                        if (position + 1 >= data.size) return false
                        val value = ((data[position].toInt() and 0xFF) shl 8) or
                                (data[position + 1].toInt() and 0xFF)
                        position += 2
                        value.toLong()
                    }
                    26 -> {
                        if (position + 3 >= data.size) return false
                        val value = ((data[position].toLong() and 0xFF) shl 24) or
                                ((data[position + 1].toLong() and 0xFF) shl 16) or
                                ((data[position + 2].toLong() and 0xFF) shl 8) or
                                (data[position + 3].toLong() and 0xFF)
                        position += 4
                        value
                    }
                    27 -> {
                        if (position + 7 >= data.size) return false
                        // Skip 8-byte length for simplicity
                        position += 8
                        Long.MAX_VALUE // Placeholder
                    }
                    in 28..30 -> return false // Reserved
                    31 -> -1L // Indefinite length
                    else -> return false
                }

                // Process based on major type
                when (majorType) {
                    CBOR_MAJOR_TYPE_UINT,
                    CBOR_MAJOR_TYPE_NEGATIVE -> {
                        // Already processed in length calculation
                        return true
                    }
                    CBOR_MAJOR_TYPE_BYTES,
                    CBOR_MAJOR_TYPE_TEXT -> {
                        if (length >= 0) {
                            if (position + length > data.size) return false
                            position += length.toInt()
                        } else {
                            // Indefinite length - look for break marker
                            while (position < data.size && data[position].toInt() != 0xFF) {
                                if (!readCBORValue()) return false
                            }
                            if (position >= data.size) return false
                            position++ // Skip break marker
                        }
                        return true
                    }
                    CBOR_MAJOR_TYPE_ARRAY -> {
                        if (length >= 0) {
                            repeat(length.toInt()) {
                                if (!readCBORValue()) return false
                            }
                        } else {
                            // Indefinite length array
                            while (position < data.size && data[position].toInt() != 0xFF) {
                                if (!readCBORValue()) return false
                            }
                            if (position >= data.size) return false
                            position++ // Skip break marker
                        }
                        return true
                    }
                    CBOR_MAJOR_TYPE_MAP -> {
                        if (length >= 0) {
                            repeat(length.toInt()) {
                                if (!readCBORValue()) return false // Key
                                if (!readCBORValue()) return false // Value
                            }
                        } else {
                            // Indefinite length map
                            while (position < data.size && data[position].toInt() != 0xFF) {
                                if (!readCBORValue()) return false // Key
                                if (!readCBORValue()) return false // Value
                            }
                            if (position >= data.size) return false
                            position++ // Skip break marker
                        }
                        return true
                    }
                    CBOR_MAJOR_TYPE_TAG -> {
                        // Tag is followed by content
                        return readCBORValue()
                    }
                    CBOR_MAJOR_TYPE_SIMPLE -> {
                        // Simple values and floats
                        when (additionalInfo) {
                            in 0..19 -> return true // Simple values
                            20, 21 -> return true // False, True
                            22, 23 -> return true // Null, Undefined
                            24 -> {
                                // 1-byte simple value already consumed
                                return true
                            }
                            25 -> {
                                // 2-byte float already consumed
                                return true
                            }
                            26 -> {
                                // 4-byte float already consumed
                                return true
                            }
                            27 -> {
                                // 8-byte float already consumed
                                return true
                            }
                            31 -> return false // Break in wrong context
                            else -> return false
                        }
                    }
                    else -> return false
                }
            }

            // Try to read one complete CBOR value
            val result = readCBORValue()

            // Check if we consumed exactly all the data
            return result && position == data.size

        } catch (e: Exception) {
            logger.d("CBOR validation failed: ${e.message}")
            return false
        }
    }

    /**
     * Clear assembly state for a connection
     */
    fun clearConnection(connectionId: String) {
        assemblyStates.remove(connectionId)
        logger.d("Cleared assembly state for $connectionId")
    }

    /**
     * Clear all assembly states
     */
    fun clearAll() {
        val count = assemblyStates.size
        assemblyStates.clear()
        logger.d("Cleared $count assembly states")
    }

    /**
     * Get statistics about message assembly
     */
    fun getStatistics(): FramingStatistics {
        return FramingStatistics(
            activeConnections = assemblyStates.size,
            totalBufferedBytes = assemblyStates.values.sumOf { it.buffer.size() }
        )
    }

    data class FramingStatistics(
        val activeConnections: Int,
        val totalBufferedBytes: Int
    )
}