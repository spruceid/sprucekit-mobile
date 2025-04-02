package com.spruceid.mobilesdkexample.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface WalletActivityLogsDao {
    @Insert
    suspend fun insertWalletActivity(walletActivityLogs: WalletActivityLogs)

    @Query("SELECT * FROM wallet_activity_logs ORDER BY dateTime DESC")
    fun getAllWalletActivityLogs(): List<WalletActivityLogs>
}

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

@Dao
interface TrustedCertificatesDao {
    @Insert
    suspend fun insertCertificate(certificate: TrustedCertificates)

    @Query("SELECT * FROM trusted_certificates")
    fun getAllCertificates(): List<TrustedCertificates>

    @Query("SELECT * FROM trusted_certificates WHERE id = :id")
    fun getCertificate(id: Long): TrustedCertificates

    @Query("DELETE FROM trusted_certificates")
    fun deleteAllCertificates(): Int

    @Query("DELETE FROM trusted_certificates WHERE id = :id")
    fun deleteCertificate(id: Long): Int
}

@Dao
interface HacApplicationsDao {
    @Insert
    suspend fun insertApplication(application: HacApplications)

    @Query("SELECT * FROM hac_applications")
    fun getAllApplications(): List<HacApplications>

    @Query("SELECT * FROM hac_applications WHERE id = :id")
    fun getApplication(id: String): HacApplications

    @Query("DELETE FROM hac_applications")
    fun deleteAllApplications(): Int

    @Query("DELETE FROM hac_applications WHERE id = :id")
    fun deleteApplication(id: String): Int
}
