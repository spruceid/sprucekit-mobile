package com.spruceid.mobile.sdk


fun hexToByteArray(value: String): ByteArray {
    val stripped = value.substring(2)

    return stripped.chunked(2).map { it.toInt(16).toByte() }
        .toByteArray()
}

fun byteArrayToHex(bytes: ByteArray): String {
    return "0x${bytes.joinToString("") { "%02x".format(it) }}"
}

enum class PresentmentState {
    /// Presentment has yet to start
    UNINITIALIZED,

    /// App should display the error message
    ERROR,

    /// App should display the QR code
    ENGAGING_QR_CODE,

    /// App should display an interactive page for the user to chose which values to reveal
    SELECT_NAMESPACES,

    /// App should display a success message and offer to close the page
    SUCCESS,
}
