package com.financeapp.data.pin

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.financeapp.domain.pin.PinState
import com.financeapp.domain.pin.PinStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private val Context.pinDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "finance_pin_store",
    // A corrupted store loses only the PIN hash; the lock gate then falls back to the
    // device credential (see LockViewModel) rather than locking the user out forever.
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }
)

/**
 * DataStore-backed [PinStore]. Holds a Keystore-peppered hash record and attempt counters —
 * never the PIN. Updates are atomic (DataStore.edit is a read-modify-write transaction).
 */
@Singleton
class PinStoreImpl @Inject constructor(
    @ApplicationContext context: Context
) : PinStore {

    private val store = context.pinDataStore

    override suspend fun read(): PinState = store.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it.toState() }
        .first()

    override suspend fun update(transform: (PinState) -> PinState): PinState {
        var result = PinState()
        store.edit { prefs ->
            val next = transform(prefs.toState())
            // Legacy v2 keys: written only by an unreachable code path in v1.0 (no screen ever
            // navigated to PIN setup), so they are dropped rather than migrated.
            prefs.remove(LEGACY_HASH)
            prefs.remove(LEGACY_SALT)
            prefs.remove(LEGACY_LAST_FAILED)
            if (next.record != null) prefs[KEY_RECORD] = next.record else prefs.remove(KEY_RECORD)
            prefs[KEY_FAILURES] = next.failures
            if (next.lockedUntilMs > 0) prefs[KEY_LOCKED_UNTIL] = next.lockedUntilMs else prefs.remove(KEY_LOCKED_UNTIL)
            result = next
        }
        return result
    }

    private fun Preferences.toState() = PinState(
        record = this[KEY_RECORD],
        failures = (this[KEY_FAILURES] ?: 0).coerceAtLeast(0),
        lockedUntilMs = this[KEY_LOCKED_UNTIL] ?: 0L
    )

    private companion object {
        val KEY_RECORD = stringPreferencesKey("pin_record_v3")
        val KEY_FAILURES = intPreferencesKey("pin_failed_attempts")
        val KEY_LOCKED_UNTIL = longPreferencesKey("pin_locked_until")
        val LEGACY_HASH = stringPreferencesKey("pin_hash_v2")
        val LEGACY_SALT = stringPreferencesKey("pin_salt_v2")
        val LEGACY_LAST_FAILED = longPreferencesKey("pin_last_failed_at")
    }
}
