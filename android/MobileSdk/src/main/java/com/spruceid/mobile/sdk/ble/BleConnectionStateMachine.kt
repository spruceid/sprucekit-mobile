package com.spruceid.mobile.sdk.ble

import android.bluetooth.BluetoothManager
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import java.lang.ref.WeakReference
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Singleton thread-safe state machine for BLE connections to prevent invalid state transitions
 * Uses StateFlow for reactive UI updates and stores BluetoothManager instance
 */
class BleConnectionStateMachine private constructor() {

    companion object {
        @Volatile
        private var INSTANCE: BleConnectionStateMachine? = null

        fun getInstance(): BleConnectionStateMachine {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: BleConnectionStateMachine().also { INSTANCE = it }
            }
        }

        /**
         * Reset the singleton instance (mainly for testing)
         */
        fun resetInstance() {
            synchronized(this) {
                INSTANCE = null
            }
        }
    }

    enum class State {
        IDLE,
        SCANNING,
        CONNECTING,
        CONNECTED,
        DISCONNECTING,
        DISCONNECTED,
        ERROR
    }

    data class ConnectionState(
        val state: State = State.IDLE,
        val errorMessage: String? = null,
        val errorType: BleErrorClassifier.ErrorType? = null,
        val timestamp: Long = System.currentTimeMillis()
    )

    private val _connectionState = MutableStateFlow(ConnectionState())
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val stateLock = Any()

    // Store BluetoothManager and Context instances
    private var _bluetoothManager: BluetoothManager? = null
    private var _contextRef: WeakReference<Context>? = null

    // Termination callback for sending 0x02 signal per ISO 18013-5
    private var terminationCallback: (() -> Unit)? = null

    /**
     * Initialize or update the BluetoothManager and Context
     * Uses WeakReference for ApplicationContext to prevent memory leaks
     */
    fun setBluetoothManager(manager: BluetoothManager, context: Context) {
        synchronized(stateLock) {
            _bluetoothManager = manager
            _contextRef = WeakReference(context.applicationContext)
        }
    }

    /**
     * Get the stored BluetoothManager
     * @throws IllegalStateException if BluetoothManager has not been initialized
     */
    fun getBluetoothManager(): BluetoothManager {
        synchronized(stateLock) {
            return _bluetoothManager
                ?: throw IllegalStateException("BluetoothManager has not been initialized. Call setBluetoothManager() first.")
        }
    }

    /**
     * Get the stored Context
     * @throws IllegalStateException if Context has not been initialized or has been garbage collected
     */
    fun getContext(): Context {
        synchronized(stateLock) {
            return _contextRef?.get()
                ?: throw IllegalStateException("Context has not been initialized or has been garbage collected. Call setBluetoothManager() first.")
        }
    }
    
    /**
     * Valid state transitions map
     */
    private val validTransitions = mapOf(
        State.IDLE to setOf(State.SCANNING, State.CONNECTING),
        State.SCANNING to setOf(State.CONNECTING, State.IDLE, State.ERROR),
        State.CONNECTING to setOf(State.CONNECTED, State.DISCONNECTED, State.ERROR),
        State.CONNECTED to setOf(State.DISCONNECTING, State.DISCONNECTED, State.ERROR),
        State.DISCONNECTING to setOf(State.DISCONNECTED, State.ERROR),
        State.DISCONNECTED to setOf(State.IDLE, State.SCANNING, State.CONNECTING),
        State.ERROR to setOf(State.IDLE, State.DISCONNECTED)
    )
    
    /**
     * Attempt to transition to a new state
     * @return true if transition was successful, false otherwise
     */
    fun transitionTo(newState: State, error: String? = null): Boolean {
        synchronized(stateLock) {
            val current = _connectionState.value.state
            val allowedTransitions = validTransitions[current] ?: emptySet()

            if (newState in allowedTransitions) {
                _connectionState.value = ConnectionState(
                    state = newState,
                    errorMessage = if (newState == State.ERROR) error else null,
                    timestamp = System.currentTimeMillis()
                )
                return true
            }

            return false
        }
    }
    
    /**
     * Force transition to a state (use carefully, mainly for recovery)
     */
    fun forceTransitionTo(newState: State) {
        synchronized(stateLock) {
            _connectionState.value = ConnectionState(
                state = newState,
                errorMessage = if (newState == State.ERROR) _connectionState.value.errorMessage else null,
                timestamp = System.currentTimeMillis()
            )
        }
    }

    /**
     * Get current state
     */
    fun getState(): State = _connectionState.value.state

    /**
     * Check if in a specific state
     */
    fun isInState(state: State): Boolean = _connectionState.value.state == state

    /**
     * Check if transition is valid from current state
     */
    fun canTransitionTo(state: State): Boolean {
        val current = _connectionState.value.state
        val allowedTransitions = validTransitions[current] ?: emptySet()
        return state in allowedTransitions
    }

    /**
     * Get error message if in error state
     */
    fun getErrorMessage(): String? = _connectionState.value.errorMessage

    /**
     * Reset to idle state
     */
    fun reset() {
        synchronized(stateLock) {
            _connectionState.value = ConnectionState(
                state = State.IDLE,
                errorMessage = null,
                timestamp = System.currentTimeMillis()
            )
        }
    }

    /**
     * Check if in terminal state (requires reset to continue)
     */
    fun isInTerminalState(): Boolean {
        return _connectionState.value.state in setOf(State.ERROR, State.DISCONNECTED)
    }

    /**
     * Set termination callback for sending ISO 18013-5 session termination (0x02)
     */
    fun setTerminationCallback(callback: () -> Unit) {
        synchronized(stateLock) {
            terminationCallback = callback
        }
    }

    /**
     * Clear termination callback
     */
    fun clearTerminationCallback() {
        synchronized(stateLock) {
            terminationCallback = null
        }
    }

    /**
     * Enhanced error transition with automatic termination handling per ISO 18013-5
     * Classifies error and triggers session termination (0x02) if required
     *
     * @param error The exception/error that occurred
     * @param context Additional context about the error
     */
    fun transitionToError(error: Throwable, context: String = ""): Boolean {
        val errorType = BleErrorClassifier.classifyError(error, context)

        synchronized(stateLock) {
            val current = _connectionState.value.state
            val allowedTransitions = validTransitions[current] ?: emptySet()

            if (State.ERROR in allowedTransitions) {
                _connectionState.value = ConnectionState(
                    state = State.ERROR,
                    errorMessage = error.message ?: "Unknown error",
                    errorType = errorType,
                    timestamp = System.currentTimeMillis()
                )

                // Send session termination (0x02) if error is terminal per ISO 18013-5
                if (errorType == BleErrorClassifier.ErrorType.TERMINAL) {
                    terminationCallback?.invoke()
                }

                return true
            }

            return false
        }
    }

    /**
     * Get error type from current state if in error state
     */
    fun getErrorType(): BleErrorClassifier.ErrorType? = _connectionState.value.errorType

    /**
     * Check if current error requires session termination
     */
    fun isCurrentErrorTerminal(): Boolean {
        return _connectionState.value.errorType == BleErrorClassifier.ErrorType.TERMINAL
    }

    /**
     * Check if current error is recoverable
     */
    fun isCurrentErrorRecoverable(): Boolean {
        return _connectionState.value.errorType == BleErrorClassifier.ErrorType.RECOVERABLE
    }
}