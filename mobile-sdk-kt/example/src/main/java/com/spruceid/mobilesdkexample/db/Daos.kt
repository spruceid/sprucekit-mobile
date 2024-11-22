package com.spruceid.mobilesdkexample.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface VerificationActivityLogsDao {
    @Insert
    suspend fun insertVerificationActivity(verificationActivityLogs: VerificationActivityLogs)

    @Query("SELECT * FROM verification_activity_logs ORDER BY verificationDateTime DESC")
    fun getAllVerificationActivityLogs(): List<VerificationActivityLogs>

    @Query(
        "SELECT * FROM verification_activity_logs " +
                "WHERE verificationDateTime > :fromDate " +
                "ORDER BY verificationDateTime DESC"
    )
    fun getFilteredVerificationActivityLogs(fromDate: Long): List<VerificationActivityLogs>

    @Query("SELECT DISTINCT credentialTitle FROM verification_activity_logs")
    fun getDistinctCredentialTitles(): List<String>
}

@Dao
interface RawCredentialsDao {
    @Insert
    suspend fun insertRawCredential(rawCredential: RawCredentials)

    @Query("SELECT * FROM raw_credentials")
    fun getAllRawCredentials(): List<RawCredentials>

    @Query("DELETE FROM raw_credentials")
    fun deleteAllRawCredentials(): Int

    @Query("DELETE FROM raw_credentials WHERE id = :id")
    fun deleteRawCredential(id: Long): Int
}

@Dao
interface VerificationMethodsDao {
    @Insert
    suspend fun insertVerificationMethod(verificationMethod: VerificationMethods)

    @Query("SELECT * FROM verification_methods")
    fun getAllVerificationMethods(): List<VerificationMethods>

    @Query("SELECT * FROM verification_methods WHERE id = :id")
    fun getVerificationMethod(id: Long): VerificationMethods

    @Query("DELETE FROM verification_methods")
    fun deleteAllVerificationMethods(): Int

    @Query("DELETE FROM verification_methods WHERE id = :id")
    fun deleteVerificationMethod(id: Long): Int
}
