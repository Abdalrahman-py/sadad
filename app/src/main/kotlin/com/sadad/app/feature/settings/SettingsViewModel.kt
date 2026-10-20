package com.sadad.app.feature.settings

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sadad.app.AppGraph
import com.sadad.app.R
import com.sadad.app.data.session.AccountsFile
import com.sadad.app.data.session.StoreScope
import com.sadad.app.data.sync.SyncScheduling
import com.sadad.app.data.sync.SyncStatus
import com.sadad.app.export.StatementExport
import com.sadad.app.export.StatementHeader
import com.sadad.app.feature.history.PeriodPreset
import com.sadad.app.feature.history.bounds
import com.sadad.app.feature.history.periodLabel
import com.sadad.app.feature.lock.AppLock
import java.time.Clock
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The screen state that does not come from a store, a preference or the lock. */
private data class Ui(
    val pending: Pending? = null,
    val pinError: PinError? = null,
    val error: SettingsError? = null,
    val outcome: SettingsOutcome? = null,
)

private data class Core(val file: AccountsFile, val open: StoreScope?)

private data class SyncPart(val line: SyncLine, val unsent: Int)

private data class Prefs(val theme: ThemeChoice, val period: ExportPeriod)

private data class LockPart(
    val on: Boolean,
    val pinSet: Boolean,
    val biometric: Boolean,
    val biometricAvailable: Boolean,
)

/**
 * The settings screen. It only calls the graph, the lock, the scheduler and the statement export:
 * no session, lock or export logic lives here. The profile draft is kept in [saved] so a recreated
 * screen keeps what was typed; a PIN is never put there.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModel(
    private val graph: AppGraph,
    private val saved: SavedStateHandle,
    private val preferences: ThemePreference,
    private val lock: AppLock,
    private val scheduler: SyncScheduling?,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val biometricAvailable: () -> Boolean = { false },
    private val syncStatus: (String) -> Flow<SyncStatus> = graph::syncStatus,
) : ViewModel() {
  private val ui = MutableStateFlow(Ui(pending = savedProfile()))

  val state: StateFlow<SettingsState> =
      combine(core(), syncPart(), prefs(), lockPart(), ui) { core, sync, prefs, lock, ui ->
            val open = core.open?.account?.storeUserId
            val account = core.file.accounts.firstOrNull { it.storeUserId == open }
            SettingsState(
                loaded = true,
                storeName = account?.storeName.orEmpty(),
                ownerName = account?.ownerName.orEmpty(),
                theme = prefs.theme,
                exportPeriod = prefs.period,
                sync = sync.line,
                unsent = sync.unsent,
                lockOn = lock.on,
                pinSet = lock.pinSet,
                biometricEnabled = lock.biometric,
                biometricAvailable = lock.biometricAvailable,
                accounts =
                    core.file.accounts.map {
                      SavedStore(it.storeUserId, it.username, it.storeName, it.storeUserId == open)
                    },
                pending = ui.pending,
                pinError = ui.pinError,
                error = ui.error,
                outcome = ui.outcome,
            )
          }
          .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsState())

  // --- Store profile -------------------------------------------------------------------------

  fun editProfile() {
    val current = state.value
    setProfile(Pending.Profile(current.storeName, current.ownerName))
  }

  fun onProfileChange(storeName: String, ownerName: String) =
      setProfile(Pending.Profile(storeName, ownerName))

  fun saveProfile() {
    val draft = ui.value.pending as? Pending.Profile ?: return
    val id = openStoreId() ?: return
    viewModelScope.launch {
      graph.accounts.update(id) {
        it.copy(storeName = draft.storeName.trim(), ownerName = draft.ownerName.trim())
      }
      clearProfile()
      ui.update { it.copy(pending = null) }
    }
  }

  // --- Theme and export ----------------------------------------------------------------------

  fun setTheme(choice: ThemeChoice) {
    viewModelScope.launch { preferences.setTheme(choice) }
  }

  fun selectPreset(preset: PeriodPreset) {
    viewModelScope.launch { preferences.setExportPeriod(ExportPeriod.Preset(preset)) }
  }

  fun selectCustom(from: LocalDate, to: LocalDate) {
    require(!from.isAfter(to)) { "The range must not end before it starts" }
    viewModelScope.launch { preferences.setExportPeriod(ExportPeriod.Custom(from, to)) }
  }

  /** Shares the statement of every Contact for the saved period. */
  fun shareStatement(activity: Context) {
    val context = activity.applicationContext
    val scope = graph.current.value ?: return
    val view = scope.repository.view.value ?: return
    if (view.contacts.isEmpty()) {
      ui.update { it.copy(error = SettingsError.NOTHING_TO_EXPORT) }
      return
    }
    ui.update { it.copy(error = null) }
    val period = state.value.exportPeriod.toPeriod(LocalDate.now(clock))
    val bounds = period.bounds(clock.zone)
    val header =
        StatementHeader(
            store =
                scope.account.storeName.ifBlank { null }
                    ?: context.getString(R.string.settings_default_store),
            owner = scope.account.ownerName,
            periodLabel = periodLabel(context, period),
        )
    viewModelScope.launch {
      try {
        StatementExport.sharePdf(context, header, view, null, bounds.from, bounds.to?.minusNanos(1))
      } catch (failure: Throwable) {
        if (failure is CancellationException) throw failure
        ui.update { it.copy(error = SettingsError.SHARE_FAILED) }
      }
    }
  }

  // --- Sync ----------------------------------------------------------------------------------

  fun downloadLatest() {
    val id = openStoreId() ?: return
    scheduler?.syncNow(id)
  }

  // --- App lock ------------------------------------------------------------------------------

  fun setLockEnabled(on: Boolean) {
    viewModelScope.launch {
      if (on && !lock.hasPin()) requestPin() else lock.setLockEnabled(on)
    }
  }

  /** Opens the set-PIN dialog, for a first PIN and for changing it. */
  fun requestPin() {
    ui.update { it.copy(pending = Pending.SetPin, pinError = null) }
  }

  fun savePin(first: String, second: String) {
    val error =
        when {
          first.length !in 4..12 || !first.all(Char::isDigit) -> PinError.INVALID
          first != second -> PinError.MISMATCH
          else -> null
        }
    if (error != null) {
      ui.update { it.copy(pinError = error) }
      return
    }
    viewModelScope.launch {
      lock.setPin(first)
      ui.update { it.copy(pending = null, pinError = null) }
    }
  }

  fun setBiometric(on: Boolean) {
    viewModelScope.launch {
      when {
        !on -> lock.setBiometricEnabled(false)
        !biometricAvailable() -> Unit
        !lock.hasPin() -> requestPin()
        else -> lock.setBiometricEnabled(true)
      }
    }
  }

  // --- Account -------------------------------------------------------------------------------

  /** Counts the unsent entries, then asks the screen to warn before signing out. */
  fun requestSignOut() {
    viewModelScope.launch {
      val unsent = graph.unsentCount()
      ui.update { it.copy(pending = Pending.SignOut(unsent)) }
    }
  }

  fun confirmSignOut() {
    if (ui.value.pending !is Pending.SignOut) return
    viewModelScope.launch {
      graph.sessions.signOut()
      ui.update { it.copy(pending = null, outcome = SettingsOutcome.NEEDS_LOGIN) }
    }
  }

  fun requestRemove() {
    viewModelScope.launch {
      val unsent = graph.unsentCount()
      ui.update {
        it.copy(pending = Pending.Remove(unsent, confirmed = 0, needed = if (unsent > 0) 2 else 1))
      }
    }
  }

  /** One confirmation; the store is removed once [Pending.Remove.needed] have been given. */
  fun confirmRemove() {
    val pending = ui.value.pending as? Pending.Remove ?: return
    val id = openStoreId() ?: return
    val confirmed = pending.confirmed + 1
    if (confirmed < pending.needed) {
      ui.update { it.copy(pending = pending.copy(confirmed = confirmed)) }
      return
    }
    viewModelScope.launch {
      if (graph.removeStore(id, confirmedTwice = pending.needed > 1)) {
        ui.update { it.copy(pending = null, outcome = SettingsOutcome.NEEDS_LOGIN) }
      } else {
        // Entries were queued after the first count: ask again with the new count.
        requestRemove()
      }
    }
  }

  fun switchTo(storeUserId: String) {
    viewModelScope.launch {
      graph.switchStore(storeUserId)
      if (graph.current.value?.account?.storeUserId != storeUserId) {
        ui.update { it.copy(outcome = SettingsOutcome.NEEDS_LOGIN) }
      }
    }
  }

  // --- Dialogs -------------------------------------------------------------------------------

  fun dismiss() {
    clearProfile()
    ui.update { it.copy(pending = null, pinError = null) }
  }

  fun consumeOutcome() = ui.update { it.copy(outcome = null) }

  fun clearError() = ui.update { it.copy(error = null) }

  // --- Sources -------------------------------------------------------------------------------

  private fun core(): Flow<Core> =
      combine(graph.accounts.state, graph.current) { file, open -> Core(file, open) }

  private fun syncPart(): Flow<SyncPart> =
      graph.current.flatMapLatest { open ->
        if (open == null) flowOf(SyncPart(SyncLine.SignedOut, 0))
        else
            syncStatus(open.account.storeUserId).map { status ->
              SyncPart(line(status), graph.unsentCount())
            }
      }

  private fun prefs(): Flow<Prefs> =
      combine(preferences.theme, preferences.exportPeriod) { theme, period -> Prefs(theme, period) }

  private fun lockPart(): Flow<LockPart> =
      combine(lock.lockEnabled, lock.pinSet, lock.biometricEnabled) { enabled, pinSet, biometric ->
        LockPart(enabled && pinSet, pinSet, biometric, biometricAvailable())
      }

  private fun line(status: SyncStatus): SyncLine =
      when (status) {
        SyncStatus.Idle -> SyncLine.Idle
        SyncStatus.Running -> SyncLine.Running
        SyncStatus.Offline -> SyncLine.Offline
        SyncStatus.SignedOut -> SyncLine.SignedOut
        SyncStatus.AwaitingApproval -> SyncLine.AwaitingApproval
        SyncStatus.Revoked -> SyncLine.Revoked
        SyncStatus.Suspended -> SyncLine.Suspended
        SyncStatus.PasswordChangeRequired -> SyncLine.PasswordChangeRequired
        SyncStatus.Retry -> SyncLine.Retry
        is SyncStatus.Failed -> SyncLine.Failed(graph.errorMessages.text(status.code))
      }

  private fun openStoreId(): String? = graph.current.value?.account?.storeUserId

  private fun setProfile(draft: Pending.Profile) {
    saved[DRAFT_STORE] = draft.storeName
    saved[DRAFT_OWNER] = draft.ownerName
    ui.update { it.copy(pending = draft) }
  }

  private fun savedProfile(): Pending.Profile? {
    val store = saved.get<String>(DRAFT_STORE) ?: return null
    return Pending.Profile(store, saved.get<String>(DRAFT_OWNER).orEmpty())
  }

  private fun clearProfile() {
    saved.remove<String>(DRAFT_STORE)
    saved.remove<String>(DRAFT_OWNER)
  }

  private companion object {
    const val DRAFT_STORE = "settings:draft-store"
    const val DRAFT_OWNER = "settings:draft-owner"
  }
}
