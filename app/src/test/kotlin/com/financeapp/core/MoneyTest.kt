package com.financeapp.core

import com.financeapp.core.money.AmountInput
import com.financeapp.core.money.AmountInput.Key
import com.financeapp.core.money.Money
import com.financeapp.core.money.MoneyFormatter
import com.financeapp.domain.model.Currency
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Locale

class MoneyParseTest {

    @Test fun `parses whole and fractional amounts exactly`() {
        assertEquals(500_00L, Money.parse("500"))
        assertEquals(12_50L, Money.parse("12.5"))
        assertEquals(12_50L, Money.parse("12,50"))
        assertEquals(1L, Money.parse("0.01"))
        assertEquals(10L, Money.parse("0,1"))
        // the classic binary-float trap: 0.1 + 0.2 style values stay exact
        assertEquals(1_15L, Money.parse("1.15"))
        assertEquals(4_35L, Money.parse("4.35"))
    }

    @Test fun `ignores group spaces including no-break spaces`() {
        assertEquals(1_234_567_00L, Money.parse("1 234 567"))
        assertEquals(1_234_50L, Money.parse("1\u00A0234,50"))
        assertEquals(1_234_50L, Money.parse("1\u202F234.50"))
    }

    @Test fun `rejects zero, negative, malformed and over-precise input`() {
        assertNull(Money.parse(""))
        assertNull(Money.parse("   "))
        assertNull(Money.parse("0"))
        assertNull(Money.parse("0.00"))
        assertNull(Money.parse("-5"))
        assertNull(Money.parse("1.234")) // three decimals must not be silently rounded
        assertNull(Money.parse("1.2.3"))
        assertNull(Money.parse("abc"))
        assertNull(Money.parse("1e5"))
    }

    @Test fun `enforces upper bound`() {
        assertEquals(Money.MAX_AMOUNT_MINOR, Money.parse("999999999.99"))
        assertNull(Money.parse("1000000000"))
        assertNull(Money.parse("99999999999999999999999"))
    }

    @Test fun `plain string is canonical with two decimals`() {
        assertEquals("1234.50", Money.toPlainString(1_234_50))
        assertEquals("0.01", Money.toPlainString(1))
        assertEquals("5000.00", Money.toPlainString(5_000_00))
    }

    @Test fun `input string round-trips`() {
        assertEquals("1234", Money.toInputString(1_234_00))
        assertEquals("1234.50", Money.toInputString(1_234_50))
        assertEquals(1_234_50L, Money.parse(Money.toInputString(1_234_50)))
    }

    @Test fun `legacy double conversion rounds half up to the cent`() {
        assertEquals(1_234_50L, Money.fromLegacyDouble(1234.5))
        assertEquals(1_15L, Money.fromLegacyDouble(1.15))         // 1.149999… in binary
        assertEquals(19_99L, Money.fromLegacyDouble(19.99))
        assertEquals(4_35L, Money.fromLegacyDouble(4.35))         // 4.35 * 100 = 434.99999… in binary
        assertEquals(5_000_00L, Money.fromLegacyDouble(5000.0))
    }

    @Test fun `sum detects overflow instead of wrapping`() {
        assertEquals(30L, Money.sum(listOf(10L, 20L)))
        assertThrows(ArithmeticException::class.java) { Money.sum(listOf(Long.MAX_VALUE, 1L)) }
    }
}

class AmountInputTest {

    private fun type(vararg keys: Key): String = keys.fold("") { acc, k -> AmountInput.apply(acc, k) }
    private fun d(v: Int) = Key.Digit(v)

    @Test fun `builds a decimal amount`() {
        assertEquals("12.5", type(d(1), d(2), Key.Decimal, d(5)))
        assertEquals(12_50L, AmountInput.toMinor("12.5"))
    }

    @Test fun `no leading zeros and single decimal point`() {
        assertEquals("0", type(d(0), d(0)))
        assertEquals("5", type(d(0), d(5)))
        assertEquals("0.", type(Key.Decimal))
        assertEquals("1.", type(d(1), Key.Decimal, Key.Decimal))
    }

    @Test fun `limits fraction to two digits and integer to nine`() {
        assertEquals("1.23", type(d(1), Key.Decimal, d(2), d(3), d(4)))
        assertEquals("999999999", type(*Array(12) { d(9) }))
    }

    @Test fun `backspace and clear`() {
        assertEquals("1", type(d(1), d(2), Key.Backspace))
        assertEquals("", type(d(1), Key.Clear))
        assertEquals("", type(Key.Decimal, Key.Backspace)) // "0." -> "0" -> collapses to empty
    }

    @Test fun `display uses locale separators and keeps a trailing decimal`() {
        assertEquals("0", AmountInput.display("", ' ', ','))
        assertEquals("12 345,5", AmountInput.display("12345.5", ' ', ','))
        assertEquals("1,234,567.05", AmountInput.display("1234567.05", ',', '.'))
        assertEquals("7,", AmountInput.display("7.", ' ', ','))
        assertEquals("0,", AmountInput.display("0.", ' ', ','))
    }

    @Test fun `incomplete buffers are not valid amounts`() {
        assertNull(AmountInput.toMinor(""))
        assertNull(AmountInput.toMinor("0."))
        assertEquals(1_00L, AmountInput.toMinor("1."))
    }
}

class MoneyFormatterTest {

    private val ru = MoneyFormatter(Locale.forLanguageTag("ru-RU"))
    private val en = MoneyFormatter(Locale.US)

    private fun norm(s: String) = s.replace('\u00A0', ' ').replace('\u202F', ' ')

    @Test fun `russian locale uses space grouping, comma decimals and trailing symbol`() {
        assertEquals("12 500 ֏", norm(ru.format(12_500_00, Currency.AMD)))
        assertEquals("1 234,50 ₽", norm(ru.format(1_234_50, Currency.RUB)))
    }

    @Test fun `english locale uses comma grouping and leading symbol`() {
        assertEquals("$1,234.50", en.format(1_234_50, Currency.USD))
        assertEquals("€0.05", en.format(5, Currency.EUR))
    }

    @Test fun `negative values use a real minus sign`() {
        assertEquals("\u2212$12", en.format(-12_00, Currency.USD))
    }

    @Test fun `compact form for chart axes`() {
        assertEquals("1.3M", en.formatCompact(1_250_000_00))
        assertEquals("12K", en.formatCompact(12_000_00))
        assertEquals("950", en.formatCompact(950_40))
        assertEquals("1,5K", ru.formatCompact(1_500_00))
    }
}
