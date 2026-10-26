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
  private fun person(name: String, phone: String = "", net: Long = 0L, last: Instant? = null) =
      QuickAddPerson(
          id = name,
          name = name,
          phone = phone,
          kind = ContactKind.CUSTOMER,
          net = Cents(net),
          lastActivity = last,
      )

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
    val people = listOf(person("خالد يوسف", phone = "+970599123482"))
    assertEquals(listOf("خالد يوسف"), PersonSearch.search(people, "٠٥٩٩").map { it.name })
  }

  @Test
  fun twoDigitsAreEnoughToMatchAPhoneAndOneIsNot() {
    val people = listOf(person("خالد يوسف", phone = "+970599123482"))
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

  @Test
  fun aThousandPeopleAreSearchedInUnderFiftyMilliseconds() {
    val people =
        (1..1_000).map { index ->
          person("زبون رقم $index", phone = "+9705${10000000 + index}", net = index.toLong())
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
