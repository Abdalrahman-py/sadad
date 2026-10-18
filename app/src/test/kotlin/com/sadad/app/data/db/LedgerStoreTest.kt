package com.sadad.app.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sadad.ledger.Cents
import com.sadad.ledger.Commands
import com.sadad.ledger.Direction
import com.sadad.ledger.LedgerState
import com.sadad.ledger.PaymentMethod
import com.sadad.ledger.ReceiptStatus
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
                        phone = "+970599123456",
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

  private fun contact(name: String, id: String) =
      Commands.contactCreate(Events.origin, Events.at(0), name = name, entityId = id)

  private fun contactEntity(id: String, name: String, createdSeq: Long) =
      ContactEntity(
          id = id,
          name = name,
          phone = "",
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

    override suspend fun upsert(row: ContactEntity) {
      upserts++
      real.upsert(row)
    }
  }

  private companion object {
    const val CONTACT_ID = "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d"
    const val DEBT_ID = "b2c3d4e5-f6a7-4b8c-9d0e-1f2a3b4c5d6e"
    const val PAYMENT_ID = "c3d4e5f6-a7b8-4c9d-8e1f-2a3b4c5d6e7f"
    const val Z_ID = "ffffffff-1111-4111-8111-111111111111"
    const val M_ID = "aaaaaaaa-2222-4222-8222-222222222222"
    const val A_ID = "11111111-3333-4333-8333-333333333333"

    fun contactId(index: Int): String = "00000000-0000-4000-8000-%012d".format(index)
  }
}
