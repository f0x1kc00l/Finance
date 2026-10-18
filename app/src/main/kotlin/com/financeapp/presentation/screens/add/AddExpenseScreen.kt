package com.financeapp.presentation.screens.add

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.financeapp.R
import com.financeapp.core.money.AmountInput
import com.financeapp.core.money.AmountInput.Key
import com.financeapp.data.repository.ExpenseRepository
import com.financeapp.domain.model.Category
import com.financeapp.domain.model.PaymentMethod
import com.financeapp.presentation.theme.AmountStyle
import com.financeapp.presentation.theme.Spacing
import com.financeapp.presentation.theme.categoryColor
import com.financeapp.presentation.ui.CategoryBadge
import com.financeapp.presentation.ui.FinanceTopBar
import com.financeapp.presentation.ui.currentLocale
import com.financeapp.presentation.ui.labelRes
import java.text.DecimalFormatSymbols
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun AddExpenseScreen(
    onBack: () -> Unit,
    onFinished: (AddOutcome) -> Unit,
    vm: AddExpenseViewModel = hiltViewModel()
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var showDatePicker by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(state.outcome) { state.outcome?.let(onFinished) }

    Scaffold(
        topBar = {
            FinanceTopBar(
                title = stringResource(if (state.isEdit) R.string.edit_title else R.string.add_title),
                onBack = onBack
            ) {
                if (state.isEdit && state.error != AddError.MISSING) {
                    IconButton(onClick = { vm.requestDelete(true) }, enabled = state.canSave) {
                        Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.action_delete))
                    }
                }
            }
        }
    ) { padding ->
        if (state.loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
        ) {
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.gutter),
                verticalArrangement = Arrangement.spacedBy(Spacing.l)
            ) {
                AmountDisplay(state)
                state.sourceCurrencyMismatch?.let { notifCurrency ->
                    Text(
                        stringResource(R.string.add_currency_mismatch, notifCurrency, state.currency.code),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                FieldLabel(stringResource(R.string.add_category), isError = state.error == AddError.CATEGORY)
                CategoryGrid(selected = state.category, onSelect = vm::onCategory)

                FieldLabel(stringResource(R.string.add_method))
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.s)
                ) {
                    PaymentMethod.entries.forEach { m ->
                        FilterChip(
                            selected = m == state.method,
                            onClick = { vm.onMethod(m) },
                            label = { Text("${m.emoji} ${stringResource(m.labelRes)}") },
                            leadingIcon = if (m == state.method) {
                                { Icon(Icons.Rounded.Check, contentDescription = null) }
                            } else null
                        )
                    }
                }

                DateChip(state.date, onClick = { showDatePicker = true })

                OutlinedTextField(
                    value = state.note,
                    onValueChange = vm::onNote,
                    label = { Text(stringResource(R.string.add_note_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    supportingText = {
                        Text(
                            stringResource(R.string.add_note_counter, state.note.length, ExpenseRepository.MAX_NOTE_LENGTH),
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.End
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                val errorText = when (state.error) {
                    AddError.AMOUNT -> stringResource(R.string.add_error_amount)
                    AddError.CATEGORY -> stringResource(R.string.add_error_category)
                    AddError.SAVE -> stringResource(R.string.add_error_save)
                    AddError.MISSING -> stringResource(R.string.add_error_missing)
                    null -> null
                }
                if (errorText != null) {
                    Text(
                        errorText,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive }
                    )
                }
                Spacer(Modifier.height(Spacing.xs))
            }

            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 0.dp) {
                Column(
                    Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.m),
                    verticalArrangement = Arrangement.spacedBy(Spacing.s)
                ) {
                    Keypad(onKey = vm::onKey)
                    Button(
                        onClick = vm::save,
                        enabled = state.canSave && state.error != AddError.MISSING,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                    ) {
                        if (state.saving) {
                            CircularProgressIndicator(Modifier.height(20.dp), strokeWidth = 2.dp)
                        } else {
                            Text(stringResource(R.string.action_save))
                        }
                    }
                }
            }
        }
    }

    if (showDatePicker) {
        DateDialog(
            initial = state.date,
            onDismiss = { showDatePicker = false },
            onPicked = {
                vm.onDate(it)
                showDatePicker = false
            }
        )
    }

    if (state.showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { vm.requestDelete(false) },
            title = { Text(stringResource(R.string.add_delete_title)) },
            text = { Text(stringResource(R.string.add_delete_body)) },
            confirmButton = {
                TextButton(onClick = vm::delete) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { vm.requestDelete(false) }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }
}

@Composable
private fun AmountDisplay(state: AddUiState) {
    val locale = currentLocale()
    val symbols = remember(locale) { DecimalFormatSymbols.getInstance(locale) }
    val text = AmountInput.display(state.buffer, symbols.groupingSeparator, symbols.decimalSeparator)
    val a11y = stringResource(R.string.add_amount_a11y, "$text ${state.currency.code}")
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = Spacing.s)
            .semantics(mergeDescendants = true) {
                contentDescription = a11y
                liveRegion = LiveRegionMode.Polite
            },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            stringResource(R.string.add_amount_label),
            style = MaterialTheme.typography.labelMedium,
            color = if (state.error == AddError.AMOUNT) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text,
                style = MaterialTheme.typography.displayMedium.merge(AmountStyle),
                color = if (state.buffer.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            Text(
                " ${state.currency.symbol}",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        state.source?.let {
            Text(
                stringResource(R.string.add_from_bank, it),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun FieldLabel(text: String, isError: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun CategoryGrid(selected: Category?, onSelect: (Category) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Category.entries.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                row.forEach { cat ->
                    val isSelected = cat == selected
                    val color = categoryColor(cat.colorHex)
                    Surface(
                        onClick = { onSelect(cat) },
                        shape = MaterialTheme.shapes.medium,
                        color = if (isSelected) color.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceContainer,
                        border = BorderStroke(if (isSelected) 2.dp else 1.dp, if (isSelected) color else MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 76.dp)
                            .semantics {
                                this.selected = isSelected
                                role = Role.RadioButton
                            }
                    ) {
                        Column(
                            Modifier.padding(vertical = Spacing.s, horizontal = Spacing.xs),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                        ) {
                            CategoryBadge(cat, size = 32.dp)
                            Text(
                                stringResource(cat.labelRes),
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun DateChip(date: LocalDate, onClick: () -> Unit) {
    val locale = currentLocale()
    val today = LocalDate.now()
    val label = if (date == today) stringResource(R.string.add_today)
    else DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(date)
    AssistChip(
        onClick = onClick,
        label = { Text("${stringResource(R.string.add_date)}: $label") },
        leadingIcon = { Icon(Icons.Rounded.CalendarMonth, contentDescription = null, modifier = Modifier.height(AssistChipDefaults.IconSize)) }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateDialog(initial: LocalDate, onDismiss: () -> Unit, onPicked: (LocalDate) -> Unit) {
    // DatePicker works in UTC-midnight millis; convert explicitly to avoid off-by-one days.
    val todayUtc = remember { LocalDate.now().atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() }
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= todayUtc
            override fun isSelectableYear(year: Int) = year <= LocalDate.now().year
        }
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    state.selectedDateMillis?.let { millis ->
                        onPicked(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate())
                    }
                },
                enabled = state.selectedDateMillis != null
            ) { Text(stringResource(R.string.action_apply)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    ) {
        DatePicker(state = state)
    }
}

@Composable
private fun Keypad(onKey: (Key) -> Unit) {
    val haptic = LocalHapticFeedback.current
    val locale = currentLocale()
    val decimal = remember(locale) { DecimalFormatSymbols.getInstance(locale).decimalSeparator.toString() }
    val rows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf(".", "0", "<")
    )
    val decimalLabel = stringResource(R.string.add_keypad_decimal)
    val backspaceLabel = stringResource(R.string.add_keypad_backspace)
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                row.forEach { k ->
                    val key = when (k) {
                        "." -> Key.Decimal
                        "<" -> Key.Backspace
                        else -> Key.Digit(k.toInt())
                    }
                    Surface(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onKey(key)
                        },
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 52.dp)
                            .semantics {
                                when (k) {
                                    "." -> contentDescription = decimalLabel
                                    "<" -> contentDescription = backspaceLabel
                                }
                            }
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.heightIn(min = 52.dp)) {
                            when (k) {
                                "<" -> Icon(Icons.AutoMirrored.Rounded.Backspace, contentDescription = null)
                                "." -> Text(decimal, style = MaterialTheme.typography.titleLarge, modifier = Modifier.clearAndSetSemantics { })
                                else -> Text(k, style = MaterialTheme.typography.titleLarge)
                            }
                        }
                    }
                }
            }
        }
    }
}
