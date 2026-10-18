package com.financeapp.di

import android.os.SystemClock
import com.financeapp.domain.session.SessionManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SessionModule {
    @Provides
    @Singleton
    fun provideSessionManager(): SessionManager = SessionManager { SystemClock.elapsedRealtime() }
}
