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
import com.spruceid.mobile.sdk.rs.MdocCertificateProfiles
import com.spruceid.mobile.sdk.rs.establishSession
import com.spruceid.mobile.sdk.rs.ReaderHandover
import java.util.UUID

/**
 * @param requestedItems data elements to request, keyed by namespace then element identifier.
 * @param docType document type to request. Required: a request naming the wrong doctype is
 *   answered with nothing rather than an error, so the caller must say what it wants.
 * @param certificateProfiles certificate validation rules per doctype. Null validates every
 *   doctype under the ISO/IEC 18013-5 mDL profile, which is the behaviour every caller had
 *   before this was configurable. Supplying a map also means a doctype absent from it is
 *   refused rather than validated under a guess, so it must cover [docType].
 */
class IsoMdlReader(
    val callback: BLESessionStateDelegate,
    handover: ReaderHandover,
    requestedItems: Map<String, Map<String, Boolean>>,
    trustAnchorRegistry: List<String>?,
    platformBluetooth: BluetoothManager,
    context: Context,
    docType: String,
    private val certificateProfiles: Map<String, MdocCertificateProfiles>? = null
) {
    private lateinit var session: MdlSessionManager
    private lateinit var bleManager: Transport

    constructor(
        callback: BLESessionStateDelegate,
        uri: String,
        requestedItems: Map<String, Map<String, Boolean>>,
        trustAnchorRegistry: List<String>?,
        platformBluetooth: BluetoothManager,
        context: Context,
        docType: String,
        certificateProfiles: Map<String, MdocCertificateProfiles>? = null,
    ) : this(
        callback,
        ReaderHandover.newQr(uri),
        requestedItems,
        trustAnchorRegistry,
        platformBluetooth,
        context,
        docType,
        certificateProfiles,
    )

    init {
        try {
            val sessionData =
                establishSession(handover, requestedItems, trustAnchorRegistry, docType)

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

                    else -> throw IllegalStateException("No BLE transport options in Device Engagement")
                }
            } catch (e: SecurityException) {
                Log.e("IsoMdlReader", "SecurityException during BLE initialization: ${e.message}", e)
                callback.update(mapOf("error" to "Bluetooth permission denied: ${e.message}"))
            }

        } catch (e: Exception) {
            Log.e("IsoMdlReader.constructor", "Error during initialization: ${e.message}", e)
            callback.update(mapOf("error" to (e.message ?: "Unknown initialization error")))
        }
    }

    fun handleResponse(response: ByteArray): Map<String, Map<String, MDocItem>> {
        try {
            val responseData =
                com.spruceid.mobile.sdk.rs.handleResponse(session, response, certificateProfiles)
            return responseData.verifiedResponse
        } catch (e: MdlReaderResponseException) {
            throw e
        }
    }

    fun handleMdlReaderResponseData(response: ByteArray): MdlReaderResponseData {
        try {
            val data =
                com.spruceid.mobile.sdk.rs.handleResponse(session, response, certificateProfiles)
            // Diagnostic: surface what handleResponse produced so a capture can
            // tell "empty/failed parse" from "parsed but unverified". `errors`
            // is the JSON-encoded per-category error map from isomdl.
            Log.d(
                "IsoMdlReader",
                "handleResponse: docTypes=${data.docTypes}, " +
                    "issuerAuth=${data.issuerAuthentication}, " +
                    "deviceAuth=${data.deviceAuthentication}, " +
                    "namespaces=${data.verifiedResponse.keys}, " +
                    "errors=${data.errors}"
            )
            return data
        } catch (e: MdlReaderResponseException) {
            Log.e("IsoMdlReader", "handleResponse failed", e)
            throw e
        }
    }

    private fun updateResponseData(data: ByteArray): Boolean {
        // Handle mDL response when Reader is Central (Client)
        val response = handleMdlReaderResponseData(data)
        callback.update(mapOf("mdl" to response))
        return true
    }
}
