package com.sadad.app.feature.history

import com.sadad.app.AppGraph
import com.sadad.app.data.session.Account
import com.sadad.ledger.LedgerView
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

/** What Activity reads from the open store. */
interface StatementSource {
  /** The rows of [bounds] with the queue on top, for the list; null while no store is open. */
  fun view(bounds: PeriodBounds): Flow<LedgerView?>

  /** The whole view a statement needs, read once, and its account, from one store. */
  suspend fun latest(): Latest?
}

class Latest(val view: LedgerView, val account: Account)

@OptIn(ExperimentalCoroutinesApi::class)
fun AppGraph.statementSource(): StatementSource {
  val graph = this
  return object : StatementSource {
    override fun view(bounds: PeriodBounds): Flow<LedgerView?> =
        graph.current.flatMapLatest { scope ->
          scope?.personLedger?.period(bounds.from, bounds.to) ?: flowOf(null)
        }

    override suspend fun latest(): Latest? =
        graph.current.value?.let { scope -> Latest(scope.personLedger.wholeView(), scope.account) }
  }
}
