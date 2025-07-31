package com.spruceid.mobile.sdk

import com.spruceid.mobile.sdk.rs.DeviceEngagementData
import com.spruceid.mobile.sdk.rs.PrenegotiatedBle
import com.spruceid.mobile.sdk.rs.Uuid

sealed class CredentialPresentData {
    /**
     * Indicates the device engagement will be via QR code
     */
    class Qr : CredentialPresentData() {
        companion object
    }

    /**
     * Device engagement via Near Field Communication (NFC)
     * The BLE
     */
    data class Nfc(
        val prenegotiatedBle: PrenegotiatedBle,
        val transport: Transport,
    ) : CredentialPresentData() {
        companion object
    }
}