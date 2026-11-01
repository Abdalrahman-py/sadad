package com.sadad.app.feature.home

import com.sadad.app.AppGraph
import com.sadad.app.data.session.Account
import com.sadad.app.data.session.StoreScope
import com.sadad.app.data.sync.SyncStatus
import kotlinx.coroutines.flow.Flow

/** What Home reads: the open store, the saved accounts, a store's sync state and its lease. */
interface HomeSource {
  val current: Flow<StoreScope?>
  val accounts: Flow<List<Account>>

  fun syncStatus(storeUserId: String): Flow<SyncStatus>

  /** When the store's offline lease ends, in epoch millis; null without one. */
  fun offlineUntil(account: Account): Long?
}

fun AppGraph.homeSource(): HomeSource {
  val graph = this
  return object : HomeSource {
    override val current = graph.current
    override val accounts = graph.accounts.accounts

    override fun syncStatus(storeUserId: String) = graph.syncStatus(storeUserId)

    override fun offlineUntil(account: Account) = graph.offlineUntil(account)
  }
}
