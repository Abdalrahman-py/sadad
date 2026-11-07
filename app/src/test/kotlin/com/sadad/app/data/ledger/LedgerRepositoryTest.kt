package com.sadad.app.data.ledger

import app.cash.turbine.test
import com.sadad.app.data.db.ContactSummaryDao
import com.sadad.app.data.db.ContactSummaryEntity
import com.sadad.app.data.db.Events
import com.sadad.app.data.db.LedgerMetaDao
import com.sadad.app.data.db.LedgerStore
import com.sadad.ledger.Cents
import com.sadad.ledger.CommandType
import com.sadad.ledger.Commands
import com.sadad.ledger.Direction
import com.sadad.ledger.LedgerCommand
import com.sadad.ledger.LedgerState
import com.sadad.ledger.PaymentMethod
import com.sadad.ledger.ReceiptStatus
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LedgerRepositoryTest : LedgerTestCase() {

  private val countedScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  /**
   * Runs before the fixture's teardown ([LedgerTestCase.closeDatabase]), so Room sees no
   * collectors.
   */
  @After
  fun joinCountedScope() {
    runBlocking { countedScope.coroutineContext[Job]?.cancelAndJoin() }
  }

  @Test
  fun theViewStaysNullUntilTheRowsAreLoaded() = runTest {
    val repo = repository()

    withContext(Dispatchers.Default) {
      // Real time: by now the outbox and the staff name have emitted, only the rows are missing.
      delay(300)
      assertNull(repo.view.value)

      repo.load()
      withTimeout(5_000) {
        repo.awaitLoaded()
        repo.view.first { it != null }
      }
    }
  }

  @Test
  fun firstContactWithAnInvalidPhoneCannotQueueThePersonOrItsDebt() = runTest {
    val repo = repository()
    repo.load()
    for (phone in listOf("+970599123456", "599123456", "05991234567", "0299123456")) {
      val result = repo.addContact("أول شخص", phone, null, null, FirstDebt(Cents(25_800)))
      assertTrue(
          "invalid wire phone must be refused before queueing either command",
          result.isFailure,
      )
      assertTrue(outbox.snapshot().isEmpty())
    }
  }

  @Test
  fun compoundSaveShowsTheContactAndTheDebtAsPending() = runTest {
    val repo = repository()
    repo.load()

    repo.view.test {
      val saved =
          repo.addContact(
              name = "سامي",
              phone = "0599123456",
              category = "family",
              note = "قريب",
              firstDebt = FirstDebt(Cents(10_000)),
          )
      assertTrue(saved.isSuccess)

      val view = awaitView { it.contacts.any { contact -> contact.name == "سامي" } }
      val contact = view.contacts.single { it.name == "سامي" }
      assertTrue(contact.pending)
      val debt = view.debts.single { it.contactId == contact.id }
      assertEquals(Direction.RECEIVABLE, debt.direction)
      assertEquals(Cents(10_000), debt.amount)
      assertTrue(debt.pending)
      assertEquals(Cents(10_000), view.receivable)
      assertEquals(Cents.ZERO, view.confirmedReceivable)
      assertEquals(1, view.pendingContacts)
      assertEquals(1, view.pendingDebts)

      val rows = outbox.snapshot()
      assertEquals(2, rows.size)
      assertTrue(rows.all { it.state == OutboxState.QUEUED })
      val contactRow = rows.single { it.type == CommandType.CONTACT_CREATE }
      val debtRow = rows.single { it.type == CommandType.DEBT_CREATE }
      assertEquals(contact.id, contactRow.entityId)
      assertEquals(listOf(contactRow.operationId), debtRow.command.dependsOn)
      assertEquals(1, session.queued)
    }
  }

  @Test
  fun payContactSplitsTheAmountOldestFirstAndRefusesAnOverpayment() = runTest {
    val repo = repository()
    repo.load()
    repo.seed(
        contactCommand(),
        debtCommand(amount = 10_000, id = DEBT_ID, minute = 1),
        debtCommand(amount = 5_000, id = OTHER_DEBT_ID, minute = 2),
    )

    val paid = repo.payContact(CONTACT_ID, Cents(12_000), PaymentMethod.CASH, "")
    assertTrue(paid.isSuccess)

    val first = outbox.snapshot()
    assertEquals(listOf(10_000L, 2_000L), first.map { paymentAmount(it) })
    assertTrue(first.all { it.type == CommandType.PAYMENT_CREATE })
    assertTrue(first.all { it.command.dependsOn.isEmpty() })
    assertEquals(1, session.queued)

    val overpaid = repo.payContact(CONTACT_ID, Cents(16_000), PaymentMethod.CASH, "")
    assertTrue(overpaid.exceptionOrNull() is Refusal.Invalid)
    assertEquals(first.size, outbox.snapshot().size)
    assertEquals(1, session.queued)
  }

  @Test
  fun pendingParentsAreDependenciesOfTheirChildren() = runTest {
    val repo = repository()
    repo.load()

    val contactId = repo.addContact("سامي", null, null, null, null).getOrThrow()
    val debtId =
        repo.addDebt(contactId, Direction.RECEIVABLE, Cents(5_000), null, null).getOrThrow()
    val payment = repo.payDebt(debtId, Cents(1_000), PaymentMethod.CASH, null)
    assertTrue(payment.isSuccess)

    val rows = outbox.snapshot()
    val contactRow = rows.single { it.type == CommandType.CONTACT_CREATE }
    val debtRow = rows.single { it.type == CommandType.DEBT_CREATE }
    val paymentRow = rows.single { it.type == CommandType.PAYMENT_CREATE }
    assertEquals(listOf(contactRow.operationId), debtRow.command.dependsOn)
    assertEquals(listOf(debtRow.operationId), paymentRow.command.dependsOn)
  }

  @Test
  fun anAcceptedRowStaysPendingUntilThePageHoldsIt() = runTest {
    val repo = repository()
    repo.load()
    repo.addContact("سامي", null, null, null, null)
    val command = outbox.snapshot().single().command

    outbox.settle(listOf(receiptOf(command, ReceiptStatus.ACCEPTED)))
    repo.view.test { assertTrue(awaitView { it.contacts.isNotEmpty() }.contacts.single().pending) }

    repo.applyPage(Events.page(listOf(Events.accepted(command, 1))))

    repo.view.test {
      val view = awaitView { it.contacts.isNotEmpty() && !it.contacts.single().pending }
      assertFalse(view.contacts.single().pending)
    }
    assertTrue(outbox.snapshot().isEmpty())
  }

  @Test
  fun aPageOfAnotherEpochResetsTheStateAndKeepsTheOutbox() = runTest {
    val repo = repository()
    repo.load()
    repo.applyPage(Events.page(listOf(Events.accepted(contactCommand(name = "مؤكد"), 1))))
    repo.addContact("معلق", null, null, null, null)

    val otherEpoch = Events.page(emptyList()).copy(epoch = "11111111-2222-4333-8444-555555555555")
    val failure = runCatching { repo.applyPage(otherEpoch) }.exceptionOrNull()

    assertTrue(failure is LedgerReset)
    assertEquals(1, outbox.snapshot().size)
    repo.view.test {
      val view = awaitView { it.contacts.size == 1 && it.contacts.single().name == "معلق" }
      assertTrue(view.contacts.single().pending)
    }
  }

  /**
   * An idle poll's page (no events, cursor unmoved) must do no work at all. A persist is counted
   * through the store's DAO hook: [LedgerStore] takes its DAOs as parameters so a test can count
   * writes, and [LedgerMetaDao.put] runs once per persist. A new equal `LedgerState` instance would
   * be conflated by the state flow, so only the persist count proves the skip.
   */
  @Test
  fun anIdlePageEmitsNothingPersistsNothingAndKeepsTheCursor() = runTest {
    val persists = AtomicInteger()
    val repo = repositoryCountingPersist(persists)
    repo.load()
    repo.seed(contactCommand())
    val afterSeed = persists.get()

    var records: ContactRecords? = null
    repo.contactRecords.test {
      // The projection runs on Default: wait for the seeded records, or they land mid-check.
      while (records?.ledger?.contacts.isNullOrEmpty()) records = awaitItem()
      repo.applyPage(Events.page(emptyList(), cursor = 1, nextCursor = 1))
      // Real time: give the idle pass somewhere to (wrongly) emit into.
      withContext(Dispatchers.Default) { delay(300) }
      expectNoEvents()
    }

    assertEquals("an idle page must not persist", afterSeed, persists.get())
    assertTrue("an idle page must not republish the records", records === repo.contactRecords.value)
    assertEquals(1L, db.metaDao().get()!!.cursor)
  }

  /**
   * The server may trim rejected-only events, so an empty page that moves the cursor is not busy
   * work: it must still fold into the state and persist the new cursor.
   */
  @Test
  fun anEmptyPageThatMovesTheCursorStillPersistsIt() = runTest {
    val repo = repository()
    repo.load()
    repo.seed(contactCommand())

    repo.applyPage(Events.page(emptyList(), cursor = 2, nextCursor = 2))

    assertEquals(2L, db.metaDao().get()!!.cursor)
  }

  /** The no-op skip never hides a reset: epoch is checked before the idle-page return. */
  @Test
  fun anIdlePageOfAnotherEpochStillResetsTheState() = runTest {
    val repo = repository()
    repo.load()
    repo.seed(contactCommand())

    val otherEpoch = Events.page(emptyList()).copy(epoch = "11111111-2222-4333-8444-555555555555")
    val failure = runCatching { repo.applyPage(otherEpoch) }.exceptionOrNull()

    assertTrue(failure is LedgerReset)
    assertNull(db.metaDao().get())
  }

  @Test
  fun refusingBeforeQueueingWritesNothing() = runTest {
    val repo = repository()
    repo.load()

    session.origin = null
    assertTrue(
        repo.addContact("سامي", null, null, null, null).exceptionOrNull() is Refusal.NotReady
    )
    assertTrue(outbox.snapshot().isEmpty())

    session.origin = Events.origin
    session.online = false
    session.offline = false
    assertTrue(
        repo.addContact("سامي", null, null, null, null).exceptionOrNull()
            is Refusal.OfflineNotAllowed
    )
    assertTrue(outbox.snapshot().isEmpty())
  }

  @Test
  fun archivingWhileOfflineIsOnlineOnly() = runTest {
    val repo = repository()
    repo.load()
    repo.seed(contactCommand())

    session.online = false
    val archived = repo.archiveContact(CONTACT_ID, "سبب")

    assertTrue(archived.exceptionOrNull() is Refusal.OnlineOnly)
    assertTrue(outbox.snapshot().isEmpty())
  }

  @Test
  fun archivingAContactWithAnOpenBalanceIsRefused() = runTest {
    val repo = repository()
    repo.load()
    repo.seed(contactCommand(), debtCommand(amount = 10_000))

    val archived = repo.archiveContact(CONTACT_ID, "سبب")

    assertTrue(archived.exceptionOrNull() is Refusal.Invalid)
    assertTrue(outbox.snapshot().isEmpty())
  }

  @Test
  fun archivingAContactWithAnOpenPayableIsRefused() = runTest {
    val repo = repository()
    repo.load()
    repo.seed(
        contactCommand(),
        debtCommand(amount = 10_000, direction = Direction.PAYABLE),
    )

    val archived = repo.archiveContact(CONTACT_ID, "سبب")

    assertTrue(archived.exceptionOrNull() is Refusal.Invalid)
    assertTrue(outbox.snapshot().isEmpty())
  }

  @Test
  fun aPendingContactCanBeEditedBeforeItIsConfirmed() = runTest {
    val repo = repository()
    repo.load()
    val contactId = repo.addContact("سامي", null, null, null, null).getOrThrow()
    val created = outbox.snapshot().single { it.type == CommandType.CONTACT_CREATE }

    val updated = repo.updateContact(contactId, "سامي الجديد", null, null, null)

    assertTrue(updated.isSuccess)
    val update = outbox.snapshot().single { it.type == CommandType.CONTACT_UPDATE }
    assertEquals(listOf(created.operationId), update.command.dependsOn)
    assertEquals(1L, update.command.expectedVersion)
  }

  @Test
  fun twoEditsBeforeAPullCarryIncreasingVersions() = runTest {
    val repo = repository()
    repo.load()
    repo.seed(contactCommand())

    assertTrue(repo.updateContact(CONTACT_ID, "الاسم الأول", null, null, null).isSuccess)
    assertTrue(repo.updateContact(CONTACT_ID, "الاسم الثاني", null, null, null).isSuccess)

    val versions =
        outbox
            .snapshot()
            .filter { it.type == CommandType.CONTACT_UPDATE }
            .map { it.command.expectedVersion }
    assertEquals(
        "the second edit names the version the first one consumes",
        listOf(1L, 2L),
        versions,
    )
  }

  @Test
  fun cancellingAQueuedCompoundHidesItFromTheView() = runTest {
    val repo = repository()
    repo.load()
    repo.addContact(
        "سامي",
        null,
        null,
        null,
        FirstDebt(Cents(10_000)),
    )
    val contactId = outbox.snapshot().single { it.type == CommandType.CONTACT_CREATE }.operationId

    repo.view.test {
      awaitView { it.contacts.isNotEmpty() && it.debts.isNotEmpty() }
      outbox.cancel(contactId)
      val view = awaitView { it.contacts.isEmpty() && it.debts.isEmpty() }
      assertEquals(0, view.pendingContacts)
      assertEquals(0, view.pendingDebts)
    }
  }

  @Test
  fun discardingARejectedDraftDismissesItsDebtThenThePerson() = runTest {
    val repo = repository()
    repo.load()
    val id = repo.addContact("منى", "0599123456", null, null, FirstDebt(Cents(4_000))).getOrThrow()
    outbox.settle(
        outbox.snapshot().map {
          receiptOf(
              it.command,
              ReceiptStatus.REJECTED,
              if (it.type == CommandType.CONTACT_CREATE) "contact_limit_reached"
              else "dependency_rejected",
          )
        }
    )
    repo.contactRecords.first { it?.failed?.isNotEmpty() == true }

    assertTrue(repo.discardDraft(id).isSuccess)

    assertTrue(outbox.snapshot().isEmpty())
    assertTrue(
        checkNotNull(repo.contactRecords.first { it?.failed?.isEmpty() == true }).failed.isEmpty()
    )
  }

  @Test
  fun discardingAQueuedOrAcceptedPersonIsRefused() = runTest {
    val repo = repository()
    repo.load()
    val queued = repo.addContact("منى", null, null, null, null).getOrThrow()
    assertTrue(repo.discardDraft(queued).exceptionOrNull() is Refusal.Unknown)
    assertEquals(1, outbox.snapshot().size)

    val command = outbox.snapshot().single().command
    repo.applyPage(
        com.sadad.app.data.db.Events.page(listOf(com.sadad.app.data.db.Events.accepted(command, 1)))
    )
    assertTrue(repo.discardDraft(queued).exceptionOrNull() is Refusal.Unknown)
  }

  @Test
  fun anIdOutsideTheViewIsUnknown() = runTest {
    val repo = repository()
    repo.load()

    val added = repo.addDebt(newOperationId(), Direction.RECEIVABLE, Cents(1_000), null, null)
    val paid = repo.payDebt(newOperationId(), Cents(1_000), PaymentMethod.CASH, null)

    assertTrue(added.exceptionOrNull() is Refusal.Unknown)
    assertTrue(paid.exceptionOrNull() is Refusal.Unknown)
    assertTrue(outbox.snapshot().isEmpty())
  }

  @Test
  fun aQueuedEntryKeepsTheConfirmedRowsItAlreadyBuilt() = runTest {
    val repo = repository()
    repo.load()
    repo.seed(contactCommand(), debtCommand())

    repo.view.test {
      val before = awaitView { it.debts.isNotEmpty() }
      repo.payDebt(DEBT_ID, Cents(1_000), PaymentMethod.CASH, null)
      val after = awaitView { it.payments.isNotEmpty() }

      assertEquals(Cents(9_000), after.debts.single().remaining)
      // The same object, not an equal copy: showing the entry rebuilt nothing confirmed.
      assertSame(before.contacts.single(), after.contacts.single())
    }
  }

  @Test
  fun aPaymentPageUpsertsExactlyOneSummaryRow() = runTest {
    val counting = CountingSummaryDao(db.contactSummaryDao())
    val repo =
        LedgerRepository(LedgerStore(db, summaries = counting), outbox, session, countedScope)
    repo.load()
    repo.seed(contactCommand(), debtCommand(amount = 10_000, id = DEBT_ID, minute = 1))
    assertEquals(1, db.contactSummaryDao().all().size)
    counting.reset()

    val payment = paymentCommand(debtId = DEBT_ID, amount = 1_000, id = PAYMENT_ID, minute = 2)
    repo.applyPage(Events.page(listOf(Events.accepted(payment, 3))))

    assertEquals("one payment touches one person", 1, counting.upserted)
    assertEquals(0, counting.deleted)
    val rows = db.contactSummaryDao().all()
    assertEquals(1, rows.size)
    assertEquals(CONTACT_ID, rows.single().contactId)
    assertEquals(Cents(9_000).value, rows.single().net)
    assertEquals(Cents(9_000).value, rows.single().openReceivable)
  }

  @Test
  fun anOffsetPageRewritesOnePersonsRow() = runTest {
    val counting = CountingSummaryDao(db.contactSummaryDao())
    val repo =
        LedgerRepository(LedgerStore(db, summaries = counting), outbox, session, countedScope)
    repo.load()
    repo.seed(
        contactCommand(),
        debtCommand(amount = 10_000, id = DEBT_ID, minute = 1),
        debtCommand(amount = 3_000, id = OTHER_DEBT_ID, minute = 2, direction = Direction.PAYABLE),
    )
    counting.reset()

    val offsetId = UUID.randomUUID().toString()
    val offset =
        Commands.debtOffset(
            Events.origin,
            Events.at(3),
            receivableDebtId = DEBT_ID,
            payableDebtId = OTHER_DEBT_ID,
            amount = Cents(1_000),
            entityId = offsetId,
            operationId = newOperationId(),
        )
    repo.applyPage(Events.page(listOf(Events.accepted(offset, 4))))

    assertEquals("one offset touches one person", 1, counting.upserted)
    assertEquals(0, counting.deleted)
    val row = db.contactSummaryDao().all().single()
    assertEquals(CONTACT_ID, row.contactId)
    // 10_000 receivable less 1_000 offset is 9_000; 3_000 payable less 1_000 is 2_000.
    assertEquals(Cents(7_000).value, row.net)
    assertEquals(Cents(9_000).value, row.openReceivable)
    assertEquals(Cents(2_000).value, row.openPayable)
  }

  @Test
  fun aContactDeletePageDeletesTheRow() = runTest {
    val repo = repository()
    repo.load()
    repo.seed(contactCommand())
    assertEquals(1, db.contactSummaryDao().all().size)

    val archive =
        Commands.contactArchive(
            Events.origin,
            Events.at(1),
            CONTACT_ID,
            expectedVersion = 1,
            reason = "ترحيل",
            entityId = CONTACT_ID,
            operationId = newOperationId(),
        )
    repo.applyPage(Events.page(listOf(Events.accepted(archive, 2))))
    assertTrue(db.contactSummaryDao().all().isEmpty())

    // A stale row (e.g. left by an older build) must still be removed by the delete page alone.
    val stale =
        db.contactSummaryDao().all().singleOrNull()?.let {
          return@let it
        }
            ?: kotlin.run {
              // Seed one stale row the delete page must remove.
              db.contactSummaryDao()
                  .upsert(
                      listOf(
                          ContactSummaryEntity(
                              contactId = CONTACT_ID,
                              name = "stale",
                              phone = "",
                              kind = com.sadad.ledger.ContactKind.CUSTOMER,
                              category = "",
                              pending = false,
                              createdAt = Events.at(0),
                              net = 0,
                              openReceivable = 0,
                              openPayable = 0,
                              earliestOpenDue = null,
                              lastEntryAt = null,
                              searchKey = "stale",
                          )
                      )
                  )
              db.contactSummaryDao().all().single()
            }
    assertEquals(CONTACT_ID, stale.contactId)

    val delete =
        Commands.contactDelete(
            Events.origin,
            Events.at(2),
            CONTACT_ID,
            expectedVersion = 2,
            reason = "حذف",
            entityId = CONTACT_ID,
            operationId = newOperationId(),
        )
    repo.applyPage(Events.page(listOf(Events.accepted(delete, 3))))

    assertTrue(db.contactSummaryDao().all().isEmpty())
  }

  @Test
  fun aFailingPageLeavesSummariesUnchanged() = runTest {
    val repo = repository()
    repo.load()
    repo.seed(contactCommand(), debtCommand(amount = 10_000, id = DEBT_ID, minute = 1))
    val before = db.contactSummaryDao().all()

    // A payment on a debt the state never held is refused by the fold.
    val bad =
        Commands.paymentCreate(
            Events.origin,
            Events.at(2),
            debtId = UUID.randomUUID().toString(),
            amount = Cents(100),
            method = PaymentMethod.CASH,
            entityId = UUID.randomUUID().toString(),
            operationId = newOperationId(),
        )
    val failure = runCatching { repo.applyPage(Events.page(listOf(Events.accepted(bad, 3)))) }
    assertTrue(failure.isFailure)
    assertEquals(before, db.contactSummaryDao().all())
  }

  @Test
  fun afterThreePagesEveryRowEqualsWholeStoreView() = runTest {
    val repo = repository()
    repo.load()
    val thirdContact = UUID.randomUUID().toString()
    val thirdDebt = UUID.randomUUID().toString()
    val payableDebt = OTHER_DEBT_ID

    repo.applyPage(
        Events.page(
            listOf(
                Events.accepted(contactCommand(), 1),
                Events.accepted(
                    Commands.contactCreate(
                        Events.origin,
                        Events.at(1),
                        name = "ثاني",
                        entityId = OTHER_CONTACT_ID,
                        operationId = newOperationId(),
                    ),
                    2,
                ),
                Events.accepted(
                    Commands.contactCreate(
                        Events.origin,
                        Events.at(2),
                        name = "ثالث",
                        entityId = thirdContact,
                        operationId = newOperationId(),
                    ),
                    3,
                ),
            )
        )
    )

    repo.applyPage(
        Events.page(
            listOf(
                Events.accepted(debtCommand(amount = 10_000, id = DEBT_ID, minute = 3), 4),
                Events.accepted(
                    debtCommand(
                        amount = 5_000,
                        id = thirdDebt,
                        minute = 4,
                        contactId = thirdContact,
                    ),
                    5,
                ),
                Events.accepted(
                    debtCommand(
                        amount = 3_000,
                        id = payableDebt,
                        minute = 5,
                        direction = Direction.PAYABLE,
                    ),
                    6,
                ),
            )
        )
    )

    // A queued payment that must count in every later summary.
    repo.payDebt(DEBT_ID, Cents(500), PaymentMethod.CASH, "مؤجل").getOrThrow()

    val offsetId = UUID.randomUUID().toString()
    val offset =
        Commands.debtOffset(
            Events.origin,
            Events.at(6),
            receivableDebtId = DEBT_ID,
            payableDebtId = payableDebt,
            amount = Cents(1_000),
            entityId = offsetId,
            operationId = newOperationId(),
        )
    val archiveThird =
        Commands.contactArchive(
            Events.origin,
            Events.at(7),
            thirdContact,
            expectedVersion = 1,
            reason = "ترحيل",
            entityId = thirdContact,
            operationId = newOperationId(),
        )
    repo.applyPage(
        Events.page(listOf(Events.accepted(offset, 7), Events.accepted(archiveThird, 8)))
    )

    val view =
        repo.view.first {
          it != null && it.contacts.size == 2 && it.debts.any { row -> row.id == DEBT_ID }
        } ?: error("no view")
    val rows = db.contactSummaryDao().all()
    assertEquals(
        "only the two visible people have rows",
        view.contacts.map { it.id }.toSet(),
        rows.map { it.contactId }.toSet(),
    )
    assertTrue("archived person has no row", rows.none { it.contactId == thirdContact })
    for (row in rows) {
      assertEquals(
          "row ${row.contactId} equals summaryOf(wholeStoreView)",
          summaryOf(view, row.contactId),
          row,
      )
    }
  }

  @Test
  fun loadFillsEmptySummariesFromExistingRows() = runTest {
    val thirdContact = UUID.randomUUID().toString()
    val thirdDebt = UUID.randomUUID().toString()
    val queuedContact = UUID.randomUUID().toString()
    val queuedDebt = UUID.randomUUID().toString()
    val queuedPayment = UUID.randomUUID().toString()
    val reversed = UUID.randomUUID().toString()
    val reverse = UUID.randomUUID().toString()
    val offsetId = UUID.randomUUID().toString()
    val payable = UUID.randomUUID().toString()

    // Seed confirmed rows through the store directly, so no summaries are written yet.
    val seedStore = LedgerStore(db)
    val commands =
        listOf(
            contactCommand(name = "أ", id = CONTACT_ID, minute = 0),
            Commands.contactCreate(
                Events.origin,
                Events.at(1),
                name = "ب",
                entityId = OTHER_CONTACT_ID,
                operationId = newOperationId(),
            ),
            Commands.contactCreate(
                Events.origin,
                Events.at(2),
                name = "ج",
                entityId = thirdContact,
                operationId = newOperationId(),
            ),
            debtCommand(amount = 10_000, id = DEBT_ID, minute = 3),
            debtCommand(
                amount = 4_000,
                id = OTHER_DEBT_ID,
                minute = 4,
                contactId = OTHER_CONTACT_ID,
            ),
            debtCommand(amount = 3_000, id = payable, minute = 5, direction = Direction.PAYABLE),
            paymentCommand(debtId = DEBT_ID, amount = 4_000, id = paymentCommandId(), minute = 6),
            paymentCommand(debtId = DEBT_ID, amount = 2_000, id = reversed, minute = 7),
            Commands.paymentReverse(
                Events.origin,
                Events.at(8),
                reversed,
                "خطأ",
                entityId = reverse,
                operationId = newOperationId(),
            ),
            debtCommand(amount = 5_000, id = thirdDebt, minute = 9, contactId = thirdContact),
            Commands.contactArchive(
                Events.origin,
                Events.at(10),
                thirdContact,
                expectedVersion = 1,
                reason = "ترحيل",
                entityId = thirdContact,
                operationId = newOperationId(),
            ),
            Commands.debtOffset(
                Events.origin,
                Events.at(11),
                receivableDebtId = DEBT_ID,
                payableDebtId = payable,
                amount = Cents(1_000),
                entityId = offsetId,
                operationId = newOperationId(),
            ),
        )
    val page =
        Events.page(commands.mapIndexed { index, command -> Events.accepted(command, index + 1L) })
    seedStore.persist(LedgerState.EMPTY, LedgerState.EMPTY.apply(page), page)
    assertTrue(db.contactSummaryDao().all().isEmpty())

    // Queued entries that must count in the fill.
    val queuedContactCmd =
        Commands.contactCreate(
            Events.origin,
            Events.at(20),
            name = "د",
            entityId = queuedContact,
            operationId = newOperationId(),
        )
    val queuedDebtCmd =
        Commands.debtCreate(
            Events.origin,
            Events.at(20),
            contactId = queuedContact,
            direction = Direction.RECEIVABLE,
            amount = Cents(2_000),
            entityId = queuedDebt,
            operationId = newOperationId(),
            dependsOn = listOf(queuedContactCmd.operationId),
        )
    val queuedPaymentCmd =
        Commands.paymentCreate(
            Events.origin,
            Events.at(21),
            debtId = DEBT_ID,
            amount = Cents(500),
            method = PaymentMethod.CASH,
            entityId = queuedPayment,
            operationId = newOperationId(),
        )
    outbox.append(listOf(queuedContactCmd, queuedDebtCmd, queuedPaymentCmd))

    val repo = LedgerRepository(LedgerStore(db), outbox, session, countedScope)
    repo.load()
    repo.summaryReady.first { it }

    val view =
        repo.view.first {
          it != null &&
              it.contacts.any { person -> person.id == queuedContact } &&
              it.debts.firstOrNull { row -> row.id == DEBT_ID }?.remaining == Cents(4_500)
        } ?: error("no view")
    val rows = db.contactSummaryDao().all()
    assertEquals(
        "every visible person incl. queued has a row",
        view.contacts.map { it.id }.toSet(),
        rows.map { it.contactId }.toSet(),
    )
    assertTrue("archived person has no row", rows.none { it.contactId == thirdContact })
    for (row in rows) {
      assertEquals(
          "row ${row.contactId} equals summaryOf(wholeStoreView)",
          summaryOf(view, row.contactId),
          row,
      )
    }
  }

  @Test
  fun anEmptyStoreWritesNothingAndIsReady() = runTest {
    val counting = CountingSummaryDao(db.contactSummaryDao())
    val repo =
        LedgerRepository(LedgerStore(db, summaries = counting), outbox, session, countedScope)
    repo.load()
    repo.summaryReady.first { it }
    assertEquals(0, counting.upserted)
    assertTrue(db.contactSummaryDao().all().isEmpty())
  }

  @Test
  fun aSecondLoadWithRowsWritesNothing() = runTest {
    val seedStore = LedgerStore(db)
    val page =
        Events.page(
            listOf(
                Events.accepted(contactCommand(), 1),
                Events.accepted(debtCommand(amount = 10_000, id = DEBT_ID, minute = 1), 2),
            )
        )
    seedStore.persist(LedgerState.EMPTY, LedgerState.EMPTY.apply(page), page)

    val counting = CountingSummaryDao(db.contactSummaryDao())
    val repo =
        LedgerRepository(LedgerStore(db, summaries = counting), outbox, session, countedScope)
    repo.load()
    repo.summaryReady.first { it }
    assertTrue("first load fills", counting.upserted > 0)
    val filled = db.contactSummaryDao().all()
    assertTrue(filled.isNotEmpty())

    counting.reset()
    repo.load()
    repo.summaryReady.first { it }
    assertEquals("second load writes nothing", 0, counting.upserted)
    assertEquals(filled, db.contactSummaryDao().all())
  }

  @Test
  fun aPageDuringFillWaitsForIt() = runTest {
    val seedStore = LedgerStore(db)
    val page =
        Events.page(
            listOf(
                Events.accepted(contactCommand(), 1),
                Events.accepted(debtCommand(amount = 10_000, id = DEBT_ID, minute = 1), 2),
            )
        )
    seedStore.persist(LedgerState.EMPTY, LedgerState.EMPTY.apply(page), page)

    val blocking = BlockingSummaryDao(db.contactSummaryDao())
    val repo =
        LedgerRepository(LedgerStore(db, summaries = blocking), outbox, session, countedScope)
    repo.load()
    blocking.started.await()

    val payment =
        paymentCommand(debtId = DEBT_ID, amount = 1_000, id = paymentCommandId(), minute = 2)
    val duringFill =
        async(Dispatchers.Default) {
          repo.applyPage(Events.page(listOf(Events.accepted(payment, 3))))
        }
    withContext(Dispatchers.Default) { delay(300) }
    assertFalse("the page must wait for the fill", duringFill.isCompleted)

    blocking.release.complete(Unit)
    duringFill.await()
    repo.summaryReady.first { it }

    val rows = db.contactSummaryDao().all()
    assertEquals(1, rows.size)
    assertEquals(Cents(9_000).value, rows.single().net)
  }

  /** A helper id that is a valid UUID for payment entities. */
  private fun paymentCommandId(): String = UUID.randomUUID().toString()

  /** A DAO that blocks its first upsert until [release], to hold the fill open. */
  private class BlockingSummaryDao(private val real: ContactSummaryDao) :
      ContactSummaryDao by real {
    val started = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()

    override suspend fun upsert(rows: List<ContactSummaryEntity>) {
      started.complete(Unit)
      release.await()
      real.upsert(rows)
    }
  }

  /** A counting wrapper, to see how many summary rows one page writes. */
  private class CountingSummaryDao(private val real: ContactSummaryDao) :
      ContactSummaryDao by real {
    var upserted = 0
    var deleted = 0

    fun reset() {
      upserted = 0
      deleted = 0
    }

    override suspend fun upsert(rows: List<ContactSummaryEntity>) {
      upserted += rows.size
      real.upsert(rows)
    }

    override suspend fun delete(ids: List<String>) {
      deleted += ids.size
      real.delete(ids)
    }
  }

  /** Builds the repository with a `persist`-counting meta DAO, per the [LedgerStore] hook. */
  private fun repositoryCountingPersist(persists: AtomicInteger): LedgerRepository {
    val real = db.metaDao()
    val counted =
        Proxy.newProxyInstance(
            LedgerMetaDao::class.java.classLoader,
            arrayOf(LedgerMetaDao::class.java),
        ) { _, method, args ->
          if (method.name == "put") persists.incrementAndGet()
          method.invoke(real, *(args ?: emptyArray()))
        } as LedgerMetaDao
    return LedgerRepository(LedgerStore(db, meta = counted), outbox, session, countedScope)
  }

  private suspend fun LedgerRepository.seed(vararg commands: LedgerCommand) {
    val events = commands.mapIndexed { index, command -> Events.accepted(command, index + 1L) }
    applyPage(Events.page(events))
  }
}
