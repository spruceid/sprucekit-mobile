package com.spruceid.mobile.sdk.nfc

val APDU_AID_MDOC = "A0000002480400".fromHex()
val APDU_AID_NDEF_APPLICATION = "D2760000850101".fromHex()

enum class ApduCommandType {
    SELECT_AID,
    SELECT_FILE,
    READ_BINARY,
    UPDATE_BINARY,
    RESPONSE,
    ENVELOPE;

    companion object {
        fun fromBytes(msg: ByteArray): ApduCommandType? {
            if(msg.size <= 2) return null
            val ins = msg[1].toInt() and 0xFF
            val p1 = msg[2].toInt() and 0xFF
            return when(ins) {
                0xa4 -> when(p1) {
                    0x00 -> SELECT_FILE
                    0x04 -> SELECT_AID
                    else -> null
                }
                0xb0 -> READ_BINARY
                0xd6 -> UPDATE_BINARY
                0xc0 -> RESPONSE
                0xc3 -> ENVELOPE
                else -> null
            }
        }
    }
}

enum class ApduResponse(val bytes: ByteArray) {
    OK("9000".fromHex()),
    NOT_FOUND("6A82".fromHex()),
    INCORRECT_P1_OR_P2("6A86".fromHex()),
    CONDITIONS_NOT_SATISFIED("6985".fromHex()),
}

enum class FileId(val id: Int) {
    CAPABILITY_CONTAINER(0xE103),
    NDEF_FILE(0xE104);

    companion object {
        fun fromId(id: Int): FileId? {
            return values().find { it.id == id }
        }
    }
}

// fun response(status: Int, payload: ?ByteArray) {
//
// }