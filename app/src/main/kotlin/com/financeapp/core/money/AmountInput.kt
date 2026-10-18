package com.financeapp.core.money

/**
 * Pure state machine behind the on-screen amount keypad.
 *
 * The buffer always uses '.' internally; the UI renders it with the locale's separator.
 * Invariants: digits only plus at most one '.', no redundant leading zeros, at most
 * [Money.MAX_INTEGER_DIGITS] integer digits and [Money.FRACTION_DIGITS] fraction digits.
 * Keys that would break an invariant are ignored (the buffer is returned unchanged).
 */
object AmountInput {

    sealed interface Key {
        data class Digit(val value: Int) : Key {
            init { require(value in 0..9) }
        }
        data object Decimal : Key
        data object Backspace : Key
        data object Clear : Key
    }

    fun apply(buffer: String, key: Key): String = when (key) {
        Key.Clear -> ""
        Key.Backspace -> buffer.dropLast(1).let { if (it == "0") "" else it }
        Key.Decimal -> when {
            buffer.contains('.') -> buffer
            buffer.isEmpty() -> "0."
            else -> "$buffer."
        }
        is Key.Digit -> appendDigit(buffer, key.value)
    }

    private fun appendDigit(buffer: String, digit: Int): String {
        val d = ('0' + digit)
        if (!buffer.contains('.')) {
            if (buffer == "0") return if (digit == 0) buffer else d.toString()
            if (buffer.length >= Money.MAX_INTEGER_DIGITS) return buffer
            return buffer + d
        }
        val fraction = buffer.substringAfter('.')
        if (fraction.length >= Money.FRACTION_DIGITS) return buffer
        return buffer + d
    }

    /**
     * Human-readable rendering of the raw buffer with locale separators, keeping a trailing
     * decimal separator while the user is typing: "12345.5" -> "12 345,5", "7." -> "7,".
     */
    fun display(buffer: String, grouping: Char, decimal: Char): String {
        if (buffer.isEmpty()) return "0"
        val intPart = buffer.substringBefore('.').ifEmpty { "0" }
        val grouped = intPart.reversed().chunked(3).joinToString(grouping.toString()).reversed()
        return if (buffer.contains('.')) grouped + decimal + buffer.substringAfter('.') else grouped
    }

    /** Minor units for a complete buffer, or null if empty / zero / out of range. */
    fun toMinor(buffer: String): Long? = Money.parse(buffer)
}
