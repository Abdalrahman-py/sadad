package com.sadad.app.feature.account

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.sadad.app.AppGraph
import com.sadad.app.R
import com.sadad.app.data.sync.SyncScheduler
import com.sadad.app.feature.lock.AppLock
import com.sadad.app.feature.settings.SettingsDialogs
import com.sadad.app.feature.settings.SettingsNavigation
import com.sadad.app.feature.settings.SettingsOutcome
import com.sadad.app.feature.settings.SettingsViewModel
import com.sadad.app.feature.settings.ThemeChoice
import com.sadad.app.feature.settings.ThemePreference
import kotlinx.coroutines.launch

@Composable
internal fun AppearanceDialog(onDismiss: () -> Unit) {
  val context = LocalContext.current
  val preferences = remember(context) { ThemePreference.get(context) }
  val theme by preferences.theme.collectAsStateWithLifecycle(ThemeChoice.SYSTEM)
  val scope = rememberCoroutineScope()
  AlertDialog(
      onDismissRequest = onDismiss,
      title = { Text(stringResource(R.string.account_appearance)) },
      text = {
        Column {
          for ((choice, label) in
              listOf(
                  ThemeChoice.SYSTEM to R.string.account_theme_system,
                  ThemeChoice.LIGHT to R.string.account_theme_light,
                  ThemeChoice.DARK to R.string.account_theme_dark,
              )) {
            TextButton(
                onClick = {
                  scope.launch {
                    preferences.setTheme(choice)
                    onDismiss()
                  }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
              Text((if (choice == theme) "✓ " else "") + stringResource(label))
            }
          }
        }
      },
      confirmButton = {
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.account_back)) }
      },
  )
}

/** Reuses Settings' actual dialog and switching API; it keeps observing delayed switch outcomes. */
@Composable
internal fun SavedStoresController(
    graph: AppGraph,
    show: Boolean,
    onDismiss: () -> Unit,
    onAddStore: () -> Unit,
    onNeedsLogin: () -> Unit,
) {
  val context = LocalContext.current.applicationContext
  val model: SettingsViewModel =
      viewModel(
          key = "account-menu-stores",
          factory =
              viewModelFactory {
                initializer {
                  SettingsViewModel(
                      graph,
                      createSavedStateHandle(),
                      ThemePreference.get(context),
                      AppLock.get(context),
                      SyncScheduler(context),
                  )
                }
              },
      )
  val state by model.state.collectAsStateWithLifecycle()
  LaunchedEffect(state.outcome) {
    if (state.outcome == SettingsOutcome.NEEDS_LOGIN) {
      model.consumeOutcome()
      onNeedsLogin()
    }
  }
  SettingsDialogs(
      state,
      model,
      SettingsNavigation(onAddStore = onAddStore, onNeedsLogin = onNeedsLogin),
      show,
      onDismiss,
  )
}
