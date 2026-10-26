package com.sadad.app.feature.home

import com.sadad.ledger.Cents
import com.sadad.ledger.ContactKind
import com.sadad.ledger.ContactView
import com.sadad.ledger.DebtView
import com.sadad.ledger.Direction
import com.sadad.ledger.LedgerView
import com.sadad.ledger.PaymentMethod
import com.sadad.ledger.PaymentView
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What Home says about the store. The two hero figures must not be the same number computed twice:
 * one is what people owe (positive `net` only), the other is what the store owes (negative `net`,
 * absolute), and a supplier's balance belongs to the second. Then the month, the follow-up list and
 * the two states that replace the page: an empty store and an offline queue.
 */
class HomeViewModelTest {
  private val zone: ZoneId = ZoneId.of("Asia/Jerusalem")
  private val october: YearMonth = YearMonth.of(2026, 10)
  private val today: LocalDate = LocalDate.of(2026, 10, 25)

  @Test
  fun theHeroSplitsPositiveAndNegativeBalancesIncludingSuppliers() {
    val view =
        ledger(
            contacts =
                listOf(contact("c1", "سامي"), contact("s1", "مخبز الأمل", ContactKind.SUPPLIER)),
            debts =
                listOf(
                    debt("d1", "c1", 450_000, at(2026, 10, 2)),
                    debt("d2", "c1", 798_050, at(2026, 10, 3)),
                    debt("p1", "s1", 124_000, at(2026, 10, 4), direction = Direction.PAYABLE),
                ),
        )

    val state = homeUiState(view, october, zone, today = today)

    assertEquals(
        "«لك عند الناس» sums the positive nets only",
        Cents(12_480_50),
        state.owedToStore,
    )
    assertEquals(
        "a supplier the store owes lands in «عليك للناس»",
        Cents(124_000),
        state.storeOwes,
    )
  }

  @Test
  fun aContactInCreditPullsTheirOwnRowOutOfTheOwedTotal() {
    val view =
        ledger(
            contacts = listOf(contact("c1", "سامي"), contact("c2", "ليلى")),
            debts =
                listOf(
                    debt("d1", "c1", 5_000, at(2026, 10, 2)),
                    debt("d2", "c2", 2_000, at(2026, 10, 2), direction = Direction.PAYABLE),
                ),
        )

    val state = homeUiState(view, october, zone, today = today)

    assertEquals("only سامي is owed", Cents(5_000), state.owedToStore)
    assertEquals("ليلى holds the store's money", Cents(2_000), state.storeOwes)
  }

  @Test
  fun theMonthCountsCustomersOnlyAndExcludesSupplierEntries() {
    val view =
        ledger(
            contacts =
                listOf(contact("c1", "سامي"), contact("s1", "مخبز الأمل", ContactKind.SUPPLIER)),
            debts =
                listOf(
                    debt("d1", "c1", 3_200, at(2026, 10, 2)),
                    debt("p1", "s1", 900_000, at(2026, 10, 5), direction = Direction.PAYABLE),
                ),
            payments =
                listOf(
                    payment("pay1", "d1", "c1", 1_950, at(2026, 10, 6)),
                    payment("pay2", "p1", "s1", 500_000, at(2026, 10, 7)),
                ),
        )

    val state = homeUiState(view, october, zone, today = today)

    assertEquals(Cents(3_200), state.monthDebts)
    assertEquals(Cents(1_950), state.monthPayments)
    assertEquals("the supplier payment is not one of the month's", 1, state.monthPaymentsCount)
    assertEquals(61, state.collectionPercent)
  }

  @Test
  fun theCollectionShareCapsAtOneHundredPercent() {
    val view =
        ledger(
            debts = listOf(debt("d1", "c1", 1_000, at(2026, 10, 2))),
            payments = listOf(payment("p1", "d1", "c1", 2_000, at(2026, 10, 3))),
        )

    val state = homeUiState(view, october, zone, today = today)

    assertEquals(100, state.collectionPercent)
  }

  @Test
  fun aMonthWithoutDebtsHasNoShareRatherThanAFullBar() {
    val view = ledger(debts = emptyList(), payments = emptyList())

    val state = homeUiState(view, october, zone, today = today)

    assertEquals(0, state.collectionPercent)
  }

  @Test
  fun followUpListsOverdueBeforeDueTodayAndStopsAtFiveRows() {
    val view =
        ledger(
            contacts =
                listOf(
                    contact("c1", "مريم حسن"),
                    contact("c2", "أحمد سالم"),
                    contact("c3", "خالد يوسف"),
                    contact("c4", "سارة محمود"),
                    contact("c5", "أبو رامي"),
                    contact("c6", "ليلى"),
                    contact("c7", "ناصر"),
                ),
            debts =
                listOf(
                    debt("d1", "c1", 112_000, at(2026, 10, 1), due = "2026-10-22"),
                    debt("d2", "c2", 25_000, at(2026, 10, 1), due = "2026-10-25"),
                    debt("d3", "c3", 8_500, at(2026, 10, 1), due = "2026-10-25"),
                    debt("d4", "c4", 5_000, at(2026, 10, 1), due = "2026-10-20"),
                    debt("d5", "c5", 4_000, at(2026, 10, 1), due = "2026-10-24"),
                    debt("d6", "c6", 3_000, at(2026, 10, 1), due = "2026-10-25"),
                    debt("d7", "c7", 2_000, at(2026, 10, 1), due = "2026-11-10"),
                ),
        )

    val state = homeUiState(view, october, zone, today = today)

    assertEquals("the count is every person who needs chasing", 6, state.followUpCount)
    assertEquals("five rows at most", 5, state.followUps.size)
    assertEquals(
        "most days late first",
        listOf("سارة محمود", "مريم حسن", "أبو رامي"),
        state.followUps.take(3).map { it.name },
    )
    assertEquals(
        "five days, three days, one day — then the two due today",
        listOf(5, 3, 1),
        state.followUps.take(3).map { it.lateDays },
    )
    assertTrue(
        "a debt due next month is not chased",
        state.followUps.none { it.name == "ناصر" },
    )
    assertEquals("the row shows what they owe", Cents(112_000), state.followUps[1].amount)
    assertEquals("due today has no late day", null, state.followUps[3].lateDays)
  }

  @Test
  fun followUpSkipsPeopleWhoDoNotOweAnything() {
    val view =
        ledger(
            contacts = listOf(contact("c1", "سامي")),
            debts =
                listOf(debt("d1", "c1", 5_000, at(2026, 10, 1), due = "2026-10-01", remaining = 0)),
            payments = listOf(payment("p1", "d1", "c1", 5_000, at(2026, 10, 2))),
        )

    val state = homeUiState(view, october, zone, today = today)

    assertTrue("a settled contact is not chased", state.followUps.isEmpty())
    assertEquals(0, state.followUpCount)
  }

  @Test
  fun aStoreWithNoContactsAndNoEntriesIsEmpty() {
    val state = homeUiState(ledger(contacts = emptyList()), october, zone, today = today)

    assertTrue(state.empty)

    val withOneContact =
        homeUiState(ledger(contacts = listOf(contact("c1", "سامي"))), october, zone, today = today)
    assertTrue("a contact alone ends the empty state", !withOneContact.empty)
  }

  @Test
  fun offlineWithThreeQueuedEntriesReportsTheBannerAndTheLeaseEnd() {
    val leaseEnd = at(2026, 10, 25).plusSeconds(4 * 3600)
    val state =
        homeUiState(
            ledger(),
            october,
            zone,
            today = today,
            queued = 3,
            offline = true,
            leaseEndsAt = leaseEnd,
        )

    assertEquals(3, state.offline?.queued)
    assertEquals(leaseEnd, state.offline?.leaseEndsAt)
  }

  @Test
  fun nothingWaitingAndOnlineMeansNoBanner() {
    val state = homeUiState(ledger(), october, zone, today = today, queued = 0, offline = true)
    assertNull("offline with nothing queued is not the banner", state.offline)

    val online = homeUiState(ledger(), october, zone, today = today, queued = 4, offline = false)
    assertNull(online.offline)
  }

  /* ------------------------------------------------------------------ builders */

  private fun at(year: Int, month: Int, day: Int): Instant =
      LocalDate.of(year, month, day).atTime(12, 0).atZone(zone).toInstant()

  private fun contact(
      id: String,
      name: String,
      kind: ContactKind = ContactKind.CUSTOMER,
  ): ContactView =
      ContactView(
          id = id,
          name = name,
          phone = "+970****0000",
          category = "",
          note = "",
          version = 1,
          pending = false,
          createdAt = at(2026, 9, 1),
          createdBy = "",
          operationId = "op-$id",
          kind = kind,
      )

  private fun debt(
      id: String,
      contactId: String,
      amount: Long,
      createdAt: Instant,
      direction: Direction = Direction.RECEIVABLE,
      pending: Boolean = false,
      due: String? = null,
      remaining: Long? = null,
  ): DebtView =
      DebtView(
          id = id,
          contactId = contactId,
          direction = direction,
          amount = Cents(amount),
          paid = Cents.ZERO,
          confirmedRemaining = Cents(remaining ?: amount),
          remaining = Cents(remaining ?: amount),
          version = 1,
          pending = pending,
          hasPendingPayments = false,
          note = "",
          dueDate = due,
          createdAt = createdAt,
          createdBy = "",
          operationId = "op-$id",
      )

  private fun payment(
      id: String,
      debtId: String,
      contactId: String,
      amount: Long,
      createdAt: Instant,
      reversed: Boolean = false,
  ): PaymentView =
      PaymentView(
          id = id,
          debtId = debtId,
          contactId = contactId,
          amount = Cents(amount),
          method = PaymentMethod.CASH,
          note = "",
          reversed = reversed,
          pending = false,
          createdAt = createdAt,
          createdBy = "",
          operationId = "op-$id",
      )

  private fun ledger(
      contacts: List<ContactView> = listOf(contact("c1", "سامي")),
      debts: List<DebtView> = emptyList(),
      payments: List<PaymentView> = emptyList(),
  ): LedgerView =
      LedgerView(
          contacts = contacts,
          archivedContacts = emptyList(),
          debts = debts,
          payments = payments,
          receivable = Cents.ZERO,
          payable = Cents.ZERO,
          confirmedReceivable = Cents.ZERO,
          confirmedPayable = Cents.ZERO,
          pendingContacts = 0,
          pendingDebts = 0,
          pendingPayments = 0,
          excludedOperations = emptySet(),
      )
}
