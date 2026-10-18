package com.financeapp.domain.pin

import com.financeapp.core.security.PinAttemptPolicy
import com.financeapp.core.security.PinHasher
import com.financeapp.core.security.PinPolicy
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Every possible result of a PIN verification attempt. */
sealed interface PinResult {
    data object Success : PinResult
    /** Wrong PIN; [attemptsLeft] free attempts remain before lockouts start. */
    data class Failure(val attemptsLeft: Int) : PinResult
    data class LockedOut(val remainingMs: Long) : PinResult
    data object NotSet : PinResult
}

sealed interface SetPinResult {
    data object Saved : SetPinResult
    data object Mismatch : SetPinResult
    data object TooWeak : SetPinResult
}

/** Step of the PIN management flow. */
enum class PinSetupStep { VERIFY_CURRENT, CREATE, CONFIRM }

/**
 * Why the PIN management screen was opened (unlocking is handled by the lock gate).
 *  SETUP   – create a PIN and enable the lock (also used after a "Forgot PIN" reset)
 *  CHANGE  – verify the current PIN, then create a new one
 *  DISABLE – verify the current PIN, then remove it and turn the lock off
 */
enum class PinScreenMode { SETUP, CHANGE, DISABLE }

/** Persisted PIN state. [record] is a [PinHasher] record, never the PIN itself. */
data class PinState(
    val record: String? = null,
    val failures: Int = 0,
    val lockedUntilMs: Long = 0L
)

/** Atomic storage for [PinState] (DataStore on Android, in-memory in tests). */
interface PinStore {
    suspend fun read(): PinState
    suspend fun update(transform: (PinState) -> PinState): PinState
}

/**
 * Single entry point for PIN verification, creation and lockout bookkeeping.
 *
 * All methods are main-safe only when called from a background dispatcher: hashing is CPU
 * bound by design. A mutex serialises attempts so two screens can't race the counters.
 */
class PinManager(
    private val store: PinStore,
    private val hasher: PinHasher,
    private val nowMs: () -> Long = System::currentTimeMillis
) {
    private val mutex = Mutex()

    suspend fun isPinSet(): Boolean = store.read().record != null

    /** Remaining lockout, normalising a lockout that a backwards clock change inflated. */
    suspend fun lockoutRemainingMs(): Long = mutex.withLock { normalizedRemaining() }

    private suspend fun normalizedRemaining(): Long {
        val s = store.read()
        val now = nowMs()
        val raw = s.lockedUntilMs - now
        val clamped = PinAttemptPolicy.remainingMs(now, s.lockedUntilMs, s.failures)
        if (raw > clamped && clamped > 0) store.update { it.copy(lockedUntilMs = now + clamped) }
        return clamped
    }

    suspend fun verify(pin: CharArray): PinResult = mutex.withLock {
        try {
            val record = store.read().record ?: return@withLock PinResult.NotSet
            val remaining = normalizedRemaining()
            if (remaining > 0) return@withLock PinResult.LockedOut(remaining)

            if (hasher.verify(pin.copyOf(), record)) {
                store.update { it.copy(failures = 0, lockedUntilMs = 0L) }
                if (hasher.needsRehash(record)) {
                    // Parameters were raised since this PIN was set: transparently upgrade.
                    val upgraded = hasher.hash(pin.copyOf())
                    store.update { it.copy(record = upgraded) }
                }
                PinResult.Success
            } else {
                val now = nowMs()
                val updated = store.update {
                    val failures = it.failures + 1
                    val lock = PinAttemptPolicy.lockoutDurationMs(failures)
                    it.copy(failures = failures, lockedUntilMs = if (lock > 0) now + lock else 0L)
                }
                val lock = PinAttemptPolicy.lockoutDurationMs(updated.failures)
                if (lock > 0) PinResult.LockedOut(lock)
                else PinResult.Failure(PinAttemptPolicy.attemptsBeforeLockout(updated.failures))
            }
        } finally {
            pin.fill('\u0000')
        }
    }

    /** Creates or replaces the PIN. Both arrays are wiped. */
    suspend fun setPin(pin: CharArray, confirmation: CharArray): SetPinResult = mutex.withLock {
        try {
            if (!pin.contentEquals(confirmation)) return@withLock SetPinResult.Mismatch
            if (PinPolicy.isTooWeak(pin)) return@withLock SetPinResult.TooWeak
            val record = hasher.hash(pin.copyOf())
            store.update { PinState(record = record, failures = 0, lockedUntilMs = 0L) }
            SetPinResult.Saved
        } finally {
            pin.fill('\u0000')
            confirmation.fill('\u0000')
        }
    }

    /** Removes the PIN and all counters (lock disabled / PIN reset after device-credential check). */
    suspend fun clear() = mutex.withLock { store.update { PinState() } }

    /** Called after a successful strong-biometric unlock: the owner is present. */
    suspend fun onOwnerVerified() = mutex.withLock { store.update { it.copy(failures = 0, lockedUntilMs = 0L) } }
}
