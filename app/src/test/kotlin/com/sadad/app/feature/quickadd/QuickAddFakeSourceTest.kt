package com.sadad.app.feature.quickadd

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.sadad.app.data.db.Events
import com.sadad.app.feature.auth.MainDispatcherRule
import com.sadad.app.ui.UiTestApplication
import com.sadad.ledger.Cents
import com.sadad.ledger.CommandType
import com.sadad.ledger.ContactKind
import com.sadad.ledger.ContactView
import com.sadad.ledger.DebtCreate
import com.sadad.ledger.Direction
import com.sadad.ledger.LedgerCommand
import com.sadad.ledger.LedgerView
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

  private fun contact(id: String, name: String) =
      ContactView(
          id = id,
          name = name,
          phone = "+970599000000",
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
