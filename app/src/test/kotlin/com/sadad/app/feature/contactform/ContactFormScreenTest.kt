package com.sadad.app.feature.contactform

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.speech.RecognizerIntent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.sadad.app.R
import com.sadad.app.feature.auth.MainDispatcherRule
import com.sadad.app.feature.contactpicker.PickedContact
import com.sadad.app.feature.lock.AppLock
import com.sadad.app.testing.STORE_A
import com.sadad.app.testing.TestGraph
import com.sadad.app.ui.UiTestApplication
import com.sadad.app.ui.theme.SadadTheme
import java.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@Config(application = UiTestApplication::class)
class ContactFormScreenTest {
  @get:Rule val compose = createComposeRule()
  @get:Rule val main = MainDispatcherRule()

  private val context: Context = ApplicationProvider.getApplicationContext()

  @Test
  fun savingAtTheBottomBringsTheInvalidPhoneAndItsMessageIntoView() {
    val fixture = runBlocking { TestGraph.signedIn() }
    fixture.use {
      val model = fixture.model()
      model.onNameChange("إبراهيم")
      model.onPhoneChange("123")
      compose.setContent { SadadTheme { ContactFormScreen(model) } }
      repeat(2) {
        compose.onNodeWithText(context.getString(R.string.contactform_save_only)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(checkNotNull(model.state.value.phoneError)).assertIsDisplayed()
        compose.onNodeWithText("123").assertIsDisplayed()
        compose
            .onNodeWithContentDescription(context.getString(R.string.contactform_phone))
            .assertIsFocused()
      }
    }
  }

  @Test
  fun savingAtTheBottomRevealsTheMissingName() {
    assertSaveReveals(
        configure = { it.onNameChange("") },
        fieldLabel = R.string.contactform_name,
        error = { it.nameError },
    )
  }

  private fun assertSaveReveals(
      configure: (ContactFormViewModel) -> Unit,
      fieldLabel: Int,
      error: (ContactFormState) -> String?,
  ) {
    val fixture = runBlocking { TestGraph.signedIn() }
    fixture.use {
      val model = fixture.model()
      model.onNameChange("إبراهيم")
      configure(model)
      compose.setContent { SadadTheme { ContactFormScreen(model) } }
      compose.onNodeWithText(context.getString(R.string.contactform_save_only)).performClick()
      compose.waitForIdle()
      compose.onNodeWithText(checkNotNull(error(model.state.value))).assertIsDisplayed()
      compose.onNodeWithContentDescription(context.getString(fieldLabel)).assertIsFocused()
      runBlocking {
        assertTrue(checkNotNull(fixture.graph.current.value).outbox.snapshot().isEmpty())
      }
    }
  }

  @Test
  fun dismissingTheDuplicateDialogReturnsToThePhoneAndItsError() {
    val fixture = runBlocking { TestGraph.signedIn() }
    fixture.use {
      runBlocking {
        checkNotNull(fixture.graph.current.value)
            .repository
            .addContact("شخص موجود", "0599123456", null, null, null)
            .getOrThrow()
      }
      val model = fixture.model()
      model.onNameChange("إبراهيم")
      model.onPhoneChange("0599123456")
      compose.setContent { SadadTheme { ContactFormScreen(model) } }
      compose.waitForIdle()
      compose.onNodeWithText(context.getString(R.string.contactform_save_only)).performClick()
      compose.waitUntil(5_000) { model.state.value.duplicate != null }
      compose
          .onNodeWithText(context.getString(R.string.contactform_back_to_edit))
          .performSemanticsAction(SemanticsActions.OnClick) { it() }
      compose.waitForIdle()
      compose.onNodeWithText(checkNotNull(model.state.value.phoneError)).assertIsDisplayed()
      compose
          .onNodeWithContentDescription(context.getString(R.string.contactform_phone))
          .assertIsFocused()
    }
  }

  private fun TestGraph.model(contactId: String? = null): ContactFormViewModel {
    val scope = checkNotNull(graph.current.value) { "the fixture has no open store" }
    return ContactFormViewModel(
        scope.repository,
        scope.personLedger,
        STORE_A,
        context,
        SavedStateHandle(),
        contactId,
    )
  }

  @Test
  fun addModeShowsTheThreePersonFieldsAndBothButtonsAndNothingElse() {
    val fixture = runBlocking { TestGraph.signedIn() }
    fixture.use {
      val model = fixture.model()
      compose.setContent { SadadTheme { ContactFormScreen(model) } }
      compose.waitForIdle()
      // The name, the phone with its code chip, and the two kind segments.
      compose
          .onNodeWithText(context.getString(R.string.contactform_name))
          .performScrollTo()
          .assertIsDisplayed()
      compose
          .onNodeWithText(context.getString(R.string.contactform_prefix))
          .performScrollTo()
          .assertIsDisplayed()
      compose
          .onNodeWithText(context.getString(R.string.contactform_phone))
          .performScrollTo()
          .assertIsDisplayed()
      compose
          .onNodeWithText(context.getString(R.string.contactform_kind_customer))
          .performScrollTo()
          .assertIsDisplayed()
      compose
          .onNodeWithText(context.getString(R.string.contactform_kind_supplier))
          .performScrollTo()
          .assertIsDisplayed()
      // The contacts button above the fields and the two buttons pinned at their end.
      compose
          .onNodeWithText(context.getString(R.string.contactform_pick_contacts))
          .performScrollTo()
          .assertIsDisplayed()
      compose
          .onNodeWithText(context.getString(R.string.contactform_save_record))
          .assertIsDisplayed()
      compose.onNodeWithText(context.getString(R.string.contactform_save_only)).assertIsDisplayed()
      // Nothing else from the busy form stays in add mode.
      compose.onNodeWithText(context.getString(R.string.contactform_category)).assertDoesNotExist()
      compose.onNodeWithText(context.getString(R.string.contactform_note)).assertDoesNotExist()
      compose.onNodeWithText("تسجيل دين عند إضافة الشخص").assertDoesNotExist()
      compose.onNodeWithText("مبلغ الدين").assertDoesNotExist()
      assertEquals(
          0,
          compose
              .onAllNodesWithContentDescription(context.getString(R.string.voice_draft))
              .fetchSemanticsNodes()
              .size,
      )
    }
  }

  @Test
  fun editModeKeepsTheCategoryNoteAndVoiceAndHasOneSaveButton() {
    val fixture = runBlocking { TestGraph.signedIn() }
    fixture.use {
      var existing: String? = null
      runBlocking {
        fixture.seed { existing = addContact("نمر", null, null, null, null).getOrThrow() }
        fixture.settleAll()
      }

      val model = fixture.model(contactId = existing)
      compose.setContent { SadadTheme { ContactFormScreen(model) } }
      compose.waitUntil(timeoutMillis = 10_000) {
        model.state.value.name == "نمر"
      }
      compose
          .onNodeWithText(context.getString(R.string.contactform_category))
          .performScrollTo()
          .assertIsDisplayed()
      compose
          .onNodeWithText(context.getString(R.string.contactform_note))
          .performScrollTo()
          .assertIsDisplayed()
      // The voice button needs a speech recognizer; withVoiceForm covers it in edit mode.
      compose.onNodeWithText(context.getString(R.string.contactform_save_edit)).assertIsDisplayed()
      compose.onNodeWithText(context.getString(R.string.contactform_save_only)).assertDoesNotExist()
      compose
          .onNodeWithText(context.getString(R.string.contactform_save_record))
          .assertDoesNotExist()
    }
  }

  @Test
  fun savingAndRecordingEndsOnTheQuickAddInsteadOfThePersonsPage() {
    val fixture = runBlocking { TestGraph.signedIn() }
    fixture.use {
      var recorded: String? = null
      var opened = 0
      val model = fixture.model()
      model.onNameChange("سامي")
      model.onPhoneChange("0599123456")
      compose.setContent {
        SadadTheme {
          ContactFormScreen(
              model,
              onOpenContact = { opened += 1 },
              onRecord = { recorded = it },
          )
        }
      }
      compose.waitForIdle()
      compose.onNodeWithText(context.getString(R.string.contactform_save_record)).performClick()
      compose.waitUntil(timeoutMillis = 10_000) { recorded != null }
      assertEquals(0, opened)
      assertTrue(model.state.value.record)
    }
  }

  @Test
  fun savingOnlyEndsOnThePersonsPage() {
    val fixture = runBlocking { TestGraph.signedIn() }
    fixture.use {
      var opened: String? = null
      var recorded = 0
      val model = fixture.model()
      model.onNameChange("سامي")
      model.onPhoneChange("0599123456")
      compose.setContent {
        SadadTheme {
          ContactFormScreen(
              model,
              onOpenContact = { opened = it },
              onRecord = { recorded += 1 },
          )
        }
      }
      compose.waitForIdle()
      compose.onNodeWithText(context.getString(R.string.contactform_save_only)).performClick()
      compose.waitUntil(timeoutMillis = 10_000) { opened != null }
      assertEquals(0, recorded)
    }
  }

  @Test
  fun cancellingAMicrophonePressDoesNotSkipTheNextLock() {
    withVoiceForm { lock ->
      compose
          .onNodeWithContentDescription(context.getString(R.string.voice_draft))
          .performTouchInput {
            down(center)
            cancel()
          }

      leaveAndReturn(lock)

      assertTrue(lock.locked.value)
    }
  }

  @Test
  fun accessibilityVoiceLaunchSkipsExactlyOneLock() {
    withVoiceForm { lock ->
      compose
          .onNodeWithContentDescription(context.getString(R.string.voice_draft))
          .performSemanticsAction(SemanticsActions.OnClick) { it() }

      leaveAndReturn(lock)
      assertFalse(lock.locked.value)

      leaveAndReturn(lock)
      assertTrue(lock.locked.value)
    }
  }

  /** Voice stays on the edit form: add mode has no notes field to narrate into. */
  private fun withVoiceForm(check: (AppLock) -> Unit) {
    val recognizer =
        ResolveInfo().apply {
          activityInfo =
              ActivityInfo().apply {
                packageName = "test.speech"
                name = "test.speech.Recognizer"
              }
        }
    shadowOf(context.packageManager)
        .addResolveInfoForIntent(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH),
            recognizer,
        )
    val lock = AppLock.get(context)
    lock.appStarted()
    lock.markUnlocked()
    val fixture = runBlocking { TestGraph.signedIn() }
    fixture.use {
      var existing: String? = null
      runBlocking {
        fixture.seed { existing = addContact("سامي", null, null, null, null).getOrThrow() }
        fixture.settleAll()
      }
      val model = fixture.model(contactId = existing)
      compose.setContent { SadadTheme { ContactFormScreen(model) } }
      compose.waitUntil(timeoutMillis = 10_000) {
        compose
            .onAllNodesWithContentDescription(context.getString(R.string.voice_draft))
            .fetchSemanticsNodes()
            .isNotEmpty()
      }
      check(lock)
    }
  }

  @Test
  fun aPickedExistingPersonOpensItsPage() {
    val fixture = runBlocking { TestGraph.signedIn() }
    fixture.use {
      var opened: String? = null
      var trashed = false
      var delivered = 0
      compose.setContent {
        SadadTheme {
          ContactFormScreen(
              fixture.model(),
              picked = PickedContact.Existing("p5"),
              onPickDelivered = { delivered += 1 },
              onOpenContact = { opened = it },
              onOpenTrash = { trashed = true },
          )
        }
      }
      compose.waitUntil(timeoutMillis = 10_000) { opened != null && delivered == 1 }
      assertEquals("p5", opened)
      assertFalse(trashed)
    }
  }

  @Test
  fun aPickedArchivedPersonOpensTheTrash() {
    val fixture = runBlocking { TestGraph.signedIn() }
    fixture.use {
      var opened: String? = null
      var trashed = false
      compose.setContent {
        SadadTheme {
          ContactFormScreen(
              fixture.model(),
              picked = PickedContact.Archived("p9"),
              onPickDelivered = {},
              onOpenContact = { opened = it },
              onOpenTrash = { trashed = true },
          )
        }
      }
      compose.waitUntil(timeoutMillis = 10_000) { trashed }
      assertTrue(trashed)
      assertNull(opened)
    }
  }

  private fun leaveAndReturn(lock: AppLock) {
    lock.appStopped()
    ShadowSystemClock.advanceBy(Duration.ofSeconds(61))
    lock.appStarted()
  }

  @Test
  fun theContactsButtonShowsOnAdd() {
    val fixture = runBlocking { TestGraph.signedIn() }
    fixture.use {
      val model = fixture.model()
      compose.setContent { SadadTheme { ContactFormScreen(model) } }
      compose
          .onNodeWithText(context.getString(R.string.contactform_pick_contacts))
          .assertIsDisplayed()
    }
  }

  @Test
  fun theContactsButtonIsAbsentOnEdit() {
    val fixture = runBlocking { TestGraph.signedIn() }
    fixture.use {
      var existing: String? = null
      runBlocking {
        fixture.seed { existing = addContact("نمر", null, null, null, null).getOrThrow() }
        fixture.settleAll()
      }

      val model = fixture.model(contactId = existing)
      compose.setContent { SadadTheme { ContactFormScreen(model) } }
      compose
          .onNodeWithText(context.getString(R.string.contactform_pick_contacts))
          .assertDoesNotExist()
    }
  }
}
