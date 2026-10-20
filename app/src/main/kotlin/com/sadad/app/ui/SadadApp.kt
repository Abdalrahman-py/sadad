package com.sadad.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.sadad.app.R
import com.sadad.app.feature.auth.AwaitingApprovalScreen
import com.sadad.app.feature.auth.LoginScreen
import com.sadad.app.feature.auth.PasswordChangeScreen
import com.sadad.app.feature.auth.WelcomeScreen
import com.sadad.app.feature.auth.navigateAuth
import com.sadad.app.feature.contactform.ContactFormScreen
import com.sadad.app.feature.contacts.ContactDetailScreen
import com.sadad.app.feature.contacts.ContactsScreen
import com.sadad.app.feature.debt.DebtFormScreen
import com.sadad.app.feature.history.HistoryScreen
import com.sadad.app.feature.home.HomeScreen
import com.sadad.app.feature.lock.LockGate
import com.sadad.app.feature.notifications.NotificationsScreen
import com.sadad.app.feature.payment.PaymentFormScreen
import com.sadad.app.feature.recovery.RecoveryScreen
import com.sadad.app.feature.reports.ReportsScreen
import com.sadad.app.feature.settings.SettingsScreen
import com.sadad.app.feature.trash.TrashScreen
import com.sadad.app.ui.theme.SadadTheme
import kotlin.reflect.KClass

@Composable
fun SadadApp(
    startDestination: Any = Home,
    dark: Boolean = isSystemInDarkTheme(),
    navController: NavHostController = rememberNavController(),
) {
  SadadTheme(dark = dark) {
    LockGate {
      // Arabic-first, so the shell mirrors like the Java app did, whatever the device locale.
      CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Scaffold(bottomBar = { SadadBottomBar(navController) }) { insets ->
          NavHost(
              navController = navController,
              startDestination = startDestination,
              modifier = Modifier.padding(insets),
          ) {
            composable<Welcome> { WelcomeScreen(onNavigate = navController::navigateAuth) }
            composable<Login> { LoginScreen(onNavigate = navController::navigateAuth) }
            composable<PasswordChange> {
              PasswordChangeScreen(onNavigate = navController::navigateAuth)
            }
            composable<AwaitingApproval> {
              AwaitingApprovalScreen(onNavigate = navController::navigateAuth)
            }
            composable<Home> {
              HomeScreen(onOpenContact = { navController.navigate(ContactDetail(it)) })
            }
            composable<Contacts> {
              ContactsScreen(
                  onOpenContact = { contactId -> navController.navigate(ContactDetail(contactId)) }
              )
            }
            composable<ContactDetail> {
              val contactId = it.toRoute<ContactDetail>().contactId
              ContactDetailScreen(
                  contactId = contactId,
                  onBack = {
                    // One entry back to the list the Contact was opened from; clearing the whole
                    // graph here would drop the start destination.
                    if (!navController.popBackStack()) {
                      navController.navigate(Contacts) { launchSingleTop = true }
                    }
                  },
                  onRecordDebt = { navController.navigate(DebtForm(contactId)) },
                  onRecordPayment = { navController.navigate(PaymentForm(contactId)) },
                  onEdit = { navController.navigate(ContactForm(contactId)) },
              )
            }
            composable<ContactForm> { ContactFormScreen(it.toRoute<ContactForm>().contactId) }
            composable<DebtForm> { DebtFormScreen(it.toRoute<DebtForm>().contactId) }
            composable<PaymentForm> {
              val route = it.toRoute<PaymentForm>()
              PaymentFormScreen(route.contactId, route.debtId)
            }
            composable<History> { HistoryScreen() }
            composable<Reports> { ReportsScreen() }
            composable<Notifications> { NotificationsScreen() }
            composable<Settings> { SettingsScreen() }
            composable<Trash> { TrashScreen() }
            composable<Recovery> { RecoveryScreen() }
          }
        }
      }
    }
  }
}

@Composable
private fun SadadBottomBar(navController: NavHostController) {
  val currentDestination = navController.currentBackStackEntryAsState().value?.destination
  val navigate: (Any) -> Unit = { destination ->
    navController.navigate(destination) {
      popUpTo(navController.graph.findStartDestination().id) { saveState = true }
      launchSingleTop = true
      restoreState = true
    }
  }
  NavigationBar {
    BottomItem(
        R.string.nav_home,
        R.drawable.ic_home,
        ScreenTag.NAV_HOME,
        Home::class,
        currentDestination,
    ) {
      navigate(Home)
    }
    BottomItem(
        R.string.nav_contacts,
        R.drawable.ic_people,
        ScreenTag.NAV_CONTACTS,
        Contacts::class,
        currentDestination,
    ) {
      navigate(Contacts)
    }
    BottomItem(
        R.string.nav_history,
        R.drawable.ic_history,
        ScreenTag.NAV_HISTORY,
        History::class,
        currentDestination,
    ) {
      navigate(History)
    }
    BottomItem(
        R.string.nav_settings,
        R.drawable.ic_settings,
        ScreenTag.NAV_SETTINGS,
        Settings::class,
        currentDestination,
    ) {
      navigate(Settings)
    }
  }
}

@Composable
private fun RowScope.BottomItem(
    labelRes: Int,
    iconRes: Int,
    tag: String,
    route: KClass<*>,
    currentDestination: NavDestination?,
    onClick: () -> Unit,
) {
  NavigationBarItem(
      selected = currentDestination?.hierarchy?.any { it.hasRoute(route) } == true,
      onClick = onClick,
      icon = { Icon(painter = painterResource(iconRes), contentDescription = null) },
      label = { Text(stringResource(labelRes)) },
      modifier = Modifier.testTag(tag),
  )
}
