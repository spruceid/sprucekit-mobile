package com.spruceid.mobilesdkexample.di

import android.content.Context
import androidx.room.Room
import com.spruceid.mobilesdkexample.db.AppDatabase
import com.spruceid.mobilesdkexample.db.HacApplicationsDao
import com.spruceid.mobilesdkexample.db.HacApplicationsRepository
import com.spruceid.mobilesdkexample.db.TrustedCertificatesDao
import com.spruceid.mobilesdkexample.db.TrustedCertificatesRepository
import com.spruceid.mobilesdkexample.db.VerificationActivityLogsDao
import com.spruceid.mobilesdkexample.db.VerificationActivityLogsRepository
import com.spruceid.mobilesdkexample.db.VerificationMethodsDao
import com.spruceid.mobilesdkexample.db.VerificationMethodsRepository
import com.spruceid.mobilesdkexample.db.WalletActivityLogsDao
import com.spruceid.mobilesdkexample.db.WalletActivityLogsRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    @JvmStatic
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        return AppDatabase.getDatabase(context)
    }

    @Provides
    @JvmStatic
    fun provideWalletActivityLogsDao(database: AppDatabase): WalletActivityLogsDao {
        return database.walletActivityLogsDao()
    }

    @Provides
    @JvmStatic
    fun provideVerificationActivityLogsDao(database: AppDatabase): VerificationActivityLogsDao {
        return database.verificationActivityLogsDao()
    }

    @Provides
    @JvmStatic
    fun provideVerificationMethodsDao(database: AppDatabase): VerificationMethodsDao {
        return database.verificationMethodsDao()
    }

    @Provides
    @JvmStatic
    fun provideTrustedCertificatesDao(database: AppDatabase): TrustedCertificatesDao {
        return database.trustedCertificatesDao()
    }

    @Provides
    @JvmStatic
    fun provideHacApplicationsDao(database: AppDatabase): HacApplicationsDao {
        return database.hacApplicationsDao()
    }

    @Provides
    @Singleton
    @JvmStatic
    fun provideWalletActivityLogsRepository(dao: WalletActivityLogsDao): WalletActivityLogsRepository {
        return WalletActivityLogsRepository(dao)
    }

    @Provides
    @Singleton
    @JvmStatic
    fun provideVerificationActivityLogsRepository(dao: VerificationActivityLogsDao): VerificationActivityLogsRepository {
        return VerificationActivityLogsRepository(dao)
    }

    @Provides
    @Singleton
    @JvmStatic
    fun provideVerificationMethodsRepository(dao: VerificationMethodsDao): VerificationMethodsRepository {
        return VerificationMethodsRepository(dao)
    }

    @Provides
    @Singleton
    @JvmStatic
    fun provideTrustedCertificatesRepository(dao: TrustedCertificatesDao): TrustedCertificatesRepository {
        return TrustedCertificatesRepository(dao)
    }

    @Provides
    @Singleton
    @JvmStatic
    fun provideHacApplicationsRepository(dao: HacApplicationsDao): HacApplicationsRepository {
        return HacApplicationsRepository(dao)
    }
} 