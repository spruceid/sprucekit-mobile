package com.spruceid.mobile.sdk

import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import com.spruceid.mobile.sdk.rs.MDocItem
import com.spruceid.mobile.sdk.rs.MdlReaderResponseData
import com.spruceid.mobile.sdk.rs.MdlReaderResponseException
import com.spruceid.mobile.sdk.rs.MdlSessionManager
import com.spruceid.mobile.sdk.rs.establishSession
import java.util.UUID

class IsoMdlReader(
    val callback: BLESessionStateDelegate,
    uri: String,
    requestedItems: Map<String, Map<String, Boolean>>,
    trustAnchorRegistry: List<String>?,
    platformBluetooth: BluetoothManager,
    context: Context
) {
    private lateinit var session: MdlSessionManager
    private lateinit var bleManager: Transport

    init {
        try {
            val sessionData = establishSession(uri, requestedItems, trustAnchorRegistry)

            session = sessionData.state
            bleManager = Transport(platformBluetooth)
            bleManager.initialize(
                "Reader",
                UUID.fromString(sessionData.uuid),
                "BLE",
                "Peripheral",
                sessionData.bleIdent,
                null,
                context,
                callback,
                sessionData.request
            )

        } catch (e: Error) {
            Log.e("BleSessionManager.constructor", e.toString())
        }
    }

    fun handleResponse(response: ByteArray): Map<String, Map<String, MDocItem>> {
        try {
            val responseData = com.spruceid.mobile.sdk.rs.handleResponse(session, response)
            return responseData.verifiedResponse
        } catch (e: MdlReaderResponseException) {
            throw e
        }
    }

    fun handleMdlReaderResponseData(response: ByteArray): MdlReaderResponseData {
        try {
            return com.spruceid.mobile.sdk.rs.handleResponse(session, response)
        } catch (e: MdlReaderResponseException) {
            throw e
        }
    }
}