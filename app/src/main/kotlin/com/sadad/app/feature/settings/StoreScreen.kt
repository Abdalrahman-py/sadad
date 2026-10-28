package com.sadad.app.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.sadad.app.R
import com.sadad.app.SadadApplication
import com.sadad.app.data.sync.SyncScheduler
import com.sadad.app.feature.lock.AppLock
import com.sadad.app.feature.lock.rememberActivityBiometric
import com.sadad.app.feature.notifications.DueReminders
import com.sadad.app.feature.photo.PhotoEditor
import com.sadad.app.feature.photo.PhotoKind
import com.sadad.app.feature.trash.TrashHidden
import com.sadad.app.feature.updates.UpdateCenter
import com.sadad.app.ui.components.SadadToastHost
import com.sadad.app.ui.components.ScreenHeader
import kotlinx.coroutines.delay

/** Stable handles the store tab's tests find its list and its rows by. */
object SettingsTags {
  const val LIST = "settings:list"
  const val PHOTO = "settings:photo"
  const val PROFILE = "settings:profile"
  const val SWITCHER = "settings:switcher"
  const val ADD_STORE = "settings:add-store"
  const val SYNC_NOW = "settings:sync-now"
  const val RECOVERY = "settings:recovery"
  const val TRASH = "settings:trash"
  const val THEME = "settings:theme"
  const val NOTIFICATIONS = "settings:notifications"
  const val SUPPORT = "settings:support"
  const val SIGN_OUT = "settings:sign-out"
  const val REMOVE = "settings:remove"
  const val VERSION = "settings:version"
  const val UPDATE = "settings:update"
}

/**
 * Where the rows lead. The routes belong to the shell (`ui/SadadApp.kt`), so each is a callback; a
 * screen opened without them just does nothing on those rows.
 */
internal data class SettingsNavigation(
    val onOpenTrash: () -> Unit = {},
    val onOpenRecovery: () -> Unit = {},
    /** The password-change route, used voluntarily here. */
    val onChangePassword: () -> Unit = {},
    /** «مزامنة الآن»: the screen starts the sync and tells how it ended. */
    val onSyncNow: () -> Unit = {},
    /** The Login route, to add another store. */
    val onAddStore: () -> Unit = {},
    /** No store is open any more: signed out, removed, or the other store has no session. */
    val onNeedsLogin: () -> Unit = {},
)

/**
 * The المتجر tab, as `boards/Store.dc.html` draws it: the store card with its switcher, then
 * devices and sync, security, data, appearance, support and the version. Every section reads the
 * state the [SettingsViewModel] already exposes; the screen owns only what is drawn.
 */
@Composable
fun StoreScreen(
    onOpenTrash: () -> Unit = {},
    onOpenRecovery: () -> Unit = {},
    onChangePassword: () -> Unit = {},
    onAddStore: () -> Unit = {},
    onNeedsLogin: () -> Unit = {},
    onOpenUpdates: () -> Unit = {},
    settingsViewModel: SettingsViewModel = storeViewModel(),
) {
  val state by settingsViewModel.state.collectAsStateWithLifecycle()
  val context = LocalContext.current
  val updates = remember(context) { UpdateCenter.get(context.applicationContext) }
  val update by updates.state.collectAsStateWithLifecycle()
  var storesOpen by rememberSaveable { mutableStateOf(false) }
  var showStorePhoto by rememberSaveable { mutableStateOf(false) }
  val openStore = state.accounts.firstOrNull { it.active }?.storeUserId
  // «مزامنة الآن» says it started, then how it ended: sent, offline, or what refused.
  var syncAsked by rememberSaveable { mutableStateOf(false) }
  var toast by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
  val syncStarted = stringResource(R.string.settings_sync_started)
  val syncDone = stringResource(R.string.settings_sync_done)
  val phrase = statusPhrase(state.sync)
  LaunchedEffect(state.sync) {
    if (!syncAsked) return@LaunchedEffect
    when (state.sync) {
      SyncLine.Running -> Unit
      SyncLine.Idle -> {
        toast = syncDone to false
        syncAsked = false
      }
      else -> {
        toast = (phrase ?: syncDone) to true
        syncAsked = false
      }
    }
  }
  // A sync that finds nothing to do never leaves «Idle»: say so rather than stay silent.
  LaunchedEffect(syncAsked) {
    if (syncAsked) {
      delay(6_000)
      if (syncAsked && state.sync == SyncLine.Idle) {
        toast = syncDone to false
        syncAsked = false
      }
    }
  }
  // Re-read on resume: a fingerprint enrolled in the system settings shows its switch on return.
  LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { settingsViewModel.refreshBiometricSensor() }
  LaunchedEffect(state.outcome) {
    if (state.outcome == SettingsOutcome.NEEDS_LOGIN) {
      settingsViewModel.consumeOutcome()
      onNeedsLogin()
    }
  }
  val navigation =
      SettingsNavigation(
          onOpenTrash = onOpenTrash,
          onOpenRecovery = onOpenRecovery,
          onChangePassword = onChangePassword,
          onAddStore = onAddStore,
          onNeedsLogin = onNeedsLogin,
          onSyncNow = {
            syncAsked = true
            toast = syncStarted to false
            settingsViewModel.downloadLatest()
          },
      )
  Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
      ScreenHeader(stringResource(R.string.nav_store))
      LazyColumn(
          modifier = Modifier.fillMaxSize().testTag(SettingsTags.LIST),
          contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 28.dp),
          verticalArrangement = Arrangement.spacedBy(18.dp),
      ) {
        storeCard(
            state = state,
            model = settingsViewModel,
            storesOpen = storesOpen,
            onToggleStores = { storesOpen = !storesOpen },
            onOpenPhoto = { showStorePhoto = true },
            onAddStore = onAddStore,
            onPickStore = { storesOpen = false },
        )
        devicesSection(state, settingsViewModel, navigation)
        securitySection(state, settingsViewModel, navigation)
        dataSection(state, settingsViewModel, navigation, context)
        appearanceSection(state, settingsViewModel, context)
        accountSection(state, settingsViewModel)
        supportItem(context)
        updateItem(update, onOpenUpdates)
        versionItem()
      }
    }
    SadadToastHost(
        message = toast?.first,
        error = toast?.second == true,
        onDismiss = { toast = null },
        modifier = Modifier.align(Alignment.BottomCenter).padding(18.dp),
    )
  }
  if (showStorePhoto && openStore != null) {
    PhotoEditor(openStore, PhotoKind.STORE, { showStorePhoto = false }) {
      settingsViewModel.state.value.accounts.firstOrNull { it.active }?.storeUserId
    }
  }
  SettingsDialogs(state, settingsViewModel)
}

@Composable
private fun storeViewModel(): SettingsViewModel {
  val app = LocalContext.current.applicationContext as SadadApplication
  val biometric = rememberActivityBiometric()
  return viewModel(
      factory =
          viewModelFactory {
            initializer {
              SettingsViewModel(
                  graph = app.graph,
                  saved = createSavedStateHandle(),
                  preferences = ThemePreference.get(app),
                  lock = AppLock.get(app),
                  scheduler = SyncScheduler(app),
                  biometricAvailable = biometric::available,
                  reminders = DueReminders.get(app)::dismissed,
                  hiddenTrash = { storeUserId -> TrashHidden.get(app, storeUserId).ids },
              )
            }
          }
  )
}
