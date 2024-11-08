package com.spruceid.mobilesdkexample.viewmodels

import StorageManager
import android.app.Application
import android.content.Context
import androidx.compose.runtime.toMutableStateList
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.spruceid.mobile.sdk.CredentialPack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class CredentialPacksViewModel(application: Application) : AndroidViewModel(application) {
    private val storageManager = StorageManager(context = (application as Context))
    private val _credentialPacks = MutableStateFlow(listOf<CredentialPack>())
    val credentialPacks = _credentialPacks.asStateFlow()

    init {
        viewModelScope.launch {
            _credentialPacks.value = CredentialPack.loadPacks(storageManager)
        }
    }

    fun saveCredentialPack(credentialPack: CredentialPack) {
        credentialPack.save(storageManager)
        val tmpCredentialPacksList = _credentialPacks.value.toMutableStateList()
        tmpCredentialPacksList.add(credentialPack)
        _credentialPacks.value = tmpCredentialPacksList
    }

    fun deleteAllCredentialPacks() {
        _credentialPacks.value.forEach { credentialPack ->
            credentialPack.remove(storageManager)
        }
        _credentialPacks.value = emptyList()
    }

    fun deleteCredentialPack(credentialPack: CredentialPack) {
        credentialPack.remove(storageManager)
        val tmpCredentialPacksList = _credentialPacks.value.toMutableStateList()
        tmpCredentialPacksList.remove(credentialPack)
        _credentialPacks.value = tmpCredentialPacksList
    }
}

class CredentialPacksViewModelFactory(private val application: Application) :
    ViewModelProvider.NewInstanceFactory() {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        CredentialPacksViewModel(application) as T
}