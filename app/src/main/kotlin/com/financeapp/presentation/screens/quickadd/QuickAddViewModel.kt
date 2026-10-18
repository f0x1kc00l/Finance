package com.financeapp.presentation.screens.quickadd

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import javax.inject.Inject

enum class QuickAddStatus { READY, INVALID, SAVING, SAVED, DUPLICATE, ERROR }

data class QuickAddUiState(
    val amountMinor: Long = 0L,
    val notificationCurrency: String = "",
    val appCurrency: Currency = Currency.AMD,
    val bank: String = "",
    val status: QuickAddStatus = QuickAddStatus.READY
) {
    /** The app has no exchange rates: a foreign-currency amount must be checked manually. */
    val currencyMismatch: Boolean get() = notificationCurrency.isNotEmpty() && notificationCurrency != appCurrency.code
    val canQuickSave: Boolean get() = status == QuickAddStatus.READY && !currencyMismatch
}

@HiltViewModel
class QuickAddViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repo: ExpenseRepository,
    private val settings: AppSettingsRepository,
    private val time: TimeSource
) : ViewModel() {

    private val amount: Long = savedStateHandle.get<Long>(EXTRA_AMOUNT_MINOR) ?: 0L
    private val sourceKey: String? = savedStateHandle.get<String>(EXTRA_SOURCE_KEY)?.takeIf { it.length in 16..128 }
    val notificationId: Int = savedStateHandle.get<Int>(EXTRA_NOTIFICATION_ID) ?: 0

    private val _state = MutableStateFlow(
        QuickAddUiState(
            amountMinor = amount,
            notificationCurrency = savedStateHandle.get<String>(EXTRA_CURRENCY).orEmpty().take(3).uppercase(),
            bank = savedStateHandle.get<String>(EXTRA_BANK).orEmpty().take(40),
            status = if (Money.isValidAmount(amount) && sourceKey != null) QuickAddStatus.READY else QuickAddStatus.INVALID
        )
    )
    val state: StateFlow<QuickAddUiState> = _state.asStateFlow()

    val sourceKeyForDetails: String? get() = sourceKey

    init {
        viewModelScope.launch {
            val currency = settings.current().currency
            _state.update { it.copy(appCurrency = currency) }
        }
    }

    fun save(category: Category) {
        val s = _state.value
        if (!s.canQuickSave) return          // also blocks double taps while SAVING
        _state.update { it.copy(status = QuickAddStatus.SAVING) }
        viewModelScope.launch {
            val result = runCatchingCancellable {
                repo.addDetected(
                    Expense(
                        amountMinor = s.amountMinor,
                        category = category,
                        note = s.bank,
                        paymentMethod = PaymentMethod.NFC,
                        occurredAt = Instant.ofEpochMilli(time.nowMs()),
                        sourceKey = sourceKey
                    )
                )
            }
            _state.update {
                it.copy(
                    status = when (result.getOrNull()) {
                        is DetectedInsert.Inserted -> QuickAddStatus.SAVED
                        DetectedInsert.Duplicate -> QuickAddStatus.DUPLICATE
                        null -> QuickAddStatus.ERROR
                    }
                )
            }
        }
    }

    fun retry() = _state.update { if (it.status == QuickAddStatus.ERROR) it.copy(status = QuickAddStatus.READY) else it }

    companion object {
        const val EXTRA_AMOUNT_MINOR = "amount_minor"
        const val EXTRA_CURRENCY = "currency"
        const val EXTRA_BANK = "bank"
        const val EXTRA_SOURCE_KEY = "source_key"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
    }
}
