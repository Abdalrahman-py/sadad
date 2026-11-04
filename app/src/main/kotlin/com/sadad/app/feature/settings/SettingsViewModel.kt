package com.sadad.app.feature.settings

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sadad.app.data.sync.SyncScheduling
import com.sadad.app.data.sync.SyncStatus
import com.sadad.app.feature.lock.AppLock
import com.sadad.app.feature.notifications.Reminder
import java.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * The settings screen: the one place the six modules are assembled, for the app and for the tests.
 * It only calls the settings source, the lock, the scheduler and the statement export: no session,
 * lock or export logic lives here. Every source is a required parameter, so no caller leans on a
 * default.
 */
class SettingsViewModel(
    source: SettingsSource,
    saved: SavedStateHandle,
    preferences: ThemePreference,
    lock: AppLock,
    scheduler: SyncScheduling,
    clock: Clock,
    biometricAvailable: () -> Boolean,
    syncStatus: (String) -> Flow<SyncStatus>,
    /** The dismissed due reminders the trash lists; empty when the screen has none to show. */
    reminders: (String) -> Flow<List<Reminder>>,
    /** The trash ids this phone removed for good, as the trash screen hides them. */
    hiddenTrash: (String) -> Flow<Set<String>>,
) : ViewModel() {
  internal val ui = SettingsUi(viewModelScope, saved)
  internal val store = StoreModule(source, ui)
  internal val devices = DevicesModule(source, scheduler, syncStatus, ui)
  internal val security = SecurityModule(lock, biometricAvailable, ui)
  internal val data = DataModule(source, preferences, reminders, hiddenTrash, clock, ui)
  internal val appearance = AppearanceModule(preferences, ui)
  internal val account = AccountModule(source, ui)

  val state: StateFlow<SettingsState> =
      combine(
              combine(store.state, devices.state, security.state) { a, b, c -> Triple(a, b, c) },
              combine(data.state, appearance.state, ui.state) { a, b, c -> Triple(a, b, c) },
          ) { (storeSlice, devicesSlice, securitySlice), (dataSlice, theme, shared) ->
            SettingsState(
                loaded = true,
                store = storeSlice,
                devices = devicesSlice,
                security = securitySlice,
                data = dataSlice,
                theme = theme,
                busy = shared.busy,
                pending = shared.pending,
                pinError = shared.pinError,
                error = shared.error,
                outcome = shared.outcome,
            )
          }
          .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsState())
}
