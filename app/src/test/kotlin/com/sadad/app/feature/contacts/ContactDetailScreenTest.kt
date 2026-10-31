package com.sadad.app.feature.contacts

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.sadad.app.data.session.StoredInstallation
import com.sadad.app.feature.auth.MainDispatcherRule
import com.sadad.app.feature.lock.AppLock
import com.sadad.app.testing.STORE_A
import com.sadad.app.testing.TestGraph
import com.sadad.app.ui.UiTestApplication
import com.sadad.app.ui.theme.SadadTheme
import com.sadad.ledger.Direction
import java.io.File
import java.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@Config(application = UiTestApplication::class)
class ContactDetailScreenTest {
  @get:Rule val compose = createComposeRule()
  @get:Rule val main = MainDispatcherRule()

  private val context: Context = ApplicationProvider.getApplicationContext()

  @Test
  fun refusedContactShowsItsSavedInputsAndReasonInsteadOfMissingOrSettled() {
    val fixture = runBlocking { TestGraph.signedIn() }
    fixture.use {
      val store = checkNotNull(fixture.graph.current.value)
      val id = runBlocking {
        val id =
            store.repository
                .addContact(
                    "إبراهيم",
                    null,
                    "عميل",
                    "ملاحظة محفوظة",
                    com.sadad.app.data.ledger.FirstDebt(com.sadad.ledger.Cents(25_800)),
                )
                .getOrThrow()
        store.outbox.settle(
            store.outbox.snapshot().map {
              com.sadad.app.data.ledger.receiptOf(
                  it.command,
                  com.sadad.ledger.ReceiptStatus.REJECTED,
                  if (it.type == com.sadad.ledger.CommandType.CONTACT_CREATE)
                      "contact_limit_reached"
                  else "dependency_rejected",
              )
            }
        )
        id
      }
      val model =
          ContactDetailViewModel(fixture.graph, id, context).also {
            fixture.closeWithStore(it.viewModelScope)
          }
      compose.setContent { SadadTheme { ContactDetailScreen(model, {}, {}, {}, {}) } }
      compose.waitUntil(5_000) { model.state.value.syncFailure != null }
      compose
          .onNodeWithText(context.getString(com.sadad.app.R.string.contacts_saved_draft))
          .assertIsDisplayed()
      compose
          .onNodeWithText(
              context.getString(
                  com.sadad.app.R.string.contacts_sync_failed,
                  "وصل المتجر إلى حد عدد الأشخاص.",
              )
          )
          .assertIsDisplayed()
      compose.onNodeWithTag(ContactsTag.MENU).assertDoesNotExist()
      compose
          .onNodeWithText(context.getString(com.sadad.app.R.string.contacts_record_debt))
          .assertDoesNotExist()
      compose.onNodeWithTag(ContactsTag.MOVEMENTS).performScrollToNode(hasText("258 ₪"))
      compose.onNodeWithText("258 ₪").assertIsDisplayed()
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
              ContactDetailViewModel(fixture.graph, person.entityId, context).also {
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
          compose.waitUntil(10_000) {
            model.state.value.loaded &&
                !model.state.value.canRegisterPayments &&
                !model.state.value.canDeleteContacts
          }

          compose.onNodeWithText("اطلب من صاحب المتجر تفعيل الدفعات").assertIsDisplayed()
          compose.onNodeWithText("استلام دفعة").assertDoesNotExist()
          compose.onNodeWithTag(ContactsTag.MENU).performClick()
          compose.onNodeWithText("حذف").assertDoesNotExist()
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
          ContactDetailViewModel(fixture.graph, person.entityId, context).also {
            fixture.closeWithStore(it.viewModelScope)
          }
      compose.setContent { SadadTheme { ContactDetailScreen(model, {}, {}, {}, {}) } }
      compose.waitUntil(10_000) {
        model.state.value.loaded && !model.state.value.canRegisterPayments
      }
      compose.onNodeWithTag(ContactsTag.MENU).performClick()
      compose.onNodeWithText("حذف").performClick()
      compose.waitUntil(10_000) { model.state.value.settleFirst != null }
      compose.onNodeWithText("لا يمكن حذف السجل").assertIsDisplayed()
      compose.onNodeWithText("استلام دفعة").assertDoesNotExist()
    }
  }

  @Test fun aFailedWhatsAppLaunchDoesNotSuppressTheNextLock() = failedExternalAction(share = false)

  @Test fun aFailedPdfDoesNotSuppressTheNextLock() = failedExternalAction(share = true)

  @Test
  fun aFullPaymentAcrossTwoDebtsShowsOneAmountAndSettledBalance() {
    val person = contact("سامي", contactId(1))
    val first = debt(person.entityId, 10000, minute = 1)
    val second = debt(person.entityId, 5000, minute = 2)
    val fixture = runBlocking {
      TestGraph.signedIn().also {
        it.seedCommands(
            listOf(
                person,
                first,
                second,
                payment(first.entityId, 10000, minute = 3),
                payment(second.entityId, 5000, minute = 3),
            )
        )
      }
    }
    fixture.use {
      val model =
          ContactDetailViewModel(fixture.graph, person.entityId, context).also {
            fixture.closeWithStore(it.viewModelScope)
          }
      compose.setContent { SadadTheme { ContactDetailScreen(model, {}, {}, {}, {}) } }
      compose.waitUntil(10000) { model.state.value.loaded && model.state.value.movements.size == 3 }
      timelineRow("دفعة · نقدي").assertIsDisplayed()
      compose.onNodeWithTag(ContactsTag.MOVEMENTS).performScrollToNode(hasText("₪ 150"))
      compose
          .onNode(hasText("₪ 150") and hasAnyAncestor(hasTestTag(ContactsTag.MOVEMENTS)))
          .assertIsDisplayed()
      timelineRow("مسدد").assertIsDisplayed()
    }
  }

  @Test
  fun payableHistoryLabelsTheDebtAndPaymentAsMoneyWeOweAndPayOut() {
    val person = contact("مورد", contactId(1))
    val owing = debt(person.entityId, 2000, direction = Direction.PAYABLE, minute = 1)
    val paid = payment(owing.entityId, 1000, minute = 2)
    val fixture = runBlocking {
      TestGraph.signedIn().also { it.seedCommands(listOf(person, owing, paid)) }
    }
    fixture.use {
      val model =
          ContactDetailViewModel(fixture.graph, person.entityId, context).also {
            fixture.closeWithStore(it.viewModelScope)
          }
      compose.setContent { SadadTheme { ContactDetailScreen(model, {}, {}, {}, {}) } }
      compose.waitUntil(10_000) { model.state.value.movements.size == 2 }
      timelineRow("دفع للمورد").assertIsDisplayed()
      timelineRow("بضاعة بالدين").assertIsDisplayed()
      // The row's own label, not any text in it: «عكس الدفعة» is an action on the row, not a label.
      compose
          .onAllNodes(
              SemanticsMatcher("a label that starts with «دفعة»") { node ->
                node.config.getOrNull(SemanticsProperties.Text)?.any {
                  it.text.startsWith("دفعة")
                } == true
              } and hasAnyAncestor(hasTestTag(ContactsTag.MOVEMENTS))
          )
          .assertCountEquals(0)
    }
  }

  @Test
  fun aSuccessfulWhatsAppLaunchSkipsExactlyOneLock() {
    val base = ApplicationProvider.getApplicationContext<Context>()
    var launched: Intent? = null
    val context =
        object : ContextWrapper(base) {
          override fun startActivity(intent: Intent) {
            launched = intent
          }
        }
    val person = contact("سامي", contactId(1), phone = "+970599123456")
    val fixture = runBlocking { TestGraph.signedIn().also { it.seedCommands(listOf(person)) } }
    fixture.use {
      val model =
          ContactDetailViewModel(fixture.graph, person.entityId, context).also {
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
      compose.onNodeWithTag(ContactsTag.MENU).performClick()
      compose.onNodeWithText("تذكير على واتساب").performClick()
      assertEquals("https://wa.me/970599123456", launched?.dataString)
      leaveAndReturn(lock)
      assertFalse(lock.locked.value)
      leaveAndReturn(lock)
      assertTrue(lock.locked.value)
    }
  }

  /** A row of the timeline whose text holds [text], scrolled into view. */
  private fun timelineRow(text: String) =
      compose
          .onNodeWithTag(ContactsTag.MOVEMENTS)
          .performScrollToNode(hasText(text, substring = true))
          .let {
            compose.onNode(
                hasText(text, substring = true) and
                    hasAnyAncestor(hasTestTag(ContactsTag.MOVEMENTS))
            )
          }

  private fun leaveAndReturn(lock: AppLock) {
    lock.appStopped()
    ShadowSystemClock.advanceBy(Duration.ofSeconds(2))
    lock.appStarted()
  }

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
          ContactDetailViewModel(fixture.graph, person.entityId, context).also {
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
      compose.onNodeWithTag(ContactsTag.MENU).performClick()
      compose.onNodeWithText(if (share) "مشاركة الكشف PDF" else "تذكير على واتساب").performClick()
      compose.waitUntil(10_000) { model.state.value.error != null }
      lock.appStopped()
      ShadowSystemClock.advanceBy(Duration.ofSeconds(2))
      lock.appStarted()
      assertTrue("A failed external action must not bypass the next PIN", lock.locked.value)
    }
    blockedCache.delete()
  }
}
