package com.sadad.app.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sadad.app.data.ledger.summaryOf
import com.sadad.ledger.Cents
import com.sadad.ledger.Commands
import com.sadad.ledger.ContactKind
import com.sadad.ledger.DebtOrigin
import com.sadad.ledger.Direction
import com.sadad.ledger.LedgerState
import com.sadad.ledger.PaymentMethod
import com.sadad.ledger.ReceiptStatus
import com.sadad.ledger.view
import com.sadad.ledger.workingSetIds
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// The LedgerStore contract: a page in, the same rows out, and one transaction per page.
@RunWith(RobolectricTestRunner::class)
class LedgerStoreTest {
  private lateinit var db: LedgerDatabase

  @Before
  fun openDatabase() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    db = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()
  }

  @After fun closeDatabase() = db.close()

  @Test
  fun roundTripsAPageThroughTheDatabase() = runTest {
    val store = LedgerStore(db)
    val page =
        Events.page(
            listOf(
                Events.accepted(
                    Commands.contactCreate(
                        Events.origin,
                        Events.at(0),
                        name = "أبو خالد",
                        phone = "0599123456",
                        category = "family",
                        note = "قريب",
                        entityId = CONTACT_ID,
                    ),
                    1,
                ),
                Events.accepted(
                    Commands.debtCreate(
                        Events.origin,
                        Events.at(1),
                        contactId = CONTACT_ID,
                        direction = Direction.RECEIVABLE,
                        amount = Cents(15000),
                        note = "بضاعة",
                        entityId = DEBT_ID,
                    ),
                    2,
                ),
                Events.accepted(
                    Commands.paymentCreate(
                        Events.origin,
                        Events.at(2),
                        debtId = DEBT_ID,
                        amount = Cents(5000),
                        method = PaymentMethod.CASH,
                        entityId = PAYMENT_ID,
                    ),
                    3,
                ),
            ),
            cursor = 9,
        )
    val state = LedgerState.EMPTY.apply(page)

    store.persist(LedgerState.EMPTY, state, page)

    assertEquals(state, store.load())
    // Each new row takes the sequence of the page event whose entityId is its id.
    assertEquals(1L, db.contactDao().all().single().createdSeq)
    assertEquals(2L, db.debtDao().all().single().createdSeq)
    assertEquals(3L, db.paymentDao().all().single().createdSeq)
  }

  @Test
  fun roundTripsACreditDebtAndAnOffset() = runTest {
    val store = LedgerStore(db)
    val page =
        Events.page(
            listOf(
                Events.accepted(contact("أ", CONTACT_ID), 1),
                Events.accepted(
                    Commands.debtCreate(
                        Events.origin,
                        Events.at(1),
                        contactId = CONTACT_ID,
                        direction = Direction.RECEIVABLE,
                        amount = Cents(10000),
                        entityId = DEBT_ID,
                    ),
                    2,
                ),
                // The excess of a payment: a payable Debt that marks where it came from.
                Events.accepted(
                    Commands.debtCreate(
                        Events.origin,
                        Events.at(2),
                        contactId = CONTACT_ID,
                        direction = Direction.PAYABLE,
                        amount = Cents(25000),
                        note = "رصيد من دفعة زائدة",
                        debtOrigin = DebtOrigin.OVERPAYMENT,
                        entityId = CREDIT_ID,
                    ),
                    3,
                ),
                Events.accepted(
                    Commands.debtOffset(
                        Events.origin,
                        Events.at(3),
                        receivableDebtId = DEBT_ID,
                        payableDebtId = CREDIT_ID,
                        amount = Cents(10000),
                        entityId = OFFSET_ID,
                    ),
                    4,
                ),
            ),
            cursor = 9,
        )
    val state = LedgerState.EMPTY.apply(page)

    store.persist(LedgerState.EMPTY, state, page)

    assertEquals("the offset and the credit must survive a round trip", state, store.load())
    assertEquals(
        DebtOrigin.OVERPAYMENT,
        db.debtDao().all().first { it.id == CREDIT_ID }.origin,
    )
    assertEquals(DebtOrigin.MANUAL, db.debtDao().all().first { it.id == DEBT_ID }.origin)
    assertEquals(4L, db.offsetDao().all().single().createdSeq)
  }

  @Test
  fun theOnePersonReadIsNullWhileThereIsNoLedgerMeta() = runTest {
    assertNull(LedgerStore(db).loadPerson(contactId(1)))
  }

  @Test
  fun theOnePersonReadReturnsExactlyThatPersonsRowsInCreatedSequenceOrder() = runTest {
    val store = LedgerStore(db)
    val a = contactId(1)
    val b = contactId(2)
    val d = contactId(3)
    val debtA1 = contactId(11)
    val debtA2 = contactId(12)
    val debtB = contactId(13)
    val debtD1 = contactId(14)
    val debtD2 = contactId(15)
    val paymentA = contactId(21)
    val paymentB = contactId(22)
    val offsetA = contactId(31)
    val offsetD = contactId(32)
    fun debt(
        contactId: String,
        direction: Direction,
        amount: Long,
        id: String,
        sequence: Long,
    ) =
        Events.accepted(
            Commands.debtCreate(
                Events.origin,
                Events.at(0),
                contactId = contactId,
                direction = direction,
                amount = Cents(amount),
                entityId = id,
            ),
            sequence,
        )
    val page =
        Events.page(
            listOf(
                Events.accepted(contact("أ", a), 1),
                Events.accepted(contact("ب", b), 2),
                Events.accepted(contact("د", d), 3),
                debt(a, Direction.RECEIVABLE, 10_000, debtA1, 4),
                debt(a, Direction.PAYABLE, 3_000, debtA2, 5),
                debt(b, Direction.RECEIVABLE, 4_000, debtB, 6),
                debt(d, Direction.RECEIVABLE, 8_000, debtD1, 7),
                debt(d, Direction.PAYABLE, 8_000, debtD2, 8),
                Events.accepted(
                    Commands.paymentCreate(
                        Events.origin,
                        Events.at(9),
                        debtId = debtA1,
                        amount = Cents(2_000),
                        method = PaymentMethod.CASH,
                        entityId = paymentA,
                    ),
                    9,
                ),
                Events.accepted(
                    Commands.paymentCreate(
                        Events.origin,
                        Events.at(9),
                        debtId = debtB,
                        amount = Cents(1_000),
                        method = PaymentMethod.CASH,
                        entityId = paymentB,
                    ),
                    10,
                ),
                Events.accepted(
                    Commands.debtOffset(
                        Events.origin,
                        Events.at(10),
                        receivableDebtId = debtA1,
                        payableDebtId = debtA2,
                        amount = Cents(1_000),
                        entityId = offsetA,
                    ),
                    11,
                ),
                Events.accepted(
                    Commands.debtOffset(
                        Events.origin,
                        Events.at(10),
                        receivableDebtId = debtD1,
                        payableDebtId = debtD2,
                        amount = Cents(2_000),
                        entityId = offsetD,
                    ),
                    12,
                ),
            )
        )
    val state = LedgerState.EMPTY.apply(page)
    store.persist(LedgerState.EMPTY, state, page)

    val person = store.loadPerson(a)!!

    assertEquals("a has the store's meta, not the whole store", Events.STORE_ID, person.storeId)
    assertEquals(Events.EPOCH, person.epoch)
    assertEquals(12L, person.cursor)
    assertEquals(listOf(a), person.contacts.keys.toList())
    assertEquals(
        "their debts in createdSeq order",
        listOf(debtA1, debtA2),
        person.debts.keys.toList(),
    )
    assertEquals(listOf(paymentA), person.payments.keys.toList())
    assertEquals(listOf(offsetA), person.offsets.keys.toList())
    // Nothing of the other two people falls in.
    assertTrue(person.contacts.keys.none { it == b || it == d })
    assertTrue(person.debts.keys.none { it == debtB || it == debtD1 || it == debtD2 })
    assertTrue(person.payments.keys.none { it == paymentB })

    val other = store.loadPerson(b)!!
    assertEquals(listOf(debtB), other.debts.keys.toList())
    assertEquals(listOf(paymentB), other.payments.keys.toList())
    assertTrue(other.offsets.isEmpty())

    val third = store.loadPerson(d)!!
    assertEquals(listOf(debtD1, debtD2), third.debts.keys.toList())
    assertTrue(third.payments.isEmpty())
    assertEquals(listOf(offsetD), third.offsets.keys.toList())
  }

  @Test
  fun thePeriodReadReturnsExactlyTheRowsTheRuleNames() = runTest {
    val store = LedgerStore(db)
    val a = contactId(1)
    val b = contactId(2)
    val c = contactId(3)
    val quiet = contactId(4)
    val queuedFor = contactId(5)
    val inPeriod = contactId(11)
    val paidInPeriod = contactId(12)
    val named = contactId(13)
    val untouched = contactId(14)
    val offsetSide = contactId(15)
    val oldPayment = contactId(21)
    val periodPayment = contactId(22)
    val offsetId = contactId(31)
    fun debt(contactId: String, id: String, minute: Int, sequence: Long, direction: Direction) =
        Events.accepted(
            Commands.debtCreate(
                Events.origin,
                Events.at(minute),
                contactId = contactId,
                direction = direction,
                amount = Cents(1_000),
                entityId = id,
            ),
            sequence,
        )
    fun payment(debtId: String, id: String, minute: Int, sequence: Long) =
        Events.accepted(
            Commands.paymentCreate(
                Events.origin,
                Events.at(minute),
                debtId = debtId,
                amount = Cents(100),
                method = PaymentMethod.CASH,
                entityId = id,
            ),
            sequence,
        )
    val page =
        Events.page(
            listOf(
                Events.accepted(contact("أ", a), 1),
                Events.accepted(contact("ب", b), 2),
                Events.accepted(contact("ج", c), 3),
                Events.accepted(contact("هادئ", quiet), 4),
                Events.accepted(contact("مؤجل", queuedFor), 5),
                debt(b, paidInPeriod, 1, 6, Direction.RECEIVABLE),
                debt(c, named, 2, 7, Direction.RECEIVABLE),
                debt(quiet, untouched, 3, 8, Direction.RECEIVABLE),
                debt(a, offsetSide, 4, 9, Direction.PAYABLE),
                payment(paidInPeriod, oldPayment, 5, 10),
                debt(a, inPeriod, 10, 11, Direction.RECEIVABLE),
                Events.accepted(
                    Commands.debtOffset(
                        Events.origin,
                        Events.at(30),
                        receivableDebtId = inPeriod,
                        payableDebtId = offsetSide,
                        amount = Cents(100),
                        entityId = offsetId,
                    ),
                    12,
                ),
                payment(paidInPeriod, periodPayment, 11, 13),
            )
        )
    val state = LedgerState.EMPTY.apply(page)
    store.persist(LedgerState.EMPTY, state, page)

    val period = store.loadPeriod(Events.at(10), Events.at(12), setOf(named), setOf(queuedFor))

    assertEquals(Events.STORE_ID, period.storeId)
    assertEquals(Events.EPOCH, period.epoch)
    assertEquals(13L, period.cursor)
    assertEquals(listOf(paidInPeriod, named, inPeriod), period.debts.keys.toList())
    assertEquals(
        "every payment on those debts, not only the period's",
        listOf(oldPayment, periodPayment),
        period.payments.keys.toList(),
    )
    assertEquals(listOf(offsetId), period.offsets.keys.toList())
    assertEquals(listOf(a, b, c, queuedFor), period.contacts.keys.toList())

    val end = store.loadPeriod(Events.at(9), Events.at(10), emptySet(), emptySet())
    assertTrue("the end is exclusive", end.debts.isEmpty() && end.contacts.isEmpty())
    assertEquals(
        "unbounded holds every debt",
        state.debts.keys,
        store.loadPeriod(null, null, emptySet(), emptySet()).debts.keys,
    )
  }

  @Test
  fun thePeriodAndArchivedReadsAreEmptyWhileThereIsNoLedgerMeta() = runTest {
    val store = LedgerStore(db)
    assertEquals(LedgerState.EMPTY, store.loadPeriod(null, null, emptySet(), emptySet()))
    assertEquals(LedgerState.EMPTY, store.loadArchived())
  }

  @Test
  fun theArchivedReadHoldsOnlyArchivedContacts() = runTest {
    val store = LedgerStore(db)
    val kept = contactId(1)
    val first = contactId(2)
    val second = contactId(3)
    fun archive(id: String, sequence: Long) =
        Events.accepted(
            Commands.contactArchive(
                Events.origin,
                Events.at(1),
                id,
                expectedVersion = 1,
                reason = "حذف",
                entityId = id,
            ),
            sequence,
        )
    val page =
        Events.page(
            listOf(
                Events.accepted(contact("أ", kept), 1),
                Events.accepted(contact("ب", first), 2),
                Events.accepted(contact("ج", second), 3),
                archive(second, 4),
                archive(first, 5),
            )
        )
    val state = LedgerState.EMPTY.apply(page)
    store.persist(LedgerState.EMPTY, state, page)

    val archived = store.loadArchived()

    assertEquals(listOf(first, second), archived.contacts.keys.toList())
    assertTrue(archived.debts.isEmpty())
    assertEquals(5L, archived.cursor)
  }

  @Test
  fun loadsRowsInCreatedSequenceOrderAndKeepsCreatedSeqOnUpdate() = runTest {
    val store = LedgerStore(db)
    // Rows created in sequences 3, 1, 2, so the table's own order cannot be the answer.
    db.metaDao().put(LedgerMetaEntity(storeId = Events.STORE_ID, epoch = Events.EPOCH, cursor = 3))
    for ((id, name, seq) in
        listOf(Triple(A_ID, "أ", 3L), Triple(Z_ID, "ز", 1L), Triple(M_ID, "م", 2L))) {
      db.contactDao().upsert(contactEntity(id, name, seq))
    }

    assertEquals(listOf(Z_ID, M_ID, A_ID), store.load().contacts.keys.toList())

    // A later page updates the Contact created in sequence 1: its createdSeq and place stay.
    val before = store.load()
    val update =
        Events.page(
            listOf(
                Events.accepted(
                    Commands.contactUpdate(
                        Events.origin,
                        Events.at(3),
                        contactId = Z_ID,
                        expectedVersion = 1,
                        name = "ز",
                    ),
                    4,
                )
            )
        )
    store.persist(before, before.apply(update), update)

    assertEquals(listOf(Z_ID, M_ID, A_ID), store.load().contacts.keys.toList())
    assertEquals(1L, db.contactDao().all().first { it.id == Z_ID }.createdSeq)
  }

  @Test
  fun writesOnlyTheRowsThePageTouched() = runTest {
    val counting = CountingContactDao(db.contactDao())
    val store = LedgerStore(db, contacts = counting)
    val ids = (1..10).map { contactId(it) }
    val first =
        Events.page(
            ids.mapIndexed { index, id ->
              Events.accepted(contact("contact ${index + 1}", id), index + 1L)
            }
        )
    val afterFirst = LedgerState.EMPTY.apply(first)
    store.persist(LedgerState.EMPTY, afterFirst, first)
    assertEquals(10, counting.upserts)

    val second =
        Events.page(
            listOf(
                Events.accepted(
                    Commands.contactUpdate(
                        Events.origin,
                        Events.at(1),
                        contactId = ids[0],
                        expectedVersion = 1,
                        name = "renamed",
                    ),
                    11,
                )
            )
        )
    store.persist(afterFirst, afterFirst.apply(second), second)

    assertEquals(11, counting.upserts)
  }

  @Test
  fun aFailedEventInsertLeavesEverythingAsItWas() = runTest {
    val store = LedgerStore(db)
    val first = Events.page(listOf(Events.accepted(contact("أ", Z_ID), 1)))
    val afterFirst = LedgerState.EMPTY.apply(first)
    store.persist(LedgerState.EMPTY, afterFirst, first)
    // A row another epoch left behind: the next persist cannot insert its own sequence 3.
    db.eventDao()
        .insert(
            listOf(
                EventEntity(
                    sequence = 3,
                    operationId = "0d1e2f3a-4b5c-4d6e-8f7a-8b9c0d1e2f3a",
                    kind = "contact.create",
                    entityId = M_ID,
                    installationId = Events.INSTALLATION_ID,
                    createdAt = 0,
                    status = ReceiptStatus.ACCEPTED,
                    code = null,
                    command = "{}",
                )
            )
        )
    val second =
        Events.page(
            listOf(
                Events.accepted(contact("م", M_ID), 2),
                Events.accepted(contact("ج", A_ID), 3),
            )
        )
    val afterSecond = afterFirst.apply(second)

    assertTrue(runCatching { store.persist(afterFirst, afterSecond, second) }.isFailure)

    assertEquals(listOf("أ"), db.contactDao().all().map { it.name })
    assertEquals(listOf(1L, 3L), db.eventDao().all().map { it.sequence })
    assertEquals(1L, store.load().cursor)
  }

  @Test
  fun keepsOnlyTheNewestFiveHundredEvents() = runTest {
    val store = LedgerStore(db)
    var state = LedgerState.EMPTY
    repeat(7) { page ->
      val first = page * 100L + 1
      val events =
          (first until first + 100).map { sequence ->
            Events.accepted(contact("contact $sequence", contactId(sequence.toInt())), sequence)
          }
      val changes = Events.page(events)
      val next = state.apply(changes)
      store.persist(state, next, changes)
      state = next
    }

    val kept = db.eventDao().all()
    assertEquals(500, kept.size)
    assertEquals(201L, kept.first().sequence)
    assertEquals(700L, kept.last().sequence)
  }

  @Test
  fun resetEmptiesTheLedgerAndKeepsTheOutbox() = runTest {
    val store = LedgerStore(db)
    val page =
        Events.page(
            listOf(
                Events.accepted(contact("أ", CONTACT_ID), 1),
                Events.accepted(
                    Commands.debtCreate(
                        Events.origin,
                        Events.at(1),
                        contactId = CONTACT_ID,
                        direction = Direction.PAYABLE,
                        amount = Cents(100),
                        entityId = DEBT_ID,
                    ),
                    2,
                ),
                Events.accepted(
                    Commands.paymentCreate(
                        Events.origin,
                        Events.at(2),
                        debtId = DEBT_ID,
                        amount = Cents(50),
                        method = PaymentMethod.WALLET_PALPAY,
                        entityId = PAYMENT_ID,
                    ),
                    3,
                ),
            )
        )
    store.persist(LedgerState.EMPTY, LedgerState.EMPTY.apply(page), page)
    db.outboxDao()
        .insert(
            OutboxEntity(
                operationId = "6f1c2b3a-0d4e-4f5a-8b6c-7d8e9f0a1b2c",
                entityId = CONTACT_ID,
                type = "contact.create",
                state = "pending",
                code = null,
                createdAt = 0,
                recoveryPermit = null,
                payload = byteArrayOf(1, 2, 3),
            )
        )

    store.reset()

    assertTrue(db.contactDao().all().isEmpty())
    assertTrue(db.debtDao().all().isEmpty())
    assertTrue(db.paymentDao().all().isEmpty())
    assertTrue(db.eventDao().all().isEmpty())
    assertNull(db.metaDao().get())
    assertEquals(1, db.outboxDao().all().size)
    assertEquals(LedgerState.EMPTY, store.load())
  }

  @Test
  fun resetAlsoClearsSummaries() = runTest {
    val store = LedgerStore(db)
    db.contactSummaryDao()
        .upsert(
            listOf(
                ContactSummaryEntity(
                    contactId = CONTACT_ID,
                    name = "أ",
                    phone = "",
                    kind = ContactKind.CUSTOMER,
                    category = "",
                    pending = false,
                    createdAt = Events.at(0),
                    net = 100,
                    openReceivable = 100,
                    openPayable = 0,
                    earliestOpenDue = null,
                    lastEntryAt = Events.at(0),
                    searchKey = "a",
                )
            )
        )
    assertEquals(1, db.contactSummaryDao().all().size)

    store.reset()

    assertTrue(db.contactSummaryDao().all().isEmpty())
  }

  @Test
  fun loadWorkingSetForOnePaymentReadsOnlyItsRows() = runTest {
    val seedStore = LedgerStore(db)
    val a = contactId(1)
    val b = contactId(2)
    val dA = contactId(11)
    val dB = contactId(13)
    val pA = contactId(21)
    val pB = contactId(22)
    val seed =
        Events.page(
            listOf(
                Events.accepted(contact("A", a), 1),
                Events.accepted(contact("B", b), 2),
                Events.accepted(
                    Commands.debtCreate(
                        Events.origin,
                        Events.at(1),
                        contactId = a,
                        Direction.RECEIVABLE,
                        Cents(10_000),
                        entityId = dA,
                    ),
                    3,
                ),
                Events.accepted(
                    Commands.debtCreate(
                        Events.origin,
                        Events.at(2),
                        contactId = b,
                        Direction.RECEIVABLE,
                        Cents(4_000),
                        entityId = dB,
                    ),
                    4,
                ),
                Events.accepted(
                    Commands.paymentCreate(
                        Events.origin,
                        Events.at(3),
                        debtId = dA,
                        Cents(2_000),
                        PaymentMethod.CASH,
                        entityId = pA,
                    ),
                    5,
                ),
                Events.accepted(
                    Commands.paymentCreate(
                        Events.origin,
                        Events.at(4),
                        debtId = dB,
                        Cents(1_000),
                        PaymentMethod.CASH,
                        entityId = pB,
                    ),
                    6,
                ),
            )
        )
    seedStore.persist(LedgerState.EMPTY, LedgerState.EMPTY.apply(seed), seed)

    val pNew = contactId(31)
    val payPage =
        Events.page(
            listOf(
                Events.accepted(
                    Commands.paymentCreate(
                        Events.origin,
                        Events.at(5),
                        debtId = dA,
                        Cents(500),
                        PaymentMethod.CASH,
                        entityId = pNew,
                    ),
                    7,
                )
            )
        )
    val countingContacts = CountingContactDao(db.contactDao())
    val countingDebts = CountingDebtDao(db.debtDao())
    val countingPayments = CountingPaymentDao(db.paymentDao())
    val countingOffsets = CountingOffsetDao(db.offsetDao())
    val store =
        LedgerStore(
            db,
            contacts = countingContacts,
            debts = countingDebts,
            payments = countingPayments,
            offsets = countingOffsets,
        )

    val ws = store.loadWorkingSet(payPage.workingSetIds())
    assertEquals(setOf(a), ws.contacts.keys)
    assertEquals(setOf(dA), ws.debts.keys)
    assertTrue(ws.payments.isEmpty())
    assertTrue(ws.offsets.isEmpty())
    val allCalls =
        countingContacts.allCalls +
            countingDebts.allCalls +
            countingPayments.allCalls +
            countingOffsets.allCalls
    assertEquals(0, allCalls)
    assertEquals(1, countingContacts.byIdsCalls)
    assertEquals(1, countingDebts.byIdsCalls)
    assertEquals(1, countingPayments.byIdsCalls)
    assertEquals(0, countingOffsets.byIdsCalls)

    val next = ws.apply(payPage)
    store.persist(ws, next, payPage)
    assertEquals(3, db.paymentDao().all().size)
    assertEquals(1, countingPayments.upserts)
    assertEquals(0, countingContacts.upserts)
    assertEquals(0, countingDebts.upserts)
  }

  @Test
  fun workingSetSummaryIsWrittenFromRoomAfterPersist() = runTest {
    val store = LedgerStore(db)
    val a = contactId(1)
    val dA = contactId(11)
    val seed =
        Events.page(
            listOf(
                Events.accepted(contact("A", a), 1),
                Events.accepted(
                    Commands.debtCreate(
                        Events.origin,
                        Events.at(1),
                        contactId = a,
                        Direction.RECEIVABLE,
                        Cents(10_000),
                        entityId = dA,
                    ),
                    2,
                ),
            )
        )
    store.persist(LedgerState.EMPTY, LedgerState.EMPTY.apply(seed), seed)
    val payPage =
        Events.page(
            listOf(
                Events.accepted(
                    Commands.paymentCreate(
                        Events.origin,
                        Events.at(2),
                        debtId = dA,
                        Cents(1_000),
                        PaymentMethod.CASH,
                        entityId = contactId(31),
                    ),
                    3,
                )
            )
        )
    val ws = store.loadWorkingSet(payPage.workingSetIds())
    val next = ws.apply(payPage)
    store.persistWithSummariesFromRoom(ws, next, payPage, setOf(a)) { contactId ->
      val person = store.loadPerson(contactId) ?: return@persistWithSummariesFromRoom null
      summaryOf(person.view(emptyList(), emptySet(), Events.INSTALLATION_ID, ""), contactId)
    }
    val rows = db.contactSummaryDao().all()
    assertEquals(1, rows.size)
    assertEquals(a, rows.single().contactId)
    assertEquals(Cents(9_000).value, rows.single().net)
  }

  @Test
  fun contactDeleteWorkingSetRemovesAllItsRows() = runTest {
    val store = LedgerStore(db)
    val a = contactId(1)
    val b = contactId(2)
    val dA1 = contactId(11)
    val dA2 = contactId(12)
    val dB = contactId(13)
    val pA = contactId(21)
    val pB = contactId(22)
    val oA = contactId(31)
    fun archive(id: String, sequence: Long, minute: Int) =
        Events.accepted(
            Commands.contactArchive(
                Events.origin,
                Events.at(minute),
                id,
                expectedVersion = 1,
                reason = "سلة",
                entityId = id,
            ),
            sequence,
        )
    val seed =
        Events.page(
            listOf(
                Events.accepted(contact("A", a), 1),
                Events.accepted(contact("B", b), 2),
                Events.accepted(
                    Commands.debtCreate(
                        Events.origin,
                        Events.at(1),
                        contactId = a,
                        Direction.RECEIVABLE,
                        Cents(10_000),
                        entityId = dA1,
                    ),
                    3,
                ),
                Events.accepted(
                    Commands.debtCreate(
                        Events.origin,
                        Events.at(2),
                        contactId = a,
                        Direction.PAYABLE,
                        Cents(3_000),
                        entityId = dA2,
                    ),
                    4,
                ),
                Events.accepted(
                    Commands.debtCreate(
                        Events.origin,
                        Events.at(3),
                        contactId = b,
                        Direction.RECEIVABLE,
                        Cents(4_000),
                        entityId = dB,
                    ),
                    5,
                ),
                Events.accepted(
                    Commands.debtOffset(
                        Events.origin,
                        Events.at(4),
                        receivableDebtId = dA1,
                        payableDebtId = dA2,
                        Cents(1_000),
                        entityId = oA,
                    ),
                    6,
                ),
                Events.accepted(
                    Commands.paymentCreate(
                        Events.origin,
                        Events.at(5),
                        debtId = dA1,
                        Cents(1_000),
                        PaymentMethod.CASH,
                        entityId = pA,
                    ),
                    7,
                ),
                Events.accepted(
                    Commands.paymentCreate(
                        Events.origin,
                        Events.at(6),
                        debtId = dB,
                        Cents(500),
                        PaymentMethod.CASH,
                        entityId = pB,
                    ),
                    8,
                ),
                archive(a, 9, 7),
            )
        )
    store.persist(LedgerState.EMPTY, LedgerState.EMPTY.apply(seed), seed)

    val deletePage =
        Events.page(
            listOf(
                Events.accepted(
                    Commands.contactDelete(
                        Events.origin,
                        Events.at(8),
                        a,
                        expectedVersion = 2,
                        reason = "نهائي",
                        entityId = a,
                    ),
                    10,
                )
            )
        )
    val ws = store.loadWorkingSet(deletePage.workingSetIds())
    // The delete's working set holds A's contact with all its debts, payments and offsets.
    assertEquals(setOf(a), ws.contacts.keys)
    assertEquals(setOf(dA1, dA2), ws.debts.keys)
    assertEquals(setOf(pA), ws.payments.keys)
    assertEquals(setOf(oA), ws.offsets.keys)

    val next = ws.apply(deletePage)
    assertTrue(next.contacts.isEmpty())
    assertTrue(next.debts.isEmpty())
    assertTrue(next.payments.isEmpty())
    assertTrue(next.offsets.isEmpty())
    store.persist(ws, next, deletePage)

    assertEquals(setOf(b), db.contactDao().all().map { it.id }.toSet())
    assertEquals(setOf(dB), db.debtDao().all().map { it.id }.toSet())
    assertEquals(setOf(pB), db.paymentDao().all().map { it.id }.toSet())
    assertTrue(db.offsetDao().all().isEmpty())
  }

  @Test
  fun aWorkingSetPageThatFailsLeavesEveryTableAsItWas() = runTest {
    val store = LedgerStore(db)
    val a = contactId(1)
    val seed = Events.page(listOf(Events.accepted(contact("A", a), 1)))
    store.persist(LedgerState.EMPTY, LedgerState.EMPTY.apply(seed), seed)
    val beforeContacts = db.contactDao().all()
    val beforeMeta = db.metaDao().get()
    val beforeEvents = db.eventDao().all()

    val unknownDebt = "d0000000-0000-4000-8000-0000000000de"
    val bad =
        Events.page(
            listOf(
                Events.accepted(
                    Commands.paymentCreate(
                        Events.origin,
                        Events.at(1),
                        debtId = unknownDebt,
                        Cents(100),
                        PaymentMethod.CASH,
                        entityId = contactId(99),
                    ),
                    2,
                )
            )
        )
    val ws = store.loadWorkingSet(bad.workingSetIds())
    var threw = false
    try {
      ws.apply(bad)
    } catch (e: IllegalStateException) {
      threw = true
    }
    assertTrue(threw)
    assertEquals(beforeContacts, db.contactDao().all())
    assertEquals(beforeMeta, db.metaDao().get())
    assertEquals(beforeEvents, db.eventDao().all())
  }

  private fun contact(name: String, id: String) =
      Commands.contactCreate(Events.origin, Events.at(0), name = name, entityId = id)

  private fun contactEntity(id: String, name: String, createdSeq: Long) =
      ContactEntity(
          id = id,
          name = name,
          phone = "",
          kind = ContactKind.CUSTOMER,
          category = "",
          note = "",
          archived = false,
          version = 1,
          operationId = id,
          createdAt = Events.at(0),
          originInstallationId = Events.INSTALLATION_ID,
          createdSeq = createdSeq,
      )

  /** A counting wrapper, to see how many rows one page writes. */
  private class CountingContactDao(private val real: ContactDao) : ContactDao by real {
    var upserts = 0
    var allCalls = 0
    var byIdsCalls = 0

    override suspend fun upsert(row: ContactEntity) {
      upserts++
      real.upsert(row)
    }

    override suspend fun all(): List<ContactEntity> {
      allCalls++
      return real.all()
    }

    override suspend fun byIds(ids: List<String>): List<ContactEntity> {
      byIdsCalls++
      return real.byIds(ids)
    }
  }

  private class CountingDebtDao(private val real: DebtDao) : DebtDao by real {
    var upserts = 0
    var allCalls = 0
    var byIdsCalls = 0

    override suspend fun upsert(row: DebtEntity) {
      upserts++
      real.upsert(row)
    }

    override suspend fun all(): List<DebtEntity> {
      allCalls++
      return real.all()
    }

    override suspend fun byIds(ids: List<String>): List<DebtEntity> {
      byIdsCalls++
      return real.byIds(ids)
    }
  }

  private class CountingPaymentDao(private val real: PaymentDao) : PaymentDao by real {
    var upserts = 0
    var allCalls = 0
    var byIdsCalls = 0

    override suspend fun upsert(row: PaymentEntity) {
      upserts++
      real.upsert(row)
    }

    override suspend fun all(): List<PaymentEntity> {
      allCalls++
      return real.all()
    }

    override suspend fun byIds(ids: List<String>): List<PaymentEntity> {
      byIdsCalls++
      return real.byIds(ids)
    }
  }

  private class CountingOffsetDao(private val real: OffsetDao) : OffsetDao by real {
    var upserts = 0
    var allCalls = 0
    var byIdsCalls = 0

    override suspend fun upsert(row: OffsetEntity) {
      upserts++
      real.upsert(row)
    }

    override suspend fun all(): List<OffsetEntity> {
      allCalls++
      return real.all()
    }

    override suspend fun byIds(ids: List<String>): List<OffsetEntity> {
      byIdsCalls++
      return real.byIds(ids)
    }
  }

  private companion object {
    const val CONTACT_ID = "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d"
    const val DEBT_ID = "b2c3d4e5-f6a7-4b8c-9d0e-1f2a3b4c5d6e"
    const val CREDIT_ID = "d4e5f6a7-b8c9-4d0e-9f1a-2b3c4d5e6f70"
    const val OFFSET_ID = "e5f6a7b8-c9d0-4e1f-8a2b-3c4d5e6f7081"
    const val PAYMENT_ID = "c3d4e5f6-a7b8-4c9d-8e1f-2a3b4c5d6e7f"
    const val Z_ID = "ffffffff-1111-4111-8111-111111111111"
    const val M_ID = "aaaaaaaa-2222-4222-8222-222222222222"
    const val A_ID = "11111111-3333-4333-8333-333333333333"

    fun contactId(index: Int): String = "00000000-0000-4000-8000-%012d".format(index)
  }
}
