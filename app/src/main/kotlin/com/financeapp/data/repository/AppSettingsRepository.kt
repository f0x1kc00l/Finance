package com.financeapp.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.financeapp.di.ApplicationScope
import com.financeapp.domain.model.Currency
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * File name and keys are unchanged from v1.0 so existing users keep their settings.
 * NOTE: DataStore is NOT encrypted. It holds only non-secret preferences; the PIN lives in
 * a separate store as a Keystore-peppered hash (see PinStoreImpl).
 */
private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "finance_secure_prefs",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }
)

data class AppSettings(
    val currency: Currency = Currency.AMD,
    val onboardingDone: Boolean = false,
    val lockEnabled: Boolean = false,
    val biometricEnabled: Boolean = false,
    val autoLockTimeoutMs: Long = DEFAULT_AUTO_LOCK_MS,
    val hideInRecents: Boolean = true
) {
    companion object {
        const val DEFAULT_AUTO_LOCK_MS = 60_000L
        /** Allowed auto-lock delays (0 = lock immediately on leaving the app). */
        val AUTO_LOCK_CHOICES_MS = listOf(0L, 30_000L, 60_000L, 5 * 60_000L, 15 * 60_000L)
    }
}

/**
 * Single source of truth for app settings, loaded asynchronously.
 *
 * [settings] is null only until the first read completes (the splash screen stays up until
 * then), after which every consumer — including lifecycle callbacks that must answer
 * synchronously — reads the in-memory value. No call ever blocks the main thread on disk I/O
 * (the previous implementation used runBlocking for every get/put).
 */
@Singleton
class AppSettingsRepository @Inject constructor(
    @ApplicationContext context: Context,
    @ApplicationScope scope: CoroutineScope
) {
    private val store = context.settingsDataStore

    val settings: StateFlow<AppSettings?> = store.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it.toSettings() }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /** Latest settings, suspending only until the first load. */
    suspend fun current(): AppSettings = settings.filterNotNull().first()

    /** Non-suspending snapshot; defaults until loaded. Safe from lifecycle callbacks. */
    fun snapshot(): AppSettings = settings.value ?: AppSettings()

    suspend fun setCurrency(currency: Currency) = store.edit { it[KEY_CURRENCY] = currency.name }
    suspend fun setOnboardingDone() = store.edit { it[KEY_ONBOARDING_DONE] = true }
    suspend fun setLockEnabled(enabled: Boolean) = store.edit {
        it[KEY_LOCK_ENABLED] = enabled
        if (!enabled) it[KEY_BIOMETRIC_ENABLED] = false
    }
    suspend fun setBiometricEnabled(enabled: Boolean) = store.edit { it[KEY_BIOMETRIC_ENABLED] = enabled }
    suspend fun setAutoLockTimeoutMs(ms: Long) = store.edit {
        it[KEY_AUTO_LOCK_TIMEOUT_MS] = ms.coerceIn(0L, AppSettings.AUTO_LOCK_CHOICES_MS.max())
    }
    suspend fun setHideInRecents(hide: Boolean) = store.edit { it[KEY_HIDE_IN_RECENTS] = hide }

    private fun Preferences.toSettings() = AppSettings(
        currency = Currency.fromStorage(this[KEY_CURRENCY]),
        onboardingDone = this[KEY_ONBOARDING_DONE] ?: false,
        lockEnabled = this[KEY_LOCK_ENABLED] ?: false,
        biometricEnabled = this[KEY_BIOMETRIC_ENABLED] ?: false,
        autoLockTimeoutMs = (this[KEY_AUTO_LOCK_TIMEOUT_MS] ?: AppSettings.DEFAULT_AUTO_LOCK_MS)
            .coerceIn(0L, AppSettings.AUTO_LOCK_CHOICES_MS.max()),
        hideInRecents = this[KEY_HIDE_IN_RECENTS] ?: true
    )

    private companion object {
        // v1.0 keys — do not rename.
        val KEY_CURRENCY = stringPreferencesKey("selected_currency")
        val KEY_ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        val KEY_BIOMETRIC_ENABLED = booleanPreferencesKey("biometric_enabled")
        val KEY_LOCK_ENABLED = booleanPreferencesKey("lock_enabled")
        val KEY_AUTO_LOCK_TIMEOUT_MS = longPreferencesKey("auto_lock_timeout_ms")
        // v2 keys
        val KEY_HIDE_IN_RECENTS = booleanPreferencesKey("hide_in_recents")
    }
}
