package com.financeapp.presentation.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.core.os.ConfigurationCompat
import com.financeapp.core.money.MoneyFormatter
import java.util.Locale

/** Primary locale of the current configuration (reacts to language changes). */
@Composable
fun currentLocale(): Locale {
    val configuration = LocalConfiguration.current
    return ConfigurationCompat.getLocales(configuration)[0] ?: Locale.getDefault()
}

@Composable
fun rememberMoneyFormatter(): MoneyFormatter {
    val locale = currentLocale()
    return remember(locale) { MoneyFormatter(locale) }
}
