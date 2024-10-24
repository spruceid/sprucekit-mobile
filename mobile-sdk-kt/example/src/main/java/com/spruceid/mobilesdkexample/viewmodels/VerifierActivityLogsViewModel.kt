package com.spruceid.mobilesdkexample.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.spruceid.mobilesdkexample.db.VerificationActivityLogs
import com.spruceid.mobilesdkexample.db.VerificationActivityLogsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class LogsViewModel(private val verificationActivityLogsRepository: VerificationActivityLogsRepository) :
    ViewModel() {
    private val _verificationActivityLogs = MutableStateFlow(listOf<VerificationActivityLogs>())
    val verificationActivityLogs = _verificationActivityLogs.asStateFlow()

    init {
        viewModelScope.launch {
            _verificationActivityLogs.value =
                verificationActivityLogsRepository.verificationActivityLogs
        }
    }

    suspend fun saveVerificationActivityLog(verificationActivityLogs: VerificationActivityLogs) {
        verificationActivityLogsRepository.insertVerificationActivityLog(verificationActivityLogs)
        _verificationActivityLogs.value =
            verificationActivityLogsRepository.getVerificationActivityLogs()
    }

    fun generateVerificationActivityLogCSV(): String {
        val heading = "ID, Full Name, Credential Title, Permit Expiration, Status, Date\n"
        return heading +
                verificationActivityLogs.value.joinToString("\n") {
                    "${it.id}, ${it.name}, ${it.credentialTitle}, ${it.expirationDate}, ${it.status}, ${it.date}"
                }
    }
}

class LogsViewModelFactory(private val repository: VerificationActivityLogsRepository) :
    ViewModelProvider.NewInstanceFactory() {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = LogsViewModel(repository) as T
}
