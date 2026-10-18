package com.financeapp.presentation.screens.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.financeapp.R
import com.financeapp.core.analytics.AnalyticsSummary
import com.financeapp.core.analytics.BudgetStatus
import com.financeapp.core.money.MoneyFormatter
import com.financeapp.domain.model.Currency
import com.financeapp.domain.model.Expense
import com.financeapp.presentation.theme.AmountStyle
import com.financeapp.presentation.theme.FinanceTheme
import com.financeapp.presentation.theme.Spacing
import com.financeapp.presentation.theme.categoryColor
import com.financeapp.presentation.ui.CategoryBadge
import com.financeapp.presentation.ui.ChangeIndicator
import com.financeapp.presentation.ui.EmptyState
import com.financeapp.presentation.ui.ErrorState
import com.financeapp.presentation.ui.FinanceTopBar
import com.financeapp.presentation.ui.LabeledValue
import com.financeapp.presentation.ui.SectionCard
import com.financeapp.presentation.ui.ThinBar
import com.financeapp.presentation.ui.currentLocale
import com.financeapp.presentation.ui.labelRes
import com.financeapp.presentation.ui.rememberMoneyFormatter
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import kotlinx.coroutines.launch

/** One-shot result passed back from the add/edit screen. */
enum class HomeResult { SAVED, UPDATED, DELETED }

@Composable
fun HomeScreen(
    pendingResult: HomeResult?,
    onResultConsumed: () -> Unit,
    onAddExpense: () -> Unit,
    onEditExpense: (Long) -> Unit,
    onOpenAnalytics: () -> Unit,
    onOpenSettings: () -> Unit,
    vm: HomeViewModel = hiltViewModel()
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val formatter = rememberMoneyFormatter()

    LaunchedEffect(Unit) {
        vm.events.collect { event ->
            when (event) {
                is HomeEvent.Deleted -> scope.launch {
                    val r = snackbar.showSnackbar(
                        message = context.getString(R.string.home_deleted),
                        actionLabel = context.getString(R.string.action_undo),
                        duration = SnackbarDuration.Long
                    )
                    if (r == SnackbarResult.ActionPerformed) vm.undoDelete(event.expense)
                }
                HomeEvent.DeleteFailed -> scope.launch { snackbar.showSnackbar(context.getString(R.string.home_delete_failed)) }
            }
        }
    }
    LaunchedEffect(pendingResult) {
        if (pendingResult != null) {
            val message = context.getString(
                when (pendingResult) {
                    HomeResult.SAVED -> R.string.home_saved
                    HomeResult.UPDATED -> R.string.home_updated
                    HomeResult.DELETED -> R.string.home_deleted
                }
            )
            // Consuming the result changes this effect's key; show the snackbar in the screen's
            // scope so that key change doesn't cancel it mid-display.
            onResultConsumed()
            scope.launch { snackbar.showSnackbar(message) }
        }
    }
    BackHandler(enabled = state.searchActive) { vm.closeSearch() }

    Scaffold(
        topBar = {
            if (state.searchActive) {
                SearchBar(state.query, vm::onQueryChange, vm::closeSearch)
            } else {
                FinanceTopBar(title = stringResource(R.string.home_title)) {
                    IconButton(onClick = vm::openSearch) {
                        Icon(Icons.Rounded.Search, contentDescription = stringResource(R.string.action_search))
                    }
                    IconButton(onClick = onOpenAnalytics) {
                        Icon(Icons.Rounded.BarChart, contentDescription = stringResource(R.string.action_analytics))
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Rounded.Settings, contentDescription = stringResource(R.string.action_settings))
                    }
                }
            }
        },
        floatingActionButton = {
            if (!state.searchActive) {
                ExtendedFloatingActionButton(
                    onClick = onAddExpense,
                    icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.action_add_expense)) }
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        val listPadding = PaddingValues(
            start = Spacing.gutter, end = Spacing.gutter,
            top = padding.calculateTopPadding() + Spacing.s,
            bottom = padding.calculateBottomPadding() + 96.dp
        )
        when {
            state.searchActive -> SearchResults(state, formatter, listPadding, onEditExpense)
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.error -> Box(Modifier.fillMaxSize().padding(listPadding)) {
                ErrorState(stringResource(R.string.error_load), onRetry = vm::load)
            }
            state.isEmpty -> Box(Modifier.fillMaxSize().padding(listPadding), contentAlignment = Alignment.Center) {
                EmptyState(
                    emoji = "💸",
                    title = stringResource(R.string.home_empty_title),
                    body = stringResource(R.string.home_empty_body),
                    action = { Button(onClick = onAddExpense) { Text(stringResource(R.string.action_add_expense)) } }
                )
            }
            else -> LazyColumn(contentPadding = listPadding, verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
                state.month?.let { month ->
                    item(key = "summary") {
                        SummaryCard(month, state.budget, state.currency, formatter, onSetBudget = onOpenSettings)
                    }
                    if (month.count > 0) {
                        item(key = "insights") { InsightsCard(month, state.currency, formatter) }
                        item(key = "categories") {
                            CategoriesCard(month, state.currency, formatter, onOpenAnalytics)
                        }
                    }
                }
                item(key = "recent_header") {
                    Text(
                        stringResource(R.string.home_recent),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = Spacing.s)
                    )
                }
                items(state.recent, key = { it.id }) { expense ->
                    SwipeToDeleteRow(onDelete = { vm.delete(expense.id) }) {
                        ExpenseRow(expense, state.currency, formatter, onClick = { onEditExpense(expense.id) })
                    }
                }
            }
        }
    }
}

// ── Search ───────────────────────────────────────────────────────────────────

@Composable
private fun SearchBar(query: String, onChange: (String) -> Unit, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .padding(horizontal = Spacing.xs, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onClose) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back))
        }
        OutlinedTextField(
            value = query,
            onValueChange = onChange,
            singleLine = true,
            placeholder = { Text(stringResource(R.string.home_search_hint), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier = Modifier
                .weight(1f)
                .focusRequester(focus),
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onChange("") }) {
                        Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_clear_search))
                    }
                }
            }
        )
        Spacer(Modifier.width(Spacing.s))
    }
}

@Composable
private fun SearchResults(state: HomeUiState, formatter: MoneyFormatter, padding: PaddingValues, onEdit: (Long) -> Unit) {
    if (state.query.isNotBlank() && state.results.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(padding)) {
            EmptyState(emoji = "🔍", title = stringResource(R.string.home_search_empty), body = "")
        }
        return
    }
    LazyColumn(contentPadding = padding, verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        items(state.results, key = { it.id }) { e -> ExpenseRow(e, state.currency, formatter, onClick = { onEdit(e.id) }) }
    }
}

// ── Cards ────────────────────────────────────────────────────────────────────

@Composable
private fun SummaryCard(
    month: AnalyticsSummary,
    budget: BudgetStatus?,
    currency: Currency,
    formatter: MoneyFormatter,
    onSetBudget: () -> Unit
) {
    val locale = currentLocale()
    val monthName = month.range.start.month.getDisplayName(TextStyle.FULL_STANDALONE, locale).lowercase(locale)
    SectionCard {
        Text(
            stringResource(R.string.home_spent_this_month, monthName),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(Spacing.xs))
        Text(
            formatter.format(month.totalMinor, currency),
            style = MaterialTheme.typography.displaySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        val change = month.comparison?.changePercent
        Spacer(Modifier.height(Spacing.xs))
        if (change != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChangeIndicator(change)
                Spacer(Modifier.width(Spacing.s))
                Text(
                    stringResource(R.string.home_vs_last_month),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else if (month.count > 0) {
            Text(
                stringResource(R.string.home_no_comparison),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(Spacing.l))
        if (budget != null) {
            BudgetBlock(budget, currency, formatter)
        } else {
            TextButton(onClick = onSetBudget, contentPadding = PaddingValues(0.dp)) {
                Text(stringResource(R.string.home_set_budget))
            }
        }
    }
}

@Composable
private fun BudgetBlock(b: BudgetStatus, currency: Currency, formatter: MoneyFormatter) {
    val ext = FinanceTheme.extended
    val color = when {
        b.isOver -> ext.negative
        b.usedPercent >= 85 -> ext.warning
        else -> ext.positive
    }
    ThinBar(b.progress, color)
    Spacer(Modifier.height(Spacing.s))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            if (b.isOver) stringResource(R.string.home_budget_over, formatter.format(-b.remainingMinor, currency), formatter.format(b.limitMinor, currency))
            else stringResource(R.string.home_budget_left, formatter.format(b.remainingMinor, currency), formatter.format(b.limitMinor, currency)),
            style = MaterialTheme.typography.bodyMedium,
            color = if (b.isOver) ext.negative else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Text(
            stringResource(R.string.home_budget_used, b.usedPercent),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    b.dailyAllowanceMinor?.let {
        Text(
            stringResource(R.string.home_budget_allowance, formatter.format(it, currency)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun InsightsCard(month: AnalyticsSummary, currency: Currency, formatter: MoneyFormatter) {
    SectionCard {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
            LabeledValue(
                stringResource(R.string.home_avg_per_day),
                formatter.format(month.averagePerDayMinor, currency),
                Modifier.weight(1f)
            )
            month.largest?.let {
                LabeledValue(stringResource(R.string.home_largest), formatter.format(it.amountMinor, currency), Modifier.weight(1f))
            }
            month.categories.firstOrNull()?.let {
                LabeledValue(
                    stringResource(R.string.home_top_category),
                    "${it.category.emoji} ${stringResource(it.category.labelRes)}",
                    Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun CategoriesCard(month: AnalyticsSummary, currency: Currency, formatter: MoneyFormatter, onMore: () -> Unit) {
    SectionCard(title = stringResource(R.string.home_categories)) {
        month.categories.take(4).forEach { stat ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = Spacing.xs)) {
                CategoryBadge(stat.category, size = 32.dp)
                Spacer(Modifier.width(Spacing.m))
                Column(Modifier.weight(1f)) {
                    Row {
                        Text(
                            stringResource(stat.category.labelRes),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(formatter.format(stat.amountMinor, currency), style = MaterialTheme.typography.bodyMedium.merge(AmountStyle))
                    }
                    Spacer(Modifier.height(Spacing.xs))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ThinBar(stat.fraction, categoryColor(stat.category.colorHex), Modifier.weight(1f))
                        Spacer(Modifier.width(Spacing.s))
                        Text("${stat.percent}%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        TextButton(onClick = onMore, contentPadding = PaddingValues(0.dp)) { Text(stringResource(R.string.home_see_all)) }
    }
}

// ── Rows ─────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToDeleteRow(onDelete: () -> Unit, content: @Composable () -> Unit) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onDelete()
                true
            } else false
        }
    )
    val deleteLabel = stringResource(R.string.action_delete)
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        modifier = Modifier.semantics {
            // Swipe isn't usable with a screen reader: expose delete as an accessibility action.
            customActions = listOf(CustomAccessibilityAction(deleteLabel) { onDelete(); true })
        },
        backgroundContent = {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.errorContainer, MaterialTheme.shapes.medium)
                    .padding(horizontal = Spacing.xl),
                contentAlignment = Alignment.CenterEnd
            ) {
                Icon(Icons.Rounded.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    ) { content() }
}

@Composable
fun ExpenseRow(expense: Expense, currency: Currency, formatter: MoneyFormatter, onClick: () -> Unit) {
    val locale = currentLocale()
    val zone = ZoneId.systemDefault()
    val dateFmt = remember(locale) { DateTimeFormatter.ofPattern("d MMM", locale) }
    val timeFmt = remember(locale) { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale) }
    val at = expense.occurredAt.atZone(zone)
    val subtitle = buildString {
        append(dateFmt.format(at)).append(", ").append(timeFmt.format(at))
        if (expense.note.isNotBlank()) append(" · ").append(expense.note)
    }
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            leadingContent = { CategoryBadge(expense.category) },
            headlineContent = { Text(stringResource(expense.category.labelRes), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = { Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            trailingContent = {
                Text(
                    formatter.format(-expense.amountMinor, currency),
                    style = MaterialTheme.typography.titleSmall.merge(AmountStyle)
                )
            }
        )
    }
}
