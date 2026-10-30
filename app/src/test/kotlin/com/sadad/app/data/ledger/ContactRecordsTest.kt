package com.sadad.app.data.ledger

import com.sadad.ledger.Cents
import com.sadad.ledger.CommandType
import com.sadad.ledger.ReceiptStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ContactRecordsTest : LedgerTestCase() {
  @Test
  fun rejectedInputsStayReadableWithoutBecomingBalances() = runTest {
    val repo = repository()
    repo.load()
    val id =
        repo
            .addContact(
                "إبراهيم",
                "+970599123456",
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
    val records = checkNotNull(repo.contactRecords.first { it?.failed?.isNotEmpty() == true })
    val draft = records.failed.single()
    assertEquals(id, draft.contact.id)
    assertEquals("+970599123456", draft.contact.phone)
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
    repo.contactRecords.first { it?.failed?.isNotEmpty() == true }
    repo.applyPage(
        com.sadad.app.data.db.Events.page(listOf(com.sadad.app.data.db.Events.accepted(command, 1)))
    )
    val records =
        checkNotNull(repo.contactRecords.first { it?.ledger?.contacts?.isNotEmpty() == true })
    assertTrue(records.failed.isEmpty())
    assertEquals(1, records.directory.contacts.size)
  }

  @Test
  fun otherRefusalCodesAlsoKeepTheContactAndExplicitCancellationDoesNot() = runTest {
    val repo = repository()
    repo.load()
    repo.addContact("إبراهيم", null, null, null, null).getOrThrow()
    val row = outbox.snapshot().single()
    val base = checkNotNull(repo.view.first { it != null }).copy(contacts = emptyList())
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
