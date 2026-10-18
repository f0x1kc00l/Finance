package com.financeapp.presentation.screens.add

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.financeapp.core.money.AmountInput
import com.financeapp.core.money.Money
import com.financeapp.core.time.TimeSource
import com.financeapp.core.util.runCatchingCancellable
import com.financeapp.data.repository.AppSettingsRepository
import com.financeapp.data.repository.DetectedInsert
import com.financeapp.data.repository.ExpenseRepository
import com.financeapp.domain.model.Category
import com.financeapp.domain.model.Currency
import com.financeapp.domain.model.Expense
import com.financeapp.domain.model.PaymentMethod
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject

enum class AddError { AMOUNT, CATEGORY, SAVE, MISSING }

enum class AddOutcome { SAVED, UPDATED, DELETED }

data class AddUiState(
    val loading: Boolean = false,
    val isEdit: Boolean = false,
    val buffer: String = "",
    val category: Category? = null,
    val method: PaymentMethod = PaymentMethod.CARD,
    val date: LocalDate = LocalDate.now(),
    val note: String = "",
    val currency: Currency = Currency.AMD,
    /** Bank name when pre-filled from a payment notification. */
    val source: String? = null,
    /** Notification currency differs from the app currency (no conversion is ever done). */
    val sourceCurrencyMismatch: String? = null,
    val saving: Boolean = false,
    val error: AddError? = null,
    val showDeleteDialog: Boolean = false,
    /** One-shot: the screen should close with this outcome. */
    val outcome: AddOutcome? = null
) {
    val amountMinor: Long? get() = AmountInput.toMinor(buffer)
    val canSave: Boolean get() = !saving && !loading && outcome == null
}

/**
 * Add and edit share one screen.
 *
 * Duplicate protection: [save] is ignored while a save is in flight or after it succeeded, so
 * double taps and recomposition can't create two rows; expenses pre-filled from a bank
 * notification also carry its de-duplication key, so the same payment can't be saved twice
 * via the quick sheet and this screen.
 */
@HiltViewModel
class AddExpenseViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repo: ExpenseRepository,
    private val settings: AppSettingsRepository,
    private val time: TimeSource
) : ViewModel() {

    private val editId: Long = savedStateHandle.get<Long>(ARG_ID) ?: -1L
    private val sourceKey: String? = savedStateHandle.get<String>(ARG_KEY)?.takeIf { it.length in 16..128 }
    private var original: Expense? = null

    private val _state = MutableStateFlow(AddUiState(isEdit = editId > 0, loading = editId > 0, date = time.today()))
    val state: StateFlow<AddUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val currency = settings.current().currency
            _state.update { it.copy(currency = currency) }

            if (editId > 0) {
                val existing = runCatchingCancellable { repo.getById(editId) }.getOrNull()
                if (existing == null) {
                    _state.update { it.copy(loading = false, error = AddError.MISSING) }
                } else {
                    original = existing
                    _state.update {
                        it.copy(
                            loading = false,
                            buffer = Money.toInputString(existing.amountMinor),
                            category = existing.category,
                            method = existing.paymentMethod,
                            date = existing.occurredAt.atZone(time.zone()).toLocalDate(),
                            note = existing.note
                        )
                    }
                }
            } else {
                val prefill = savedStateHandle.get<Long>(ARG_AMOUNT)?.takeIf { Money.isValidAmount(it) }
                val bank = savedStateHandle.get<String>(ARG_BANK)?.take(40)?.takeIf { it.isNotBlank() }
                val notifCurrency = savedStateHandle.get<String>(ARG_CURRENCY)?.take(3)?.uppercase()
                _state.update {
                    it.copy(
                        buffer = prefill?.let(Money::toInputString) ?: "",
                        source = bank,
                        note = bank ?: "",
                        method = if (bank != null) PaymentMethod.NFC else PaymentMethod.CARD,
                        sourceCurrencyMismatch = notifCurrency?.takeIf { c -> c.isNotBlank() && c != currency.code }
                    )
                }
            }
        }
    }

    fun onKey(key: AmountInput.Key) = _state.update {
        it.copy(buffer = AmountInput.apply(it.buffer, key), error = it.error.takeUnless { e -> e == AddError.AMOUNT })
    }

    fun onCategory(c: Category) = _state.update {
        it.copy(category = c, error = it.error.takeUnless { e -> e == AddError.CATEGORY })
    }

    fun onMethod(m: PaymentMethod) = _state.update { it.copy(method = m) }

    fun onDate(date: LocalDate) {
        val today = time.today()
        _state.update { it.copy(date = if (date.isAfter(today)) today else date) }
    }

    fun onNote(text: String) = _state.update { it.copy(note = text.take(ExpenseRepository.MAX_NOTE_LENGTH)) }

    fun save() {
        val s = _state.value
        if (!s.canSave) return
        val amount = s.amountMinor
        if (amount == null) {
            _state.update { it.copy(error = AddError.AMOUNT) }
            return
        }
        val category = s.category
        if (category == null) {
            _state.update { it.copy(error = AddError.CATEGORY) }
            return
        }
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            val expense = Expense(
                id = original?.id ?: 0,
                amountMinor = amount,
                category = category,
                note = s.note.trim(),
                paymentMethod = s.method,
                occurredAt = resolveInstant(s.date),
                sourceKey = original?.sourceKey ?: sourceKey
            )
            val result = runCatchingCancellable {
                when {
                    original != null -> if (repo.update(expense)) AddOutcome.UPDATED else null
                    expense.sourceKey != null -> when (repo.addDetected(expense)) {
                        is DetectedInsert.Inserted -> AddOutcome.SAVED
                        // Already recorded from the quick sheet — nothing new to save, just close.
                        DetectedInsert.Duplicate -> AddOutcome.SAVED
                    }
                    else -> { repo.add(expense); AddOutcome.SAVED }
                }
            }
            val outcome = result.getOrNull()
            _state.update {
                when {
                    outcome != null -> it.copy(saving = false, outcome = outcome)
                    result.isSuccess -> it.copy(saving = false, error = AddError.MISSING) // edited row vanished
                    else -> it.copy(saving = false, error = AddError.SAVE)
                }
            }
        }
    }

    fun requestDelete(show: Boolean) = _state.update { it.copy(showDeleteDialog = show) }

    fun delete() {
        val id = original?.id ?: return
        _state.update { it.copy(showDeleteDialog = false, saving = true) }
        viewModelScope.launch {
            val ok = runCatchingCancellable { repo.delete(id) }.isSuccess
            _state.update { if (ok) it.copy(saving = false, outcome = AddOutcome.DELETED) else it.copy(saving = false, error = AddError.SAVE) }
        }
    }

    /**
     * Keeps the original time of day when editing; new expenses dated today use "now", other
     * days use the current time of day on that date (so ordering within a day stays natural).
     */
    private fun resolveInstant(date: LocalDate): Instant {
        val zone: ZoneId = time.zone()
        val orig = original
        if (orig != null) {
            val origLocal = orig.occurredAt.atZone(zone)
            if (origLocal.toLocalDate() == date) return orig.occurredAt
            return date.atTime(origLocal.toLocalTime()).atZone(zone).toInstant()
        }
        val now = Instant.ofEpochMilli(time.nowMs())
        return if (date == time.today()) now else date.atTime(LocalTime.now(time.clock())).atZone(zone).toInstant()
    }

    companion object {
        const val ARG_ID = "id"
        const val ARG_AMOUNT = "amount"
        const val ARG_BANK = "bank"
        const val ARG_KEY = "key"
        const val ARG_CURRENCY = "currency"
    }
}
