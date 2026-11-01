package com.sadad.app.feature.recovery

import com.sadad.app.AppGraph
import com.sadad.app.data.session.AccountsFile
import com.sadad.app.data.session.StoreScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * What recovery reads and asks for: the open store, the accounts file, a sync and a re-register.
 */
interface RecoverySource {
  val current: StateFlow<StoreScope?>
  val accounts: Flow<AccountsFile>

  suspend fun syncStore(storeUserId: String)

  /** Registers a revoked installation again; false when it was not revoked. */
  suspend fun registerAgain(scope: StoreScope): Boolean
}

fun AppGraph.recoverySource(): RecoverySource {
  val graph = this
  return object : RecoverySource {
    override val current = graph.current
    override val accounts = graph.accounts.state

    override suspend fun syncStore(storeUserId: String) {
      graph.syncStore(storeUserId)
    }

    override suspend fun registerAgain(scope: StoreScope) = graph.sessions.registerAgain(scope)
  }
}
