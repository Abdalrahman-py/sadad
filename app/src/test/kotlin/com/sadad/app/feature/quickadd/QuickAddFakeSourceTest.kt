package com.sadad.app.feature.quickadd

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.sadad.app.data.db.ContactSummaryEntity
import com.sadad.app.data.db.Events
import com.sadad.app.feature.auth.MainDispatcherRule
import com.sadad.app.feature.contactpicker.ContactPickerRows
import com.sadad.app.feature.contactpicker.ContactRow
import com.sadad.app.feature.contactpicker.DeviceContact
import com.sadad.app.feature.contactpicker.DeviceContacts
import com.sadad.app.ui.UiTestApplication
import com.sadad.ledger.Cents
import com.sadad.ledger.CommandType
import com.sadad.ledger.ContactKind
import com.sadad.ledger.ContactView
import com.sadad.ledger.DebtCreate
import com.sadad.ledger.DebtView
import com.sadad.ledger.Direction
import com.sadad.ledger.LedgerCommand
import com.sadad.ledger.LedgerView
import com.sadad.ledger.PaymentCreate
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The ViewModel built from [QuickAddSource] alone: no `AppGraph`, no Room database and no
 * `MockWebServer`. The store-backed tests cover the outbox and the Ledger; these cover what the
 * flow does with what the source gives it.
 */

// Commands refuse an id that is not a UUID, so the fake ledger uses real ones.
private const val C1 = "0a1b2c3d-4e5f-4a6b-8c7d-8e9f0a1b2c3d"
private const val C2 = "1b2c3d4e-5f6a-4b7c-8d8e-9f0a1b2c3d4e"
private const val C3 = "2b3c4d5e-6f7a-4b8c-9d0e-1f2a3b4c5d6e"

@RunWith(RobolectricTestRunner::class)
@Config(application = UiTestApplication::class)
class QuickAddFakeSourceTest {
  @get:Rule val main = MainDispatcherRule()
  private val context: Context = ApplicationProvider.getApplicationContext()
  private val jobs = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  @After
  fun tearDown() {
    jobs.cancel()
  }

  private class FakeSource(val jobs: CoroutineScope, view: LedgerView) : QuickAddSource {
    val appended = CompletableDeferred<List<LedgerCommand>>()
    override val view = MutableStateFlow<LedgerView?>(view)
    override val permissions = flowOf(emptyMap<String, Boolean>())
    override val blocked = flowOf(false)

    override fun open(): QuickAddStore =
        object : QuickAddStore {
          override val jobs = this@FakeSource.jobs

          override fun origin() = Events.origin

          override fun isOnline() = true

          override suspend fun append(commands: List<LedgerCommand>) {
            appended.complete(commands)
          }
        }
  }

  private fun model(source: QuickAddSource, contactId: String? = null) =
      QuickAddViewModel(source, contactId, false, SavedStateHandle(), undoWindowMs = 100)

  @Test
  fun thePickerListsTheSourcesPeopleAndTheRouteContactStartsAtTheAmount() = runBlocking {
    withTimeout(5_000) {
      val source = FakeSource(jobs, ledger(contact(C1, "خالد"), contact(C2, "سارة")))

      val picker = model(source).state.first { it.loaded }
      assertEquals(listOf("خالد", "سارة"), picker.people.map { it.name })

      val started = model(source, contactId = C2).state.first { it.selected != null }
      assertEquals(C2, started.selected?.id)
      assertEquals(QuickAddStep.AMOUNT, started.step)
    }
  }

  @Test
  fun aDebtReachesTheStoreOnlyAfterTheUndoWindow() = runBlocking {
    withTimeout(5_000) {
      val source = FakeSource(jobs, ledger(contact(C1, "خالد")))
      val model = model(source)
      model.state.first { it.loaded }

      model.pick(C1)
      model.press("8")
      model.press("5")
      assertFalse("nothing is appended while the amount is on screen", source.appended.isCompleted)

      model.save(context)
      val command = source.appended.await().single()

      assertEquals(CommandType.DEBT_CREATE, command.type)
      val payload = command.payload() as DebtCreate
      assertEquals(Direction.RECEIVABLE, payload.direction)
      assertEquals(Cents(8_500), payload.amountCents)
      assertEquals(C1, payload.contactId)
    }
  }

  @Test
  fun thePickerReadsTheSummariesWithTheStoredOpenBalanceAndNeverCollectsTheWholeView() =
      runBlocking {
        withTimeout(5_000) {
          val source =
              SummarySource(
                  jobs,
                  listOf(
                      summary(C1, "خالد", net = 15_000, openReceivable = 15_000, atHours = 2),
                      summary(C2, "سارة", net = 0, openReceivable = 0, atHours = 1),
                  ),
              )

          val model = model(source)
          val state = model.state.first { it.loaded }

          assertEquals(listOf("خالد", "سارة"), state.people.map { it.name })
          assertEquals(Cents(15_000), state.people.first().net)

          model.pick(C1)
          model.toPayment()
          model.state.first { it.overBalance == Cents(15_000) }

          assertEquals(Cents(15_000), model.state.value.overBalance)
        }
      }

  @Test
  fun aPaymentAllocatesOverThePersonsOwnViewOldestFirst() = runBlocking {
    withTimeout(5_000) {
      val older =
          debt(
              id = "0a1b2c3d-4e5f-4a6b-8c7d-8e9f0a1b2c3d",
              contactId = C1,
              amount = 10_000,
              at = "2026-09-01T00:00:00Z",
          )
      val newer =
          debt(
              id = "1b2c3d4e-5f6a-4b7c-8d8e-9f0a1b2c3d4e",
              contactId = C1,
              amount = 5_000,
              at = "2026-09-02T00:00:00Z",
          )
      val person =
          LedgerView(
              contacts = listOf(contact(C1, "خالد")),
              archivedContacts = emptyList(),
              debts = listOf(older, newer),
              payments = emptyList(),
              receivable = Cents(15_000),
              payable = Cents.ZERO,
              confirmedReceivable = Cents(15_000),
              confirmedPayable = Cents.ZERO,
              pendingContacts = 0,
              pendingDebts = 0,
              pendingPayments = 0,
              excludedOperations = emptySet(),
          )
      val source =
          SummarySource(
              jobs,
              listOf(summary(C1, "خالد", net = 15_000, openReceivable = 15_000, atHours = 2)),
              person = person,
          )

      val model = model(source)
      model.state.first { it.loaded }
      model.pick(C1)
      model.toPayment()
      model.press("1")
      model.press("2")
      model.press("0")
      model.save(context)

      val commands = source.appended.await()
      assertEquals(2, commands.size)
      val parts = commands.map { it.payload() as PaymentCreate }
      assertEquals(listOf(older.id, newer.id), parts.map { it.debtId })
      assertEquals(listOf(Cents(10_000), Cents(2_000)), parts.map { it.amountCents })
    }
  }

  @Test
  fun aSaveTappedBeforeThePersonsViewLandsWaitsWithoutBlockingAndThenSaves() = runBlocking {
    withTimeout(5_000) {
      val gate = CompletableDeferred<Unit>()
      val source =
          SummarySource(
              jobs,
              listOf(summary(C1, "خالد", net = 0, openReceivable = 0, atHours = 2)),
              person = ledger(contact(C1, "خالد")),
              personGate = gate,
          )
      val model = model(source)
      model.state.first { it.loaded }
      model.pick(C1)
      model.press("8")
      model.press("5")

      model.save(context)
      assertEquals(
          "the save returns at once and waits for the view",
          QuickAddStep.AMOUNT,
          model.state.value.step,
      )
      assertFalse(source.appended.isCompleted)

      gate.complete(Unit)
      model.state.first { it.step == QuickAddStep.DONE }
      val payload = source.appended.await().single().payload() as DebtCreate
      assertEquals(Cents(8_500), payload.amountCents)
      assertEquals(C1, payload.contactId)
    }
  }

  @Test
  @OptIn(ExperimentalCoroutinesApi::class)
  fun aNewSummaryEmissionUpdatesThePickerRowsForTheTypedQueryOffTheMainThread() = runBlocking {
    withTimeout(5_000) {
      val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "TestMain") }
      val testMain = executor.asCoroutineDispatcher()
      Dispatchers.resetMain()
      Dispatchers.setMain(testMain)
      try {
        val personThreads = ConcurrentLinkedQueue<String>()
        val contactThreads = ConcurrentLinkedQueue<String>()
        val personSearch: (List<QuickAddPerson>, String) -> List<QuickAddPerson> =
            { people, query ->
              personThreads.add(Thread.currentThread().name)
              PersonSearch.search(people, query)
            }
        val contactSearch: (List<ContactRow>, String) -> List<ContactRow> = { rows, query ->
          contactThreads.add(Thread.currentThread().name)
          ContactPickerRows.search(rows, query)
        }
        val source =
            SummarySource(
                jobs,
                listOf(
                    summary(C1, "خالد", net = 0, openReceivable = 0, atHours = 2),
                    summary(C2, "سارة", net = 0, openReceivable = 0, atHours = 1),
                ),
            )
        val model =
            QuickAddViewModel(
                source,
                null,
                false,
                SavedStateHandle(),
                100,
                personSearch,
                contactSearch,
            )
        model.state.first { it.loaded && it.people.size == 2 }
        model.onQueryChange("سا")
        model.state.first { it.people.map { person -> person.name } == listOf("سارة") }

        personThreads.clear()
        contactThreads.clear()
        source.summaries.value =
            listOf(
                summary(C1, "خالد", net = 0, openReceivable = 0, atHours = 2),
                summary(C2, "سارة", net = 0, openReceivable = 0, atHours = 1),
                summary(C3, "سامي", net = 0, openReceivable = 0, atHours = 3),
            )
        val updated =
            model.state.first { it.people.map { person -> person.name } == listOf("سامي", "سارة") }

        assertEquals(listOf("سامي", "سارة"), updated.people.map { it.name })
        assertTrue("the projection searched: $personThreads", personThreads.isNotEmpty())
        assertTrue("the projection searched contacts: $contactThreads", contactThreads.isNotEmpty())
        assertTrue(
            "no person search on the main thread: $personThreads",
            personThreads.none { it.startsWith("TestMain") },
        )
        assertTrue(
            "no contact search on the main thread: $contactThreads",
            contactThreads.none { it.startsWith("TestMain") },
        )
      } finally {
        Dispatchers.resetMain()
        executor.shutdownNow()
      }
    }
  }

  @Test
  fun deviceContactsReadDuringTheProjectionsSearchAreNotOverwrittenByItsOlderRows() = runBlocking {
    withTimeout(5_000) {
      // The projection searches the empty contact rows; the device contacts land before it applies.
      val projectionSearching = CompletableDeferred<Unit>()
      val contactsShown = CountDownLatch(1)
      // Only the projection searches people before they are loaded into the ViewModel.
      val projecting = ThreadLocal.withInitial { false }
      val personSearch: (List<QuickAddPerson>, String) -> List<QuickAddPerson> = { people, query ->
        projecting.set(people.isNotEmpty())
        PersonSearch.search(people, query)
      }
      val contactSearch: (List<ContactRow>, String) -> List<ContactRow> = { rows, query ->
        if (projecting.get() && rows.isEmpty() && projectionSearching.complete(Unit)) {
          contactsShown.await(5, TimeUnit.SECONDS)
        }
        ContactPickerRows.search(rows, query)
      }
      val device =
          object : DeviceContacts {
            override suspend fun list(): List<DeviceContact> {
              projectionSearching.await()
              return listOf(DeviceContact("نمر", listOf("0599123456")))
            }
          }
      val source =
          SummarySource(
              jobs,
              listOf(summary(C1, "خالد", net = 0, openReceivable = 0, atHours = 1)),
              device = device,
          )
      val model =
          QuickAddViewModel(
              source,
              null,
              false,
              SavedStateHandle(),
              100,
              personSearch,
              contactSearch,
          )
      model.state.first { it.contactRows.isNotEmpty() }
      contactsShown.countDown()

      val loaded = model.state.first { it.loaded }

      assertTrue("the device contacts stay listed", loaded.contactRows.isNotEmpty())
    }
  }

  @Test
  fun aQueryTypedBeforeThePeopleLoadStillShowsItsRowsAfterTheyLoad() = runBlocking {
    withTimeout(5_000) {
      val source = SummarySource(jobs, emptyList())
      val model = model(source)
      model.onQueryChange("سار")
      model.state.first { it.loaded }

      source.summaries.value =
          listOf(
              summary(C1, "خالد", net = 0, openReceivable = 0, atHours = 2),
              summary(C2, "سارة", net = 0, openReceivable = 0, atHours = 1),
          )
      val picked = model.state.first { it.people.map { person -> person.name } == listOf("سارة") }

      assertEquals(listOf("سارة"), picked.people.map { it.name })
    }
  }

  /** A summary-ready source whose [view] fails the test if the ViewModel ever collects it. */
  private class SummarySource(
      val jobs: CoroutineScope,
      summaries: List<ContactSummaryEntity>,
      val person: LedgerView? = null,
      val personGate: CompletableDeferred<Unit>? = null,
      val device: DeviceContacts? = null,
  ) : QuickAddSource {
    override val contacts: DeviceContacts
      get() = device ?: super.contacts

    override fun contactsGranted() = device != null

    val appended = CompletableDeferred<List<LedgerCommand>>()
    override val view: Flow<LedgerView?> =
        kotlinx.coroutines.flow.flow { error("the whole-store view must never be collected") }

    override val summaries = MutableStateFlow<List<ContactSummaryEntity>?>(summaries)
    override val summaryReady = MutableStateFlow(true)
    override val permissions = flowOf(emptyMap<String, Boolean>())
    override val blocked = flowOf(false)

    override fun personView(contactId: String): Flow<LedgerView?> =
        kotlinx.coroutines.flow.flow {
          personGate?.await()
          emit(person)
        }

    override fun open(): QuickAddStore =
        object : QuickAddStore {
          override val jobs = this@SummarySource.jobs

          override fun origin() = Events.origin

          override fun isOnline() = true

          override suspend fun append(commands: List<LedgerCommand>) {
            appended.complete(commands)
          }
        }
  }

  private fun summary(
      id: String,
      name: String,
      net: Long,
      openReceivable: Long,
      atHours: Int,
  ): ContactSummaryEntity {
    val at = Instant.parse("2026-09-01T00:00:00Z").plusSeconds(atHours * 3_600L)
    return ContactSummaryEntity(
        contactId = id,
        name = name,
        phone = "0599000000",
        kind = ContactKind.CUSTOMER,
        category = "",
        pending = false,
        createdAt = at,
        net = net,
        openReceivable = openReceivable,
        openPayable = 0,
        earliestOpenDue = null,
        lastEntryAt = at,
        searchKey = PersonSearch.normalize(name),
    )
  }

  private fun debt(id: String, contactId: String, amount: Long, at: String): DebtView =
      DebtView(
          id = id,
          contactId = contactId,
          direction = Direction.RECEIVABLE,
          amount = Cents(amount),
          paid = Cents.ZERO,
          confirmedRemaining = Cents(amount),
          remaining = Cents(amount),
          version = 1,
          pending = false,
          hasPendingPayments = false,
          note = "",
          dueDate = null,
          createdAt = Instant.parse(at),
          createdBy = "",
          operationId = "op-$id",
      )

  private fun contact(id: String, name: String) =
      ContactView(
          id = id,
          name = name,
          phone = "0599000000",
          category = "",
          note = "",
          version = 1,
          pending = false,
          createdAt = Instant.parse("2026-09-01T00:00:00Z"),
          createdBy = "",
          operationId = "op-$id",
          kind = ContactKind.CUSTOMER,
      )

  private fun ledger(vararg contacts: ContactView) =
      LedgerView(
          contacts = contacts.toList(),
          archivedContacts = emptyList(),
          debts = emptyList(),
          payments = emptyList(),
          receivable = Cents.ZERO,
          payable = Cents.ZERO,
          confirmedReceivable = Cents.ZERO,
          confirmedPayable = Cents.ZERO,
          pendingContacts = 0,
          pendingDebts = 0,
          pendingPayments = 0,
          excludedOperations = emptySet(),
      )
}
