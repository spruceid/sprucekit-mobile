package com.spruceid.mobilesdkexample.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.spruceid.mobilesdkexample.db.RawCredentials
import com.spruceid.mobilesdkexample.db.RawCredentialsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

abstract class IRawCredentialsViewModel : ViewModel() {
    abstract val rawCredentials: StateFlow<List<RawCredentials>>
    abstract suspend fun saveRawCredential(rawCredential: RawCredentials)
    abstract suspend fun deleteAllRawCredentials()
    abstract suspend fun deleteRawCredential(id: Long)
    abstract fun generateRawCredentialsCSV(): String
}

class RawCredentialsViewModel(private val rawCredentialsRepository: RawCredentialsRepository) :
    IRawCredentialsViewModel() {
    private val _rawCredentials = MutableStateFlow(listOf<RawCredentials>())
    override val rawCredentials = _rawCredentials.asStateFlow()

    init {
        viewModelScope.launch {
            _rawCredentials.value = rawCredentialsRepository.rawCredentials
        }
    }

    override suspend fun saveRawCredential(rawCredential: RawCredentials) {
        rawCredentialsRepository.insertRawCredential(rawCredential)
        _rawCredentials.value = rawCredentialsRepository.getRawCredentials()
    }

    override suspend fun deleteAllRawCredentials() {
        rawCredentialsRepository.deleteAllRawCredentials()
        _rawCredentials.value = rawCredentialsRepository.getRawCredentials()
    }

    override suspend fun deleteRawCredential(id: Long) {
        rawCredentialsRepository.deleteRawCredential(id = id)
        _rawCredentials.value = rawCredentialsRepository.getRawCredentials()
    }

    override fun generateRawCredentialsCSV(): String {
        val heading = "ID, Raw Credential\n"
        return heading +
                rawCredentials.value.joinToString("\n") {
                    "${it.id}, ${it.rawCredential}"
                }
    }
}

class RawCredentialsViewModelFactory(private val repository: RawCredentialsRepository) :
    ViewModelProvider.NewInstanceFactory() {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        RawCredentialsViewModel(repository) as T
}

class RawCredentialsViewModelPreview(
    override val rawCredentials: StateFlow<List<RawCredentials>> = MutableStateFlow(
        emptyList()
    )
) : IRawCredentialsViewModel() {
    override suspend fun saveRawCredential(credential: RawCredentials) {}

    override suspend fun deleteAllRawCredentials() {}

    override suspend fun deleteRawCredential(id: Long) {}

    override fun generateRawCredentialsCSV(): String {
        return ""
    }
}