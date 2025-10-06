package com.spruceid.mobile.sdk

import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import com.spruceid.mobile.sdk.rs.CryptoCurveUtils
import com.spruceid.mobile.sdk.rs.DeviceEngagementData
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
    var callback: BLESessionStateDelegate?,
    val context: Context,
) {
    // The callback is set in the `initialize` method

    var uuid: UUID = UUID.randomUUID()
    var session: MdlPresentationSession? = null
    var itemsRequests: List<ItemsRequest> = listOf()
    var bleManager: Transport? = null
    lateinit var deviceEngagementData: DeviceEngagementData

    fun initialize(presentationData: CredentialPresentData = CredentialPresentData.Qr()) {

        when (presentationData) {
            is CredentialPresentData.Qr -> {
                deviceEngagementData = DeviceEngagementData.Qr(uuid.toString())
            }
            is CredentialPresentData.Nfc -> {
                this.uuid = UUID.fromString(presentationData.negotiatedCarrierInfo.getUuid())
                deviceEngagementData = DeviceEngagementData.Nfc(presentationData.negotiatedCarrierInfo)
            }
        }

        try {
            session = initializeMdlPresentationFromBytes(this.mdoc, deviceEngagementData)

            this.bleManager = Transport(this.bluetoothManager)
            this.bleManager!!.initialize(
                "Holder",
                this.uuid,
                "BLE",
                "Central",
                session!!.getBleIdent(),
                ::updateRequestData,
                context,
                callback
            )

            // Set the callback to the transport BLE client holder callback.
            callback = this.bleManager!!.transportBLE.transportBleCentralClientHolder.callback

            // Only when the Device engagement type is QR do we
            // require the `engagingQRCode`
            if (deviceEngagementData is DeviceEngagementData.Qr && callback != null) {
                callback!!.update(mapOf(Pair("engagingQRCode", session!!.getQrHandover())))
            }

        } catch (e: Error) {
            Log.e("IsoMdlPresentation.constructor", e.toString())
        }
    }

    fun submitNamespaces(items: Map<String, Map<String, List<String>>>) {
        val payload = session!!.generateResponse(items)

        val ks: KeyStore = KeyStore.getInstance("AndroidKeyStore")

        ks.load(null)

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
            this.callback?.update(mapOf(Pair("error", e.toString())))
            throw e
        }
    }

    fun terminate() {
        this.bleManager!!.terminate()
    }

    fun updateRequestData(data: ByteArray) {
        try {
            this.itemsRequests = session!!.handleRequest(data)
            this.callback?.update(mapOf(Pair("selectNamespaces", this.itemsRequests)))
        } catch (e: RequestException) {
            this.callback?.error(e)
        }
    }
}
