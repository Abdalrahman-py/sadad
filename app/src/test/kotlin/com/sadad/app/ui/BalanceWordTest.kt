package com.sadad.app.ui

import com.sadad.app.ui.components.BalanceTone
import com.sadad.app.ui.components.balanceWord
import com.sadad.app.ui.components.formatShekels
import com.sadad.ledger.Cents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BalanceWordTest {
  @Test
  fun aPositiveNetIsWhatThePersonOwesTheStore() {
    val word = balanceWord(25_000)
    assertEquals("عليه", word.word)
    assertEquals(BalanceTone.OWES, word.tone)
    assertEquals("₪ 250", word.amount)
  }

  @Test
  fun aNegativeNetIsTheirCredit() {
    val word = balanceWord(-25_000)
    assertEquals("له", word.word)
    assertEquals(BalanceTone.CREDIT, word.tone)
    assertEquals("₪ 250", word.amount)
  }

  @Test
  fun zeroIsSettledWithNoAmount() {
    val word = balanceWord(0)
    assertEquals("مسدد", word.word)
    assertEquals(BalanceTone.SETTLED, word.tone)
    assertNull(word.amount)
    assertEquals("مسدد", word.spoken)
  }

  @Test
  fun theSpokenTextNamesTheShekelAndNeverSaysMinus() {
    val owes = balanceWord(25_000).spoken
    val credit = balanceWord(-25_000).spoken
    assertEquals("عليه 250 شيكل", owes)
    assertEquals("له 250 شيكل", credit)
    for (spoken in listOf(owes, credit)) {
      assertFalse(spoken, spoken.contains('-') || spoken.contains('−'))
      assertTrue(spoken, spoken.contains("شيكل"))
    }
  }

  @Test
  fun theMoneyFormatPutsTheShekelFirstAndTrimsOnlyTrailingZeros() {
    assertEquals("₪ 1,234.5", formatShekels(Cents(123_450)))
    assertEquals("₪ 0.05", formatShekels(Cents(5)))
    assertEquals("₪ 1,000,000", formatShekels(Cents(100_000_000)))
  }
}
