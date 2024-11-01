package com.spruceid.mobilesdkexample.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.spruceid.mobilesdkexample.db.RawCredentials
import com.spruceid.mobilesdkexample.db.VerificationActivityLogs
import com.spruceid.mobilesdkexample.db.VerificationActivityLogsRepository
import com.spruceid.mobilesdkexample.db.VerificationMethods
import com.spruceid.mobilesdkexample.db.VerificationMethodsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class VerificationMethodsViewModel(private val verificationMethodsRepository: VerificationMethodsRepository) :
    ViewModel() {
    private val _verificationMethods = MutableStateFlow(listOf<VerificationMethods>())
    val verificationMethods = _verificationMethods.asStateFlow()

    init {
        viewModelScope.launch {
            _verificationMethods.value =
                verificationMethodsRepository.verificationMethods
        }
    }

    suspend fun saveVerificationMethod(verificationMethod: VerificationMethods) {
        verificationMethodsRepository.insertVerificationMethod(verificationMethod)
        _verificationMethods.value = verificationMethodsRepository.getVerificationMethods()
    }

    suspend fun getVerificationMethod(id: Long): VerificationMethods {
        return verificationMethodsRepository.getVerificationMethod(id)
    }

    suspend fun deleteAllVerificationMethods() {
        verificationMethodsRepository.deleteAllVerificationMethods()
        _verificationMethods.value = verificationMethodsRepository.getVerificationMethods()
    }

    suspend fun deleteVerificationMethod(id: Long) {
        verificationMethodsRepository.deleteVerificationMethod(id = id)
        _verificationMethods.value = verificationMethodsRepository.getVerificationMethods()
    }
}

class VerificationMethodsViewModelFactory(private val repository: VerificationMethodsRepository) :
    ViewModelProvider.NewInstanceFactory() {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = VerificationMethodsViewModel(repository) as T
}
