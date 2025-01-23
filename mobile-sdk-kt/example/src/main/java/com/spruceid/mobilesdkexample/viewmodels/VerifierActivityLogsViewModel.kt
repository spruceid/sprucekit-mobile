package com.spruceid.mobilesdkexample.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.spruceid.mobilesdkexample.db.VerificationActivityLogs
import com.spruceid.mobilesdkexample.db.VerificationActivityLogsRepository
import com.spruceid.mobilesdkexample.utils.formatSqlDateTime
import com.spruceid.mobilesdkexample.utils.removeCommas
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class VerificationActivityLogsViewModel(private val verificationActivityLogsRepository: VerificationActivityLogsRepository) :
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

    // TODO: Add fromDate and credentialType filter params
    fun getFilteredVerificationActivityLog() {
        verificationActivityLogsRepository.getFilteredVerificationActivityLogs()
    }

    fun getDistinctCredentialTitles(): List<String> {
        return verificationActivityLogsRepository.getDistinctCredentialTitles()
    }

    fun generateVerificationActivityLogCSV(logs: List<VerificationActivityLogs>? = null): String {
        val heading =
            "ID, Credential Title, Issuer, Status, Verification Date Time, Additional Information\n"

        val rows = logs?.joinToString("\n") {
            "${it.id}, " +
                    "${it.credentialTitle}, " +
                    "${it.issuer}, " +
                    "${it.status}, " +
                    "${formatSqlDateTime(it.verificationDateTime).removeCommas()}, " +
                    it.additionalInformation
        }
            ?: verificationActivityLogs.value.joinToString("\n") {
                "${it.id}, " +
                        "${it.credentialTitle}, " +
                        "${it.issuer}, " +
                        "${formatSqlDateTime(it.verificationDateTime).removeCommas()}, " +
                        it.additionalInformation
            }

        return heading + rows
    }
}

class VerificationActivityLogsViewModelFactory(private val repository: VerificationActivityLogsRepository) :
    ViewModelProvider.NewInstanceFactory() {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        VerificationActivityLogsViewModel(repository) as T
}
