package com.spruceid.mobile.sdk

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import androidx.annotation.RequiresPermission
import com.spruceid.mobile.sdk.ble.Transport
import com.spruceid.mobile.sdk.rs.CryptoCurveUtils
import com.spruceid.mobile.sdk.rs.ItemsRequest
import com.spruceid.mobile.sdk.rs.MdlPresentationSession
import com.spruceid.mobile.sdk.rs.Mdoc
import com.spruceid.mobile.sdk.rs.RequestException
import com.spruceid.mobile.sdk.rs.initializeMdlPresentationFromBytes
import java.security.KeyStore
import java.security.Signature
import java.util.UUID

abstract class BLESessionStateDelegate {
    abstract fun update(state: Map<String, Any>)
    abstract fun error(error: Exception)
}

class IsoMdlPresentation(
    val mdoc: Mdoc,
    val keyAlias: String,
    val bluetoothManager: BluetoothManager,
    val callback: BLESessionStateDelegate,
    val context: Context,
    val bleMode: String = "Central" // "Central" or "Peripheral"
) {
    val uuid: UUID = UUID.randomUUID()
    var session: MdlPresentationSession? = null
    var itemsRequests: List<ItemsRequest> = listOf()
    var bleManager: Transport? = null

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun initialize() {
        try {
            session = initializeMdlPresentationFromBytes(this.mdoc, uuid.toString())
            this.bleManager = Transport(this.bluetoothManager, context)

            // Central: receives data via GATT notifications from Reader's Server2Client characteristic
            // Peripheral: receives data via GATT writes to Holder's Client2Server characteristic
            Log.d("IsoMdlPresentation", "HOLDER AS $bleMode")
            this.bleManager!!
                .initialize(
                    "Holder",
                    this.uuid,
                    "BLE",
                    bleMode,
                    session!!.getBleIdent(),
                    ::updateRequestData,
                    callback
                )

            this.callback.update(mapOf(Pair("engagingQRCode", session!!.getQrCodeUri())))
        } catch (e: Error) {
            Log.e("BleSessionManager.constructor", e.toString())
        }
    }

    fun submitNamespaces(items: Map<String, Map<String, List<String>>>) {
        val payload = session!!.generateResponse(items)

        val ks: KeyStore = KeyStore.getInstance(
            "AndroidKeyStore"
        )

        ks.load(
            null
        )

        val entry = ks.getEntry(this.keyAlias, null)
        if (entry !is KeyStore.PrivateKeyEntry) {
            throw IllegalStateException("No such private key under the alias <${this.keyAlias}>")
        }

        try {
            val signer = Signature.getInstance("SHA256withECDSA")
            signer.initSign(entry.privateKey)

            signer.update(payload)

            val signature = signer.sign()
            val normalizedSignature =
                CryptoCurveUtils.secp256r1().ensureRawFixedWidthSignatureEncoding(signature)
                    ?: throw Error("unrecognized signature encoding")
            val response = session!!.submitResponse(normalizedSignature)
            this.bleManager!!.send(response)
        } catch (e: Error) {
            Log.e("CredentialsViewModel.submitNamespaces", e.toString())
            this.callback.update(mapOf(Pair("error", e.toString())))
            throw e
        }
    }

    fun terminate() {
        this.bleManager!!.terminate()
    }

    fun updateRequestData(data: ByteArray) {
        try {
            this.itemsRequests = session!!.handleRequest(data)
            this.callback.update(mapOf(Pair("selectNamespaces", this.itemsRequests)))
        } catch (e: RequestException) {
            this.callback.error(e)
        }
    }
}
