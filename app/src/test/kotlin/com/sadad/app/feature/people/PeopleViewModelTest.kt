package com.sadad.app.feature.people

import androidx.lifecycle.SavedStateHandle
import com.sadad.app.data.db.ContactSummaryEntity
import com.sadad.app.data.ledger.summaryOf
import com.sadad.ledger.Cents
import com.sadad.ledger.ContactKind
import com.sadad.ledger.ContactView
import com.sadad.ledger.DebtView
import com.sadad.ledger.Direction
import com.sadad.ledger.LedgerView
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the الأشخاص tab shows, on the board's seven people
 * (`docs/ux-redesign/boards/People.dc.html`): every chip's rule and count, the three sorts, the
 * search that folds a hamza and Arabic-Indic digits, the total line under a chip, and a thousand
 * people inside the time budget. The seven are the board's own names, phones, balances and supplier
 * flags; when each last moved is the one thing this file chooses, because a date is what the
 * relative text is made of.
 */
class PeopleViewModelTest {
  private val zone: ZoneId = ZoneId.of("Asia/Jerusalem")
  private val today: LocalDate = LocalDate.of(2026, 10, 25)

  @Test
  fun theRoutesFilterPreselectsTheChip() {
    // The card on Home navigates People("late"); the destination's SavedStateHandle carries it.
    assertEquals(
        PeopleFilter.LATE,
        initialPeopleFilter(SavedStateHandle(mapOf("filter" to "late"))),
    )
  }

  @Test
  fun everyChipCountsItsOwnPeople() {
    val state = state()

    assertEquals("the header's total is everyone, not the chip's rows", 7, state.registered)
    assertEquals(
        mapOf(
            PeopleFilter.ALL to 7,
            PeopleFilter.OWES to 3,
            PeopleFilter.CREDIT to 2,
            PeopleFilter.LATE to 1,
            PeopleFilter.SUPPLIERS to 2,
        ),
        state.counts,
    )
  }

  @Test
  fun eachChipKeepsOnlyThePeopleItsRuleDescribes() {
    assertEquals(
        listOf("خالد يوسف", "أحمد سالم", "مريم حسن"),
        state(filter = PeopleFilter.OWES).rows.map { it.name },
    )
    assertEquals(
        listOf("أحمد ناصر", "مخبز الأمل"),
        state(filter = PeopleFilter.CREDIT).rows.map { it.name },
    )
    assertEquals(listOf("مريم حسن"), state(filter = PeopleFilter.LATE).rows.map { it.name })
    assertEquals(
        listOf("مخبز الأمل", "أبو رامي للخضار"),
        state(filter = PeopleFilter.SUPPLIERS).rows.map { it.name },
    )
  }

  @Test
  fun theRouteFilterPicksTheChipOnEntry() {
    assertEquals(
        PeopleFilter.LATE,
        initialPeopleFilter(SavedStateHandle(mapOf("filter" to "late"))),
    )
    assertEquals(
        PeopleFilter.OWES,
        initialPeopleFilter(SavedStateHandle(mapOf("filter" to "owes"))),
    )
    assertEquals(
        PeopleFilter.CREDIT,
        initialPeopleFilter(SavedStateHandle(mapOf("filter" to "credit"))),
    )
    assertEquals(
        PeopleFilter.SUPPLIERS,
        initialPeopleFilter(SavedStateHandle(mapOf("filter" to "suppliers"))),
    )
    assertEquals(
        "no filter on the route is الكل",
        PeopleFilter.ALL,
        initialPeopleFilter(SavedStateHandle()),
    )
    assertEquals(
        "an unknown filter is الكل, not a crash",
        PeopleFilter.ALL,
        initialPeopleFilter(SavedStateHandle(mapOf("filter" to "whatever"))),
    )
  }

  @Test
  fun recentSortPutsTheNewestActivityFirst() {
    assertEquals(
        listOf(
            "خالد يوسف",
            "أحمد سالم",
            "أحمد ناصر",
            "مخبز الأمل",
            "مريم حسن",
            "سارة محمود",
            "أبو رامي للخضار",
        ),
        state(sort = PeopleSort.RECENT).rows.map { it.name },
    )
  }

  @Test
  fun balanceSortPutsTheLargestNetFirstEitherWayAndTiesOnName() {
    assertEquals(
        listOf(
            "مخبز الأمل",
            "مريم حسن",
            "أحمد سالم",
            "أحمد ناصر",
            "خالد يوسف",
            "أبو رامي للخضار",
            "سارة محمود",
        ),
        state(sort = PeopleSort.BALANCE).rows.map { it.name },
    )
  }

  @Test
  fun nameSortIsArabicOrderNotCodePointOrder() {
    assertEquals(
        listOf(
            "أبو رامي للخضار",
            "أحمد سالم",
            "أحمد ناصر",
            "خالد يوسف",
            "سارة محمود",
            "مخبز الأمل",
            "مريم حسن",
        ),
        state(sort = PeopleSort.NAME).rows.map { it.name },
    )
  }

  @Test
  fun searchFindsTheHamzaSpellingTypedWithoutIt() {
    val state = state(query = "احمد")

    assertEquals(listOf("أحمد سالم", "أحمد ناصر"), state.rows.map { it.name })
  }

  @Test
  fun theSummaryRowsMatchTheWholeViewRowsForEverySortAndChip() {
    val view = board()
    val summaries = summariesOf(view)

    for (sort in PeopleSort.entries) {
      for (filter in PeopleFilter.entries) {
        val fromView = peopleUiState(view, zone, today, "", filter, sort)
        val fromSummary = peopleUiState(summaries, emptyList(), zone, today, "", filter, sort)
        assertEquals("sort $sort chip $filter", fromView.rows, fromSummary.rows)
      }
    }
  }

  @Test
  fun theSummaryPathSearchFoldsHamzaAndDiacritics() {
    val summaries = summariesOf(board())

    val hamza =
        peopleUiState(
            summaries,
            emptyList(),
            zone,
            today,
            "احمد",
            PeopleFilter.ALL,
            PeopleSort.RECENT,
        )
    assertEquals(listOf("أحمد سالم", "أحمد ناصر"), hamza.rows.map { it.name })
  }

  @Test
  fun aDiacriticNameIsFoundWithoutItsDiacritics() {
    val diacritic =
        ledger(listOf(person("p8", "مُحمَّد", "0599000000", 1_000, at(2026, 10, 25, 9, 0))))

    val state =
        peopleUiState(
            summariesOf(diacritic),
            emptyList(),
            zone,
            today,
            "محمد",
            PeopleFilter.ALL,
            PeopleSort.RECENT,
        )

    assertEquals(listOf("مُحمَّد"), state.rows.map { it.name })
  }

  @Test
  fun aFailedDraftStaysListedWithSyncFailedAndNoNet() {
    val view = board()
    val sarah = view.contacts.first { it.id == "p6" }
    val summaries = summariesOf(view).filterNot { it.contactId == "p6" }

    val fromSummary =
        peopleUiState(
            summaries,
            listOf(sarah),
            zone,
            today,
            "",
            PeopleFilter.ALL,
            PeopleSort.RECENT,
        )
    val fromViewRows =
        peopleUiState(board(), zone, today, "", PeopleFilter.ALL, PeopleSort.RECENT).rows.map {
          if (it.id == "p6") it.copy(syncFailed = true) else it
        }

    assertEquals(fromViewRows, fromSummary.rows)
    val row = fromSummary.rows.first { it.id == "p6" }
    assertTrue(row.syncFailed)
    assertEquals(Cents.ZERO, row.net)
    assertNull(row.lateDays)
  }

  @Test
  fun searchMatchesThePhoneDigits() {
    assertEquals(listOf("خالد يوسف"), state(query = "9123").rows.map { it.name })
    assertEquals(
        "Arabic-Indic digits the phone's keyboard types",
        listOf("خالد يوسف"),
        state(query = "٩١٢٣").rows.map { it.name },
    )
    assertEquals(
        "a name is never read as a phone number",
        emptyList<String>(),
        state(query = "9123ح").rows.map { it.name },
    )
  }

  @Test
  fun theChipsCountUnderTheCurrentSearch() {
    val state = state(query = "احمد")

    assertEquals(
        "only the two people whose name carries «احمد» remain",
        2,
        state.rows.size,
    )
    assertEquals(
        mapOf(
            PeopleFilter.ALL to 2,
            PeopleFilter.OWES to 1,
            PeopleFilter.CREDIT to 1,
            PeopleFilter.LATE to 0,
            PeopleFilter.SUPPLIERS to 0,
        ),
        state.counts,
    )
  }

  @Test
  fun theTotalLineSumsTheChipAndIsAbsentOnEveryone() {
    assertNull("الكل has no total line", state().totalLine)
    assertEquals("المجموع عليهم ₪ 1,455", state(filter = PeopleFilter.OWES).totalLine)
    assertEquals("المجموع لهم ₪ 1,390", state(filter = PeopleFilter.CREDIT).totalLine)
    assertEquals("المجموع المتأخر ₪ 1,120", state(filter = PeopleFilter.LATE).totalLine)
  }

  @Test
  fun theLateRowSaysHowLateInsteadOfWhenItLastMoved() {
    val row = state(filter = PeopleFilter.LATE).rows.single()

    assertEquals(3, row.lateDays)
    assertEquals("متأخر 3 أيام", row.activity)
  }

  @Test
  fun theActivityLineReadsAsTheBoardWritesIt() {
    val rows = state().rows.associateBy { it.name }

    assertEquals("اليوم 10:24", rows.getValue("خالد يوسف").activity)
    assertEquals("أمس", rows.getValue("أحمد سالم").activity)
    assertEquals("قبل 5 أيام", rows.getValue("أحمد ناصر").activity)
    assertEquals("قبل أسبوع", rows.getValue("مخبز الأمل").activity)
    assertEquals("قبل أسبوعين", rows.getValue("سارة محمود").activity)
    assertEquals("الشهر الماضي", rows.getValue("أبو رامي للخضار").activity)
    assertEquals(
        "the tail is the last three digits the row shows after «…»",
        "482",
        rows.getValue("خالد يوسف").phoneTail,
    )
  }

  /* ------------------------------------------------- row strings (ticket 176) */

  private fun row(
      name: String = "خالد يوسف",
      phone: String = "0599123482",
      supplier: Boolean = false,
      net: Long = 8500L,
      category: String = "عميل دائم",
      lateDays: Int? = null,
      activity: String = "اليوم 10:24",
  ): PeopleRow =
      PeopleRow(
          id = "row-1",
          name = name,
          phone = phone,
          supplier = supplier,
          net = Cents(net),
          lastActiveAt = Instant.parse("2026-10-25T07:24:00Z"),
          activity = activity,
          lateDays = lateDays,
          category = category,
      )

  @Test
  fun aRowWithNoPhoneShowsOnlyTheCategory() {
    val found = row(phone = "")
    assertEquals("عميل دائم", found.identityLine)
    assertEquals("خي", found.initials)
    assertEquals("عليه", found.balance.word)
    assertEquals("₪ 85", found.balance.amount)
    assertEquals("عليه 85 شيكل", found.balance.spoken)
  }

  @Test
  fun aRowWithNoCategoryHasNoIdentityLine() {
    val found = row(category = "")
    assertEquals("", found.identityLine)
    assertEquals("خي", found.initials)
    assertEquals("عليه 85 شيكل", found.balance.spoken)
  }

  @Test
  fun aRowWithBlankCategoryHasNoIdentityLine() {
    assertEquals("", row(category = "   ").identityLine)
  }

  @Test
  fun aSupplierRowCarriesItsInitialsAndCredit() {
    val found =
        row(name = "أبو رامي للخضار", phone = "0595118076", supplier = true, net = -124_000L)
    assertEquals("أر", found.initials)
    assertEquals("له", found.balance.word)
    assertEquals("₪ 1,240", found.balance.amount)
    assertEquals("له 1,240 شيكل", found.balance.spoken)
  }

  @Test
  fun aSettledRowHasNoAmount() {
    val found = row(net = 0L)
    assertEquals("مسدد", found.balance.word)
    assertNull(found.balance.amount)
    assertEquals("مسدد", found.balance.spoken)
  }

  @Test
  fun aRowInCreditNamesItsCredit() {
    val found = row(net = -15_000L)
    assertEquals("له", found.balance.word)
    assertEquals("₪ 150", found.balance.amount)
    assertEquals("له 150 شيكل", found.balance.spoken)
  }

  @Test
  fun aLateRowSaysHowLateAndStillOwes() {
    val found = row(net = 112_000L, lateDays = 3, activity = "متأخر 3 أيام")
    assertEquals("متأخر 3 أيام", found.activity)
    assertEquals("عليه", found.balance.word)
    assertEquals("₪ 1,120", found.balance.amount)
    assertEquals("عليه 1,120 شيكل", found.balance.spoken)
  }

  @Test
  fun aSingleWordNameHasOnePeopleInitial() {
    assertEquals("س", row(name = "سامي").initials)
  }

  @Test
  fun theBoardRowsCarryTheirReadyMadeStrings() {
    val rows = state().rows.associateBy { it.name }
    assertEquals("خي", rows.getValue("خالد يوسف").initials)
    assertEquals("عليه 85 شيكل", rows.getValue("خالد يوسف").balance.spoken)
    assertEquals("مسدد", rows.getValue("سارة محمود").balance.word)
    assertNull(rows.getValue("سارة محمود").balance.amount)
    assertEquals("له 150 شيكل", rows.getValue("أحمد ناصر").balance.spoken)
    assertEquals("متأخر 3 أيام", rows.getValue("مريم حسن").activity)
  }

  @Test
  fun aThousandPeopleFilterAndSortInsideTheBudget() {
    val many =
        ledger(
            (0 until 1_000).map { index ->
              person(
                  id = "p$index",
                  name = "شخص $index",
                  phone = "0599%07d".format(index),
                  net = if (index % 3 == 0) 1_000L + index else -(1_000L + index),
                  at = today.minusDays((index % 40).toLong()).atTime(9, 0).atZone(zone).toInstant(),
              )
            }
        )

    repeat(3) { peopleUiState(many, zone, today, "شخص", PeopleFilter.OWES, PeopleSort.BALANCE) }
    val started = System.nanoTime()
    val state = peopleUiState(many, zone, today, "شخص", PeopleFilter.OWES, PeopleSort.BALANCE)
    val millis = (System.nanoTime() - started) / 1_000_000

    assertEquals(334, state.rows.size)
    assertTrue("1,000 people took $millis ms, the budget is 100", millis < 100)
  }

  /* ---------------------------------------------------------------- the board */

  private fun state(
      query: String = "",
      filter: PeopleFilter = PeopleFilter.ALL,
      sort: PeopleSort = PeopleSort.RECENT,
  ): PeopleState = peopleUiState(board(), zone, today, query, filter, sort)

  private fun board(): LedgerView =
      ledger(
          listOf(
              person("p1", "خالد يوسف", "0599123482", 8_500, at(2026, 10, 25, 10, 24)),
              person("p2", "أحمد سالم", "0598440905", 25_000, at(2026, 10, 24, 9, 0)),
              person(
                  "p3",
                  "مريم حسن",
                  "0599871117",
                  112_000,
                  at(2026, 10, 15, 12, 0),
                  due = "2026-10-22",
              ),
              person("p4", "أحمد ناصر", "0597220331", -15_000, at(2026, 10, 20, 12, 0)),
              person(
                  "p5",
                  "مخبز الأمل",
                  "0592300208",
                  -124_000,
                  at(2026, 10, 17, 12, 0),
                  kind = ContactKind.SUPPLIER,
              ),
              person("p6", "سارة محمود", "0599335640", 0, at(2026, 10, 10, 12, 0)),
              person(
                  "p7",
                  "أبو رامي للخضار",
                  "0595118076",
                  0,
                  at(2026, 9, 15, 12, 0),
                  kind = ContactKind.SUPPLIER,
              ),
          )
      )

  /** One person with one settled-but-open entry, so `net` is the board's number. */
  private fun person(
      id: String,
      name: String,
      phone: String,
      net: Long,
      at: Instant,
      kind: ContactKind = ContactKind.CUSTOMER,
      due: String? = null,
  ): Pair<ContactView, List<DebtView>> {
    val contact =
        ContactView(
            id = id,
            name = name,
            phone = phone,
            kind = kind,
            category = "",
            note = "",
            version = 1,
            pending = false,
            createdAt = at,
            createdBy = "",
            operationId = "op-$id",
        )
    if (net == 0L) return contact to emptyList()
    val debt =
        DebtView(
            id = "d-$id",
            contactId = id,
            direction = if (net > 0) Direction.RECEIVABLE else Direction.PAYABLE,
            amount = Cents(if (net > 0) net else -net),
            paid = Cents.ZERO,
            confirmedRemaining = Cents(if (net > 0) net else -net),
            remaining = Cents(if (net > 0) net else -net),
            version = 1,
            pending = false,
            hasPendingPayments = false,
            note = "",
            dueDate = due,
            createdAt = at,
            createdBy = "",
            operationId = "op-d-$id",
        )
    return contact to listOf(debt)
  }

  private fun ledger(people: List<Pair<ContactView, List<DebtView>>>): LedgerView =
      LedgerView(
          contacts = people.map { it.first },
          archivedContacts = emptyList(),
          debts = people.flatMap { it.second },
          payments = emptyList(),
          receivable = Cents.ZERO,
          payable = Cents.ZERO,
          confirmedReceivable = Cents.ZERO,
          confirmedPayable = Cents.ZERO,
          pendingContacts = 0,
          pendingDebts = 0,
          pendingPayments = 0,
          excludedOperations = emptySet(),
      )

  private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Instant =
      LocalDate.of(year, month, day).atTime(hour, minute).atZone(zone).toInstant()

  /** The stored rows for [view], built the way production builds them (ticket 160). */
  private fun summariesOf(view: LedgerView): List<ContactSummaryEntity> =
      view.contacts.mapNotNull { summaryOf(view, it.id) }
}
