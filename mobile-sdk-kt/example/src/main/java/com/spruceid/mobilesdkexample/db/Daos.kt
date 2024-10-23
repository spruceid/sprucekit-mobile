package com.spruceid.mobilesdkexample.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface VerificationActivityLogsDao {
  @Insert
  suspend fun insertVerificationActivity(verificationActivityLogs: VerificationActivityLogs)

  @Query("SELECT * FROM verification_activity_logs")
  fun getAllVerificationActivityLogs(): List<VerificationActivityLogs>
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
