package com.financeapp.domain.model

import java.time.Instant

/**
 * A single expense.
 *
 * Money is stored as an exact integer number of minor units ([amountMinor]; 1/100 of the
 * major unit for every supported currency), never as a floating-point value.
 *
 * [occurredAt] is an absolute instant. It is converted to a local date only for display and
 * for period aggregation, so changing the device time zone never rewrites stored data.
 */
data class Expense(
    val id: Long = 0,
    val amountMinor: Long,
    val category: Category,
    val note: String = "",
    val paymentMethod: PaymentMethod = PaymentMethod.CARD,
    val occurredAt: Instant,
    /**
     * Opaque de-duplication key for automatically detected transactions (a hash of the
     * notification identity). `null` for manually entered expenses. Unique in the database.
     */
    val sourceKey: String? = null
)

/**
 * Category identifiers. The enum NAME is persisted in the database — never rename a constant.
 * User-facing labels live in Android string resources (see presentation `CategoryUi`).
 */
enum class Category(val emoji: String, val colorHex: Long) {
    FOOD("🛒", 0xFFE5674F),
    FASTFOOD("🍔", 0xFFF29A38),
    TRANSPORT("🚇", 0xFF3D8FE0),
    UTILITIES("🏠", 0xFF5FA845),
    SUBSCRIPTIONS("📱", 0xFF9C5FD6),
    ENTERTAINMENT("🎮", 0xFFE0577B),
    HEALTH("💊", 0xFF2BA58C),
    CLOTHING("👗", 0xFFD9609F),
    BEAUTY("💅", 0xFFB66AD6),
    TRANSFERS("💸", 0xFFC9A227),
    OTHER("📦", 0xFF7D8590);

    companion object {
        fun fromStorage(name: String): Category = entries.firstOrNull { it.name == name } ?: OTHER
    }
}

/** Persisted by NAME — never rename a constant. */
enum class PaymentMethod(val emoji: String) {
    CARD("💳"),
    CASH("💵"),
    NFC("📲"),
    TRANSFER("🔄");

    companion object {
        fun fromStorage(name: String): PaymentMethod = entries.firstOrNull { it.name == name } ?: CARD
    }
}

/**
 * Display currency. The app is single-currency: all amounts are interpreted in the currency
 * selected in Settings. Switching currency changes only the label — amounts are NOT converted
 * (the app has no exchange-rate source and must never invent one).
 *
 * [fractionDigits] follows ISO 4217 (all supported currencies have 2 minor digits).
 */
enum class Currency(val symbol: String, val code: String, val fractionDigits: Int = 2) {
    AMD("֏", "AMD"),
    RUB("₽", "RUB"),
    USD("$", "USD"),
    EUR("€", "EUR");

    companion object {
        fun fromStorage(name: String?): Currency = entries.firstOrNull { it.name == name } ?: AMD
        fun fromIsoCode(code: String?): Currency? = entries.firstOrNull { it.code.equals(code, ignoreCase = true) }
    }
}

/** Monthly spending limit in minor units of the selected currency. */
data class Budget(val monthlyLimitMinor: Long)

enum class Period { DAY, WEEK, MONTH, YEAR }
