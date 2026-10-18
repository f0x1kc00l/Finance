package com.financeapp.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.financeapp.data.local.entities.BudgetEntity
import com.financeapp.data.local.entities.ExpenseEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ExpenseDao {

    /** Half-open range [from, to) in epoch ms — uses index_expenses_timestamp. */
    @Query("SELECT * FROM expenses WHERE timestamp >= :fromMs AND timestamp < :toMs ORDER BY timestamp DESC, id DESC")
    fun observeBetween(fromMs: Long, toMs: Long): Flow<List<ExpenseEntity>>

    @Query("SELECT COALESCE(SUM(amountMinor), 0) FROM expenses WHERE timestamp >= :fromMs AND timestamp < :toMs")
    fun observeTotalBetween(fromMs: Long, toMs: Long): Flow<Long>

    @Query("SELECT * FROM expenses ORDER BY timestamp DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<ExpenseEntity>>

    @Query("SELECT COUNT(*) FROM expenses")
    fun observeCount(): Flow<Int>

    /**
     * Note search (LIKE wildcards in [escapedQuery] must already be escaped with '\') OR
     * membership in [categories] (enum names whose localized label matched the query).
     */
    @Query(
        """
        SELECT * FROM expenses
        WHERE note LIKE '%' || :escapedQuery || '%' ESCAPE '\'
           OR category IN (:categories)
        ORDER BY timestamp DESC, id DESC
        LIMIT :limit
        """
    )
    fun search(escapedQuery: String, categories: List<String>, limit: Int): Flow<List<ExpenseEntity>>

    @Query("SELECT * FROM expenses WHERE id = :id")
    suspend fun getById(id: Long): ExpenseEntity?

    @Query("SELECT * FROM expenses ORDER BY timestamp ASC, id ASC")
    suspend fun getAllChronological(): List<ExpenseEntity>

    /** Manual entries. ABORT so an accidental id collision fails loudly instead of overwriting. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(expense: ExpenseEntity): Long

    /** Auto-detected entries: returns -1 when an entry with the same sourceKey already exists. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfNew(expense: ExpenseEntity): Long

    @Update
    suspend fun update(expense: ExpenseEntity): Int

    @Query("DELETE FROM expenses WHERE id = :id")
    suspend fun deleteById(id: Long): Int
}

@Dao
interface BudgetDao {

    @Query("SELECT * FROM budgets WHERE id = ${BudgetEntity.SINGLE_BUDGET_ID}")
    fun observe(): Flow<BudgetEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(budget: BudgetEntity)

    @Query("DELETE FROM budgets")
    suspend fun clear()
}
