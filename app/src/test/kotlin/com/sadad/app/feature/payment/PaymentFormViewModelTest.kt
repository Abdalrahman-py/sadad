package com.sadad.app.feature.payment

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.sadad.app.R
import com.sadad.app.data.session.StoredInstallation
import com.sadad.app.feature.auth.MainDispatcherRule
import com.sadad.app.testing.STORE_A
import com.sadad.app.testing.TestGraph
import com.sadad.app.ui.UiTestApplication
import com.sadad.ledger.Cents
import com.sadad.ledger.CommandType
import com.sadad.ledger.Direction
import com.sadad.ledger.PaymentCreate
import com.sadad.ledger.PaymentMethod
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = UiTestApplication::class)
class PaymentFormViewModelTest {
  @get:Rule val main = MainDispatcherRule()
  private val context: Context = ApplicationProvider.getApplicationContext()

  @Test
  fun anAmountOverTheOldestDebtQueuesTwoPartsOldestFirst() = runTest {
    TestGraph.signedIn().use { fixture ->
      val contact = fixture.settledContact()
      val older = fixture.openDebt(contact, 10_000)
      val newer = fixture.openDebt(contact, 5_000)
      val store = checkNotNull(fixture.graph.current.value)
      val model = fixture.model(contact)
      model.state.first { it.loaded && it.openBalance == Cents(15_000) }

      model.onAmountChange("120")
      model.save(context)

      val review = checkNotNull(model.state.value.review)
      assertEquals(Cents(12_000), review.amount)
      assertEquals(
          listOf(older to Cents(10_000), newer to Cents(2_000)),
          review.parts.map { part -> part.debtId to part.amount },
      )
      assertTrue(store.outbox.snapshot().isEmpty())

      model.confirm(context)
      model.state.first { it.saved }

      val rows = store.outbox.snapshot()
      assertTrue(rows.all { it.type == CommandType.PAYMENT_CREATE })
      val payloads = rows.map { it.command.payload() as PaymentCreate }
      assertEquals(listOf(Cents(10_000), Cents(2_000)), payloads.map { it.amountCents })
      assertEquals(listOf(older, newer), payloads.map { it.debtId })
      assertTrue(payloads.all { it.method == PaymentMethod.CASH })
    }
  }

  @Test
  fun anAmountAboveTheOpenBalanceShowsTheTextAndQueuesNothing() = runTest {
    TestGraph.signedIn().use { fixture ->
      val contact = fixture.settledContact()
      fixture.openDebt(contact, 10_000)
      fixture.openDebt(contact, 5_000)
      val store = checkNotNull(fixture.graph.current.value)
      val model = fixture.model(contact)
      model.state.first { it.loaded && it.openBalance == Cents(15_000) }

      model.onAmountChange("151")
      model.save(context)

      assertEquals(context.getString(R.string.payment_over_balance), model.state.value.error)
      assertNull(model.state.value.review)
      assertTrue(store.outbox.snapshot().isEmpty())
    }
  }

  @Test
  fun zeroIsRefusedWithTheAmountText() = runTest {
    TestGraph.signedIn().use { fixture ->
      val contact = fixture.settledContact()
      fixture.openDebt(contact, 10_000)
      val store = checkNotNull(fixture.graph.current.value)
      val model = fixture.model(contact)
      model.state.first { it.loaded && it.openBalance == Cents(10_000) }

      model.onAmountChange("0")
      model.save(context)

      assertEquals(context.getString(R.string.payment_amount_invalid), model.state.value.error)
      assertNull(model.state.value.review)
      assertTrue(store.outbox.snapshot().isEmpty())
    }
  }

  @Test
  fun theChosenMethodTravelsInTheQueuedParts() = runTest {
    TestGraph.signedIn().use { fixture ->
      val contact = fixture.settledContact()
      fixture.openDebt(contact, 10_000)
      val store = checkNotNull(fixture.graph.current.value)
      val model = fixture.model(contact)
      model.state.first { it.loaded && it.openBalance == Cents(10_000) }

      model.onMethodChange(PaymentMethod.WALLET_PALPAY)
      model.onAmountChange("40")
      model.save(context)
      assertEquals(PaymentMethod.WALLET_PALPAY, checkNotNull(model.state.value.review).method)
      model.confirm(context)
      model.state.first { it.saved }

      val payload = store.outbox.snapshot().single().command.payload() as PaymentCreate
      assertEquals(PaymentMethod.WALLET_PALPAY, payload.method)
      assertEquals(Cents(4_000), payload.amountCents)
    }
  }

  @Test
  fun aDeviceWithoutThePaymentPermissionStaysRestricted() = runTest {
    TestGraph.signedIn().use { fixture ->
      val contact = fixture.settledContact()
      fixture.openDebt(contact, 10_000)
      fixture.graph.accounts.update(STORE_A) {
        it.copy(
            installation =
                StoredInstallation(
                    status = "active",
                    generation = 1,
                    permissions = mapOf("registerPayments" to false),
                )
        )
      }
      val store = checkNotNull(fixture.graph.current.value)
      val model = fixture.model(contact)
      val state = model.state.first { it.restricted }
      assertEquals(Cents(10_000), state.openBalance)

      model.onAmountChange("40")
      model.save(context)
      model.confirm(context)

      assertNull(model.state.value.review)
      assertFalse(model.state.value.saved)
      assertTrue(store.outbox.snapshot().isEmpty())
    }
  }

  @Test
  fun theFieldsSurviveARecreatedViewModel() = runTest {
    TestGraph.signedIn().use { fixture ->
      val contact = fixture.settledContact()
      fixture.openDebt(contact, 10_000)
      val saved = SavedStateHandle()
      val first = PaymentFormViewModel(fixture.graph, contact, saved)
      first.state.first { it.loaded }
      first.onAmountChange("40")
      first.onMethodChange(PaymentMethod.BANK)
      first.onNoteChange("بضاعة")

      val recreated = PaymentFormViewModel(fixture.graph, contact, saved)
      val state = recreated.state.first { it.loaded }

      assertEquals("40", state.amount)
      assertEquals(PaymentMethod.BANK, state.method)
      assertEquals("بضاعة", state.note)
    }
  }

  private suspend fun TestGraph.settledContact(): String =
      checkNotNull(graph.current.value)
          .repository
          .addContact("سامي", PHONE, "عميل", null, null)
          .getOrThrow()
          .also { settleAll() }

  /** A settled receivable Debt on [contactId], queued oldest first by the caller's call order. */
  private suspend fun TestGraph.openDebt(contactId: String, cents: Long): String =
      checkNotNull(graph.current.value)
          .repository
          .addDebt(contactId, Direction.RECEIVABLE, Cents(cents), null, null)
          .getOrThrow()
          .also { settleAll() }

  private fun TestGraph.model(contactId: String): PaymentFormViewModel =
      PaymentFormViewModel(graph, contactId, SavedStateHandle())

  private companion object {
    const val PHONE = "+970****3456"
  }
}
