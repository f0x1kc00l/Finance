package com.financeapp.presentation.ui

// Maps domain enums to string resources (labels are never stored in the domain layer).

import androidx.annotation.StringRes
import com.financeapp.R
import com.financeapp.domain.model.Category
import com.financeapp.domain.model.Currency
import com.financeapp.domain.model.PaymentMethod
import com.financeapp.domain.model.Period

@get:StringRes
val Category.labelRes: Int
    get() = when (this) {
        Category.FOOD -> R.string.category_food
        Category.FASTFOOD -> R.string.category_fastfood
        Category.TRANSPORT -> R.string.category_transport
        Category.UTILITIES -> R.string.category_utilities
        Category.SUBSCRIPTIONS -> R.string.category_subscriptions
        Category.ENTERTAINMENT -> R.string.category_entertainment
        Category.HEALTH -> R.string.category_health
        Category.CLOTHING -> R.string.category_clothing
        Category.BEAUTY -> R.string.category_beauty
        Category.TRANSFERS -> R.string.category_transfers
        Category.OTHER -> R.string.category_other
    }

@get:StringRes
val PaymentMethod.labelRes: Int
    get() = when (this) {
        PaymentMethod.CARD -> R.string.method_card
        PaymentMethod.CASH -> R.string.method_cash
        PaymentMethod.NFC -> R.string.method_nfc
        PaymentMethod.TRANSFER -> R.string.method_transfer
    }

@get:StringRes
val Currency.labelRes: Int
    get() = when (this) {
        Currency.AMD -> R.string.currency_amd
        Currency.RUB -> R.string.currency_rub
        Currency.USD -> R.string.currency_usd
        Currency.EUR -> R.string.currency_eur
    }

@get:StringRes
val Period.labelRes: Int
    get() = when (this) {
        Period.DAY -> R.string.period_day
        Period.WEEK -> R.string.period_week
        Period.MONTH -> R.string.period_month
        Period.YEAR -> R.string.period_year
    }
