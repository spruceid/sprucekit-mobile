package com.spruceid.mobile.sdk.ble

import java.io.IOException
import java.io.InputStream

/**
 * L2CAP Utilities for BLE Communication
 *
 * Handles L2CAP message framing and PSM (Protocol Service Multiplexer) parsing
 * as specified in ISO 18013-5 and Bluetooth Core Specification.
 *
 * Message Framing (ISO 18013-5):
 * - Bytes 0-1: Reserved (0x00, 0x00)
 * - Bytes 2-3: Payload length (big-endian uint16)
 *
 * PSM Parsing (Bluetooth Core Spec v5.4, Vol 3, Part A, Section 4.2):
 * - PSM is 2+ octets, valid range 0x0001-0x00FF for LE (128-255)
 * - Handles both big-endian (iOS multipaz) and little-endian (standard) encoding
 */
object L2CapUtils {

    /**
     * L2CAP Header Result
     *
     * @property header The 4-byte header (for logging/debugging)
     * @property payloadLength The parsed payload length from the header
     */
    data class HeaderResult(
        val header: ByteArray,
        val payloadLength: Int
    )

    /**
     * Frame a payload with the L2CAP 4-byte header
     *
     * @param payload The data to frame (can be empty for termination messages)
     * @return ByteArray with 4-byte header + payload
     */
    fun frame(payload: ByteArray): ByteArray {
        val payloadLength = payload.size
        val framedMessage = ByteArray(4 + payloadLength)

        // Reserved bytes
        framedMessage[0] = 0x00
        framedMessage[1] = 0x00

        // Length in big-endian format
        framedMessage[2] = ((payloadLength shr 8) and 0xFF).toByte() // High byte
        framedMessage[3] = (payloadLength and 0xFF).toByte()          // Low byte

        // Copy payload after header
        if (payloadLength > 0) {
            System.arraycopy(payload, 0, framedMessage, 4, payloadLength)
        }

        return framedMessage
    }

    /**
     * Parse the L2CAP header to extract payload length
     *
     * @param header First 4 bytes of L2CAP message
     * @return Payload length in bytes
     */
    fun parseLength(header: ByteArray): Int {
        require(header.size >= 4) { "Header must be at least 4 bytes" }

        // Parse big-endian uint16 from bytes 2-3
        return ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)
    }

    /**
     * Read and parse L2CAP header from input stream
     *
     * Reads exactly 4 bytes from the input stream (blocking until available)
     * and parses the payload length. Handles partial reads gracefully.
     *
     * @param inputStream The L2CAP socket input stream
     * @return HeaderResult containing the raw header and parsed payload length
     * @throws IOException if connection closes before reading complete header
     */
    fun readHeader(inputStream: InputStream): HeaderResult {
        val header = ByteArray(4)
        var totalRead = 0

        while (totalRead < 4) {
            val n = inputStream.read(header, totalRead, 4 - totalRead)
            if (n == -1) {
                throw IOException("L2CAP connection closed while reading header (read $totalRead/4 bytes)")
            }
            totalRead += n
        }

        val payloadLength = parseLength(header)
        return HeaderResult(header, payloadLength)
    }

    /**
     * Parse L2CAP PSM (Protocol Service Multiplexer) from characteristic value
     *
     * Per Bluetooth Core Spec v5.4, Vol 3, Part A, Section 4.2:
     * - PSM is 2+ octets, valid range 0x0001-0x00FF for LE (128-255)
     * - Spec requires PSM to be ODD (LSB must be 1)
     * - However, iOS and other implementations violate this, using EVEN PSMs
     * - Therefore, we accept both odd and even PSM values
     * - Spec defines bit patterns but NOT explicit byte order
     * - Bluetooth convention is little-endian (Core Spec Vol 1, Part A)
     *
     * Implementation differences:
     * - Standard (little-endian): [0xC1, 0x00] = 193 (odd, compliant)
     * - iOS multipaz: [0x00, 0x00, 0x00, 0xC0] = 192 (even, non-compliant but common)
     *
     * Strategy: Read last 2 bytes to handle padding, try big-endian first (works with multipaz app),
     * fallback to little-endian (Bluetooth convention) if result is 0
     *
     * @param value The raw characteristic value bytes
     * @return PSM value (may be odd or even), or 0 if invalid
     */
    fun parsePSM(value: ByteArray): Int {
        if (value.size < 2) {
            return 0
        }

        val byte1 = value[value.size - 2].toInt() and 0xFF
        val byte2 = value[value.size - 1].toInt() and 0xFF

        // Try big-endian (natural byte order) - works with multipaz
        var psm = (byte1 shl 8) or byte2

        // If invalid (0), try little-endian (Bluetooth convention)
        if (psm == 0) {
            psm = (byte2 shl 8) or byte1
        }

        return psm
    }

    /**
     * Create a termination message (empty payload)
     *
     * @return 4-byte header with length=0
     */
    fun createTerminationMessage(): ByteArray {
        return frame(ByteArray(0))
    }
}
