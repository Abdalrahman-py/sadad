package com.sadad.app.feature.quickadd

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.sadad.app.data.db.Events
import com.sadad.app.feature.auth.MainDispatcherRule
import com.sadad.app.feature.contactpicker.ContactPermission
import com.sadad.app.feature.contactpicker.DeviceContact
import com.sadad.app.feature.contactpicker.DeviceContacts
import com.sadad.app.ui.UiTestApplication
import com.sadad.ledger.Cents
import com.sadad.ledger.CommandType
import com.sadad.ledger.ContactCreate
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val KHALED = "0a1b2c3d-4e5f-4a6b-8c7d-8e9f0a1b2c3d"
private const val SARA = "1b2c3d4e-5f6a-4b7c-8d8e-9f0a1b2c3d4e"

/**
 * Quick add creating a person who is not in Sadad yet (ticket 137): from the phone's contacts or a
 * typed name, through the new-person step, saved with the debt as one batch after the undo window.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = UiTestApplication::class)
class QuickAddNewPersonTest {
  @get:Rule val main = MainDispatcherRule()
  private val context: Context = ApplicationProvider.getApplicationContext()
  private val jobs = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  @After
  fun tearDown() {
    jobs.cancel()
  }

  private class FakeSource(
      val jobs: CoroutineScope,
      view: LedgerView,
      private val phone: List<DeviceContact> = emptyList(),
      private val granted: Boolean = true,
      private val limit: Int? = null,
  ) : QuickAddSource {
    val appended = CompletableDeferred<List<LedgerCommand>>()
    override val view = MutableStateFlow<LedgerView?>(view)
    override val permissions = flowOf(emptyMap<String, Boolean>())
    override val blocked = flowOf(false)
    override val contacts =
        object : DeviceContacts {
          override suspend fun list() = phone
        }

    override fun contactsGranted() = granted

    override fun contactLimit() = limit

    override fun refusalText(code: String) = "refused:$code"

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

  private fun model(source: QuickAddSource) =
      QuickAddViewModel(source, null, false, SavedStateHandle(), undoWindowMs = 100)

  private suspend fun QuickAddViewModel.search(query: String, rows: Int = 1): QuickAddState {
    state.first { it.loaded }
    onQueryChange(query)
    return state.first { it.contactRows.size >= rows }
  }

  @Test
  fun aPhoneContactGoesThroughTheNewPersonStepAndSavesThePersonAndTheDebtTogether() = runBlocking {
    withTimeout(5_000) {
      val source =
          FakeSource(
              jobs,
              ledger(contact(KHALED, "خالد")),
              listOf(DeviceContact("منى", listOf("+972 59 912 3456"))),
          )
      val model = model(source)

      val searched = model.search("منى")
      model.pickPhoneContact(searched.contactRows.single())
      val step = model.state.value
      assertEquals(QuickAddStep.NEW_PERSON, step.step)
      assertEquals("منى", step.newPerson?.name)
      assertEquals("0599123456", step.newPerson?.phone)
      assertEquals("972", step.newPerson?.prefix)

      model.confirmNewPerson(context)
      assertEquals(QuickAddStep.AMOUNT, model.state.value.step)
      model.press("4")
      model.press("0")
      assertFalse(source.appended.isCompleted)
      model.save(context)

      val commands = source.appended.await()
      assertEquals(
          listOf(CommandType.CONTACT_CREATE, CommandType.DEBT_CREATE),
          commands.map { it.type },
      )
      val person = commands[0].payload() as ContactCreate
      val debt = commands[1].payload() as DebtCreate
      assertEquals("منى", person.name)
      assertEquals("0599123456", person.phone)
      assertEquals("972", person.whatsappCode)
      assertEquals(commands[0].entityId, debt.contactId)
      assertEquals(listOf(commands[0].operationId), commands[1].dependsOn)
      assertEquals(Direction.RECEIVABLE, debt.direction)
      assertEquals(Cents(4_000), debt.amountCents)
    }
  }

  @Test
  fun aSupplierGetsAPayableDebt() = runBlocking {
    withTimeout(5_000) {
      val source = FakeSource(jobs, ledger())
      val model = model(source)
      model.state.first { it.loaded }
      model.onQueryChange("مخبز الأمل")
      model.addNewPerson()
      model.onNewPersonKind(ContactKind.SUPPLIER)
      model.confirmNewPerson(context)
      model.press("9")
      model.save(context)

      val commands = source.appended.await()
      assertEquals(ContactKind.SUPPLIER, (commands[0].payload() as ContactCreate).kind)
      assertEquals(Direction.PAYABLE, (commands[1].payload() as DebtCreate).direction)
    }
  }

  @Test
  fun undoInsideTheWindowCreatesNeitherThePersonNorTheDebt() = runBlocking {
    withTimeout(5_000) {
      val source = FakeSource(jobs, ledger())
      val model = model(source)
      model.state.first { it.loaded }
      model.onQueryChange("منى")
      model.addNewPerson()
      model.confirmNewPerson(context)
      model.press("5")
      model.save(context)
      model.undo(context)
      delay(300)

      assertFalse(source.appended.isCompleted)
      assertEquals(QuickAddStep.AMOUNT, model.state.value.step)
      assertEquals("منى", model.state.value.selected?.name)
    }
  }

  @Test
  fun aTypedNameOpensTheStepWithAnEmptyPhone() = runBlocking {
    withTimeout(5_000) {
      val model = model(FakeSource(jobs, ledger()))
      model.state.first { it.loaded }
      model.onQueryChange("  سعيد  ")
      model.addNewPerson()

      val step = model.state.value
      assertEquals(QuickAddStep.NEW_PERSON, step.step)
      assertEquals("سعيد", step.newPerson?.name)
      assertEquals("", step.newPerson?.phone)
      assertEquals("970", step.newPerson?.prefix)
    }
  }

  @Test
  fun aPhoneContactThatIsInTheStoreSelectsThatPersonAndSkipsTheStep() = runBlocking {
    withTimeout(5_000) {
      val source =
          FakeSource(
              jobs,
              ledger(contact(KHALED, "خالد", "0599123456")),
              listOf(DeviceContact("أبو محمد", listOf("0599123456"))),
          )
      val model = model(source)

      model.pickPhoneContact(model.search("محمد").contactRows.single())

      assertEquals(QuickAddStep.AMOUNT, model.state.value.step)
      assertEquals(KHALED, model.state.value.selected?.id)
      assertNull(model.state.value.newPerson)
    }
  }

  @Test
  fun anArchivedMatchLeavesForTheTrash() = runBlocking {
    withTimeout(5_000) {
      val archived = contact(SARA, "سارة", "0599888777")
      val source =
          FakeSource(
              jobs,
              ledger().copy(archivedContacts = listOf(archived)),
              listOf(DeviceContact("سارة", listOf("0599888777"))),
          )
      val model = model(source)

      model.pickPhoneContact(model.search("سارة").contactRows.single())

      assertTrue(model.state.value.openTrash)
      assertEquals(QuickAddStep.PICKER, model.state.value.step)
    }
  }

  @Test
  fun aDuplicatePhoneTypedInTheStepOffersTheExistingRecord() = runBlocking {
    withTimeout(5_000) {
      val model = model(FakeSource(jobs, ledger(contact(KHALED, "خالد", "0599123456"))))
      model.state.first { it.loaded }
      model.onQueryChange("خالد الثاني")
      model.addNewPerson()
      model.onNewPersonPhone("0599123456")
      model.confirmNewPerson(context)

      val step = model.state.value
      assertEquals(QuickAddStep.NEW_PERSON, step.step)
      assertEquals(KHALED, step.newPerson?.duplicate?.contactId)

      model.openDuplicate()
      assertEquals(QuickAddStep.AMOUNT, model.state.value.step)
      assertEquals(KHALED, model.state.value.selected?.id)
      assertNull(model.state.value.newPerson)
    }
  }

  @Test
  fun atThePersonLimitTheStepDoesNotOpen() = runBlocking {
    withTimeout(5_000) {
      val people = ledger(contact(KHALED, "خالد"), contact(SARA, "سارة", "0599000001"))
      val full = model(FakeSource(jobs, people, limit = 2))
      full.state.first { it.loaded }
      full.onQueryChange("منى")
      full.addNewPerson()
      assertEquals(QuickAddStep.PICKER, full.state.value.step)
      assertEquals("refused:contact_limit_reached", full.state.value.error)

      val room = model(FakeSource(jobs, people, limit = 3))
      room.state.first { it.loaded }
      room.onQueryChange("منى")
      room.addNewPerson()
      assertEquals(QuickAddStep.NEW_PERSON, room.state.value.step)

      val noLease = model(FakeSource(jobs, people, limit = null))
      noLease.state.first { it.loaded }
      noLease.onQueryChange("منى")
      noLease.addNewPerson()
      assertEquals(QuickAddStep.NEW_PERSON, noLease.state.value.step)
    }
  }

  @Test
  fun withoutThePermissionThePhoneContactsAreNotRead() = runBlocking {
    withTimeout(5_000) {
      val model =
          model(
              FakeSource(
                  jobs,
                  ledger(),
                  listOf(DeviceContact("منى", listOf("0599123456"))),
                  granted = false,
              )
          )
      model.state.first { it.loaded }
      model.onQueryChange("منى")

      assertEquals(ContactPermission.NotAsked, model.state.value.contactsPermission)
      assertTrue(model.state.value.contactRows.isEmpty())

      model.onContactsPermission(granted = true)
      assertNotNull(model.state.first { it.contactRows.isNotEmpty() })
    }
  }

  private fun contact(id: String, name: String, phone: String = "0599000000") =
      ContactView(
          id = id,
          name = name,
          phone = phone,
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
