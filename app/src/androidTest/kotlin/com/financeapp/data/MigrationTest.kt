package com.financeapp.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.financeapp.data.local.FinanceDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies v1 → v2 against a database created from the v1 schema JSON, then lets Room
 * validate the result against the exported v2 schema (generated at build time).
 *
 * Run: ./gradlew connectedDebugAndroidTest (needs a device or emulator).
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val dbName = "migration-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        FinanceDatabase::class.java
    )

    @Test
    fun migrate1To2_preservesEveryRowAndConvertsAmountsExactly() {
        helper.createDatabase(dbName, 1).apply {
            execSQL("INSERT INTO expenses (id, amount, category, note, paymentMethod, timestamp) VALUES (1, 1.15, 'FOOD', '', 'CARD', 1000)")
            execSQL("INSERT INTO expenses (id, amount, category, note, paymentMethod, timestamp) VALUES (2, 4.35, 'TRANSPORT', 'via Ameriabank', 'NFC', 2000)")
            execSQL("INSERT INTO expenses (id, amount, category, note, paymentMethod, timestamp) VALUES (5, 12500.0, 'UNKNOWN', 'x', 'WEIRD', 3000)")
            execSQL("INSERT INTO budgets (id, monthlyLimit) VALUES (1, 150000.5)")
            close()
        }

        helper.runMigrationsAndValidate(dbName, 2, true, FinanceDatabase.MIGRATION_1_2).use { db ->
            db.query("SELECT id, amountMinor, category, note, paymentMethod, timestamp, sourceKey FROM expenses ORDER BY id").use { c ->
                assertEquals(3, c.count)
                c.moveToNext(); assertEquals(1L, c.getLong(0)); assertEquals(115L, c.getLong(1))
                c.moveToNext(); assertEquals(2L, c.getLong(0)); assertEquals(435L, c.getLong(1)); assertEquals("via Ameriabank", c.getString(3))
                c.moveToNext(); assertEquals(5L, c.getLong(0)); assertEquals(1_250_000L, c.getLong(1)); assertEquals("UNKNOWN", c.getString(2))
                assertEquals(3000L, c.getLong(5)); assertTrue(c.isNull(6))
            }
            db.query("SELECT monthlyLimitMinor FROM budgets WHERE id = 1").use { c ->
                c.moveToFirst(); assertEquals(15_000_050L, c.getLong(0))
            }
        }
    }

    @Test
    fun migratedDatabaseOpensWithRoomAndMapsUnknownEnumsSafely() {
        helper.createDatabase(dbName, 1).apply {
            execSQL("INSERT INTO expenses (id, amount, category, note, paymentMethod, timestamp) VALUES (7, 19.99, 'NOT_A_CATEGORY', '', 'NOT_A_METHOD', 4000)")
            close()
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.databaseBuilder(context, FinanceDatabase::class.java, dbName)
            .addMigrations(*FinanceDatabase.ALL_MIGRATIONS)
            .build()
        try {
            val rows = runBlocking { db.expenseDao().observeRecent(10).first() }
            assertEquals(1, rows.size)
            assertEquals(1999L, rows[0].amountMinor)
            assertNull(rows[0].sourceKey)
        } finally {
            db.close()
        }
    }
}
