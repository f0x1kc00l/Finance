package com.financeapp.domain

import com.financeapp.core.security.Pepper
import com.financeapp.core.security.PinAttemptPolicy
import com.financeapp.core.security.PinHasher
import com.financeapp.core.security.PinPolicy
import com.financeapp.domain.pin.PinManager
import com.financeapp.domain.pin.PinResult
import com.financeapp.domain.pin.PinState
import com.financeapp.domain.pin.PinStore
import com.financeapp.domain.pin.SetPinResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Software stand-in for the Android Keystore HMAC key. */
private class TestPepper(key: ByteArray = ByteArray(32) { it.toByte() }) : Pepper {
    private val spec = SecretKeySpec(key, "HmacSHA256")
    override fun apply(data: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run { init(spec); doFinal(data) }
}

private class InMemoryPinStore(var state: PinState = PinState()) : PinStore {
    override suspend fun read() = state
    override suspend fun update(transform: (PinState) -> PinState): PinState { state = transform(state); return state }
}

class PinHasherTest {
    // Low iteration count keeps the test fast; production uses DEFAULT_ITERATIONS.
    private val hasher = PinHasher(TestPepper(), iterations = 10_000)

    @Test fun `correct pin verifies, wrong pin does not`() {
        val record = hasher.hash("2580".toCharArray())
        assertTrue(hasher.verify("2580".toCharArray(), record))
        assertFalse(hasher.verify("2581".toCharArray(), record))
    }

    @Test fun `record never contains the pin and salts differ`() {
        val a = hasher.hash("2580".toCharArray())
        val b = hasher.hash("2580".toCharArray())
        assertFalse(a.contains("2580"))
        assertNotEquals(a, b)
        assertTrue(a.startsWith("v3:10000:"))
    }

    @Test fun `record is bound to the device pepper`() {
        val record = hasher.hash("2580".toCharArray())
        val otherDevice = PinHasher(TestPepper(ByteArray(32) { 7 }), iterations = 10_000)
        assertFalse(otherDevice.verify("2580".toCharArray(), record))
    }

    @Test fun `input arrays are wiped`() {
        val pin = "2580".toCharArray()
        val record = hasher.hash(pin)
        assertArrayEquals(CharArray(4), pin)
        val again = "2580".toCharArray()
        hasher.verify(again, record)
        assertArrayEquals(CharArray(4), again)
    }

    @Test fun `malformed or tampered records fail closed`() {
        assertFalse(hasher.verify("2580".toCharArray(), ""))
        assertFalse(hasher.verify("2580".toCharArray(), "v2:abc:def"))
        assertFalse(hasher.verify("2580".toCharArray(), "v3:1:AAAA:AAAA"))      // iterations below floor
        assertFalse(hasher.verify("2580".toCharArray(), "v3:10000:!!!:???"))
        assertFalse(hasher.verify("25a0".toCharArray(), hasher.hash("2580".toCharArray())))
    }

    @Test fun `rehash is requested when iterations increase`() {
        val record = hasher.hash("2580".toCharArray())
        assertFalse(hasher.needsRehash(record))
        assertTrue(PinHasher(TestPepper(), iterations = 20_000).needsRehash(record))
    }
}

class PinPolicyTest {
    @Test fun `weak pins are rejected at creation`() {
        listOf("0000", "1111", "1234", "4321", "6789", "9876").forEach {
            assertTrue(it, PinPolicy.isTooWeak(it.toCharArray()))
        }
        listOf("2580", "1357", "1123", "9071").forEach {
            assertFalse(it, PinPolicy.isTooWeak(it.toCharArray()))
        }
        assertTrue(PinPolicy.isTooWeak("123".toCharArray()))
    }

    @Test fun `lockout escalates and is capped`() {
        assertEquals(0L, PinAttemptPolicy.lockoutDurationMs(4))
        assertEquals(30_000L, PinAttemptPolicy.lockoutDurationMs(5))
        assertEquals(60_000L, PinAttemptPolicy.lockoutDurationMs(6))
        assertEquals(480_000L, PinAttemptPolicy.lockoutDurationMs(9))
        assertEquals(PinAttemptPolicy.MAX_LOCKOUT_MS, PinAttemptPolicy.lockoutDurationMs(10))
        assertEquals(PinAttemptPolicy.MAX_LOCKOUT_MS, PinAttemptPolicy.lockoutDurationMs(1_000))
    }

    @Test fun `remaining time is clamped after a backwards clock change`() {
        val now = 1_000_000L
        // lock set 1 day in the "future" because the clock was moved back
        assertEquals(30_000L, PinAttemptPolicy.remainingMs(now, now + 86_400_000L, failures = 5))
        assertEquals(10_000L, PinAttemptPolicy.remainingMs(now, now + 10_000L, failures = 5))
        assertEquals(0L, PinAttemptPolicy.remainingMs(now, now - 1, failures = 5))
        assertEquals(0L, PinAttemptPolicy.remainingMs(now, 0L, failures = 9))
    }
}

class PinManagerTest {
    private var now = 1_000_000_000L
    private val store = InMemoryPinStore()
    private val manager = PinManager(store, PinHasher(TestPepper(), iterations = 10_000)) { now }

    private fun setPin(pin: String = "2580") = runBlocking { manager.setPin(pin.toCharArray(), pin.toCharArray()) }

    @Test fun `not set until created`() = runBlocking {
        assertFalse(manager.isPinSet())
        assertEquals(PinResult.NotSet, manager.verify("2580".toCharArray()))
        assertEquals(SetPinResult.Saved, setPin())
        assertTrue(manager.isPinSet())
    }

    @Test fun `mismatch and weak pins are not saved`() = runBlocking {
        assertEquals(SetPinResult.Mismatch, manager.setPin("2580".toCharArray(), "2581".toCharArray()))
        assertEquals(SetPinResult.TooWeak, manager.setPin("1234".toCharArray(), "1234".toCharArray()))
        assertNull(store.state.record)
    }

    @Test fun `five free attempts then escalating lockout`() = runBlocking {
        setPin()
        for (left in 4 downTo 1) {
            assertEquals(PinResult.Failure(left), manager.verify("0000".toCharArray()))
        }
        assertEquals(PinResult.LockedOut(30_000L), manager.verify("0000".toCharArray()))
        // Even the correct PIN is refused while locked out — no oracle during lockout.
        assertTrue(manager.verify("2580".toCharArray()) is PinResult.LockedOut)
        now += 30_001L
        assertEquals(PinResult.LockedOut(60_000L), manager.verify("0000".toCharArray()))
        now += 60_001L
        assertEquals(PinResult.Success, manager.verify("2580".toCharArray()))
        assertEquals(0, store.state.failures)
        assertEquals(0L, store.state.lockedUntilMs)
    }

    @Test fun `lockout survives a new manager instance (process death)`() = runBlocking {
        setPin()
        repeat(5) { manager.verify("0000".toCharArray()) }
        val restarted = PinManager(store, PinHasher(TestPepper(), iterations = 10_000)) { now }
        assertTrue(restarted.verify("2580".toCharArray()) is PinResult.LockedOut)
        assertEquals(30_000L, restarted.lockoutRemainingMs())
    }

    @Test fun `backwards clock change cannot lock the owner out for days`() = runBlocking {
        setPin()
        repeat(5) { manager.verify("0000".toCharArray()) }
        now -= 86_400_000L // clock moved back a day
        assertEquals(30_000L, manager.lockoutRemainingMs())
        now += 30_001L
        assertEquals(0L, manager.lockoutRemainingMs())
    }

    @Test fun `changing the pin resets counters and old pin stops working`() = runBlocking {
        setPin("2580")
        manager.verify("0000".toCharArray())
        assertEquals(SetPinResult.Saved, setPin("1357"))
        assertEquals(0, store.state.failures)
        assertTrue(manager.verify("2580".toCharArray()) is PinResult.Failure)
        assertEquals(PinResult.Success, manager.verify("1357".toCharArray()))
    }

    @Test fun `clear removes everything`() = runBlocking {
        setPin()
        manager.clear()
        assertEquals(PinState(), store.state)
    }

    @Test fun `owner verification by biometrics resets counters`() = runBlocking {
        setPin()
        repeat(3) { manager.verify("0000".toCharArray()) }
        manager.onOwnerVerified()
        assertEquals(0, store.state.failures)
    }
}

class PinFlowTest {
    private var now = 5_000_000L
    private val store = InMemoryPinStore()
    private val manager = PinManager(store, PinHasher(TestPepper(), iterations = 10_000)) { now }

    private fun com.financeapp.domain.pin.PinFlow.type(pin: String) = runBlocking {
        pin.forEach { onDigit(it - '0') }
        state
    }

    @Test fun `setup creates a pin after matching confirmation`() {
        val flow = com.financeapp.domain.pin.PinFlow(com.financeapp.domain.pin.PinScreenMode.SETUP, manager)
        assertEquals(com.financeapp.domain.pin.PinSetupStep.CREATE, flow.state.step)
        assertEquals(com.financeapp.domain.pin.PinSetupStep.CONFIRM, flow.type("2580").step)
        assertTrue(flow.type("2580").completed)
        assertEquals(PinResult.Success, runBlocking { manager.verify("2580".toCharArray()) })
    }

    @Test fun `setup rejects weak pins and mismatches`() {
        val flow = com.financeapp.domain.pin.PinFlow(com.financeapp.domain.pin.PinScreenMode.SETUP, manager)
        val weak = flow.type("1111")
        assertEquals(com.financeapp.domain.pin.PinFlowMessage.TooWeak, weak.message)
        assertEquals(com.financeapp.domain.pin.PinSetupStep.CREATE, weak.step)
        flow.type("2580")
        val mismatch = flow.type("2581")
        assertEquals(com.financeapp.domain.pin.PinFlowMessage.Mismatch, mismatch.message)
        assertEquals(com.financeapp.domain.pin.PinSetupStep.CREATE, mismatch.step)
        assertFalse(runBlocking { manager.isPinSet() })
    }

    @Test fun `change requires the current pin and shares the lockout counter`() {
        runBlocking { manager.setPin("2580".toCharArray(), "2580".toCharArray()) }
        val flow = com.financeapp.domain.pin.PinFlow(com.financeapp.domain.pin.PinScreenMode.CHANGE, manager)
        assertEquals(com.financeapp.domain.pin.PinFlowMessage.WrongPin(4), flow.type("0000").message)
        assertEquals(1, store.state.failures)
        assertEquals(com.financeapp.domain.pin.PinSetupStep.CREATE, flow.type("2580").step)
        flow.type("1357")
        assertTrue(flow.type("1357").completed)
        assertEquals(PinResult.Success, runBlocking { manager.verify("1357".toCharArray()) })
    }

    @Test fun `disable verifies then clears`() {
        runBlocking { manager.setPin("2580".toCharArray(), "2580".toCharArray()) }
        val flow = com.financeapp.domain.pin.PinFlow(com.financeapp.domain.pin.PinScreenMode.DISABLE, manager)
        assertTrue(flow.type("2580").completed)
        assertFalse(runBlocking { manager.isPinSet() })
    }

    @Test fun `delete removes the last digit`() = runBlocking {
        val flow = com.financeapp.domain.pin.PinFlow(com.financeapp.domain.pin.PinScreenMode.SETUP, manager)
        flow.onDigit(1); flow.onDigit(2)
        assertEquals(1, flow.onDelete().entered)
        assertEquals(0, flow.onDelete().entered)
        assertEquals(0, flow.onDelete().entered)
    }
}
