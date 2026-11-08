package com.sadad.app.feature.quickadd

import com.sadad.ledger.Cents
import com.sadad.ledger.ContactKind
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The search behind «تسجيل لمن؟». It has to find a person whatever the writer typed: the same name
 * with ا instead of أ, ه instead of ة, ي instead of ى, Arabic-Indic digits instead of Latin ones,
 * or the last digits of the phone. 1,000 people is the size the ticket names, so it is measured
 * here rather than assumed.
 */
class PersonSearchTest {
  /** The projection's moment every test person is built at, so no test reads the clock. */
  private val now = Instant.parse("2026-10-25T10:00:00Z")

  private fun person(name: String, phone: String = "", net: Long = 0L, last: Instant? = null) =
      QuickAddPerson(
          id = name,
          name = name,
          phone = phone,
          kind = ContactKind.CUSTOMER,
          net = Cents(net),
          lastActivity = last,
          asOf = now,
      )

  /**
   * The keys ([QuickAddPerson.nameKey], [QuickAddPerson.phoneDigits]) live outside the primary
   * constructor, so separately built people with the same fields stay equal.
   */
  @Test
  fun twoPeopleBuiltSeparatelyWithTheSameFieldsAreEqual() {
    val first = person("أحمد سالم", phone = "0599", last = Instant.parse("2026-10-01T09:00:00Z"))
    val second = person("أحمد سالم", phone = "0599", last = Instant.parse("2026-10-01T09:00:00Z"))
    assertEquals(first, second)
  }

  /**
   * A copy of the search as it was on origin/main 72b3abf: folding on every comparison. The new
   * key-based search must give exactly the same matches and order as this reference for the queries
   * below. Pins behavior; green before and after the change.
   */
  private fun referenceSearch(
      people: List<QuickAddPerson>,
      query: String,
      limit: Int = PersonSearch.RECENT_LIMIT,
  ): List<QuickAddPerson> {
    val folded = PersonSearch.normalize(query)
    val digits = folded.filter { it.isDigit() }
    val matched =
        if (folded.isEmpty()) {
          people
        } else {
          people.filter { person ->
            PersonSearch.normalize(person.name).contains(folded) ||
                (digits.length >= 2 && person.phone.filter { it.isDigit() }.contains(digits))
          }
        }
    return matched
        .sortedWith(
            compareBy<QuickAddPerson> { it.lastActivity == null }
                .thenByDescending { it.lastActivity ?: Instant.EPOCH }
                .thenBy { PersonSearch.normalize(it.name) }
                .thenBy { it.id }
        )
        .take(limit)
  }

  @Test
  fun theKeysGiveTheSameMatchesAndOrderAsFoldingOnEveryCall() {
    val base = Instant.parse("2026-10-01T09:00:00Z")
    // Every fold the search does, as separate spellings of the same names, plus diacritics.
    val spellings =
        listOf(
            "أحمد",
            "احمد",
            "إبراهيم",
            "ابراهيم",
            "فاطمة",
            "فاطمه",
            "مصطفى",
            "مصطفي",
            "مُحمَّد",
            "محمد",
        )
    val people =
        (0 until 5_000).map { index ->
          person(
              "${spellings[index % spellings.size]} $index",
              phone = "05991234${(index % 100).toString().padStart(2, '1')}",
              last = if (index % 3 == 0) null else base.plusSeconds((index % 97) * 3_600L),
          )
        }
    val queries = listOf("", "احمد", "أحمد", "فاطمه", "مصطفي", "محمد", "59", "0599", "ززز")
    queries.forEach { query ->
      assertEquals(referenceSearch(people, query), PersonSearch.search(people, query))
    }
  }

  @Test
  fun aPlainAlifFindsANameWrittenWithHamza() {
    val people = listOf(person("أحمد سالم"))
    assertEquals(listOf("أحمد سالم"), PersonSearch.search(people, "احمد").map { it.name })
  }

  @Test
  fun aTehMarbutaIsWrittenAsHeh() {
    assertEquals(
        listOf("فاطمة"),
        PersonSearch.search(listOf(person("فاطمة")), "فاطمه").map { it.name },
    )
  }

  @Test
  fun anAlifMaqsuraIsWrittenAsYeh() {
    assertEquals(
        listOf("مصطفى"),
        PersonSearch.search(listOf(person("مصطفى")), "مصطفي").map { it.name },
    )
  }

  @Test
  fun arabicIndicDigitsFindTheLastDigitsOfAPhone() {
    val people = listOf(person("خالد يوسف", phone = "0599123482"))
    assertEquals(listOf("خالد يوسف"), PersonSearch.search(people, "٠٥٩٩").map { it.name })
  }

  @Test
  fun twoDigitsAreEnoughToMatchAPhoneAndOneIsNot() {
    val people = listOf(person("خالد يوسف", phone = "0599123482"))
    assertEquals(1, PersonSearch.search(people, "59").size)
    assertEquals(0, PersonSearch.search(people, "5").size)
  }

  @Test
  fun anEmptyQueryIsEveryoneAndAQueryThatMatchesNobodyIsEmpty() {
    val people = listOf(person("أحمد سالم"), person("مريم حسن"))
    assertEquals(2, PersonSearch.search(people, "").size)
    assertEquals(0, PersonSearch.search(people, "ززز").size)
  }

  @Test
  fun theNewestActivityComesFirstAndTheListStopsAtTwenty() {
    val base = Instant.parse("2026-10-01T09:00:00Z")
    val people =
        (1..25).map { day ->
          person("شخص $day", last = base.plusSeconds(day.toLong() * 86_400))
        }
    val recent = PersonSearch.search(people, "")
    assertEquals(20, recent.size)
    assertEquals("شخص 25", recent.first().name)
    assertEquals("شخص 6", recent.last().name)
  }

  @Test
  fun someoneWithNoActivityIsListedAfterEveryoneWhoHasSome() {
    val base = Instant.parse("2026-10-01T09:00:00Z")
    val people =
        listOf(
            person("بلا حركة", last = null),
            person("قديم", last = base),
        )
    assertEquals(listOf("قديم", "بلا حركة"), PersonSearch.search(people, "").map { it.name })
  }

  /* ------------------------------------------------- row strings (ticket 176) */

  @Test
  fun thePickerInitialsTakeTwoLettersForOneWord() {
    assertEquals("خي", person("خالد يوسف").initials)
    assertEquals("سا", person("سامي").initials)
    assertEquals("؟", person("   ").initials)
  }

  @Test
  fun aSettledPickerRowHasNoAmount() {
    val found = person("سارة محمود", net = 0L)
    assertEquals("مسدد", found.balance.word)
    assertEquals(null, found.balance.amount)
    assertEquals("مسدد", found.balance.spoken)
  }

  @Test
  fun aPickerRowInCreditNamesItsCredit() {
    val found = person("أحمد ناصر", net = -15_000L)
    assertEquals("له", found.balance.word)
    assertEquals("₪ 150", found.balance.amount)
    assertEquals("له 150 شيكل", found.balance.spoken)
  }

  @Test
  fun aPickerRowThatOwesNamesItsDebt() {
    val found = person("خالد يوسف", net = 25_000L)
    assertEquals("عليه", found.balance.word)
    assertEquals("₪ 250", found.balance.amount)
    assertEquals("عليه 250 شيكل", found.balance.spoken)
  }

  @Test
  fun aSupplierPickerRowCarriesItsInitialsAndCredit() {
    val found =
        QuickAddPerson(
            id = "s1",
            name = "أبو رامي للخضار",
            phone = "0595118076",
            kind = ContactKind.SUPPLIER,
            net = Cents(-124_000L),
            lastActivity = null,
            asOf = now,
        )
    assertEquals("أر", found.initials)
    assertEquals("له", found.balance.word)
    assertEquals("₪ 1,240", found.balance.amount)
    assertEquals("له 1,240 شيكل", found.balance.spoken)
    assertEquals("…076", found.detailLine)
  }

  @Test
  fun theDetailLineJoinsThePhoneTailAndTheActivity() {
    val found =
        person(
            "خالد يوسف",
            phone = "0599123482",
            last = Instant.parse("2026-10-23T10:00:00Z"),
        )
    assertEquals("…482 · قبل 2 أيام", PersonSearch.detailLine(found, now))
  }

  @Test
  fun theDetailLineWithNoPhoneIsOnlyWhenTheyMoved() {
    val found = person("خالد يوسف", phone = "", last = Instant.parse("2026-10-24T10:00:00Z"))
    assertEquals("أمس", PersonSearch.detailLine(found, now))
  }

  @Test
  fun theDetailLineWithNoActivityIsOnlyThePhoneTail() {
    val found = person("خالد يوسف", phone = "0599123482", last = null)
    assertEquals("…482", PersonSearch.detailLine(found, now))
    assertEquals("…482", found.detailLine)
  }

  @Test
  fun theDetailLineWithNeitherIsEmpty() {
    val found = person("خالد يوسف", phone = "", last = null)
    assertEquals("", PersonSearch.detailLine(found, now))
    assertEquals("", found.detailLine)
  }

  @Test
  fun theBuiltLineJoinsThePhoneTailAndTheActivityAtTheProjectionsMoment() {
    val found = person("خالد يوسف", phone = "0599123482", last = now.minusSeconds(2L * 86_400))
    assertEquals("…482 · قبل 2 أيام", found.detailLine)
  }

  @Test
  fun theBuiltLineWithNoPhoneIsOnlyWhenTheyMovedAtTheProjectionsMoment() {
    val found = person("خالد يوسف", phone = "", last = now.minusSeconds(86_400))
    assertEquals("أمس", found.detailLine)
  }

  @Test
  fun theDetailLineReadsTheSameAtTheSameMoment() {
    val found =
        person("خالد يوسف", phone = "0599123482", last = Instant.parse("2026-10-25T08:00:00Z"))
    assertEquals(PersonSearch.detailLine(found, now), found.detailLine)
  }

  @Test
  fun aThousandPeopleAreSearchedInUnderFiftyMilliseconds() {
    val people =
        (1..1_000).map { index ->
          person("زبون رقم $index", phone = "05${10000000 + index}", net = index.toLong())
        }
    // One warm-up pass: the assertion is about the search, not about JIT.
    PersonSearch.search(people, "زبون رقم 999")
    val started = System.nanoTime()
    repeat(10) { PersonSearch.search(people, "زبون رقم 999") }
    val perSearchMillis = (System.nanoTime() - started) / 10 / 1_000_000.0
    assertTrue(
        "1,000 people took $perSearchMillis ms per search",
        perSearchMillis < 50.0,
    )
  }
}
