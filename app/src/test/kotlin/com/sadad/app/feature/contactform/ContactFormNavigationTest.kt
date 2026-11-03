package com.sadad.app.feature.contactform

import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.LayoutDirection
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.sadad.app.ui.ContactDetail
import com.sadad.app.ui.ContactForm
import com.sadad.app.ui.People
import com.sadad.app.ui.QuickAdd
import com.sadad.app.ui.SadadShell
import com.sadad.app.ui.SyncDot
import com.sadad.app.ui.UiTestApplication
import com.sadad.app.ui.openFirstDebtRecording
import com.sadad.app.ui.openSavedContact
import com.sadad.app.ui.theme.SadadTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Where the contact form's save outcomes land, over the real shell's navigation helpers with stub
 * destinations: «حفظ وتسجيل دين» opens [QuickAdd] for that person (a back returns to People), and
 * «حفظ فقط» or an edit opens [ContactDetail].
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = UiTestApplication::class)
class ContactFormNavigationTest {
  @get:Rule val compose = createComposeRule()
  private lateinit var nav: NavHostController

  private fun showShell() {
    compose.setContent {
      SadadTheme {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
          nav = rememberNavController()
          SadadShell(
              navController = nav,
              startDestination = com.sadad.app.ui.Home,
              syncDot = SyncDot.NONE,
          ) {
            composable<com.sadad.app.ui.Home> { Text("stub-home") }
            composable<People> { Text("stub-people") }
            composable<ContactForm> { Text("stub-contact-form") }
            composable<ContactDetail> { Text("stub-contact") }
            composable<QuickAdd> { Text("stub-quick-add") }
          }
        }
      }
    }
  }

  private fun openTheForm() {
    compose.runOnIdle { nav.navigate(People()) }
    compose.runOnIdle { nav.navigate(ContactForm()) }
  }

  @Test
  fun savingAndRecordingLandsOnQuickAddForThatPersonAndBackReturnsToPeople() {
    showShell()
    openTheForm()
    compose.runOnIdle { nav.openFirstDebtRecording("c7") }
    compose.onNodeWithText("stub-quick-add").assertIsDisplayed()
    compose.runOnIdle {
      assertEquals("c7", nav.currentBackStackEntry?.toRoute<QuickAdd>()?.contactId)
    }
    compose.runOnIdle { nav.popBackStack() }
    compose.onNodeWithText("stub-people").assertIsDisplayed()
  }

  @Test
  fun savingOnlyLandsOnThePersonsPageAndBackReturnsToPeople() {
    showShell()
    openTheForm()
    compose.runOnIdle { nav.openSavedContact("c9") }
    compose.onNodeWithText("stub-contact").assertIsDisplayed()
    compose.runOnIdle {
      assertEquals("c9", nav.currentBackStackEntry?.toRoute<ContactDetail>()?.contactId)
    }
    compose.runOnIdle { nav.popBackStack() }
    compose.onNodeWithText("stub-people").assertIsDisplayed()
  }
}
