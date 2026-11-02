package com.sadad.app.data.ledger

/**
 * The one reader of typed or imported phone numbers. A Contact's phone is the mobile number owners
 * write, `05` plus 8 digits; the WhatsApp country code is stored apart from it.
 */
object WhatsAppPhone {
  /** [code] is `970` or `972` when the number carried one, else null. */
  data class Parsed(val phone: String, val code: String?)

  private val MOBILE = Regex("05[0-9]{8}")

  /**
   * Drops `+`, `-`, parentheses and whitespace, reads Arabic-Indic digits, drops a leading `00`,
   * and reads a leading `970`/`972` as the code (restoring the leading `0` of the rest). Null when
   * the result is not a mobile number.
   */
  fun parse(raw: String): Parsed? {
    val digits = StringBuilder()
    for (character in raw) {
      val digit = Character.digit(character, 10)
      when {
        digit >= 0 -> digits.append(digit)
        character in "+-()" || character.isWhitespace() -> Unit
        else -> return null
      }
    }
    var number = digits.toString().removePrefix("00")
    var code: String? = null
    if (number.startsWith("970") || number.startsWith("972")) {
      code = number.take(3)
      number = "0" + number.drop(3).removePrefix("0")
    }
    return if (MOBILE.matches(number)) Parsed(number, code) else null
  }

  /** The `wa.me` number: the code, then the phone without its leading 0. */
  fun waNumber(phone: String, code: String): String = code + phone.removePrefix("0")
}
