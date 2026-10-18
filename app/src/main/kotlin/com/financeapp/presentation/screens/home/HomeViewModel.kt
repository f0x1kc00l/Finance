package com.financeapp.presentation.screens.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.financeapp.core.analytics.AnalyticsCalculator
import com.financeapp.core.analytics.AnalyticsSummary
import com.financeapp.core.analytics.BudgetStatus
import com.financeapp.core.time.TimeSource
import com.financeapp.core.util.runCatchingCancellable
import com.financeapp.data.repository.AppSettingsRepository
import com.financeapp.data.repository.ExpenseRepository
import com.financeapp.data.repository.PeriodSpec
import com.financeapp.domain.model.Category
import com.financeapp.domain.model.Currency
import com.financeapp.domain.model.Expense
import com.financeapp.domain.model.Period
import com.financeapp.presentation.ui.labelRes
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val loading: Boolean = true,
    val error: Boolean = false,
    val currency: Currency = Currency.AMD,
    val month: AnalyticsSummary? = null,
    val budget: BudgetStatus? = null,
    val recent: List<Expense> = emptyList(),
    val searchActive: Boolean = false,
    val query: String = "",
    val results: List<Expense> = emptyList()
) {
    val isEmpty: Boolean get() = !loading && !error && recent.isEmpty()
}

sealed interface HomeEvent {
    data class Deleted(val expense: Expense) : HomeEvent
    data object DeleteFailed : HomeEvent
}

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: ExpenseRepository,
    private val settings: AppSettingsRepository,
    private val time: TimeSource
) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    private val _events = Channel<HomeEvent>(Channel.BUFFERED)
    val events: Flow<HomeEvent> = _events.receiveAsFlow()

    private val query = MutableStateFlow("")
    private var dataJob: Job? = null

    init {
        load()
        viewModelScope.launch {
            query.debounce(200).distinctUntilChanged().flatMapLatest { q ->
                if (q.isBlank()) flowOf(emptyList()) else repo.search(q, categoriesMatching(q))
            }.catch { emit(emptyList()) }
                .collect { results -> _state.update { it.copy(results = results) } }
        }
    }

    fun load() {
        dataJob?.cancel()
        _state.update { it.copy(loading = it.month == null, error = false) }
        dataJob = viewModelScope.launch {
            combine(
                repo.observeSummary(PeriodSpec.Preset(Period.MONTH)),
                repo.observeRecent(),
                repo.observeBudget(),
                settings.settings.filterNotNull().map { it.currency }.distinctUntilChanged()
            ) { month, recent, budget, currency ->
                val status = budget?.let {
                    AnalyticsCalculator.budgetStatus(it.monthlyLimitMinor, month.totalMinor, month.range, time.today())
                }
                HomeData(month, recent, status, currency)
            }
                .catch { _state.update { it.copy(loading = false, error = true) } }
                .collect { d ->
                    _state.update {
                        it.copy(loading = false, error = false, month = d.month, recent = d.recent, budget = d.budget, currency = d.currency)
                    }
                }
        }
    }

    private data class HomeData(val month: AnalyticsSummary, val recent: List<Expense>, val budget: BudgetStatus?, val currency: Currency)

    // ── Search ───────────────────────────────────────────────────────────────

    fun openSearch() = _state.update { it.copy(searchActive = true) }

    fun onQueryChange(q: String) {
        val trimmed = q.take(100)
        _state.update { it.copy(query = trimmed) }
        query.value = trimmed
    }

    fun closeSearch() {
        query.value = ""
        _state.update { it.copy(searchActive = false, query = "", results = emptyList()) }
    }

    private fun categoriesMatching(q: String): List<Category> {
        val needle = q.trim()
        if (needle.isEmpty()) return emptyList()
        return Category.entries.filter { context.getString(it.labelRes).contains(needle, ignoreCase = true) }
    }

    // ── Delete / undo ────────────────────────────────────────────────────────

    fun delete(id: Long) {
        viewModelScope.launch {
            val deleted = runCatchingCancellable { repo.delete(id) }
            when {
                deleted.isFailure -> _events.send(HomeEvent.DeleteFailed)
                deleted.getOrNull() != null -> _events.send(HomeEvent.Deleted(deleted.getOrNull()!!))
                // null: already deleted (e.g. a repeated swipe callback) — nothing to report
            }
        }
    }

    fun undoDelete(expense: Expense) {
        viewModelScope.launch { runCatchingCancellable { repo.restore(expense) } }
    }
}
