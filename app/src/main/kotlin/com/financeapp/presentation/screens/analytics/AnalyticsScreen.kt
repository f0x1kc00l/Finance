package com.financeapp.presentation.screens.analytics

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.financeapp.R
import com.financeapp.core.analytics.AnalyticsSummary
import com.financeapp.core.analytics.SeriesGranularity
import com.financeapp.core.money.MoneyFormatter
import com.financeapp.core.time.DateRange
import com.financeapp.data.repository.PeriodSpec
import com.financeapp.domain.model.Currency
import com.financeapp.domain.model.Period
import com.financeapp.presentation.theme.AmountStyle
import com.financeapp.presentation.theme.Spacing
import com.financeapp.presentation.theme.categoryColor
import com.financeapp.presentation.ui.CategoryBadge
import com.financeapp.presentation.ui.ChangeIndicator
import com.financeapp.presentation.ui.EmptyState
import com.financeapp.presentation.ui.ErrorState
import com.financeapp.presentation.ui.FinanceTopBar
import com.financeapp.presentation.ui.SectionCard
import com.financeapp.presentation.ui.ThinBar
import com.financeapp.presentation.ui.currentLocale
import com.financeapp.presentation.ui.labelRes
import com.financeapp.presentation.ui.rememberMoneyFormatter
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle

@Composable
fun AnalyticsScreen(onBack: () -> Unit, vm: AnalyticsViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val formatter = rememberMoneyFormatter()
    var showPicker by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            FinanceTopBar(title = stringResource(R.string.analytics_title), onBack = onBack) {
                IconButton(onClick = { showPicker = true }) {
                    Icon(Icons.Rounded.CalendarMonth, contentDescription = stringResource(R.string.analytics_pick_range))
                }
            }
        }
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = Spacing.gutter, end = Spacing.gutter,
                top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + Spacing.xl
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.m)
        ) {
            item(key = "filters") { PeriodChips(state.spec, vm::selectPeriod, onCustom = { showPicker = true }) }
            val summary = state.summary
            when {
                state.error -> item(key = "error") { ErrorState(stringResource(R.string.error_load), onRetry = vm::load) }
                summary == null -> item(key = "loading") {
                    Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
                else -> {
                    item(key = "total") { TotalCard(summary, state.currency, formatter, state.loading) }
                    if (summary.isEmpty) {
                        item(key = "empty") {
                            EmptyState(
                                emoji = "📊",
                                title = stringResource(R.string.analytics_empty_title),
                                body = stringResource(R.string.analytics_empty_body)
                            )
                        }
                    } else {
                        item(key = "donut") { DonutCard(summary, state.currency, formatter) }
                        item(key = "trend") { TrendCard(summary, state.currency, formatter) }
                    }
                }
            }
        }
    }

    if (showPicker) {
        RangePickerDialog(
            onDismiss = { showPicker = false },
            onPicked = { from, to ->
                vm.selectRange(from, to)
                showPicker = false
            }
        )
    }
}

@Composable
private fun PeriodChips(spec: PeriodSpec, onPeriod: (Period) -> Unit, onCustom: () -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Period.entries.forEach { p ->
            val selected = spec is PeriodSpec.Preset && spec.period == p
            FilterChip(
                selected = selected,
                onClick = { onPeriod(p) },
                label = { Text(stringResource(p.labelRes)) },
                leadingIcon = if (selected) {
                    { Icon(Icons.Rounded.Check, contentDescription = null) }
                } else null
            )
        }
        val custom = spec as? PeriodSpec.Custom
        FilterChip(
            selected = custom != null,
            onClick = onCustom,
            label = { Text(custom?.let { rangeLabel(it.range) } ?: stringResource(R.string.period_custom)) },
            leadingIcon = { Icon(Icons.Rounded.CalendarMonth, contentDescription = null) }
        )
    }
}

@Composable
private fun rangeLabel(range: DateRange): String {
    val fmt = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(currentLocale())
    return if (range.lengthInDays == 1L) fmt.format(range.start) else "${fmt.format(range.start)} – ${fmt.format(range.lastDay)}"
}

@Composable
private fun TotalCard(s: AnalyticsSummary, currency: Currency, formatter: MoneyFormatter, refreshing: Boolean) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.analytics_total),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            if (refreshing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        }
        Text(rangeLabel(s.range), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(Spacing.xs))
        Text(formatter.format(s.totalMinor, currency), style = MaterialTheme.typography.displaySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        s.comparison?.changePercent?.let { change ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChangeIndicator(change)
                Spacer(Modifier.width(Spacing.s))
                Text(
                    stringResource(R.string.analytics_vs_previous),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (!s.isEmpty) {
            Spacer(Modifier.height(Spacing.xs))
            Text(
                stringResource(R.string.analytics_avg, formatter.format(s.averagePerDayMinor, currency)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DonutCard(s: AnalyticsSummary, currency: Currency, formatter: MoneyFormatter) {
    SectionCard(title = stringResource(R.string.analytics_by_category)) {
        val progress = remember(s.range) { Animatable(0f) }
        LaunchedEffect(s.range) { progress.animateTo(1f, tween(700, easing = FastOutSlowInEasing)) }
        val colors = s.categories.map { categoryColor(it.category.colorHex) }
        val track = MaterialTheme.colorScheme.surfaceVariant
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Canvas(
                Modifier
                    .size(180.dp)
                    // The legend below carries the same data as text; the ring itself is decorative.
                    .clearAndSetSemantics { }
            ) {
                val stroke = 22.dp.toPx()
                val diameter = size.minDimension - stroke
                val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
                drawArc(track, 0f, 360f, false, topLeft, Size(diameter, diameter), style = Stroke(stroke))
                val gap = if (s.categories.size > 1) 1.5f else 0f
                var start = -90f
                s.categories.forEachIndexed { i, stat ->
                    val sweep = stat.fraction * 360f * progress.value
                    if (sweep > gap) drawArc(colors[i], start + gap / 2, sweep - gap, false, topLeft, Size(diameter, diameter), style = Stroke(stroke))
                    start += sweep
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(formatter.formatCompact(s.totalMinor), style = MaterialTheme.typography.titleLarge.merge(AmountStyle))
                Text(currency.symbol, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(Spacing.l))
        s.categories.forEach { stat ->
            val label = stringResource(stat.category.labelRes)
            val amount = formatter.format(stat.amountMinor, currency)
            val a11y = stringResource(R.string.analytics_category_row_a11y, label, amount, stat.percent)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(vertical = Spacing.xs)
                    .semantics(mergeDescendants = true) { contentDescription = a11y }
            ) {
                CategoryBadge(stat.category, size = 32.dp)
                Spacer(Modifier.width(Spacing.m))
                Column(Modifier.weight(1f)) {
                    Row {
                        Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(amount, style = MaterialTheme.typography.bodyMedium.merge(AmountStyle))
                    }
                    Spacer(Modifier.height(Spacing.xs))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ThinBar(stat.fraction, categoryColor(stat.category.colorHex), Modifier.weight(1f))
                        Spacer(Modifier.width(Spacing.s))
                        Text("${stat.percent}%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun TrendCard(s: AnalyticsSummary, currency: Currency, formatter: MoneyFormatter) {
    val locale = currentLocale()
    val daily = s.granularity == SeriesGranularity.DAY
    val dayFmt = remember(locale) { DateTimeFormatter.ofPattern("d MMM", locale) }
    fun pointLabel(d: LocalDate): String =
        if (daily) dayFmt.format(d) else d.month.getDisplayName(TextStyle.SHORT_STANDALONE, locale)
    val peak = s.series.maxByOrNull { it.amountMinor }
    val a11y = stringResource(
        R.string.analytics_chart_a11y,
        formatter.format(s.totalMinor, currency),
        formatter.format(peak?.amountMinor ?: 0L, currency),
        peak?.let { pointLabel(it.start) } ?: "—"
    )
    SectionCard(title = stringResource(if (daily) R.string.analytics_trend_daily else R.string.analytics_trend_monthly)) {
        val maxValue = (peak?.amountMinor ?: 0L).coerceAtLeast(1L)
        val barColor = MaterialTheme.colorScheme.primary
        val gridColor = MaterialTheme.colorScheme.outlineVariant
        val progress = remember(s.range) { Animatable(0f) }
        LaunchedEffect(s.range) { progress.animateTo(1f, tween(600, easing = FastOutSlowInEasing)) }

        Row(verticalAlignment = Alignment.Top) {
            Canvas(
                Modifier
                    .weight(1f)
                    .height(160.dp)
                    .semantics { contentDescription = a11y }
            ) {
                val n = s.series.size.coerceAtLeast(1)
                val slot = size.width / n
                val barWidth = (slot * 0.62f).coerceAtLeast(1.5f)
                // Grid lines at 0, 50 %, 100 % of the peak.
                listOf(0f, 0.5f, 1f).forEach { f ->
                    val y = size.height * (1f - f)
                    drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
                }
                s.series.forEachIndexed { i, p ->
                    val h = size.height * (p.amountMinor.toFloat() / maxValue.toFloat()) * progress.value
                    if (h > 0f) {
                        drawRoundRect(
                            color = barColor,
                            topLeft = Offset(i * slot + (slot - barWidth) / 2f, size.height - h),
                            size = Size(barWidth, h),
                            cornerRadius = CornerRadius(minOf(barWidth / 2f, 4.dp.toPx()))
                        )
                    }
                }
            }
            Spacer(Modifier.width(Spacing.s))
            Column(Modifier.height(160.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Text(formatter.formatCompact(maxValue), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(formatter.formatCompact(maxValue / 2), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("0", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (s.series.isNotEmpty()) {
            Spacer(Modifier.height(Spacing.xs))
            Row(Modifier.fillMaxWidth().clearAndSetSemantics { }) {
                Text(pointLabel(s.series.first().start), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                if (s.series.size > 1) {
                    Text(pointLabel(s.series.last().start), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RangePickerDialog(onDismiss: () -> Unit, onPicked: (LocalDate, LocalDate) -> Unit) {
    val todayUtc = remember { LocalDate.now().atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() }
    val state = rememberDateRangePickerState(
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= todayUtc
        }
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = state.selectedStartDateMillis != null && state.selectedEndDateMillis != null,
                onClick = {
                    val start = state.selectedStartDateMillis
                    val end = state.selectedEndDateMillis
                    if (start != null && end != null) {
                        // Picker values are UTC midnights: convert in UTC to get the calendar dates.
                        onPicked(
                            Instant.ofEpochMilli(start).atZone(ZoneOffset.UTC).toLocalDate(),
                            Instant.ofEpochMilli(end).atZone(ZoneOffset.UTC).toLocalDate()
                        )
                    }
                }
            ) { Text(stringResource(R.string.action_apply)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    ) {
        DateRangePicker(
            state = state,
            title = { Text(stringResource(R.string.analytics_range_title), modifier = Modifier.padding(start = Spacing.xl, top = Spacing.l)) },
            modifier = Modifier.weight(1f)
        )
    }
}
