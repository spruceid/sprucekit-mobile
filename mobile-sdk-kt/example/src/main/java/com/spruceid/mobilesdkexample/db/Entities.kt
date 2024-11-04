package com.spruceid.mobilesdkexample.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.sql.Date

@Entity(tableName = "verification_activity_logs")
data class VerificationActivityLogs(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val credentialTitle: String,
    val date: Date,
    val expirationDate: Date,
    val status: String,
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