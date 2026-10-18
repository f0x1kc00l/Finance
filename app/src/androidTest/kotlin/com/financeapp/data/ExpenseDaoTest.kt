package com.financeapp.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.financeapp.data.local.FinanceDatabase
import com.financeapp.data.local.entities.ExpenseEntity
import com.financeapp.data.repository.ExpenseRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExpenseDaoTest {

    private lateinit var db: FinanceDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, FinanceDatabase::class.java).build()
    }

    @After fun tearDown() = db.close()

    private fun e(minor: Long, ts: Long, note: String = "", key: String? = null, cat: String = "FOOD") =
        ExpenseEntity(amountMinor = minor, category = cat, note = note, paymentMethod = "CARD", timestamp = ts, sourceKey = key)

    @Test fun rangeIsHalfOpenAndTotalsMatch() = runBlocking {
        val dao = db.expenseDao()
        dao.insert(e(100, 999)); dao.insert(e(200, 1000)); dao.insert(e(300, 1999)); dao.insert(e(400, 2000))
        assertEquals(listOf(300L, 200L), dao.observeBetween(1000, 2000).first().map { it.amountMinor })
        assertEquals(500L, dao.observeTotalBetween(1000, 2000).first())
        assertEquals(0L, dao.observeTotalBetween(5000, 6000).first())
    }

    @Test fun duplicateSourceKeyIsIgnoredButNullKeysAreNot() = runBlocking {
        val dao = db.expenseDao()
        assertEquals(1L, dao.insertIfNew(e(100, 1, key = "k")))
        assertEquals(-1L, dao.insertIfNew(e(100, 2, key = "k")))
        dao.insert(e(100, 3)); dao.insert(e(100, 4))
        assertEquals(3, dao.observeCount().first())
    }

    @Test fun searchEscapesWildcardsAndMatchesCategories() = runBlocking {
        val dao = db.expenseDao()
        dao.insert(e(1, 1, note = "100% juice")); dao.insert(e(1, 2, note = "1000 juice"))
        dao.insert(e(1, 3, note = "", cat = "HEALTH"))
        val pct = dao.search(ExpenseRepository.escapeLike("100%"), emptyList(), 50).first()
        assertEquals(listOf("100% juice"), pct.map { it.note })
        val byCat = dao.search(ExpenseRepository.escapeLike("zzz"), listOf("HEALTH"), 50).first()
        assertEquals(1, byCat.size)
    }

    @Test fun updateAndDelete() = runBlocking {
        val dao = db.expenseDao()
        val id = dao.insert(e(100, 1))
        assertEquals(1, dao.update(e(250, 1).copy(id = id)))
        assertEquals(250L, dao.getById(id)!!.amountMinor)
        assertEquals(1, dao.deleteById(id))
        assertEquals(0, dao.deleteById(id))
    }
}
