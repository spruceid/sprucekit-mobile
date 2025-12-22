package com.spruceid.mobilesdkexample.di

import android.app.Application
import com.spruceid.mobile.sdk.dcapi.Registry
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    @JvmStatic
    fun provideRegistry(application: Application): Registry {
        return Registry(application, "icon.ico")
    }
} 