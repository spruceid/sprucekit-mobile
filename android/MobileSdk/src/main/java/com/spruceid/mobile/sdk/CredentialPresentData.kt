package com.spruceid.mobile.sdk

import com.spruceid.mobile.sdk.rs.NegotiatedCarrierInfo

sealed class CredentialPresentData {
    /** Indicates the device engagement will be via QR code */
    class Qr : CredentialPresentData() {
        companion object
    }

    /** Device engagement via Near Field Communication (NFC) The BLE */
    data class Nfc(
            val negotiatedCarrierInfo: NegotiatedCarrierInfo,
    ) : CredentialPresentData() {
        companion object
    }
}
