package com.spruceid.mobilesdkexample.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.spruceid.mobile.sdk.KeyManager
import com.spruceid.mobile.sdk.rs.IssuanceServiceClient
import com.spruceid.mobile.sdk.rs.WalletServiceClient
import com.spruceid.mobilesdkexample.db.HacApplications
import com.spruceid.mobilesdkexample.db.HacApplicationsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

const val SPRUCEID_HAC_PROOFING_CLIENT = "https://proofing.haci.staging.spruceid.xyz"
const val SPRUCEID_HAC_WALLET_SERVICE = "https://wallet.haci.staging.spruceid.xyz"
const val SPRUCEID_HAC_ISSUANCE_SERVICE = "https://issuance.haci.staging.spruceid.xyz"

class HacApplicationsViewModel(private val hacApplicationsRepository: HacApplicationsRepository) :
    ViewModel() {
    private val _hacApplications = MutableStateFlow(listOf<HacApplications>())
    val hacApplications = _hacApplications.asStateFlow()
    val walletServiceClient = WalletServiceClient(SPRUCEID_HAC_WALLET_SERVICE)
    val issuanceClient = IssuanceServiceClient(SPRUCEID_HAC_ISSUANCE_SERVICE)
    private val keyManager = KeyManager()

    init {
        viewModelScope.launch {
            _hacApplications.value = hacApplicationsRepository.hacApplications
        }
    }

    suspend fun getWalletAttestation(): String? {
        return try {
            if (walletServiceClient.isTokenValid()) {
                walletServiceClient.getToken()
            } else {
                val keyId = "reference-app/default-signing"
                keyManager.generateSigningKey(keyId)
                val jwk = keyManager.getJwk(keyId)
                jwk?.let { walletServiceClient.login(it) }
            }
        } catch (e: Exception) {
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

class HacApplicationsViewModelFactory(private val repository: HacApplicationsRepository) :
    ViewModelProvider.NewInstanceFactory() {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        HacApplicationsViewModel(repository) as T
} 