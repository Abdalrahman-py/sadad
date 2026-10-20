package com.sadad.app.feature.contactform

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.speech.RecognizerIntent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
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
import com.sadad.app.feature.lock.AppLock
import com.sadad.app.testing.STORE_A
import com.sadad.app.testing.TestGraph
import com.sadad.app.ui.UiTestApplication
import com.sadad.app.ui.theme.SadadTheme
import java.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
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

  private fun TestGraph.model(): ContactFormViewModel {
    val scope = checkNotNull(graph.current.value) { "the fixture has no open store" }
    return ContactFormViewModel(scope.repository, STORE_A, context, SavedStateHandle(), null)
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

  private fun leaveAndReturn(lock: AppLock) {
    lock.appStopped()
    ShadowSystemClock.advanceBy(Duration.ofSeconds(2))
    lock.appStarted()
  }
}
