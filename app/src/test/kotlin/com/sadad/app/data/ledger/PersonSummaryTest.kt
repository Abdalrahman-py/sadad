package com.sadad.app.data.ledger

import com.sadad.app.data.db.Events
import com.sadad.app.feature.contacts.contact
import com.sadad.app.feature.contacts.contactId
import com.sadad.app.feature.contacts.payment
import com.sadad.app.feature.contacts.seedCommands
import com.sadad.app.feature.home.homeUiState
import com.sadad.app.feature.quickadd.PersonSearch
import com.sadad.app.testing.seedPayment
import com.sadad.ledger.Cents
import com.sadad.ledger.Commands
import com.sadad.ledger.Direction
import com.sadad.ledger.PaymentAllocation
import com.sadad.ledger.PaymentMethod
import com.sadad.ledger.ReceiptStatus
import com.sadad.ledger.view
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Ticket 160: the summary row must equal the facts the whole-store view gives for that person,
// with queued entries included, and none for an archived person.
@RunWith(RobolectricTestRunner::class)
class PersonSummaryTest {
  @Test
  fun summaryEqualsWholeStoreViewFactsForEveryPerson() = runTest {
    val today = LocalDate.of(2026, 10, 16)
    val zone = ZoneId.of("UTC")
    val month = YearMonth.of(2026, 10)
    com.sadad.app.testing.TestGraph.signedIn().use { fixture ->
      val store = checkNotNull(fixture.graph.current.value)
      val a = contactId(1)
      val b = contactId(2)
      val c = contactId(3)
      val dA1 = contactId(11)
      val dA2 = contactId(12)
      val dA3 = contactId(15)
      val dB1 = contactId(13)
      val dC = contactId(14)
      val paymentPartial = contactId(21)
      val paymentReversed = contactId(22)
      val reverse =
          Commands.paymentReverse(
              Events.origin,
              Events.at(8),
              paymentReversed,
              "خطأ",
              entityId = contactId(23),
              operationId = UUID.randomUUID().toString(),
          )
      val offsetA = contactId(31)
      // a: an overdue receivable debt, a payable debt, partial + reversed payments, an offset,
      // and an unparseable due date that must not win earliestOpenDue.
      // b: a receivable due today. c: archived.
      fixture.seedCommands(
          listOf(
              contact("أ", a, minute = 0),
              contact("ب", b, minute = 1),
              contact("ج", c, minute = 2),
              Commands.debtCreate(
                  Events.origin,
                  Events.at(3),
                  contactId = a,
                  direction = Direction.RECEIVABLE,
                  amount = Cents(10_000),
                  dueDate = "2026-10-11",
                  entityId = dA1,
                  operationId = UUID.randomUUID().toString(),
              ),
              Commands.debtCreate(
                  Events.origin,
                  Events.at(4),
                  contactId = b,
                  direction = Direction.RECEIVABLE,
                  amount = Cents(4_000),
                  dueDate = "2026-10-16",
                  entityId = dB1,
                  operationId = UUID.randomUUID().toString(),
              ),
              Commands.debtCreate(
                  Events.origin,
                  Events.at(5),
                  contactId = a,
                  direction = Direction.PAYABLE,
                  amount = Cents(3_000),
                  entityId = dA2,
                  operationId = UUID.randomUUID().toString(),
              ),
              Commands.debtCreate(
                  Events.origin,
                  Events.at(5),
                  contactId = a,
                  direction = Direction.RECEIVABLE,
                  amount = Cents(1_000),
                  dueDate = "not-a-date",
                  entityId = dA3,
                  operationId = UUID.randomUUID().toString(),
              ),
              payment(dA1, 4_000, id = paymentPartial, minute = 6),
              payment(dA1, 2_000, id = paymentReversed, minute = 7),
              reverse,
              Commands.debtCreate(
                  Events.origin,
                  Events.at(9),
                  contactId = c,
                  direction = Direction.RECEIVABLE,
                  amount = Cents(5_000),
                  entityId = dC,
                  operationId = UUID.randomUUID().toString(),
              ),
              Commands.contactArchive(
                  Events.origin,
                  Events.at(10),
                  c,
                  expectedVersion = 1,
                  reason = "ترحيل",
                  entityId = c,
                  operationId = UUID.randomUUID().toString(),
              ),
              com.sadad.app.feature.contacts.offset(dA1, dA2, 1_000, id = offsetA, minute = 11),
          )
      )
      // A queued new person with a queued debt.
      val d =
          store.repository.addContact("د", null, null, null, FirstDebt(Cents(2_000))).getOrThrow()
      // A queued payment on a confirmed debt.
      store.seedPayment(dA1, Cents(500), note = "دفعة مؤجلة")
      // A rejected contact.create, still saved with its debts.
      val e =
          store.repository.addContact("هـ", null, null, null, FirstDebt(Cents(700))).getOrThrow()
      val eRows = store.outbox.snapshot()
      val eCreate = eRows.first {
        it.entityId == e && it.type == com.sadad.ledger.CommandType.CONTACT_CREATE
      }
      store.outbox.settle(
          eRows
              .filter { it.entityId == e || eCreate.operationId in it.command.dependsOn }
              .map { row ->
                receiptOf(
                    row.command,
                    ReceiptStatus.REJECTED,
                    code =
                        if (row.type == com.sadad.ledger.CommandType.CONTACT_CREATE)
                            "contact_limit_reached"
                        else "dependency_rejected",
                )
              }
      )

      // The whole-store projection, built here from Room: the resident flow is gone (ticket 168),
      // and this test's rule is that the stored summary equals that projection's facts per person.
      val rows = store.outbox.snapshot()
      val view =
          store.store
              .load()
              .view(
                  pendingCommands(rows),
                  failedOperations(rows),
                  store.selfInstallationId,
                  store.staffName.first(),
              )

      val home = homeUiState(view, month, zone, today = today)

      // Archived and rejected people have no row.
      assertNull("c archived must have no row", summaryOf(view, c))
      assertNull("e excluded must have no row", summaryOf(view, e))

      for (id in listOf(a, b, d)) {
        val summary = checkNotNull(summaryOf(view, id)) { id }
        val person = view.contacts.first { it.id == id }

        assertEquals("$id contactId", id, summary.contactId)
        assertEquals("$id name", person.name, summary.name)
        assertEquals("$id phone", person.phone, summary.phone)
        assertEquals("$id kind", person.kind, summary.kind)
        assertEquals("$id category", person.category, summary.category)
        assertEquals("$id pending", person.pending, summary.pending)
        assertEquals("$id createdAt", person.createdAt, summary.createdAt)
        assertEquals("$id searchKey", PersonSearch.normalize(person.name), summary.searchKey)

        assertEquals("$id net", view.net(id).value, summary.net)
        assertEquals(
            "$id openReceivable",
            PaymentAllocation.openBalance(view, id, Direction.RECEIVABLE).value,
            summary.openReceivable,
        )
        assertEquals(
            "$id openPayable",
            PaymentAllocation.openBalance(view, id, Direction.PAYABLE).value,
            summary.openPayable,
        )

        val expectedLast =
            (view.debtsOf(id).map { it.createdAt } +
                    view.payments.filter { it.contactId == id }.map { it.createdAt })
                .maxOrNull()
        assertEquals("$id lastEntryAt", expectedLast, summary.lastEntryAt)

        // earliestOpenDue is the smallest parseable due date among open receivable debts.
        val expectedEarliest =
            view
                .debtsOf(id)
                .filter { it.direction == Direction.RECEIVABLE && it.remaining > Cents.ZERO }
                .mapNotNull {
                  it.dueDate?.let { due -> runCatching { LocalDate.parse(due) }.getOrNull() }
                }
                .minOrNull()
                ?.toString()
        assertEquals("$id earliestOpenDue", expectedEarliest, summary.earliestOpenDue)

        // The same late days Home shows: its follow-up is built from the same debts.
        val followUp = home.followUps.firstOrNull { it.contactId == id }
        val earliest = summary.earliestOpenDue
        if (earliest == null) {
          assertNull("$id no earliest so Home must not follow up", followUp)
        } else {
          val due = LocalDate.parse(earliest)
          val days = ChronoUnit.DAYS.between(due, today)
          when {
            days > 0 -> {
              assertNotNull("$id overdue so Home must follow up", followUp)
              assertEquals("$id lateDays", days.toInt(), followUp!!.lateDays)
              assertEquals("$id follow-up amount is net", view.net(id), followUp.amount)
            }
            days == 0L -> {
              assertNotNull("$id due today so Home must follow up", followUp)
              assertNull("$id due today has no late days", followUp!!.lateDays)
            }
            else -> assertNull("$id future due is not a follow-up", followUp)
          }
        }
      }

      // Known literals from the seed: a is 5 days late, b is due today.
      assertEquals("2026-10-11", summaryOf(view, a)!!.earliestOpenDue)
      assertEquals("2026-10-16", summaryOf(view, b)!!.earliestOpenDue)
    }
  }

  // Ticket 161: each outbox command names exactly one person.
  @Test
  fun personOfNamesTheOnePersonEachCommandTouches() = runTest {
    val a = contactId(1)
    val b = contactId(2)
    val debtA = contactId(11)
    val debtB = contactId(12)
    val queuedDebtA = contactId(13)
    val payableA = contactId(14)
    val paymentA = contactId(21)
    val otherEntity = contactId(99)

    // Owners as the collector resolves them: confirmed debts from Room, a queued debt from the
    // queued debt.create in the same outbox snapshot.
    val debtOwner: suspend (String) -> String? = { debtId ->
      mapOf(debtA to a, debtB to b, queuedDebtA to a, payableA to a)[debtId]
    }
    val paymentDebt: suspend (String) -> String? = { paymentId ->
      mapOf(paymentA to debtA)[paymentId]
    }
    fun op() = UUID.randomUUID().toString()

    // contact.create names its entityId; the others name the payload contactId, not entityId.
    assertEquals(
        a,
        personOf(
            Commands.contactCreate(
                Events.origin,
                Events.at(0),
                name = "أ",
                entityId = a,
                operationId = op(),
            ),
            debtOwner,
            paymentDebt,
        ),
    )
    assertEquals(
        a,
        personOf(
            Commands.contactUpdate(
                Events.origin,
                Events.at(1),
                contactId = a,
                expectedVersion = 1,
                name = "أ",
                entityId = otherEntity,
                operationId = op(),
            ),
            debtOwner,
            paymentDebt,
        ),
    )
    assertEquals(
        a,
        personOf(
            Commands.contactArchive(
                Events.origin,
                Events.at(2),
                a,
                expectedVersion = 1,
                reason = "ترحيل",
                entityId = otherEntity,
                operationId = op(),
            ),
            debtOwner,
            paymentDebt,
        ),
    )
    assertEquals(
        a,
        personOf(
            Commands.contactRestore(
                Events.origin,
                Events.at(3),
                a,
                expectedVersion = 2,
                reason = "استرجاع",
                entityId = otherEntity,
                operationId = op(),
            ),
            debtOwner,
            paymentDebt,
        ),
    )
    assertEquals(
        a,
        personOf(
            Commands.contactDelete(
                Events.origin,
                Events.at(4),
                a,
                expectedVersion = 2,
                reason = "حذف",
                entityId = otherEntity,
                operationId = op(),
            ),
            debtOwner,
            paymentDebt,
        ),
    )
    assertEquals(
        b,
        personOf(
            Commands.debtCreate(
                Events.origin,
                Events.at(5),
                contactId = b,
                direction = Direction.RECEIVABLE,
                amount = Cents(1_000),
                entityId = debtB,
                operationId = op(),
            ),
            debtOwner,
            paymentDebt,
        ),
    )
    // debt.correct and payment.create name the owner of debtId.
    assertEquals(
        a,
        personOf(
            Commands.debtCorrect(
                Events.origin,
                Events.at(6),
                debtId = debtA,
                expectedVersion = 1,
                amount = Cents(9_000),
                reason = "تصحيح",
                entityId = otherEntity,
                operationId = op(),
            ),
            debtOwner,
            paymentDebt,
        ),
    )
    assertEquals(
        a,
        personOf(
            Commands.paymentCreate(
                Events.origin,
                Events.at(7),
                debtId = debtA,
                amount = Cents(500),
                method = PaymentMethod.CASH,
                entityId = paymentA,
                operationId = op(),
            ),
            debtOwner,
            paymentDebt,
        ),
    )
    // A payment on a queued debt: the owner comes from the queued debt.create.
    assertEquals(
        a,
        personOf(
            Commands.paymentCreate(
                Events.origin,
                Events.at(8),
                debtId = queuedDebtA,
                amount = Cents(500),
                method = PaymentMethod.CASH,
                entityId = otherEntity,
                operationId = op(),
            ),
            debtOwner,
            paymentDebt,
        ),
    )
    // payment.reverse names the owner through the payment's debt.
    assertEquals(
        a,
        personOf(
            Commands.paymentReverse(
                Events.origin,
                Events.at(9),
                paymentId = paymentA,
                reason = "خطأ",
                entityId = otherEntity,
                operationId = op(),
            ),
            debtOwner,
            paymentDebt,
        ),
    )
    // debt.offset names the owner of receivableDebtId (its payable debt is the same person).
    assertEquals(
        a,
        personOf(
            Commands.debtOffset(
                Events.origin,
                Events.at(10),
                receivableDebtId = debtA,
                payableDebtId = payableA,
                amount = Cents(300),
                entityId = otherEntity,
                operationId = op(),
            ),
            debtOwner,
            paymentDebt,
        ),
    )
    // An unknown debt or payment owner names nobody.
    assertNull(
        personOf(
            Commands.debtCorrect(
                Events.origin,
                Events.at(11),
                debtId = contactId(77),
                expectedVersion = 1,
                amount = Cents(1),
                reason = "تصحيح",
                entityId = otherEntity,
                operationId = op(),
            ),
            debtOwner,
            paymentDebt,
        )
    )
    assertNull(
        personOf(
            Commands.paymentReverse(
                Events.origin,
                Events.at(12),
                paymentId = contactId(78),
                reason = "خطأ",
                entityId = otherEntity,
                operationId = op(),
            ),
            debtOwner,
            paymentDebt,
        )
    )
  }
}
