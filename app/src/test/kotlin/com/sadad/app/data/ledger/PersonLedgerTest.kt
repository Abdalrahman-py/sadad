package com.sadad.app.data.ledger

import com.sadad.app.data.db.Events
import com.sadad.app.feature.contacts.contact
import com.sadad.app.feature.contacts.contactId
import com.sadad.app.feature.contacts.debt
import com.sadad.app.feature.contacts.offset
import com.sadad.app.feature.contacts.payment
import com.sadad.app.feature.contacts.seedCommands
import com.sadad.app.testing.STORE_A
import com.sadad.app.testing.TestGraph
import com.sadad.app.testing.seedPayment
import com.sadad.ledger.Cents
import com.sadad.ledger.CommandType
import com.sadad.ledger.Commands
import com.sadad.ledger.Direction
import com.sadad.ledger.ReceiptStatus
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// The key rule of ticket 159: for every person, one-person records equal the whole-store
// projection restricted to that person.
@RunWith(RobolectricTestRunner::class)
class PersonLedgerTest {
  @Test
  fun theOnePersonRecordsEqualTheWholeStoreProjectionForEveryPerson() = runTest {
    TestGraph.signedIn().use { fixture ->
      val store = checkNotNull(fixture.graph.current.value)
      val a = contactId(1)
      val b = contactId(2)
      val c = contactId(3)
      val dA1 = contactId(11)
      val dA2 = contactId(12)
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
      // Three people with interleaved debts, partial and reversed payments, and an offset;
      // the third person is archived.
      fixture.seedCommands(
          listOf(
              contact("أ", a, minute = 0),
              contact("ب", b, minute = 1),
              contact("ج", c, minute = 2),
              debt(a, 10_000, id = dA1, minute = 3),
              debt(b, 4_000, id = dB1, minute = 4),
              debt(a, 3_000, direction = Direction.PAYABLE, id = dA2, minute = 5),
              payment(dA1, 4_000, id = paymentPartial, minute = 6),
              payment(dA1, 2_000, id = paymentReversed, minute = 7),
              reverse,
              debt(c, 5_000, id = dC, minute = 9),
              Commands.contactArchive(
                  Events.origin,
                  Events.at(10),
                  c,
                  expectedVersion = 1,
                  reason = "ترحيل",
                  entityId = c,
                  operationId = UUID.randomUUID().toString(),
              ),
              offset(dA1, dA2, 1_000, id = offsetA, minute = 11),
          )
      )
      // A queued new person with a queued debt.
      val d =
          store.repository.addContact("د", null, null, null, FirstDebt(Cents(2_000))).getOrThrow()
      val dIds = queuedIdsOf(d, store.outbox.snapshot())
      // A queued payment on a confirmed debt, and one too large to apply (excluded).
      val queuedPayment = store.seedPayment(dA1, Cents(500), note = "دفعة مؤجلة")
      val excessivePayment = store.seedPayment(dB1, Cents(99_999))
      // A rejected contact.create, still saved with its debts.
      val e =
          store.repository.addContact("هـ", null, null, null, FirstDebt(Cents(700))).getOrThrow()
      val eRows = store.outbox.snapshot()
      val eCreate = eRows.first { it.entityId == e && it.type == CommandType.CONTACT_CREATE }
      store.outbox.settle(
          eRows
              .filter { it.entityId == e || eCreate.operationId in it.command.dependsOn }
              .map { row ->
                receiptOf(
                    row.command,
                    ReceiptStatus.REJECTED,
                    code =
                        if (row.type == CommandType.CONTACT_CREATE) "contact_limit_reached"
                        else "dependency_rejected",
                )
              }
      )

      val expected =
          store.repository.contactRecords
              .first {
                it != null &&
                    it.ledger.archivedContacts.any { person -> person.id == c } &&
                    it.ledger.contacts.any { person -> person.id == d } &&
                    it.failed.any { draft -> draft.contact.id == e } &&
                    it.ledger.debts.firstOrNull { row -> row.id == dA1 }?.remaining == Cents(4_500)
              }
              .let {
                assertNotNull(it)
                it!!
              }
      val mine =
          mapOf(
              a to
                  setOf(
                      a,
                      dA1,
                      dA2,
                      paymentPartial,
                      paymentReversed,
                      reverse.entityId,
                      offsetA,
                      queuedPayment,
                  ),
              b to setOf(b, dB1, excessivePayment),
              c to setOf(c),
              d to dIds,
              e to queuedIdsOf(e, store.outbox.snapshot()),
          )

      // excludedOperations holds operation ids; `mine` holds entity ids.
      val rows = store.outbox.snapshot()
      fun operationsOf(own: Set<String>) =
          rows.filter { it.entityId in own }.mapTo(HashSet()) { it.operationId }
      assertTrue(
          "the excessive payment is refused in the whole store, so the check below is not empty",
          operationsOf(setOf(excessivePayment)).single() in expected.ledger.excludedOperations,
      )

      for ((id, own) in mine) {
        val actual = checkNotNull(store.personLedger.records(id)) { id }
        val view = expected.ledger
        val ownOperations = operationsOf(own)
        assertEquals(
            "$id: same ContactView from either side",
            view.contacts.firstOrNull { it.id == id },
            actual.ledger.contacts.firstOrNull { it.id == id },
        )
        assertEquals(
            "$id: same archived ContactView",
            view.archivedContacts.firstOrNull { it.id == id },
            actual.ledger.archivedContacts.firstOrNull { it.id == id },
        )
        assertEquals(
            "$id: their debts as today's whole-store view holds them",
            view.debts.filter { it.contactId == id },
            actual.ledger.debts.filter { it.contactId == id },
        )
        assertEquals(
            "$id: their payments as today's whole-store view holds them",
            view.payments.filter { it.contactId == id },
            actual.ledger.payments.filter { it.contactId == id },
        )
        // A confirmed row of another person must never leak in; a queued new person's commands do
        // project on everyone's view(), exactly as they do in the whole store, and stay pending.
        assertTrue(
            "$id: only confirmed rows of their own, or pending ones",
            actual.ledger.debts.all { it.contactId == id || it.pending } &&
                actual.ledger.payments.all { it.contactId == id || it.pending },
        )
        assertEquals("$id: same net", view.net(id), actual.ledger.net(id))
        assertEquals(
            "$id: same refusals of their commands",
            view.excludedOperations intersect ownOperations,
            actual.ledger.excludedOperations intersect ownOperations,
        )
        assertEquals(
            "$id: same failed draft",
            expected.failed.firstOrNull { it.contact.id == id },
            actual.failed.firstOrNull { it.contact.id == id },
        )
      }
    }
  }

  @Test
  fun recordsIsNullWhileTheStoreHasNoLedgerMeta() = runTest {
    TestGraph.unsigned().use { fixture ->
      val scope = fixture.signIn(STORE_A, "store-$STORE_A")
      assertNull(scope.personLedger.records(STORE_A))
    }
  }

  /** A person's own queued commands: the create and every row that depends on it. */
  private fun queuedIdsOf(personContactId: String, rows: List<OutboxRow>): Set<String> {
    val create = rows.first {
      it.entityId == personContactId && it.command.type == CommandType.CONTACT_CREATE
    }
    return setOf(personContactId) +
        rows.filter { create.operationId in it.command.dependsOn }.map { it.entityId }.toSet()
  }
}
