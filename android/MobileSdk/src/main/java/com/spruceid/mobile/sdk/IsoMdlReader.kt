package com.spruceid.mobile.sdk

import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import com.spruceid.mobile.sdk.ble.BleConnectionStateMachineInstanceType
import com.spruceid.mobile.sdk.ble.Transport
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
            try {
                val peripheralDetails = session.blePeripheralServerDetails()
                val centralDetails = session.bleCentralClientDetails()

                when {
                    centralDetails.isNotEmpty() -> {
                        Log.d("IsoMdlReader", "HOLDER AS CENTRAL AND READER AS PERIPHERAL")
                        // Primary: Prefer mdoc Central Client mode per ISO 18013-5 Section 8.3.3.1.1.4
                        // "If the mdoc indicates during device engagement that it supports both modes,
                        // the mdoc reader should select the mdoc central client mode."
                        // Holder connects as Central → Reader advertises as Peripheral
                        // Create Transport with SERVER instance for Reader's Peripheral mode
                        bleManager = Transport(platformBluetooth, context, stateMachineType = BleConnectionStateMachineInstanceType.SERVER)
                        bleManager.initialize(
                            "Reader",
                            UUID.fromString(centralDetails.first().serviceUuid),
                            "BLE",
                            "Peripheral",
                            sessionData.bleIdent,
                            null,
                            callback,
                            sessionData.request
                        )
                    }
                    peripheralDetails.isNotEmpty() -> {
                        Log.d("IsoMdlReader", "HOLDER AS PERIPHERAL AND READER AS CENTRAL")
                        // Fallback: Holder as Peripheral Server
                        // Reader connects as Central → Holder advertises as Peripheral
                        // Create Transport with CLIENT instance for Reader's Central mode
                        bleManager = Transport(platformBluetooth, context, stateMachineType = BleConnectionStateMachineInstanceType.CLIENT)
                        bleManager.initialize(
                            "Reader",
                            UUID.fromString(peripheralDetails.first().serviceUuid),
                            "BLE",
                            "Central",
                            sessionData.bleIdent,
                            ::updateResponseData,
                            callback,
                            sessionData.request
                        )
                    }

                    else -> throw Error("No BLE transport options in Device Engagement")
                }
            } catch (e: SecurityException) {
                Log.e("IsoMdlReader", "SecurityException during BLE initialization: ${e.message}", e)
                callback.update(mapOf("error" to "Bluetooth permission denied: ${e.message}"))
            }

        } catch (e: Error) {
            Log.e("IsoMdlReader.constructor", "Error during initialization: ${e.message}", e)
            callback.update(mapOf("error" to (e.message ?: "Unknown initialization error")))
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

    private fun updateResponseData(data: ByteArray){
        // Handle mDL response when Reader is Central (Client)
        val response = handleMdlReaderResponseData(data)
        callback.update(mapOf("mdl" to response))
    }
}