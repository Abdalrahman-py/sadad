package com.sadad.app.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import com.sadad.app.feature.lock.BiometricAuth
import com.sadad.app.feature.lock.rememberActivityBiometric
import com.sadad.app.feature.photo.PhotoEditor
import com.sadad.app.feature.photo.PhotoKind
import com.sadad.app.ui.components.ScreenHeader

/** Stable handles the settings tests find the list and its rows by. */
object SettingsTags {
  const val LIST = "settings:list"
}

/**
 * The المتجر tab. Until ticket 86 redesigns it, the existing settings list under a header; each way
 * out is a route the shell owns.
 */
@Composable
fun StoreScreen(
    onOpenTrash: () -> Unit,
    onOpenRecovery: () -> Unit,
    onChangePassword: () -> Unit,
    onAddStore: () -> Unit,
    onNeedsLogin: () -> Unit,
    onOpenOperationsLog: () -> Unit,
    onOpenNotifications: () -> Unit,
) {
  Column(Modifier.fillMaxSize()) {
    ScreenHeader(stringResource(R.string.nav_store))
    SettingsScreen(
        navigation =
            SettingsNavigation(
                onOpenTrash = onOpenTrash,
                onOpenNotifications = onOpenNotifications,
                onOpenActivityLog = onOpenOperationsLog,
                onOpenRecovery = onOpenRecovery,
                onChangePassword = onChangePassword,
                onAddStore = onAddStore,
                onNeedsLogin = onNeedsLogin,
            )
    )
  }
}

/**
 * Where the settings rows lead. The routes belong to the shell (`ui/SadadApp.kt`), so each is a
 * callback; a screen opened without them just does nothing on those rows.
 */
data class SettingsNavigation(
    val onOpenTrash: () -> Unit = {},
    val onOpenNotifications: () -> Unit = {},
    /** The ledger operations log. */
    val onOpenActivityLog: () -> Unit = {},
    val onOpenRecovery: () -> Unit = {},
    /** The password-change route, used voluntarily here. */
    val onChangePassword: () -> Unit = {},
    /** The Login route, to add another store. */
    val onAddStore: () -> Unit = {},
    /** No store is open any more: signed out, removed, or the other store has no session. */
    val onNeedsLogin: () -> Unit = {},
)

@Composable
internal fun SettingsScreen(
    modifier: Modifier = Modifier,
    navigation: SettingsNavigation = SettingsNavigation(),
    biometric: BiometricAuth = rememberActivityBiometric(),
    settingsViewModel: SettingsViewModel = settingsViewModel(biometric),
) {
  val state by settingsViewModel.state.collectAsStateWithLifecycle()
  val context = LocalContext.current
  var showStores by rememberSaveable { mutableStateOf(false) }
  var showStorePhoto by rememberSaveable { mutableStateOf(false) }
  val photoStoreId = state.accounts.firstOrNull { it.active }?.storeUserId
  // Re-read on resume: a fingerprint enrolled in the system settings shows the toggle on return.
  var biometricAvailable by remember { mutableStateOf(biometric.available()) }
  LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { biometricAvailable = biometric.available() }
  LaunchedEffect(state.outcome) {
    if (state.outcome == SettingsOutcome.NEEDS_LOGIN) {
      settingsViewModel.consumeOutcome()
      navigation.onNeedsLogin()
    }
  }
  LazyColumn(
      modifier = modifier.testTag(SettingsTags.LIST),
      verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    storeSection(state, settingsViewModel)
    item {
      TextButton(onClick = { showStorePhoto = true }, enabled = photoStoreId != null) {
        Text(stringResource(R.string.photo_store))
      }
    }
    dataSection(state, settingsViewModel, navigation, context)
    syncSection(state, settingsViewModel, navigation)
    lockSection(state, settingsViewModel, biometricAvailable)
    appearanceSection(state, settingsViewModel)
    accountSection(state, settingsViewModel, navigation, context) { showStores = true }
  }
  if (showStorePhoto && photoStoreId != null) {
    PhotoEditor(photoStoreId, PhotoKind.STORE, { showStorePhoto = false }) {
      settingsViewModel.state.value.accounts.firstOrNull { it.active }?.storeUserId
    }
  }
  SettingsDialogs(
      state = state,
      model = settingsViewModel,
      navigation = navigation,
      showStores = showStores,
      onHideStores = { showStores = false },
  )
}

@Composable
private fun settingsViewModel(biometric: BiometricAuth): SettingsViewModel {
  val app = LocalContext.current.applicationContext as SadadApplication
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
              )
            }
          }
  )
}
