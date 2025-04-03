package com.spruceid.mobilesdkexample.db

import androidx.annotation.WorkerThread
import java.sql.Date

class WalletActivityLogsRepository(private val walletActivityLogsDao: WalletActivityLogsDao) {
    val walletActivityLogs: List<WalletActivityLogs> =
        walletActivityLogsDao.getAllWalletActivityLogs()

    @WorkerThread
    suspend fun insertWalletActivityLog(walletActivityLogs: WalletActivityLogs) {
        walletActivityLogsDao.insertWalletActivity(walletActivityLogs)
    }

    @WorkerThread
    suspend fun getWalletActivityLogs(): List<WalletActivityLogs> {
        return walletActivityLogsDao.getAllWalletActivityLogs()
    }
}

class VerificationActivityLogsRepository(private val verificationActivityLogsDao: VerificationActivityLogsDao) {
    val verificationActivityLogs: List<VerificationActivityLogs> =
        verificationActivityLogsDao.getAllVerificationActivityLogs()

    @WorkerThread
    suspend fun insertVerificationActivityLog(verificationActivityLogs: VerificationActivityLogs) {
        verificationActivityLogsDao.insertVerificationActivity(verificationActivityLogs)
    }

    @WorkerThread
    suspend fun getVerificationActivityLogs(): List<VerificationActivityLogs> {
        return verificationActivityLogsDao.getAllVerificationActivityLogs()
    }

    // TODO: Add fromDate and credentialType filter params
    @WorkerThread
    fun getFilteredVerificationActivityLogs(): List<VerificationActivityLogs> {
        return verificationActivityLogsDao.getFilteredVerificationActivityLogs(
            fromDate = Date(Long.MIN_VALUE).time
        )
    }

    @WorkerThread
    fun getDistinctCredentialTitles(): List<String> {
        return verificationActivityLogsDao.getDistinctCredentialTitles()
    }
}

class RawCredentialsRepository(private val rawCredentialsDao: RawCredentialsDao) {
    val rawCredentials: List<RawCredentials> = rawCredentialsDao.getAllRawCredentials()

    @WorkerThread
    suspend fun insertRawCredential(credential: RawCredentials) {
        rawCredentialsDao.insertRawCredential(credential)
    }

    @WorkerThread
    suspend fun getRawCredentials(): List<RawCredentials> {
        return rawCredentialsDao.getAllRawCredentials()
    }

    @WorkerThread
    suspend fun deleteAllRawCredentials(): Int {
        return rawCredentialsDao.deleteAllRawCredentials()
    }

    @WorkerThread
    suspend fun deleteRawCredential(id: Long): Int {
        return rawCredentialsDao.deleteRawCredential(id = id)
    }
}

class VerificationMethodsRepository(private val verificationMethodsDao: VerificationMethodsDao) {
    val verificationMethods: List<VerificationMethods> =
        verificationMethodsDao.getAllVerificationMethods()

    @WorkerThread
    suspend fun insertVerificationMethod(verificationMethod: VerificationMethods) {
        verificationMethodsDao.insertVerificationMethod(verificationMethod)
    }

    @WorkerThread
    suspend fun getVerificationMethods(): List<VerificationMethods> {
        return verificationMethodsDao.getAllVerificationMethods()
    }

    @WorkerThread
    suspend fun getVerificationMethod(id: Long): VerificationMethods {
        return verificationMethodsDao.getVerificationMethod(id)
    }

    @WorkerThread
    suspend fun deleteAllVerificationMethods(): Int {
        return verificationMethodsDao.deleteAllVerificationMethods()
    }

    @WorkerThread
    suspend fun deleteVerificationMethod(id: Long): Int {
        return verificationMethodsDao.deleteVerificationMethod(id = id)
    }
}

class TrustedCertificatesRepository(private val trustedCertificatesDao: TrustedCertificatesDao) {
    val trustedCertificates: List<TrustedCertificates> =
        trustedCertificatesDao.getAllCertificates()

    @WorkerThread
    suspend fun insertCertificate(certificate: TrustedCertificates) {
        trustedCertificatesDao.insertCertificate(certificate)
    }

    @WorkerThread
    fun getCertificates(): List<TrustedCertificates> {
        return trustedCertificatesDao.getAllCertificates()
    }

    @WorkerThread
    fun getCertificate(id: Long): TrustedCertificates {
        return trustedCertificatesDao.getCertificate(id)
    }

    @WorkerThread
    fun deleteAllCertificates(): Int {
        return trustedCertificatesDao.deleteAllCertificates()
    }

    @WorkerThread
    fun deleteCertificate(id: Long): Int {
        return trustedCertificatesDao.deleteCertificate(id = id)
    }
}

class HacApplicationsRepository(private val hacApplicationsDao: HacApplicationsDao) {
    val hacApplications: List<HacApplications> =
        hacApplicationsDao.getAllApplications()

    @WorkerThread
    suspend fun insertApplication(application: HacApplications): String {
        hacApplicationsDao.insertApplication(application)
        return application.id
    }

    @WorkerThread
    fun getApplications(): List<HacApplications> {
        return hacApplicationsDao.getAllApplications()
    }

    @WorkerThread
    fun getApplication(id: String): HacApplications {
        return hacApplicationsDao.getApplication(id)
    }

    @WorkerThread
    fun deleteAllApplications(): Int {
        return hacApplicationsDao.deleteAllApplications()
    }

    @WorkerThread
    fun deleteApplication(id: String): Int {
        return hacApplicationsDao.deleteApplication(id = id)
    }
}