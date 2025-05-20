package com.spruceid.mobilesdkexample.viewmodels

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.spruceid.mobile.sdk.AppAttestation
import com.spruceid.mobile.sdk.KeyManager
import com.spruceid.mobile.sdk.rs.IssuanceServiceClient
import com.spruceid.mobile.sdk.rs.WalletServiceClient
import com.spruceid.mobilesdkexample.DEFAULT_SIGNING_KEY_ID
import com.spruceid.mobilesdkexample.config.EnvironmentConfig
import com.spruceid.mobilesdkexample.db.HacApplications
import com.spruceid.mobilesdkexample.db.HacApplicationsRepository
import com.spruceid.mobilesdkexample.utils.Toast
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class HacApplicationsViewModel(
    application: Application,
    private val hacApplicationsRepository: HacApplicationsRepository
) :
    ViewModel() {
    private val _hacApplications = MutableStateFlow(listOf<HacApplications>())
    val hacApplications = _hacApplications.asStateFlow()

    private var _walletServiceClient: WalletServiceClient? = null
    private var _issuanceClient: IssuanceServiceClient? = null

    val walletServiceClient: WalletServiceClient
        get() {
            if (_walletServiceClient == null) {
                _walletServiceClient = WalletServiceClient(EnvironmentConfig.walletServiceUrl)
            }
            return _walletServiceClient!!
        }

    val issuanceClient: IssuanceServiceClient
        get() {
            if (_issuanceClient == null) {
                _issuanceClient = IssuanceServiceClient(EnvironmentConfig.issuanceServiceUrl)
            }
            return _issuanceClient!!
        }

    private val keyManager = KeyManager()
    private val context = application as Context

    init {
        viewModelScope.launch {
            _hacApplications.value = hacApplicationsRepository.hacApplications

            // Observe changes in dev mode
            EnvironmentConfig.isDevMode.collect { isDevMode ->
                // Reset clients when dev mode changes
                _walletServiceClient = null
                _issuanceClient = null
            }
        }
    }

    fun getSigningJwk(): String? {
        val keyId = DEFAULT_SIGNING_KEY_ID
        if (!keyManager.keyExists(keyId)) {
            keyManager.generateSigningKey(keyId)
        }
        return keyManager.getJwk(keyId)
    }

    suspend fun getNonce(): String? {
        try {
            return walletServiceClient.nonce()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    suspend fun getWalletAttestation(): String? {
        return try {
            if (walletServiceClient.isTokenValid()) {
                walletServiceClient.getToken()
            } else {
                val attestation = AppAttestation(context)
                val nonce = getNonce() ?: throw Exception("Failed to get nonce")

                getSigningJwk()

                suspendCancellableCoroutine { continuation ->
                    attestation.appAttest(nonce, DEFAULT_SIGNING_KEY_ID) { result ->
                        result.fold(
                            onSuccess = { payload ->
                                viewModelScope.launch {
                                    try {
                                        continuation.resume(walletServiceClient.login(payload))
                                    } catch (e: Exception) {
                                        continuation.resumeWithException(e)
                                    }
                                }
                            },
                            onFailure = { error ->
                                error.printStackTrace()
                                continuation.resumeWithException(error)
                            }
                        )
                    }
                }
            }
        } catch (e: Exception) {
            e.localizedMessage?.let { Toast.showError(it) }
            null
        }
    }

    suspend fun saveApplication(application: HacApplications): String {
        val id = hacApplicationsRepository.insertApplication(application)
        _hacApplications.value = hacApplicationsRepository.getApplications()
        return id
    }

    suspend fun getApplication(id: String): HacApplications {
        return hacApplicationsRepository.getApplication(id)
    }

    suspend fun deleteAllApplications() {
        hacApplicationsRepository.deleteAllApplications()
        _hacApplications.value = hacApplicationsRepository.getApplications()
    }

    suspend fun deleteApplication(id: String) {
        hacApplicationsRepository.deleteApplication(id)
        _hacApplications.value = hacApplicationsRepository.getApplications()
    }
}

class HacApplicationsViewModelFactory(
    private val application: Application,
    private val repository: HacApplicationsRepository
) :
    ViewModelProvider.NewInstanceFactory() {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        HacApplicationsViewModel(application, repository) as T
} 