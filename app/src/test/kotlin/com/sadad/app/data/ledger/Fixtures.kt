package com.sadad.app.data.ledger

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.ReceiveTurbine
import com.sadad.app.data.db.Events
import com.sadad.app.data.db.LedgerDatabase
import com.sadad.app.data.db.LedgerStore
import com.sadad.ledger.Cents
import com.sadad.ledger.Commands
import com.sadad.ledger.DebtCreate
import com.sadad.ledger.Direction
import com.sadad.ledger.LedgerCommand
import com.sadad.ledger.LedgerView
import com.sadad.ledger.Origin
import com.sadad.ledger.PaymentCreate
import com.sadad.ledger.PaymentMethod
import com.sadad.ledger.Receipt
import com.sadad.ledger.ReceiptStatus
import java.security.SecureRandom
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before

const val CONTACT_ID = "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d"
const val DEBT_ID = "b2c3d4e5-f6a7-4b8c-9d0e-1f2a3b4c5d6e"
const val OTHER_DEBT_ID = "c3d4e5f6-a7b8-4c9d-8e1f-2a3b4c5d6e7f"
const val PAYMENT_ID = "d4e5f6a7-b8c9-4d0e-9f1a-2b3c4d5e6f70"
const val OTHER_CONTACT_ID = "e5f6a7b8-c9d0-4e1f-8a2b-3c4d5e6f7081"

fun contactCommand(
    name: String = "سامي",
    id: String = CONTACT_ID,
    minute: Int = 0,
    operationId: String = newOperationId(),
) =
    Commands.contactCreate(
        Events.origin,
        Events.at(minute),
        name = name,
        entityId = id,
        operationId = operationId,
    )

fun debtCommand(
    contactId: String = CONTACT_ID,
    amount: Long = 10_000,
    id: String = DEBT_ID,
    minute: Int = 1,
    direction: Direction = Direction.RECEIVABLE,
    operationId: String = newOperationId(),
) =
    Commands.debtCreate(
        Events.origin,
        Events.at(minute),
        contactId = contactId,
        direction = direction,
        amount = Cents(amount),
        entityId = id,
        operationId = operationId,
    )

fun paymentCommand(
    debtId: String = DEBT_ID,
    amount: Long = 1_000,
    id: String = PAYMENT_ID,
    minute: Int = 2,
    operationId: String = newOperationId(),
) =
    Commands.paymentCreate(
        Events.origin,
        Events.at(minute),
        debtId = debtId,
        amount = Cents(amount),
        method = PaymentMethod.CASH,
        entityId = id,
        operationId = operationId,
    )

fun newOperationId(): String = java.util.UUID.randomUUID().toString()

fun receiptOf(
    command: LedgerCommand,
    status: ReceiptStatus,
    code: String? = null,
    sequence: Long = 1,
) =
    Receipt(
        operationId = command.operationId,
        entityId = command.entityId,
        status = status,
        code = code,
        sequence = sequence,
        epoch = Events.EPOCH,
        serverTime = Events.SERVER_TIME,
    )

fun debtAmount(row: OutboxRow): Long = (row.command.payload() as DebtCreate).amountCents.value

fun paymentAmount(row: OutboxRow): Long = (row.command.payload() as PaymentCreate).amountCents.value

class FakeSession(var origin: Origin? = Events.origin) : RepositorySession {
  override val selfInstallationId: String = Events.INSTALLATION_ID
  val staffNames = MutableStateFlow("سامي")
  override val staffName: Flow<String> = staffNames
  var online = true
  var offline = true
  var queued = 0

  override fun origin(): Origin? = origin

  override fun isOnline(): Boolean = online

  override fun offlineAllowed(): Boolean = offline

  override fun onQueued() {
    queued++
  }
}

abstract class LedgerTestCase {
  protected lateinit var db: LedgerDatabase
  protected lateinit var key: SecretKey
  protected lateinit var session: FakeSession
  protected lateinit var outbox: Outbox
  private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  @Before
  fun openDatabase() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    db = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()
    key = SecretKeySpec(ByteArray(32).also { SecureRandom().nextBytes(it) }, "AES")
    session = FakeSession()
    outbox = Outbox(db.outboxDao(), key)
  }

  /**
   * Joins the repository's collectors first: a Room query still running would throw once closed.
   */
  @After
  fun closeDatabase() {
    runBlocking { repositoryScope.coroutineContext[Job]?.cancelAndJoin() }
    db.close()
  }

  /**
   * The scope is the fixture's own, not a test's `backgroundScope`: that one is cancelled but not
   * joined when `runTest` returns, so a query could outlive the test and fail after `db.close()`.
   */
  protected fun repository() = LedgerRepository(LedgerStore(db), outbox, session, repositoryScope)
}

/** Waits through the [LedgerView] flow's initial `null` and stale emissions. */
suspend fun ReceiveTurbine<LedgerView?>.awaitView(predicate: (LedgerView) -> Boolean): LedgerView {
  while (true) {
    val view = awaitItem() ?: continue
    if (predicate(view)) return view
  }
}
