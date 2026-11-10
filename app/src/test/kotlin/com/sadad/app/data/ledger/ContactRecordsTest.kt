package com.sadad.app.data.ledger

import com.sadad.app.data.db.LedgerStore
import com.sadad.ledger.Cents
import com.sadad.ledger.CommandType
import com.sadad.ledger.LedgerState
import com.sadad.ledger.ReceiptStatus
import com.sadad.ledger.view
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ContactRecordsTest : LedgerTestCase() {
  // The repository's whole-store flows are gone (ticket 168): the tests read the person, the way
  // the screens do.

  @Test
  fun rejectedInputsStayReadableWithoutBecomingBalances() = runTest {
    val repo = repository()
    repo.load()
    val id =
        repo
            .addContact(
                "إبراهيم",
                "0599123456",
                "عميل",
                "ملاحظة",
                FirstDebt(Cents(25_800), "دين", "2026-12-01"),
            )
            .getOrThrow()
    val rows = outbox.snapshot()
    outbox.settle(
        rows.map {
          receiptOf(
              it.command,
              ReceiptStatus.REJECTED,
              if (it.type == CommandType.CONTACT_CREATE) "contact_limit_reached"
              else "dependency_rejected",
          )
        }
    )
    val records = personRecordsUntil(id) { it.failed.isNotEmpty() }
    val draft = records.failed.single()
    assertEquals(id, draft.contact.id)
    assertEquals("0599123456", draft.contact.phone)
    assertEquals("ملاحظة", draft.contact.note)
    assertEquals("contact_limit_reached", draft.code)
    assertEquals(Cents(25_800), draft.debts.single().amountCents)
    assertEquals("2026-12-01", draft.debts.single().dueDate)
    assertEquals("دين", draft.debts.single().note)
    assertEquals(id, records.directory.contacts.single().id)
    assertTrue(records.ledger.contacts.isEmpty())
    assertTrue(records.ledger.debts.isEmpty())
    assertEquals(Cents.ZERO, records.ledger.receivable)
    assertEquals(Cents.ZERO, records.ledger.payable)
    assertEquals(2, outbox.snapshot().size)
    assertEquals(records.failed, contactRecords(records.ledger, outbox.snapshot()).failed)
  }

  @Test
  fun aConfirmedContactWinsOverAnOldFailedAttempt() = runTest {
    val repo = repository()
    repo.load()
    repo.addContact("إبراهيم", null, null, null, null).getOrThrow()
    val command = outbox.snapshot().single().command
    outbox.settle(listOf(receiptOf(command, ReceiptStatus.REJECTED, "duplicate_phone")))
    personRecordsUntil(command.entityId) { it.failed.isNotEmpty() }
    repo.applyPage(
        com.sadad.app.data.db.Events.page(listOf(com.sadad.app.data.db.Events.accepted(command, 1)))
    )
    val records = personRecordsUntil(command.entityId) { it.ledger.contacts.isNotEmpty() }
    assertTrue(records.failed.isEmpty())
    assertEquals(1, records.directory.contacts.size)
  }

  @Test
  fun otherRefusalCodesAlsoKeepTheContactAndExplicitCancellationDoesNot() = runTest {
    val repo = repository()
    repo.load()
    repo.addContact("إبراهيم", null, null, null, null).getOrThrow()
    val row = outbox.snapshot().single()
    // Ticket 162 kept `contactRecords` as a test reference; the base view now comes from the
    // person's own rows (empty, the contact exists only in the queue).
    val base =
        checkNotNull(LedgerStore(db).loadPerson(row.entityId) ?: LedgerState.EMPTY)
            .view(emptyList(), emptySet(), session.selfInstallationId, "")
            .copy(contacts = emptyList())
    for (code in listOf("invalid_phone", "permission_denied", "future_server_code")) {
      val records =
          contactRecords(base, listOf(row.copy(state = OutboxState.REJECTED, code = code)))
      assertEquals(code, records.failed.single().code)
      assertEquals(row.entityId, records.directory.contacts.single().id)
    }
    assertTrue(
        contactRecords(base, listOf(row.copy(state = OutboxState.CANCELLED))).failed.isEmpty()
    )
  }
}
