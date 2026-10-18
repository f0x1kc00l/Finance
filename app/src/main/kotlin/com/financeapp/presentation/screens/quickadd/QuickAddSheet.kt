package com.financeapp.presentation.screens.quickadd

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.financeapp.R
import com.financeapp.domain.model.Category
import com.financeapp.domain.model.Currency
import com.financeapp.presentation.theme.AmountStyle
import com.financeapp.presentation.theme.Spacing
import com.financeapp.presentation.ui.CategoryBadge
import com.financeapp.presentation.ui.labelRes
import com.financeapp.presentation.ui.rememberMoneyFormatter
import kotlinx.coroutines.delay

@Composable
fun QuickAddSheet(
    vm: QuickAddViewModel,
    onDismiss: () -> Unit,
    onMoreDetails: (amountMinor: Long, bank: String) -> Unit
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val formatter = rememberMoneyFormatter()

    LaunchedEffect(state.status) {
        if (state.status == QuickAddStatus.SAVED) {
            delay(700)
            onDismiss()
        }
    }

    // Scrim: tapping outside the sheet dismisses it.
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            ),
        contentAlignment = Alignment.BottomCenter
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge.copy(
                bottomStart = androidx.compose.foundation.shape.CornerSize(0.dp),
                bottomEnd = androidx.compose.foundation.shape.CornerSize(0.dp)
            ),
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                // Consume clicks so taps inside the sheet don't reach the scrim.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
        ) {
            Column(
                Modifier
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(Spacing.xl),
                verticalArrangement = Arrangement.spacedBy(Spacing.m)
            ) {
                Text(stringResource(R.string.quick_title), style = MaterialTheme.typography.titleMedium)
                val amountText = Currency.fromIsoCode(state.notificationCurrency)
                    ?.let { formatter.format(state.amountMinor, it) }
                    ?: "${formatter.formatNumber(state.amountMinor)} ${state.notificationCurrency}"
                Text(amountText, style = MaterialTheme.typography.headlineMedium.merge(AmountStyle))
                if (state.bank.isNotBlank()) {
                    Text(state.bank, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                when (state.status) {
                    QuickAddStatus.INVALID -> StatusText(stringResource(R.string.quick_invalid), MaterialTheme.colorScheme.error)
                    QuickAddStatus.DUPLICATE -> StatusText(stringResource(R.string.quick_duplicate), MaterialTheme.colorScheme.onSurfaceVariant)
                    QuickAddStatus.ERROR -> StatusText(stringResource(R.string.quick_error), MaterialTheme.colorScheme.error)
                    QuickAddStatus.SAVED -> StatusText(stringResource(R.string.quick_saved), MaterialTheme.colorScheme.primary)
                    else -> Unit
                }
                if (state.currencyMismatch && state.status == QuickAddStatus.READY) {
                    StatusText(
                        stringResource(R.string.quick_currency_mismatch, state.notificationCurrency, state.appCurrency.code),
                        MaterialTheme.colorScheme.error
                    )
                }

                if (state.status == QuickAddStatus.SAVING) {
                    Box(Modifier.fillMaxWidth().padding(Spacing.l), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else if (state.canQuickSave) {
                    Text(stringResource(R.string.quick_where), style = MaterialTheme.typography.labelLarge)
                    Category.entries.chunked(4).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                            row.forEach { cat ->
                                CategoryTile(cat, Modifier.weight(1f)) { vm.save(cat) }
                            }
                            repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.action_skip)) }
                    if (state.status != QuickAddStatus.INVALID && state.status != QuickAddStatus.SAVED) {
                        OutlinedButton(
                            onClick = { onMoreDetails(state.amountMinor, state.bank) },
                            enabled = state.status != QuickAddStatus.SAVING,
                            modifier = Modifier.weight(1f)
                        ) { Text(stringResource(R.string.quick_more)) }
                    }
                }
                if (state.status == QuickAddStatus.ERROR) {
                    TextButton(onClick = vm::retry) { Text(stringResource(R.string.action_retry)) }
                }
            }
        }
    }
}

@Composable
private fun StatusText(text: String, color: Color) {
    Text(
        text,
        color = color,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
    )
}

@Composable
private fun CategoryTile(category: Category, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.heightIn(min = 72.dp)
    ) {
        Column(
            Modifier.padding(vertical = Spacing.s, horizontal = Spacing.xs),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            CategoryBadge(category, size = 32.dp)
            Text(
                stringResource(category.labelRes),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }
}
