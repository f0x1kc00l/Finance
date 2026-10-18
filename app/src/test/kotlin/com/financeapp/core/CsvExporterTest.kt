package com.financeapp.core

import com.financeapp.core.export.CsvExporter
import com.financeapp.domain.model.Category
import com.financeapp.domain.model.Currency
import com.financeapp.domain.model.Expense
import com.financeapp.domain.model.PaymentMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

class CsvExporterTest {

    private val zone = ZoneId.of("Asia/Yerevan")
    private val labels = CsvExporter.Labels("Дата", "Категория", "Сумма", "Валюта", "Способ оплаты", "Заметка")

    private fun export(vararg e: Expense): String {
        val sb = StringBuilder()
        CsvExporter.write(sb, e.asSequence(), Currency.AMD, zone, labels, { it.category.name }, { it.paymentMethod.name })
        return sb.toString()
    }

    private fun exp(minor: Long, note: String = "") = Expense(
        id = 99,
        amountMinor = minor,
        category = Category.FOOD,
        note = note,
        paymentMethod = PaymentMethod.CARD,
        occurredAt = LocalDateTime.parse("2026-10-18T09:05").atZone(zone).toInstant(),
        sourceKey = "secret-hash"
    )

    @Test fun `amount uses dot decimals regardless of default locale`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ru-RU")) // the old exporter wrote "1234,50" here
            val lines = export(exp(1_234_50)).removePrefix(CsvExporter.BOM).split("\r\n")
            assertEquals("\"2026-10-18 09:05\",\"FOOD\",1234.50,\"AMD\",\"CARD\",\"\"", lines[1])
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test fun `every row has the same number of columns`() {
        val csv = export(exp(1_00, "a, b"), exp(2_00, "say \"hi\""), exp(3_00, "line1\nline2"))
        val header = csv.removePrefix(CsvExporter.BOM).substringBefore("\r\n")
        assertEquals(6, header.split(",").size)
        assertTrue(csv.contains("\"say \"\"hi\"\"\""))
        assertTrue(csv.contains("\"a, b\""))
    }

    @Test fun `formula injection is neutralised`() {
        val csv = export(exp(1_00, "=HYPERLINK(\"http://x\")"), exp(1_00, "+1"), exp(1_00, "@SUM(A1)"), exp(1_00, "-2"))
        assertTrue(csv.contains("\"'=HYPERLINK(\"\"http://x\"\")\""))
        assertTrue(csv.contains("\"'+1\""))
        assertTrue(csv.contains("\"'@SUM(A1)\""))
        assertTrue(csv.contains("\"'-2\""))
    }

    @Test fun `internal identifiers are not exported`() {
        val csv = export(exp(1_00))
        assertFalse(csv.contains("secret-hash"))
        assertFalse(csv.contains(",99,"))
    }

    @Test fun `starts with BOM and header`() {
        val csv = export()
        assertTrue(csv.startsWith(CsvExporter.BOM + "\"Дата\""))
    }
}
