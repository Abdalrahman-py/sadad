package com.sadad.app.feature.auth

import com.sadad.app.AppGraph
import com.sadad.app.data.session.Accounts
import com.sadad.app.data.sync.SyncStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow

/** What the sign-in, password and approval screens use: sessions, accounts, sync and the route. */
interface AuthSource {
  val accounts: Accounts

  /** The open store's id, or null when none is open. */
  fun openStoreUserId(): String?

  suspend fun signIn(username: String, password: String)

  suspend fun changePassword(current: String, new: String)

  /**
   * After a password change: enrolls a phone that has no approved installation, so a new store's
   * first phone goes straight to Home. A failure here only leaves it on the approval screen.
   */
  suspend fun enrollAfterPasswordChange() = Unit

  /** Where the app should go now; opens the store when that is Home. */
  suspend fun startRoute(): Any

  fun syncStatus(storeUserId: String): StateFlow<SyncStatus>

  suspend fun syncStore(storeUserId: String)

  /** The Arabic sentence for an `errors.json` [code]. */
  fun errorText(code: String): String
}

fun AppGraph.authSource(): AuthSource {
  val graph = this
  return object : AuthSource {
    override val accounts = graph.accounts

    override fun openStoreUserId() = graph.current.value?.account?.storeUserId

    override suspend fun signIn(username: String, password: String) {
      graph.sessions.signIn(username, password)
    }

    override suspend fun changePassword(current: String, new: String) =
        graph.sessions.changePassword(current, new)

    override suspend fun enrollAfterPasswordChange() {
      val id = graph.accounts.file().activeStoreUserId ?: return
      val account = graph.accounts.account(id) ?: return
      if (account.installation?.status == "active") return
      if (account.staffName.isBlank()) {
        graph.accounts.update(id) { it.copy(staffName = it.username) }
      }
      try {
        graph.syncStore(id)
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        // The approval screen shows the failure and offers to try again.
      }
    }

    override suspend fun startRoute() = graph.startRoute()

    override fun syncStatus(storeUserId: String) = graph.syncStatus(storeUserId)

    override suspend fun syncStore(storeUserId: String) {
      graph.syncStore(storeUserId)
    }

    override fun errorText(code: String) = graph.errorMessages.text(code)
  }
}
