package com.sadad.app.data.ledger

import app.cash.turbine.test
import com.sadad.app.data.db.ContactDao
import com.sadad.app.data.db.ContactSummaryDao
import com.sadad.app.data.db.ContactSummaryEntity
import com.sadad.app.data.db.DebtDao
import com.sadad.app.data.db.Events
import com.sadad.app.data.db.LedgerMetaDao
import com.sadad.app.data.db.LedgerStore
import com.sadad.app.data.db.OffsetDao
import com.sadad.app.data.db.PaymentDao
import com.sadad.ledger.Cents
import com.sadad.ledger.CommandType
import com.sadad.ledger.Commands
import com.sadad.ledger.ContactArchive
import com.sadad.ledger.ContactCreate
import com.sadad.ledger.ContactDelete
import com.sadad.ledger.ContactRestore
import com.sadad.ledger.ContactUpdate
import com.sadad.ledger.DebtCorrect
import com.sadad.ledger.DebtCreate
import com.sadad.ledger.DebtOffset
import com.sadad.ledger.Direction
import com.sadad.ledger.LedgerCommand
import com.sadad.ledger.LedgerState
import com.sadad.ledger.PaymentCreate
import com.sadad.ledger.PaymentMethod
import com.sadad.ledger.PaymentReverse
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
import kotlinx.coroutines.launch
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
  fun aQueuedEntryKeepsTheConfirmedRowsItAlreadyBuilt() = runTest {
    val repo = repository()
    repo.load()
    repo.seed(contactCommand(), debtCommand())

    repo.view.test {
      val before = awaitView { it.debts.isNotEmpty() }
      outbox.append(
          // What the deleted payDebt queued: a payment on a confirmed debt needs nothing.
          listOf(
              Commands.paymentCreate(
                  Events.origin,
                  java.time.Instant.now(),
                  DEBT_ID,
                  Cents(1_000),
                  PaymentMethod.CASH,
                  null,
              )
          )
      )
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
    val queuedPayment =
        Commands.paymentCreate(
            Events.origin,
            java.time.Instant.now(),
            DEBT_ID,
            Cents(500),
            PaymentMethod.CASH,
            "مؤجل",
        )
    outbox.append(listOf(queuedPayment))

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

  // Ticket 161: the summary follows the outbox.
  @Test
  fun outboxChangesRewriteOnlyTheNamedPersonsSummaries() = runTest {
    val counting = CountingSummaryDao(db.contactSummaryDao())
    val repo =
        LedgerRepository(LedgerStore(db, summaries = counting), outbox, session, countedScope)
    repo.load()
    repo.seed(
        contactCommand(name = "أ", id = CONTACT_ID, minute = 0),
        debtCommand(amount = 10_000, id = DEBT_ID, minute = 1),
        contactCommand(name = "ب", id = OTHER_CONTACT_ID, minute = 2),
        debtCommand(
            contactId = OTHER_CONTACT_ID,
            amount = 4_000,
            id = OTHER_DEBT_ID,
            minute = 3,
        ),
    )
    assertEquals(2, db.contactSummaryDao().all().size)
    counting.reset()
    countedScope.launch { repo.followOutbox(db, counting) }

    // Queue a debt for A offline: A's row grows, B's row is not written.
    val bBefore = db.contactSummaryDao().all().single { it.contactId == OTHER_CONTACT_ID }
    val queuedDebtCommand =
        Commands.debtCreate(
            Events.origin,
            java.time.Instant.now(),
            CONTACT_ID,
            Direction.RECEIVABLE,
            Cents(5_000),
        )
    outbox.append(listOf(queuedDebtCommand))
    val queuedDebt = queuedDebtCommand.entityId
    awaitSummary { rows ->
      rows.singleOrNull { it.contactId == CONTACT_ID }?.net == Cents(15_000).value
    }
    assertEquals("only A is named", 1, counting.upserted)
    assertEquals(0, counting.deleted)
    assertEquals(bBefore, db.contactSummaryDao().all().single { it.contactId == OTHER_CONTACT_ID })
    assertRowsEqualView(repo)

    // Queue a new person with a first debt: a new pending row.
    counting.reset()
    val newPerson = repo.addContact("ج", null, null, null, FirstDebt(Cents(2_000))).getOrThrow()
    awaitSummary { rows ->
      val row = rows.singleOrNull { it.contactId == newPerson }
      row != null && row.pending && row.net == 2_000L
    }
    assertEquals("one new person, one row", 1, counting.upserted)
    assertRowsEqualView(repo)

    // Settle A's queued debt as REJECTED: A's row returns to the old net.
    counting.reset()
    val queuedRow = outbox.snapshot().single { it.entityId == queuedDebt }
    outbox.settle(listOf(receiptOf(queuedRow.command, ReceiptStatus.REJECTED, code = "refused")))
    awaitSummary { rows ->
      rows.singleOrNull { it.contactId == CONTACT_ID }?.net == Cents(10_000).value
    }
    assertEquals(1, counting.upserted)
    assertRowsEqualView(repo)

    // Cancel a queued payment: the owner's row returns to the old open balance.
    val paymentCommand =
        Commands.paymentCreate(
            Events.origin,
            java.time.Instant.now(),
            DEBT_ID,
            Cents(1_000),
            PaymentMethod.CASH,
            null,
        )
    outbox.append(listOf(paymentCommand))
    val paymentId = paymentCommand.entityId
    awaitSummary { rows ->
      rows.singleOrNull { it.contactId == CONTACT_ID }?.openReceivable == Cents(9_000).value
    }
    assertRowsEqualView(repo)
    counting.reset()
    val paymentRow = outbox.snapshot().single { it.entityId == paymentId }
    outbox.cancel(paymentRow.operationId)
    awaitSummary { rows ->
      rows.singleOrNull { it.contactId == CONTACT_ID }?.openReceivable == Cents(10_000).value
    }
    assertEquals(1, counting.upserted)
    assertRowsEqualView(repo)
  }

  @Test
  fun openingAStoreWithAQueuedDebtRewritesThatPersonsRow() = runTest {
    val repo = repository()
    repo.load()
    repo.seed(contactCommand(), debtCommand(amount = 10_000, id = DEBT_ID, minute = 1))
    // A queued debt written before the crash, with no collector running: the row is stale.
    val queuedDebtCommand =
        Commands.debtCreate(
            Events.origin,
            java.time.Instant.now(),
            CONTACT_ID,
            Direction.RECEIVABLE,
            Cents(5_000),
        )
    outbox.append(listOf(queuedDebtCommand))
    awaitSummary { rows ->
      rows.singleOrNull { it.contactId == CONTACT_ID }?.net == Cents(10_000).value
    }
    val queuedDebt = queuedDebtCommand.entityId
    assertEquals(
        Cents(10_000).value,
        db.contactSummaryDao().all().single { it.contactId == CONTACT_ID }.net,
    )

    // Reopen the store: the first outbox emission rewrites A before any other change.
    val reopened = LedgerRepository(LedgerStore(db), outbox, session, countedScope)
    reopened.load()
    reopened.summaryReady.first { it }
    countedScope.launch { reopened.followOutbox(db) }
    awaitSummary { rows ->
      rows.singleOrNull { it.contactId == CONTACT_ID }?.net == Cents(15_000).value
    }
    assertEquals(
        queuedDebt,
        outbox.snapshot().single { it.type == CommandType.DEBT_CREATE }.entityId,
    )
    assertRowsEqualView(reopened)
  }

  /** No pulled page names a queued new person, and the outbox did not change to rewrite them. */
  @Test
  fun aPullAfterAResetKeepsAQueuedPersonsSummary() = runTest {
    val repo = repository()
    repo.load()
    val confirmed =
        listOf(
            Events.accepted(contactCommand(), 1),
            Events.accepted(debtCommand(amount = 10_000, id = DEBT_ID, minute = 1), 2),
        )
    repo.applyPage(Events.page(confirmed))
    countedScope.launch { repo.followOutbox(db) }
    val queued = repo.addContact("معلق", null, null, null, FirstDebt(Cents(2_000))).getOrThrow()
    awaitSummary { rows -> rows.any { it.contactId == queued } }

    val epoch = "11111111-2222-4333-8444-555555555555"
    val failure = runCatching {
      repo.applyPage(Events.page(emptyList()).copy(epoch = epoch))
    }
        .exceptionOrNull()
    assertTrue(failure is LedgerReset)
    assertTrue(db.contactSummaryDao().all().isEmpty())
    repo.view.first { it?.contacts?.size == 1 }

    repo.applyPage(Events.page(confirmed).copy(epoch = epoch))

    repo.view.first { it?.contacts?.size == 2 }
    assertRowsEqualView(repo)
    val row = db.contactSummaryDao().all().single { it.contactId == queued }
    assertTrue(row.pending)
    assertEquals(2_000L, row.net)
  }

  // ---- Characterisation (ticket 167): every write's queued commands and Result, pinned before
  // the write moves to the one-person view. Ids and timestamps are normalized, so the pin is the
  // command's type, order and the fields that matter, exactly as the ticket asks.

  /** The ids a pin strips: ids the fixtures fix stay as they are, the rest get stable tokens. */
  private val fixedIds = setOf(CONTACT_ID, DEBT_ID, OTHER_CONTACT_ID, OTHER_DEBT_ID, PAYMENT_ID)
  private val flattenIds = HashMap<String, String>()

  private fun String.idToken(): String =
      if (this in fixedIds) this else flattenIds.getOrPut(this) { "#${flattenIds.size + 1}" }

  private fun flattened(rows: List<OutboxRow>): List<String> = rows.map { row ->
    fun dependsOn(): String =
        row.command.dependsOn.joinToString(prefix = "[", postfix = "]") { it.idToken() }
    val body =
        when (val p = row.command.payload()) {
          is ContactCreate ->
              "name:${p.name} phone:${p.phone} kind:${p.kind} category:${p.category} " +
                  "note:${p.note} code:${p.whatsappCode} dependsOn:${dependsOn()}"
          is ContactUpdate ->
              "contactId:${p.contactId.idToken()} name:${p.name} phone:${p.phone} kind:${p.kind} " +
                  "category:${p.category} note:${p.note} code:${p.whatsappCode} " +
                  "version:${row.command.expectedVersion} dependsOn:${dependsOn()}"
          is ContactArchive ->
              "contactId:${p.contactId.idToken()} reason:${p.reason} " +
                  "version:${row.command.expectedVersion} dependsOn:${dependsOn()}"
          is ContactRestore ->
              "contactId:${p.contactId.idToken()} reason:${p.reason} " +
                  "version:${row.command.expectedVersion} dependsOn:${dependsOn()}"
          is ContactDelete ->
              "contactId:${p.contactId.idToken()} reason:${p.reason} " +
                  "version:${row.command.expectedVersion} dependsOn:${dependsOn()}"
          is DebtCreate ->
              "contactId:${p.contactId.idToken()} direction:${p.direction} " +
                  "amount:${p.amountCents.value} note:${p.note} due:${p.dueDate} " +
                  "origin:${p.origin} dependsOn:${dependsOn()}"
          is DebtCorrect ->
              "debtId:${p.debtId.idToken()} amount:${p.amountCents.value} reason:${p.reason} " +
                  "version:${row.command.expectedVersion}"
          is DebtOffset ->
              "receivable:${p.receivableDebtId.idToken()} payable:${p.payableDebtId.idToken()} " +
                  "amount:${p.amountCents.value} dependsOn:${dependsOn()}"
          is PaymentCreate ->
              "debtId:${p.debtId.idToken()} amount:${p.amountCents.value} method:${p.method} " +
                  "note:${p.note} dependsOn:${dependsOn()}"
          is PaymentReverse -> "paymentId:${p.paymentId.idToken()} reason:${p.reason}"
        }
    "${row.type.wireName}[$body]"
  }

  /**
   * Three confirmed people with debts (the third with none, for the archive/delete pins) plus one
   * queued contact with a queued debt — the store every characterisation runs against.
   */
  private suspend fun seedThreePeopleWithQueuedEntries(repo: LedgerRepository) {
    repo.load()
    flattenIds.clear()
    val third = UUID.nameUUIDFromBytes("third".toByteArray()).toString()
    val queued = UUID.nameUUIDFromBytes("queued".toByteArray()).toString()
    val queuedDebtId = UUID.nameUUIDFromBytes("queued-debt".toByteArray()).toString()
    repo.seed(
        contactCommand(minute = 0),
        debtCommand(amount = 10_000, id = DEBT_ID, minute = 1),
        contactCommand(name = "ليلى", id = OTHER_CONTACT_ID, minute = 2),
        debtCommand(contactId = OTHER_CONTACT_ID, amount = 4_000, id = OTHER_DEBT_ID, minute = 3),
        contactCommand(name = "مريم", id = third, minute = 4),
    )
    val queuedCreate = contactCommand(name = "ندى", id = queued, minute = 5)
    outbox.append(
        listOf(
            queuedCreate,
            Commands.debtCreate(
                Events.origin,
                Events.at(6),
                contactId = queued,
                direction = Direction.RECEIVABLE,
                amount = Cents(2_000),
                entityId = queuedDebtId,
                dependsOn = listOf(queuedCreate.operationId),
            ),
        )
    )
  }

  @Test
  fun characterisationAddingAContactWithAFirstDebtQueuesTheSameCommands() = runTest {
    val repo = repository()
    seedThreePeopleWithQueuedEntries(repo)
    val seeded = outbox.snapshot().size

    val saved = repo.addContact("نداء", "0599123456", "عميل", "ملاحظة", FirstDebt(Cents(7_500)))
    assertTrue(saved.isSuccess)
    val after = outbox.snapshot().drop(seeded)

    // The ids show up as stable tokens: the debt names the contact it was written with and
    // depends on it.
    assertEquals(
        listOf(
            "contact.create[name:نداء phone:0599123456 kind:CUSTOMER category:عميل " +
                "note:ملاحظة code:null dependsOn:[]]",
            "debt.create[contactId:${checkNotNull(saved.getOrNull()).idToken()} " +
                "direction:RECEIVABLE amount:7500 note:null due:null origin:null " +
                // #2 is the contact.create's operation id: the debt waits for it, not for the
                // contact's entity id (#1).
                "dependsOn:[#2]]",
        ),
        flattened(after),
    )
  }

  @Test
  fun characterisationUpdatingAContactQueuesTheSameCommands() = runTest {
    val repo = repository()
    seedThreePeopleWithQueuedEntries(repo)
    val seeded = outbox.snapshot().size

    val saved = repo.updateContact(CONTACT_ID, "سامي الجديد", "0599123457", "زراعي", "ملاحظة جديدة")
    assertTrue(saved.isSuccess)

    assertEquals(
        listOf(
            "contact.update[contactId:$CONTACT_ID name:سامي الجديد phone:0599123457 " +
                "kind:CUSTOMER category:زراعي note:ملاحظة جديدة code:null version:1 dependsOn:[]]",
        ),
        flattened(outbox.snapshot().drop(seeded)),
    )
  }

  @Test
  fun characterisationUpdatingAQueuedContactKeepsItsDependencyAndVersion() = runTest {
    val repo = repository()
    seedThreePeopleWithQueuedEntries(repo)
    val seeded = outbox.snapshot().size
    val queuedCreate =
        outbox.snapshot().single {
          it.type == CommandType.CONTACT_CREATE &&
              it.entityId == UUID.nameUUIDFromBytes("queued".toByteArray()).toString()
        }

    val saved = repo.updateContact(queuedCreate.entityId, "ندى الجديدة", null, null, null)
    assertTrue(saved.isSuccess)

    assertEquals(
        listOf(
            "contact.update[contactId:${queuedCreate.entityId.idToken()} name:ندى الجديدة " +
                "phone:null kind:CUSTOMER category:null note:null code:null version:1 " +
                "dependsOn:[${queuedCreate.operationId.idToken()}]]",
        ),
        flattened(outbox.snapshot().drop(seeded)),
    )
  }

  @Test
  fun characterisationArchivingAZeroBalanceContactQueuesTheSameCommands() = runTest {
    val repo = repository()
    val third = UUID.nameUUIDFromBytes("third".toByteArray()).toString()
    seedThreePeopleWithQueuedEntries(repo)
    val seeded = outbox.snapshot().size

    val saved = repo.archiveContact(third, "ترحيل")
    assertTrue(saved.isSuccess)

    assertEquals(
        listOf(
            "contact.archive[contactId:${third.idToken()} reason:ترحيل version:1 dependsOn:[]]",
        ),
        flattened(outbox.snapshot().drop(seeded)),
    )
  }

  /** Deletes stay refused until the trash; the archive refusal is pinned in the same shape. */
  @Test
  fun characterisationArchivingAContactWithAnOpenBalanceIsRefused() = runTest {
    val repo = repository()
    seedThreePeopleWithQueuedEntries(repo)
    val before = outbox.snapshot().size

    val archived = repo.archiveContact(CONTACT_ID, "ترحيل")
    assertTrue(archived.exceptionOrNull() is Refusal.Invalid)
    assertEquals(
        "سدّد الدين المتبقي على هذا الشخص أولاً.",
        checkNotNull(archived.exceptionOrNull()).message,
    )
    assertEquals(before, outbox.snapshot().size)
  }

  @Test
  fun characterisationDeletingANonArchivedPersonIsRefused() = runTest {
    val repo = repository()
    seedThreePeopleWithQueuedEntries(repo)
    val before = outbox.snapshot().size

    val deleted = repo.deleteContact(CONTACT_ID, "حذف نهائي من سلة المحذوفات")
    assertTrue(deleted.exceptionOrNull() is Refusal.Invalid)
    assertEquals(
        "انقل الشخص إلى سلة المحذوفات أولًا.",
        checkNotNull(deleted.exceptionOrNull()).message,
    )
    assertEquals(before, outbox.snapshot().size)
  }

  @Test
  fun characterisationRestoringAnArchivedContactQueuesTheSameCommands() = runTest {
    val repo = repository()
    val third = UUID.nameUUIDFromBytes("third".toByteArray()).toString()
    seedThreePeopleWithQueuedEntries(repo)
    // The page after the seed's five events: the sequences must follow them.
    repo.applyPage(
        Events.page(
            listOf(
                    Commands.contactArchive(
                        Events.origin,
                        Events.at(9),
                        third,
                        expectedVersion = 1,
                        reason = "ترحيل",
                        entityId = third,
                        operationId = UUID.randomUUID().toString(),
                    ),
                )
                .mapIndexed { index, command -> Events.accepted(command, 6L + index) },
        ),
    )
    val archived =
        repo.view
            .first { view ->
              view?.archivedContacts?.any { it.id == third } == true
            }!!
            .archivedContacts
            .first { it.id == third }
    val seeded = outbox.snapshot().size

    val saved = repo.restoreContact(third, "استعادة من سلة المحذوفات")
    assertTrue(saved.isSuccess)

    // The version is the stored contact's; the queued row must name exactly the version it has.
    assertEquals(
        listOf(
            "contact.restore[contactId:${third.idToken()} reason:استعادة من سلة المحذوفات " +
                "version:${archived.version} dependsOn:[]]",
        ),
        flattened(outbox.snapshot().drop(seeded)),
    )
  }

  @Test
  fun characterisationDeletingAnArchivedZeroBalanceContactQueuesTheSameCommands() = runTest {
    val repo = repository()
    val third = UUID.nameUUIDFromBytes("third".toByteArray()).toString()
    seedThreePeopleWithQueuedEntries(repo)
    // The page after the seed's five events: the sequences must follow them.
    repo.applyPage(
        Events.page(
            listOf(
                    Commands.contactArchive(
                        Events.origin,
                        Events.at(9),
                        third,
                        expectedVersion = 1,
                        reason = "ترحيل",
                        entityId = third,
                        operationId = UUID.randomUUID().toString(),
                    ),
                )
                .mapIndexed { index, command -> Events.accepted(command, 6L + index) },
        ),
    )
    val archived =
        repo.view
            .first { view ->
              view?.archivedContacts?.any { it.id == third } == true
            }!!
            .archivedContacts
            .first { it.id == third }
    val seeded = outbox.snapshot().size

    val saved = repo.deleteContact(third, "حذف نهائي من سلة المحذوفات")
    assertTrue(saved.isSuccess)

    assertEquals(
        listOf(
            "contact.delete[contactId:${third.idToken()} reason:حذف نهائي من سلة المحذوفات " +
                "version:${archived.version} dependsOn:[]]",
        ),
        flattened(outbox.snapshot().drop(seeded)),
    )
  }

  // ---- Red (ticket 167): the writes must check the one person's rows without the whole-store
  // view. The store below throws on every whole-store query, and the repository is never loaded;
  // today the writes hang on `currentView()`, after the switch they answer from Room directly.

  /** Proxies [real] so the whole-store queries throw; every per-person read stays. */
  @Suppress("UNCHECKED_CAST")
  private fun <T : Any> throwingOn(names: Set<String>, type: Class<T>, real: T): T =
      Proxy.newProxyInstance(
          type.classLoader,
          arrayOf(type),
      ) { _, method, args ->
        check(method.name !in names) { "a write read the whole store via ${method.name}" }
        method.invoke(real, *(args ?: emptyArray()))
      } as T

  /**
   * Seeds the confirmed rows through a plain store and opens the repository on a store whose
   * whole-store queries throw, without ever calling `load()`. The whole-state cache stays empty.
   */
  private fun storeThatNeverLoadsTheWholeStore(
      vararg confirmed: LedgerCommand,
      block: suspend LedgerRepository.() -> Unit,
  ) {
    runBlocking {
      withTimeout(20_000) {
        val seedStore = LedgerStore(db)
        val page =
            Events.page(confirmed.mapIndexed { i, command -> Events.accepted(command, i + 1L) })
        seedStore.persist(LedgerState.EMPTY, LedgerState.EMPTY.apply(page), page)
        val reads = setOf("all", "archived")
        val store =
            LedgerStore(
                db,
                contacts = throwingOn(reads, ContactDao::class.java, db.contactDao()),
                debts = throwingOn(reads, DebtDao::class.java, db.debtDao()),
                payments = throwingOn(reads, PaymentDao::class.java, db.paymentDao()),
                offsets = throwingOn(reads, OffsetDao::class.java, db.offsetDao()),
            )
        val repo = LedgerRepository(store, outbox, session, countedScope)
        withContext(Dispatchers.IO) { repo.block() }
      }
    }
  }

  @Test
  fun updateContactReadsTheOnePersonViewNotTheWholeStore() =
      storeThatNeverLoadsTheWholeStore(contactCommand(minute = 0)) {
        assertTrue(updateContact(CONTACT_ID, "جديد", null, null, null).isSuccess)
        assertEquals(1L, outbox.snapshot().single().command.expectedVersion)
      }

  /**
   * The refusal names the balance from the person's own rows: an `Unknown` would mean the write
   * could not see them, and today's code can't answer at all (it waits for `load()`).
   */
  @Test
  fun archiveContactRefusalComesFromTheOnePersonView() =
      storeThatNeverLoadsTheWholeStore(
          contactCommand(minute = 0),
          debtCommand(amount = 10_000, id = DEBT_ID, minute = 1),
      ) {
        val archived = archiveContact(CONTACT_ID, "سبب")
        assertTrue(archived.exceptionOrNull() is Refusal.Invalid)
        assertEquals(
            "سدّد الدين المتبقي على هذا الشخص أولاً.",
            checkNotNull(archived.exceptionOrNull()).message,
        )
        assertTrue(outbox.snapshot().isEmpty())
      }

  @Test
  fun restoreContactReadsTheOnePersonViewNotTheWholeStore() =
      storeThatNeverLoadsTheWholeStore(
          contactCommand(name = "م", id = thirdId(), minute = 0),
          Commands.contactArchive(
              Events.origin,
              Events.at(1),
              thirdId(),
              expectedVersion = 1,
              reason = "ترحيل",
              entityId = thirdId(),
              operationId = UUID.randomUUID().toString(),
          ),
      ) {
        assertTrue(restoreContact(thirdId(), "استعادة").isSuccess)
        assertEquals(
            listOf(CommandType.CONTACT_RESTORE),
            outbox.snapshot().map { it.type },
        )
      }

  private fun thirdId(): String = UUID.nameUUIDFromBytes("third".toByteArray()).toString()

  @Test
  fun deleteContactReadsTheOnePersonViewNotTheWholeStore() =
      storeThatNeverLoadsTheWholeStore(
          contactCommand(name = "م", id = thirdId(), minute = 0),
          Commands.contactArchive(
              Events.origin,
              Events.at(1),
              thirdId(),
              expectedVersion = 1,
              reason = "ترحيل",
              entityId = thirdId(),
              operationId = UUID.randomUUID().toString(),
          ),
      ) {
        assertTrue(deleteContact(thirdId(), "حذف نهائي من سلة المحذوفات").isSuccess)
        assertEquals(
            listOf(CommandType.CONTACT_DELETE),
            outbox.snapshot().map { it.type },
        )
      }

  /** Waits (real time, on Default) until the summary table satisfies [predicate]. */
  private suspend fun awaitSummary(predicate: suspend (List<ContactSummaryEntity>) -> Boolean) {
    withContext(Dispatchers.Default) {
      withTimeout(5_000) {
        while (!predicate(db.contactSummaryDao().all())) delay(20)
      }
    }
  }

  /** Every stored row equals `summaryOf(wholeStoreView, id)` for the current view. */
  private suspend fun assertRowsEqualView(repo: LedgerRepository) {
    val view = repo.view.first { it != null }!!
    val rows = db.contactSummaryDao().all()
    assertEquals(view.contacts.map { it.id }.toSet(), rows.map { it.contactId }.toSet())
    for (row in rows) {
      assertEquals("row ${row.contactId}", summaryOf(view, row.contactId), row)
    }
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
