package com.sadad.app.feature.quickadd

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.sadad.app.data.db.Events
import com.sadad.app.data.session.StoredInstallation
import com.sadad.app.feature.auth.MainDispatcherRule
import com.sadad.app.testing.TestGraph
import com.sadad.app.ui.UiTestApplication
import com.sadad.ledger.Cents
import com.sadad.ledger.CommandType
import com.sadad.ledger.Commands
import com.sadad.ledger.ContactKind
import com.sadad.ledger.DebtCreate
import com.sadad.ledger.DebtOffset
import com.sadad.ledger.DebtOrigin
import com.sadad.ledger.Direction
import com.sadad.ledger.PaymentCreate
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The one flow that records money. Everything here is about what reaches the outbox: which command,
 * with which direction and amount, and when — nothing may be queued before the undo window ends,
 * and nothing at all when the save is refused.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = UiTestApplication::class)
class QuickAddViewModelTest {
  @Test
  fun aPersonSavedThroughTheFullFormCanReceiveADebtAndSurvivesSettlement() = runTest {
    TestGraph.signedIn().use { fixture ->
      val store = checkNotNull(fixture.graph.current.value)
      val form =
          com.sadad.app.feature.contactform.ContactFormViewModel(
              store.repository,
              store.account.storeUserId,
              context,
              SavedStateHandle(),
              initialName = "إبراهيم",
          )
      fixture.closeWithStore(form.viewModelScope)
      assertEquals("إبراهيم", form.state.value.name)
      form.onPhoneChange("0599123456")
      form.save()
      form.confirm()
      val id = checkNotNull(form.state.first { it.savedContactId != null }.savedContactId)
      val model = QuickAddViewModel(fixture.graph, id, false, SavedStateHandle(), 100)
      fixture.closeWithStore(model.viewModelScope)
      model.state.first { it.selected?.id == id }
      model.press("2")
      model.press("5")
      model.press("8")
      model.save(context)
      store.awaitOutbox(2)
      val rows = store.outbox.snapshot()
      assertEquals(
          listOf(CommandType.CONTACT_CREATE, CommandType.DEBT_CREATE),
          rows.map { it.type },
      )
      assertEquals(
          "+970599123456",
          (rows.first().command.payload() as com.sadad.ledger.ContactCreate).phone,
      )
      assertEquals(listOf(rows.first().operationId), rows.last().command.dependsOn)
      fixture.settleAll()
      val view =
          store.repository.view.first {
            it?.contacts?.any { person -> person.id == id && !person.pending } == true
          }!!
      assertEquals("إبراهيم", view.contacts.first { it.id == id }.name)
      assertEquals(Cents(25_800), view.debts.first { it.contactId == id }.remaining)
    }
  }

  @get:Rule val main = MainDispatcherRule()
  private val context: Context = ApplicationProvider.getApplicationContext()

  @Test
  fun aCustomerDebtIsQueuedOnlyAfterTheUndoWindowAndSaysWhatItRecorded() = runTest {
    TestGraph.signedIn().use { fixture ->
      val contact = fixture.settledContact("خالد")
      val store = checkNotNull(fixture.graph.current.value)
      val model = fixture.model(undoWindowMs = 1_000)

      model.state.first { it.loaded }
      model.onQueryChange("خالد")
      model.pick(contact)
      model.press("8")
      model.press("5")

      val amountStep = model.state.value
      assertEquals(QuickAddStep.AMOUNT, amountStep.step)
      assertEquals("سجّل ₪85 على خالد", model.sentence(context))
      assertTrue(
          "nothing is queued while the amount is on screen",
          store.outbox.snapshot().isEmpty(),
      )

      model.save(context)
      assertEquals(QuickAddStep.DONE, model.state.value.step)
      assertEquals("سجّل ₪85 على خالد", model.state.value.done?.sentence)
      assertTrue("the undo window holds the command", store.outbox.snapshot().isEmpty())

      store.awaitOutbox(1)

      val row = store.outbox.snapshot().single()
      assertEquals(CommandType.DEBT_CREATE, row.type)
      val payload = row.command.payload() as DebtCreate
      assertEquals(Direction.RECEIVABLE, payload.direction)
      assertEquals(Cents(8_500), payload.amountCents)
      assertEquals(contact, payload.contactId)
    }
  }

  @Test
  fun thePickerShowsOnlyTheMatches() = runTest {
    TestGraph.signedIn().use { fixture ->
      fixture.settledContact("خالد")
      fixture.settledContact("سارة")
      fixture.settledContact("سامي")
      val model = fixture.model()
      model.state.first { it.loaded && it.people.size == 3 }

      model.onQueryChange("سار")

      assertEquals(listOf("سارة"), model.state.value.people.map { it.name })
      assertEquals(
          "the count is the rendered list, not a second number",
          1,
          model.state.value.people.size,
      )

      model.clearQuery()
      assertEquals(3, model.state.value.people.size)
    }
  }

  @Test
  fun openingTheFlowForAContactSelectsThemAndStartsAtTheAmount() = runTest {
    TestGraph.signedIn().use { fixture ->
      val contact = fixture.settledContact("خالد")
      val model =
          QuickAddViewModel(fixture.graph, contact, false, SavedStateHandle()).also {
            fixture.closeWithStore(it.viewModelScope)
          }
      val state = model.state.first { it.loaded }

      assertEquals(contact, state.selected?.id)
      assertEquals(QuickAddStep.AMOUNT, state.step)
      assertEquals("خالد", state.personName)
    }
  }

  @Test
  fun undoInsideTheWindowQueuesNothingAndGoesBackToTheAmount() = runTest {
    TestGraph.signedIn().use { fixture ->
      val contact = fixture.settledContact("خالد")
      val store = checkNotNull(fixture.graph.current.value)
      val model = fixture.model()
      model.state.first { it.loaded }
      model.pick(contact)
      model.press("8")
      model.press("5")
      model.save(context)

      model.undo(context)
      Thread.sleep(600)

      assertEquals(QuickAddStep.AMOUNT, model.state.value.step)
      assertEquals("85", model.state.value.amount)
      assertTrue(store.outbox.snapshot().isEmpty())
    }
  }

  @Test
  fun aSupplierDebtIsQueuedPayable() = runTest {
    TestGraph.signedIn().use { fixture ->
      val contact = fixture.settledContact("مخبز الأمل", kind = ContactKind.SUPPLIER)
      val store = checkNotNull(fixture.graph.current.value)
      val model = fixture.model()
      model.state.first { it.loaded }
      model.pick(contact)
      model.press("3")
      model.press("0")
      model.press("0")
      assertEquals("سجّل ₪300 لمخبز الأمل", model.sentence(context))
      model.save(context)
      store.awaitOutbox(1)

      val payload = store.outbox.snapshot().single().command.payload() as DebtCreate
      assertEquals(Direction.PAYABLE, payload.direction)
      assertEquals(Cents(30_000), payload.amountCents)
    }
  }

  @Test
  fun aPaymentAgainstTwoOpenDebtsQueuesTwoPartsOldestFirst() = runTest {
    TestGraph.signedIn().use { fixture ->
      val contact = fixture.settledContact("خالد")
      val older = fixture.openDebt(contact, 10_000)
      val newer = fixture.openDebt(contact, 5_000)
      val store = checkNotNull(fixture.graph.current.value)
      val model = fixture.model()
      model.state.first { it.loaded }
      model.pick(contact)
      model.toPayment()
      model.press("1")
      model.press("2")
      model.press("0")
      assertEquals("سجّل دفعة ₪120 من خالد", model.sentence(context))
      model.save(context)
      store.awaitOutbox(2)

      val payloads = store.outbox.snapshot().map { it.command.payload() as PaymentCreate }
      assertEquals(listOf(older, newer), payloads.map { it.debtId })
      assertEquals(listOf(Cents(10_000), Cents(2_000)), payloads.map { it.amountCents })
      assertEquals(CommandType.PAYMENT_CREATE, store.outbox.snapshot().first().type)
    }
  }

  @Test
  fun aSupplierPaymentSavesAgainstThePayableBalance() = runTest {
    TestGraph.signedIn().use { fixture ->
      val supplier = fixture.settledContact("مخبز الأمل", kind = ContactKind.SUPPLIER)
      val debt = fixture.openDebt(supplier, 10_000, Direction.PAYABLE)
      val store = checkNotNull(fixture.graph.current.value)
      val model = fixture.model()
      model.state.first { it.loaded && it.people.isNotEmpty() }
      model.pick(supplier)
      model.toPayment()
      model.state.first { it.overBalance == Cents(10_000) }

      model.press("1")
      model.press("0")
      model.press("0")

      val state = model.state.value
      assertEquals(Cents(10_000), state.overBalance)
      assertTrue("a supplier payment inside the payable balance can be saved", state.canSave)

      model.save(context)
      store.awaitOutbox(1)

      val payload = store.outbox.snapshot().single().command.payload() as PaymentCreate
      assertEquals(debt, payload.debtId)
      assertEquals(Cents(10_000), payload.amountCents)
    }
  }

  @Test
  fun aPaymentAboveTheOpenBalanceBecomesCreditAndThePreviewSaysSo() = runTest {
    TestGraph.signedIn().use { fixture ->
      val contact = fixture.settledContact("خالد")
      fixture.openDebt(contact, 25_000)
      val store = checkNotNull(fixture.graph.current.value)
      val model = fixture.model(undoWindowMs = 50)
      model.state.first { it.loaded && it.people.isNotEmpty() }
      model.pick(contact)
      model.toPayment()
      model.state.first { it.overBalance == Cents(25_000) }

      for (key in listOf("5", "0", "0")) model.press(key)

      assertEquals(
          "يسدد ₪ 250 الدين · يبقى له ₪ 250 رصيدًا",
          model.preview(context),
      )
      assertTrue("a customer's overpayment is accepted", model.state.value.canSave)

      model.save(context)
      store.awaitOutbox(2)

      val rows = store.outbox.snapshot()
      assertEquals(
          listOf(CommandType.PAYMENT_CREATE, CommandType.DEBT_CREATE),
          rows.map { it.type },
      )
      val payment = rows[0].command.payload() as PaymentCreate
      assertEquals(Cents(25_000), payment.amountCents)
      val credit = rows[1].command.payload() as DebtCreate
      assertEquals(contact, credit.contactId)
      assertEquals(Direction.PAYABLE, credit.direction)
      assertEquals(Cents(25_000), credit.amountCents)
      assertEquals(DebtOrigin.OVERPAYMENT, credit.origin)
    }
  }

  @Test
  fun aDebtIsPaidFromTheCreditHeldAndThePreviewSaysWhatIsLeft() = runTest {
    TestGraph.signedIn().use { fixture ->
      val (contact, credit) = fixture.creditFor("خالد", 25_000)
      val store = checkNotNull(fixture.graph.current.value)
      val model = fixture.model(undoWindowMs = 50)
      model.state.first { it.loaded && it.people.any { person -> person.id == contact } }
      model.pick(contact)

      for (key in listOf("1", "0", "0")) model.press(key)

      assertEquals("يُخصم من رصيده ₪ 100 · يبقى له ₪ 150", model.preview(context))

      model.save(context)
      store.awaitOutbox(2)

      val rows = store.outbox.snapshot()
      assertEquals(
          listOf(CommandType.DEBT_CREATE, CommandType.DEBT_OFFSET),
          rows.map { it.type },
      )
      val debt = rows[0].command.payload() as DebtCreate
      assertEquals(Direction.RECEIVABLE, debt.direction)
      assertEquals(Cents(10_000), debt.amountCents)

      val offset = rows[1].command.payload() as DebtOffset
      assertEquals("the new Debt is the one settled", rows[0].entityId, offset.receivableDebtId)
      assertEquals("the credit pays for it", credit, offset.payableDebtId)
      assertEquals(Cents(10_000), offset.amountCents)
      assertEquals(
          "the offset waits for the Debt it settles",
          listOf(rows[0].operationId),
          rows[1].command.dependsOn,
      )
    }
  }

  @Test
  fun aSupplierPaymentAboveTheBalanceIsStillRefused() = runTest {
    TestGraph.signedIn().use { fixture ->
      val supplier = fixture.settledContact("مخبز الأمل", kind = ContactKind.SUPPLIER)
      fixture.openDebt(supplier, 10_000, Direction.PAYABLE)
      val model = fixture.model()
      model.state.first { it.loaded && it.people.isNotEmpty() }
      model.pick(supplier)
      model.toPayment()
      model.state.first { it.overBalance == Cents(10_000) }

      for (key in listOf("1", "2", "0")) model.press(key)

      assertFalse("no supplier credit in this ticket", model.state.value.canSave)
    }
  }

  @Test
  fun fiveThousandAsksForConfirmationAndFourNineNineNineNinetyNineDoesNot() = runTest {
    TestGraph.signedIn().use { fixture ->
      val contact = fixture.settledContact("خالد")
      val store = checkNotNull(fixture.graph.current.value)
      val model = fixture.model()
      model.state.first { it.loaded }
      model.pick(contact)

      for (key in listOf("4", "9", "9", "9", ".", "9", "9")) model.press(key)
      model.save(context)
      assertEquals(QuickAddStep.DONE, model.state.value.step)

      model.another(context)
      model.pick(contact)
      for (key in listOf("5", "0", "0", "0")) model.press(key)
      model.save(context)

      assertEquals(Cents(500_000), model.state.value.anomaly)
      assertEquals(QuickAddStep.AMOUNT, model.state.value.step)
      // The 4,999.99 entry was recorded when «تسجيل آخر» left the done step; the 5,000 one is
      // still waiting for its confirmation and adds nothing.
      store.awaitOutbox(1)
      Thread.sleep(600)
      assertEquals(1, store.outbox.snapshot().size)
    }
  }

  @Test
  fun withoutPermissionToRegisterPaymentsThereIsNoPaymentMode() = runTest {
    TestGraph.signedIn().use { fixture ->
      val contact = fixture.settledContact("خالد")
      val store = checkNotNull(fixture.graph.current.value)
      fixture.limitPermissions(mapOf("registerPayments" to false))
      val model = fixture.model()
      model.state.first { it.loaded && it.restricted }
      model.pick(contact)

      model.toPayment()

      assertFalse(model.state.value.payment)
      assertEquals(QuickAddStep.AMOUNT, model.state.value.step)
      assertTrue(store.outbox.snapshot().isEmpty())
    }
  }

  @Test
  fun withoutNetworkOrLeaseASaveStillQueuesAndTheDoneStepSaysItWaits() = runTest {
    var online = true
    TestGraph.signedIn(online = { online }).use { fixture ->
      val contact = fixture.settledContact("خالد")
      val store = checkNotNull(fixture.graph.current.value)
      online = false
      assertFalse(store.offlineAllowed())
      val model = fixture.model(undoWindowMs = 50)
      assertFalse(
          "a lapsed lease must not block the keypad",
          model.state.first { it.loaded }.blocked,
      )
      model.pick(contact)
      model.press("4")
      model.press("0")

      assertTrue(model.state.value.canSave)
      model.save(context)
      store.awaitOutbox(1)

      assertEquals(
          Cents(4_000),
          (store.outbox.snapshot().single().command.payload() as DebtCreate).amountCents,
      )
      assertTrue("the done step must say the entry waits", model.state.value.done!!.offline)
    }
  }

  @Test
  fun anUnapprovedDeviceStillMayNotSave() = runTest {
    TestGraph.signedIn().use { fixture ->
      val contact = fixture.settledContact("خالد")
      val account = checkNotNull(fixture.graph.accounts.accounts.first().firstOrNull())
      fixture.graph.accounts.update(account.storeUserId) {
        it.copy(installation = StoredInstallation("revoked", 1, emptyMap()))
      }
      val model = fixture.model()
      model.state.first { it.loaded && it.blocked }
      model.pick(contact)
      model.press("4")

      assertFalse(model.state.value.canSave)
    }
  }

  @Test
  fun aDebtCarriesTheDueDateAndNoteTheChipsSet() = runTest {
    TestGraph.signedIn().use { fixture ->
      val contact = fixture.settledContact("خالد")
      val store = checkNotNull(fixture.graph.current.value)
      val model = fixture.model(undoWindowMs = 50)
      model.state.first { it.loaded }
      model.pick(contact)
      model.onDueDateChange("2026-11-01")
      model.onNoteChange("بضاعة")
      model.press("4")
      model.press("0")
      model.save(context)
      store.awaitOutbox(1)

      val payload = store.outbox.snapshot().single().command.payload() as DebtCreate
      assertEquals("2026-11-01", payload.dueDate)
      assertEquals("بضاعة", payload.note)
    }
  }

  @Test
  fun undoAfterTheWindowCannotQueueTheSameEntryTwice() = runTest {
    TestGraph.signedIn().use { fixture ->
      val contact = fixture.settledContact("خالد")
      val store = checkNotNull(fixture.graph.current.value)
      val model = fixture.model(undoWindowMs = 50)
      model.state.first { it.loaded }
      model.pick(contact)
      model.press("7")
      model.press("5")
      model.save(context)
      store.awaitOutbox(1)

      // The window has ended: the entry is queued and «تراجع 0» must not re-arm it.
      model.undo(context)
      model.save(context)
      Thread.sleep(300)

      assertEquals(1, store.outbox.snapshot().size)
    }
  }

  @Test
  fun aWayOutOfTheDoneStepQueuesTheEntryNow() = runTest {
    TestGraph.signedIn().use { fixture ->
      val contact = fixture.settledContact("خالد")
      val store = checkNotNull(fixture.graph.current.value)
      // Longer than the test will wait, so only leave() can have queued the row.
      val model = fixture.model(undoWindowMs = 5_000)
      model.state.first { it.loaded }
      model.pick(contact)
      model.press("4")
      model.press("0")
      model.save(context)
      assertEquals(QuickAddStep.DONE, model.state.value.step)

      // System back, «الرئيسية», «فتح الشخص»: every way out goes through leave().
      model.leave(context)
      store.awaitOutbox(1)

      assertEquals(CommandType.DEBT_CREATE, store.outbox.snapshot().single().type)
    }
  }

  @Test
  fun theEntrySurvivesTheLockTeardownInsideTheUndoWindow() = runTest {
    TestGraph.signedIn().use { fixture ->
      val contact = fixture.settledContact("خالد")
      val store = checkNotNull(fixture.graph.current.value)
      val model = fixture.model(undoWindowMs = 300)
      model.state.first { it.loaded }
      model.pick(contact)
      model.press("6")
      model.press("5")
      model.save(context)
      assertEquals(QuickAddStep.DONE, model.state.value.step)

      // What LockGate does when it swaps content() for UnlockScreen: the NavHost leaves
      // composition and its NavBackStackEntry ViewModelStore is cleared. feature/lock belongs to
      // #111 (ticket 87), so this drives the teardown the lock performs, not the lock itself.
      model.viewModelScope.cancel()
      Thread.sleep(1_000)

      val row = store.outbox.snapshot().single()
      assertEquals(CommandType.DEBT_CREATE, row.type)
      val payload = row.command.payload() as DebtCreate
      assertEquals(contact, payload.contactId)
      assertEquals(Cents(6_500), payload.amountCents)
    }
  }

  @Test
  fun switchingFromANewlySavedPersonToAnExistingOneKeepsPaymentMode() = runTest {
    TestGraph.signedIn().use { fixture ->
      val contact = fixture.settledContact("خالد")
      val debt = fixture.openDebt(contact, 10_000)
      val store = checkNotNull(fixture.graph.current.value)
      val model = fixture.model(undoWindowMs = 300)
      model.state.first { it.loaded }

      val added = store.repository.addContact("New customer", null, null, null, null).getOrThrow()
      model.state.first { it.people.any { person -> person.id == added } }
      model.pick(added)
      model.backToPicker()
      model.clearQuery()
      model.pick(contact)
      model.toPayment()
      model.press("1")
      model.press("0")
      model.press("0")
      model.save(context)
      store.awaitOutbox(2)

      val row = store.outbox.snapshot().last()
      assertEquals(
          "the mode on screen decides, not the previous contact",
          CommandType.PAYMENT_CREATE,
          row.type,
      )
      assertEquals(debt, (row.command.payload() as PaymentCreate).debtId)
    }
  }

  @Test
  fun undoingTheDebtKeepsTheSeparatelySavedPersonAndNextSaveDependsOnIt() = runTest {
    TestGraph.signedIn().use { fixture ->
      val store = checkNotNull(fixture.graph.current.value)
      val model = fixture.model(undoWindowMs = 300)
      model.state.first { it.loaded }

      val added = store.repository.addContact("New customer", null, null, null, null).getOrThrow()
      model.state.first { it.people.any { person -> person.id == added } }
      model.pick(added)
      model.press("5")
      model.press("0")
      model.press("0")
      model.save(context)
      assertEquals(QuickAddStep.DONE, model.state.value.step)

      model.undo(context)
      assertEquals(QuickAddStep.AMOUNT, model.state.value.step)

      assertEquals(CommandType.CONTACT_CREATE, store.outbox.snapshot().single().type)
      // Undo removes only the debt; the separately saved person remains queued.
      model.save(context)
      store.awaitOutbox(2)

      val rows = store.outbox.snapshot()
      assertEquals(
          listOf(CommandType.CONTACT_CREATE, CommandType.DEBT_CREATE),
          rows.map { it.type },
      )
      assertEquals(
          "the debt names the contact that carries it",
          listOf(rows.first().operationId),
          rows.last().command.dependsOn,
      )
    }
  }

  private suspend fun TestGraph.settledContact(
      name: String,
      phone: String? = null,
      kind: ContactKind = ContactKind.CUSTOMER,
  ): String =
      checkNotNull(graph.current.value)
          .repository
          .addContact(name, phone, null, null, null, kind)
          .getOrThrow()
          .also { settleAll() }

  private suspend fun TestGraph.openDebt(
      contactId: String,
      cents: Long,
      direction: Direction = Direction.RECEIVABLE,
  ): String =
      checkNotNull(graph.current.value)
          .repository
          .addDebt(contactId, direction, Cents(cents), null, null)
          .getOrThrow()
          .also { settleAll() }

  /**
   * A Contact holding [cents] of credit: the excess of an earlier payment, as `apply_one` stores
   * it.
   */
  private suspend fun TestGraph.creditFor(name: String, cents: Long): Pair<String, String> {
    val contact = java.util.UUID.nameUUIDFromBytes("credit-$name".toByteArray()).toString()
    val credit = java.util.UUID.randomUUID().toString()
    seed {
      applyPage(
          Events.page(
              listOf(
                  Events.accepted(
                      Commands.contactCreate(
                          Events.origin,
                          Events.at(0),
                          name = name,
                          entityId = contact,
                      ),
                      1,
                  ),
                  Events.accepted(
                      Commands.debtCreate(
                          Events.origin,
                          Events.at(1),
                          contactId = contact,
                          direction = Direction.PAYABLE,
                          amount = Cents(cents),
                          note = "رصيد من دفعة زائدة",
                          debtOrigin = DebtOrigin.OVERPAYMENT,
                          entityId = credit,
                      ),
                      2,
                  ),
              )
          )
      )
    }
    return contact to credit
  }

  private suspend fun TestGraph.limitPermissions(permissions: Map<String, Boolean>) {
    val account = checkNotNull(graph.accounts.accounts.first().firstOrNull())
    checkNotNull(graph.current.value)
    graph.accounts.update(account.storeUserId) {
      it.copy(installation = StoredInstallation("active", 1, permissions))
    }
  }

  /**
   * Short, real windows: the commit runs on the store's scope, not the test dispatcher's clock. The
   * screen's scope is registered with the fixture so no collector outlives the database.
   */
  private fun TestGraph.model(undoWindowMs: Long = 300): QuickAddViewModel =
      QuickAddViewModel(graph, null, false, SavedStateHandle(), undoWindowMs).also {
        closeWithStore(it.viewModelScope)
      }

  /** The outbox is written from another coroutine; polling keeps the assertion about the row. */
  private suspend fun com.sadad.app.data.session.StoreScope.awaitOutbox(count: Int) {
    val deadline = System.currentTimeMillis() + 5_000
    while (outbox.snapshot().size < count && System.currentTimeMillis() < deadline) {
      Thread.sleep(10)
    }
    assertEquals(count, outbox.snapshot().size)
  }
}
