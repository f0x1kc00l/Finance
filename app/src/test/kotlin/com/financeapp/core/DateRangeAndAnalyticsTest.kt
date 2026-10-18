package com.financeapp.core

import com.financeapp.core.analytics.AnalyticsCalculator
import com.financeapp.core.analytics.SeriesGranularity
import com.financeapp.core.time.DateRange
import com.financeapp.core.time.Periods
import com.financeapp.domain.model.Category
import com.financeapp.domain.model.Expense
import com.financeapp.domain.model.Period
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class PeriodsTest {

    private val d = LocalDate.of(2026, 10, 18) // a Sunday

    @Test fun `month range is half-open over the whole calendar month`() {
        val r = Periods.current(Period.MONTH, d, DayOfWeek.MONDAY)
        assertEquals(LocalDate.of(2026, 10, 1), r.start)
        assertEquals(LocalDate.of(2026, 11, 1), r.endExclusive)
        assertEquals(31L, r.lengthInDays)
    }

    @Test fun `week start follows the locale's first day`() {
        assertEquals(LocalDate.of(2026, 10, 12), Periods.current(Period.WEEK, d, DayOfWeek.MONDAY).start)
        assertEquals(LocalDate.of(2026, 10, 18), Periods.current(Period.WEEK, d, DayOfWeek.SUNDAY).start)
    }

    @Test fun `year and day ranges`() {
        assertEquals(DateRange(LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1)), Periods.current(Period.YEAR, d, DayOfWeek.MONDAY))
        assertEquals(DateRange(d, d.plusDays(1)), Periods.current(Period.DAY, d, DayOfWeek.MONDAY))
    }

    @Test fun `previous-to-date compares like for like and caps at short months`() {
        val oct = Periods.current(Period.MONTH, d, DayOfWeek.MONDAY)
        assertEquals(DateRange(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 19)), Periods.previousToDate(Period.MONTH, oct, d))

        val mar31 = LocalDate.of(2026, 3, 31)
        val mar = Periods.current(Period.MONTH, mar31, DayOfWeek.MONDAY)
        assertEquals(DateRange(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 3, 1)), Periods.previousToDate(Period.MONTH, mar, mar31))
    }

    @Test fun `year boundary`() {
        val jan1 = LocalDate.of(2027, 1, 1)
        val month = Periods.current(Period.MONTH, jan1, DayOfWeek.MONDAY)
        assertEquals(DateRange(LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 2)), Periods.previousToDate(Period.MONTH, month, jan1))
    }

    @Test fun `inclusive custom range accepts reversed input`() {
        val r = DateRange.inclusive(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 1))
        assertEquals(LocalDate.of(2026, 10, 1), r.start)
        assertEquals(LocalDate.of(2026, 10, 6), r.endExclusive)
        assertEquals(DateRange(LocalDate.of(2026, 9, 26), LocalDate.of(2026, 10, 1)), Periods.precedingOfSameLength(r))
    }

    @Test fun `millis use the given zone and DST days are handled`() {
        val berlin = ZoneId.of("Europe/Berlin")
        val dstEnd = DateRange(LocalDate.of(2026, 10, 25), LocalDate.of(2026, 10, 26)) // 25-hour day
        assertEquals(25L * 3_600_000, dstEnd.endExclusiveMillis(berlin) - dstEnd.startMillis(berlin))
    }
}

class AnalyticsCalculatorTest {

    private val zone = ZoneId.of("Asia/Yerevan")
    private val today = LocalDate.of(2026, 10, 18)
    private val october = DateRange(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 1))

    private fun exp(minor: Long, cat: Category, at: String, id: Long = 0) = Expense(
        id = id,
        amountMinor = minor,
        category = cat,
        occurredAt = LocalDateTime.parse(at).atZone(zone).toInstant()
    )

    @Test fun `totals, counts and category shares`() {
        val list = listOf(
            exp(1_000_00, Category.FOOD, "2026-10-02T10:00"),
            exp(500_00, Category.FOOD, "2026-10-03T10:00"),
            exp(1_500_00, Category.TRANSPORT, "2026-10-03T12:00"),
            exp(999_00, Category.HEALTH, "2026-09-30T23:59"), // outside: previous month
        )
        val s = AnalyticsCalculator.summarize(list, october, zone, today)
        assertEquals(3_000_00L, s.totalMinor)
        assertEquals(3, s.count)
        assertEquals(listOf(Category.FOOD, Category.TRANSPORT), s.categories.map { it.category }) // tie -> stable by ordinal
        assertEquals(listOf(50, 50), s.categories.map { it.percent })
        assertEquals(2, s.categories.first { it.category == Category.FOOD }.count)
    }

    @Test fun `range boundaries are inclusive at start and exclusive at end`() {
        val list = listOf(
            exp(1_00, Category.OTHER, "2026-10-01T00:00"),
            exp(2_00, Category.OTHER, "2026-10-31T23:59:59"),
            exp(4_00, Category.OTHER, "2026-11-01T00:00"),
        )
        assertEquals(3_00L, AnalyticsCalculator.summarize(list, october, zone, today).totalMinor)
    }

    @Test fun `empty data yields zeros and no division by zero`() {
        val s = AnalyticsCalculator.summarize(emptyList(), october, zone, today)
        assertTrue(s.isEmpty)
        assertEquals(0L, s.totalMinor)
        assertEquals(0L, s.averagePerDayMinor)
        assertTrue(s.categories.isEmpty())
        assertNull(s.largest)
        assertEquals(18, s.series.size) // 1..18 Oct, all zero; no future days
        assertTrue(s.series.all { it.amountMinor == 0L })
    }

    @Test fun `daily series fills gaps with zero and stops at today`() {
        val list = listOf(
            exp(100_00, Category.FOOD, "2026-10-01T09:00"),
            exp(50_00, Category.FOOD, "2026-10-01T20:00"),
            exp(30_00, Category.FOOD, "2026-10-04T09:00"),
        )
        val s = AnalyticsCalculator.summarize(list, october, zone, today)
        assertEquals(SeriesGranularity.DAY, s.granularity)
        assertEquals(LocalDate.of(2026, 10, 18), s.series.last().start)
        assertEquals(150_00L, s.series[0].amountMinor)
        assertEquals(0L, s.series[1].amountMinor)
        assertEquals(30_00L, s.series[3].amountMinor)
        assertEquals(s.totalMinor, s.series.sumOf { it.amountMinor })
    }

    @Test fun `long ranges are charted per month`() {
        val year = DateRange(LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1))
        val list = listOf(exp(10_00, Category.FOOD, "2026-01-15T10:00"), exp(20_00, Category.FOOD, "2026-03-15T10:00"))
        val s = AnalyticsCalculator.summarize(list, year, zone, today)
        assertEquals(SeriesGranularity.MONTH, s.granularity)
        assertEquals(10, s.series.size) // Jan..Oct
        assertEquals(listOf(10_00L, 0L, 20_00L), s.series.take(3).map { it.amountMinor })
    }

    @Test fun `average per day uses elapsed days of an ongoing period`() {
        val list = listOf(exp(18_00, Category.FOOD, "2026-10-05T10:00"))
        assertEquals(1_00L, AnalyticsCalculator.summarize(list, october, zone, today).averagePerDayMinor)
        val sept = DateRange(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 1))
        val s = AnalyticsCalculator.summarize(listOf(exp(30_00, Category.FOOD, "2026-09-05T10:00")), sept, zone, today)
        assertEquals(1_00L, s.averagePerDayMinor)
    }

    @Test fun `percentages always sum to 100`() {
        val p = AnalyticsCalculator.largestRemainderPercents(listOf(1L, 1L, 1L))
        assertEquals(100, p.sum())
        assertEquals(listOf(34, 33, 33), p)
        assertEquals(listOf(0, 0), AnalyticsCalculator.largestRemainderPercents(listOf(0L, 0L)))
        val q = AnalyticsCalculator.largestRemainderPercents(listOf(9_999_99L, 1L, 1L, 1L))
        assertEquals(100, q.sum())
        assertEquals(100, q[0])
    }

    @Test fun `percent change handles zero and sign`() {
        assertNull(AnalyticsCalculator.percentChange(500, 0))
        assertEquals(50, AnalyticsCalculator.percentChange(150, 100))
        assertEquals(-25, AnalyticsCalculator.percentChange(75, 100))
        assertEquals(-100, AnalyticsCalculator.percentChange(0, 100))
        assertEquals(33, AnalyticsCalculator.percentChange(4, 3)) // 33.3 -> 33
        assertEquals(-67, AnalyticsCalculator.percentChange(1, 3)) // -66.7 -> -67
    }

    @Test fun `comparison is attached when previous total is given`() {
        val prev = DateRange(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 19))
        val s = AnalyticsCalculator.summarize(listOf(exp(200_00, Category.FOOD, "2026-10-05T10:00")), october, zone, today, prev to 100_00L)
        assertEquals(100, s.comparison!!.changePercent)
        assertEquals(prev, s.comparison!!.previousRange)
    }

    @Test fun `budget status under and over limit`() {
        val under = AnalyticsCalculator.budgetStatus(100_000_00, 25_000_00, october, today)
        assertEquals(25, under.usedPercent)
        assertFalse(under.isOver)
        assertEquals(75_000_00L, under.remainingMinor)
        // 14 days left (18..31 Oct inclusive)
        assertEquals(75_000_00L / 14, under.dailyAllowanceMinor)

        val over = AnalyticsCalculator.budgetStatus(100_00, 130_00, october, today)
        assertTrue(over.isOver)
        assertEquals(130, over.usedPercent)
        assertEquals(1f, over.progress, 0f)
        assertEquals(-30_00L, over.remainingMinor)
        assertNull(over.dailyAllowanceMinor)
    }

    @Test fun `largest expense is reported`() {
        val list = listOf(exp(5_00, Category.FOOD, "2026-10-02T10:00", 1), exp(7_00, Category.HEALTH, "2026-10-03T10:00", 2))
        assertEquals(2L, AnalyticsCalculator.summarize(list, october, zone, today).largest!!.id)
    }
}
