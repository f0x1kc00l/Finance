package com.financeapp.core.export

import com.financeapp.core.money.Money
import com.financeapp.domain.model.Currency
import com.financeapp.domain.model.Expense
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * RFC 4180 CSV writer for expenses.
 *
 * Machine-friendly by design so a spreadsheet can re-import it in any locale:
 *  - date as ISO "yyyy-MM-dd HH:mm" in the device zone, amount with '.' decimal and exactly
 *    two digits ("1234.50"), plus an explicit ISO currency column;
 *  - every text field is quoted, embedded quotes doubled;
 *  - spreadsheet formula injection is neutralised: text starting with = + - @ TAB or CR is
 *    prefixed with an apostrophe (OWASP "CSV injection" guidance);
 *  - a UTF-8 BOM is written so Excel shows Cyrillic/Armenian correctly.
 *
 * Only the columns listed in [Labels] are exported — no internal ids or de-dup keys.
 */
object CsvExporter {

    data class Labels(
        val date: String,
        val category: String,
        val amount: String,
        val currency: String,
        val paymentMethod: String,
        val note: String
    )

    private val DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    const val BOM = "\uFEFF"

    fun write(
        out: Appendable,
        expenses: Sequence<Expense>,
        currency: Currency,
        zone: ZoneId,
        labels: Labels,
        categoryLabel: (Expense) -> String,
        methodLabel: (Expense) -> String
    ): Int {
        out.append(BOM)
        out.append(
            listOf(labels.date, labels.category, labels.amount, labels.currency, labels.paymentMethod, labels.note)
                .joinToString(",") { quote(it) }
        ).append("\r\n")
        var rows = 0
        for (e in expenses) {
            out.append(quote(DATE.format(e.occurredAt.atZone(zone)))).append(',')
                .append(quote(categoryLabel(e))).append(',')
                .append(Money.toPlainString(e.amountMinor)).append(',')
                .append(quote(currency.code)).append(',')
                .append(quote(methodLabel(e))).append(',')
                .append(quote(e.note))
                .append("\r\n")
            rows++
        }
        return rows
    }

    fun quote(raw: String): String {
        val safe = if (raw.isNotEmpty() && raw[0] in FORMULA_TRIGGERS) "'$raw" else raw
        return "\"" + safe.replace("\"", "\"\"") + "\""
    }

    private val FORMULA_TRIGGERS = charArrayOf('=', '+', '-', '@', '\t', '\r')
}
