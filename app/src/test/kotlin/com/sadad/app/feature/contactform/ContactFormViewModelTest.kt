package com.sadad.app.feature.contactform

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.sadad.app.data.ledger.FakeSession
import com.sadad.app.data.ledger.LedgerRepository
import com.sadad.app.data.ledger.OutboxRow
import com.sadad.app.data.ledger.PersonLedger
import com.sadad.app.feature.auth.MainDispatcherRule
import com.sadad.app.feature.contactpicker.PickedContact
import com.sadad.app.testing.STORE_A
import com.sadad.app.testing.TestGraph
import com.sadad.app.ui.UiTestApplication
import com.sadad.ledger.CommandType
import com.sadad.ledger.ContactCreate
import com.sadad.ledger.ContactKind
import com.sadad.ledger.ContactUpdate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
class ContactFormViewModelTest {
  @Test
  fun aSuggestedNameDoesNotOverwriteTheRestoredDraft() = runTest {
    TestGraph.signedIn().use { fixture ->
      val store = checkNotNull(fixture.graph.current.value)
      val model =
          ContactFormViewModel(
              store.repository,
              store.personLedger,
              STORE_A,
              context,
              SavedStateHandle(mapOf("name" to "إبراهيم أحمد")),
              initialName = "إبراهيم",
          )
      assertEquals("إبراهيم أحمد", model.state.value.name)
    }
  }

  @Test
  fun theShortPhoneFromTheReportedCaseCannotCreateAPerson() = runTest {
    TestGraph.signedIn().use { fixture ->
      val store = checkNotNull(fixture.graph.current.value)
      val model =
          ContactFormViewModel(
              store.repository,
              store.personLedger,
              STORE_A,
              context,
              SavedStateHandle(),
              initialName = "إبراهيم",
          )
      model.onPhoneChange("059669696")
      model.saveOnly()
      assertNotNull(model.state.value.phoneError)
      assertNull(model.state.value.savedContactId)
      assertTrue(fixture.rows().isEmpty())
    }
  }

  @get:Rule val main = MainDispatcherRule()

  private val context: Context = ApplicationProvider.getApplicationContext()

  private fun TestGraph.model(
      contactId: String? = null,
      saved: SavedStateHandle = SavedStateHandle(),
  ): ContactFormViewModel {
    val scope = checkNotNull(graph.current.value) { "the fixture has no open store" }
    return ContactFormViewModel(
        scope.repository,
        scope.personLedger,
        STORE_A,
        context,
        saved,
        contactId,
    )
  }

  private suspend fun TestGraph.rows(): List<OutboxRow> =
      checkNotNull(graph.current.value).outbox.snapshot()

  @Test
  fun aBlankNameIsRefusedAndNothingIsQueued() = runTest {
    TestGraph.signedIn().use { fixture ->
      val model = fixture.model()
      model.onNameChange("   ")
      model.onPhoneChange("0599123456")

      model.saveOnly()

      assertNotNull(model.state.value.nameError)
      assertTrue(fixture.rows().isEmpty())
    }
  }

  @Test
  fun aNameOverOneHundredAndTwentyCharactersIsRefused() = runTest {
    TestGraph.signedIn().use { fixture ->
      val model = fixture.model()
      model.onNameChange("س".repeat(121))

      model.saveOnly()

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

      model.saveOnly()
      model.state.first { it.savedContactId != null }

      val contact = fixture.rows().single { it.type == CommandType.CONTACT_CREATE }
      assertEquals("0599123456", (contact.command.payload() as ContactCreate).phone)
      assertEquals("970", (contact.command.payload() as ContactCreate).whatsappCode)
    }
  }

  @Test
  fun theChosenCodeIsSentApartFromTheTenDigits() = runTest {
    TestGraph.signedIn().use { fixture ->
      val model = fixture.model()
      model.onNameChange("سامي")
      model.onPrefixChange("972")
      model.onPhoneChange("+972 599 123 456")

      model.saveOnly()
      model.state.first { it.savedContactId != null }

      val payload =
          fixture.rows().single { it.type == CommandType.CONTACT_CREATE }.command.payload()
              as ContactCreate
      assertEquals("0599123456", payload.phone)
      assertEquals("972", payload.whatsappCode)
    }
  }

  @Test
  fun aTypedCodeThatDisagreesWithTheChipIsRefused() = runTest {
    TestGraph.signedIn().use { fixture ->
      val model = fixture.model()
      model.onNameChange("سامي")
      model.onPhoneChange("+972599123456")

      model.saveOnly()

      assertEquals("مقدمة الرقم لا تطابق الاختيار.", model.state.value.phoneError)
      assertTrue(fixture.rows().isEmpty())
    }
  }

  @Test
  fun aShortPhoneShowsTheWhatsAppPhoneError() = runTest {
    TestGraph.signedIn().use { fixture ->
      val model = fixture.model()
      model.onNameChange("سامي")
      model.onPhoneChange("12345")

      model.saveOnly()

      assertEquals(
          "أدخل رقم جوال من 10 أرقام يبدأ بـ05.",
          model.state.value.phoneError,
      )
      assertTrue(fixture.rows().isEmpty())
    }
  }

  @Test
  fun savingAndRecordingQueuesOnlyTheContactWithNoDebtAndMarksTheRecordEnding() = runTest {
    TestGraph.signedIn().use { fixture ->
      val model = fixture.model()
      model.onNameChange("سامي")
      model.onPhoneChange("0599123456")

      model.saveAndRecord()
      model.state.first { it.savedContactId != null }

      val state = model.state.value
      assertTrue(state.record)
      val rows = fixture.rows()
      assertEquals(listOf(CommandType.CONTACT_CREATE), rows.map { it.type })
    }
  }

  @Test
  fun savingOnlyQueuesTheContactAndKeepsThePersonsPageEnding() = runTest {
    TestGraph.signedIn().use { fixture ->
      val model = fixture.model()
      model.onNameChange("سامي")
      model.onPhoneChange("0599123456")

      model.saveOnly()
      val state = model.state.first { it.savedContactId != null }

      assertFalse(state.record)
      val rows = fixture.rows()
      assertEquals(listOf(CommandType.CONTACT_CREATE), rows.map { it.type })
    }
  }

  @Test
  fun aDuplicatePhoneOffersThatContactAndQueuesNothing() = runTest {
    TestGraph.signedIn().use { fixture ->
      var existing: String? = null
      fixture.seed {
        existing = addContact("سامي", "0599123456", "عميل", null, null).getOrThrow()
      }

      val model = fixture.model()
      model.onNameChange("شخص آخر")
      model.onPhoneChange("0599123456")
      model.saveOnly()
      model.state.first { it.duplicate != null || it.savedContactId != null }

      assertEquals(existing, model.state.value.duplicate?.contactId)
      assertNull(model.state.value.savedContactId)
      assertEquals(1, fixture.rows().size)
    }
  }

  /** The queued, not-yet-confirmed contact also blocks a new person with its phone. */
  @Test
  fun aDuplicatePhoneAmongQueuedContactCreatesStillShowsTheDialog() = runTest {
    TestGraph.signedIn().use { fixture ->
      var queued: String? = null
      fixture.seed { queued = addContact("سامي", "0599123456", "عميل", null, null).getOrThrow() }

      val model = fixture.model()
      model.onNameChange("شخص آخر")
      model.onPhoneChange("0599123456")
      model.saveOnly()
      model.state.first { it.duplicate != null || it.savedContactId != null }

      assertEquals(queued, model.state.value.duplicate?.contactId)
      assertNull(model.state.value.savedContactId)
      assertEquals(1, fixture.rows().size)
    }
  }

  /** The stored, settled contact blocks the form too: the Room query alone must find them. */
  @Test
  fun aDuplicatePhoneAmongConfirmedStoredContactsShowsTheDialog() = runTest {
    TestGraph.signedIn().use { fixture ->
      var stored: String? = null
      fixture.seed { stored = addContact("سامي", "0599123456", "عميل", null, null).getOrThrow() }
      fixture.settleAll()

      val model = fixture.model()
      model.onNameChange("شخص آخر")
      model.onPhoneChange("0599123456")
      model.saveOnly()
      model.state.first { it.duplicate != null || it.savedContactId != null }

      assertEquals(stored, model.state.value.duplicate?.contactId)
      assertEquals("سامي", model.state.value.duplicate?.name)
      assertNull(model.state.value.savedContactId)
      assertTrue("nothing new queued", fixture.rows().isEmpty())
    }
  }

  /** A queued archive takes the person out of the duplicate check before the server answers. */
  @Test
  fun aQueuedArchiveRemovesTheDuplicate() = runTest {
    TestGraph.signedIn().use { fixture ->
      var archived: String? = null
      fixture.seed { archived = addContact("سامي", "0599123456", "عميل", null, null).getOrThrow() }
      fixture.settleAll()
      fixture.seed {
        archiveContact(checkNotNull(archived), "حذف الزبون من التطبيق").getOrThrow()
      }

      val model = fixture.model()
      model.onNameChange("شخص آخر")
      model.onPhoneChange("0599123456")
      model.saveOnly()
      model.state.first { it.duplicate != null || it.savedContactId != null }

      assertNull(model.state.value.duplicate)
      assertNotNull(model.state.value.savedContactId)
    }
  }

  /** The archive keeps the person out of the form's duplicate checks, as the view does today. */
  @Test
  fun anArchivedContactWithThePhoneDoesNotOfferIt() = runTest {
    TestGraph.signedIn().use { fixture ->
      var archived: String? = null
      fixture.seed { archived = addContact("سامي", "0599123456", "عميل", null, null).getOrThrow() }
      fixture.settleAll()
      fixture.seed {
        archiveContact(checkNotNull(archived), "حذف الزبون من التطبيق").getOrThrow()
      }
      fixture.settleAll()

      val model = fixture.model()
      model.onNameChange("شخص آخر")
      model.onPhoneChange("0599123456")
      model.saveOnly()
      model.state.first { it.savedContactId != null || it.message != null }

      assertNull(model.state.value.duplicate)
      assertNotNull(model.state.value.savedContactId)
    }
  }

  /**
   * A queued edit that moves the person to another phone must not offer them on their old phone:
   * the check reads the queued edit's own payload, not the last committed row.
   */
  @Test
  fun aQueuedPhoneChangeAwayFromThePhoneDoesNotOfferTheContact() = runTest {
    TestGraph.signedIn().use { fixture ->
      var existing: String? = null
      fixture.seed { existing = addContact("سامي", "0599123456", "عميل", null, null).getOrThrow() }
      fixture.settleAll()
      fixture.seed {
        updateContact(
                checkNotNull(existing),
                "سامي",
                "0599123457",
                "عميل",
                null,
            )
            .getOrThrow()
      }

      val model = fixture.model()
      model.onNameChange("شخص آخر")
      model.onPhoneChange("0599123456")
      model.saveOnly()
      model.state.first { it.savedContactId != null || it.message != null }

      assertNull(model.state.value.duplicate)
      assertNotNull(model.state.value.savedContactId)
    }
  }

  /** An edit of the person themselves never quarrels with their own phone. */
  @Test
  fun theFormsOwnContactDoesNotOfferItself() = runTest {
    TestGraph.signedIn().use { fixture ->
      var existing: String? = null
      fixture.seed { existing = addContact("سامي", "0599123456", "عميل", null, null).getOrThrow() }
      fixture.settleAll()

      val model = fixture.model(contactId = existing)
      model.state.first { it.name == "سامي" }
      model.onNameChange("سامي الجديد")
      assertEquals("0599123456", model.state.value.phone)
      model.save()
      model.state.first { it.savedContactId != null }

      assertNull(model.state.value.duplicate)
      val update = fixture.rows().single { it.type == CommandType.CONTACT_UPDATE }
      assertEquals(
          "0599123456",
          (update.command.payload() as com.sadad.ledger.ContactUpdate).phone,
      )
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
      first.onKindChange(ContactKind.SUPPLIER)
      first.onCategoryChange("مورد")
      first.onNoteChange("ملاحظة")

      val second = fixture.model(saved = saved)

      with(second.state.value) {
        assertEquals("سامي", name)
        assertEquals("972", prefix)
        assertEquals("0501234567", phone)
        assertEquals(ContactKind.SUPPLIER, kind)
        assertEquals("مورد", category)
        assertEquals("ملاحظة", note)
      }
    }
  }

  @Test
  fun editModeSavesDirectlyWithNoReviewAndQueuesTheUpdateWithTheViewsVersion() = runTest {
    TestGraph.signedIn().use { fixture ->
      var existing: String? = null
      fixture.seed {
        existing =
            addContact("سامي", "0599123456", "عميل", null, null, whatsappCode = "972").getOrThrow()
      }
      fixture.settleAll()
      val store = checkNotNull(fixture.graph.current.value)
      val model = fixture.model(contactId = existing)
      model.state.first { it.name == "سامي" }
      assertEquals("0599123456", model.state.value.phone)
      assertEquals("972", model.state.value.prefix)
      model.onNameChange("سامي الجديد")
      model.save()

      val state = model.state.first { it.savedContactId != null }
      assertFalse(state.busy)
      val rows = fixture.rows()
      val update = rows.single { it.type == CommandType.CONTACT_UPDATE }
      // The queued version names the stored Contact's version, read from Room (ticket 168).
      val version = checkNotNull(store.database.contactDao().byId(checkNotNull(existing))).version
      assertEquals(version, update.command.expectedVersion)
      assertEquals("سامي الجديد", (update.command.payload() as ContactUpdate).name)
      assertEquals("0599123456", (update.command.payload() as ContactUpdate).phone)
      assertEquals("972", (update.command.payload() as ContactUpdate).whatsappCode)
    }
  }

  @Test
  fun aRecreatedEditFormKeepsWhatTheUserTyped() = runTest {
    TestGraph.signedIn().use { fixture ->
      var existing: String? = null
      fixture.seed {
        existing = addContact("سامي", "0599123456", "عميل", null, null).getOrThrow()
      }
      fixture.settleAll()
      val saved = SavedStateHandle()
      val first = fixture.model(contactId = existing, saved = saved)
      first.state.first { it.name == "سامي" }
      first.onNameChange("سامي الجديد")

      val second = fixture.model(contactId = existing, saved = saved)

      assertEquals("سامي الجديد", second.state.value.name)
      assertEquals("0599123456", second.state.value.phone)
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

      val model =
          ContactFormViewModel(
              offline,
              PersonLedger(scope.store, scope.outbox, session),
              STORE_A,
              context,
              SavedStateHandle(),
              existing,
          )
      model.state.first { it.name == "سامي" }
      model.onNameChange("سامي الجديد")
      model.save()

      val state = model.state.first { !it.busy && it.message != null }
      assertEquals("لا يمكن الحفظ بلا اتصال الآن؛ اتصل بالإنترنت.", state.message)
      assertTrue(fixture.rows().isEmpty())
    }
  }

  @Test
  fun savingAnEditOfASupplierSendsItsKind() = runTest {
    TestGraph.signedIn().use { fixture ->
      var existing: String? = null
      fixture.seed {
        existing =
            addContact("شركة النور", null, "مورد", null, null, kind = ContactKind.SUPPLIER)
                .getOrThrow()
      }
      fixture.settleAll()

      val model = fixture.model(contactId = existing)
      model.state.first { it.kind == ContactKind.SUPPLIER }
      model.onNameChange("شركة النور الجديدة")
      model.save()
      model.state.first { it.savedContactId != null }

      val update = fixture.rows().single { it.type == CommandType.CONTACT_UPDATE }
      assertEquals(ContactKind.SUPPLIER, (update.command.payload() as ContactUpdate).kind)
    }
  }

  @Test
  fun creatingAContactSendsTheChosenKind() = runTest {
    TestGraph.signedIn().use { fixture ->
      val model = fixture.model()
      model.onNameChange("مخبز الأمل")
      model.onKindChange(ContactKind.SUPPLIER)

      model.saveOnly()
      model.state.first { it.savedContactId != null }

      val contact = fixture.rows().single { it.type == CommandType.CONTACT_CREATE }
      assertEquals(ContactKind.SUPPLIER, (contact.command.payload() as ContactCreate).kind)
    }
  }

  @Test
  fun aPickedContactFillsTheNamePhoneAndCode() = runTest {
    TestGraph.signedIn().use { fixture ->
      val model = fixture.model()

      model.onPicked(PickedContact.New("سامي", "0599123456", "972"))

      val state = model.state.value
      assertEquals("سامي", state.name)
      assertEquals("0599123456", state.phone)
      assertEquals("972", state.prefix)
      assertNull(state.openContactId)
      assertFalse(state.openTrash)
    }
  }

  @Test
  fun aPickedNameOnlyContactLeavesThePhoneEmptyOnTheDefaultCode() = runTest {
    TestGraph.signedIn().use { fixture ->
      val model = fixture.model()

      model.onPicked(PickedContact.New("نمر", null, "970"))

      val state = model.state.value
      assertEquals("نمر", state.name)
      assertEquals("", state.phone)
      assertEquals("970", state.prefix)
      assertNull(state.phoneError)
    }
  }

  @Test
  fun anExistingPickOpensThatPersonAndAnArchivedPickOpensTheTrash() = runTest {
    TestGraph.signedIn().use { fixture ->
      val model = fixture.model()

      model.onPicked(PickedContact.Existing("c9"))
      assertEquals("c9", model.state.value.openContactId)
      assertFalse(model.state.value.openTrash)

      model.onPageHandled()
      assertNull(model.state.value.openContactId)

      model.onPicked(PickedContact.Archived("t1"))
      assertTrue(model.state.value.openTrash)
      assertNull(model.state.value.openContactId)

      model.onPageHandled()
      assertFalse(model.state.value.openTrash)
    }
  }
}
