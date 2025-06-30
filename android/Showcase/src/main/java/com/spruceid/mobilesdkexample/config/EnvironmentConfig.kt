package com.spruceid.mobilesdkexample.config

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object EnvironmentConfig {
    private const val PROD_WALLET_SERVICE = "https://wallet.grove.spruceid.xyz"
    private const val PROD_ISSUANCE_SERVICE = "https://issuance.grove.spruceid.xyz"

    private const val DEV_WALLET_SERVICE = "https://wallet.grove.staging.spruceid.xyz"
    private const val DEV_ISSUANCE_SERVICE = "https://issuance.grove.staging.spruceid.xyz"

    private val _isDevMode = MutableStateFlow(false)
    val isDevMode: StateFlow<Boolean> = _isDevMode.asStateFlow()

    fun toggleDevMode() {
        _isDevMode.value = !_isDevMode.value
    }

    val walletServiceUrl: String
        get() = if (_isDevMode.value) DEV_WALLET_SERVICE else PROD_WALLET_SERVICE

    val issuanceServiceUrl: String
        get() = if (_isDevMode.value) DEV_ISSUANCE_SERVICE else PROD_ISSUANCE_SERVICE
} 
