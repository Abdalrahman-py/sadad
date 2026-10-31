package com.sadad.app.feature.history

import com.sadad.app.AppGraph
import com.sadad.app.data.session.Account
import com.sadad.ledger.LedgerView
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

/** What the two statement screens, History and Reports, read from the open store. */
interface StatementSource {
  /** The open store's Ledger view; null while no store is open. */
  val view: Flow<LedgerView?>

  /** The open store's account, for the statement's header; null while no store is open. */
  fun account(): Account?

  /** The open store's newest view and its account, from one store so a switch cannot mix two. */
  fun latest(): Latest?
}

class Latest(val view: LedgerView?, val account: Account)

@OptIn(ExperimentalCoroutinesApi::class)
fun AppGraph.statementSource(): StatementSource {
  val graph = this
  return object : StatementSource {
    override val view: Flow<LedgerView?> =
        graph.current.flatMapLatest { scope -> scope?.repository?.view ?: flowOf(null) }

    override fun account(): Account? = graph.current.value?.account

    override fun latest(): Latest? =
        graph.current.value?.let { scope -> Latest(scope.repository.view.value, scope.account) }
  }
}
