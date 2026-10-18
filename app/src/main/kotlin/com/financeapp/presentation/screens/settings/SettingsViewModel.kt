package com.financeapp.presentation.screens.settings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.financeapp.R
import com.financeapp.core.export.CsvExporter
import com.financeapp.core.money.Money
import com.financeapp.core.time.TimeSource
import com.financeapp.core.util.runCatchingCancellable
import com.financeapp.data.repository.AppSettings
import com.financeapp.data.repository.AppSettingsRepository
import com.financeapp.data.repository.ExpenseRepository
import com.financeapp.data.security.BiometricKeyManager
import com.financeapp.di.IoDispatcher
import com.financeapp.domain.model.Currency
import com.financeapp.domain.pin.PinManager
import com.financeapp.presentation.security.AuthOutcome
import com.financeapp.presentation.security.BiometricPrompts
import com.financeapp.presentation.ui.labelRes
import com.financeapp.service.PaymentNotificationListener
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import java.io.OutputStreamWriter
import javax.crypto.Cipher
import javax.inject.Inject

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val loaded: Boolean = false,
    val pinSet: Boolean = false,
    val budgetInput: String = "",
    val budgetError: Boolean = false,
    val hasBudget: Boolean = false,
    val notificationAccess: Boolean = false,
    val canPostNotifications: Boolean = true,
    val strongBiometricAvailable: Boolean = false,
    val exporting: Boolean = false
) {
    /** v1.0 allowed enabling the lock without an app PIN (device credential only). */
    val legacyLock: Boolean get() = settings.lockEnabled && !pinSet
}

sealed interface SettingsEvent {
    data class Message(val resId: Int, val arg: Int? = null) : SettingsEvent
    data class Share(val uri: Uri) : SettingsEvent
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: ExpenseRepository,
    private val settingsRepo: AppSettingsRepository,
    private val pinManager: PinManager,
    private val biometricKeys: BiometricKeyManager,
    private val time: TimeSource,
    @IoDispatcher private val io: CoroutineDispatcher
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    private val _events = Channel<SettingsEvent>(Channel.BUFFERED)
    val events: Flow<SettingsEvent> = _events.receiveAsFlow()

    init {
        viewModelScope.launch {
            settingsRepo.settings.filterNotNull().collect { s ->
                _state.update { it.copy(settings = s, loaded = true, pinSet = pinManager.isPinSet()) }
            }
        }
        viewModelScope.launch {
            repo.observeBudget().collect { b ->
                // Emits only when the stored budget changes (load, save, removal).
                _state.update {
                    it.copy(
                        hasBudget = b != null,
                        budgetInput = b?.let { v -> Money.toInputString(v.monthlyLimitMinor) } ?: "",
                        budgetError = false
                    )
                }
            }
        }
        refreshSystemState()
    }

    /** Re-read permission / hardware state (call on every resume: the user may change it in system settings). */
    fun refreshSystemState() {
        viewModelScope.launch {
            val pinSet = pinManager.isPinSet()
            _state.update {
                it.copy(
                    pinSet = pinSet,
                    notificationAccess = PaymentNotificationListener.isAccessGranted(context),
                    canPostNotifications = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
                    strongBiometricAvailable = BiometricPrompts.canUseStrongBiometric(context)
                )
            }
        }
    }

    // ── Currency & budget ───────────────────────────────────────────────────

    fun setCurrency(c: Currency) {
        viewModelScope.launch { settingsRepo.setCurrency(c) }
    }

    fun onBudgetInput(text: String) {
        val filtered = text.filter { it.isDigit() || it == '.' || it == ',' }.take(14)
        _state.update { it.copy(budgetInput = filtered, budgetError = false) }
    }

    fun saveBudget() {
        val minor = Money.parse(_state.value.budgetInput)
        if (minor == null) {
            _state.update { it.copy(budgetError = true) }
            return
        }
        viewModelScope.launch {
            val ok = runCatchingCancellable { repo.saveBudget(minor) }.isSuccess
            _events.send(SettingsEvent.Message(if (ok) R.string.settings_budget_saved else R.string.error_generic))
        }
    }

    fun removeBudget() {
        viewModelScope.launch {
            runCatchingCancellable { repo.clearBudget() }
            _state.update { it.copy(budgetInput = "", budgetError = false) }
            _events.send(SettingsEvent.Message(R.string.settings_budget_removed))
        }
    }

    // ── Security ─────────────────────────────────────────────────────────────

    /** Turning off a v1.0 device-credential lock (no app PIN exists to verify). */
    fun disableLegacyLock() {
        viewModelScope.launch { settingsRepo.setLockEnabled(false) }
    }

    fun setAutoLock(ms: Long) {
        viewModelScope.launch { settingsRepo.setAutoLockTimeoutMs(ms) }
    }

    fun setHideInRecents(hide: Boolean) {
        viewModelScope.launch { settingsRepo.setHideInRecents(hide) }
    }

    /** Creates a fresh biometric-bound key and returns its cipher for the confirmation prompt. */
    fun prepareBiometricEnrollment(): Cipher? = runCatching {
        biometricKeys.createKey()
        (biometricKeys.cipherForUnlock() as? BiometricKeyManager.CipherResult.Ready)?.cipher
    }.getOrNull()

    fun onBiometricEnrollmentResult(outcome: AuthOutcome) {
        val ok = outcome is AuthOutcome.Success && biometricKeys.confirm(outcome.cipher)
        if (!ok) biometricKeys.deleteKey()
        viewModelScope.launch { settingsRepo.setBiometricEnabled(ok) }
        if (outcome is AuthOutcome.Error) viewModelScope.launch { _events.send(SettingsEvent.Message(R.string.error_generic)) }
    }

    fun disableBiometric() {
        biometricKeys.deleteKey()
        viewModelScope.launch { settingsRepo.setBiometricEnabled(false) }
    }

    // ── Export ───────────────────────────────────────────────────────────────

    /** Writes the CSV to a user-chosen document (Storage Access Framework — no storage permission). */
    fun exportTo(uri: Uri) = export { rows ->
        context.contentResolver.openOutputStream(uri, "wt")?.use { out -> rows(out) }
            ?: throw IllegalStateException("cannot open output")
        null
    }

    /** Writes the CSV to private cache and shares it via a content:// URI (no file paths leak). */
    fun exportForSharing() = export { rows ->
        val dir = File(context.cacheDir, EXPORT_DIR).apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // never keep stale exports around
        val file = File(dir, "financeapp_${time.today()}.csv")
        file.outputStream().use { out -> rows(out) }
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    private fun export(write: ((OutputStream) -> Int) -> Uri?) {
        if (_state.value.exporting) return
        _state.update { it.copy(exporting = true) }
        viewModelScope.launch {
            val expenses = runCatchingCancellable { repo.getAllForExport() }.getOrElse { emptyList() }
            if (expenses.isEmpty()) {
                _state.update { it.copy(exporting = false) }
                _events.send(SettingsEvent.Message(R.string.settings_export_empty))
                return@launch
            }
            val currency = _state.value.settings.currency
            val zone = time.zone()
            val labels = CsvExporter.Labels(
                date = context.getString(R.string.csv_date),
                category = context.getString(R.string.csv_category),
                amount = context.getString(R.string.csv_amount),
                currency = context.getString(R.string.csv_currency),
                paymentMethod = context.getString(R.string.csv_method),
                note = context.getString(R.string.csv_note)
            )
            var count = 0
            val result = runCatchingCancellable {
                withContext(io) {
                    write { out ->
                        val writer = OutputStreamWriter(out, Charsets.UTF_8).buffered()
                        count = CsvExporter.write(
                            writer, expenses.asSequence(), currency, zone, labels,
                            categoryLabel = { context.getString(it.category.labelRes) },
                            methodLabel = { context.getString(it.paymentMethod.labelRes) }
                        )
                        writer.flush()
                        count
                    }
                }
            }
            _state.update { it.copy(exporting = false) }
            result.fold(
                onSuccess = { uri ->
                    if (uri != null) _events.send(SettingsEvent.Share(uri))
                    else _events.send(SettingsEvent.Message(R.string.settings_export_done, count))
                },
                onFailure = { _events.send(SettingsEvent.Message(R.string.settings_export_failed)) }
            )
        }
    }

    companion object {
        const val EXPORT_DIR = "exports"
    }
}
