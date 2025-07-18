package com.spruceid.mobilesdkexample.viewmodels

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spruceid.mobile.sdk.AppAttestation
import com.spruceid.mobile.sdk.KeyManager
import com.spruceid.mobile.sdk.rs.FlowState
import com.spruceid.mobile.sdk.rs.IssuanceServiceClient
import com.spruceid.mobile.sdk.rs.WalletServiceClient
import com.spruceid.mobilesdkexample.DEFAULT_SIGNING_KEY_ID
import com.spruceid.mobilesdkexample.config.EnvironmentConfig
import com.spruceid.mobilesdkexample.db.HacApplications
import com.spruceid.mobilesdkexample.db.HacApplicationsRepository
import com.spruceid.mobilesdkexample.utils.Toast
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.CancellationException
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException as KotlinCancellationException

@HiltViewModel
class HacApplicationsViewModel @Inject constructor(
    application: Application,
    private val hacApplicationsRepository: HacApplicationsRepository
) : ViewModel() {
    private val _hacApplications = MutableStateFlow(listOf<HacApplications>())
    val hacApplications = _hacApplications.asStateFlow()

    private val _issuanceStates = MutableStateFlow<Map<String, FlowState>>(emptyMap())
    val issuanceStates = _issuanceStates.asStateFlow()

    private var _walletServiceClient: WalletServiceClient? = null
    private var _issuanceClient: IssuanceServiceClient? = null

    private val walletServiceClient: WalletServiceClient
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
            EnvironmentConfig.isDevMode.collect {
                // Reset clients when dev mode changes
                _walletServiceClient = null
                _issuanceClient = null
            }
        }
    }

    suspend fun updateIssuanceState(hacId: String, issuanceId: String) {
        try {
            val walletAttestation = getWalletAttestation()
            if (walletAttestation != null) {
                val status = issuanceClient.checkStatus(
                    issuanceId = issuanceId,
                    walletAttestation = walletAttestation
                )

                val currentStates = _issuanceStates.value.toMutableMap()
                currentStates[hacId] = status
                _issuanceStates.value = currentStates
            }
        } catch (e: Exception) {
            println("Error updating issuance state for $hacId: ${e.message}")
        }
    }

    suspend fun updateAllIssuanceStates() {
        val applications = _hacApplications.value
        val walletAttestation = getWalletAttestation()

        if (walletAttestation != null) {
            val newStates = mutableMapOf<String, FlowState>()

            applications.forEach { application ->
                try {
                    val status = issuanceClient.checkStatus(
                        issuanceId = application.issuanceId,
                        walletAttestation = walletAttestation
                    )
                    newStates[application.id] = status
                } catch (e: Exception) {
                    println("Error updating issuance state for ${application.id}: ${e.message}")
                }
            }

            _issuanceStates.value = newStates
        }
    }

    private fun clearIssuanceState(hacId: String) {
        val currentStates = _issuanceStates.value.toMutableMap()
        currentStates.remove(hacId)
        _issuanceStates.value = currentStates
    }

    private fun getSigningJwk(): String? {
        val keyId = DEFAULT_SIGNING_KEY_ID
        if (!keyManager.keyExists(keyId)) {
            keyManager.generateSigningKey(keyId)
        }
        return keyManager.getJwk(keyId)
    }

    private suspend fun getNonce(): String? {
        return withContext(Dispatchers.IO) {
            try {
                walletServiceClient.nonce()
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

    suspend fun getWalletAttestation(): String? {
        return try {
            if (walletServiceClient.isTokenValid()) {
                walletServiceClient.getToken()
            } else {
                val attestation = AppAttestation(context)
                val nonce = getNonce() ?: throw Exception("Failed to get nonce")

                getSigningJwk()

                val payload = suspendCancellableCoroutine { continuation ->
                    if (!continuation.isActive) {
                        return@suspendCancellableCoroutine
                    }
                    attestation.appAttest(nonce, DEFAULT_SIGNING_KEY_ID) { result ->
                        result.fold(
                            onSuccess = { payload ->
                                if (continuation.isActive) {
                                    continuation.resume(payload)
                                }
                            },
                            onFailure = { error ->
                                if (continuation.isActive) {
                                    continuation.resumeWithException(error)
                                }
                            }
                        )
                    }
                }
                withContext(Dispatchers.IO) {
                    walletServiceClient.login(payload)
                }
            }
        } catch (e: CancellationException) {
            null
        } catch (e: KotlinCancellationException) {
            null
        } catch (e: Exception) {
            if (!e.message.orEmpty().contains("composition") &&
                !e.message.orEmpty().contains("scope left")
            ) {
                e.localizedMessage?.let { Toast.showError(it) }
            }
            null
        }
    }

    suspend fun saveApplication(application: HacApplications): String {
        val id = hacApplicationsRepository.insertApplication(application)
        _hacApplications.value = hacApplicationsRepository.getApplications()

        updateIssuanceState(application.id, application.issuanceId)

        return id
    }

    fun getApplicationByIssuanceId(issuanceId: String): HacApplications? {
        return hacApplicationsRepository.getApplications()
            .find { it.issuanceId == issuanceId }
    }

    fun deleteAllApplications() {
        hacApplicationsRepository.deleteAllApplications()
        _hacApplications.value = hacApplicationsRepository.getApplications()
        _issuanceStates.value = emptyMap()
    }

    fun deleteApplication(id: String) {
        hacApplicationsRepository.deleteApplication(id)
        _hacApplications.value = hacApplicationsRepository.getApplications()
        clearIssuanceState(id)
    }
} 