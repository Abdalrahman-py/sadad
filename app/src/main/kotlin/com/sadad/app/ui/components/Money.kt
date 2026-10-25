package com.sadad.app.ui.components

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import com.sadad.ledger.Cents

/**
 * The one money formatter. Latin digits, thousands grouping like the Java app's `money()`, 0 to 2
 * decimals with trailing zeros dropped, then ` ₪`. Exact arithmetic on `Long`; never `Double`.
 */
fun formatCents(cents: Cents): String {
  val value = cents.value
  val negative = value < 0
  val magnitude = if (negative) -value else value
  val whole = magnitude / 100
  val fraction = magnitude % 100
  val decimals =
      when {
        fraction == 0L -> ""
        fraction % 10L == 0L -> ".${fraction / 10}"
        else -> ".${fraction.toString().padStart(2, '0')}"
      }
  return (if (negative) "-" else "") + groupThousands(whole) + decimals + " ₪"
}

/**
 * The one money parser. Trims; accepts Latin and Arabic-Indic digits, `.` or `٫` as the decimal
 * mark and `,` or `٬` as grouping; at most 2 decimals. Returns null for empty, negative, zero, more
 * than 2 decimals, letters or a value above [Cents.MAX_WIRE]. Never goes through `Double`.
 */
fun parseCents(text: String): Cents? {
  val trimmed = text.trim()
  if (trimmed.isEmpty()) return null
  val normalized =
      buildString(trimmed.length) {
        for (character in trimmed) {
          when {
            character in '0'..'9' -> append(character)
            character in '\u0660'..'\u0669' -> append('0' + (character - '\u0660'))
            character in '\u06F0'..'\u06F9' -> append('0' + (character - '\u06F0'))
            character == '\u066B' -> append('.')
            character == '\u066C' -> append(',')
            else -> append(character)
          }
        }
      }
  val parts = normalized.split('.')
  if (parts.size > 2) return null
  val wholeText = parts[0]
  val fractionText = if (parts.size == 2) parts[1] else ""
  if (wholeText.isEmpty() && fractionText.isEmpty()) return null
  // Grouping only in whole groups of three ("1,234"), never "12,5" (a decimal comma) or "1.2,3".
  if (!WHOLE.matches(wholeText) || !FRACTION.matches(fractionText)) return null
  val whole =
      if (wholeText.isEmpty()) 0L else wholeText.replace(",", "").toLongOrNull() ?: return null
  val fraction = if (fractionText.isEmpty()) 0L else fractionText.padEnd(2, '0').toLong()
  val cents =
      try {
        Math.addExact(Math.multiplyExact(whole, 100L), fraction)
      } catch (overflow: ArithmeticException) {
        return null
      }
  if (cents <= 0L || cents > Cents.MAX_WIRE.value) return null
  return Cents(cents)
}

/**
 * The redesign's money format: `₪` first, grouped, always two decimals («₪ 1,234.50»). Screens move
 * to it from [formatCents] as tickets 81–87 redesign them.
 */
fun formatShekels(cents: Cents): String {
  val magnitude = Math.abs(cents.value)
  val fraction = (magnitude % 100).toString().padStart(2, '0')
  return (if (cents.value < 0) "-" else "") +
      "₪ " +
      groupThousands(magnitude / 100) +
      "." +
      fraction
}

/** [formatShekels], laid out left to right inside the Arabic page. */
@Composable
fun MoneyText(
    cents: Cents,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
) {
  Text(
      text = formatShekels(cents),
      modifier = modifier,
      style = style.copy(textDirection = TextDirection.Ltr),
      color = color,
  )
}

private val WHOLE = Regex("|[0-9]+|[0-9]{1,3}(,[0-9]{3})+")
private val FRACTION = Regex("[0-9]{0,2}")

private fun groupThousands(value: Long): String {
  val digits = value.toString()
  val grouped = StringBuilder(digits.length + digits.length / 3)
  for ((index, digit) in digits.withIndex()) {
    if (index > 0 && (digits.length - index) % 3 == 0) grouped.append(',')
    grouped.append(digit)
  }
  return grouped.toString()
}
