package com.sadad.app.ui

import com.sadad.app.ui.components.formatCents
import com.sadad.app.ui.components.parseCents
import com.sadad.ledger.Cents
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoneyTest {
  @Test
  fun parsesAcceptedText() {
    val cases =
        mapOf(
            "12" to 1200L,
            "12.5" to 1250L,
            "12.50" to 1250L,
            "١٢٫٥٠" to 1250L,
            "۱۲" to 1200L,
            "1,234.56" to 123456L,
            "1,234,567" to 123456700L,
            "١٬٢٣٤٫٥" to 123450L,
            ".5" to 50L,
            " 7 " to 700L,
        )
    cases.forEach { (text, cents) -> assertEquals(text, Cents(cents), parseCents(text)) }
  }

  @Test
  fun rejectsBadText() {
    val rejected =
        listOf(
            "",
            "0",
            "0.00",
            "-1",
            "12.345",
            "abc",
            "1e3",
            "90071992547409.92",
            // A decimal comma or a misplaced grouping mark is refused, never read as a bigger
            // amount.
            "12,5",
            "1,23",
            "1,2345",
            ",123",
            "1.2,3",
            "1,234,5",
            // Only Latin and Arabic-Indic digits.
            "१२",
            "１２",
        )
    rejected.forEach { text -> assertNull(text, parseCents(text)) }
  }

  @Test
  fun formatsLikeTheJavaApp() {
    assertEquals("123,456 ₪", formatCents(Cents(12_345_600)))
    assertEquals("1,234.5 ₪", formatCents(Cents(123_450)))
    assertEquals("1,234.56 ₪", formatCents(Cents(123_456)))
    assertEquals("0.05 ₪", formatCents(Cents(5)))
    assertEquals("0 ₪", formatCents(Cents(0)))
  }

  @Test
  fun roundTripsRandomValues() {
    val random = Random(43)
    repeat(1_000) {
      val cents = 1L + random.nextLong(1_000_000_000_000L)
      val text = formatCents(Cents(cents)).removeSuffix(" ₪")
      assertEquals(cents, parseCents(text)?.value)
    }
  }
}
