package com.spruceid.wallet.sdk

abstract class GattServerCallback {
    open fun onPeerConnected() {}
    open fun onPeerDisconnected() {}
    open fun onMessageReceived(data: ByteArray) {}
    open fun onTransportSpecificSessionTermination() {}
    open fun onError(error: Throwable) {}
    open fun onLog(message: String) {}
    open fun onState (state: String) {}
    open fun onMessageSendProgress(progress: Int, max: Int) {}
}