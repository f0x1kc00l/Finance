package com.financeapp.core.money

import com.financeapp.domain.model.Currency
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * Exact money rules shared by every screen, the notification parser and the CSV export.
 *
 * Representation: Long minor units (1/100). Supported currencies all use 2 fraction digits.
 * Business rules:
 *  - An expense amount must be in [MIN_AMOUNT_MINOR]..[MAX_AMOUNT_MINOR] (positive, capped so
 *    that summing a lifetime of entries can never overflow a Long).
 *  - Input never silently rounds: more than 2 fraction digits is rejected, not truncated.
 *  - Conversions from decimal text use BigDecimal; no Double arithmetic touches money.
 */
object Money {
    const val MINOR_PER_MAJOR = 100L
    const val FRACTION_DIGITS = 2
    const val MIN_AMOUNT_MINOR = 1L                       // 0.01
    const val MAX_AMOUNT_MINOR = 999_999_999_99L          // 999 999 999.99
    const val MAX_INTEGER_DIGITS = 9

    fun isValidAmount(minor: Long): Boolean = minor in MIN_AMOUNT_MINOR..MAX_AMOUNT_MINOR

    /**
     * Parses a user- or machine-entered decimal amount. Accepts '.' or ',' as the decimal
     * separator and ignores ordinary/no-break spaces used as group separators.
     * Returns null for empty, negative, malformed, over-precise or out-of-range input.
     */
    fun parse(text: String): Long? {
        val cleaned = text.trim()
            .replace(" ", "").replace("\u00A0", "").replace("\u202F", "")
            .replace(',', '.')
        if (cleaned.isEmpty()) return null
        if (!cleaned.matches(Regex("""\d+(\.\d{0,2})?"""))) return null
        val intPart = cleaned.substringBefore('.').trimStart('0')
        if (intPart.length > MAX_INTEGER_DIGITS) return null
        val minor = BigDecimal(if (cleaned.endsWith('.')) cleaned.dropLast(1) else cleaned)
            .movePointRight(FRACTION_DIGITS)
            .setScale(0, RoundingMode.UNNECESSARY)   // cannot round: regex limits to 2 digits
            .longValueExact()
        return if (isValidAmount(minor)) minor else null
    }

    /**
     * Converts a legacy floating-point major-unit value (database v1 stored REAL) to minor
     * units with exactly the rule the SQL migration uses: ROUND(amount * 100) — the double
     * product rounded to the nearest integer, halves away from zero. For every value the old
     * UI could produce (at most 2 decimals) this recovers the intended cents exactly
     * (e.g. 1.15 is stored as 1.1499999…; ×100 = 114.99999… → 115).
     */
    fun fromLegacyDouble(value: Double): Long =
        BigDecimal(value * MINOR_PER_MAJOR).setScale(0, RoundingMode.HALF_UP).longValueExact()

    /** Canonical machine-readable form, always with a '.' and 2 decimals: 123450 -> "1234.50". */
    fun toPlainString(minor: Long): String =
        BigDecimal.valueOf(minor, FRACTION_DIGITS).toPlainString()

    /** Editable text for an existing amount: 123400 -> "1234", 123450 -> "1234.50". */
    fun toInputString(minor: Long): String {
        val bd = BigDecimal.valueOf(minor, FRACTION_DIGITS)
        return if (minor % MINOR_PER_MAJOR == 0L) bd.setScale(0).toPlainString() else bd.toPlainString()
    }

    /** Exact sum; throws ArithmeticException instead of silently overflowing. */
    fun sum(values: Iterable<Long>): Long = values.fold(0L) { acc, v -> Math.addExact(acc, v) }
}

/**
 * Locale-aware formatting of minor-unit amounts.
 *
 * - Grouping and decimal separators come from [locale] (e.g. ru: "1 234,50", en: "1,234.50").
 * - Fraction digits are shown only when the amount has a non-zero minor part, so whole
 *   amounts stay compact ("12 500 ֏") while cents are never hidden ("12,50 €").
 * - The symbol follows the locale's usual position: before the number for English, after
 *   it (separated by a no-break space) for Russian/Armenian and most other locales.
 * - Negative values use a true minus sign (U+2212).
 */
class MoneyFormatter(private val locale: Locale) {

    private val symbols = DecimalFormatSymbols.getInstance(locale)
    private val whole = DecimalFormat("#,##0", symbols)
    private val fractional = DecimalFormat("#,##0.00", symbols)
    private val symbolFirst = locale.language == "en"

    /** Number only, no symbol. */
    fun formatNumber(minor: Long): String {
        val abs = BigDecimal.valueOf(minor, Money.FRACTION_DIGITS).abs()
        val body = if (minor % Money.MINOR_PER_MAJOR == 0L) whole.format(abs) else fractional.format(abs)
        return if (minor < 0) "\u2212$body" else body
    }

    fun format(minor: Long, currency: Currency): String {
        val number = formatNumber(kotlin.math.abs(minor))
        val sign = if (minor < 0) "\u2212" else ""
        return if (symbolFirst) "$sign${currency.symbol}$number" else "$sign$number\u00A0${currency.symbol}"
    }

    /** Short form for chart axes: 1 250 000 -> "1,3M" style using the locale decimal separator. */
    fun formatCompact(minor: Long): String {
        val major = BigDecimal.valueOf(minor, Money.FRACTION_DIGITS)
        val absMajor = major.abs()
        val (scaled, suffix) = when {
            absMajor >= BigDecimal(1_000_000) -> absMajor.divide(BigDecimal(1_000_000)) to "M"
            absMajor >= BigDecimal(1_000) -> absMajor.divide(BigDecimal(1_000)) to "K"
            else -> return formatNumber(minor - (minor % Money.MINOR_PER_MAJOR))
        }
        val oneDecimal = DecimalFormat("0.#", symbols)
        val text = oneDecimal.format(scaled.setScale(1, RoundingMode.HALF_UP)) + suffix
        return if (minor < 0) "\u2212$text" else text
    }
}
