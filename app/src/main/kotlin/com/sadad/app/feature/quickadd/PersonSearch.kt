package com.sadad.app.feature.quickadd

import com.sadad.app.feature.contacts.searchText
import com.sadad.ledger.Cents
import com.sadad.ledger.ContactKind
import java.time.Instant

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
)

/**
 * Who «تسجيل لمن؟» offers for what the user typed. The writer types the name the way they say it,
 * so the comparison folds the letters Arabic writers swap freely (أ/إ/آ/ا, ة/ه, ى/ي) and drops the
 * diacritics; digits are compared on the phone number, so «٠٥٩٩» finds `+970599…`. Nothing here
 * touches the ledger: it is a pure function of the people list and the query, which is what makes
 * the 1,000-person case measurable in a JVM test.
 */
internal object PersonSearch {
  /** What the picker lists with no query: the people the store dealt with most recently. */
  const val RECENT_LIMIT = 20

  /** A query shorter than this in digits is not a phone search, or every phone matches. */
  private const val DIGITS_TO_MATCH = 2

  fun normalize(value: String): String {
    val ascii = searchText(value)
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
            normalize(person.name).contains(folded) ||
                (digits.length >= DIGITS_TO_MATCH &&
                    person.phone.filter { it.isDigit() }.contains(digits))
          }
        }
    return matched
        .sortedWith(
            compareBy<QuickAddPerson> { it.lastActivity == null }
                .thenByDescending { it.lastActivity ?: Instant.EPOCH }
                .thenBy { normalize(it.name) }
                .thenBy { it.id }
        )
        .take(limit)
  }
}
