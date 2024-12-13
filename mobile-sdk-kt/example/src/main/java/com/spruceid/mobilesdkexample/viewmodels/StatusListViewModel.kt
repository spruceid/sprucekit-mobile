package com.spruceid.mobilesdkexample.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.spruceid.mobile.sdk.CredentialPack
import com.spruceid.mobile.sdk.CredentialStatusList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

class StatusListViewModel(application: Application) : AndroidViewModel(application) {
    private val _statusLists = MutableStateFlow(mutableMapOf<UUID, CredentialStatusList>())
    val statusLists = _statusLists.asStateFlow()
    private val _hasConnection = MutableStateFlow(true)
    val hasConnection = _hasConnection.asStateFlow()

    suspend fun fetchStatus(credentialPack: CredentialPack): CredentialStatusList {
        val statusLists = credentialPack.getStatusListsAsync(hasConnection.value)

        if (statusLists.isEmpty()) {
            return CredentialStatusList.UNDEFINED
        } else {
            return statusLists.entries.first().value
        }
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