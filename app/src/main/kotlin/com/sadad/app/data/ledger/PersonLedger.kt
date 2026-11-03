package com.sadad.app.data.ledger

import com.sadad.app.data.db.LedgerStore
import com.sadad.ledger.LedgerState
import com.sadad.ledger.view
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * One open store's ledger, read a person at a time instead of the whole store: Room returns only
 * that person's rows, and the store's queued commands are projected on top by the same
 * [LedgerState.view] rules [LedgerRepository] uses — so a person's page shows what the whole-store
 * projection would, restricted to them.
 */
class PersonLedger(
    private val store: LedgerStore,
    private val outbox: Outbox,
    private val session: RepositorySession,
) {
  /** The person's [ContactRecords] as they are now; null while the store has no `ledger_meta`. */
  suspend fun records(contactId: String): ContactRecords? =
      withContext(Dispatchers.Default) {
        val staff = session.staffName.first()
        store.loadPerson(contactId)?.let { state -> project(state, outbox.snapshot(), staff) }
      }

  /** [records] as a flow, re-read when that person's rows or the outbox change. */
  fun observe(contactId: String): Flow<ContactRecords?> =
      combine(store.observePerson(contactId), outbox.rows, session.staffName) { state, rows, staff
            ->
            state?.let { project(state, rows, staff) }
          }
          .flowOn(Dispatchers.Default)

  /**
   * The whole-store projection on one stage: the unsettled commands and failed ids of the whole
   * outbox on a state that holds only this person — commands for other people fall out of
   * [LedgerState.view]'s own rules.
   */
  private fun project(state: LedgerState, rows: List<OutboxRow>, staff: String): ContactRecords =
      contactRecords(
          state.view(
              pendingCommands(rows),
              failedOperations(rows),
              session.selfInstallationId,
              staff,
          ),
          rows,
      )
}
