package com.sadad.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.sadad.app.R
import com.sadad.app.feature.account.AccountActivityScreen
import com.sadad.app.feature.account.TopBarActions
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
import com.sadad.app.feature.settings.SettingsNavigation
import com.sadad.app.feature.settings.SettingsScreen
import com.sadad.app.feature.trash.TrashScreen
import com.sadad.app.ui.components.sadadPageBackground
import com.sadad.app.ui.theme.SadadTheme
import com.sadad.app.ui.theme.sadadPalette
import kotlin.reflect.KClass

@Composable
fun SadadApp(
    startDestination: Any = Home,
    dark: Boolean = isSystemInDarkTheme(),
    navController: NavHostController = rememberNavController(),
) {
  var accountActivityRequest by rememberSaveable { mutableIntStateOf(0) }
  SadadTheme(dark = dark) {
    LockGate {
      // Arabic-first, so the shell mirrors like the Java app did, whatever the device locale.
      CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        SadadShell(
            navController = navController,
            startDestination = startDestination,
            topBar = { screenTitle ->
              TopBarActions(
                  screenTitle = screenTitle,
                  onOpenNotifications = {
                    navController.navigate(Notifications) { launchSingleTop = true }
                  },
                  onOpenActivityLog = {
                    accountActivityRequest++
                    navController.navigate(History) { launchSingleTop = true }
                  },
                  onAddStore = { navController.navigateAuth(Login) },
                  onNeedsLogin = { navController.navigateAuth(Login) },
              )
            },
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
                onOpenContact = { contactId -> navController.navigate(ContactDetail(contactId)) },
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
          composable<DebtForm> {
            val contactId = it.toRoute<DebtForm>().contactId
            DebtFormScreen(
                contactId = contactId,
                // Back to the Contact the Debt was recorded for, keeping its live detail state.
                onSaved = {
                  if (!navController.popBackStack()) {
                    navController.navigate(ContactDetail(contactId))
                  }
                },
            )
          }
          composable<PaymentForm> {
            val route = it.toRoute<PaymentForm>()
            PaymentFormScreen(
                route.contactId,
                route.debtId,
                // Back to the Contact the payment was recorded for, as the debt form does.
                onSaved = {
                  if (!navController.popBackStack()) {
                    navController.navigate(ContactDetail(route.contactId))
                  }
                },
            )
          }
          composable<History> {
            if (accountActivityRequest > 0) AccountActivityScreen(accountActivityRequest)
            else
                HistoryScreen(
                    onOpenContact = { navController.navigate(ContactDetail(it)) },
                    onOpenReports = { navController.navigate(Reports) },
                )
          }
          composable<Reports> { ReportsScreen() }
          composable<Notifications> { NotificationsScreen() }
          composable<Settings> {
            SettingsScreen(
                navigation =
                    SettingsNavigation(
                        onOpenTrash = { navController.navigate(Trash) },
                        onOpenNotifications = {
                          navController.navigate(Notifications) { launchSingleTop = true }
                        },
                        onOpenActivityLog = {
                          accountActivityRequest++
                          navController.navigate(History) { launchSingleTop = true }
                        },
                        onOpenRecovery = { navController.navigate(Recovery) },
                        onChangePassword = { navController.navigate(PasswordChange) },
                        onAddStore = { navController.navigateAuth(Login) },
                        onNeedsLogin = { navController.navigateAuth(Login) },
                    )
            )
          }
          composable<Trash> { TrashScreen() }
          composable<Recovery> { RecoveryScreen() }
        }
      }
    }
  }
}

/**
 * The bars and the padded content area every destination lives in. Edge-to-edge is on, so the
 * Scaffold hands the system-bar insets to the content as padding; no screen handles them itself.
 * The top bar gets the current screen's title, as the Java header showed it under «سدد»; the bottom
 * bar is hidden on the forms and the sign-in screens, as `showBottomNav()` did. `internal` so the
 * inset tests can render the real destinations in the real shell.
 */
@Composable
internal fun SadadShell(
    navController: NavHostController,
    startDestination: Any,
    topBar: @Composable (screenTitle: String) -> Unit,
    destinations: NavGraphBuilder.() -> Unit,
) {
  val destination = navController.currentBackStackEntryAsState().value?.destination
  val title = stringResource(screenTitle(destination))
  Scaffold(
      modifier = Modifier.sadadPageBackground(),
      containerColor = Color.Transparent,
      topBar = { topBar(title) },
      bottomBar = { if (showsBottomBar(destination)) SadadBottomBar(navController) },
  ) { insets ->
    NavHost(
        navController = navController,
        startDestination = startDestination,
        // Consumed, so a destination with its own Scaffold does not add the bar insets again.
        modifier = Modifier.padding(insets).consumeWindowInsets(insets),
        builder = destinations,
    )
  }
}

private val FORMS = listOf(ContactForm::class, DebtForm::class, PaymentForm::class)
private val AUTH =
    listOf(Welcome::class, Login::class, PasswordChange::class, AwaitingApproval::class)

private fun NavDestination?.isAny(routes: List<KClass<*>>) =
    this != null && routes.any { route -> hierarchy.any { it.hasRoute(route) } }

private fun showsBottomBar(destination: NavDestination?) =
    destination != null && !destination.isAny(FORMS) && !destination.isAny(AUTH)

/** `titleFor(page)` of the Java app. */
private fun screenTitle(destination: NavDestination?): Int =
    when {
      destination == null -> R.string.brand_name
      destination.isAny(listOf(Home::class)) -> R.string.nav_home
      destination.isAny(listOf(Contacts::class)) -> R.string.nav_contacts
      destination.isAny(listOf(ContactDetail::class)) -> R.string.screen_contact_detail
      destination.isAny(listOf(ContactForm::class)) -> R.string.contactform_add_title
      destination.isAny(listOf(DebtForm::class)) -> R.string.screen_debt
      destination.isAny(listOf(PaymentForm::class)) -> R.string.screen_payment
      destination.isAny(listOf(History::class)) -> R.string.screen_history
      destination.isAny(listOf(Reports::class)) -> R.string.screen_reports
      destination.isAny(listOf(Settings::class)) -> R.string.nav_settings
      destination.isAny(listOf(Trash::class)) -> R.string.screen_trash
      destination.isAny(listOf(Notifications::class)) -> R.string.title_notifications
      destination.isAny(listOf(Recovery::class)) -> R.string.screen_recovery
      else -> R.string.brand_name
    }

/** The tab a destination belongs to, as `activeTab(page)`. */
private fun activeTab(destination: NavDestination?): KClass<*> =
    when {
      destination.isAny(listOf(Contacts::class, ContactDetail::class) + FORMS) -> Contacts::class
      destination.isAny(listOf(History::class, Reports::class)) -> History::class
      destination.isAny(listOf(Settings::class, Trash::class, Recovery::class)) -> Settings::class
      else -> Home::class
    }

/**
 * The Java app's bottom bar: home, people, the raised round «＋ إضافة», history, settings. The
 * chosen tab has an accent halo behind its glyph. `internal` so the shell test can render it alone.
 */
@Composable
internal fun SadadBottomBar(navController: NavHostController) {
  val currentDestination = navController.currentBackStackEntryAsState().value?.destination
  val active = activeTab(currentDestination)
  val navigate: (Any) -> Unit = { destination ->
    navController.navigate(destination) {
      popUpTo(navController.graph.findStartDestination().id) { saveState = true }
      launchSingleTop = true
      restoreState = true
    }
  }
  val p = sadadPalette
  Row(
      modifier =
          Modifier.fillMaxWidth()
              .shadow(8.dp)
              .background(p.surface)
              .windowInsetsPadding(WindowInsets.navigationBars)
              .padding(horizontal = 8.dp, vertical = 7.dp)
              .selectableGroup(),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    BottomItem(R.string.nav_home, R.drawable.ic_home, ScreenTag.NAV_HOME, active == Home::class) {
      navigate(Home)
    }
    BottomItem(
        R.string.nav_contacts,
        R.drawable.ic_people,
        ScreenTag.NAV_CONTACTS,
        active == Contacts::class,
    ) {
      navigate(Contacts)
    }
    AddItem { navController.navigate(ContactForm()) }
    BottomItem(
        R.string.nav_history,
        R.drawable.ic_history,
        ScreenTag.NAV_HISTORY,
        active == History::class,
    ) {
      navigate(History)
    }
    BottomItem(
        R.string.nav_settings,
        R.drawable.ic_settings,
        ScreenTag.NAV_SETTINGS,
        active == Settings::class,
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
    selected: Boolean,
    onClick: () -> Unit,
) {
  val p = sadadPalette
  val ink = if (selected) (if (isSystemInDarkTheme()) p.text else p.primary) else p.muted
  // The halo fades in over 580ms when the tab becomes the chosen one, as the Java animator did.
  val halo by animateFloatAsState(if (selected) 0.94f else 0f, tween(580), label = "halo")
  val glow =
      remember(p) {
        Brush.radialGradient(
            listOf(
                p.accent.copy(alpha = 112 / 255f),
                p.accent.copy(alpha = 42 / 255f),
                Color.Transparent,
            )
        )
      }
  Column(
      modifier =
          Modifier.weight(1f)
              .height(68.dp)
              .selectable(selected = selected, role = Role.Tab, onClick = onClick)
              .testTag(tag),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center,
  ) {
    Box(Modifier.height(40.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
      Box(Modifier.size(48.dp, 46.dp).graphicsLayer { alpha = halo }.background(glow, CircleShape))
      Icon(
          painterResource(iconRes),
          contentDescription = null,
          tint = ink,
          modifier = Modifier.size(23.dp),
      )
    }
    Text(
        stringResource(labelRes),
        style =
            MaterialTheme.typography.labelSmall.copy(
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
            ),
        color = ink,
        modifier = Modifier.padding(top = 3.dp),
    )
  }
}

@Composable
private fun RowScope.AddItem(onClick: () -> Unit) {
  val p = sadadPalette
  val description = stringResource(R.string.nav_add_description)
  Column(
      modifier = Modifier.weight(1f).height(68.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center,
  ) {
    Box(
        modifier =
            Modifier.size(48.dp)
                .shadow(6.dp, CircleShape)
                .background(p.primary, CircleShape)
                .clickable(role = Role.Button, onClick = onClick)
                .semantics { contentDescription = description }
                .testTag(ScreenTag.NAV_ADD),
        contentAlignment = Alignment.Center,
    ) {
      Text("＋", color = Color.White, fontSize = 26.sp, lineHeight = 26.sp)
    }
    Text(
        stringResource(R.string.nav_add),
        style = MaterialTheme.typography.labelSmall,
        color = p.muted,
        modifier = Modifier.padding(top = 4.dp),
    )
  }
}
