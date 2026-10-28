package com.sadad.app.feature.settings

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sadad.app.AppGraph
import com.sadad.app.R
import com.sadad.app.data.ledger.OutboxRow
import com.sadad.app.data.ledger.OutboxState
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
import com.sadad.app.feature.notifications.Reminder
import com.sadad.ledger.ContactArchive
import com.sadad.ledger.ContactCreate
import com.sadad.ledger.ContactRestore
import com.sadad.ledger.ContactUpdate
import com.sadad.ledger.DebtCorrect
import com.sadad.ledger.DebtCreate
import com.sadad.ledger.DebtOffset
import com.sadad.ledger.LedgerView
import com.sadad.ledger.PaymentCreate
import com.sadad.ledger.PaymentReverse
import java.time.Clock
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
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
import kotlinx.coroutines.withContext

/** The screen state that does not come from a store, a preference or the lock. */
private data class Ui(
    val pending: Pending? = null,
    val pinError: PinError? = null,
    val error: SettingsError? = null,
    val outcome: SettingsOutcome? = null,
    val busy: Boolean = false,
)

private data class Core(val file: AccountsFile, val open: StoreScope?)

private data class SyncPart(val line: SyncLine, val unsent: Int, val newest: QueuedEntry?)

private data class Prefs(val theme: ThemeChoice, val period: ExportPeriod)

private data class LockPart(val on: Boolean, val pinSet: Boolean, val biometric: Boolean)

/** Everything the screen reads that is not the store's account or the sync status. */
private data class Extras(
    val theme: ThemeChoice,
    val period: ExportPeriod,
    val lockOn: Boolean,
    val pinSet: Boolean,
    val biometric: Boolean,
    val sensor: Boolean,
    val canDelete: Boolean,
    val trash: Int,
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
    private val scheduler: SyncScheduling,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val biometricAvailable: () -> Boolean = { false },
    private val syncStatus: (String) -> Flow<SyncStatus> = graph::syncStatus,
    /** The dismissed due reminders the trash lists; empty when the screen has none to show. */
    private val reminders: (String) -> Flow<List<Reminder>> = { flowOf(emptyList()) },
    /** The trash ids this phone removed for good, as the trash screen hides them. */
    private val hiddenTrash: (String) -> Flow<Set<String>> = { flowOf(emptySet()) },
) : ViewModel() {
  private val ui = MutableStateFlow(Ui(pending = savedProfile()))

  // Read at construction and again on ON_RESUME: a fingerprint enrolled in the system settings
  // shows the switch when the screen comes back.
  private val sensor = MutableStateFlow(biometricAvailable())

  val state: StateFlow<SettingsState> =
      combine(core(), syncPart(), extras(), ui) { core, sync, extras, ui ->
            val open = core.open?.account?.storeUserId
            val account = core.file.accounts.firstOrNull { it.storeUserId == open }
            SettingsState(
                loaded = true,
                storeName = account?.storeName.orEmpty(),
                ownerName = account?.ownerName.orEmpty(),
                theme = extras.theme,
                exportPeriod = extras.period,
                sync = sync.line,
                unsent = sync.unsent,
                newest = sync.newest,
                trashCount = extras.trash,
                canDelete = extras.canDelete,
                biometricAvailable = extras.sensor,
                lockOn = extras.lockOn,
                pinSet = extras.pinSet,
                biometricEnabled = extras.biometric,
                busy = ui.busy,
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
    act {
      graph.accounts.update(id) {
        it.copy(storeName = draft.storeName.trim(), ownerName = draft.ownerName.trim())
      }
      clearProfile()
      ui.update { it.copy(pending = null) }
    }
  }

  // --- Theme and export ----------------------------------------------------------------------

  fun setTheme(choice: ThemeChoice) {
    act { preferences.setTheme(choice) }
  }

  fun selectPreset(preset: PeriodPreset) {
    act { preferences.setExportPeriod(ExportPeriod.Preset(preset)) }
  }

  fun selectCustom(from: LocalDate, to: LocalDate) {
    require(!from.isAfter(to)) { "The range must not end before it starts" }
    act { preferences.setExportPeriod(ExportPeriod.Custom(from, to)) }
  }

  /** Shares the statement of every Contact for the saved period. */
  fun shareStatement(activity: Context) {
    if (ui.value.busy) return
    val context = activity.applicationContext
    val scope = graph.current.value
    val view = scope?.repository?.view?.value
    if (scope == null || view == null) {
      ui.update { it.copy(error = SettingsError.SHARE_FAILED) }
      return
    }
    if (view.contacts.isEmpty()) {
      ui.update { it.copy(error = SettingsError.NOTHING_TO_EXPORT) }
      return
    }
    ui.update { it.copy(error = null, busy = true) }
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
        StatementExport.sharePdf(context, header, view, null, bounds.from, bounds.to)
        ui.update { it.copy(busy = false) }
      } catch (failure: Throwable) {
        if (failure is CancellationException) throw failure
        ui.update { it.copy(busy = false, error = SettingsError.SHARE_FAILED) }
      }
    }
  }

  // --- Sync ----------------------------------------------------------------------------------

  fun downloadLatest() {
    val id = openStoreId() ?: return
    act { scheduler.syncNow(id) }
  }

  // --- App lock ------------------------------------------------------------------------------

  fun setLockEnabled(on: Boolean) {
    act {
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
    act {
      lock.setPin(first)
      ui.update { it.copy(pending = null, pinError = null) }
    }
  }

  fun setBiometric(on: Boolean) {
    act {
      when {
        !on -> lock.setBiometricEnabled(false)
        !biometricAvailable() -> Unit
        !lock.hasPin() -> requestPin()
        else -> lock.setBiometricEnabled(true)
      }
      refreshBiometricSensor()
    }
  }

  /** Re-reads the phone's biometric hardware, for a fingerprint enrolled while the app was away. */
  fun refreshBiometricSensor() {
    sensor.value = biometricAvailable()
  }

  // --- Account -------------------------------------------------------------------------------

  /** Counts the unsent entries of the open store, then asks the screen to warn first. */
  fun requestSignOut() {
    val id = openStoreId() ?: return
    act {
      val unsent = graph.unsentCount(id)
      ui.update { it.copy(pending = Pending.SignOut(id, unsent)) }
    }
  }

  fun confirmSignOut() {
    val pending = ui.value.pending as? Pending.SignOut ?: return
    if (ui.value.busy) return
    if (openStoreId() != pending.storeUserId) {
      // Another store is open now than the one the warning counted: ask again.
      dismiss()
      return
    }
    ui.update { it.copy(busy = true) }
    act {
      // Back-navigation must not cancel the sign-out half way.
      withContext(NonCancellable) { graph.sessions.signOut() }
      ui.update { it.copy(pending = null, busy = false, outcome = SettingsOutcome.NEEDS_LOGIN) }
    }
  }

  fun requestRemove() {
    val id = openStoreId() ?: return
    act {
      val unsent = graph.unsentCount(id)
      ui.update {
        it.copy(
            pending = Pending.Remove(id, unsent, confirmed = 0, needed = if (unsent > 0) 2 else 1)
        )
      }
    }
  }

  /** One confirmation; the store is removed once [Pending.Remove.needed] have been given. */
  fun confirmRemove() {
    val pending = ui.value.pending as? Pending.Remove ?: return
    if (ui.value.busy) return
    if (openStoreId() != pending.storeUserId) {
      dismiss()
      return
    }
    val confirmed = pending.confirmed + 1
    if (confirmed < pending.needed) {
      ui.update { it.copy(pending = pending.copy(confirmed = confirmed)) }
      return
    }
    ui.update { it.copy(busy = true) }
    act {
      val removed =
          withContext(NonCancellable) {
            graph.removeStore(pending.storeUserId, confirmedTwice = pending.needed > 1)
          }
      if (removed) {
        ui.update { it.copy(pending = null, busy = false, outcome = SettingsOutcome.NEEDS_LOGIN) }
      } else {
        // Entries were queued after the first count: ask again with the new count.
        ui.update { it.copy(busy = false) }
        requestRemove()
      }
    }
  }

  fun switchTo(storeUserId: String) {
    act {
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

  /** Runs an account action; a failure ends the dialog with a message instead of crashing. */
  private fun act(block: suspend () -> Unit) {
    // A failure from an earlier action must not outlive the retry.
    ui.update { it.copy(error = null) }
    viewModelScope.launch {
      try {
        block()
      } catch (failure: Throwable) {
        if (failure is CancellationException) throw failure
        ui.update { it.copy(pending = null, busy = false, error = SettingsError.ACTION_FAILED) }
      }
    }
  }

  // --- Sources -------------------------------------------------------------------------------

  private fun core(): Flow<Core> =
      combine(graph.accounts.state, graph.current) { file, open -> Core(file, open) }

  private fun syncPart(): Flow<SyncPart> =
      graph.current.flatMapLatest { open ->
        if (open == null) flowOf(SyncPart(SyncLine.SignedOut, 0, null))
        else
            combine(syncStatus(open.account.storeUserId), open.outbox.rows, open.repository.view) {
                status,
                rows,
                view ->
              val waiting = rows.filter { it.state == OutboxState.QUEUED }
              SyncPart(line(status), waiting.size, newest(waiting, view))
            }
      }

  private fun prefs(): Flow<Prefs> =
      combine(preferences.theme, preferences.exportPeriod) { theme, period -> Prefs(theme, period) }

  private fun lockPart(): Flow<LockPart> =
      combine(lock.lockEnabled, lock.pinSet, lock.biometricEnabled) { enabled, pinSet, biometric ->
        LockPart(enabled && pinSet, pinSet, biometric)
      }

  /** What this device may do, from the installation's `permissions` map (never the staff role). */
  private fun permissions(): Flow<Map<String, Boolean>> =
      graph.current.flatMapLatest { open ->
        val id = open?.account?.storeUserId
        graph.accounts.accounts.map { accounts ->
          accounts.firstOrNull { it.storeUserId == id }?.installation?.permissions.orEmpty()
        }
      }

  /** The number the trash screen lists: archived Contacts and dismissed reminders, minus hidden. */
  private fun trashPart(): Flow<Int> =
      graph.current.flatMapLatest { open ->
        if (open == null) flowOf(0)
        else
            combine(
                open.repository.view,
                reminders(open.account.storeUserId),
                hiddenTrash(open.account.storeUserId),
            ) { view, dismissed, hidden ->
              (view?.archivedContacts.orEmpty().map { it.id } + dismissed.map { it.id }).count {
                it !in hidden
              }
            }
      }

  private fun extras(): Flow<Extras> =
      combine(prefs(), lockPart(), permissions(), trashPart(), sensor) {
          prefs,
          lock,
          permissions,
          trash,
          sensor ->
        Extras(
            theme = prefs.theme,
            period = prefs.period,
            lockOn = lock.on,
            pinSet = lock.pinSet,
            biometric = lock.biometric,
            sensor = sensor,
            canDelete =
                permissions["deleteRecords"] != false || permissions["deleteContacts"] != false,
            trash = trash,
        )
      }

  /** The newest entry waiting for the server, named the way its row reads. */
  private fun newest(waiting: List<OutboxRow>, view: LedgerView?): QueuedEntry? =
      waiting
          // The queue order, not createdAt: three entries made in the same millisecond would tie.
          .maxByOrNull { it.seq }
          ?.let { row ->
            when (val payload = row.command.payload()) {
              is DebtCreate ->
                  QueuedEntry(
                      QueuedKind.DEBT,
                      contactName(view, payload.contactId),
                      payload.amountCents,
                  )
              is DebtCorrect ->
                  QueuedEntry(
                      QueuedKind.DEBT_CORRECT,
                      debtContact(view, payload.debtId),
                      payload.amountCents,
                  )
              is DebtOffset ->
                  QueuedEntry(
                      QueuedKind.OFFSET,
                      debtContact(view, payload.receivableDebtId),
                      payload.amountCents,
                  )
              is PaymentCreate ->
                  QueuedEntry(
                      QueuedKind.PAYMENT,
                      debtContact(view, payload.debtId),
                      payload.amountCents,
                  )
              is PaymentReverse ->
                  QueuedEntry(
                      QueuedKind.PAYMENT_REVERSE,
                      paymentContact(view, payload.paymentId),
                      null,
                  )
              is ContactCreate -> QueuedEntry(QueuedKind.CONTACT_NEW, payload.name, null)
              is ContactUpdate -> QueuedEntry(QueuedKind.CONTACT_EDIT, payload.name, null)
              is ContactArchive ->
                  QueuedEntry(
                      QueuedKind.CONTACT_ARCHIVE,
                      contactName(view, payload.contactId),
                      null,
                  )
              is ContactRestore ->
                  QueuedEntry(
                      QueuedKind.CONTACT_RESTORE,
                      contactName(view, payload.contactId),
                      null,
                  )
            }
          }

  private fun contactName(view: LedgerView?, contactId: String): String =
      view?.contacts?.firstOrNull { it.id == contactId }?.name
          ?: view?.archivedContacts?.firstOrNull { it.id == contactId }?.name
          ?: ""

  private fun debtContact(view: LedgerView?, debtId: String): String {
    val contactId = view?.debts?.firstOrNull { it.id == debtId }?.contactId ?: return ""
    return contactName(view, contactId)
  }

  private fun paymentContact(view: LedgerView?, paymentId: String): String {
    val debtId = view?.payments?.firstOrNull { it.id == paymentId }?.debtId ?: return ""
    return debtContact(view, debtId)
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
