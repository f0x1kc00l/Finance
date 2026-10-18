package com.financeapp.di

import android.content.Context
import androidx.room.Room
import com.financeapp.core.time.SystemTimeSource
import com.financeapp.core.time.TimeSource
import com.financeapp.data.local.FinanceDatabase
import com.financeapp.data.local.dao.BudgetDao
import com.financeapp.data.local.dao.ExpenseDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier @Retention(AnnotationRetention.BINARY) annotation class ApplicationScope
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class IoDispatcher
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class DefaultDispatcher

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): FinanceDatabase =
        Room.databaseBuilder(context, FinanceDatabase::class.java, FinanceDatabase.NAME)
            .addMigrations(*FinanceDatabase.ALL_MIGRATIONS)
            // Deliberately NO fallbackToDestructiveMigration*(): a missing migration must crash
            // in testing rather than silently wipe a user's expense history in production.
            .build()

    @Provides fun provideExpenseDao(db: FinanceDatabase): ExpenseDao = db.expenseDao()
    @Provides fun provideBudgetDao(db: FinanceDatabase): BudgetDao = db.budgetDao()

    @Provides @IoDispatcher fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO
    @Provides @DefaultDispatcher fun provideDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    /** Lives as long as the process; used for settings caching and fire-and-forget writes. */
    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides fun provideTimeSource(): TimeSource = SystemTimeSource
}
