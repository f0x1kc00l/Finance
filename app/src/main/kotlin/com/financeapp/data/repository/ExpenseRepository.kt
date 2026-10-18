package com.financeapp.data.repository

import androidx.room.withTransaction
import com.financeapp.core.analytics.AnalyticsCalculator
import com.financeapp.core.analytics.AnalyticsSummary
import com.financeapp.core.money.Money
import com.financeapp.core.time.DateRange
import com.financeapp.core.time.Periods
import com.financeapp.core.time.TimeSource
import com.financeapp.core.time.currentDateFlow
import com.financeapp.data.local.FinanceDatabase
import com.financeapp.data.local.dao.BudgetDao
import com.financeapp.data.local.dao.ExpenseDao
import com.financeapp.data.local.entities.BudgetEntity
import com.financeapp.data.local.entities.ExpenseEntity
import com.financeapp.di.DefaultDispatcher
import com.financeapp.domain.model.Budget
import com.financeapp.domain.model.Category
import com.financeapp.domain.model.Expense
import com.financeapp.domain.model.PaymentMethod
import com.financeapp.domain.model.Period
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** What period a summary covers. */
sealed interface PeriodSpec {
    data class Preset(val period: Period) : PeriodSpec
    /** Inclusive user-picked dates, stored half-open. */
    data class Custom(val range: DateRange) : PeriodSpec
}

sealed interface DetectedInsert {
    data class Inserted(val id: Long) : DetectedInsert
    /** The same bank notification was already recorded. */
    data object Duplicate : DetectedInsert
}

/**
 * Expenses and budget persistence plus derived summaries.
 *
 * Every period-based flow is driven by [currentDateFlow], so ranges roll over at midnight and
 * follow time-zone changes, and every summary uses the same half-open [DateRange] → epoch-ms
 * conversion. (v1.0 computed the range once, ending at subscription time, so expenses added
 * afterwards never appeared in the monthly total until the screen was rebuilt.)
 *
 * Mutations validate amounts and throw on invalid input; callers wrap them with
 * runCatchingCancellable and surface failures in UI state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class ExpenseRepository @Inject constructor(
    private val db: FinanceDatabase,
    private val expenseDao: ExpenseDao,
    private val budgetDao: BudgetDao,
    private val time: TimeSource,
    @DefaultDispatcher private val computation: CoroutineDispatcher
) {

    // ── Queries ──────────────────────────────────────────────────────────────

    fun observeRecent(limit: Int = RECENT_LIMIT): Flow<List<Expense>> =
        expenseDao.observeRecent(limit).map { list -> list.map { it.toDomain() } }

    fun observeCount(): Flow<Int> = expenseDao.observeCount()

    /**
     * Searches notes (case-insensitive for ASCII; SQLite LIKE limitation) and categories whose
     * localized label matched (resolved by the caller, since labels live in resources).
     */
    fun search(query: String, matchingCategories: Collection<Category>): Flow<List<Expense>> =
        expenseDao.search(escapeLike(query.trim()), matchingCategories.map { it.name }, SEARCH_LIMIT)
            .map { list -> list.map { it.toDomain() } }

    fun observeSummary(spec: PeriodSpec): Flow<AnalyticsSummary> =
        currentDateFlow(clock = { time.clock() }).flatMapLatest { (today, zone) ->
            val range = when (spec) {
                is PeriodSpec.Preset -> Periods.current(spec.period, today, time.firstDayOfWeek())
                is PeriodSpec.Custom -> spec.range
            }
            val previous = when (spec) {
                is PeriodSpec.Preset -> Periods.previousToDate(spec.period, range, today)
                is PeriodSpec.Custom -> Periods.precedingOfSameLength(spec.range)
            }
            combine(
                expenseDao.observeBetween(range.startMillis(zone), range.endExclusiveMillis(zone)),
                expenseDao.observeTotalBetween(previous.startMillis(zone), previous.endExclusiveMillis(zone))
            ) { entities, previousTotal ->
                AnalyticsCalculator.summarize(
                    expenses = entities.map { it.toDomain() },
                    range = range,
                    zone = zone,
                    today = today,
                    previous = previous to previousTotal
                )
            }
        }.flowOn(computation)

    suspend fun getById(id: Long): Expense? = expenseDao.getById(id)?.toDomain()

    suspend fun getAllForExport(): List<Expense> = expenseDao.getAllChronological().map { it.toDomain() }

    // ── Mutations ────────────────────────────────────────────────────────────

    /** Inserts a manual expense and returns its id. */
    suspend fun add(expense: Expense): Long {
        validate(expense)
        return expenseDao.insert(expense.copy(id = 0).toEntity())
    }

    /** Inserts an auto-detected expense unless the same notification was already recorded. */
    suspend fun addDetected(expense: Expense): DetectedInsert {
        validate(expense)
        requireNotNull(expense.sourceKey) { "detected expense needs a sourceKey" }
        val id = expenseDao.insertIfNew(expense.copy(id = 0).toEntity())
        return if (id == -1L) DetectedInsert.Duplicate else DetectedInsert.Inserted(id)
    }

    /** Returns false if the expense no longer exists (deleted elsewhere). */
    suspend fun update(expense: Expense): Boolean {
        require(expense.id > 0) { "update needs an id" }
        validate(expense)
        return db.withTransaction {
            val existing = expenseDao.getById(expense.id) ?: return@withTransaction false
            // sourceKey is identity metadata — editing never changes it.
            expenseDao.update(expense.toEntity().copy(sourceKey = existing.sourceKey)) == 1
        }
    }

    /** Deletes and returns the removed expense (for undo), or null if it was already gone. */
    suspend fun delete(id: Long): Expense? = db.withTransaction {
        val existing = expenseDao.getById(id) ?: return@withTransaction null
        expenseDao.deleteById(id)
        existing.toDomain()
    }

    /** Re-inserts a just-deleted expense with its original id (undo). */
    suspend fun restore(expense: Expense): Long {
        validate(expense)
        return expenseDao.insertIfNew(expense.toEntity())
    }

    // ── Budget ───────────────────────────────────────────────────────────────

    fun observeBudget(): Flow<Budget?> =
        budgetDao.observe().map { e -> e?.let { Budget(it.monthlyLimitMinor) } }.distinctUntilChanged()

    suspend fun saveBudget(limitMinor: Long) {
        require(Money.isValidAmount(limitMinor)) { "invalid budget" }
        budgetDao.save(BudgetEntity(monthlyLimitMinor = limitMinor))
    }

    suspend fun clearBudget() = budgetDao.clear()

    // ── Mapping ──────────────────────────────────────────────────────────────

    private fun validate(e: Expense) {
        require(Money.isValidAmount(e.amountMinor)) { "amount out of range" }
        require(e.note.length <= MAX_NOTE_LENGTH) { "note too long" }
    }

    private fun ExpenseEntity.toDomain() = Expense(
        id = id,
        amountMinor = amountMinor,
        category = Category.fromStorage(category),
        note = note,
        paymentMethod = PaymentMethod.fromStorage(paymentMethod),
        occurredAt = Instant.ofEpochMilli(timestamp),
        sourceKey = sourceKey
    )

    private fun Expense.toEntity() = ExpenseEntity(
        id = id,
        amountMinor = amountMinor,
        category = category.name,
        note = note,
        paymentMethod = paymentMethod.name,
        timestamp = occurredAt.toEpochMilli(),
        sourceKey = sourceKey
    )

    companion object {
        const val RECENT_LIMIT = 30
        const val SEARCH_LIMIT = 200
        const val MAX_NOTE_LENGTH = 200

        /** Escapes LIKE wildcards so "50%" searches literally. Pairs with ESCAPE '\' in the DAO. */
        fun escapeLike(q: String): String = q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
    }
}
