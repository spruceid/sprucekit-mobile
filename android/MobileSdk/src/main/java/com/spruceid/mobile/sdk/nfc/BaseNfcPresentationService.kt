package com.spruceid.mobile.sdk.nfc

import android.app.ActivityManager
import android.content.Context
import android.content.ComponentName;
import android.nfc.NdefMessage
import android.nfc.cardemulation.HostApduService
import android.nfc.cardemulation.CardEmulation
import android.nfc.NfcAdapter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.spruceid.mobile.sdk.nfc.ApduCommandType
import com.spruceid.mobile.sdk.nfc.currentFileWriteBytes
import com.google.android.play.core.integrity.d
import com.spruceid.mobile.sdk.rs.PrenegotiatedBle
import com.spruceid.mobile.sdk.rs.negotiateBleConnection
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

enum class NfcPresentationError(val humanReadable: String) {
    NEGOTIATION_FAILED("This wallet is not compatible with the current reader."),
    CONNECTION_CLOSED("The device was removed from the reader too quickly."),
}

fun listenForApdus(applicationContext: Context, componentName: ComponentName, aids: List<ByteArray>) {
    val cardEmulation = CardEmulation.getInstance(NfcAdapter.getDefaultAdapter(applicationContext))
    val success = cardEmulation.registerAidsForService(componentName, CardEmulation.CATEGORY_OTHER, aids.map { it.toHex() })
    Log.d("BaseNfcPresentationService", "Registered AIDs for service: $success (${aids.joinToString(", ") { it.toHex() }})")
}

var currentInteractionId: Long = 0
var currentFileReadBytes: ByteArray? = null
var currentFileWriteBytes: ByteArray? = null
var currentFileId: FileId? = null

abstract class BaseNfcPresentationService : HostApduService() {

    val TAG = "BaseNfcPresentationService"


    private var _prenegotiatedBle: PrenegotiatedBle? = null
    // private var lastNDEFMessage: ByteArray? = null

    val prenegotiatedBle: PrenegotiatedBle
        get() {
            if (_prenegotiatedBle == null) {
                _prenegotiatedBle = negotiateBleConnection(UUID.randomUUID().toString())
            }
            return _prenegotiatedBle!!
        }

    // private fun getNDEFResponse(): ByteArray? {
    //     val resp = prenegotiatedBle.getNfcHandover(lastNDEFMessage)
    //     lastNDEFMessage = null
    //     return resp
    // }

    private fun receivedNdefMessage(message: ByteArray): ApduResponse {
        val msg = NdefMessage(message)
        if(msg.records.isEmpty()) {
            // TODO: Wrong response code
            return ApduResponse.FILE_OR_APPLICATION_NOT_FOUND
        }
        val handoverRecord = msg.records[0]
        if(!handoverRecord.type.contentEquals(byteArrayOf(0x54, 0x73))) {
            // TODO: Wrong response code
            return ApduResponse.FILE_OR_APPLICATION_NOT_FOUND
        }
        Log.d(TAG, "Received NDEF message: ${handoverRecord.toString()}")
        return ApduResponse.OK
    }

    override fun processCommandApdu(commandApdu: ByteArray, extras: Bundle?): ByteArray? {

        fun readU16(bytes: ByteArray, offset: Int): Int {
            return (bytes[offset].toInt() and 0xFF shl 8) or (bytes[offset + 1].toInt() and 0xFF)
        }

        currentInteractionId++

        var commandType = ApduCommandType.fromBytes(commandApdu)

        Log.d(TAG, "Received ${commandType ?: "unknown"} command APDU: ${commandApdu.toHex()}")

        return when (commandType) {
            ApduCommandType.SELECT_AID -> {
                if (commandApdu.size < 12) {
                    Log.w(TAG, "Received SELECT AID command APDU too short")
                    return null
                }

                if(commandApdu.sliceEq(5, APDU_AID_MDOC)) {
                    // SELECT MDOC. This is the first message we get.
                    // When we get this, tell Android to give us subsequent NDEF messages.
                    negotiationStarted()

                    Log.d(TAG, "Recv'd SELECT AID for MDOC - enabling listening for NDEFs")

                    listenForApdus(
                        applicationContext,
                        componentName(),
                        listOf(APDU_AID_MDOC, APDU_AID_NDEF_APPLICATION)
                    )
                    ApduResponse.OK
                } else if(commandApdu.sliceEq(5, APDU_AID_NDEF_APPLICATION)) {
                    // SELECT NDEF. Keep progressing through the negotiation process.
                    ApduResponse.OK
                } else {
                    Log.w(TAG, "Unexpected AID: ${commandApdu.sliceArray(5 until 12).toHex()}")
                    ApduResponse.NOT_FOUND
                }
            }
            ApduCommandType.SELECT_FILE -> {
                if (commandApdu.size < 7) {
                    Log.w(TAG, "Received SELECT FILE command APDU too short")
                    return null
                }

                when(val p2 = commandApdu[3].toInt() and 0xFF) {
                    0x0c -> {
                        // no FCI requested
                        // OK
                    }
                    0x04 -> {
                        Log.e(TAG, "FCI is unimplemented")
                        return ApduResponse.INCORRECT_P1_OR_P2.bytes
                    }
                    else -> {
                        Log.w(TAG, "Unexpected P2 value in SELECT FILE command APDU: $p2")
                        return ApduResponse.INCORRECT_P1_OR_P2.bytes
                    }
                }

                val fileId = (commandApdu[5].toInt() and 0xFF shl 8) or (commandApdu[6].toInt() and 0xFF)
                val file = FileId.fromId(fileId)

                when (file) {
                    FileId.CAPABILITY_CONTAINER -> {
                        Log.d(TAG, "Received SELECT FILE command for CAPABILITY_CONTAINER")
                        currentFileReadBytes = listOf(
                            0x00, 0x0f, // Length of the CC file
                            0x20, // Mapping version
                            0x00, 0x3b, // Maximum R-APDU (reader -> app) size // TODO: validate this value
                            0x00, 0x34, // Maximum C-APDU (app -> reader) size // TODO: validate this value
                            0x04, // NDEF file control TLV
                            0x06, // Length of TLV
                            0xe1, 0x04, // File ID: NDEF file (0xe104)
                            0x00, 0x32, // Max size of NDEF file // TODO: validate this value
                            0x00, // Read access condition
                            0x00, // Write access condition. 00 for negotiated, ff for static
                        ).map { it.toByte() }.toByteArray()
                        ApduResponse.OK
                    }
                    FileId.NDEF_FILE -> {
                        Log.d(TAG, "Received SELECT FILE command for NDEF_FILE")
                        // val resp = getNDEFResponse() ?: return null
                        val resp = prenegotiatedBle.getNfcHandoverDirect()
                        // if (resp == null) {
                        //     negotiationFailed(NfcPresentationError.NEGOTIATION_FAILED)
                        //     return null // TODO: Error reporting
                        // }
                        val lenBytes = byteArrayOf(
                            (resp.size and 0xFF00 shr 8).toByte(),
                            (resp.size and 0x00FF).toByte()
                        )
                        currentFileReadBytes = lenBytes + resp
                        @OptIn(kotlin.ExperimentalStdlibApi::class)
                        Log.d(TAG, "NDEF transmission bytes: ${currentFileReadBytes!!.joinToString("") { it.toHexString() }}")
                        ApduResponse.OK
                    }
                    null -> {
                        Log.w(TAG, "Received SELECT FILE command for unknown file ID: $fileId")
                        return null
                    }
                }
            }
            ApduCommandType.READ_BINARY -> {
                if (currentFileReadBytes == null) {
                    Log.w(TAG, "Received READ BINARY command APDU but no file selected")
                    return null
                }

                val offset = readU16(commandApdu, 2)
                val length = commandApdu[4].toInt() and 0xFF

                if (offset + length > currentFileReadBytes!!.size) {
                    Log.w(TAG, "READ BINARY command APDU out of bounds: offset=$offset, length=$length")
                    return ApduResponse.INCORRECT_P1_OR_P2.bytes
                }

                Log.d(TAG, "READ BINARY command APDU: offset=$offset, length=$length")

                return currentFileReadBytes!!.sliceArray(offset until offset + length) + ApduResponse.OK.bytes
            }
            ApduCommandType.UPDATE_BINARY -> {
                val offset = readU16(commandApdu, 2)
                val payloadLength = commandApdu[4].toInt() and 0xFF
                var data = commandApdu.sliceArray(5 until 5 + payloadLength)

                // reading data in chunks
                // the first two bytes are the message length, and the rest is the data

                Log.w(TAG, "offset = $offset")
                if(offset == 0) {
                    // When offset is 0, we're either beginning an update or ending an update.
                    val fileLength = readU16(data, 0)
                    data = data.sliceArray(2 until data.size)
                    if(data.isEmpty()) {
                        Log.w(TAG, "data IS empty")
                        // We have a length response only. This is either a reset or a finalization.
                        if(fileLength == 0) {
                            Log.w(TAG, "fileLength zero, resetting file")
                            // Reset the file.
                            currentFileWriteBytes = ByteArray(0)
                            ApduResponse.OK
                        } else {
                            Log.w(TAG, "finalizing already written file")
                            // Finalization - the data bytes should be the final length of the file.
                            if(currentFileWriteBytes == null) {
                                Log.w(TAG, "Finalization without a write in progress")
                                return ApduResponse.FILE_OR_APPLICATION_NOT_FOUND.bytes
                            }
                            if(fileLength != currentFileWriteBytes!!.size) {
                                Log.w(TAG, "Finalization length mismatch: expected $fileLength, got ${currentFileWriteBytes!!.size}")
                                return ApduResponse.FILE_OR_APPLICATION_NOT_FOUND.bytes
                            }

                            val finalFile = currentFileWriteBytes!!
                            currentFileWriteBytes = null
                            receivedNdefMessage(finalFile)
                        }
                    } else {
                        // Got entire file in a single command.
                        // receivedNdefMessage(data)
                        currentFileWriteBytes = data.copyOf()
                        ApduResponse.OK
                    }
                } else {
                    Log.w(TAG, "beginning partial write of ${data.size} bytes")
                    if(offset == 1) {
                        // Invalid - we're going to subtract 2 from the offset to account for the file length header
                        Log.w(TAG, "Received UPDATE BINARY command with offset 1, which is invalid")
                        return ApduResponse.FILE_OR_APPLICATION_NOT_FOUND.bytes
                    }
                    if(currentFileWriteBytes == null) {
                        Log.w(TAG, "Received UPDATE BINARY command with no file write in progress")
                        return ApduResponse.FILE_OR_APPLICATION_NOT_FOUND.bytes
                    }
                    val offsetIntoFile = offset - 2
                    if(offsetIntoFile != currentFileWriteBytes!!.size) {
                        Log.w(TAG, "expected sequential write, but offset is $offsetIntoFile, expected ${currentFileWriteBytes!!.size}")
                        return ApduResponse.FILE_OR_APPLICATION_NOT_FOUND.bytes
                    }
                    // Append the data to the current write.
                    currentFileWriteBytes = currentFileWriteBytes!! + data

                    ApduResponse.OK
                }
            }
            else -> {
                // This is a message that we don't know how to handle.
                Log.w(TAG, "Received unknown command APDU: ${commandApdu.toHex()}")
                return null
            }
        }.bytes
    }

    override fun onDeactivated(reason: Int) {

        currentFileReadBytes = null
        currentFileWriteBytes = null
        currentFileId = null
        // lastNDEFMessage = null

        fun defer(delay: Duration, action: Runnable) {
            Handler(Looper.getMainLooper()).postDelayed(action, delay.inWholeMilliseconds)
        }

        // Wait a moment before turning off NDEF listening.
        // This is because the shift from MDOC -> NDEF triggers a disconnect, but
        // this disconnect is expected and not an error.
        val prevInteractionId = currentInteractionId
        defer(5.seconds) {
            if (prevInteractionId == currentInteractionId) {
                listenForApdus(applicationContext, componentName(), listOf(APDU_AID_MDOC))
                _prenegotiatedBle = null
                // TODO: Flag error to implementer?
            }
        }


        Log.d(TAG, "deactivated: $reason")

        // TODO("Not yet implemented")
    }

    fun appInForeground(): Boolean {
        val activityManager =
                this.baseContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val runningProcesses = activityManager.runningAppProcesses ?: return false
        return runningProcesses.any {
            it.processName == baseContext.packageName &&
                    it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        }
    }

    abstract fun componentName(): ComponentName

    /**
     * This method is called at the beginning of the NFC negotiation process.
     * If you want to do any background processing/loading, you can kick it off here
     * and wait for it to complete in `negotiatedTransport`.
     * NOTE: This happens at the beginning of the NFC negotiation process - there is no
     * guarantee that the app is in the foreground, and no guarantee that NFC negotiation will succeed!
     */
    protected fun negotiationStarted() {}

    /**
     * This method is called when NFC negotiation failed. This could be due to failing to find a
     * compatible transport method.
     */
    abstract fun negotiationFailed(error: NfcPresentationError)

    /**
     * This method is called when an NFC reader has successfully negotiated transport for a
     * credential presentation. This *may* be called while the app is in the background, so no
     * assumptions can be made about the app's UI state.
     */
    abstract fun negotiatedTransport()
}
