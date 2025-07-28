package com.spruceid.mobile.sdk.nfc

import java.util.Arrays

// TODO: These should exist somewhere else, or maybe as standalone functions

fun ByteArray.toHex(): String {
    return this.joinToString("") { "%02x".format(it) }
}

fun String.fromHex(): ByteArray {
    val hex = this.replace(" ", "")
    return ByteArray(hex.length / 2) { i ->
        ((Character.digit(hex[i * 2], 16) shl 4) + Character.digit(hex[i * 2 + 1], 16)).toByte()
    }
}

fun ByteArray.sliceEq(start: Int, needle: ByteArray): Boolean {
    if (start + needle.size > this.size) return false
    return needle.contentEquals(this.sliceArray(start until start + needle.size))
}