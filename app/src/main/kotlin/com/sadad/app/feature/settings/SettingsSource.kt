package com.sadad.app.feature.settings

import com.sadad.app.AppGraph
import com.sadad.app.data.session.Accounts
import com.sadad.app.data.session.StoreScope
import kotlinx.coroutines.flow.StateFlow

/**
 * What the settings modules use from the app: the open store, the accounts and the session calls.
 */
interface SettingsSource {
  val current: StateFlow<StoreScope?>
  val accounts: Accounts

  suspend fun switchStore(storeUserId: String)

  suspend fun unsentCount(storeUserId: String): Int

  suspend fun signOut()

  /** Removes a store from this phone; false when unsent entries need a second confirmation. */
  suspend fun removeStore(storeUserId: String, confirmedTwice: Boolean): Boolean

  /** The Arabic sentence for an `errors.json` [code]. */
  fun errorText(code: String): String
}

fun AppGraph.settingsSource(): SettingsSource {
  val graph = this
  return object : SettingsSource {
    override val current = graph.current
    override val accounts = graph.accounts

    override suspend fun switchStore(storeUserId: String) {
      graph.switchStore(storeUserId)
    }

    override suspend fun unsentCount(storeUserId: String) = graph.unsentCount(storeUserId)

    override suspend fun signOut() = graph.sessions.signOut()

    override suspend fun removeStore(storeUserId: String, confirmedTwice: Boolean) =
        graph.removeStore(storeUserId, confirmedTwice)

    override fun errorText(code: String) = graph.errorMessages.text(code)
  }
}
