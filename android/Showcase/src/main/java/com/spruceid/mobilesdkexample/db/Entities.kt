package com.spruceid.mobilesdkexample.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.sql.Date

@Entity(tableName = "wallet_activity_logs")
data class WalletActivityLogs(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val credentialPackId: String,
    val credentialId: String,
    val credentialTitle: String,
    val issuer: String,
    val action: String,
    val dateTime: Date,
    val additionalInformation: String,
)

@Entity(tableName = "verification_activity_logs")
data class VerificationActivityLogs(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val credentialTitle: String,
    val issuer: String,
    val status: String,
    val verificationDateTime: Date,
    val additionalInformation: String,
)

@Entity(tableName = "raw_credentials")
data class RawCredentials(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val rawCredential: String,
)

@Entity(tableName = "verification_methods")
data class VerificationMethods(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    val name: String,
    val description: String,
    val verifierName: String,
    val url: String,
)

@Entity(tableName = "trusted_certificates")
data class TrustedCertificates(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val content: String
)

@Entity(tableName = "hac_applications")
data class HacApplications(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val issuanceId: String
)