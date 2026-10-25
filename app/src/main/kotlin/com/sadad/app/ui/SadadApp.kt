package com.sadad.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.absoluteOffset
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import com.sadad.app.SadadApplication
import com.sadad.app.feature.activity.ActivityScreen
import com.sadad.app.feature.activity.OperationsLogScreen
import com.sadad.app.feature.auth.AwaitingApprovalScreen
import com.sadad.app.feature.auth.LoginScreen
import com.sadad.app.feature.auth.PasswordChangeScreen
import com.sadad.app.feature.auth.navigateAuth
import com.sadad.app.feature.contactform.ContactFormScreen
import com.sadad.app.feature.contacts.ContactDetailScreen
import com.sadad.app.feature.home.HomeScreen
import com.sadad.app.feature.lock.LockGate
import com.sadad.app.feature.notifications.NotificationsScreen
import com.sadad.app.feature.people.PeopleScreen
import com.sadad.app.feature.quickadd.QuickAddScreen
import com.sadad.app.feature.recovery.RecoveryScreen
import com.sadad.app.feature.settings.StoreScreen
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
  val graph = (LocalContext.current.applicationContext as SadadApplication).graph
  val syncDot by remember(graph) { syncDot(graph) }.collectAsStateWithLifecycle(SyncDot.NONE)
  // One step back, or nowhere when this is the first screen.
  val back: () -> Unit = { navController.navigateUp() }
  SadadTheme(dark = dark) {
    LockGate {
      // Arabic-first, so the shell mirrors like the Java app did, whatever the device locale.
      CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        SadadShell(navController, startDestination, syncDot) {
          composable<Login> { LoginScreen(onNavigate = navController::navigateAuth) }
          composable<PasswordChange> {
            PasswordChangeScreen(onNavigate = navController::navigateAuth)
          }
          composable<AwaitingApproval> {
            AwaitingApprovalScreen(onNavigate = navController::navigateAuth)
          }
          composable<Home> {
            HomeScreen(
                onOpenContact = { navController.navigate(ContactDetail(it)) },
                onOpenPeople = { navController.navigateTab(People(it)) },
                onOpenNotifications = {
                  navController.navigate(Notifications) { launchSingleTop = true }
                },
                onRecord = { navController.navigate(QuickAdd()) },
            )
          }
          composable<People> {
            PeopleScreen(onOpenContact = { navController.navigate(ContactDetail(it)) })
          }
          composable<ContactDetail> {
            val contactId = it.toRoute<ContactDetail>().contactId
            ContactDetailScreen(
                contactId = contactId,
                // A Contact opened from a reminder is the first screen: back goes to People.
                onBack = { if (!navController.navigateUp()) navController.navigateTab(People()) },
                onRecord = { id, payment -> navController.navigate(QuickAdd(id, payment)) },
                onEdit = { navController.navigate(ContactForm(contactId)) },
            )
          }
          composable<ContactForm> { ContactFormScreen(it.toRoute<ContactForm>().contactId) }
          composable<QuickAdd> {
            val route = it.toRoute<QuickAdd>()
            QuickAddScreen(
                contactId = route.contactId,
                payment = route.payment,
                onClose = back,
                onOpenContact = { id ->
                  navController.navigate(ContactDetail(id)) {
                    popUpTo<QuickAdd> { inclusive = true }
                  }
                },
            )
          }
          composable<Activity> {
            ActivityScreen(onOpenContact = { navController.navigate(ContactDetail(it)) })
          }
          composable<OperationsLog> { OperationsLogScreen(onBack = back) }
          composable<Store> {
            StoreScreen(
                onOpenTrash = { navController.navigate(Trash) },
                onOpenRecovery = { navController.navigate(Recovery) },
                onChangePassword = { navController.navigate(PasswordChange) },
                onAddStore = { navController.navigateAuth(Login) },
                onNeedsLogin = { navController.navigateAuth(Login) },
                onOpenOperationsLog = { navController.navigate(OperationsLog) },
                onOpenNotifications = {
                  navController.navigate(Notifications) { launchSingleTop = true }
                },
            )
          }
          composable<Notifications> { NotificationsScreen(onBack = back) }
          composable<Trash> { TrashScreen(onBack = back) }
          composable<Recovery> { RecoveryScreen(onBack = back) }
        }
      }
    }
  }
}

/**
 * The bar and the padded content area every destination lives in. There is no top bar: each screen
 * draws its own header. Edge-to-edge is on, so the Scaffold hands the system-bar insets to the
 * content as padding; no screen handles them itself. `internal` so the shell and inset tests can
 * render real or stub destinations in the real shell.
 */
@Composable
internal fun SadadShell(
    navController: NavHostController,
    startDestination: Any,
    syncDot: SyncDot,
    destinations: NavGraphBuilder.() -> Unit,
) {
  val destination = navController.currentBackStackEntryAsState().value?.destination
  Scaffold(
      modifier = Modifier.sadadPageBackground(),
      containerColor = Color.Transparent,
      contentColor = sadadPalette.text,
      bottomBar = { if (destination.isAny(WITH_BAR)) SadadBottomBar(navController, syncDot) },
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

/** The four tabs and the contact page show the bar; quick add, forms and sub-pages hide it. */
private val WITH_BAR =
    listOf(Home::class, People::class, ContactDetail::class, Activity::class, Store::class)

private fun NavDestination?.isAny(routes: List<KClass<*>>) =
    this != null && routes.any { route -> hierarchy.any { it.hasRoute(route) } }

private enum class Tab {
  HOME,
  PEOPLE,
  ACTIVITY,
  STORE,
}

/** The tab a destination belongs to. */
private fun activeTab(destination: NavDestination?): Tab =
    when {
      destination.isAny(listOf(People::class, ContactDetail::class)) -> Tab.PEOPLE
      destination.isAny(listOf(Activity::class)) -> Tab.ACTIVITY
      destination.isAny(
          listOf(Store::class, OperationsLog::class, Trash::class, Recovery::class)
      ) -> Tab.STORE
      else -> Tab.HOME
    }

/** A tab switch keeps one copy of each tab and its saved state, as bottom bars do. */
private fun NavHostController.navigateTab(destination: Any) =
    navigate(destination) {
      popUpTo(graph.findStartDestination().id) { saveState = true }
      launchSingleTop = true
      restoreState = true
    }

/**
 * الرئيسية · الأشخاص · «+» تسجيل · الحركات · المتجر, right to left (the Nav board). The chosen tab
 * has a halo behind its icon; المتجر carries the [syncDot]. `internal` so the shell tests can
 * render it alone.
 */
@Composable
internal fun SadadBottomBar(navController: NavHostController, syncDot: SyncDot) {
  val active = activeTab(navController.currentBackStackEntryAsState().value?.destination)
  val p = sadadPalette
  Row(
      modifier =
          Modifier.fillMaxWidth()
              .shadow(8.dp)
              .background(p.surface)
              .windowInsetsPadding(WindowInsets.navigationBars)
              .padding(start = 6.dp, end = 6.dp, top = 14.dp, bottom = 10.dp)
              .selectableGroup(),
      verticalAlignment = Alignment.Bottom,
  ) {
    TabItem(R.string.nav_home, R.drawable.ic_home, ScreenTag.NAV_HOME, active == Tab.HOME) {
      navController.navigateTab(Home)
    }
    TabItem(R.string.nav_people, R.drawable.ic_people, ScreenTag.NAV_PEOPLE, active == Tab.PEOPLE) {
      navController.navigateTab(People())
    }
    RecordItem { navController.navigate(QuickAdd()) }
    TabItem(
        R.string.nav_activity,
        R.drawable.ic_activity,
        ScreenTag.NAV_ACTIVITY,
        active == Tab.ACTIVITY,
    ) {
      navController.navigateTab(Activity)
    }
    TabItem(
        R.string.nav_store,
        R.drawable.ic_store,
        ScreenTag.NAV_STORE,
        active == Tab.STORE,
        syncDot,
    ) {
      navController.navigateTab(Store)
    }
  }
}

@Composable
private fun RowScope.TabItem(
    labelRes: Int,
    iconRes: Int,
    tag: String,
    selected: Boolean,
    syncDot: SyncDot = SyncDot.NONE,
    onClick: () -> Unit,
) {
  val p = sadadPalette
  val ink = if (selected) (if (p.isDark) p.accent else p.primary) else p.muted
  // The halo fades in over 580ms when the tab becomes the chosen one, as the Java animator did.
  val halo by animateFloatAsState(if (selected) 1f else 0f, tween(580), label = "halo")
  val description =
      stringResource(
          when (syncDot) {
            SyncDot.NONE -> labelRes
            SyncDot.PENDING -> R.string.nav_store_pending
            SyncDot.OFFLINE -> R.string.nav_store_offline
          }
      )
  Column(
      modifier =
          Modifier.weight(1f)
              .height(64.dp)
              .selectable(selected = selected, role = Role.Tab, onClick = onClick)
              .semantics { contentDescription = description }
              .testTag(tag),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
  ) {
    Box(Modifier.size(52.dp, 34.dp), contentAlignment = Alignment.Center) {
      Box(
          Modifier.matchParentSize()
              .graphicsLayer { alpha = halo }
              .background(p.accent.copy(alpha = if (p.isDark) 0.20f else 0.22f), HALO_SHAPE)
      )
      Icon(painterResource(iconRes), null, tint = ink, modifier = Modifier.size(23.dp))
      if (syncDot != SyncDot.NONE) {
        Box(
            Modifier.align(AbsoluteAlignment.TopLeft)
                .absoluteOffset(x = 10.dp, y = 3.dp)
                .size(10.dp)
                .background(
                    if (syncDot == SyncDot.OFFLINE) OFFLINE_RED else PENDING_AMBER,
                    CircleShape,
                )
                .border(2.dp, p.surface, CircleShape)
        )
      }
    }
    Text(
        stringResource(labelRes),
        color = ink,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
    )
  }
}

/** The raised round «تسجيل»: a debt or a payment, starting from the person. */
@Composable
private fun RowScope.RecordItem(onClick: () -> Unit) {
  val p = sadadPalette
  val description = stringResource(R.string.nav_record_description)
  Column(
      modifier =
          Modifier.weight(1f)
              .height(72.dp)
              .clickable(role = Role.Button, onClick = onClick)
              .semantics { contentDescription = description }
              .testTag(ScreenTag.NAV_RECORD),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.Bottom),
  ) {
    Box(
        modifier =
            Modifier.size(60.dp)
                .shadow(8.dp, CircleShape)
                .background(p.primary, CircleShape)
                .border(4.dp, p.bg, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
      Icon(
          painterResource(R.drawable.ic_record),
          null,
          tint = Color.White,
          modifier = Modifier.size(28.dp),
      )
    }
    Text(
        stringResource(R.string.nav_record),
        color = if (p.isDark) p.accent else p.primary,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Bold,
    )
  }
}

private val HALO_SHAPE = RoundedCornerShape(17.dp)
private val PENDING_AMBER = Color(0xFFE0A23A)
private val OFFLINE_RED = Color(0xFFC13634)
