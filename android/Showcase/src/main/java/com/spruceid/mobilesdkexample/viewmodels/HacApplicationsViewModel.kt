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
import com.spruceid.mobilesdkexample.db.HacApplications
import com.spruceid.mobilesdkexample.db.HacApplicationsRepository
import com.spruceid.mobilesdkexample.utils.Toast
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

const val SPRUCEID_HAC_PROOFING_CLIENT = "https://proofing.haci.staging.spruceid.xyz"
const val SPRUCEID_HAC_WALLET_SERVICE = "https://wallet.haci.staging.spruceid.xyz"
const val SPRUCEID_HAC_ISSUANCE_SERVICE = "https://issuance.haci.staging.spruceid.xyz"

class HacApplicationsViewModel(
    application: Application,
    private val hacApplicationsRepository: HacApplicationsRepository
) :
    ViewModel() {
    private val _hacApplications = MutableStateFlow(listOf<HacApplications>())
    val hacApplications = _hacApplications.asStateFlow()
    val walletServiceClient = WalletServiceClient(SPRUCEID_HAC_WALLET_SERVICE)
    val issuanceClient = IssuanceServiceClient(SPRUCEID_HAC_ISSUANCE_SERVICE)
    private val keyManager = KeyManager()
    private val context = application as Context
    private val signingKeyAlias = "reference-app/default-signing"

    init {
        viewModelScope.launch {
            _hacApplications.value = hacApplicationsRepository.hacApplications
        }
    }

    fun getSigningJwk(): String? {
        val keyId = signingKeyAlias
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
                    attestation.appAttest(nonce, signingKeyAlias) { result ->
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