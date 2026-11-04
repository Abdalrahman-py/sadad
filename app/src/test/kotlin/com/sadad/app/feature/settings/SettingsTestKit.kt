package com.sadad.app.feature.settings

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.sadad.app.data.sync.SyncScheduling
import com.sadad.app.data.sync.SyncStatus
import com.sadad.app.feature.lock.AppLock
import com.sadad.app.testing.TestGraph
import java.io.File
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** The fixed clock the settings tests share: zone `Asia/Gaza`, 17 October 2026. */
internal val settingsZone: ZoneId = ZoneId.of("Asia/Gaza")
internal val settingsClock: Clock =
    Clock.fixed(
        LocalDate.of(2026, 10, 17).atTime(12, 0).atZone(settingsZone).toInstant(),
        settingsZone,
    )

/** Records `syncNow` instead of enqueueing WorkManager work. */
internal class RecordingScheduler : SyncScheduling {
  val syncNow = mutableListOf<String>()

  override fun syncNow(storeUserId: String) {
    syncNow += storeUserId
  }

  override fun schedulePeriodic(storeUserId: String) = Unit

  override fun cancel(storeUserId: String) = Unit
}

/**
 * What a settings test needs besides the graph: its own preference and lock files in the cache
 * directory (the process-wide singletons would leak between tests) and a ViewModel factory.
 */
internal class SettingsKit(private val fixture: TestGraph) : AutoCloseable {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  val file = File(fixture.context.cacheDir, "settings-${UUID.randomUUID()}.preferences_pb")
  val preferences: ThemePreference = ThemePreference.create(scope, file)
  val lock =
      AppLock(
          File(fixture.context.cacheDir, "lock-${UUID.randomUUID()}.preferences_pb"),
          scope,
          elapsedRealtime = { 0L },
          bootCount = { 1 },
      )
  val recording = RecordingScheduler()

  /** What the next view model gets; a test can swap in one that fails. */
  var scheduler: SyncScheduling = recording

  fun viewModel(
      saved: SavedStateHandle = SavedStateHandle(),
      biometricAvailable: Boolean = false,
      syncStatus: ((String) -> Flow<SyncStatus>)? = null,
  ): SettingsViewModel {
    val model =
        SettingsViewModel(
            source = fixture.graph.settingsSource(),
            saved = saved,
            preferences = preferences,
            lock = lock,
            scheduler = scheduler,
            clock = settingsClock,
            biometricAvailable = { biometricAvailable },
            syncStatus = syncStatus ?: fixture.graph::syncStatus,
            reminders = { flowOf(emptyList()) },
            hiddenTrash = { flowOf(emptySet()) },
        )
    fixture.closeWithStore(model.viewModelScope)
    return model
  }

  override fun close() = scope.cancel()
}
