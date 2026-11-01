package com.sadad.app.data.sync

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.sadad.app.data.db.Events
import com.sadad.app.feature.auth.MainDispatcherRule
import com.sadad.app.feature.contactform.ContactFormViewModel
import com.sadad.app.feature.contacts.ContactDetailViewModel
import com.sadad.app.feature.contacts.contactDetailSource
import com.sadad.ledger.Cents
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The first person in an empty local store, through the form and the actual sync engine. */
@RunWith(RobolectricTestRunner::class)
class FirstContactSyncTest {
  @get:Rule val main = MainDispatcherRule()

  @Test
  fun firstContactAndDebtStayVisibleWhenAcceptedAcrossSeparateChangesPages() = runTest {
    verifyFirstContact(null)
  }

  @Test
  fun firstContactWithALocalPhoneIsNormalizedBeforeTheActualSyncRequest() = runTest {
    verifyFirstContact("0599123456")
  }

  private suspend fun verifyFirstContact(phone: String?) {
    SyncFixture.create().use { f ->
      val store = f.scope
      assertTrue(checkNotNull(store.repository.view.first { it != null }).contacts.isEmpty())
      val form =
          ContactFormViewModel(
              store.repository,
              store.account.storeUserId,
              f.test.context,
              SavedStateHandle(),
              initialName = "أول شخص",
          )
      f.test.closeWithStore(form.viewModelScope)
      if (phone != null) form.onPhoneChange(phone)
      form.onFirstDebtChange(true)
      form.onDebtAmountChange("258")
      form.save()
      form.confirm()
      val id = checkNotNull(form.state.first { it.savedContactId != null }.savedContactId)
      val detail = ContactDetailViewModel(f.test.graph.contactDetailSource(), id, f.test.context)
      f.test.closeWithStore(detail.viewModelScope)
      detail.state.first { it.loaded && !it.missing && it.balance == Cents(25_800) }
      val commands = store.outbox.queued()
      assertEquals(2, commands.size)
      assertEquals(
          if (phone == null) null else "+970599123456",
          (commands.first().payload() as com.sadad.ledger.ContactCreate).phone,
      )
      f.server.enqueue(receiptResponse(commands))
      f.page(Events.page(listOf(Events.accepted(commands[0], 1)), cursor = 2).copy(hasMore = true))
      f.page(Events.page(listOf(Events.accepted(commands[1], 2))))
      assertEquals(SyncStatus.Idle, f.engine.pass(store))
      val confirmed =
          store.repository.view.first {
            it?.contacts?.any { person -> person.id == id && !person.pending } == true &&
                it.debts.any { debt -> debt.contactId == id && !debt.pending }
          }!!
      assertEquals(Cents(25_800), confirmed.net(id))
      assertTrue(store.outbox.snapshot().isEmpty())
      val visible = detail.state.first { it.movements.any { movement -> !movement.pending } }
      assertFalse(visible.missing)
      assertNull(visible.syncFailure)
      assertEquals("أول شخص", visible.name)
      val reopened = ContactDetailViewModel(f.test.graph.contactDetailSource(), id, f.test.context)
      f.test.closeWithStore(reopened.viewModelScope)
      assertFalse(reopened.state.first { it.loaded }.missing)
    }
  }
}
