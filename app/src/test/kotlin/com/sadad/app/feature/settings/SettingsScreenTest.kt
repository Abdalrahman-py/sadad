package com.sadad.app.feature.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.LayoutDirection
import com.sadad.app.feature.auth.MainDispatcherRule
import com.sadad.app.feature.history.HistoryTags
import com.sadad.app.feature.history.PeriodPreset
import com.sadad.app.testing.TestGraph
import com.sadad.app.ui.UiTestApplication
import com.sadad.app.ui.theme.SadadTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = UiTestApplication::class)
class SettingsScreenTest {
  @get:Rule val compose = createComposeRule()
  @get:Rule val main = MainDispatcherRule()

  @Test
  fun eachSectionTitleRenders() {
    show { _ ->
      for (title in
          listOf(
              "بيانات المتجر",
              "البيانات والتصدير",
              "مزامنة السجلات والاسترداد",
              "قفل التطبيق",
              "المظهر",
              "الحساب",
          )) {
        scrollTo(hasText(title))
        compose.onNodeWithText(title).assertIsDisplayed()
      }
    }
  }

  @Test
  fun theExportPeriodChipsChangeTheSavedPeriod() {
    show { kit ->
      scrollTo(hasText("البيانات والتصدير"))
      compose.onNodeWithTag(HistoryTags.PERIOD_MONTH).assertIsSelected()

      compose.onNodeWithTag(HistoryTags.PERIOD_YEAR).performClick()

      compose.waitUntil(timeoutMillis = 10_000) {
        runBlocking { kit.preferences.exportPeriod.first() } ==
            ExportPeriod.Preset(PeriodPreset.YEAR)
      }
      assertEquals(
          ExportPeriod.Preset(PeriodPreset.YEAR),
          runBlocking { kit.preferences.exportPeriod.first() },
      )
      compose.onNodeWithTag(HistoryTags.PERIOD_YEAR).assertIsSelected()
    }
  }

  @Test
  fun theStoreNameShowsTheJavaDefaultUntilOneIsSaved() {
    show { _ ->
      scrollTo(hasText("اسم المتجر: متجري"))
      compose.onNodeWithText("اسم المتجر: متجري").assertIsDisplayed()
      compose.onNodeWithText("اسم صاحب المتجر: غير مسجل").assertIsDisplayed()
    }
  }

  @Test
  fun editingTheProfileSavesTheNamesOnTheCard() {
    show { _ ->
      compose.onNodeWithContentDescription("تعديل اسم المتجر وصاحب المتجر").performClick()
      compose.onNodeWithTag(SettingsDialogTags.PROFILE_STORE).performTextInput("متجر سامي")
      compose.onNodeWithTag(SettingsDialogTags.PROFILE_OWNER).performTextInput("سامي")
      compose.onNodeWithTag(SettingsDialogTags.PROFILE_SAVE).performClick()

      compose.waitUntil(timeoutMillis = 10_000) {
        compose.onAllNodesWithText("اسم المتجر: متجر سامي").fetchSemanticsNodes().isNotEmpty()
      }
      scrollTo(hasText("اسم صاحب المتجر: سامي"))
      compose.onNodeWithText("اسم صاحب المتجر: سامي").assertIsDisplayed()
    }
  }

  @Test
  fun signingOutAsksBeforeLeavingAndCancelKeepsTheStore() {
    show { _ ->
      scrollTo(hasText("تسجيل الخروج"))
      compose.onNodeWithText("تسجيل الخروج").performScrollTo().performClick()

      compose.waitUntil(timeoutMillis = 10_000) {
        compose
            .onAllNodesWithText("هل تريد تسجيل الخروج؟ تبقى بيانات المتجر على هذا الهاتف.")
            .fetchSemanticsNodes()
            .isNotEmpty()
      }
      compose.onNodeWithText("إلغاء").performClick()

      compose.waitUntil(timeoutMillis = 10_000) {
        compose
            .onAllNodesWithTag(SettingsDialogTags.SIGN_OUT_CONFIRM)
            .fetchSemanticsNodes()
            .isEmpty()
      }
    }
  }

  @Test
  fun theLockSwitchOffersAPinDialogWhenThereIsNone() {
    show { _ ->
      scrollTo(hasText("قفل التطبيق"))
      compose.onNodeWithText("إنشاء رمز قفل").performScrollTo().performClick()

      compose.waitUntil(timeoutMillis = 10_000) {
        compose.onAllNodesWithTag(SettingsDialogTags.PIN_FIRST).fetchSemanticsNodes().isNotEmpty()
      }
      compose.onNodeWithTag(SettingsDialogTags.PIN_FIRST).performTextInput("12")
      compose.onNodeWithTag(SettingsDialogTags.PIN_SECOND).performTextInput("12")
      compose.onNodeWithTag(SettingsDialogTags.PIN_SAVE).performClick()

      compose.waitUntil(timeoutMillis = 10_000) {
        compose
            .onAllNodesWithText("الرمز يجب أن يكون من 4 إلى 12 رقماً.")
            .fetchSemanticsNodes()
            .isNotEmpty()
      }
    }
  }

  private fun scrollTo(matcher: androidx.compose.ui.test.SemanticsMatcher) {
    compose.onNodeWithTag(SettingsTags.LIST).performScrollToNode(matcher)
  }

  /** Asserts inside the fixture's lifetime: closing the store empties every state flow. */
  private fun show(check: (SettingsKit) -> Unit) {
    runBlocking { TestGraph.signedIn() }
        .use { fixture ->
          SettingsKit(fixture).use { kit ->
            val model = kit.viewModel()
            runBlocking { model.state.first { it.loaded } }
            compose.setContent {
              SadadTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                  SettingsScreen(settingsViewModel = model)
                }
              }
            }
            compose.waitForIdle()
            check(kit)
          }
        }
  }
}
