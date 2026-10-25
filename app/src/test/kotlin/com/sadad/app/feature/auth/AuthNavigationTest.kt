package com.sadad.app.feature.auth

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.sadad.app.ui.AwaitingApproval
import com.sadad.app.ui.Home
import com.sadad.app.ui.Login
import com.sadad.app.ui.PasswordChange
import com.sadad.app.ui.UiTestApplication
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = UiTestApplication::class)
class AuthNavigationTest {
  @get:Rule val compose = createComposeRule()

  @Test
  fun completingAnAuthStepLeavesNoWayBack() {
    lateinit var nav: NavHostController
    compose.setContent {
      nav = rememberNavController()
      NavHost(navController = nav, startDestination = Login) {
        composable<Login> { Text("login") }
        composable<PasswordChange> { Text("password") }
        composable<AwaitingApproval> { Text("awaiting") }
        composable<Home> { Text("home") }
      }
    }

    compose.runOnIdle { nav.navigateAuth(Login) }
    compose.runOnIdle {
      assertTrue(nav.currentDestination!!.hasRoute(Login::class))
      assertNull(nav.previousBackStackEntry)
    }

    compose.runOnIdle { nav.navigateAuth(PasswordChange) }
    compose.runOnIdle {
      assertTrue(nav.currentDestination!!.hasRoute(PasswordChange::class))
      assertNull(nav.previousBackStackEntry)
    }

    compose.runOnIdle { nav.navigateAuth(AwaitingApproval) }
    compose.runOnIdle {
      assertTrue(nav.currentDestination!!.hasRoute(AwaitingApproval::class))
      assertNull(nav.previousBackStackEntry)
    }

    compose.runOnIdle { nav.navigateAuth(Home) }
    compose.runOnIdle {
      assertTrue(nav.currentDestination!!.hasRoute(Home::class))
      assertFalse(nav.popBackStack())
    }
  }
}
