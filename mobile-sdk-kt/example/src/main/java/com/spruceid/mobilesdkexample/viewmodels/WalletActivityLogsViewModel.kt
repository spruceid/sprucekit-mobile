package com.spruceid.mobilesdkexample.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.spruceid.mobilesdkexample.db.WalletActivityLogs
import com.spruceid.mobilesdkexample.db.WalletActivityLogsRepository
import com.spruceid.mobilesdkexample.utils.formatSqlDateTime
import com.spruceid.mobilesdkexample.utils.removeCommas
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class WalletActivityLogsViewModel(private val walletActivityLogsRepository: WalletActivityLogsRepository) :
    ViewModel() {
    private val _walletActivityLogs = MutableStateFlow(listOf<WalletActivityLogs>())
    val walletActivityLogs = _walletActivityLogs.asStateFlow()

    init {
        viewModelScope.launch {
            _walletActivityLogs.value =
                walletActivityLogsRepository.walletActivityLogs
        }
    }

    suspend fun saveWalletActivityLog(walletActivityLogs: WalletActivityLogs) {
        walletActivityLogsRepository.insertWalletActivityLog(walletActivityLogs)
        _walletActivityLogs.value =
            walletActivityLogsRepository.getWalletActivityLogs()
    }

    fun generateWalletActivityLogCSV(logs: List<WalletActivityLogs>? = null): String {
        val heading =
            "ID, Credential Pack Id, Credential Id, Credential Title, Issuer, Action, Date Time, Additional Information\n"

        val rows = logs?.joinToString("\n") {
            "${it.id}, " +
                    "${it.credentialPackId}, " +
                    "${it.credentialId}, " +
                    "${it.credentialTitle.removeCommas()}, " +
                    "${it.issuer.removeCommas()}, " +
                    "${it.action}, " +
                    "${formatSqlDateTime(it.dateTime).removeCommas()}, " +
                    it.additionalInformation
        }
            ?: walletActivityLogs.value.joinToString("\n") {
                "${it.id}, " +
                        "${it.credentialPackId}, " +
                        "${it.credentialId}, " +
                        "${it.credentialTitle.removeCommas()}, " +
                        "${it.issuer.removeCommas()}, " +
                        "${it.action}, " +
                        "${formatSqlDateTime(it.dateTime).removeCommas()}, " +
                        it.additionalInformation
            }

        return heading + rows
    }
}

class WalletActivityLogsViewModelFactory(private val repository: WalletActivityLogsRepository) :
    ViewModelProvider.NewInstanceFactory() {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        WalletActivityLogsViewModel(repository) as T
}
