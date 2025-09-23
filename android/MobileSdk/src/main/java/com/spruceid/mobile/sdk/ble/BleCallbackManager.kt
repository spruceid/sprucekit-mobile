package com.spruceid.mobile.sdk.ble

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.os.Build
import androidx.annotation.RequiresApi
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

/**
 * Centralized callback management with WeakReferences to prevent memory leaks
 *
 * This manager wraps Android Bluetooth callbacks in WeakReferences to prevent
 * memory leaks during configuration changes (e.g., screen rotation) or when
 * activities are destroyed while BLE operations are ongoing.
 *
 * Key features:
 * - Automatic cleanup of null references
 * - Thread-safe callback registration/unregistration
 * - Lifecycle-aware callback management
 * - Support for multiple callback types (GATT, Server, Peripheral)
 */
class BleCallbackManager private constructor() {

    companion object {
        @Volatile
        private var INSTANCE: BleCallbackManager? = null

        fun getInstance(): BleCallbackManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: BleCallbackManager().also { INSTANCE = it }
            }
        }
    }

    // Thread-safe storage for callbacks with automatic cleanup
    private val gattCallbacks = ConcurrentHashMap<String, WeakReference<BluetoothGattCallback>>()
    private val customCallbacks = ConcurrentHashMap<String, WeakReference<Any>>()
    private val logger = BleLogger.getInstance("BleCallbackManager")

    /**
     * Wraps a BluetoothGattCallback in a WeakReference wrapper that forwards calls
     * to the original callback if it's still alive, preventing memory leaks
     */
    fun wrapGattCallback(
        key: String,
        callback: BluetoothGattCallback
    ): BluetoothGattCallback {
        // Store weak reference to original callback
        gattCallbacks[key] = WeakReference(callback)

        // Return a wrapper that checks if original is still alive
        return object : BluetoothGattCallback() {

            private fun getCallback(): BluetoothGattCallback? {
                val ref = gattCallbacks[key]?.get()
                if (ref == null) {
                    logger.d("Callback for $key has been garbage collected")
                    gattCallbacks.remove(key)
                }
                return ref
            }

            override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
                getCallback()?.onConnectionStateChange(gatt, status, newState)
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
                getCallback()?.onServicesDiscovered(gatt, status)
            }

            override fun onCharacteristicRead(
                gatt: BluetoothGatt?,
                characteristic: BluetoothGattCharacteristic?,
                status: Int
            ) {
                @Suppress("deprecation")
                getCallback()?.onCharacteristicRead(gatt, characteristic, status)
            }

            @RequiresApi(Build.VERSION_CODES.TIRAMISU)
            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int
            ) {
                getCallback()?.onCharacteristicRead(gatt, characteristic, value, status)
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt?,
                characteristic: BluetoothGattCharacteristic?,
                status: Int
            ) {
                getCallback()?.onCharacteristicWrite(gatt, characteristic, status)
            }

            @Deprecated("Deprecated in Java")
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt?,
                characteristic: BluetoothGattCharacteristic?
            ) {
                @Suppress("deprecation")
                getCallback()?.onCharacteristicChanged(gatt, characteristic)
            }

            @RequiresApi(Build.VERSION_CODES.TIRAMISU)
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray
            ) {
                getCallback()?.onCharacteristicChanged(gatt, characteristic, value)
            }

            override fun onDescriptorRead(
                gatt: BluetoothGatt?,
                descriptor: BluetoothGattDescriptor?,
                status: Int
            ) {
                @Suppress("deprecation")
                getCallback()?.onDescriptorRead(gatt, descriptor, status)
            }

            @RequiresApi(Build.VERSION_CODES.TIRAMISU)
            override fun onDescriptorRead(
                gatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int,
                value: ByteArray
            ) {
                getCallback()?.onDescriptorRead(gatt, descriptor, status, value)
            }

            override fun onDescriptorWrite(
                gatt: BluetoothGatt?,
                descriptor: BluetoothGattDescriptor?,
                status: Int
            ) {
                getCallback()?.onDescriptorWrite(gatt, descriptor, status)
            }

            override fun onReliableWriteCompleted(gatt: BluetoothGatt?, status: Int) {
                getCallback()?.onReliableWriteCompleted(gatt, status)
            }

            override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
                getCallback()?.onMtuChanged(gatt, mtu, status)
            }

            override fun onPhyRead(gatt: BluetoothGatt?, txPhy: Int, rxPhy: Int, status: Int) {
                getCallback()?.onPhyRead(gatt, txPhy, rxPhy, status)
            }

            override fun onPhyUpdate(gatt: BluetoothGatt?, txPhy: Int, rxPhy: Int, status: Int) {
                getCallback()?.onPhyUpdate(gatt, txPhy, rxPhy, status)
            }

            override fun onReadRemoteRssi(gatt: BluetoothGatt?, rssi: Int, status: Int) {
                getCallback()?.onReadRemoteRssi(gatt, rssi, status)
            }
        }
    }

    /**
     * Register a custom callback (e.g., GattClientCallback, GattServerCallback)
     */
    fun <T : Any> registerCallback(key: String, callback: T): T {
        customCallbacks[key] = WeakReference(callback)
        logger.d("Registered callback for $key")
        return callback
    }

    /**
     * Get a registered callback
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> getCallback(key: String): T? {
        val ref = customCallbacks[key]?.get() as? T
        if (ref == null) {
            customCallbacks.remove(key)
            logger.d("Callback for $key has been garbage collected")
        }
        return ref
    }

    /**
     * Unregister a callback by key
     */
    fun unregisterCallback(key: String) {
        gattCallbacks.remove(key)
        customCallbacks.remove(key)
        logger.d("Unregistered callback for $key")
    }

    /**
     * Unregister all callbacks for a specific prefix (e.g., all callbacks for a connection)
     */
    fun unregisterCallbacksWithPrefix(prefix: String) {
        val gattKeysToRemove = gattCallbacks.keys.filter { it.startsWith(prefix) }
        val customKeysToRemove = customCallbacks.keys.filter { it.startsWith(prefix) }

        gattKeysToRemove.forEach { gattCallbacks.remove(it) }
        customKeysToRemove.forEach { customCallbacks.remove(it) }

        logger.d("Unregistered ${gattKeysToRemove.size + customKeysToRemove.size} callbacks with prefix $prefix")
    }

    /**
     * Clean up all null references
     */
    fun cleanupNullReferences() {
        val gattNullKeys = gattCallbacks.entries.filter { it.value.get() == null }.map { it.key }
        val customNullKeys = customCallbacks.entries.filter { it.value.get() == null }.map { it.key }

        gattNullKeys.forEach { gattCallbacks.remove(it) }
        customNullKeys.forEach { customCallbacks.remove(it) }

        if (gattNullKeys.isNotEmpty() || customNullKeys.isNotEmpty()) {
            logger.d("Cleaned up ${gattNullKeys.size + customNullKeys.size} null references")
        }
    }

    /**
     * Clear all callbacks (use with caution)
     */
    fun clearAll() {
        val totalCallbacks = gattCallbacks.size + customCallbacks.size
        gattCallbacks.clear()
        customCallbacks.clear()
        logger.i("Cleared all $totalCallbacks callbacks")
    }

    /**
     * Get statistics about registered callbacks
     */
    fun getStatistics(): CallbackStatistics {
        cleanupNullReferences()
        return CallbackStatistics(
            gattCallbackCount = gattCallbacks.size,
            customCallbackCount = customCallbacks.size,
            totalCallbackCount = gattCallbacks.size + customCallbacks.size
        )
    }

    data class CallbackStatistics(
        val gattCallbackCount: Int,
        val customCallbackCount: Int,
        val totalCallbackCount: Int
    )
}