package com.sadad.app.feature.home

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sadad.app.data.db.Events
import com.sadad.app.data.db.LedgerDatabase
import com.sadad.app.data.db.LedgerStore
import com.sadad.app.data.ledger.summaryOf
import com.sadad.app.feature.contacts.contactId
import com.sadad.ledger.Cents
import com.sadad.ledger.Commands
import com.sadad.ledger.Direction
import com.sadad.ledger.LedgerCommand
import com.sadad.ledger.LedgerState
import com.sadad.ledger.PaymentMethod
import com.sadad.ledger.view
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Ticket 162: each Room query Home reads returns what homeUiState(view) computes from the same
// data.
@RunWith(RobolectricTestRunner::class)
class HomeSummaryQueriesTest {
  private lateinit var db: LedgerDatabase
  private val zone: ZoneId = ZoneId.of("UTC")
  private val month: YearMonth = YearMonth.of(2026, 10)
  private val today: LocalDate = LocalDate.of(2026, 10, 16)

  @Before
  fun openDatabase() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    db = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()
  }

  @After fun closeDatabase() = db.close()

  @Test
  fun eachQueryEqualsHomeUiStateOnASeededStore() = runTest {
    val a = contactId(1)
    val b = contactId(2)
    val archived = contactId(3)
    val supplier = contactId(4)
    val dA = contactId(11)
    val dAPayable = contactId(12)
    val dB = contactId(13)
    val dArchived = contactId(14)
    val dSupplier = contactId(15)
    val dASeptember = contactId(16)
    val paymentA = contactId(21)
    val paymentReversed = contactId(22)
    val paymentArchived = contactId(23)
    val paymentSupplier = contactId(24)
    val paymentSeptember = contactId(25)

    // a: receivable + payable (both directions). b: receivable due today.
    // archived: receivable + payment, must be excluded everywhere.
    // supplier: payable only, in owes and never in the month.
    // reversed payment on dA and a September debt/payment must not count for the month.
    val commands =
        listOf(
            Commands.contactCreate(
                Events.origin,
                at(2026, 9, 1),
                name = "أ",
                entityId = a,
                operationId = uuid(101),
            ),
            Commands.contactCreate(
                Events.origin,
                at(2026, 9, 1),
                name = "ب",
                entityId = b,
                operationId = uuid(102),
            ),
            Commands.contactCreate(
                Events.origin,
                at(2026, 9, 1),
                name = "ج",
                entityId = archived,
                operationId = uuid(103),
            ),
            Commands.contactCreate(
                Events.origin,
                at(2026, 9, 1),
                name = "مخبز",
                kind = com.sadad.ledger.ContactKind.SUPPLIER,
                entityId = supplier,
                operationId = uuid(104),
            ),
            debt(a, dA, 10_000, at(2026, 10, 2), due = "2026-10-11", seq = 105),
            debt(a, dAPayable, 3_000, at(2026, 10, 2), direction = Direction.PAYABLE, seq = 106),
            debt(b, dB, 4_000, at(2026, 10, 2), due = "2026-10-16", seq = 107),
            debt(archived, dArchived, 5_000, at(2026, 10, 2), due = "2026-10-10", seq = 108),
            debt(
                supplier,
                dSupplier,
                7_000,
                at(2026, 10, 3),
                direction = Direction.PAYABLE,
                seq = 109,
            ),
            debt(a, dASeptember, 20_000, at(2026, 9, 15), seq = 110),
            payment(dA, paymentA, 4_000, at(2026, 10, 5), seq = 111),
            payment(dA, paymentReversed, 2_000, at(2026, 10, 6), seq = 112),
            Commands.paymentReverse(
                Events.origin,
                at(2026, 10, 7),
                paymentId = paymentReversed,
                reason = "خطأ",
                entityId = contactId(31),
                operationId = uuid(113),
            ),
            payment(dArchived, paymentArchived, 1_000, at(2026, 10, 5), seq = 114),
            payment(dSupplier, paymentSupplier, 2_000, at(2026, 10, 5), seq = 115),
            payment(dASeptember, paymentSeptember, 9_000, at(2026, 9, 20), seq = 116),
            Commands.contactArchive(
                Events.origin,
                at(2026, 10, 8),
                archived,
                expectedVersion = 1,
                reason = "ترحيل",
                entityId = archived,
                operationId = uuid(117),
            ),
        )
    val page =
        Events.page(commands.mapIndexed { index, command -> Events.accepted(command, index + 1L) })
    // Keep operation ids unique and stable: Commands defaults are random, but payloads are fixed
    // above. Persist exactly like LedgerStoreTest.
    val store = LedgerStore(db)
    val state: LedgerState = LedgerState.EMPTY.apply(page)
    store.persist(LedgerState.EMPTY, state, page)
    val view = state.view(emptyList(), emptySet(), Events.INSTALLATION_ID, "")
    val summaries = view.contacts.mapNotNull { summaryOf(view, it.id) }
    assertTrue(
        "the archived person has no summary row",
        summaries.none { it.contactId == archived },
    )
    store.fillSummaries(summaries)

    val expected = homeUiState(view, month, zone, today = today)

    val start = month.atDay(1).atStartOfDay(zone).toInstant()
    val end = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant()
    val from = start.toEpochMilli()
    val to = end.toEpochMilli()

    val summariesDao = db.contactSummaryDao()
    assertEquals(expected.owedToStore, Cents(summariesDao.owedTotal().first()))
    assertEquals(expected.storeOwes, Cents(summariesDao.owesTotal().first()))
    assertEquals(expected.empty, summariesDao.observeCount().first() == 0)

    val candidates = summariesDao.followUpCandidates(today.toString()).first()
    assertEquals(
        "every follow-up candidate, not only the five rows",
        expected.followUpCount,
        candidates.size,
    )
    for (candidate in candidates) {
      val person = view.contacts.first { it.id == candidate.contactId }
      assertEquals(person.name, candidate.name)
      assertEquals(view.net(candidate.contactId).value, candidate.net)
      assertTrue(candidate.net > 0)
      assertTrue(candidate.earliestOpenDue!!.compareTo(today.toString()) <= 0)
    }
    // DAO order (earliest due, then net, then name) is homeUiState's order, so the drawn rows
    // in that order are the first five candidates.
    assertEquals(expected.followUps.map { it.contactId }, candidates.take(5).map { it.contactId })
    assertEquals(expected.followUps.map { it.amount }, candidates.take(5).map { Cents(it.net) })

    assertEquals(expected.monthDebts, Cents(db.debtDao().monthReceivableDebts(from, to).first()))
    assertEquals(
        expected.monthPayments,
        Cents(db.paymentDao().monthReceivablePayments(from, to).first()),
    )
    assertEquals(
        expected.monthPaymentsCount,
        db.paymentDao().monthReceivablePaymentsCount(from, to).first(),
    )
  }

  private fun debt(
      contactId: String,
      id: String,
      amount: Long,
      createdAt: Instant,
      direction: Direction = Direction.RECEIVABLE,
      due: String? = null,
      seq: Int,
  ): LedgerCommand =
      Commands.debtCreate(
          Events.origin,
          createdAt,
          contactId = contactId,
          direction = direction,
          amount = Cents(amount),
          dueDate = due,
          entityId = id,
          operationId = uuid(seq),
      )

  private fun payment(
      debtId: String,
      id: String,
      amount: Long,
      createdAt: Instant,
      seq: Int,
  ): LedgerCommand =
      Commands.paymentCreate(
          Events.origin,
          createdAt,
          debtId = debtId,
          amount = Cents(amount),
          method = PaymentMethod.CASH,
          entityId = id,
          operationId = uuid(seq),
      )

  private fun at(year: Int, month: Int, day: Int): Instant =
      LocalDate.of(year, month, day).atTime(12, 0).atZone(zone).toInstant()

  private fun uuid(index: Int): String =
      UUID.nameUUIDFromBytes("op-$index".toByteArray()).toString()
}
