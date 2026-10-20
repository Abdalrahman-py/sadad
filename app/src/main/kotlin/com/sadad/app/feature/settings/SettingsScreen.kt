package com.sadad.app.feature.settings

import androidx.biometric.BiometricManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.sadad.app.SadadApplication
import com.sadad.app.data.sync.SyncScheduler

/** Stable handles the settings tests find the list and its rows by. */
object SettingsTags {
  const val LIST = "settings:list"
}

/**
 * Where the settings rows lead. The routes belong to the shell (`ui/SadadApp.kt`), so each is a
 * callback; a screen opened without them just does nothing on those rows.
 */
data class SettingsNavigation(
    val onOpenTrash: () -> Unit = {},
    val onOpenNotifications: () -> Unit = {},
    /** The History route, on its activity tab. */
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
fun SettingsScreen(
    modifier: Modifier = Modifier,
    navigation: SettingsNavigation = SettingsNavigation(),
    settingsViewModel: SettingsViewModel = settingsViewModel(),
) {
  val state by settingsViewModel.state.collectAsStateWithLifecycle()
  val context = LocalContext.current
  var showStores by rememberSaveable { mutableStateOf(false) }
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
    dataSection(state, settingsViewModel, navigation, context)
    syncSection(state, settingsViewModel, navigation)
    lockSection(state, settingsViewModel)
    appearanceSection(state, settingsViewModel)
    accountSection(state, settingsViewModel, navigation, context) { showStores = true }
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
private fun settingsViewModel(): SettingsViewModel {
  val app = LocalContext.current.applicationContext as SadadApplication
  return viewModel(
      factory =
          viewModelFactory {
            initializer {
              SettingsViewModel(
                  graph = app.graph,
                  saved = createSavedStateHandle(),
                  preferences = ThemePreference.get(app),
                  lock = com.sadad.app.feature.lock.AppLock.get(app),
                  scheduler = SyncScheduler(app),
                  biometricAvailable = {
                    BiometricManager.from(app)
                        .canAuthenticate(
                            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                                BiometricManager.Authenticators.BIOMETRIC_WEAK
                        ) == BiometricManager.BIOMETRIC_SUCCESS
                  },
              )
            }
          }
  )
}
