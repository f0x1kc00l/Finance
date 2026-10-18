package com.financeapp.presentation.screens.pin

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.financeapp.core.security.PinPolicy
import com.financeapp.data.pin.DeviceSecurityChecker
import com.financeapp.data.repository.AppSettingsRepository
import com.financeapp.data.security.BiometricKeyManager
import com.financeapp.di.DefaultDispatcher
import com.financeapp.domain.pin.PinFlow
import com.financeapp.domain.pin.PinFlowState
import com.financeapp.domain.pin.PinManager
import com.financeapp.domain.pin.PinScreenMode
import com.financeapp.presentation.AppEvents
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class PinFlowUiState(
    val flow: PinFlowState,
    val busy: Boolean = false,
    /** Hashing / verifying the completed PIN (shows a spinner). */
    val verifying: Boolean = false,
    /** Side effects (settings, keys) are finished — safe to leave the screen. */
    val finished: Boolean = false,
    val showSecurityWarning: Boolean = false
)

@HiltViewModel
class PinFlowViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context,
    pinManager: PinManager,
    private val settings: AppSettingsRepository,
    private val biometricKeys: BiometricKeyManager,
    private val appEvents: AppEvents,
    @DefaultDispatcher private val cpu: CoroutineDispatcher
) : ViewModel() {

    val mode: PinScreenMode = savedStateHandle.get<String>(ARG_MODE)
        ?.let { name -> PinScreenMode.entries.firstOrNull { it.name == name } }
        ?: PinScreenMode.SETUP

    private val flow = PinFlow(mode, pinManager)

    private val _state = MutableStateFlow(PinFlowUiState(flow.state))
    val state: StateFlow<PinFlowUiState> = _state.asStateFlow()

    init {
        if (mode == PinScreenMode.SETUP) {
            viewModelScope.launch {
                val compromised = withContext(cpu) { DeviceSecurityChecker.isDeviceCompromised(context) }
                _state.update { it.copy(showSecurityWarning = compromised) }
            }
        }
    }

    fun onDigit(digit: Int) {
        if (_state.value.busy || _state.value.finished) return
        // Only the last digit triggers hashing/verification; show progress just for that one so
        // the spinner doesn't flicker on every key press.
        val submitting = flow.state.entered == PinPolicy.LENGTH - 1
        _state.update { it.copy(busy = true, verifying = submitting) }
        viewModelScope.launch {
            val next = if (submitting) withContext(cpu) { flow.onDigit(digit) } else flow.onDigit(digit)
            _state.update { it.copy(flow = next, busy = false, verifying = false) }
            if (next.completed) finish()
        }
    }

    fun onDelete() {
        if (_state.value.busy) return
        _state.update { it.copy(flow = flow.onDelete()) }
    }

    fun dismissSecurityWarning() = _state.update { it.copy(showSecurityWarning = false) }

    private suspend fun finish() {
        when (mode) {
            PinScreenMode.SETUP, PinScreenMode.CHANGE -> {
                settings.setLockEnabled(true)
                appEvents.consumePinSetupRequest()
            }
            PinScreenMode.DISABLE -> {
                biometricKeys.deleteKey()
                settings.setLockEnabled(false) // also clears the biometric flag
            }
        }
        _state.update { it.copy(finished = true) }
    }

    override fun onCleared() {
        flow.clear()
        super.onCleared()
    }

    companion object {
        const val ARG_MODE = "mode"
    }
}
