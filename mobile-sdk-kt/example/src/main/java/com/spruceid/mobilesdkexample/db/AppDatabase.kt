package com.spruceid.mobilesdkexample.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
  entities = [
    VerificationActivityLogs::class,
    RawCredentials::class,
  ],
  version = 2
)
@TypeConverters(*[DateConverter::class])
abstract class AppDatabase : RoomDatabase() {
  abstract fun verificationActivityLogsDao(): VerificationActivityLogsDao
  abstract fun rawCredentialsDao(): RawCredentialsDao

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
            .allowMainThreadQueries()
            .build()
        dbInstance = instance
        instance
      }
    }
  }
}
