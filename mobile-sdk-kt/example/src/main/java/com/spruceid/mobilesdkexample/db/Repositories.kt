package com.spruceid.mobilesdkexample.db

import androidx.annotation.WorkerThread

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
    val verificationMethods: List<VerificationMethods> = verificationMethodsDao.getAllVerificationMethods()

    @WorkerThread
    suspend fun insertVerificationMethod(verificationMethod: VerificationMethods) {
        verificationMethodsDao.insertVerificationMethod(verificationMethod)
    }

    @WorkerThread
    suspend fun getVerificationMethods(): List<VerificationMethods> {
        return verificationMethodsDao.getAllVerificationMethods()
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