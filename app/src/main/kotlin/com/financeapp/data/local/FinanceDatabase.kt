package com.financeapp.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.financeapp.core.db.SchemaV2
import com.financeapp.data.local.dao.BudgetDao
import com.financeapp.data.local.dao.ExpenseDao
import com.financeapp.data.local.entities.BudgetEntity
import com.financeapp.data.local.entities.ExpenseEntity

/**
 * Room database.
 *
 * Schema history (JSON exported to app/schemas/, commit every new file):
 *  - v1: amounts stored as REAL major units.
 *  - v2: exact INTEGER minor units, de-dup key, indexes. See [MIGRATION_1_2].
 *
 * Migration checklist: bump version → add a Migration below → register it in AppModule →
 * build once so Room exports `<version>.json` → add a MigrationTest case → commit the JSON.
 * Destructive migrations are never allowed for user data.
 */
@Database(
    entities = [ExpenseEntity::class, BudgetEntity::class],
    version = 2,
    exportSchema = true
)
abstract class FinanceDatabase : RoomDatabase() {
    abstract fun expenseDao(): ExpenseDao
    abstract fun budgetDao(): BudgetDao

    companion object {
        const val NAME = "finance_db"

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                SchemaV2.MIGRATION_1_2.forEach(db::execSQL)
            }
        }

        val ALL_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2)
    }
}
