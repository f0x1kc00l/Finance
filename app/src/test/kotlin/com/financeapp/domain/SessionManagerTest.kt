package com.financeapp.domain

import com.financeapp.domain.session.LockConfig
import com.financeapp.domain.session.SessionManager
import com.financeapp.domain.session.SessionState
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionManagerTest {

    private var clock = 10_000L
    private val session = SessionManager { clock }

    @Test fun `unknown until settings load`() {
        assertEquals(SessionState.UNKNOWN, session.state.value)
        session.unlock() // must not unlock before we know whether a lock is required
        assertEquals(SessionState.UNKNOWN, session.state.value)
    }

    @Test fun `cold start is locked when the lock is enabled`() {
        session.onConfigChanged(LockConfig(lockEnabled = true, autoLockTimeoutMs = 60_000))
        assertEquals(SessionState.LOCKED, session.state.value)
    }

    @Test fun `cold start is unlocked when the lock is disabled`() {
        session.onConfigChanged(LockConfig(lockEnabled = false, autoLockTimeoutMs = 60_000))
        assertEquals(SessionState.UNLOCKED, session.state.value)
    }

    @Test fun `short trip to background keeps the session, long trip locks`() {
        session.onConfigChanged(LockConfig(true, 60_000))
        session.unlock()
        session.onBackgrounded(); clock += 59_999; session.onForegrounded()
        assertEquals(SessionState.UNLOCKED, session.state.value)
        session.onBackgrounded(); clock += 60_000; session.onForegrounded()
        assertEquals(SessionState.LOCKED, session.state.value)
    }

    @Test fun `zero timeout locks immediately on background`() {
        session.onConfigChanged(LockConfig(true, 0))
        session.unlock()
        session.onBackgrounded()
        assertEquals(SessionState.LOCKED, session.state.value)
    }

    @Test fun `foreground without a recorded background does nothing`() {
        session.onConfigChanged(LockConfig(true, 1_000))
        session.unlock()
        clock += 1_000_000
        session.onForegrounded()
        assertEquals(SessionState.UNLOCKED, session.state.value)
    }

    @Test fun `enabling the lock inside the app keeps the session open`() {
        session.onConfigChanged(LockConfig(false, 60_000))
        session.onConfigChanged(LockConfig(true, 60_000))
        assertEquals(SessionState.UNLOCKED, session.state.value)
    }

    @Test fun `disabling the lock unlocks`() {
        session.onConfigChanged(LockConfig(true, 60_000))
        session.onConfigChanged(LockConfig(false, 60_000))
        assertEquals(SessionState.UNLOCKED, session.state.value)
    }

    @Test fun `manual lock only applies when enabled`() {
        session.onConfigChanged(LockConfig(false, 60_000))
        session.lockNow()
        assertEquals(SessionState.UNLOCKED, session.state.value)
    }
}
