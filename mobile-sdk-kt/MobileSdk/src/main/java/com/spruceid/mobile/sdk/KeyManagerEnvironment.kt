package com.spruceid.mobile.sdk

/**
 * The Keystore environment used for the key generation.
 */
enum class KeyManagerEnvironment(val string: String) {
    TEE("tee"),
    Strongbox("strongbox"),
}