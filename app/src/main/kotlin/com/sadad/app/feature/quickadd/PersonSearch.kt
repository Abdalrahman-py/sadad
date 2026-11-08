package com.sadad.app.feature.quickadd

import com.sadad.app.ui.components.BalanceWordText
import com.sadad.app.ui.components.balanceWord
import com.sadad.ledger.Cents
import com.sadad.ledger.ContactKind
import java.time.Duration
import java.time.Instant
import java.util.Locale

/** One person the picker can offer: what the row shows, and what ordering needs. */
data class QuickAddPerson(
    val id: String,
    val name: String,
    val phone: String,
    val kind: ContactKind,
    /** On the store's side: positive means this person owes the store. */
    val net: Cents,
    /** When the store last recorded anything with them; null for a person with no entries yet. */
    val lastActivity: Instant?,
    /** The projection's moment: what [detailLine] counts days from. */
    val asOf: Instant,
) {
  /**
   * The folded name, computed once when the person is built. Outside the constructor, so it is not
   * part of `equals`, `copy` or any call site.
   */
  val nameKey: String = PersonSearch.normalize(name)

  /** The phone's digits, taken once when the person is built: what the phone search compares. */
  val phoneDigits: String = phone.filter { it.isDigit() }

  /** Ready-made row strings, built where the person is built and read by the row unchanged. */
  val initials: String = PersonSearch.initials(name)

  val balance: BalanceWordText = balanceWord(net.value)

  val detailLine: String = PersonSearch.detailLine(this, asOf)
}

/**
 * Who «تسجيل لمن؟» offers for what the user typed. The writer types the name the way they say it,
 * so the comparison folds the letters Arabic writers swap freely (أ/إ/آ/ا, ة/ه, ى/ي) and drops the
 * diacritics; digits are compared on the phone number, so «٠٥٩٩» finds `0599…`. Nothing here
 * touches the ledger: it is a pure function of the people list and the query, which is what makes
 * the 1,000-person case measurable in a JVM test.
 */
internal object PersonSearch {
  /** What the picker lists with no query: the people the store dealt with most recently. */
  const val RECENT_LIMIT = 20

  /** A query shorter than this in digits is not a phone search, or every phone matches. */
  private const val DIGITS_TO_MATCH = 2

  /**
   * The one fold every search uses: spaces trimmed, Arabic-Indic and Persian digits made Latin, أ إ
   * آ made ا, ة made ه, ى made ي, and the short vowels (064B–0652) dropped. So «احمد» finds «أحمد»
   * and «محمد» finds «مُحمَّد».
   */
  fun normalize(value: String): String {
    val ascii =
        buildString(value.length) {
              for (character in value.trim()) {
                when {
                  character in '\u0660'..'\u0669' -> append('0' + (character - '\u0660'))
                  character in '\u06F0'..'\u06F9' -> append('0' + (character - '\u06F0'))
                  else -> append(character)
                }
              }
            }
            .lowercase(Locale.ROOT)
    return buildString(ascii.length) {
      for (character in ascii) {
        when (character) {
          'أ',
          'إ',
          'آ' -> append('ا')
          'ة' -> append('ه')
          'ى' -> append('ي')
          in '\u064B'..'\u0652' -> Unit // the short vowels and the marks a name never needs
          else -> append(character)
        }
      }
    }
  }

  /**
   * The people to show, most recently dealt with first, at most [limit]. An empty query is everyone
   * ([limit] of them); a query matches the name as a substring or the phone in its digits.
   */
  fun search(
      people: List<QuickAddPerson>,
      query: String,
      limit: Int = RECENT_LIMIT,
  ): List<QuickAddPerson> {
    val folded = normalize(query)
    val digits = folded.filter { it.isDigit() }
    val matched =
        if (folded.isEmpty()) {
          people
        } else {
          people.filter { person ->
            person.nameKey.contains(folded) ||
                (digits.length >= DIGITS_TO_MATCH && person.phoneDigits.contains(digits))
          }
        }
    return matched
        .sortedWith(
            compareBy<QuickAddPerson> { it.lastActivity == null }
                .thenByDescending { it.lastActivity ?: Instant.EPOCH }
                .thenBy { it.nameKey }
                .thenBy { it.id }
        )
        .take(limit)
  }

  /** The picker's tile: the first letters of the first two words, two letters for one word. */
  fun initials(name: String): String {
    val words = name.trim().split(' ').filter { it.isNotEmpty() }
    return when {
      words.size > 1 -> "${words[0].first()}${words[1].first()}"
      words.size == 1 -> words[0].take(2)
      else -> "؟"
    }
  }

  /** «…342 · اليوم»: the phone's last three digits and when the store last dealt with them. */
  fun detailLine(person: QuickAddPerson, now: Instant): String {
    val tail = person.phone.filter { it.isDigit() }.takeLast(3)
    val activity = lastActivityText(person.lastActivity, now)
    return when {
      tail.isEmpty() -> activity
      activity.isEmpty() -> "…$tail"
      else -> "…$tail · $activity"
    }
  }

  /** Day-granularity, in the app's own words: the picker only needs "recently" or "a while ago". */
  fun lastActivityText(activity: Instant?, now: Instant): String {
    if (activity == null) return ""
    val days = Duration.between(activity, now).toDays()
    return when {
      days <= 0 -> "اليوم"
      days == 1L -> "أمس"
      days < 7 -> "قبل $days أيام"
      days < 14 -> "قبل أسبوع"
      days < 30 -> "قبل ${days / 7} أسابيع"
      days < 60 -> "قبل شهر"
      else -> "قبل ${days / 30} أشهر"
    }
  }
}
