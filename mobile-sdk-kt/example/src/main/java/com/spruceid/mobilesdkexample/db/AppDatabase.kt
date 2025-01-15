package com.spruceid.mobilesdkexample.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        WalletActivityLogs::class,
        VerificationActivityLogs::class,
        RawCredentials::class,
        VerificationMethods::class
    ],
    version = 6
)
@TypeConverters(*[DateConverter::class])
abstract class AppDatabase : RoomDatabase() {
    abstract fun walletActivityLogsDao(): WalletActivityLogsDao
    abstract fun verificationActivityLogsDao(): VerificationActivityLogsDao
    abstract fun rawCredentialsDao(): RawCredentialsDao
    abstract fun verificationMethodsDao(): VerificationMethodsDao

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
