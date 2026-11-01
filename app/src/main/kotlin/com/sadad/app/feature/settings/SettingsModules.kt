package com.sadad.app.feature.settings

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import com.sadad.app.R
import com.sadad.app.data.ledger.OutboxRow
import com.sadad.app.data.ledger.OutboxState
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * The settings screen as six modules (store, devices, security, data, appearance, account). Each
 * reads its own slice of the state, from the sources it needs and no others, and owns its commands.
 * What they share is only [SettingsUi]: the open dialog, the error line, the busy flag and the
 * outcome. A section takes its module and its slice, never the whole screen.
 */

/** The screen state that does not come from a store, a preference or the lock. */
internal data class Ui(
    val pending: Pending? = null,
    val pinError: PinError? = null,
    val error: SettingsError? = null,
    val outcome: SettingsOutcome? = null,
    val busy: Boolean = false,
)

/**
 * The part every module writes. The profile draft is kept in [saved] so a recreated screen keeps
 * what was typed; a PIN is never put there.
 */
internal class SettingsUi(val scope: CoroutineScope, private val saved: SavedStateHandle) {
  val state = MutableStateFlow(Ui(pending = savedProfile()))

  /** Runs an account action; a failure ends the dialog with a message instead of crashing. */
  fun act(block: suspend () -> Unit) {
    // A failure from an earlier action must not outlive the retry.
    state.update { it.copy(error = null) }
    scope.launch {
      try {
        block()
      } catch (failure: Throwable) {
        if (failure is CancellationException) throw failure
        state.update { it.copy(pending = null, busy = false, error = SettingsError.ACTION_FAILED) }
      }
    }
  }

  fun setProfile(draft: Pending.Profile) {
    saved[DRAFT_STORE] = draft.storeName
    saved[DRAFT_OWNER] = draft.ownerName
    state.update { it.copy(pending = draft) }
  }

  fun clearProfile() {
    saved.remove<String>(DRAFT_STORE)
    saved.remove<String>(DRAFT_OWNER)
  }

  fun dismiss() {
    clearProfile()
    state.update { it.copy(pending = null, pinError = null) }
  }

  fun consumeOutcome() = state.update { it.copy(outcome = null) }

  fun clearError() = state.update { it.copy(error = null) }

  private fun savedProfile(): Pending.Profile? {
    val store = saved.get<String>(DRAFT_STORE) ?: return null
    return Pending.Profile(store, saved.get<String>(DRAFT_OWNER).orEmpty())
  }

  private companion object {
    const val DRAFT_STORE = "settings:draft-store"
    const val DRAFT_OWNER = "settings:draft-owner"
  }
}

private fun SettingsSource.openStoreId(): String? = current.value?.account?.storeUserId

/** The store card: the open store's profile and the saved stores the switcher lists. */
internal class StoreModule(private val source: SettingsSource, private val ui: SettingsUi) {
  val state: Flow<StoreSlice> =
      combine(source.accounts.state, source.current) { file, open ->
        val id = open?.account?.storeUserId
        val account = file.accounts.firstOrNull { it.storeUserId == id }
        StoreSlice(
            storeName = account?.storeName.orEmpty(),
            ownerName = account?.ownerName.orEmpty(),
            accounts =
                file.accounts.map {
                  SavedStore(it.storeUserId, it.username, it.storeName, it.storeUserId == id)
                },
        )
      }

  fun editProfile(current: StoreSlice) =
      ui.setProfile(Pending.Profile(current.storeName, current.ownerName))

  fun onProfileChange(storeName: String, ownerName: String) =
      ui.setProfile(Pending.Profile(storeName, ownerName))

  fun saveProfile() {
    val draft = ui.state.value.pending as? Pending.Profile ?: return
    val id = source.openStoreId() ?: return
    ui.act {
      source.accounts.update(id) {
        it.copy(storeName = draft.storeName.trim(), ownerName = draft.ownerName.trim())
      }
      ui.clearProfile()
      ui.state.update { it.copy(pending = null) }
    }
  }

  fun switchTo(storeUserId: String) {
    ui.act {
      source.switchStore(storeUserId)
      if (source.current.value?.account?.storeUserId != storeUserId) {
        ui.state.update { it.copy(outcome = SettingsOutcome.NEEDS_LOGIN) }
      }
    }
  }
}

/** Devices and sync: what is waiting to be sent, what sync is doing, and the download. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class DevicesModule(
    private val source: SettingsSource,
    private val scheduler: SyncScheduling,
    private val syncStatus: (String) -> Flow<SyncStatus>,
    private val ui: SettingsUi,
) {
  val state: Flow<DevicesSlice> =
      source.current.flatMapLatest { open ->
        if (open == null) flowOf(DevicesSlice(SyncLine.SignedOut, 0, null))
        else
            combine(syncStatus(open.account.storeUserId), open.outbox.rows, open.repository.view) {
                status,
                rows,
                view ->
              val waiting = rows.filter { it.state == OutboxState.QUEUED }
              DevicesSlice(line(status), waiting.size, newest(waiting, view))
            }
      }

  fun downloadLatest() {
    val id = source.openStoreId() ?: return
    ui.act { scheduler.syncNow(id) }
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
              is com.sadad.ledger.ContactDelete ->
                  QueuedEntry(QueuedKind.CONTACT_DELETE, contactName(view, payload.contactId), null)
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
        is SyncStatus.Failed -> SyncLine.Failed(source.errorText(status.code))
      }
}

/** The app lock: the switch, the PIN and the biometric switch. Needs only the lock itself. */
internal class SecurityModule(
    private val lock: AppLock,
    private val biometricAvailable: () -> Boolean,
    private val ui: SettingsUi,
) {
  // Read at construction and again on ON_RESUME: a fingerprint enrolled in the system settings
  // shows the switch when the screen comes back.
  private val sensor = MutableStateFlow(biometricAvailable())

  val state: Flow<SecuritySlice> =
      combine(lock.lockEnabled, lock.pinSet, lock.biometricEnabled, sensor) {
          enabled,
          pinSet,
          biometric,
          available ->
        SecuritySlice(
            biometricAvailable = available,
            lockOn = enabled && pinSet,
            pinSet = pinSet,
            biometricEnabled = biometric,
        )
      }

  fun setLockEnabled(on: Boolean) {
    ui.act {
      if (on && !lock.hasPin()) requestPin() else lock.setLockEnabled(on)
    }
  }

  /** Opens the set-PIN dialog, for a first PIN and for changing it. */
  fun requestPin() {
    ui.state.update { it.copy(pending = Pending.SetPin, pinError = null) }
  }

  fun savePin(first: String, second: String) {
    val error =
        when {
          first.length != 4 || !first.all(Char::isDigit) -> PinError.INVALID
          first != second -> PinError.MISMATCH
          else -> null
        }
    if (error != null) {
      ui.state.update { it.copy(pinError = error) }
      return
    }
    ui.act {
      lock.setPin(first)
      ui.state.update { it.copy(pending = null, pinError = null) }
    }
  }

  fun setBiometric(on: Boolean) {
    ui.act {
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
}

/** The trash and the statement export: what the data section lists and the period it exports. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class DataModule(
    private val source: SettingsSource,
    private val preferences: ThemePreference,
    /** The dismissed due reminders the trash lists. */
    reminders: (String) -> Flow<List<Reminder>>,
    /** The trash ids this phone removed for good, as the trash screen hides them. */
    hiddenTrash: (String) -> Flow<Set<String>>,
    private val clock: Clock,
    private val ui: SettingsUi,
) {
  val state: Flow<DataSlice> =
      combine(preferences.exportPeriod, permissions(), trashCount(reminders, hiddenTrash)) {
          period,
          permissions,
          trash ->
        DataSlice(
            trashCount = trash,
            canDelete =
                permissions["deleteRecords"] != false || permissions["deleteContacts"] != false,
            exportPeriod = period,
        )
      }

  fun selectPreset(preset: PeriodPreset) {
    ui.act { preferences.setExportPeriod(ExportPeriod.Preset(preset)) }
  }

  fun selectCustom(from: LocalDate, to: LocalDate) {
    require(!from.isAfter(to)) { "The range must not end before it starts" }
    ui.act { preferences.setExportPeriod(ExportPeriod.Custom(from, to)) }
  }

  /** Shares the statement of every Contact for [exportPeriod], the saved period. */
  fun shareStatement(activity: Context, exportPeriod: ExportPeriod) {
    if (ui.state.value.busy) return
    val context = activity.applicationContext
    val scope = source.current.value
    val view = scope?.repository?.view?.value
    if (scope == null || view == null) {
      ui.state.update { it.copy(error = SettingsError.SHARE_FAILED) }
      return
    }
    if (view.contacts.isEmpty()) {
      ui.state.update { it.copy(error = SettingsError.NOTHING_TO_EXPORT) }
      return
    }
    ui.state.update { it.copy(error = null, busy = true) }
    val period = exportPeriod.toPeriod(LocalDate.now(clock))
    val bounds = period.bounds(clock.zone)
    val header =
        StatementHeader(
            store =
                scope.account.storeName.ifBlank { null }
                    ?: context.getString(R.string.settings_default_store),
            owner = scope.account.ownerName,
            periodLabel = periodLabel(context, period),
        )
    ui.scope.launch {
      try {
        StatementExport.sharePdf(context, header, view, null, bounds.from, bounds.to)
        ui.state.update { it.copy(busy = false) }
      } catch (failure: Throwable) {
        if (failure is CancellationException) throw failure
        ui.state.update { it.copy(busy = false, error = SettingsError.SHARE_FAILED) }
      }
    }
  }

  /** What this device may do, from the installation's `permissions` map (never the staff role). */
  private fun permissions(): Flow<Map<String, Boolean>> =
      source.current.flatMapLatest { open ->
        val id = open?.account?.storeUserId
        source.accounts.accounts.map { accounts ->
          accounts.firstOrNull { it.storeUserId == id }?.installation?.permissions.orEmpty()
        }
      }

  /** The number the trash screen lists: archived Contacts and dismissed reminders, minus hidden. */
  private fun trashCount(
      reminders: (String) -> Flow<List<Reminder>>,
      hiddenTrash: (String) -> Flow<Set<String>>,
  ): Flow<Int> =
      source.current.flatMapLatest { open ->
        if (open == null) flowOf(0)
        else
            combine(
                open.repository.view,
                reminders(open.account.storeUserId),
                hiddenTrash(open.account.storeUserId),
            ) { view, dismissed, hidden ->
              view?.archivedContacts.orEmpty().size + dismissed.count { it.id !in hidden }
            }
      }
}

internal class AppearanceModule(
    private val preferences: ThemePreference,
    private val ui: SettingsUi,
) {
  val state: Flow<ThemeChoice> = preferences.theme

  fun setTheme(choice: ThemeChoice) {
    ui.act { preferences.setTheme(choice) }
  }
}

/** Sign-out, store removal and their confirmations. Busy and the error line are the shared UI. */
internal class AccountModule(private val source: SettingsSource, private val ui: SettingsUi) {
  /** Counts the unsent entries of the open store, then asks the screen to warn first. */
  fun requestSignOut() {
    val id = source.openStoreId() ?: return
    ui.act {
      val unsent = source.unsentCount(id)
      ui.state.update { it.copy(pending = Pending.SignOut(id, unsent)) }
    }
  }

  fun confirmSignOut() {
    val pending = ui.state.value.pending as? Pending.SignOut ?: return
    if (ui.state.value.busy) return
    if (source.openStoreId() != pending.storeUserId) {
      // Another store is open now than the one the warning counted: ask again.
      ui.dismiss()
      return
    }
    ui.state.update { it.copy(busy = true) }
    ui.act {
      // Back-navigation must not cancel the sign-out half way.
      withContext(NonCancellable) { source.signOut() }
      ui.state.update {
        it.copy(pending = null, busy = false, outcome = SettingsOutcome.NEEDS_LOGIN)
      }
    }
  }

  fun requestRemove() {
    val id = source.openStoreId() ?: return
    ui.act {
      val unsent = source.unsentCount(id)
      ui.state.update {
        it.copy(
            pending = Pending.Remove(id, unsent, confirmed = 0, needed = if (unsent > 0) 2 else 1)
        )
      }
    }
  }

  /** One confirmation; the store is removed once [Pending.Remove.needed] have been given. */
  fun confirmRemove() {
    val pending = ui.state.value.pending as? Pending.Remove ?: return
    if (ui.state.value.busy) return
    if (source.openStoreId() != pending.storeUserId) {
      ui.dismiss()
      return
    }
    val confirmed = pending.confirmed + 1
    if (confirmed < pending.needed) {
      ui.state.update { it.copy(pending = pending.copy(confirmed = confirmed)) }
      return
    }
    ui.state.update { it.copy(busy = true) }
    ui.act {
      val removed =
          withContext(NonCancellable) {
            source.removeStore(pending.storeUserId, confirmedTwice = pending.needed > 1)
          }
      if (removed) {
        ui.state.update {
          it.copy(pending = null, busy = false, outcome = SettingsOutcome.NEEDS_LOGIN)
        }
      } else {
        // Entries were queued after the first count: ask again with the new count.
        ui.state.update { it.copy(busy = false) }
        requestRemove()
      }
    }
  }
}
