package com.spruceid.mobile.sdk.nfc

import android.app.ActivityManager
import android.content.Context
import android.content.ComponentName;
import android.nfc.cardemulation.HostApduService
import android.nfc.cardemulation.CardEmulation
import android.nfc.NfcAdapter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.spruceid.mobile.sdk.nfc.ApduCommandType
import com.google.android.play.core.integrity.d
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

var currentInteractionId: Int = 0
var currentFileBytes: ByteArray? = null

abstract class BaseNfcPresentationService : HostApduService() {

    val TAG = "BaseNfcPresentationService"

    override fun processCommandApdu(commandApdu: ByteArray, extras: Bundle?): ByteArray? {

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
                        currentFileBytes = listOf(
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
                        return null
                    }
                    null -> {
                        Log.w(TAG, "Received SELECT FILE command for unknown file ID: $fileId")
                        return null
                    }
                }
            }
            ApduCommandType.READ_BINARY -> {
                if (currentFileBytes == null) {
                    Log.w(TAG, "Received READ BINARY command APDU but no file selected")
                    return null
                }

                val offset = (commandApdu[2].toInt() and 0xFF shl 8) or (commandApdu[3].toInt() and 0xFF)
                val length = commandApdu[4].toInt() and 0xFF

                if (offset + length > currentFileBytes!!.size) {
                    Log.w(TAG, "READ BINARY command APDU out of bounds: offset=$offset, length=$length")
                    return ApduResponse.INCORRECT_P1_OR_P2.bytes
                }

                Log.d(TAG, "READ BINARY command APDU: offset=$offset, length=$length")

                return currentFileBytes!!.sliceArray(offset until offset + length) + ApduResponse.OK.bytes
            }
            else -> {
                // This is a message that we don't know how to handle.
                Log.w(TAG, "Received unknown command APDU: ${commandApdu.toHex()}")
                return null
            }
        }.bytes
    }

    override fun onDeactivated(reason: Int) {

        currentFileBytes = null

        fun defer(delay: Duration, action: Runnable) {
            Handler(Looper.getMainLooper()).postDelayed(action, delay.inWholeMilliseconds)
        }

        // Wait a moment before turning off NDEF listening.
        // This is because the shift from MDOC -> NDEF triggers a disconnect, but
        // this disconnect is expected and not an error.
        val prevInteractionId = currentInteractionId
        defer(5.seconds, {
            if(prevInteractionId == currentInteractionId) {
                listenForApdus(applicationContext, componentName(), listOf(APDU_AID_MDOC))
                // TODO: Flag error to implementer?
            }
        })


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
