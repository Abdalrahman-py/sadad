package com.sadad.ledger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CentsTest {
  @Test
  fun plusAndMinusAreExact() {
    assertEquals(Cents(250), Cents(100) + Cents(150))
    assertEquals(Cents(-50), Cents(100) - Cents(150))
    assertEquals(Cents(Long.MAX_VALUE), Cents(Long.MAX_VALUE - 1) + Cents(1))
  }

  @Test
  fun overflowThrowsInsteadOfWrapping() {
    assertThrows(ArithmeticException::class.java) { Cents(Long.MAX_VALUE) + Cents(1) }
    assertThrows(ArithmeticException::class.java) { Cents(Long.MIN_VALUE) - Cents(1) }
  }

  @Test
  fun ordersByValue() {
    assertTrue(Cents(1) < Cents(2))
    assertTrue(Cents(-1) < Cents.ZERO)
    assertEquals(
        listOf(Cents(1), Cents(5), Cents(9)),
        listOf(Cents(9), Cents(1), Cents(5)).sorted(),
    )
  }

  @Test
  fun wireLimitIsTheLargestSafeJsonInteger() {
    assertEquals(9_007_199_254_740_991L, Cents.MAX_WIRE.value)
    assertEquals(0L, Cents.ZERO.value)
  }

  @Test
  fun serializesAsABareInteger() {
    assertEquals("150", LedgerJson.encodeToString(Cents.serializer(), Cents(150)))
    assertEquals(Cents(150), LedgerJson.decodeFromString(Cents.serializer(), "150"))
  }
}
