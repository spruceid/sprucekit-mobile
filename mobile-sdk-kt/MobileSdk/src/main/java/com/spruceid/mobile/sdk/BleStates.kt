package com.spruceid.mobile.sdk

enum class BleStates(val string: String) {
    Scanning("scanning"),
    StopScan("stopScan"),
    DeviceDiscovered("deviceDiscovered"),
    AdvertisementStarted("advertisementStarted"),
    AdvertisementFailed("advertisementFailed"),
    StopAdvertise("stopAdvertise"),
    ConnectingGattClient("connectingGattClient"),
    DisconnectGattClient("disconnectGattClient"),
    GattClientConnected("gattClientConnected"),
    ServicesDiscovered("servicesDiscovered"),
    StopGattServer("stopGattServer"),
    GattServerConnected("gattServerConnected"),
    TransactionComplete("transactionComplete"), // Indicates successful transaction
    NeedsPermissions("needsPermissions"),
    None("none"),
}