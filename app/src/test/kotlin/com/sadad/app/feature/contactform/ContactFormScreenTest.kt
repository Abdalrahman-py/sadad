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
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
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
      model.onFirstDebtChange(true)
      model.onDebtAmountChange("258")
      compose.setContent { SadadTheme { ContactFormScreen(model) } }
      repeat(2) {
        compose
            .onNodeWithText(context.getString(R.string.contactform_save_add))
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
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

  @Test
  fun savingAtTheBottomRevealsTheInvalidDebtAmount() {
    assertSaveReveals(
        configure = { it.onDebtAmountChange("0") },
        fieldLabel = R.string.contactform_debt_amount,
        error = { it.debtError },
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
      model.onFirstDebtChange(true)
      model.onDebtAmountChange("258")
      configure(model)
      compose.setContent { SadadTheme { ContactFormScreen(model) } }
      compose
          .onNodeWithText(context.getString(R.string.contactform_save_add))
          .performScrollTo()
          .performSemanticsAction(SemanticsActions.OnClick) { it() }
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
      model.onFirstDebtChange(true)
      model.onDebtAmountChange("258")
      compose.setContent { SadadTheme { ContactFormScreen(model) } }
      compose.waitForIdle()
      compose
          .onNodeWithText(context.getString(R.string.contactform_save_add))
          .performScrollTo()
          .performSemanticsAction(SemanticsActions.OnClick) { it() }
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
    return ContactFormViewModel(scope.repository, STORE_A, context, SavedStateHandle(), contactId)
  }

  @Test
  fun theReviewDialogShowsTheFormattedAmount() {
    val fixture = runBlocking { TestGraph.signedIn() }
    fixture.use {
      val model = fixture.model()
      model.onNameChange("سامي")
      model.onFirstDebtChange(true)
      model.onDebtAmountChange("150.5")
      model.save()

      compose.setContent { SadadTheme { ContactFormScreen(model) } }

      compose.onNodeWithText("مراجعة الشخص والدين").assertIsDisplayed()
      compose.onNodeWithText("150.5 ₪").assertIsDisplayed()
    }
  }

  @Test
  fun theFirstDebtFieldsHideWhenTheSwitchIsOff() {
    val fixture = runBlocking { TestGraph.signedIn() }
    fixture.use {
      val model = fixture.model()
      compose.setContent { SadadTheme { ContactFormScreen(model) } }

      assertTrue(compose.onAllNodesWithText("مبلغ الدين").fetchSemanticsNodes().isEmpty())

      model.onFirstDebtChange(true)
      compose.waitUntil {
        compose.onAllNodesWithText("مبلغ الدين").fetchSemanticsNodes().isNotEmpty()
      }
      compose.onNodeWithText("مبلغ الدين").assertExists()
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
      val model = fixture.model()
      compose.setContent { SadadTheme { ContactFormScreen(model) } }
      compose
          .onNodeWithContentDescription(context.getString(R.string.voice_draft))
          .performScrollTo()
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
    ShadowSystemClock.advanceBy(Duration.ofSeconds(2))
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
