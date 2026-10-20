package com.sadad.app.feature.contacts

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.sadad.app.data.session.StoredInstallation
import com.sadad.app.feature.auth.MainDispatcherRule
import com.sadad.app.feature.lock.AppLock
import com.sadad.app.testing.STORE_A
import com.sadad.app.testing.TestGraph
import com.sadad.app.ui.UiTestApplication
import com.sadad.app.ui.theme.SadadTheme
import java.io.File
import java.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@Config(application = UiTestApplication::class)
class ContactsScreensTest {
  @get:Rule val compose = createComposeRule()
  @get:Rule val main = MainDispatcherRule()

  @Test
  fun theListScrollsToTheLastOfFiveThousandRows() {
    runBlocking {
      TestGraph.signedIn().also { fixture ->
        fixture.seed {
          applyPage(pageOf((0 until 5000).map { contact("شخص $it", contactId(it), minute = it) }))
        }
      }
    }
        .use { fixture ->
          val model =
              ContactsViewModel(fixture.graph, SavedStateHandle()).also {
                fixture.closeWithStore(it.viewModelScope)
              }
          compose.setContent {
            SadadTheme { ContactsScreen(viewModel = model, onOpenContact = {}) }
          }
          compose.waitUntil(30_000) { model.state.value.registered == 5000 }
          val last = model.state.value.rows[4999]

          compose.onNodeWithTag(ContactsTag.LIST).performScrollToIndex(4999)

          compose.onNodeWithText(last.name).assertIsDisplayed()
        }
  }

  @Test
  fun restrictedPaymentAndDeletePermissionsReplaceTheButtons() {
    val person = contact("سامي", contactId(1), minute = 0)
    runBlocking {
      TestGraph.signedIn().also { fixture ->
        fixture.seed { applyPage(pageOf(listOf(person))) }
        fixture.graph.accounts.update(STORE_A) {
          it.copy(
              installation =
                  StoredInstallation(
                      status = "active",
                      generation = 1,
                      permissions = mapOf("registerPayments" to false, "deleteContacts" to false),
                  )
          )
        }
      }
    }
        .use { fixture ->
          val model =
              ContactDetailViewModel(fixture.graph, person.entityId).also {
                fixture.closeWithStore(it.viewModelScope)
              }
          compose.setContent {
            SadadTheme {
              ContactDetailScreen(
                  viewModel = model,
                  onBack = {},
                  onRecordDebt = {},
                  onRecordPayment = {},
                  onEdit = {},
              )
            }
          }
          compose.waitUntil(10_000) { model.state.value.loaded }

          compose.onNodeWithText("تسجيل الدفعات مقيّد").assertIsDisplayed()
          compose.onNodeWithText("تسجيل دفعة").assertDoesNotExist()
          compose.onNodeWithText("حذف الزبون").assertDoesNotExist()
        }
  }

  @Test
  fun theSettleFirstDialogDoesNotOfferRestrictedPayments() {
    val person = contact("سامي", contactId(1))
    val fixture = runBlocking {
      TestGraph.signedIn().also {
        it.seedCommands(listOf(person, debt(person.entityId, 1000)))
        it.graph.accounts.update(STORE_A) { account ->
          account.copy(
              installation =
                  StoredInstallation(
                      "active",
                      generation = 1,
                      permissions = mapOf("registerPayments" to false),
                  )
          )
        }
      }
    }
    fixture.use {
      val model =
          ContactDetailViewModel(fixture.graph, person.entityId).also {
            fixture.closeWithStore(it.viewModelScope)
          }
      compose.setContent { SadadTheme { ContactDetailScreen(model, {}, {}, {}, {}) } }
      compose.waitUntil(10_000) {
        model.state.value.loaded && !model.state.value.canRegisterPayments
      }
      compose.onNodeWithText("حذف الزبون").performScrollTo().performClick()
      compose.onNodeWithText("لا يمكن حذف السجل").assertIsDisplayed()
      compose.onNodeWithText("تسجيل دفعة").assertDoesNotExist()
    }
  }

  @Test fun aFailedWhatsAppLaunchDoesNotSuppressTheNextLock() = failedExternalAction(share = false)

  @Test fun aFailedPdfDoesNotSuppressTheNextLock() = failedExternalAction(share = true)

  private fun failedExternalAction(share: Boolean) {
    val base = ApplicationProvider.getApplicationContext<Context>()
    val blockedCache = File.createTempFile("blocked-exports-", ".tmp", base.cacheDir)
    val context =
        object : ContextWrapper(base) {
          override fun getCacheDir(): File = blockedCache

          override fun startActivity(intent: Intent) {
            throw ActivityNotFoundException("No external app")
          }
        }
    val person = contact("سامي", contactId(1), phone = "+970599123456")
    val fixture = runBlocking { TestGraph.signedIn().also { it.seedCommands(listOf(person)) } }
    fixture.use {
      val model =
          ContactDetailViewModel(fixture.graph, person.entityId).also {
            fixture.closeWithStore(it.viewModelScope)
          }
      val lock = AppLock.get(base)
      lock.appStarted()
      lock.markUnlocked()
      compose.setContent {
        CompositionLocalProvider(LocalContext provides context) {
          SadadTheme { ContactDetailScreen(model, {}, {}, {}, {}) }
        }
      }
      compose.waitUntil(10_000) { model.state.value.loaded }
      compose
          .onNodeWithText(if (share) "مشاركة كشف الشخص" else "تواصل على واتساب")
          .performScrollTo()
          .performClick()
      compose.waitUntil(10_000) { model.state.value.error != null }
      lock.appStopped()
      ShadowSystemClock.advanceBy(Duration.ofSeconds(2))
      lock.appStarted()
      assertTrue("A failed external action must not bypass the next PIN", lock.locked.value)
    }
    blockedCache.delete()
  }
}
