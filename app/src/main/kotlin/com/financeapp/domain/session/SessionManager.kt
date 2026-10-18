package com.financeapp.domain.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class SessionState {
    /** Settings not loaded yet — show nothing (the splash screen stays up). */
    UNKNOWN,
    LOCKED,
    UNLOCKED
}

data class LockConfig(val lockEnabled: Boolean, val autoLockTimeoutMs: Long)

/**
 * App-lock session state machine. Platform-free and unit-tested.
 *
 * Rules:
 *  - Cold start / process recreation / reboot: LOCKED whenever the lock is enabled
 *    (the state lives only in memory, so a new process always starts locked).
 *  - Leaving the app records a MONOTONIC timestamp (elapsedRealtime) in memory; returning
 *    after >= timeout locks. A timeout of 0 locks as soon as the app is backgrounded.
 *    Monotonic time cannot be bypassed by changing the wall clock.
 *  - Disabling the lock unlocks; enabling it while the user is inside keeps the session
 *    open (they just proved themselves by creating a PIN).
 *  - The UI renders the lock screen INSTEAD of the navigation host while LOCKED, so the
 *    back button, deep links and stale back-stack entries cannot reach protected screens.
 */
class SessionManager(private val elapsedRealtime: () -> Long) {

    private val _state = MutableStateFlow(SessionState.UNKNOWN)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private var config: LockConfig? = null
    private var backgroundedAt: Long? = null

    @Synchronized
    fun onConfigChanged(new: LockConfig) {
        val old = config
        config = new
        when {
            old == null -> _state.value = if (new.lockEnabled) SessionState.LOCKED else SessionState.UNLOCKED
            !new.lockEnabled -> _state.value = SessionState.UNLOCKED
            // enabling (old disabled → new enabled) keeps the current session open
        }
    }

    @Synchronized
    fun onBackgrounded() {
        val c = config ?: return
        if (!c.lockEnabled) return
        if (c.autoLockTimeoutMs <= 0L) {
            _state.value = SessionState.LOCKED
            backgroundedAt = null
        } else if (_state.value == SessionState.UNLOCKED) {
            backgroundedAt = elapsedRealtime()
        }
    }

    @Synchronized
    fun onForegrounded() {
        val c = config
        val since = backgroundedAt
        backgroundedAt = null
        if (c == null || !c.lockEnabled || since == null) return
        if (elapsedRealtime() - since >= c.autoLockTimeoutMs) _state.value = SessionState.LOCKED
    }

    @Synchronized
    fun unlock() {
        if (config != null) _state.value = SessionState.UNLOCKED
    }

    @Synchronized
    fun lockNow() {
        if (config?.lockEnabled == true) _state.value = SessionState.LOCKED
    }
}
