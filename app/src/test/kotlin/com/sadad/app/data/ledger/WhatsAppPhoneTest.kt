package com.sadad.app.data.ledger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WhatsAppPhoneTest {
  @Test
  fun normalizesThePhoneNumbersTheContactFormAccepts() {
    assertEquals("+970599123456", WhatsAppPhone.normalize("0599123456", "970"))
    assertEquals("+970599123456", WhatsAppPhone.normalize("٠٥٩٩١٢٣٤٥٦", "970"))
    assertEquals("+970599123456", WhatsAppPhone.normalize("00970599123456", "970"))
    assertEquals("+970599123456", WhatsAppPhone.normalize("+970 599-123-456", "970"))
  }

  @Test
  fun refusesANumberThatDoesNotMatchTheChosenPrefix() {
    assertTrue(runCatching { WhatsAppPhone.normalize("+972599123456", "970") }.isFailure)
    assertTrue(runCatching { WhatsAppPhone.normalize("12345", "970") }.isFailure)
    assertTrue(runCatching { WhatsAppPhone.normalize("0599123456", "973") }.isFailure)
  }
}
