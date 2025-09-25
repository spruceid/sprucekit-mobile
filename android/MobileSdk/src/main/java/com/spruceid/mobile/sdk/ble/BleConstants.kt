package com.spruceid.mobile.sdk.ble

import java.util.*

/**
 * BLE Constants for ISO 18013-5 Mobile Driving License (mDL) implementation.
 * 
 * These UUIDs are defined in ISO 18013-5:2021 section 8.3.3.1.1.4
 * - Table 11: mdoc service characteristics (Holder)  
 * - Table 12: mdoc reader service characteristics (Reader)
 */
object BleConstants {
    
    /**
     * Standard Bluetooth UUIDs
     */
    val CLIENT_CHARACTERISTIC_CONFIG_UUID: UUID = 
        UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    
    /**
     * ISO 18013-5 Table 5 - mdoc service characteristics (Holder/Peripheral Server)
     * Used when the mdoc acts as a GATT server (peripheral mode)
     */
    object Holder {
        val STATE_UUID: UUID = 
            UUID.fromString("00000001-a123-48ce-896b-4c76973373e6")
        val CLIENT_TO_SERVER_UUID: UUID = 
            UUID.fromString("00000002-a123-48ce-896b-4c76973373e6") 
        val SERVER_TO_CLIENT_UUID: UUID = 
            UUID.fromString("00000003-a123-48ce-896b-4c76973373e6")
        val L2CAP_UUID: UUID = 
            UUID.fromString("0000000a-a123-48ce-896b-4c76973373e6")
    }
    
    /**
     * ISO 18013-5 Table 6 - mdoc reader service characteristics (Reader/Peripheral Server)
     * Used when the mdoc reader acts as a GATT server (peripheral mode)
     */
    object Reader {
        val STATE_UUID: UUID = 
            UUID.fromString("00000005-a123-48ce-896b-4c76973373e6")
        val CLIENT_TO_SERVER_UUID: UUID = 
            UUID.fromString("00000006-a123-48ce-896b-4c76973373e6")
        val SERVER_TO_CLIENT_UUID: UUID = 
            UUID.fromString("00000007-a123-48ce-896b-4c76973373e6")
        val IDENT_UUID: UUID = 
            UUID.fromString("00000008-a123-48ce-896b-4c76973373e6")
        val L2CAP_UUID: UUID = 
            UUID.fromString("0000000b-a123-48ce-896b-4c76973373e6")
    }
}