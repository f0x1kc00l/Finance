package com.financeapp.di

import com.financeapp.core.security.Pepper
import com.financeapp.core.security.PinHasher
import com.financeapp.core.time.TimeSource
import com.financeapp.data.pin.KeystorePepper
import com.financeapp.data.pin.PinStoreImpl
import com.financeapp.domain.pin.PinManager
import com.financeapp.domain.pin.PinStore
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class PinModule {

    @Binds @Singleton abstract fun bindPinStore(impl: PinStoreImpl): PinStore
    @Binds @Singleton abstract fun bindPepper(impl: KeystorePepper): Pepper

    companion object {
        @Provides
        @Singleton
        fun providePinManager(store: PinStore, pepper: Pepper, time: TimeSource): PinManager =
            PinManager(store, PinHasher(pepper)) { time.nowMs() }
    }
}
