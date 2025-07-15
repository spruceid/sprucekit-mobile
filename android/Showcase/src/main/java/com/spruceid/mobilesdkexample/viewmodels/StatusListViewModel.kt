package com.spruceid.mobilesdkexample.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.spruceid.mobile.sdk.CredentialPack
import com.spruceid.mobile.sdk.CredentialStatusList
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class StatusListViewModel @Inject constructor(
    application: Application
) : AndroidViewModel(application) {
    private val _statusLists = MutableStateFlow(mutableMapOf<UUID, CredentialStatusList>())
    private val _hasConnection = MutableStateFlow(true)
    private val hasConnection = _hasConnection.asStateFlow()

    private suspend fun fetchStatus(credentialPack: CredentialPack): CredentialStatusList {
        val statusLists = credentialPack.getStatusListsAsync(hasConnection.value)

        return if (statusLists.isEmpty()) {
            CredentialStatusList.UNDEFINED
        } else {
            statusLists.entries.first().value
        }
    }

    suspend fun fetchAndUpdateStatus(credentialPack: CredentialPack) {
        val tmpStatusLists = _statusLists.value.toMutableMap()
        tmpStatusLists[credentialPack.id()] = fetchStatus(credentialPack)
        _statusLists.value = tmpStatusLists
    }

    fun observeStatusForId(credentialPackId: UUID): StateFlow<CredentialStatusList?> {
        return _statusLists.map { it[credentialPackId] }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.Lazily,
                initialValue = null
            )
    }

    fun getStatus(credentialPack: CredentialPack): CredentialStatusList? {
        return _statusLists.value[credentialPack.id()]
    }

    fun getStatusLists(credentialPacks: List<CredentialPack>) {
        CoroutineScope(Dispatchers.IO).launch {
            val tmpMap = mutableMapOf<UUID, CredentialStatusList>()
            credentialPacks.forEach { credentialPack ->
                tmpMap[credentialPack.id()] = fetchStatus(credentialPack)
            }
            _statusLists.value = tmpMap
        }
    }

    fun setHasConnection(connected: Boolean) {
        _hasConnection.value = connected
    }
}