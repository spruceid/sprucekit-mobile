package com.spruceid.mobile.sdk.nfc

import android.content.ComponentName
import android.content.Context
import android.nfc.NfcAdapter
import android.nfc.cardemulation.CardEmulation
import android.util.Log

val APDU_AID_MDOC = "A0000002480400"
val APDU_AID_NDEF_APPLICATION = "D2760000850101"

/// Controls when the device should be listening for different NFC AIDs.
object NfcListenManager {
    private const val TAG = "NfcListenManager"
    private var _presentationServiceRequested = false
    private var _userRequested = false
    private var _applicationCtx: Context? = null
    private var _componentName: ComponentName? = null
    var disabled = false

    internal fun setRequestedFromNfcCommands(
            requested: Boolean,
            applicationContext: Context,
            componentName: ComponentName
    ) {
        if (_presentationServiceRequested == requested) return
        Log.d(TAG, "Setting presentationServiceRequested to $requested ")
        _presentationServiceRequested = requested
        _applicationCtx = applicationContext
        _componentName = componentName
        reconfigure()
    }

    fun init(applicationContext: Context, componentName: ComponentName) {
        _applicationCtx = applicationContext
        _componentName = componentName
        reconfigure()
    }

    var userRequested
        get() = _userRequested
        set(value) {
            Log.d(TAG, "Setting userRequested to $value")
            if(_userRequested == value) return
            _userRequested = value
            reconfigure()
        }

    private fun listenForAPDUs(aids: List<String>) {
        val cardEmulation = CardEmulation.getInstance(NfcAdapter.getDefaultAdapter(_applicationCtx))

        val success =
                cardEmulation.registerAidsForService(
                        _componentName,
                        CardEmulation.CATEGORY_OTHER,
                        aids.map { it }
                )
        Log.d(TAG, "Registered AIDs for service: $success (${aids.joinToString(", ") { it }})")
    }

    private fun reconfigure() {
        if (disabled) return
        val shouldBeListening = _presentationServiceRequested || _userRequested
        val listenFor =
                if (shouldBeListening) {
                    listOf(APDU_AID_MDOC, APDU_AID_NDEF_APPLICATION)
                } else {
                    listOf(APDU_AID_NDEF_APPLICATION)
                }
        listenForAPDUs(aids = listenFor)
    }
}
