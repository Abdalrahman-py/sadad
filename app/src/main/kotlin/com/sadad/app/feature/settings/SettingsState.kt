package com.sadad.app.feature.settings

import com.sadad.app.feature.history.PeriodPreset
import com.sadad.ledger.Cents

/** One saved store the card's switcher lists. */
data class SavedStore(
    val storeUserId: String,
    val username: String,
    val storeName: String,
    /** The store open right now. */
    val active: Boolean,
)

/** The kind of entry waiting in the outbox, so the row can say «دين» or «دفعة». */
enum class QueuedKind {
  DEBT,
  DEBT_CORRECT,
  OFFSET,
  PAYMENT,
  PAYMENT_REVERSE,
  CONTACT_NEW,
  CONTACT_EDIT,
  CONTACT_ARCHIVE,
  CONTACT_RESTORE,
  CONTACT_DELETE,
}

/** The newest entry waiting to be sent: what it was, for whom and for how much. */
data class QueuedEntry(val kind: QueuedKind, val name: String, val amount: Cents?)

/** The sync section's status line; [Failed] carries the `errors.json` text for the code. */
sealed interface SyncLine {
  data object Idle : SyncLine

  data object Running : SyncLine

  data object Offline : SyncLine

  data object SignedOut : SyncLine

  data object AwaitingApproval : SyncLine

  data object Revoked : SyncLine

  data object Suspended : SyncLine

  data object PasswordChangeRequired : SyncLine

  data object Retry : SyncLine

  data class Failed(val message: String) : SyncLine
}

/** The dialog the screen shows next, and what it needs to decide. */
sealed interface Pending {
  /** The store name and owner being edited; the text is also kept in the saved state. */
  data class Profile(val storeName: String, val ownerName: String) : Pending

  data object SetPin : Pending

  /** [storeUserId] is the store the count was taken for; a different open store refuses. */
  data class SignOut(val storeUserId: String, val unsent: Int) : Pending

  /** [needed] confirmations are required: two when entries are unsent, else one. */
  data class Remove(
      val storeUserId: String,
      val unsent: Int,
      val confirmed: Int,
      val needed: Int,
  ) : Pending
}

enum class PinError {
  /** Not exactly 4 digits. */
  INVALID,
  MISMATCH,
}

enum class SettingsError {
  NOTHING_TO_EXPORT,
  SHARE_FAILED,
  /** An account action threw: nothing was half-confirmed on screen. */
  ACTION_FAILED,
}

/** What the shell must do once an action has finished. */
enum class SettingsOutcome {
  /** No store is open any more (signed out, removed, or the other store has no session). */
  NEEDS_LOGIN
}

/** What the store card reads: the open store's profile and the stores the switcher lists. */
data class StoreSlice(
    val storeName: String = "",
    val ownerName: String = "",
    val accounts: List<SavedStore> = emptyList(),
)

/** What the devices section reads: the sync line and what is waiting to be sent. */
data class DevicesSlice(
    val sync: SyncLine = SyncLine.Idle,
    /** Entries queued on this phone and not yet accepted by the server. */
    val unsent: Int = 0,
    /** The newest of those entries, for the status row's second line. */
    val newest: QueuedEntry? = null,
    val lastSyncAt: Long? = null,
)

data class SecuritySlice(
    /** The phone has an enrolled fingerprint or face. */
    val biometricAvailable: Boolean = false,
    /** The lock applies: it is on and a PIN exists. */
    val lockOn: Boolean = false,
    val pinSet: Boolean = false,
    val biometricEnabled: Boolean = false,
)

data class DataSlice(
    /** What the trash holds: archived Contacts and dismissed reminders this phone still lists. */
    val trashCount: Int = 0,
    /** This device's `permissions` allow the trash's actions (Q6/Q20). */
    val canDelete: Boolean = true,
    val exportPeriod: ExportPeriod = ExportPeriod.Preset(PeriodPreset.MONTH),
)

/** The module slices side by side, plus what the modules share: dialogs, errors and outcomes. */
data class SettingsState(
    val loaded: Boolean = false,
    val store: StoreSlice = StoreSlice(),
    val devices: DevicesSlice = DevicesSlice(),
    val security: SecuritySlice = SecuritySlice(),
    val data: DataSlice = DataSlice(),
    val theme: ThemeChoice = ThemeChoice.SYSTEM,
    /** A document, sign-out or removal is running; the buttons wait for it. */
    val busy: Boolean = false,
    val pending: Pending? = null,
    val pinError: PinError? = null,
    val error: SettingsError? = null,
    val outcome: SettingsOutcome? = null,
)
