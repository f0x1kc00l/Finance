package com.financeapp.core.notification

import com.financeapp.core.money.Money
import java.math.BigDecimal
import java.security.MessageDigest

/**
 * Raw, already-extracted notification fields. Built by the Android listener from
 * StatusBarNotification extras; kept platform-free so parsing is unit-testable.
 */
data class BankNotification(
    val packageName: String,
    /** StatusBarNotification.key — stable across updates of the same notification. */
    val notificationKey: String,
    val title: String,
    val text: String,
    val bigText: String? = null
)

sealed interface ParseResult {
    /** An outgoing payment the user can confirm. Nothing is saved automatically. */
    data class Debit(
        val bank: String,
        val amountMinor: Long,
        /** ISO 4217 code as written in the notification. */
        val currencyCode: String,
        /** Stable hash used to de-duplicate repeated/updated notifications. */
        val dedupKey: String
    ) : ParseResult

    data class Ignored(val reason: IgnoreReason) : ParseResult
}

enum class IgnoreReason {
    UNSUPPORTED_APP,
    DECLINED,
    INCOMING,          // credit, top-up, received transfer
    REFUND,
    INFORMATIONAL,     // no payment wording (OTP codes, promos, login alerts…)
    NO_AMOUNT,
    AMBIGUOUS_AMOUNT,  // several different candidate amounts and no rule picks one
    OUT_OF_RANGE
}

/**
 * Parses push notifications from supported Armenian bank apps.
 *
 * Pipeline: normalize → classify (declined / incoming / refund / debit / informational)
 * → extract money tokens → drop balance/limit tokens → choose a single amount or reject.
 *
 * Design rules:
 *  - Ambiguous input is rejected, never guessed. A rejected notification simply doesn't
 *    produce a prompt; the user can still add the expense manually.
 *  - Decimal handling: "12 500", "12,500", "12.500" are grouped thousands; "12,50"/"12.50"
 *    are decimals; "1.234,56" and "1,234.56" are both 1234.56.
 *  - No notification text is retained: only bank name, amount, currency and a one-way hash.
 *
 * IMPORTANT: bank package names and phrasing below are best-effort. They are covered by
 * synthetic test fixtures (see BankNotificationParserTest), NOT by captured notifications
 * from real bank apps. See README "Bank notification detection" before relying on them.
 */
class BankNotificationParser(
    private val supportedPackages: Map<String, String> = DEFAULT_BANK_PACKAGES
) {

    fun parse(n: BankNotification, preferredCurrency: String? = null): ParseResult {
        val bank = supportedPackages[n.packageName] ?: return ParseResult.Ignored(IgnoreReason.UNSUPPORTED_APP)

        val body = listOf(n.title, n.bigText?.takeIf { it.isNotBlank() } ?: n.text)
            .filter { it.isNotBlank() }
            .joinToString(" ")
        val text = normalize(body)
        val lower = text.lowercase()

        if (DECLINED_KW.any { lower.contains(it) }) return ParseResult.Ignored(IgnoreReason.DECLINED)
        if (REFUND_KW.any { lower.contains(it) }) return ParseResult.Ignored(IgnoreReason.REFUND)
        if (INCOMING_KW.any { lower.contains(it) }) return ParseResult.Ignored(IgnoreReason.INCOMING)

        val tokens = extractMoneyTokens(text)
        // An explicit '+' marks money coming in, whatever the wording says.
        val outgoing = tokens.filterNot { it.sign == '+' }
        if (tokens.isNotEmpty() && outgoing.isEmpty()) return ParseResult.Ignored(IgnoreReason.INCOMING)

        if (DEBIT_KW.none { lower.contains(it) }) return ParseResult.Ignored(IgnoreReason.INFORMATIONAL)
        if (tokens.isEmpty()) return ParseResult.Ignored(IgnoreReason.NO_AMOUNT)

        val candidates = outgoing.filterNot { it.isBalanceLike }
        if (candidates.isEmpty()) return ParseResult.Ignored(IgnoreReason.NO_AMOUNT)

        // Malformed or out-of-range tokens (minor == null) never become an amount.
        val valid = candidates.filter { it.minor != null }
        if (valid.isEmpty()) return ParseResult.Ignored(IgnoreReason.OUT_OF_RANGE)

        val chosen = chooseAmount(valid, tokens, lower, preferredCurrency)
            ?: return ParseResult.Ignored(IgnoreReason.AMBIGUOUS_AMOUNT)

        return ParseResult.Debit(
            bank = bank,
            amountMinor = chosen.minor!!,
            currencyCode = chosen.currency,
            dedupKey = dedupKey(n.packageName, n.notificationKey, text)
        )
    }

    /**
     * Picks one amount from several candidates, or returns null when no rule is decisive.
     * [all] (every token, in text order) bounds the "words before a token" window so that
     * a keyword is attributed only to the first amount that follows it.
     */
    private fun chooseAmount(valid: List<MoneyToken>, all: List<MoneyToken>, lower: String, preferredCurrency: String?): MoneyToken? {
        val distinct = valid.distinctBy { it.minor to it.currency }
        if (distinct.size == 1) return distinct.first()

        // 1. Exactly one candidate in the app's currency (e.g. "10.00 USD (4 000 AMD)").
        if (preferredCurrency != null) {
            val inPreferred = distinct.filter { it.currency == preferredCurrency }
            if (inPreferred.size == 1) return inPreferred.first()
        }
        // 2. A candidate that directly follows debit wording ("Purchase: 5 000 AMD …").
        val afterKeyword = valid.filter { tok ->
            DEBIT_KW.any { wordsBefore(tok, all, lower, KEYWORD_WINDOW).contains(it) }
        }.distinctBy { it.minor to it.currency }
        if (afterKeyword.size == 1) return afterKeyword.first()
        // 3. An explicit minus sign marks the debited amount.
        val negative = distinct.filter { it.sign == '-' }
        if (negative.size == 1) return negative.first()
        return null
    }

    // ── Token extraction ─────────────────────────────────────────────────────

    internal data class MoneyToken(
        val start: Int,
        val end: Int,
        val sign: Char?,
        val minor: Long?,
        val currency: String,
        val isBalanceLike: Boolean = false
    )

    /** Text between the previous token (or [window] chars back) and [tok]. */
    private fun wordsBefore(tok: MoneyToken, all: List<MoneyToken>, lower: String, window: Int): String {
        val prevEnd = all.filter { it.end <= tok.start }.maxOfOrNull { it.end } ?: 0
        val from = maxOf(prevEnd, tok.start - window, 0)
        return lower.substring(from, tok.start)
    }

    internal fun extractMoneyTokens(text: String): List<MoneyToken> {
        val lower = text.lowercase()
        val raw = mutableListOf<MoneyToken>()
        val claimed = BooleanArray(text.length)

        fun add(start: Int, end: Int, sign: String?, number: String, currencyRaw: String) {
            if ((start until end).any { claimed[it] }) return
            val code = CURRENCY_ALIASES[currencyRaw.lowercase()] ?: return
            for (i in start until end) claimed[i] = true
            raw += MoneyToken(start, end, sign?.firstOrNull(), parseNumber(number), code)
        }

        // "5 000.00 AMD", "-12,50 €", "1.234,56 руб."
        NUMBER_THEN_CURRENCY.findAll(text).forEach { m ->
            add(m.range.first, m.range.last + 1, m.groups[1]?.value?.takeIf { it.isNotEmpty() }, m.groupValues[2], m.groupValues[3])
        }
        // "AMD 5,000.00", "$12.50", "- € 3,10"
        CURRENCY_THEN_NUMBER.findAll(text).forEach { m ->
            add(m.range.first, m.range.last + 1, m.groups[1]?.value?.takeIf { it.isNotEmpty() }, m.groupValues[3], m.groupValues[2])
        }
        val sorted = raw.sortedBy { it.start }
        return sorted.map { tok ->
            tok.copy(isBalanceLike = BALANCE_KW.any { wordsBefore(tok, sorted, lower, BALANCE_WINDOW).contains(it) })
        }
    }

    /**
     * Converts a matched number to minor units, or null if it is malformed or out of range.
     * Group separators: space, NBSP, NNBSP, '.', ','. Decimal: last '.'/',' followed by 1–2 digits.
     */
    internal fun parseNumber(raw: String): Long? {
        val s = raw.replace('\u00A0', ' ').replace('\u202F', ' ').trim()
        // A trailing separator followed by exactly 1–2 digits is the decimal part ("12,50",
        // "1 234.5"). Three trailing digits ("12,500") are always a thousands group.
        val decimalMatch = Regex("""^(.*?)[.,](\d{1,2})$""").find(s)
        val (intPart, fracPart) = if (decimalMatch != null) {
            decimalMatch.groupValues[1] to decimalMatch.groupValues[2]
        } else s to ""
        // Integer part: plain digits, or digit groups of 3 with ONE consistent separator.
        val groupSeps = intPart.filter { it == ' ' || it == '.' || it == ',' }.toSet()
        if (groupSeps.size > 1) return null
        if (groupSeps.isNotEmpty()) {
            val sep = groupSeps.first()
            val groups = intPart.split(sep)
            if (groups.first().length !in 1..3 || groups.drop(1).any { it.length != 3 }) return null
            if (decimalMatch != null && fracPart.isNotEmpty()) {
                val decSep = s[s.length - fracPart.length - 1]
                if (decSep == sep) return null // "1,234,56" — inconsistent
            }
        }
        val digits = intPart.filter { it.isDigit() }
        if (digits.isEmpty() || digits.trimStart('0').length > Money.MAX_INTEGER_DIGITS) return null
        val minor = BigDecimal(digits + "." + fracPart.padEnd(2, '0')).movePointRight(2).longValueExact()
        return minor.takeIf { Money.isValidAmount(it) }
    }

    companion object {
        /** Package → display name. Unverified against real installs; see class KDoc. */
        val DEFAULT_BANK_PACKAGES: Map<String, String> = mapOf(
            "am.ameriabank.mobilebank" to "Ameriabank",
            "am.inecobank.app" to "Inecobank",
            "am.inecobank.mobilebank" to "Inecobank",
            "am.ardshinbank.mobilebanking" to "Ardshinbank",
            "am.ardshinbank.app" to "Ardshinbank"
        )

        private const val BALANCE_WINDOW = 24
        private const val KEYWORD_WINDOW = 32

        private val DECLINED_KW = listOf(
            "отклонен", "отклонён", "отказ", "не выполнен", "недостаточно средств",
            "declined", "rejected", "failed", "unsuccessful", "insufficient",
            "մերժվ", "չի կատարվել", "անհաջող"
        )
        private val REFUND_KW = listOf(
            "возврат", "refund", "reversal", "отмена операции", "chargeback",
            "վերադարձ", "չեղարկ"
        )
        private val INCOMING_KW = listOf(
            "пополнение", "зачисление", "поступление", "получен перевод", "перевод получен", "вам перевели",
            "credited", "received", "deposit", "top up", "top-up", "topup", "incoming",
            "մուտք", "համալրում", "ստացվել", "ստացել եք"
        )
        private val DEBIT_KW = listOf(
            "оплата", "списание", "списано", "покупка", "платёж", "платеж", "расход", "снятие",
            "payment", "purchase", "debit", "debited", "charged", "spent", "withdrawal", "pos ",
            "վճարում", "դուրսգրում", "գնում", "ելք", "կանխիկացում"
        )
        private val BALANCE_KW = listOf(
            "баланс", "остаток", "доступно", "доступный", "лимит",
            "balance", "available", "avail", "limit",
            "մնացորդ", "հասանելի", "սահմանաչափ"
        )

        /** Lower-cased currency token → ISO 4217. */
        private val CURRENCY_ALIASES: Map<String, String> = mapOf(
            "amd" to "AMD", "֏" to "AMD", "драм" to "AMD", "драмов" to "AMD", "դրամ" to "AMD", "dram" to "AMD",
            "usd" to "USD", "$" to "USD",
            "eur" to "EUR", "€" to "EUR",
            "rub" to "RUB", "₽" to "RUB", "руб" to "RUB", "руб." to "RUB"
        )

        private const val NUM = """\d{1,3}(?:[ \u00A0\u202F.,]\d{3})+(?:[.,]\d{1,2})?|\d+(?:[.,]\d{1,2})?"""
        private const val CUR = """amd|֏|драмов|драм|դրամ|dram|usd|\$|eur|€|rub|₽|руб\.?"""

        private val NUMBER_THEN_CURRENCY = Regex(
            """(?<![\d.,])([-+])?\s?($NUM)\s?($CUR)(?![\p{L}])""",
            RegexOption.IGNORE_CASE
        )
        private val CURRENCY_THEN_NUMBER = Regex(
            """([-+])?\s?($CUR)\s?($NUM)(?![\d])""",
            RegexOption.IGNORE_CASE
        )

        /** Unicode minus/dashes → '-', exotic spaces kept (parser handles them), collapse runs. */
        fun normalize(s: String): String = s
            .replace('\u2212', '-').replace('\u2013', '-').replace('\u2014', '-')
            .replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
            .replace(Regex(" {2,}"), " ")
            .trim()

        fun dedupKey(packageName: String, notificationKey: String, normalizedText: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest("$packageName\u0000$notificationKey\u0000$normalizedText".toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}
