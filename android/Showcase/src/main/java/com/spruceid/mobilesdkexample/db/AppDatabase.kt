package com.spruceid.mobilesdkexample.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.spruceid.mobilesdkexample.viewmodels.DEFAULT_TRUST_ANCHOR_IACA_SPRUCEID_HACI_PROD

@Database(
    entities = [
        WalletActivityLogs::class,
        VerificationActivityLogs::class,
        RawCredentials::class,
        VerificationMethods::class,
        TrustedCertificates::class,
        HacApplications::class
    ],
    version = 9,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 6, to = 7)
    ]
)
@TypeConverters(*[DateConverter::class])
abstract class AppDatabase : RoomDatabase() {
    abstract fun walletActivityLogsDao(): WalletActivityLogsDao
    abstract fun verificationActivityLogsDao(): VerificationActivityLogsDao
    abstract fun verificationMethodsDao(): VerificationMethodsDao
    abstract fun trustedCertificatesDao(): TrustedCertificatesDao
    abstract fun hacApplicationsDao(): HacApplicationsDao

    companion object {
        @Volatile
        private var dbInstance: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return dbInstance ?: synchronized(this) {
                val instance =
                    Room.databaseBuilder(
                        context.applicationContext,
                        AppDatabase::class.java,
                        "referenceAppDb",
                    )
                        .addMigrations(MIGRATION_2_3)
                        .addMigrations(MIGRATION_3_4)
                        .addMigrations(MIGRATION_4_5)
                        .addMigrations(MIGRATION_5_6)
                        .addMigrations(MIGRATION_7_8)
                        .addMigrations(MIGRATION_8_9)
                        .allowMainThreadQueries()
                        .build()
                dbInstance = instance
                instance
            }
        }
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            "CREATE TABLE `verification_methods` (" +
                    "`id` INTEGER NOT NULL, " +
                    "`type` TEXT NOT NULL, " +
                    "`name` TEXT NOT NULL, " +
                    "`description` TEXT NOT NULL, " +
                    "`verifierName` TEXT NOT NULL, " +
                    "`url` TEXT NOT NULL, " +
                    "PRIMARY KEY(`id`))"
        )
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("DROP TABLE verification_activity_logs")
        database.execSQL(
            "CREATE TABLE `verification_activity_logs` (" +
                    "`id` INTEGER NOT NULL, " +
                    "`credentialTitle` TEXT NOT NULL, " +
                    "`issuer` TEXT NOT NULL, " +
                    "`verificationDateTime` INTEGER NOT NULL, " +
                    "`additionalInformation` TEXT NOT NULL, " +
                    "PRIMARY KEY(`id`))"
        )
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            "CREATE TABLE `wallet_activity_logs` (" +
                    "`id` INTEGER NOT NULL, " +
                    "`credentialPackId` TEXT NOT NULL, " +
                    "`credentialId` TEXT NOT NULL, " +
                    "`credentialTitle` TEXT NOT NULL, " +
                    "`issuer` TEXT NOT NULL, " +
                    "`action` TEXT NOT NULL, " +
                    "`dateTime` INTEGER NOT NULL, " +
                    "`additionalInformation` TEXT NOT NULL, " +
                    "PRIMARY KEY(`id`))"
        )
    }
}

val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            "ALTER TABLE `verification_activity_logs` " +
                    "ADD COLUMN `status` TEXT NOT NULL DEFAULT 'UNDEFINED'"
        )
    }
}

val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `hac_applications` (
                `id` TEXT NOT NULL,
                `issuanceId` TEXT NOT NULL,
                PRIMARY KEY(`id`)
            )
        """.trimIndent()
        )
    }
}

val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(database: SupportSQLiteDatabase) {
        // Insert the new Spruce HACI prod certificate only if it doesn't exist
        database.execSQL(
            """
                INSERT OR IGNORE INTO trusted_certificates (name, content)
                VALUES (
                    '${DEFAULT_TRUST_ANCHOR_IACA_SPRUCEID_HACI_PROD.name}',
                    '${DEFAULT_TRUST_ANCHOR_IACA_SPRUCEID_HACI_PROD.content}'
                )
            """.trimIndent()
        )
    }
}
