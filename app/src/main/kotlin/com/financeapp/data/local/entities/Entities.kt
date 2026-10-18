package com.financeapp.data.local.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Schema v2. Column definitions must stay in sync with [com.financeapp.core.db.SchemaV2],
 * which holds the hand-written migration SQL for the same tables.
 */
@Entity(
    tableName = "expenses",
    indices = [
        Index(value = ["timestamp"]),
        Index(value = ["category"]),
        Index(value = ["sourceKey"], unique = true)
    ]
)
data class ExpenseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Exact amount in minor units (1/100). */
    val amountMinor: Long,
    /** Category enum NAME. */
    val category: String,
    val note: String,
    /** PaymentMethod enum NAME. */
    val paymentMethod: String,
    /** Epoch milliseconds (UTC instant). */
    val timestamp: Long,
    /** De-duplication hash for auto-detected transactions; NULL for manual entries. */
    val sourceKey: String? = null
)

@Entity(tableName = "budgets")
data class BudgetEntity(
    @PrimaryKey val id: Int = SINGLE_BUDGET_ID,
    val monthlyLimitMinor: Long
) {
    companion object {
        const val SINGLE_BUDGET_ID = 1
    }
}
