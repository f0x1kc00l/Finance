package com.financeapp.core

import com.financeapp.core.notification.BankNotification
import com.financeapp.core.notification.BankNotificationParser
import com.financeapp.core.notification.IgnoreReason
import com.financeapp.core.notification.ParseResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixtures are SYNTHETIC — written to resemble common Armenian bank push formats in
 * Russian, English and Armenian. They are not captured from real bank apps.
 */
class BankNotificationParserTest {

    private val parser = BankNotificationParser()
    private val ameria = "am.ameriabank.mobilebank"

    private fun n(text: String, title: String = "Ameriabank", pkg: String = ameria, key: String = "k1", big: String? = null) =
        BankNotification(pkg, key, title, text, big)

    private fun debit(r: ParseResult): ParseResult.Debit {
        assertTrue("expected Debit but was $r", r is ParseResult.Debit)
        return r as ParseResult.Debit
    }

    private fun ignored(r: ParseResult): IgnoreReason {
        assertTrue("expected Ignored but was $r", r is ParseResult.Ignored)
        return (r as ParseResult.Ignored).reason
    }

    // ── Debits ───────────────────────────────────────────────────────────────

    @Test fun `russian purchase with space-grouped amount`() {
        val r = debit(parser.parse(n("Покупка 12 500 AMD. SAS SUPERMARKET")))
        assertEquals(12_500_00L, r.amountMinor)
        assertEquals("AMD", r.currencyCode)
        assertEquals("Ameriabank", r.bank)
    }

    @Test fun `comma decimal is not mistaken for thousands`() {
        // Old parser stripped ',' and recorded 1 250 000 here.
        assertEquals(12_500_00L, debit(parser.parse(n("Списание 12 500,00 AMD"))).amountMinor)
        assertEquals(12_50L, debit(parser.parse(n("Оплата 12,50 USD"))).amountMinor)
    }

    @Test fun `comma thousands and dot decimals`() {
        assertEquals(5_000_00L, debit(parser.parse(n("Purchase: AMD 5,000.00 at YANDEX GO"))).amountMinor)
        assertEquals(1_234_56L, debit(parser.parse(n("Payment 1.234,56 EUR"))).amountMinor)
        assertEquals(12_500_00L, debit(parser.parse(n("Payment 12,500 AMD"))).amountMinor)
    }

    @Test fun `armenian wording and dram sign`() {
        val r = debit(parser.parse(n("Վճարում 3 200 ֏ CARREFOUR")))
        assertEquals(3_200_00L, r.amountMinor)
        assertEquals("AMD", r.currencyCode)
        assertEquals(7_000_00L, debit(parser.parse(n("Գնում 7000 դրամ"))).amountMinor)
    }

    @Test fun `balance is never taken as the amount`() {
        val r = debit(parser.parse(n("Покупка 4 300 AMD, MENU.AM. Баланс: 245 120,50 AMD")))
        assertEquals(4_300_00L, r.amountMinor)
        val r2 = debit(parser.parse(n("Available balance AMD 100,000. Purchase AMD 2,500.00 GLOVO")))
        assertEquals(2_500_00L, r2.amountMinor)
    }

    @Test fun `minus sign marks the debit and unicode minus is normalised`() {
        assertEquals(800_00L, debit(parser.parse(n("Транзакция по карте *1234: \u2212800 AMD", title = "Списание"))).amountMinor)
    }

    @Test fun `foreign purchase prefers the app currency when both are present`() {
        val text = "Purchase 10.00 USD (3 900 AMD) NETFLIX.COM"
        assertEquals("AMD", debit(parser.parse(n(text), preferredCurrency = "AMD")).currencyCode)
        assertEquals(3_900_00L, debit(parser.parse(n(text), preferredCurrency = "AMD")).amountMinor)
        // Without a preference the amount right after the keyword wins.
        assertEquals("USD", debit(parser.parse(n(text))).currencyCode)
    }

    @Test fun `big text is used when present`() {
        val r = debit(parser.parse(n(text = "Новая операция", big = "Покупка 1 500 AMD в TUMO CAFE")))
        assertEquals(1_500_00L, r.amountMinor)
    }

    @Test fun `card number digits do not merge into the amount`() {
        assertEquals(500_00L, debit(parser.parse(n("Оплата картой 4083 500 AMD"))).amountMinor)
    }

    // ── Rejections ───────────────────────────────────────────────────────────

    @Test fun `unsupported apps are ignored`() {
        assertEquals(IgnoreReason.UNSUPPORTED_APP, ignored(parser.parse(n("Покупка 500 AMD", pkg = "com.whatsapp"))))
    }

    @Test fun `declined transactions are ignored`() {
        assertEquals(IgnoreReason.DECLINED, ignored(parser.parse(n("Покупка 5 000 AMD отклонена: недостаточно средств"))))
        assertEquals(IgnoreReason.DECLINED, ignored(parser.parse(n("Purchase AMD 5,000.00 declined"))))
    }

    @Test fun `incoming transfers and top-ups are ignored`() {
        assertEquals(IgnoreReason.INCOMING, ignored(parser.parse(n("Зачисление 50 000 AMD"))))
        assertEquals(IgnoreReason.INCOMING, ignored(parser.parse(n("Մուտք 20 000 ֏"))))
        assertEquals(IgnoreReason.INCOMING, ignored(parser.parse(n("Transaction +15,000 AMD"))))
    }

    @Test fun `refunds are ignored`() {
        assertEquals(IgnoreReason.REFUND, ignored(parser.parse(n("Возврат покупки 2 000 AMD"))))
    }

    @Test fun `informational notifications are ignored`() {
        assertEquals(IgnoreReason.INFORMATIONAL, ignored(parser.parse(n("Ваш код подтверждения: 4821"))))
        assertEquals(IgnoreReason.INFORMATIONAL, ignored(parser.parse(n("Вход в приложение с нового устройства"))))
    }

    @Test fun `payment wording without an amount is rejected`() {
        assertEquals(IgnoreReason.NO_AMOUNT, ignored(parser.parse(n("Покупка совершена успешно"))))
    }

    @Test fun `several unrelated amounts are rejected as ambiguous`() {
        assertEquals(
            IgnoreReason.AMBIGUOUS_AMOUNT,
            ignored(parser.parse(n("Транзакции: 1 000 AMD и 2 000 AMD, комиссия 100 AMD. Оплата")))
        )
    }

    @Test fun `absurd amounts are rejected`() {
        assertEquals(IgnoreReason.OUT_OF_RANGE, ignored(parser.parse(n("Покупка 12345678901 AMD"))))
    }

    // ── Number parsing ───────────────────────────────────────────────────────

    @Test fun `number parser rules`() {
        assertEquals(1_234_56L, parser.parseNumber("1,234.56"))
        assertEquals(1_234_56L, parser.parseNumber("1.234,56"))
        assertEquals(1_234_56L, parser.parseNumber("1\u00A0234,56"))
        assertEquals(1_234_000_00L, parser.parseNumber("1 234 000"))
        assertEquals(5_50L, parser.parseNumber("5,5"))
        assertNull(parser.parseNumber("1,234,56"))   // same separator for groups and decimals
        assertNull(parser.parseNumber("1 234.567")) // mixed group separators
        assertNull(parser.parseNumber("0"))
    }

    // ── De-duplication ───────────────────────────────────────────────────────

    @Test fun `same notification re-posted yields the same key`() {
        val a = debit(parser.parse(n("Покупка 500 AMD", key = "0|am.ameriabank|42|null|10123")))
        val b = debit(parser.parse(n("Покупка  500 AMD ", key = "0|am.ameriabank|42|null|10123")))
        assertEquals(a.dedupKey, b.dedupKey)
    }

    @Test fun `different notifications yield different keys`() {
        val a = debit(parser.parse(n("Покупка 500 AMD. Баланс 10 000 AMD", key = "k1")))
        val b = debit(parser.parse(n("Покупка 500 AMD. Баланс 9 500 AMD", key = "k1")))
        val c = debit(parser.parse(n("Покупка 500 AMD. Баланс 10 000 AMD", key = "k2")))
        assertNotEquals(a.dedupKey, b.dedupKey)
        assertNotEquals(a.dedupKey, c.dedupKey)
        assertEquals(64, a.dedupKey.length)
    }
}
