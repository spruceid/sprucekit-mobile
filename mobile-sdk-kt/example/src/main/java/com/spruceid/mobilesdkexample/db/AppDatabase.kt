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
        VerificationActivityLogs::class,
        RawCredentials::class,
        VerificationMethods::class
    ],
    version = 3
)
@TypeConverters(*[DateConverter::class])
abstract class AppDatabase : RoomDatabase() {
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
        database.execSQL("CREATE TABLE `verification_methods` (" +
                "`id` INTEGER NOT NULL, " +
                "`type` TEXT NOT NULL, " +
                "`name` TEXT NOT NULL, " +
                "`description` TEXT NOT NULL, " +
                "`verifierName` TEXT NOT NULL, " +
                "`url` TEXT NOT NULL, " +
                "PRIMARY KEY(`id`))")
    }
}
