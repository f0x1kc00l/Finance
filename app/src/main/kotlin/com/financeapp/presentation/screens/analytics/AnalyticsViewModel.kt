package com.financeapp.presentation.screens.analytics

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.financeapp.core.analytics.AnalyticsSummary
import com.financeapp.core.time.DateRange
import com.financeapp.data.repository.AppSettingsRepository
import com.financeapp.data.repository.ExpenseRepository
import com.financeapp.data.repository.PeriodSpec
import com.financeapp.domain.model.Currency
import com.financeapp.domain.model.Period
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class AnalyticsUiState(
    val spec: PeriodSpec = PeriodSpec.Preset(Period.MONTH),
    val summary: AnalyticsSummary? = null,
    val currency: Currency = Currency.AMD,
    val loading: Boolean = true,
    val error: Boolean = false
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AnalyticsViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    private val repo: ExpenseRepository,
    private val settings: AppSettingsRepository
) : ViewModel() {

    private val spec = MutableStateFlow(restoreSpec())
    private val _state = MutableStateFlow(AnalyticsUiState(spec = spec.value))
    val state: StateFlow<AnalyticsUiState> = _state.asStateFlow()
    private var job: Job? = null

    init { load() }

    fun load() {
        job?.cancel()
        _state.update { it.copy(loading = true, error = false) }
        job = viewModelScope.launch {
            combine(
                spec.flatMapLatest { s -> repo.observeSummary(s).map { s to it } },
                settings.settings.filterNotNull().map { it.currency }.distinctUntilChanged()
            ) { (s, summary), currency -> AnalyticsUiState(s, summary, currency, loading = false) }
                .catch { _state.update { it.copy(loading = false, error = true) } }
                .collect { _state.value = it }
        }
    }

    fun selectPeriod(period: Period) = setSpec(PeriodSpec.Preset(period))

    fun selectRange(from: LocalDate, to: LocalDate) = setSpec(PeriodSpec.Custom(DateRange.inclusive(from, to)))

    private fun setSpec(s: PeriodSpec) {
        if (s == spec.value) return
        _state.update { it.copy(spec = s, loading = true) }
        spec.value = s
        when (s) {
            is PeriodSpec.Preset -> {
                savedState[KEY_PERIOD] = s.period.name
                savedState.remove<Long>(KEY_FROM)
                savedState.remove<Long>(KEY_TO)
            }
            is PeriodSpec.Custom -> {
                savedState[KEY_FROM] = s.range.start.toEpochDay()
                savedState[KEY_TO] = s.range.endExclusive.toEpochDay()
            }
        }
    }

    /** Survives process death so the user returns to the period they were looking at. */
    private fun restoreSpec(): PeriodSpec {
        val from = savedState.get<Long>(KEY_FROM)
        val to = savedState.get<Long>(KEY_TO)
        if (from != null && to != null && to > from) {
            return PeriodSpec.Custom(DateRange(LocalDate.ofEpochDay(from), LocalDate.ofEpochDay(to)))
        }
        val period = savedState.get<String>(KEY_PERIOD)?.let { n -> Period.entries.firstOrNull { it.name == n } } ?: Period.MONTH
        return PeriodSpec.Preset(period)
    }

    private companion object {
        const val KEY_PERIOD = "analytics_period"
        const val KEY_FROM = "analytics_from"
        const val KEY_TO = "analytics_to"
    }
}
