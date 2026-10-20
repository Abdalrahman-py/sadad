package com.sadad.app.feature.debt

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.sadad.app.R
import com.sadad.app.feature.auth.MainDispatcherRule
import com.sadad.app.testing.TestGraph
import com.sadad.app.ui.UiTestApplication
import com.sadad.ledger.Cents
import com.sadad.ledger.CommandType
import com.sadad.ledger.DebtCreate
import com.sadad.ledger.Direction
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
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

@RunWith(RobolectricTestRunner::class)
@Config(application = UiTestApplication::class)
class DebtFormViewModelTest {
  @get:Rule val main = MainDispatcherRule()
  private val context: Context = ApplicationProvider.getApplicationContext()

  @Test
  fun aReviewedDebtQueuesExactReceivableCentsForItsContactOnlyOnce() = runTest {
    TestGraph.signedIn().use { fixture ->
      val contact = fixture.settledContact()
      val store = checkNotNull(fixture.graph.current.value)
      val model = fixture.model(contact)
      model.state.first { it.loaded }
      model.onAmountChange("150.00")
      model.save(context)
      assertEquals(Cents(15_000), model.state.value.review?.amount)
      assertTrue(store.outbox.snapshot().isEmpty())
      model.confirm(context)
      model.state.first { it.saved }
      model.confirm(context)
      val row = store.outbox.snapshot().single()
      assertEquals(CommandType.DEBT_CREATE, row.type)
      val payload = row.command.payload() as DebtCreate
      assertEquals(15_000L, payload.amountCents.value)
      assertEquals(Direction.RECEIVABLE, payload.direction)
      assertEquals(contact, payload.contactId)
      assertTrue(row.command.dependsOn.isEmpty())
    }
  }

  @Test
  fun anAmountOfFiveThousandAsksOnceThenReachesTheReview() = runTest {
    TestGraph.signedIn().use { fixture ->
      val contact = fixture.settledContact()
      val store = checkNotNull(fixture.graph.current.value)
      val model = fixture.model(contact)
      model.state.first { it.loaded }

      model.onAmountChange("5000")
      model.save(context)
      assertEquals(Cents(500_000), model.state.value.anomaly)
      assertNull(model.state.value.review)
      assertTrue(store.outbox.snapshot().isEmpty())

      model.confirmAnomaly()
      assertEquals(Cents(500_000), model.state.value.review?.amount)
      assertNull(model.state.value.anomaly)

      // The same entered value is not asked about twice.
      model.dismissReview()
      model.save(context)
      assertNull(model.state.value.anomaly)
      assertNotNull(model.state.value.review)
      assertTrue(store.outbox.snapshot().isEmpty())
    }
  }

  @Test
  fun anAmountJustUnderFiveThousandGoesStraightToTheReview() = runTest {
    TestGraph.signedIn().use { fixture ->
      val model = fixture.model(fixture.settledContact())
      model.state.first { it.loaded }

      model.onAmountChange("4999.99")
      model.save(context)

      assertNull(model.state.value.anomaly)
      assertEquals(Cents(499_999), model.state.value.review?.amount)
    }
  }

  @Test
  fun emptyZeroAndThreeDecimalsAreRefusedWithTheAmountText() = runTest {
    TestGraph.signedIn().use { fixture ->
      val store = checkNotNull(fixture.graph.current.value)
      val model = fixture.model(fixture.settledContact())
      model.state.first { it.loaded }

      for (refused in listOf("", "0", "12.345")) {
        model.onAmountChange(refused)
        model.save(context)
        assertNull(model.state.value.review)
        assertEquals(context.getString(R.string.debt_amount_invalid), model.state.value.error)
      }
      assertTrue(store.outbox.snapshot().isEmpty())
    }
  }

  @Test
  fun theDueDateAndTheNoteTravelInTheWireFormat() = runTest {
    TestGraph.signedIn().use { fixture ->
      val store = checkNotNull(fixture.graph.current.value)
      val model = fixture.model(fixture.settledContact())
      model.state.first { it.loaded }
      model.onAmountChange("150")
      model.onDueDateChange("2026-11-01")
      model.onNoteChange("  بضاعة  ")

      model.save(context)
      model.confirm(context)
      model.state.first { it.saved }

      val payload = store.outbox.snapshot().single().command.payload() as DebtCreate
      assertEquals("2026-11-01", payload.dueDate)
      assertEquals("بضاعة", payload.note)
    }
  }

  @Test
  fun aPendingContactMakesTheDebtWaitForIt() = runTest {
    TestGraph.signedIn().use { fixture ->
      val contact = fixture.pendingContact()
      val store = checkNotNull(fixture.graph.current.value)
      val model = fixture.model(contact)
      model.state.first { it.loaded && !it.missing }
      model.onAmountChange("150")

      model.save(context)
      model.confirm(context)
      model.state.first { it.saved }

      val rows = store.outbox.snapshot()
      val contactRow = rows.first { it.type == CommandType.CONTACT_CREATE }
      val debtRow = rows.first { it.type == CommandType.DEBT_CREATE }
      assertEquals(listOf(contactRow.operationId), debtRow.command.dependsOn)
    }
  }

  @Test
  fun offlineWithoutALeaseShowsTheOfflineTextAndQueuesNothing() = runTest {
    var online = true
    TestGraph.signedIn(online = { online }).use { fixture ->
      val contact = fixture.settledContact()
      val store = checkNotNull(fixture.graph.current.value)
      val model = fixture.model(contact)
      model.state.first { it.loaded }
      model.onAmountChange("150")
      model.save(context)
      online = false

      model.confirm(context)

      val state = model.state.first { it.error != null }
      assertEquals(context.getString(R.string.debt_offline), state.error)
      assertFalse(state.saved)
      assertTrue(store.outbox.snapshot().isEmpty())
    }
  }

  @Test
  fun theFieldsSurviveARecreatedViewModel() = runTest {
    TestGraph.signedIn().use { fixture ->
      val contact = fixture.settledContact()
      val saved = SavedStateHandle()
      val first = DebtFormViewModel(fixture.graph, contact, saved)
      first.state.first { it.loaded }
      first.onAmountChange("150.00")
      first.onDueDateChange("2026-11-01")
      first.onNoteChange("بضاعة")

      val recreated = DebtFormViewModel(fixture.graph, contact, saved)
      val state = recreated.state.first { it.loaded }

      assertEquals("150.00", state.amount)
      assertEquals("2026-11-01", state.dueDate)
      assertEquals("بضاعة", state.note)
    }
  }

  private suspend fun TestGraph.settledContact(): String =
      checkNotNull(graph.current.value)
          .repository
          .addContact("سامي", PHONE, "عميل", null, null)
          .getOrThrow()
          .also { settleAll() }

  private suspend fun TestGraph.pendingContact(): String =
      checkNotNull(graph.current.value)
          .repository
          .addContact("سامي", PHONE, "عميل", null, null)
          .getOrThrow()

  private fun TestGraph.model(contactId: String): DebtFormViewModel =
      DebtFormViewModel(graph, contactId, SavedStateHandle())

  private companion object {
    const val PHONE = "+970599123456"
  }
}
