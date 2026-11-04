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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
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
import com.sadad.app.LocalGraph
import com.sadad.app.R
import com.sadad.app.feature.activity.ActivityScreen
import com.sadad.app.feature.auth.AwaitingApprovalScreen
import com.sadad.app.feature.auth.LoginScreen
import com.sadad.app.feature.auth.PasswordChangeScreen
import com.sadad.app.feature.auth.navigateAuth
import com.sadad.app.feature.contactform.ContactFormScreen
import com.sadad.app.feature.contactpicker.ContactPickerScreen
import com.sadad.app.feature.contactpicker.PickedContact
import com.sadad.app.feature.contacts.ContactDetailScreen
import com.sadad.app.feature.home.HomeScreen
import com.sadad.app.feature.lock.LockGate
import com.sadad.app.feature.notifications.NotificationsScreen
import com.sadad.app.feature.people.PeopleScreen
import com.sadad.app.feature.quickadd.QuickAddScreen
import com.sadad.app.feature.recovery.RecoveryScreen
import com.sadad.app.feature.settings.StoreScreen
import com.sadad.app.feature.trash.TrashScreen
import com.sadad.app.feature.updates.AppUpdateScreen
import com.sadad.app.feature.updates.UpdateCenter
import com.sadad.app.feature.updates.UpdateState
import com.sadad.app.rememberGraph
import com.sadad.app.ui.components.sadadPageBackground
import com.sadad.app.ui.components.topHairline
import com.sadad.app.ui.theme.SadadTheme
import com.sadad.app.ui.theme.sadadPalette
import kotlin.reflect.KClass

@Composable
fun SadadApp(
    startDestination: Any = Home,
    dark: Boolean = isSystemInDarkTheme(),
    navController: NavHostController = rememberNavController(),
) {
  val graph = rememberGraph()
  if (graph == null) {
    // The Keystore read behind the first graph runs off main; until it lands there is nothing to
    // show but the page.
    SadadTheme(dark = dark) { Box(Modifier.fillMaxSize().sadadPageBackground()) }
    return
  }
  val syncDot by remember(graph) { syncDot(graph) }.collectAsStateWithLifecycle(SyncDot.NONE)
  // One step back, or nowhere when this is the first screen.
  val back: () -> Unit = { navController.navigateUp() }
  SadadTheme(dark = dark) {
    LockGate {
      // Arabic-first, so the shell mirrors like the Java app did, whatever the device locale.
      CompositionLocalProvider(
          LocalLayoutDirection provides LayoutDirection.Rtl,
          LocalGraph provides graph,
      ) {
        SadadShell(navController, startDestination, syncDot) {
          composable<Login> {
            LoginScreen(
                onNavigate = navController::navigateAuth,
                // Adding a store from the Store tab keeps that tab underneath, so back returns.
                onBack = if (navController.previousBackStackEntry != null) back else null,
            )
          }
          composable<PasswordChange> {
            PasswordChangeScreen(onNavigate = navController::navigateAuth)
          }
          composable<AwaitingApproval> {
            AwaitingApprovalScreen(onNavigate = navController::navigateAuth)
          }
          composable<Home> {
            HomeScreen(
                onOpenContact = { navController.navigate(ContactDetail(it)) },
                // Not navigateTab: restoring the tab's saved entry would bring back its old filter.
                onOpenPeople = { filter ->
                  navController.navigate(People(filter)) {
                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                    launchSingleTop = true
                  }
                },
                onOpenNotifications = {
                  navController.navigate(Notifications) { launchSingleTop = true }
                },
                onRecord = { navController.navigate(QuickAdd()) },
            )
          }
          composable<People> {
            PeopleScreen(
                onOpenContact = { navController.navigate(ContactDetail(it)) },
                onAdd = { navController.navigate(ContactForm()) },
            )
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
          composable<ContactForm> { entry ->
            val editing = entry.toRoute<ContactForm>().contactId
            ContactFormScreen(
                contactId = editing,
                initialName = entry.toRoute<ContactForm>().initialName,
                // The contacts picker's result comes back through the form's own entry.
                picked = entry.savedStateHandle.get<PickedContact>(PICKED),
                onPickDelivered = { entry.savedStateHandle.remove<PickedContact>(PICKED) },
                onBack = back,
                onOpenPicker = { navController.navigate(ContactPicker) },
                onOpenContact = { id ->
                  if (editing != null) {
                    // Editing came from that person's page: saving goes back to it.
                    back()
                  } else {
                    // «حفظ فقط» on a new person: their page replaces the form.
                    navController.openSavedContact(id)
                  }
                },
                // The picked number belongs to someone in the archive: the trash lists them.
                onOpenTrash = {
                  navController.navigate(Trash) {
                    popUpTo<ContactForm> { inclusive = true }
                  }
                },
                // «حفظ وتسجيل دين» on a new person: QuickAdd records their first debt.
                onRecord = { id -> navController.openFirstDebtRecording(id) },
            )
          }
          composable<ContactPicker> {
            ContactPickerScreen(
                onResult = { result ->
                  // Back to the form with the result in its entry; it never re-enters the form.
                  navController.previousBackStackEntry?.savedStateHandle?.set(PICKED, result)
                  navController.popBackStack()
                },
                onBack = back,
            )
          }
          composable<QuickAdd> {
            val route = it.toRoute<QuickAdd>()
            QuickAddScreen(
                contactId = route.contactId,
                payment = route.payment,
                onClose = back,
                // A picked number of someone in the archive: they are restored from the trash.
                onOpenTrash = {
                  navController.navigate(Trash) { popUpTo<QuickAdd> { inclusive = true } }
                },
                onOpenContact = { id ->
                  // Single top: quick add opened from this contact returns to it, not a second
                  // copy.
                  navController.navigate(ContactDetail(id)) {
                    popUpTo<QuickAdd> { inclusive = true }
                    launchSingleTop = true
                  }
                },
            )
          }
          composable<Activity> {
            ActivityScreen(onOpenContact = { navController.navigate(ContactDetail(it)) })
          }
          composable<Store> {
            StoreScreen(
                onOpenTrash = { navController.navigate(Trash) },
                onOpenRecovery = { navController.navigate(Recovery) },
                onChangePassword = { navController.navigate(PasswordChange) },
                // Not navigateAuth: that empties the stack, and adding a store can be cancelled.
                onAddStore = { navController.navigate(Login) { launchSingleTop = true } },
                onNeedsLogin = { navController.navigateAuth(Login) },
                onOpenUpdates = { navController.navigate(AppUpdate) { launchSingleTop = true } },
            )
          }
          composable<Notifications> { NotificationsScreen(onBack = back) }
          composable<Trash> { TrashScreen(onBack = back) }
          composable<Recovery> { RecoveryScreen(onBack = back) }
          composable<AppUpdate> { AppUpdateScreen(onBack = back) }
        }
        // Only on the home tab: a release never interrupts someone in the middle of an entry.
        val onHome =
            navController.currentBackStackEntryAsState().value?.destination?.hasRoute<Home>() ==
                true
        if (onHome) {
          UpdatePrompt(onOpen = { navController.navigate(AppUpdate) { launchSingleTop = true } })
        }
      }
    }
  }
}

/**
 * «تحديث سداد» at app start, as Seal and Mihon do — but «لاحقاً» only silences it for a week, and
 * the settings row keeps the release in view until it is installed.
 */
@Composable
private fun UpdatePrompt(onOpen: () -> Unit) {
  val context = LocalContext.current
  val center = remember(context) { UpdateCenter.get(context.applicationContext) }
  val state by center.state.collectAsStateWithLifecycle()
  val manifest = state.manifest
  if (manifest == null || !state.prompts || state.phase != UpdateState.Phase.AVAILABLE) return
  AlertDialog(
      onDismissRequest = center::snooze,
      title = { Text(stringResource(R.string.updates_prompt_title)) },
      text = { Text(stringResource(R.string.updates_prompt_text, manifest.versionName)) },
      confirmButton = {
        TextButton(
            onClick = {
              center.dismissPrompt()
              onOpen()
            }
        ) {
          Text(stringResource(R.string.updates_prompt_open))
        }
      },
      dismissButton = {
        TextButton(onClick = center::snooze) {
          Text(stringResource(R.string.updates_later))
        }
      },
  )
}

/**
 * The bar and the padded content area every destination lives in. There is no top bar: each screen
 * draws its own header. Edge-to-edge is on, so the Scaffold hands the system-bar insets to the
 * content as padding; no screen handles them itself. `internal` so the shell and inset tests can
 * render real or stub destinations in the real shell.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SadadShell(
    navController: NavHostController,
    startDestination: Any,
    syncDot: SyncDot,
    destinations: NavGraphBuilder.() -> Unit,
) {
  val destination = navController.currentBackStackEntryAsState().value?.destination
  val fromStore = navController.previousBackStackEntry?.destination?.hasRoute<Store>() == true
  // The keyboard covers the bottom bar, so it hides while the keyboard is visible.
  val showNavigation =
      !WindowInsets.isImeVisible &&
          destination != null &&
          (!destination.isAny(AUTH_GATES) ||
              (fromStore && !destination.hasRoute<AwaitingApproval>()))
  Scaffold(
      modifier = Modifier.sadadPageBackground(),
      containerColor = Color.Transparent,
      contentColor = sadadPalette.text,
      bottomBar = { if (showNavigation) SadadBottomBar(navController, syncDot) },
  ) { insets ->
    NavHost(
        navController = navController,
        startDestination = startDestination,
        // Consumed, so a destination with its own Scaffold does not add the bar insets again.
        // imePadding after consume adds only the keyboard part the bar insets have not covered,
        // so every destination clears the keyboard without handling it itself.
        modifier = Modifier.padding(insets).consumeWindowInsets(insets).imePadding(),
        builder = destinations,
    )
  }
}

/** Entry gates hide navigation; account pages opened from Store remain inside the store flow. */
private val AUTH_GATES = listOf(Login::class, PasswordChange::class, AwaitingApproval::class)

private fun NavDestination?.isAny(routes: List<KClass<*>>) =
    this != null && routes.any { route -> hierarchy.any { it.hasRoute(route) } }

private enum class Tab {
  HOME,
  PEOPLE,
  ACTIVITY,
  STORE,
}

/** The tab a destination belongs to. */
private fun activeTab(destination: NavDestination?): Tab? =
    when {
      destination.isAny(listOf(QuickAdd::class)) -> null
      destination.isAny(listOf(People::class, ContactDetail::class, ContactForm::class)) ->
          Tab.PEOPLE
      destination.isAny(listOf(Activity::class)) -> Tab.ACTIVITY
      destination.isAny(
          listOf(
              Store::class,
              Trash::class,
              Recovery::class,
              AppUpdate::class,
              Login::class,
              PasswordChange::class,
          )
      ) -> Tab.STORE
      else -> Tab.HOME
    }

/** Switching tabs restores their state; tapping the current section returns to its root. */
private fun NavHostController.navigateTab(destination: Any) {
  val target =
      when (destination) {
        Home -> Tab.HOME
        is People -> Tab.PEOPLE
        Activity -> Tab.ACTIVITY
        Store -> Tab.STORE
        else -> null
      }
  val sameSection = activeTab(currentDestination) == target
  navigate(destination) {
    popUpTo(graph.findStartDestination().id) { saveState = !sameSection }
    launchSingleTop = true
    restoreState = !sameSection && destination != Home
  }
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
              .background(p.surface)
              .topHairline(p.line)
              .windowInsetsPadding(WindowInsets.navigationBars)
              .padding(start = 8.dp, end = 8.dp, top = 16.dp, bottom = 8.dp)
              .selectableGroup(),
      verticalAlignment = Alignment.Bottom,
  ) {
    TabItem(R.string.nav_home, R.drawable.ic_home, ScreenTag.NAV_HOME, active == Tab.HOME) {
      navController.navigateTab(Home)
    }
    TabItem(R.string.nav_people, R.drawable.ic_people, ScreenTag.NAV_PEOPLE, active == Tab.PEOPLE) {
      navController.navigateTab(People())
    }
    RecordItem { navController.navigate(QuickAdd()) { launchSingleTop = true } }
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
            SyncDot.STUCK -> R.string.nav_store_stuck
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
    Box(Modifier.size(52.dp, 36.dp), contentAlignment = Alignment.Center) {
      Box(
          Modifier.matchParentSize()
              .graphicsLayer { alpha = halo }
              .background(p.accent.copy(alpha = if (p.isDark) 0.20f else 0.22f), PILL)
      )
      Icon(painterResource(iconRes), null, tint = ink, modifier = Modifier.size(24.dp))
      if (syncDot != SyncDot.NONE) {
        Box(
            Modifier.align(AbsoluteAlignment.TopLeft)
                .absoluteOffset(x = 12.dp, y = 4.dp)
                .size(12.dp)
                .background(
                    if (syncDot == SyncDot.OFFLINE || syncDot == SyncDot.STUCK) OFFLINE_RED
                    else PENDING_AMBER,
                    CircleShape,
                )
                .border(2.dp, p.surface, CircleShape)
        )
      }
    }
    Text(stringResource(labelRes), color = ink, style = MaterialTheme.typography.labelMedium)
  }
}

/** The raised round «تسجيل»: a debt or a payment, starting from the person. */
@Composable
private fun RowScope.RecordItem(onClick: () -> Unit) {
  val p = sadadPalette
  val description = stringResource(R.string.nav_record_description)
  Column(
      modifier =
          // The pill, gap and label (70dp) stand 6dp taller than the tabs, bottoms aligned.
          Modifier.weight(1f)
              .clickable(role = Role.Button, onClick = onClick)
              .semantics { contentDescription = description }
              .testTag(ScreenTag.NAV_RECORD),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.Bottom),
  ) {
    // A 60x44 pill in a 4dp ring of the page colour (68x52), on a soft 2dp shadow.
    Box(
        modifier =
            Modifier.size(68.dp, 52.dp)
                .shadow(2.dp, PILL)
                .background(p.bg, PILL)
                .padding(4.dp)
                .background(p.primary, PILL),
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
        style = MaterialTheme.typography.labelMedium,
    )
  }
}

private val PILL = RoundedCornerShape(percent = 50)
private val PENDING_AMBER = Color(0xFFE0A23A)
private val OFFLINE_RED = Color(0xFFC13634)

/** The back-stack key the contacts picker stores its [PickedContact] result under. */
private const val PICKED = "pickedContact"

/** «حفظ فقط» (and edit): the person's page replaces the form; back from it is the list again. */
internal fun NavHostController.openSavedContact(id: String) {
  navigate(ContactDetail(id)) {
    popUpTo<ContactForm> { inclusive = true }
    launchSingleTop = true
  }
}

/**
 * «حفظ وتسجيل دين»: QuickAdd opens for that person; the form leaves the stack, so back is People.
 */
internal fun NavHostController.openFirstDebtRecording(id: String) {
  navigate(QuickAdd(contactId = id)) {
    popUpTo<ContactForm> { inclusive = true }
    launchSingleTop = true
  }
}
