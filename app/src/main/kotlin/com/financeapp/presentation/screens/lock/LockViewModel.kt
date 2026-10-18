package com.financeapp.presentation.screens.lock

import android.app.ActivityManager
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.financeapp.core.security.PinPolicy
import com.financeapp.data.repository.AppSettingsRepository
import com.financeapp.data.security.BiometricKeyManager
import com.financeapp.di.DefaultDispatcher
import com.financeapp.domain.pin.PinManager
import com.financeapp.domain.pin.PinResult
import com.financeapp.domain.session.SessionManager
import com.financeapp.presentation.AppEvents
import com.financeapp.presentation.security.AuthOutcome
import com.financeapp.presentation.security.BiometricPrompts
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.crypto.Cipher
import javax.inject.Inject

enum class LockMode {
    LOADING,
    /** App PIN (+ optional biometric). */
    PIN,
    /** Lock enabled before an app PIN existed (v1.0 behaviour): confirm with the device screen lock. */
    DEVICE_CREDENTIAL,
    /** Lock enabled, no app PIN and no device screen lock: nothing to verify against. */
    NO_SECURE_METHOD
}

sealed interface LockMessage {
    data class WrongPin(val attemptsLeft: Int) : LockMessage
    data object BiometricReset : LockMessage
    data class System(val text: String) : LockMessage
}

data class LockUiState(
    val mode: LockMode = LockMode.LOADING,
    val entered: Int = 0,
    val pinLength: Int = PinPolicy.LENGTH,
    val verifying: Boolean = false,
    val message: LockMessage? = null,
    val lockoutRemainingMs: Long = 0L,
    val shakeKey: Int = 0,
    val biometricOffered: Boolean = false,
    val deviceSecure: Boolean = false,
    val showForgotDialog: Boolean = false,
    val showEraseDialog: Boolean = false
) {
    val isLockedOut: Boolean get() = lockoutRemainingMs > 0
    val inputEnabled: Boolean get() = mode == LockMode.PIN && !verifying && !isLockedOut
}

@HiltViewModel
class LockViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pinManager: PinManager,
    private val settings: AppSettingsRepository,
    private val session: SessionManager,
    private val biometricKeys: BiometricKeyManager,
    private val appEvents: AppEvents,
    @DefaultDispatcher private val cpu: CoroutineDispatcher
) : ViewModel() {

    private val _state = MutableStateFlow(LockUiState())
    val state: StateFlow<LockUiState> = _state.asStateFlow()

    /** Digits live only here (never in UI state) and are wiped after each attempt. */
    private val buffer = CharArray(PinPolicy.LENGTH)
    private var lockoutJob: Job? = null

    /**
     * Called every time the lock screen appears. The ViewModel is activity-scoped (the lock
     * gate sits outside the nav graph), so settings may have changed since the last lock —
     * e.g. the user created a PIN or enabled biometrics.
     */
    fun onShown() {
        buffer.fill('\u0000')
        _state.update { it.copy(entered = 0, verifying = false, message = null, showForgotDialog = false, showEraseDialog = false) }
        viewModelScope.launch { refresh() }
    }

    private suspend fun refresh() {
        val s = settings.current()
        val pinSet = pinManager.isPinSet()
        val deviceSecure = BiometricPrompts.isDeviceSecure(context)
        val mode = when {
            pinSet -> LockMode.PIN
            deviceSecure -> LockMode.DEVICE_CREDENTIAL
            else -> LockMode.NO_SECURE_METHOD
        }
        val biometric = pinSet && s.biometricEnabled &&
            BiometricPrompts.canUseStrongBiometric(context) && biometricKeys.hasKey()
        _state.update { it.copy(mode = mode, biometricOffered = biometric, deviceSecure = deviceSecure) }
        startLockoutCountdown()
    }

    // ── PIN entry ────────────────────────────────────────────────────────────

    fun onDigit(digit: Int) {
        val st = _state.value
        if (!st.inputEnabled || st.entered >= st.pinLength) return
        buffer[st.entered] = '0' + digit
        val entered = st.entered + 1
        _state.update { it.copy(entered = entered, message = null) }
        if (entered == st.pinLength) verify()
    }

    fun onDelete() {
        if (!_state.value.inputEnabled) return
        _state.update { it.copy(entered = (it.entered - 1).coerceAtLeast(0)) }
    }

    private fun verify() {
        val attempt = buffer.copyOf()
        buffer.fill('\u0000')
        _state.update { it.copy(verifying = true) }
        viewModelScope.launch {
            val result = withContext(cpu) { pinManager.verify(attempt) }
            when (result) {
                PinResult.Success -> {
                    _state.update { it.copy(verifying = false, entered = 0, message = null) }
                    session.unlock()
                }
                is PinResult.Failure -> _state.update {
                    it.copy(
                        verifying = false,
                        entered = 0,
                        message = LockMessage.WrongPin(result.attemptsLeft),
                        shakeKey = it.shakeKey + 1
                    )
                }
                is PinResult.LockedOut -> {
                    _state.update { it.copy(verifying = false, entered = 0, message = null, shakeKey = it.shakeKey + 1) }
                    startLockoutCountdown()
                }
                PinResult.NotSet -> {
                    _state.update { it.copy(verifying = false, entered = 0) }
                    refresh()
                }
            }
        }
    }

    private fun startLockoutCountdown() {
        lockoutJob?.cancel()
        lockoutJob = viewModelScope.launch {
            while (true) {
                val remaining = pinManager.lockoutRemainingMs()
                _state.update { it.copy(lockoutRemainingMs = remaining) }
                if (remaining <= 0L) break
                delay(minOf(remaining, 1_000L))
            }
        }
    }

    // ── Biometric ────────────────────────────────────────────────────────────

    /** Returns a cipher for the prompt, or null (and updates state) if biometrics can't be used. */
    fun biometricCipher(): Cipher? = when (val r = biometricKeys.cipherForUnlock()) {
        is BiometricKeyManager.CipherResult.Ready -> r.cipher
        BiometricKeyManager.CipherResult.Invalidated -> {
            viewModelScope.launch { settings.setBiometricEnabled(false) }
            _state.update { it.copy(biometricOffered = false, message = LockMessage.BiometricReset) }
            null
        }
        BiometricKeyManager.CipherResult.Unavailable -> {
            _state.update { it.copy(biometricOffered = false) }
            null
        }
    }

    fun onBiometricResult(outcome: AuthOutcome) {
        when (outcome) {
            is AuthOutcome.Success -> {
                // The cipher must actually work — proves the Keystore authorised this unlock.
                if (biometricKeys.confirm(outcome.cipher)) {
                    viewModelScope.launch {
                        pinManager.onOwnerVerified()
                        lockoutJob?.cancel()
                        _state.update { it.copy(lockoutRemainingMs = 0L, message = null) }
                        session.unlock()
                    }
                }
            }
            AuthOutcome.Cancelled -> Unit
            is AuthOutcome.Error -> _state.update { it.copy(message = LockMessage.System(outcome.message)) }
        }
    }

    // ── Device credential (legacy mode and PIN recovery) ─────────────────────

    fun onDeviceCredentialUnlock(outcome: AuthOutcome) {
        when (outcome) {
            is AuthOutcome.Success -> session.unlock()
            AuthOutcome.Cancelled -> Unit
            is AuthOutcome.Error -> _state.update { it.copy(message = LockMessage.System(outcome.message)) }
        }
    }

    /** NO_SECURE_METHOD: the lock can't be enforced, so it is switched off (the screen says why). */
    fun disableUnenforceableLock() {
        viewModelScope.launch { settings.setLockEnabled(false) }
    }

    fun showForgotDialog(show: Boolean) = _state.update { it.copy(showForgotDialog = show) }

    fun showEraseDialog(show: Boolean) = _state.update { it.copy(showEraseDialog = show, showForgotDialog = false) }

    fun onPinResetVerified(outcome: AuthOutcome) {
        _state.update { it.copy(showForgotDialog = false) }
        when (outcome) {
            is AuthOutcome.Success -> viewModelScope.launch {
                pinManager.clear()
                biometricKeys.deleteKey()
                settings.setBiometricEnabled(false)
                // Lock stays enabled: until a new PIN is set, the device screen lock protects the app.
                appEvents.requestPinSetup()
                session.unlock()
            }
            AuthOutcome.Cancelled -> Unit
            is AuthOutcome.Error -> _state.update { it.copy(message = LockMessage.System(outcome.message)) }
        }
    }

    /** Last resort when the owner can't be verified: wipes ALL app data (the process is killed). */
    fun eraseAllData() {
        context.getSystemService(ActivityManager::class.java)?.clearApplicationUserData()
    }

    override fun onCleared() {
        buffer.fill('\u0000')
        super.onCleared()
    }
}
