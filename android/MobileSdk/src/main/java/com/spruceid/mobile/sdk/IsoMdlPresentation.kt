package com.spruceid.mobile.sdk

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import androidx.annotation.RequiresPermission
import com.spruceid.mobile.sdk.ble.BleConnectionStateMachineInstanceType
import com.spruceid.mobile.sdk.ble.Transport
import com.spruceid.mobile.sdk.rs.CentralClientDetails
import com.spruceid.mobile.sdk.rs.CryptoCurveUtils
import com.spruceid.mobile.sdk.rs.ItemsRequest
import com.spruceid.mobile.sdk.rs.MdlPresentationSession
import com.spruceid.mobile.sdk.rs.Mdoc
import com.spruceid.mobile.sdk.rs.PeripheralServerDetails
import com.spruceid.mobile.sdk.rs.RequestException
import com.spruceid.mobile.sdk.rs.initializeMdlPresentationFromBytes
import java.security.KeyStore
import java.security.Signature
import java.util.UUID

enum class PresentationMode {
    /**
     * Central mode only: Holder acts as BLE Central (scanner).
     * Reader must act as Peripheral (advertiser).
     */
    CENTRAL_ONLY,

    /**
     * Peripheral mode only: Holder acts as BLE Peripheral (advertiser).
     * Reader must act as Central (scanner).
     */
    PERIPHERAL_ONLY,

    /**
     * Dual mode (default): Holder acts as both Central and Peripheral simultaneously.
     * First mode to establish connection wins. Provides maximum compatibility per ISO 18013-5.
     */
    DUAL_MODE
}

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
    val mode: PresentationMode = PresentationMode.DUAL_MODE
) {
    private val uuidCentral: UUID = UUID.randomUUID()
    private val uuidPeripheral: UUID = UUID.randomUUID()

    var session: MdlPresentationSession? = null
    var itemsRequests: List<ItemsRequest> = listOf()

    // Dual transport instances for simultaneous operation
    private var centralTransport: Transport? = null
    private var peripheralTransport: Transport? = null

    // Track which mode successfully connected first
    @Volatile
    private var connectedMode: String? = null
    private val connectionLock = Any()

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun initialize() {
        try {
            // Initialize session based on selected mode
            when (mode) {
                PresentationMode.CENTRAL_ONLY -> {
                    Log.d("IsoMdlPresentation", "Initializing Central-only mode (UUID: $uuidCentral)")
                    session = initializeMdlPresentationFromBytes(
                        this.mdoc,
                        CentralClientDetails(uuidCentral.toString()),
                        null
                    )
                    // Display QR code
                    this.callback.update(mapOf(Pair("engagingQRCode", session!!.getQrCodeUri())))
                    // Start only Central transport
                    startCentralTransport()
                }
                PresentationMode.PERIPHERAL_ONLY -> {
                    Log.d("IsoMdlPresentation", "Initializing Peripheral-only mode (UUID: $uuidPeripheral)")
                    session = initializeMdlPresentationFromBytes(
                        this.mdoc,
                        null,
                        PeripheralServerDetails(uuidPeripheral.toString(), null)
                    )
                    // Display QR code
                    this.callback.update(mapOf(Pair("engagingQRCode", session!!.getQrCodeUri())))
                    // Start only Peripheral transport
                    startPeripheralTransport()
                }
                PresentationMode.DUAL_MODE -> {
                    // Per ISO 18013-5: Advertise both modes in QR code for maximum compatibility
                    Log.d("IsoMdlPresentation", "Initializing dual-mode presentation (Central UUID: $uuidCentral, Peripheral UUID: $uuidPeripheral)")
                    session = initializeMdlPresentationFromBytes(
                        this.mdoc,
                        CentralClientDetails(uuidCentral.toString()),
                        PeripheralServerDetails(uuidPeripheral.toString(), null)
                    )
                    // Display QR code
                    this.callback.update(mapOf(Pair("engagingQRCode", session!!.getQrCodeUri())))
                    // Start both transports simultaneously
                    startPeripheralTransport()
                    startCentralTransport()
                }
            }

        } catch (e: Error) {
            Log.e("IsoMdlPresentation.initialize", e.toString())
            this.callback.error(Exception(e.message, e))
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun startPeripheralTransport() {
        Log.d("IsoMdlPresentation", "Starting Peripheral transport (advertising with UUID: $uuidPeripheral)")
        // Use "server" type for peripheral to get separate state machine instance
        peripheralTransport = Transport(this.bluetoothManager, context, stateMachineType = BleConnectionStateMachineInstanceType.SERVER)
        peripheralTransport!!.initialize(
            "Holder",
            uuidPeripheral,
            "BLE",
            "Peripheral",
            session!!.getBleIdent(),
            { data -> onDataReceived("Peripheral", data) },
            callback
        )
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun startCentralTransport() {
        Log.d("IsoMdlPresentation", "Starting Central transport (scanning for UUID: $uuidCentral)")
        // Use "default" type for central to get separate state machine instance
        centralTransport = Transport(this.bluetoothManager, context, stateMachineType = BleConnectionStateMachineInstanceType.CLIENT)
        centralTransport!!.initialize(
            "Holder",
            uuidCentral,
            "BLE",
            "Central",
            session!!.getBleIdent(),
            { data -> onDataReceived("Central", data) },
            callback
        )
    }

    private fun onDataReceived(mode: String, data: ByteArray) {
        val shouldProcess = synchronized(connectionLock) {
            // First data received wins - terminate the other mode (if in DUAL_MODE)
            if (connectedMode == null) {
                connectedMode = mode
                Log.d("IsoMdlPresentation", "Connection established via $mode mode")

                // Terminate the unused transport
                try {
                    when (mode) {
                        "Central" -> {
                            Log.d("IsoMdlPresentation", "Terminating unused Peripheral transport")
                            peripheralTransport?.terminate()
                            peripheralTransport = null
                        }
                        "Peripheral" -> {
                            Log.d("IsoMdlPresentation", "Terminating unused Central transport")
                            centralTransport?.terminate()
                            centralTransport = null
                        }
                        else -> {
                            Log.w("IsoMdlPresentation", "Unknown mode: $mode")
                        }
                    }
                } catch (e: Exception) {
                    Log.w("IsoMdlPresentation", "Error terminating unused transport: ${e.message}")
                }
                true // Process this data
            } else if (connectedMode == mode) {
                true // Already connected via this mode, process data
            } else {
                // This shouldn't happen, but log it if it does
                Log.w("IsoMdlPresentation", "Received data from $mode but already connected via $connectedMode - ignoring")
                false // Don't process
            }
        }

        // Forward data to the handler if we should process it
        if (shouldProcess) {
            updateRequestData(data)
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

            // Send through whichever transport is connected
            val activeTransport = when (connectedMode) {
                "Central" -> centralTransport
                "Peripheral" -> peripheralTransport
                else -> throw IllegalStateException("No active transport connection")
            }

            activeTransport?.send(response)
                ?: throw IllegalStateException("Active transport is null")
        } catch (e: Error) {
            Log.e("CredentialsViewModel.submitNamespaces", e.toString())
            this.callback.update(mapOf(Pair("error", e.toString())))
            throw e
        }
    }

    fun terminate() {
        // Terminate both transports
        try {
            centralTransport?.terminate()
            centralTransport = null
        } catch (e: Exception) {
            Log.w("IsoMdlPresentation", "Error terminating central transport: ${e.message}")
        }

        try {
            peripheralTransport?.terminate()
            peripheralTransport = null
        } catch (e: Exception) {
            Log.w("IsoMdlPresentation", "Error terminating peripheral transport: ${e.message}")
        }

        connectedMode = null
    }

    fun updateRequestData(data: ByteArray) {
        // Only process the first request. Subsequent messages are status/termination messages.
        // TODO: Not sure what to do here (termination messages)
        if (this.itemsRequests.isNotEmpty()) {
            Log.d("IsoMdlPresentation", "Ignoring subsequent message (${data.size} bytes) - request already processed")
            return
        }

        try {
            this.itemsRequests = session!!.handleRequest(data)
            this.callback.update(mapOf(Pair("selectNamespaces", this.itemsRequests)))
        } catch (e: RequestException) {
            Log.e("IsoMdlPresentation", "Error handling request: ${e.message}", e)
            this.callback.error(e)
        }
    }
}
