package com.sadad.app.data.ledger

/**
 * The phone the WhatsApp update or share link needs: Arabic-Indic digits, `00`, a leading `0` and
 * either `970`/`972` prefix are all read, and the result is `+<prefix><9 digits>`. A number that
 * already carries the other prefix is refused rather than rewritten, as the Java app did.
 */
object WhatsAppPhone {
  fun normalize(value: String, prefix: String): String {
    require(prefix == "970" || prefix == "972") { "اختر مقدمة فلسطين أو إسرائيل." }
    val digits = StringBuilder()
    for (character in value.trim()) {
      val digit = Character.digit(character, 10)
      when {
        digit >= 0 -> digits.append(digit)
        character in "+-() " || character.isWhitespace() -> Unit
        else -> throw IllegalArgumentException("أدخل رقم واتساب صحيحًا.")
      }
    }
    var number = digits.toString()
    if (number.startsWith("00")) number = number.substring(2)
    if (number.startsWith("970") || number.startsWith("972")) {
      require(number.startsWith(prefix)) { "مقدمة الرقم لا تطابق الاختيار." }
      number = number.substring(3)
    }
    if (number.startsWith("0")) number = number.substring(1)
    require(number.matches(Regex("[1-9][0-9]{8}"))) {
      "أدخل رقم الجوال من 9 أرقام بعد المقدمة، أو 10 أرقام تبدأ بصفر."
    }
    return "+$prefix$number"
  }
}
