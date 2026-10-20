package com.sadad.app.feature.debt

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.sadad.app.feature.auth.MainDispatcherRule
import com.sadad.app.testing.STORE_A
import com.sadad.app.testing.TestGraph
import com.sadad.app.ui.UiTestApplication
import com.sadad.ledger.CommandType
import com.sadad.ledger.DebtCreate
import com.sadad.ledger.Direction
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
      val store = checkNotNull(fixture.graph.current.value)
      val contact = store.repository.addContact("سامي", null, "عميل", null, null).getOrThrow()
      fixture.settleAll()
      val model = DebtFormViewModel(store.repository, STORE_A, context, SavedStateHandle(), contact)
      model.state.first { it.contact != null }
      model.onAmountChange("150.00")
      model.save()
      assertNotNull(model.state.value.review)
      assertTrue(store.outbox.snapshot().isEmpty())
      model.confirm()
      model.state.first { it.saved }
      model.confirm()
      val row = store.outbox.snapshot().single()
      assertEquals(CommandType.DEBT_CREATE, row.type)
      val payload = row.command.payload() as DebtCreate
      assertEquals(15_000L, payload.amountCents.value)
      assertEquals(Direction.RECEIVABLE, payload.direction)
      assertEquals(contact, payload.contactId)
    }
  }
}
