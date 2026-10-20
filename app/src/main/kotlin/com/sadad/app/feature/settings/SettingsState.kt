package com.sadad.app.feature.settings

/** One store the phone remembers, as the accounts dialog lists it. */
data class SavedStore(
    val storeUserId: String,
    val username: String,
    val storeName: String,
    /** The store open right now. */
    val active: Boolean,
)

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

  data class SignOut(val unsent: Int) : Pending

  /** [needed] confirmations are required: two when entries are unsent, else one. */
  data class Remove(val unsent: Int, val confirmed: Int, val needed: Int) : Pending
}

enum class PinError {
  /** Not 4 to 12 digits. */
  INVALID,
  MISMATCH,
}

enum class SettingsError {
  NOTHING_TO_EXPORT,
  SHARE_FAILED,
}

/** What the shell must do once an action has finished. */
enum class SettingsOutcome {
  /** No store is open any more (signed out, removed, or the other store has no session). */
  NEEDS_LOGIN
}

data class SettingsState(
    val loaded: Boolean = false,
    val storeName: String = "",
    val ownerName: String = "",
    val theme: ThemeChoice = ThemeChoice.SYSTEM,
    val exportPeriod: ExportPeriod =
        ExportPeriod.Preset(com.sadad.app.feature.history.PeriodPreset.MONTH),
    val sync: SyncLine = SyncLine.Idle,
    /** Entries queued on this phone and not yet accepted by the server. */
    val unsent: Int = 0,
    /** The lock applies: it is on and a PIN exists. */
    val lockOn: Boolean = false,
    val pinSet: Boolean = false,
    val biometricEnabled: Boolean = false,
    val biometricAvailable: Boolean = false,
    val accounts: List<SavedStore> = emptyList(),
    val pending: Pending? = null,
    val pinError: PinError? = null,
    val error: SettingsError? = null,
    val outcome: SettingsOutcome? = null,
)
