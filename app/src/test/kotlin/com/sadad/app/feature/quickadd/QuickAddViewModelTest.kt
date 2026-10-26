package com.sadad.app.feature.quickadd

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.sadad.app.data.session.StoredInstallation
import com.sadad.app.feature.auth.MainDispatcherRule
import com.sadad.app.testing.TestGraph
import com.sadad.app.ui.UiTestApplication
import com.sadad.ledger.Cents
import com.sadad.ledger.CommandType
import com.sadad.ledger.ContactKind
import com.sadad.ledger.DebtCreate
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
  fun openingTheFlowForAContactSelectsThemAndStartsAtTheAmount() = runTest {
    TestGraph.signedIn().use { fixture ->
      val contact = fixture.settledContact("خالد")
      val model = QuickAddViewModel(fixture.graph, contact, false, SavedStateHandle())
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
  fun aPaymentAboveTheOpenBalanceIsRefusedAndCannotBeSaved() = runTest {
    TestGraph.signedIn().use { fixture ->
      val contact = fixture.settledContact("خالد")
      fixture.openDebt(contact, 10_000)
      val store = checkNotNull(fixture.graph.current.value)
      val model = fixture.model()
      model.state.first { it.loaded && it.people.isNotEmpty() }
      model.pick(contact)
      model.toPayment()
      model.state.first { it.overBalance == Cents(10_000) }

      model.press("1")
      model.press("2")
      model.press("0")

      val state = model.state.value
      assertEquals(Cents(10_000), state.overBalance)
      assertFalse("a payment over the balance cannot be saved", state.canSave)
      model.save(context)
      assertEquals(QuickAddStep.AMOUNT, model.state.value.step)
      Thread.sleep(600)
      assertTrue(store.outbox.snapshot().isEmpty())
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
  fun aPersonWhoDoesNotExistYetIsCreatedAndTheDebtDependsOnIt() = runTest {
    TestGraph.signedIn().use { fixture ->
      val store = checkNotNull(fixture.graph.current.value)
      val model = fixture.model()
      model.state.first { it.loaded }

      model.onQueryChange("سامي")
      model.startNewPerson()
      assertEquals(QuickAddStep.NEW_PERSON, model.state.value.step)
      assertEquals("سامي", model.state.value.newName)
      model.createPerson()
      assertEquals(QuickAddStep.AMOUNT, model.state.value.step)

      model.press("7")
      model.press("5")
      model.save(context)
      store.awaitOutbox(2)

      val rows = store.outbox.snapshot()
      assertEquals(
          listOf(CommandType.CONTACT_CREATE, CommandType.DEBT_CREATE),
          rows.map { it.type },
      )
      assertEquals(
          "the debt waits for the contact that carries it",
          listOf(rows.first().operationId),
          rows.last().command.dependsOn,
      )
    }
  }

  @Test
  fun aPhoneAlreadyInTheStoreOffersThatPersonInstead() = runTest {
    TestGraph.signedIn().use { fixture ->
      fixture.settledContact("سارة", phone = "+970599335640")
      val model = fixture.model()
      model.state.first { it.loaded }

      model.startNewPerson()
      model.onNewName("سارة محمود")
      model.onNewPhone("0599335640")

      assertEquals("سارة", model.state.value.duplicate)
      model.useDuplicate()

      assertEquals(QuickAddStep.AMOUNT, model.state.value.step)
      assertEquals("سارة", model.state.value.selected?.name)
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
  fun withoutAnOfflineLeaseThePickerSaysSoBeforeAnyTyping() = runTest {
    TestGraph.signedIn(online = { false }).use { fixture ->
      val model = fixture.model()
      val state = model.state.first { it.loaded }

      assertTrue("the picker must say the save cannot happen now", state.blocked)
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

  private suspend fun TestGraph.limitPermissions(permissions: Map<String, Boolean>) {
    val account = checkNotNull(graph.accounts.accounts.first().firstOrNull())
    checkNotNull(graph.current.value)
    graph.accounts.update(account.storeUserId) {
      it.copy(installation = StoredInstallation("active", 1, permissions))
    }
  }

  /** Short, real windows: the commit runs on the store's scope, not the test dispatcher's clock. */
  private fun TestGraph.model(undoWindowMs: Long = 300): QuickAddViewModel =
      QuickAddViewModel(graph, null, false, SavedStateHandle(), undoWindowMs)

  /** The outbox is written from another coroutine; polling keeps the assertion about the row. */
  private suspend fun com.sadad.app.data.session.StoreScope.awaitOutbox(count: Int) {
    val deadline = System.currentTimeMillis() + 5_000
    while (outbox.snapshot().size < count && System.currentTimeMillis() < deadline) {
      Thread.sleep(10)
    }
    assertEquals(count, outbox.snapshot().size)
  }
}
