package com.sadad.app.data.sync

sealed interface SyncStatus {
  data object Idle : SyncStatus

  data object Running : SyncStatus

  data object Offline : SyncStatus

  data object SignedOut : SyncStatus

  data object AwaitingApproval : SyncStatus

  data object Revoked : SyncStatus

  data object Suspended : SyncStatus

  data object PasswordChangeRequired : SyncStatus

  data object Retry : SyncStatus

  data class Failed(val code: String) : SyncStatus {
    /**
     * Whether asking again can succeed. A refusal the same request can never pass — invalid input,
     * a revoked permission, a permit that does not cover the batch — is terminal; retrying it only
     * blocks newer work behind an exponential backoff.
     */
    val retryable: Boolean
      get() = code !in TERMINAL_CODES
  }

  companion object {
    private val TERMINAL_CODES =
        setOf("invalid_input", "store_permission_denied", "recovery_not_authorized")
  }
}
