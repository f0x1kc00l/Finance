package com.financeapp.domain.pin

import com.financeapp.core.security.PinPolicy

sealed interface PinFlowMessage {
    data object Mismatch : PinFlowMessage
    data object TooWeak : PinFlowMessage
    data class WrongPin(val attemptsLeft: Int) : PinFlowMessage
    data class LockedOut(val remainingMs: Long) : PinFlowMessage
}

data class PinFlowState(
    val mode: PinScreenMode,
    val step: PinSetupStep,
    val entered: Int = 0,
    val message: PinFlowMessage? = null,
    /** Increments on every rejected entry so the UI can shake the dots. */
    val shakeKey: Int = 0,
    val completed: Boolean = false
)

/**
 * Platform-free state machine for creating, changing and removing the app PIN.
 *
 *  SETUP:   CREATE → CONFIRM → save
 *  CHANGE:  VERIFY_CURRENT → CREATE → CONFIRM → save
 *  DISABLE: VERIFY_CURRENT → clear
 *
 * Verification of the current PIN goes through [PinManager.verify], so it shares the same
 * attempt counter and lockout as the lock screen (no side channel for guessing).
 * Entered digits are kept in private CharArrays and wiped as soon as they are consumed.
 * Callers must serialise calls (the ViewModel ignores input while a call is in flight).
 */
class PinFlow(private val mode: PinScreenMode, private val manager: PinManager) {

    private val length = PinPolicy.LENGTH
    private val input = CharArray(length)
    private var firstPin: CharArray? = null

    var state = PinFlowState(
        mode = mode,
        step = if (mode == PinScreenMode.SETUP) PinSetupStep.CREATE else PinSetupStep.VERIFY_CURRENT
    )
        private set

    suspend fun onDigit(digit: Int): PinFlowState {
        require(digit in 0..9)
        if (state.completed || state.entered >= length) return state
        input[state.entered] = '0' + digit
        state = state.copy(entered = state.entered + 1, message = null)
        if (state.entered == length) submit()
        return state
    }

    fun onDelete(): PinFlowState {
        if (state.entered > 0 && !state.completed) state = state.copy(entered = state.entered - 1)
        return state
    }

    private suspend fun submit() {
        val pin = input.copyOf()
        input.fill('\u0000')
        when (state.step) {
            PinSetupStep.VERIFY_CURRENT -> when (val r = manager.verify(pin)) {
                PinResult.Success -> {
                    if (mode == PinScreenMode.DISABLE) {
                        manager.clear()
                        state = state.copy(entered = 0, completed = true)
                    } else {
                        state = state.copy(step = PinSetupStep.CREATE, entered = 0)
                    }
                }
                is PinResult.Failure -> reject(PinFlowMessage.WrongPin(r.attemptsLeft))
                is PinResult.LockedOut -> reject(PinFlowMessage.LockedOut(r.remainingMs))
                // No PIN to verify (e.g. reset elsewhere): treat as verified.
                PinResult.NotSet -> state = if (mode == PinScreenMode.DISABLE) {
                    state.copy(entered = 0, completed = true)
                } else {
                    state.copy(step = PinSetupStep.CREATE, entered = 0)
                }
            }
            PinSetupStep.CREATE -> {
                if (PinPolicy.isTooWeak(pin)) {
                    pin.fill('\u0000')
                    reject(PinFlowMessage.TooWeak)
                } else {
                    firstPin = pin
                    state = state.copy(step = PinSetupStep.CONFIRM, entered = 0)
                }
            }
            PinSetupStep.CONFIRM -> {
                val first = firstPin ?: CharArray(0)
                firstPin = null
                when (manager.setPin(first, pin)) {
                    SetPinResult.Saved -> state = state.copy(entered = 0, completed = true)
                    SetPinResult.Mismatch -> {
                        state = state.copy(step = PinSetupStep.CREATE)
                        reject(PinFlowMessage.Mismatch)
                    }
                    SetPinResult.TooWeak -> {
                        state = state.copy(step = PinSetupStep.CREATE)
                        reject(PinFlowMessage.TooWeak)
                    }
                }
            }
        }
    }

    private fun reject(message: PinFlowMessage) {
        state = state.copy(entered = 0, message = message, shakeKey = state.shakeKey + 1)
    }

    fun clear() {
        input.fill('\u0000')
        firstPin?.fill('\u0000')
        firstPin = null
    }
}
