package com.spruceid.mobilesdkexample.viewmodels

import StorageManager
import android.app.Application
import android.content.Context
import androidx.compose.runtime.toMutableStateList
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.spruceid.mobile.sdk.CredentialPack
import com.spruceid.mobile.sdk.dcapi.Registry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CredentialPacksViewModel @Inject constructor(
    application: Application,
    private val dcApiRegistry: Registry
) : AndroidViewModel(application) {
    private val storageManager = StorageManager(context = (application as Context))
    private val _credentialPacks = MutableStateFlow(listOf<CredentialPack>())
    val credentialPacks = _credentialPacks.asStateFlow()
    private val _loading = MutableStateFlow(false)
    val loading = _loading.asStateFlow()

    init {
        viewModelScope.launch {
            _loading.value = true
            this.async(Dispatchers.Default) {
                _credentialPacks.value = CredentialPack.loadPacks(storageManager)
                dcApiRegistry.register(credentialPacks.value)
            }.await()
            _loading.value = false

            // Listen for credential pack updates and update the registry.
            _credentialPacks.collect { packs -> dcApiRegistry.register(packs) }
        }
    }

    suspend fun saveCredentialPack(credentialPack: CredentialPack) {
        credentialPack.save(storageManager)
        val tmpCredentialPacksList = _credentialPacks.value.toMutableStateList()
        tmpCredentialPacksList.add(credentialPack)
        _credentialPacks.value = tmpCredentialPacksList
    }

    suspend fun deleteAllCredentialPacks(onDeleteCredentialPack: (suspend (CredentialPack) -> Unit)? = null) {
        _credentialPacks.value.forEach { credentialPack ->
            credentialPack.remove(storageManager)
            onDeleteCredentialPack?.invoke(credentialPack)
        }
        _credentialPacks.value = emptyList()
    }

    suspend fun deleteCredentialPack(credentialPack: CredentialPack) {
        credentialPack.remove(storageManager)
        val tmpCredentialPacksList = _credentialPacks.value.toMutableStateList()
        tmpCredentialPacksList.remove(credentialPack)
        _credentialPacks.value = tmpCredentialPacksList
    }

    fun getById(credentialPackId: String): CredentialPack? {
        return _credentialPacks.value.firstOrNull { credentialPack ->
            credentialPack.id().toString() == credentialPackId
        }
    }
}