package com.financeapp.core.time

import com.financeapp.domain.model.Period
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * Half-open range of local calendar days: [start, endExclusive).
 *
 * Every summary in the app (home card, analytics, budget, CSV) is computed from one of these,
 * converted to epoch milliseconds with the SAME zone, so totals on different screens always
 * agree. Using an exclusive end avoids the classic "23:59:59.999" off-by-one.
 */
data class DateRange(val start: LocalDate, val endExclusive: LocalDate) {
    init { require(endExclusive.isAfter(start)) { "empty range $start..$endExclusive" } }

    val lengthInDays: Long get() = ChronoUnit.DAYS.between(start, endExclusive)
    val lastDay: LocalDate get() = endExclusive.minusDays(1)

    fun contains(date: LocalDate): Boolean = !date.isBefore(start) && date.isBefore(endExclusive)

    fun startMillis(zone: ZoneId): Long = start.atStartOfDay(zone).toInstant().toEpochMilli()
    fun endExclusiveMillis(zone: ZoneId): Long = endExclusive.atStartOfDay(zone).toInstant().toEpochMilli()

    fun days(): List<LocalDate> = (0 until lengthInDays).map { start.plusDays(it) }

    companion object {
        /** Inclusive [from]..[to] as picked in a date-range picker. Order-insensitive. */
        fun inclusive(from: LocalDate, to: LocalDate): DateRange {
            val (a, b) = if (to.isBefore(from)) to to from else from to to
            return DateRange(a, b.plusDays(1))
        }
    }
}

object Periods {

    /** The calendar period containing [today]. Weeks start on [firstDayOfWeek] (locale-dependent). */
    fun current(period: Period, today: LocalDate, firstDayOfWeek: DayOfWeek): DateRange = when (period) {
        Period.DAY -> DateRange(today, today.plusDays(1))
        Period.WEEK -> {
            val start = today.with(TemporalAdjusters.previousOrSame(firstDayOfWeek))
            DateRange(start, start.plusWeeks(1))
        }
        Period.MONTH -> {
            val start = today.withDayOfMonth(1)
            DateRange(start, start.plusMonths(1))
        }
        Period.YEAR -> {
            val start = today.withDayOfYear(1)
            DateRange(start, start.plusYears(1))
        }
    }

    /** The full calendar period immediately before [current]. */
    fun previous(period: Period, current: DateRange): DateRange = when (period) {
        Period.DAY -> DateRange(current.start.minusDays(1), current.start)
        Period.WEEK -> DateRange(current.start.minusWeeks(1), current.start)
        Period.MONTH -> DateRange(current.start.minusMonths(1), current.start)
        Period.YEAR -> DateRange(current.start.minusYears(1), current.start)
    }

    /**
     * Like-for-like comparison window: the same number of elapsed days at the start of the
     * previous period ("1–18 Oct" is compared with "1–18 Sep"), capped to that period's end
     * (31 Mar is compared with the whole of February). If [today] is outside [current]
     * (a finished period), the whole previous period is used.
     */
    fun previousToDate(period: Period, current: DateRange, today: LocalDate): DateRange {
        val prev = previous(period, current)
        if (!current.contains(today)) return prev
        val elapsedDays = ChronoUnit.DAYS.between(current.start, today) + 1
        val end = minOf(prev.start.plusDays(elapsedDays), prev.endExclusive)
        return DateRange(prev.start, end)
    }

    /** The range of equal length immediately preceding an arbitrary (custom) range. */
    fun precedingOfSameLength(range: DateRange): DateRange =
        DateRange(range.start.minusDays(range.lengthInDays), range.start)

    /** Number of days of [range] that have already started as of [today] (at least 1). */
    fun elapsedDays(range: DateRange, today: LocalDate): Long = when {
        today.isBefore(range.start) -> 1
        !today.isBefore(range.endExclusive) -> range.lengthInDays
        else -> ChronoUnit.DAYS.between(range.start, today) + 1
    }
}

/**
 * Emits the current local date (and zone) and re-emits when either changes — at midnight,
 * or when the user travels / changes the device time zone. Lets period-based flows
 * re-query their range instead of freezing the range computed at subscription time.
 */
fun currentDateFlow(clock: () -> Clock = { Clock.systemDefaultZone() }, pollMillis: Long = 30_000L): Flow<Pair<LocalDate, ZoneId>> = flow {
    while (true) {
        val c = clock()
        emit(LocalDate.now(c) to c.zone)
        delay(pollMillis)
    }
}.distinctUntilChanged()
