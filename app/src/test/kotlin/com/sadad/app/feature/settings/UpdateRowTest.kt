package com.sadad.app.feature.settings

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.sadad.app.feature.updates.UpdateManifest
import com.sadad.app.feature.updates.UpdateState
import com.sadad.app.ui.UiTestApplication
import com.sadad.app.ui.theme.SadadTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The row that cannot be dismissed: when a release is known it carries its version, and a tap opens
 * the update screen. What is never allowed is the row disappearing because the prompt was snoozed —
 * that is a state property (`UpdateState.prompts` false, `manifest` still set).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = UiTestApplication::class)
class UpdateRowTest {
  @get:Rule val compose = createComposeRule()

  private val manifest =
      UpdateManifest(
          channel = "team",
          versionCode = 57,
          versionName = "dev-17-a1b2c3d-team",
          apk = "Sadad-team.apk",
          sha256 = "a".repeat(64),
          sizeBytes = 5_662_310,
      )

  private fun show(state: UpdateState, onOpen: () -> Unit = {}) {
    compose.setContent {
      SadadTheme(dark = false) { LazyColumn { updateItem(state, onOpen) } }
    }
  }

  @Test
  fun anAvailableReleaseShowsItsVersion() {
    show(UpdateState(phase = UpdateState.Phase.AVAILABLE, manifest = manifest, prompts = true))
    compose.onNodeWithTag(SettingsTags.UPDATE).assertIsDisplayed().performScrollTo()
    compose.onNodeWithText("الإصدار dev-17-a1b2c3d-team متوفر — اضغط للتحديث").assertIsDisplayed()
  }

  @Test
  fun aSnoozedReleaseStillShowsItsVersion() {
    // «لاحقاً» silenced the prompt; the row keeps the release in front of the user.
    show(UpdateState(phase = UpdateState.Phase.AVAILABLE, manifest = manifest, prompts = false))
    compose.onNodeWithText("الإصدار dev-17-a1b2c3d-team متوفر — اضغط للتحديث").assertIsDisplayed()
  }

  @Test
  fun aReadyDownloadSaysItIsReady() {
    show(UpdateState(phase = UpdateState.Phase.READY, manifest = manifest, progress = 100))
    compose.onNodeWithText("الإصدار dev-17-a1b2c3d-team جاهز للتثبيت").assertIsDisplayed()
  }

  @Test
  fun withoutAnUpdateTheRowOffersACheck() {
    show(UpdateState())
    compose.onNodeWithText("تحقق من وجود إصدار أحدث").assertIsDisplayed()
  }

  @Test
  fun theRowOpensTheUpdateScreen() {
    var opened = false
    show(
        UpdateState(phase = UpdateState.Phase.AVAILABLE, manifest = manifest),
        onOpen = { opened = true },
    )
    compose.onNodeWithTag(SettingsTags.UPDATE).performClick()
    assertTrue(opened)
  }
}
