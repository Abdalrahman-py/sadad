package com.sadad.app.data.ledger

import com.sadad.app.data.db.LedgerStore
import com.sadad.ledger.ContactView
import com.sadad.ledger.DebtCorrect
import com.sadad.ledger.DebtCreate
import com.sadad.ledger.DebtOffset
import com.sadad.ledger.LedgerState
import com.sadad.ledger.LedgerView
import com.sadad.ledger.PaymentCreate
import com.sadad.ledger.view
import java.time.Instant
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
   * One period for Activity, re-read when the ledger or the outbox change. Every queued command is
   * projected, and [LedgerStore.loadPeriod] holds each row they name, so a list cut to `[from, to)`
   * equals the one from the whole-store view. Balances outside the period are not in it.
   */
  fun period(from: Instant?, to: Instant?): Flow<LedgerView> =
      combine(store.changes(), outbox.rows, session.staffName) { _, rows, staff ->
            val pending = pendingCommands(rows)
            val debts = HashSet<String>()
            val contacts = HashSet<String>()
            for (command in pending) {
              when (val payload = command.payload()) {
                is PaymentCreate -> debts += payload.debtId
                is DebtCorrect -> debts += payload.debtId
                is DebtOffset -> debts += listOf(payload.receivableDebtId, payload.payableDebtId)
                is DebtCreate -> contacts += payload.contactId
                else -> Unit
              }
            }
            store
                .loadPeriod(from, to, debts, contacts)
                .view(pending, failedOperations(rows), session.selfInstallationId, staff)
          }
          .flowOn(Dispatchers.Default)

  /** The trash's contacts, built by [LedgerState.view]; nothing queued changes that list. */
  fun archivedContacts(): Flow<List<ContactView>> =
      combine(store.changes(), session.staffName) { _, staff ->
            store
                .loadArchived()
                .view(emptyList(), emptySet(), session.selfInstallationId, staff)
                .archivedContacts
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
