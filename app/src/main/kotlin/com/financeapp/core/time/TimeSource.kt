package com.financeapp.core.time

import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * Injectable access to "now". Always returns a FRESH clock so a device time-zone change is
 * picked up immediately instead of being frozen in a singleton.
 */
interface TimeSource {
    fun clock(): Clock
    fun zone(): ZoneId = clock().zone
    fun today(): LocalDate = LocalDate.now(clock())
    fun nowMs(): Long = clock().millis()
    fun firstDayOfWeek(): DayOfWeek = WeekFields.of(Locale.getDefault()).firstDayOfWeek
}

object SystemTimeSource : TimeSource {
    override fun clock(): Clock = Clock.systemDefaultZone()
}
