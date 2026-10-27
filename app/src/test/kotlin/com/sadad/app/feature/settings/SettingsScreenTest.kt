package com.sadad.app.feature.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.LayoutDirection
import com.sadad.app.data.session.StoredInstallation
import com.sadad.app.feature.auth.MainDispatcherRule
import com.sadad.app.feature.history.HistoryTags
import com.sadad.app.feature.history.PeriodPreset
import com.sadad.app.testing.STORE_A
import com.sadad.app.testing.STORE_B
import com.sadad.app.testing.TestGraph
import com.sadad.app.ui.UiTestApplication
import com.sadad.app.ui.theme.SadadTheme
import com.sadad.ledger.Cents
import com.sadad.ledger.Direction
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
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

  /** Where the store tab's rows lead, counted. */
  private class Nav {
    var trash = 0
    var recovery = 0
    var password = 0
    var addStore = 0
    var login = 0
  }

  @Test
  fun eachSectionTitleRenders() {
    show { _, _, _ ->
      for (title in listOf("الأجهزة والمزامنة", "الأمان", "البيانات", "المظهر", "الحساب")) {
        scrollTo(hasText(title))
        compose.onNodeWithText(title).assertIsDisplayed()
      }
    }
  }

  @Test
  fun theSwitcherListsTheSavedStoresAndAddStoreAsksForALogin() {
    show(twoStores = true) { _, _, nav ->
      scrollTo(hasText("تبديل"))
      compose.onNodeWithText("تبديل").performClick()

      compose.waitUntil(timeoutMillis = 10_000) {
        compose.onAllNodesWithText("متجر سامي").fetchSemanticsNodes().isNotEmpty()
      }
      compose.onNodeWithTag(SettingsTags.ADD_STORE).performClick()
      assertEquals(1, nav.addStore)
    }
  }

  @Test
  fun choosingAnotherStoreInTheSwitcherOpensIt() {
    show(twoStores = true) { _, model, _ ->
      scrollTo(hasText("تبديل"))
      compose.onNodeWithText("تبديل").performClick()
      compose.waitUntil(timeoutMillis = 10_000) {
        compose.onAllNodesWithText("متجر سامي").fetchSemanticsNodes().isNotEmpty()
      }

      compose.onNodeWithText("متجر سامي").performClick()

      val switched = runBlocking {
        withTimeoutOrNull(10_000) {
          model.state.first { state ->
            state.accounts.firstOrNull { it.active }?.storeUserId == STORE_A
          }
        }
      }
      assertEquals(STORE_A, switched?.accounts?.firstOrNull { it.active }?.storeUserId)
    }
  }

  @Test
  fun theStoreNameOpensTheProfileDialog() {
    show { _, _, _ ->
      scrollTo(hasTag(SettingsTags.PROFILE))
      compose.onNodeWithTag(SettingsTags.PROFILE).performClick()

      compose.waitUntil(timeoutMillis = 10_000) {
        compose
            .onAllNodesWithTag(SettingsDialogTags.PROFILE_STORE)
            .fetchSemanticsNodes()
            .isNotEmpty()
      }
      compose.onNodeWithTag(SettingsDialogTags.PROFILE_STORE).performTextInput("متجر سامي")
      compose.onNodeWithTag(SettingsDialogTags.PROFILE_OWNER).performTextInput("سامي")
      compose.onNodeWithTag(SettingsDialogTags.PROFILE_SAVE).performClick()

      compose.waitUntil(timeoutMillis = 10_000) {
        compose.onAllNodesWithText("متجر سامي").fetchSemanticsNodes().isNotEmpty()
      }
    }
  }

  @Test
  fun theRecoveryLinkOpensItsScreen() {
    show { _, _, nav ->
      scrollTo(hasTag(SettingsTags.RECOVERY))
      compose.onNodeWithTag(SettingsTags.RECOVERY).performClick()
      assertEquals(1, nav.recovery)
    }
  }

  @Test
  fun theWaitingEntriesAreCountedAndTheNewestIsNamed() {
    show(waitingDebt = true) { _, _, _ ->
      scrollTo(hasText("دين سارة محمود 120 ₪ · يُرسل تلقائيًا"))
      compose.onNodeWithText("إدخالان بانتظار الإرسال").assertIsDisplayed()
      compose.onNodeWithText("دين سارة محمود 120 ₪ · يُرسل تلقائيًا").assertIsDisplayed()
    }
  }

  @Test
  fun aStoreWithEverythingSentSaysSo() {
    show { _, _, _ ->
      scrollTo(hasText("كل الإدخالات مرسلة"))
      compose.onNodeWithText("كل الإدخالات مرسلة").assertIsDisplayed()
    }
  }

  @Test
  fun syncNowAsksTheSchedulerForTheOpenStore() {
    show { kit, _, _ ->
      scrollTo(hasTag(SettingsTags.SYNC_NOW))
      compose.onNodeWithTag(SettingsTags.SYNC_NOW).performClick()

      compose.waitUntil(timeoutMillis = 10_000) {
        runBlocking { kit.recording.syncNow.isNotEmpty() }
      }
      assertEquals(listOf(STORE_A), kit.recording.syncNow)
    }
  }

  @Test
  fun theThemeControlChangesTheSavedChoice() {
    show { kit, _, _ ->
      scrollTo(hasText("داكن"))
      compose.onNodeWithText("داكن").performClick()

      compose.waitUntil(timeoutMillis = 10_000) {
        runBlocking { kit.preferences.theme.first() } == ThemeChoice.DARK
      }
      assertEquals(ThemeChoice.DARK, runBlocking { kit.preferences.theme.first() })
      compose.waitUntil(timeoutMillis = 10_000) {
        compose.onAllNodes(hasText("داكن") and isSelected()).fetchSemanticsNodes().isNotEmpty()
      }
    }
  }

  @Test
  fun theExportChipsChangeTheSavedPeriod() {
    show { kit, _, _ ->
      scrollTo(hasTestTag(HistoryTags.PERIOD_YEAR))
      compose.onNodeWithTag(HistoryTags.PERIOD_MONTH).assertIsSelected()

      compose.onNodeWithTag(HistoryTags.PERIOD_YEAR).performClick()

      compose.waitUntil(timeoutMillis = 10_000) {
        runBlocking { kit.preferences.exportPeriod.first() } ==
            ExportPeriod.Preset(PeriodPreset.YEAR)
      }
    }
  }

  @Test
  fun theBiometricSwitchIsHiddenWithoutAFingerprintOrFace() {
    show(sensor = false) { _, _, _ ->
      scrollTo(hasText("الأمان"))
      compose.onAllNodesWithText("فتح بالبصمة أو الوجه").assertCountEquals(0)
    }
  }

  @Test
  fun theBiometricSwitchIsShownWhenThePhoneHasOne() {
    show(sensor = true) { _, _, _ ->
      scrollTo(hasText("فتح بالبصمة أو الوجه"))
      compose.onNodeWithText("فتح بالبصمة أو الوجه").assertIsDisplayed()
    }
  }

  @Test
  fun aDeviceWithoutDeletePermissionsSeesNoTrash() {
    show(canDelete = false) { _, _, _ ->
      scrollTo(hasText("البيانات"))
      compose.onAllNodesWithText("سلة المحذوفات").assertCountEquals(0)
    }
  }

  @Test
  fun theTrashRowOpensTheTrashWithItsCount() {
    show { _, _, nav ->
      scrollTo(hasTag(SettingsTags.TRASH))
      compose.onNodeWithText("سلة المحذوفات").assertIsDisplayed()
      compose.onNodeWithTag(SettingsTags.TRASH).performClick()
      assertEquals(1, nav.trash)
    }
  }

  @Test
  fun theVersionFooterShowsTheAppVersion() {
    show { _, _, _ ->
      scrollTo(hasTag(SettingsTags.VERSION))
      compose.onNodeWithTag(SettingsTags.VERSION).assertIsDisplayed()
      compose.onNode(hasText("سدد · الإصدار", substring = true)).assertIsDisplayed()
    }
  }

  @Test
  fun signingOutAsksBeforeLeavingAndCancelKeepsTheStore() {
    show { _, _, _ ->
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
  fun theChangeLockCodeLinkOpensThePinDialogAndATooShortPinIsRefused() {
    show { _, _, _ ->
      scrollTo(hasText("إنشاء رمز قفل"))
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

  @Test
  fun theSecondRemovalConfirmationIsDisabledForAMomentSoADoubleTapCannotCountTwice() {
    show(waitingDebt = true) { _, _, _ ->
      scrollTo(hasText("إزالة هذا المتجر من الهاتف"))
      compose.onNodeWithText("إزالة هذا المتجر من الهاتف").performScrollTo().performClick()
      compose.waitUntil(timeoutMillis = 10_000) {
        compose
            .onAllNodesWithTag(SettingsDialogTags.REMOVE_CONFIRM)
            .fetchSemanticsNodes()
            .isNotEmpty()
      }
      compose.onNodeWithTag(SettingsDialogTags.REMOVE_CONFIRM).assertIsEnabled().performClick()

      compose.waitUntil(timeoutMillis = 10_000) {
        compose.onAllNodesWithText("تأكيد مرة أخرى").fetchSemanticsNodes().isNotEmpty()
      }
      compose.onNodeWithTag(SettingsDialogTags.REMOVE_CONFIRM).assertIsNotEnabled()
      compose.waitUntil(timeoutMillis = 10_000) {
        runCatching { compose.onNodeWithTag(SettingsDialogTags.REMOVE_CONFIRM).assertIsEnabled() }
            .isSuccess
      }
    }
  }

  private fun hasTag(tag: String): SemanticsMatcher = hasTestTag(tag)

  private fun scrollTo(matcher: SemanticsMatcher) {
    compose.onNodeWithTag(SettingsTags.LIST).performScrollToNode(matcher)
  }

  /** Asserts inside the fixture's lifetime: closing the store empties every state flow. */
  private fun show(
      waitingDebt: Boolean = false,
      twoStores: Boolean = false,
      sensor: Boolean = false,
      canDelete: Boolean = true,
      check: (SettingsKit, SettingsViewModel, Nav) -> Unit,
  ) {
    runBlocking { TestGraph.signedIn() }
        .use { fixture ->
          SettingsKit(fixture).use { kit ->
            if (twoStores) {
              runBlocking {
                fixture.graph.accounts.update(STORE_A) { it.copy(storeName = "متجر سامي") }
                fixture.signIn(STORE_B)
                fixture.activate(STORE_B)
                fixture.graph.accounts.update(STORE_B) { it.copy(storeName = "متجر ليلى") }
              }
            }
            if (!canDelete) {
              runBlocking {
                fixture.graph.accounts.update(STORE_A) {
                  it.copy(
                      installation =
                          StoredInstallation(
                              status = "active",
                              generation = 1,
                              permissions =
                                  mapOf("deleteRecords" to false, "deleteContacts" to false),
                          )
                  )
                }
              }
            }
            if (waitingDebt) {
              runBlocking {
                var contact = ""
                fixture.seed {
                  contact = addContact("سارة محمود", null, null, null, null).getOrThrow()
                }
                fixture.seed {
                  addDebt(contact, Direction.RECEIVABLE, Cents(12_000), null, null).getOrThrow()
                }
              }
            }
            val model = kit.viewModel(biometricAvailable = sensor)
            runBlocking { model.state.first { it.loaded } }
            val nav = Nav()
            compose.setContent {
              SadadTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                  StoreScreen(
                      onOpenTrash = { nav.trash++ },
                      onOpenRecovery = { nav.recovery++ },
                      onChangePassword = { nav.password++ },
                      onAddStore = { nav.addStore++ },
                      onNeedsLogin = { nav.login++ },
                      settingsViewModel = model,
                  )
                }
              }
            }
            compose.waitForIdle()
            check(kit, model, nav)
          }
        }
  }
}
