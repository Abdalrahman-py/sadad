package com.sadad.app.feature.contactform

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.sadad.app.data.ledger.FakeSession
import com.sadad.app.data.ledger.LedgerRepository
import com.sadad.app.data.ledger.OutboxRow
import com.sadad.app.feature.auth.MainDispatcherRule
import com.sadad.app.testing.STORE_A
import com.sadad.app.testing.TestGraph
import com.sadad.app.ui.UiTestApplication
import com.sadad.ledger.Cents
import com.sadad.ledger.CommandType
import com.sadad.ledger.ContactCreate
import com.sadad.ledger.ContactUpdate
import com.sadad.ledger.DebtCreate
import com.sadad.ledger.Direction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
class ContactFormViewModelTest {
  @get:Rule val main = MainDispatcherRule()

  private val context: Context = ApplicationProvider.getApplicationContext()

  private fun TestGraph.model(
      contactId: String? = null,
      saved: SavedStateHandle = SavedStateHandle(),
  ): ContactFormViewModel {
    val scope = checkNotNull(graph.current.value) { "the fixture has no open store" }
    return ContactFormViewModel(scope.repository, STORE_A, context, saved, contactId)
  }

  private suspend fun TestGraph.rows(): List<OutboxRow> =
      checkNotNull(graph.current.value).outbox.snapshot()

  @Test
  fun aBlankNameIsRefusedAndNothingIsQueued() = runTest {
    TestGraph.signedIn().use { fixture ->
      val model = fixture.model()
      model.onNameChange("   ")
      model.onPhoneChange("0599123456")

      model.save()

      assertNotNull(model.state.value.nameError)
      assertNull(model.state.value.review)
      assertTrue(fixture.rows().isEmpty())
    }
  }

  @Test
  fun aNameOverOneHundredAndTwentyCharactersIsRefused() = runTest {
    TestGraph.signedIn().use { fixture ->
      val model = fixture.model()
      model.onNameChange("س".repeat(121))

      model.save()

      assertNotNull(model.state.value.nameError)
      assertTrue(fixture.rows().isEmpty())
    }
  }

  @Test
  fun aLocalNumberIsNormalizedWithTheChosenPrefix() = runTest {
    TestGraph.signedIn().use { fixture ->
      val model = fixture.model()
      model.onNameChange("سامي")
      model.onPhoneChange("0599123456")

      model.save()

      assertEquals("+970599123456", model.state.value.review?.phone)
      model.confirm()
      model.state.first { it.savedContactId != null }
      val contact = fixture.rows().single { it.type == CommandType.CONTACT_CREATE }
      assertEquals("+970599123456", (contact.command.payload() as ContactCreate).phone)
    }
  }

  @Test
  fun aShortPhoneShowsTheWhatsAppPhoneError() = runTest {
    TestGraph.signedIn().use { fixture ->
      val model = fixture.model()
      model.onNameChange("سامي")
      model.onPhoneChange("12345")

      model.save()

      assertEquals(
          "أدخل رقم الجوال من 9 أرقام بعد المقدمة، أو 10 أرقام تبدأ بصفر.",
          model.state.value.phoneError,
      )
      assertNull(model.state.value.review)
      assertTrue(fixture.rows().isEmpty())
    }
  }

  @Test
  fun theFirstDebtQueuesOneContactAndOneDebtThatDependsOnIt() = runTest {
    TestGraph.signedIn().use { fixture ->
      val model = fixture.model()
      model.onNameChange("سامي")
      model.onFirstDebtChange(true)
      model.onDebtAmountChange("150.5")
      model.onDebtDueChange("2026-11-01")

      model.save()
      assertNull(
          "nothing is queued before the review is confirmed",
          model.state.value.savedContactId,
      )
      assertTrue(fixture.rows().isEmpty())
      assertEquals(Cents(15_050), model.state.value.review?.amount)

      model.confirm()
      model.state.first { it.savedContactId != null }

      val rows = fixture.rows()
      val contact = rows.single { it.type == CommandType.CONTACT_CREATE }
      val debt = rows.single { it.type == CommandType.DEBT_CREATE }
      val payload = debt.command.payload() as DebtCreate
      assertEquals(15_050L, payload.amountCents.value)
      assertEquals(Direction.RECEIVABLE, payload.direction)
      assertEquals(contact.entityId, payload.contactId)
      assertEquals("2026-11-01", payload.dueDate)
      assertEquals(listOf(contact.operationId), debt.command.dependsOn)
    }
  }

  @Test
  fun aZeroFirstDebtIsRefused() = runTest {
    TestGraph.signedIn().use { fixture ->
      val model = fixture.model()
      model.onNameChange("سامي")
      model.onFirstDebtChange(true)
      model.onDebtAmountChange("0")

      model.save()

      assertEquals("يجب أن يكون مبلغ الدين أكبر من صفر.", model.state.value.debtError)
      assertNull(model.state.value.review)
      assertTrue(fixture.rows().isEmpty())
    }
  }

  @Test
  fun aPhoneAnotherVisibleContactHasOffersThatContactAndQueuesNothing() = runTest {
    TestGraph.signedIn().use { fixture ->
      var existing: String? = null
      fixture.seed {
        existing = addContact("سامي", "+970599123456", "عميل", null, null).getOrThrow()
      }
      val repository = checkNotNull(fixture.graph.current.value).repository
      repository.view.first { view -> view?.contacts?.any { it.id == existing } == true }

      val model = fixture.model()
      model.onNameChange("شخص آخر")
      model.onPhoneChange("0599123456")
      model.save()

      assertEquals(existing, model.state.value.duplicate?.contactId)
      assertNull(model.state.value.review)
      assertEquals(1, fixture.rows().size)
    }
  }

  @Test
  fun everyFieldSurvivesARecreatedViewModelWithTheSameSavedState() = runTest {
    TestGraph.signedIn().use { fixture ->
      val saved = SavedStateHandle()
      val first = fixture.model(saved = saved)
      first.onNameChange("سامي")
      first.onPrefixChange("972")
      first.onPhoneChange("0501234567")
      first.onCategoryChange("مورد")
      first.onNoteChange("ملاحظة")
      first.onFirstDebtChange(true)
      first.onDebtAmountChange("20")
      first.onDebtDueChange("2026-12-01")
      first.onDebtNoteChange("سبب الدين")

      val second = fixture.model(saved = saved)

      with(second.state.value) {
        assertEquals("سامي", name)
        assertEquals("972", prefix)
        assertEquals("0501234567", phone)
        assertEquals("مورد", category)
        assertEquals("ملاحظة", note)
        assertTrue(withFirstDebt)
        assertEquals("20", debtAmount)
        assertEquals("2026-12-01", debtDue)
        assertEquals("سبب الدين", debtNote)
      }
    }
  }

  @Test
  fun editModeQueuesOneContactUpdateWithTheViewsVersion() = runTest {
    TestGraph.signedIn().use { fixture ->
      var existing: String? = null
      fixture.seed {
        existing = addContact("سامي", "+970599123456", "عميل", null, null).getOrThrow()
      }
      fixture.settleAll()
      val repository = checkNotNull(fixture.graph.current.value).repository
      val contact =
          repository.view
              .first { view -> view?.contacts?.any { it.id == existing } == true }!!
              .contacts
              .single { it.id == existing }

      val model = fixture.model(contactId = existing)
      model.state.first { it.name == "سامي" }
      assertEquals("599123456", model.state.value.phone)
      model.onNameChange("سامي الجديد")
      model.save()
      assertTrue(model.state.value.review != null)

      model.confirm()
      model.state.first { it.savedContactId != null }

      val rows = fixture.rows()
      val update = rows.single { it.type == CommandType.CONTACT_UPDATE }
      assertEquals(contact.version, update.command.expectedVersion)
      assertEquals("سامي الجديد", (update.command.payload() as ContactUpdate).name)
    }
  }

  @Test
  fun aRecreatedEditFormKeepsWhatTheUserTyped() = runTest {
    TestGraph.signedIn().use { fixture ->
      var existing: String? = null
      fixture.seed {
        existing = addContact("سامي", "+970599123456", "عميل", null, null).getOrThrow()
      }
      fixture.settleAll()
      val saved = SavedStateHandle()
      val first = fixture.model(contactId = existing, saved = saved)
      first.state.first { it.name == "سامي" }
      first.onNameChange("سامي الجديد")

      val second = fixture.model(contactId = existing, saved = saved)

      assertEquals("سامي الجديد", second.state.value.name)
      assertEquals("599123456", second.state.value.phone)
    }
  }

  @Test
  fun anOfflineEditReturnsOnlineOnlyAndQueuesNothing() = runTest {
    TestGraph.signedIn().use { fixture ->
      var existing: String? = null
      fixture.seed { existing = addContact("سامي", null, "عميل", null, null).getOrThrow() }
      fixture.settleAll()
      val scope = checkNotNull(fixture.graph.current.value)
      val session =
          FakeSession(origin = scope.origin()).apply {
            online = false
            offline = false
          }
      // Joined by the fixture before it closes the store; `backgroundScope` is not joined.
      val offlineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
      fixture.closeWithStore(offlineScope)
      val offline = LedgerRepository(scope.store, scope.outbox, session, offlineScope)
      offline.load()
      offline.view.first { view -> view?.contacts?.any { it.id == existing } == true }

      val model = ContactFormViewModel(offline, STORE_A, context, SavedStateHandle(), existing)
      model.state.first { it.name == "سامي" }
      model.onNameChange("سامي الجديد")
      model.save()
      model.confirm()

      val state = model.state.first { !it.busy && it.message != null }
      assertEquals("لا يمكن الحفظ بلا اتصال الآن؛ اتصل بالإنترنت.", state.message)
      assertTrue(fixture.rows().isEmpty())
    }
  }

  @Test
  fun aRefusalClearsTheReviewAndShowsTheReason() = runTest {
    TestGraph.signedIn().use { fixture ->
      val model = fixture.model()
      model.onNameChange("سامي")
      model.onFirstDebtChange(true)
      model.onDebtAmountChange("abc")

      model.save()

      assertEquals("أدخل مبلغاً صحيحاً.", model.state.value.debtError)
      assertNull(model.state.value.review)
    }
  }
}
