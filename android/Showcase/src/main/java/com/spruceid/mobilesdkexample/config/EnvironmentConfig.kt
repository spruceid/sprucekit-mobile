package com.spruceid.mobilesdkexample.config

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object EnvironmentConfig {
    private const val PROD_PROOFING_CLIENT = "https://proofing.haci.spruceid.xyz"
    private const val PROD_WALLET_SERVICE = "https://wallet.haci.spruceid.xyz"
    private const val PROD_ISSUANCE_SERVICE = "https://issuance.haci.spruceid.xyz"

    private const val DEV_PROOFING_CLIENT = "https://proofing.haci.staging.spruceid.xyz"
    private const val DEV_WALLET_SERVICE = "https://wallet.haci.staging.spruceid.xyz"
    private const val DEV_ISSUANCE_SERVICE = "https://issuance.haci.staging.spruceid.xyz"

    private val _isDevMode = MutableStateFlow(false)
    val isDevMode: StateFlow<Boolean> = _isDevMode.asStateFlow()

    fun toggleDevMode() {
        _isDevMode.value = !_isDevMode.value
    }

    val proofingClientUrl: String
        get() = if (_isDevMode.value) DEV_PROOFING_CLIENT else PROD_PROOFING_CLIENT

    val walletServiceUrl: String
        get() = if (_isDevMode.value) DEV_WALLET_SERVICE else PROD_WALLET_SERVICE

    val issuanceServiceUrl: String
        get() = if (_isDevMode.value) DEV_ISSUANCE_SERVICE else PROD_ISSUANCE_SERVICE
} 