package com.financeapp.core.analytics

import com.financeapp.core.money.Money
import com.financeapp.core.time.DateRange
import com.financeapp.core.time.Periods
import com.financeapp.domain.model.Category
import com.financeapp.domain.model.Expense
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.ZoneId

enum class SeriesGranularity { DAY, MONTH }

data class SeriesPoint(val start: LocalDate, val amountMinor: Long)

data class CategoryStat(
    val category: Category,
    val amountMinor: Long,
    val count: Int,
    /** Whole-number share of the total; the list's percents always sum to exactly 100. */
    val percent: Int,
    /** Exact share in 0..1 for drawing bars / arcs. */
    val fraction: Float
)

data class Comparison(
    val previousRange: DateRange,
    val previousTotalMinor: Long,
    /** Signed % change vs previous; null when the previous total is zero (undefined). */
    val changePercent: Int?
)

data class AnalyticsSummary(
    val range: DateRange,
    val totalMinor: Long,
    val count: Int,
    val categories: List<CategoryStat>,
    val series: List<SeriesPoint>,
    val granularity: SeriesGranularity,
    val averagePerDayMinor: Long,
    val largest: Expense?,
    val comparison: Comparison?
) {
    val isEmpty: Boolean get() = count == 0
}

data class BudgetStatus(
    val limitMinor: Long,
    val spentMinor: Long,
    /** Negative when over budget. */
    val remainingMinor: Long,
    /** May exceed 100 when over budget. */
    val usedPercent: Int,
    /** Fraction for a progress bar, clamped to 0..1. */
    val progress: Float,
    val isOver: Boolean,
    /** remaining / days left in the month (including today); null when nothing remains. */
    val dailyAllowanceMinor: Long?
)

object AnalyticsCalculator {

    /** Ranges longer than this are charted per month instead of per day. */
    const val DAILY_SERIES_MAX_DAYS = 62L

    fun summarize(
        expenses: List<Expense>,
        range: DateRange,
        zone: ZoneId,
        today: LocalDate,
        previous: Pair<DateRange, Long>? = null
    ): AnalyticsSummary {
        val from = range.startMillis(zone)
        val to = range.endExclusiveMillis(zone)
        val inRange = expenses.filter { it.occurredAt.toEpochMilli() in from until to }

        val total = Money.sum(inRange.map { it.amountMinor })

        val grouped = inRange.groupBy { it.category }
            .map { (cat, list) -> Triple(cat, Money.sum(list.map { it.amountMinor }), list.size) }
            .sortedWith(compareByDescending<Triple<Category, Long, Int>> { it.second }.thenBy { it.first.ordinal })
        val percents = largestRemainderPercents(grouped.map { it.second })
        val categories = grouped.mapIndexed { i, (cat, amount, count) ->
            CategoryStat(
                category = cat,
                amountMinor = amount,
                count = count,
                percent = percents[i],
                fraction = if (total > 0) (amount.toDouble() / total.toDouble()).toFloat() else 0f
            )
        }

        val granularity = if (range.lengthInDays <= DAILY_SERIES_MAX_DAYS) SeriesGranularity.DAY else SeriesGranularity.MONTH
        val series = buildSeries(inRange, range, zone, today, granularity)

        val elapsed = Periods.elapsedDays(range, today)
        val average = divideHalfUp(total, elapsed)

        val comparison = previous?.let { (prevRange, prevTotal) ->
            Comparison(prevRange, prevTotal, percentChange(total, prevTotal))
        }

        return AnalyticsSummary(
            range = range,
            totalMinor = total,
            count = inRange.size,
            categories = categories,
            series = series,
            granularity = granularity,
            averagePerDayMinor = average,
            largest = inRange.maxWithOrNull(compareBy<Expense> { it.amountMinor }.thenBy { it.occurredAt }),
            comparison = comparison
        )
    }

    private fun buildSeries(
        expenses: List<Expense>,
        range: DateRange,
        zone: ZoneId,
        today: LocalDate,
        granularity: SeriesGranularity
    ): List<SeriesPoint> {
        // Do not draw future buckets as "zero spending" for a period still in progress.
        val lastShown = if (range.contains(today)) today else range.lastDay
        val byDay = expenses.groupBy { it.occurredAt.atZone(zone).toLocalDate() }
            .mapValues { (_, list) -> Money.sum(list.map { it.amountMinor }) }
        return when (granularity) {
            SeriesGranularity.DAY -> range.days().filter { !it.isAfter(lastShown) }
                .map { SeriesPoint(it, byDay[it] ?: 0L) }
            SeriesGranularity.MONTH -> {
                val months = generateSequence(range.start.withDayOfMonth(1)) { it.plusMonths(1) }
                    .takeWhile { !it.isAfter(lastShown) }
                    .toList()
                months.map { m ->
                    val amount = Money.sum(byDay.filterKeys { it.year == m.year && it.month == m.month }.values)
                    SeriesPoint(m, amount)
                }
            }
        }
    }

    /**
     * Integer percentages that sum to exactly 100 (Hamilton / largest-remainder method), so a
     * legend never shows "33% + 33% + 33% = 99%". Empty or all-zero input returns zeros.
     */
    fun largestRemainderPercents(values: List<Long>): List<Int> {
        if (values.isEmpty()) return emptyList()
        require(values.all { it >= 0 }) { "negative share" }
        val total = values.fold(BigDecimal.ZERO) { a, v -> a + BigDecimal.valueOf(v) }
        if (total.signum() == 0) return values.map { 0 }
        val exact = values.map { BigDecimal.valueOf(it).multiply(BigDecimal(100)).divide(total, 12, RoundingMode.DOWN) }
        val floors = exact.map { it.setScale(0, RoundingMode.DOWN).toInt() }.toMutableList()
        var missing = 100 - floors.sum()
        val order = exact.indices.sortedWith(
            compareByDescending<Int> { exact[it] - BigDecimal(floors[it]) }.thenBy { it }
        )
        for (i in order) {
            if (missing <= 0) break
            floors[i] += 1
            missing--
        }
        return floors
    }

    /** Signed, rounded (half away from zero) % change; null when [previous] is 0. */
    fun percentChange(current: Long, previous: Long): Int? {
        if (previous == 0L) return null
        val change = BigDecimal.valueOf(current - previous).multiply(BigDecimal(100))
            .divide(BigDecimal.valueOf(previous).abs(), 0, RoundingMode.HALF_UP)
        return change.toInt()
    }

    fun budgetStatus(limitMinor: Long, spentMinor: Long, month: DateRange, today: LocalDate): BudgetStatus {
        require(limitMinor > 0) { "limit must be positive" }
        val remaining = limitMinor - spentMinor
        val used = BigDecimal.valueOf(spentMinor).multiply(BigDecimal(100))
            .divide(BigDecimal.valueOf(limitMinor), 0, RoundingMode.DOWN).toInt()
        val daysLeft = when {
            today.isBefore(month.start) -> month.lengthInDays
            !today.isBefore(month.endExclusive) -> 0L
            else -> java.time.temporal.ChronoUnit.DAYS.between(today, month.endExclusive)
        }
        return BudgetStatus(
            limitMinor = limitMinor,
            spentMinor = spentMinor,
            remainingMinor = remaining,
            usedPercent = used,
            progress = (spentMinor.toDouble() / limitMinor.toDouble()).coerceIn(0.0, 1.0).toFloat(),
            isOver = spentMinor > limitMinor,
            dailyAllowanceMinor = if (remaining > 0 && daysLeft > 0) remaining / daysLeft else null
        )
    }

    /** Rounds half-up; [divisor] must be positive. */
    fun divideHalfUp(value: Long, divisor: Long): Long {
        require(divisor > 0)
        return BigDecimal.valueOf(value).divide(BigDecimal.valueOf(divisor), 0, RoundingMode.HALF_UP).toLong()
    }
}
