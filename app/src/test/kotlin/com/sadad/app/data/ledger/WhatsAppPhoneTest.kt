package com.sadad.app.data.ledger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WhatsAppPhoneTest {
  @Test
  fun parsesTypedAndImportedNumbersToTheTenDigitPhone() {
    assertEquals(WhatsAppPhone.Parsed("0599123456", null), WhatsAppPhone.parse("0599123456"))
    assertEquals(WhatsAppPhone.Parsed("0599123456", "970"), WhatsAppPhone.parse("+970 599 123 456"))
    assertEquals(WhatsAppPhone.Parsed("0599123456", "972"), WhatsAppPhone.parse("00972599123456"))
    assertEquals(WhatsAppPhone.Parsed("0599123456", null), WhatsAppPhone.parse("٠٥٩٩١٢٣٤٥٦"))
    assertEquals(WhatsAppPhone.Parsed("0599123456", null), WhatsAppPhone.parse("(059) 912-3456"))
    assertEquals(WhatsAppPhone.Parsed("0599123456", "970"), WhatsAppPhone.parse("+9700599123456"))
  }

  @Test
  fun refusesWhatIsNotAMobileNumber() {
    assertNull("landline", WhatsAppPhone.parse("022912345"))
    assertNull("landline with code", WhatsAppPhone.parse("+970 2 291 2345"))
    assertNull("foreign", WhatsAppPhone.parse("+44 7911 123456"))
    assertNull("9 digits", WhatsAppPhone.parse("059912345"))
    assertNull("11 digits", WhatsAppPhone.parse("05991234567"))
    assertNull("letters", WhatsAppPhone.parse("0599abc456"))
    assertNull("empty", WhatsAppPhone.parse(""))
  }

  @Test
  fun buildsTheWaMeNumberFromTheCodeAndThePhone() {
    assertEquals("970599123456", WhatsAppPhone.waNumber("0599123456", "970"))
    assertEquals("972599123456", WhatsAppPhone.waNumber("0599123456", "972"))
  }
}
