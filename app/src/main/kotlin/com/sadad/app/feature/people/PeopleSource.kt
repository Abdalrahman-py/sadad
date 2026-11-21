package com.sadad.app.feature.people

import com.sadad.app.AppGraph
import com.sadad.app.data.db.ContactSummaryEntity
import com.sadad.app.data.ledger.OutboxRow
import com.sadad.app.data.ledger.OutboxState
import com.sadad.ledger.ContactCreate
import com.sadad.ledger.ContactView
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

/**
 * What People reads: the stored per-person summary (ticket 160), drafts the server refused, and the
 * day. Until the one-time fill ends the screen shows its loading state — no ledger view exists
 * anymore (ticket 168).
 */
interface PeopleSource {
  /**
   * The summary list and the outbox rows of one emission of the summary table (ticket 215); null
   * while no store is open. The view model builds the failed drafts from the same list the rows
   * come from, so a person is in the list at most once.
   */
  val summariesAndOutbox: Flow<Pair<List<ContactSummaryEntity>, List<OutboxRow>>?>

  /** True once the one-time fill ends; from then the summary is the list, never the ledger. */
  val summaryReady: Flow<Boolean>
}

/**
 * Totally rejection-finished contact creates the summary does not know, mirroring `contactRecords`:
 * state REJECTED or UNSENDABLE, payload `ContactCreate`, id not in the summary.
 */
internal fun failedDraftContacts(
    rows: List<OutboxRow>,
    known: Set<String>,
): List<ContactView> = rows.mapNotNull { row ->
  if (row.state != OutboxState.REJECTED && row.state != OutboxState.UNSENDABLE)
      return@mapNotNull null
  if (row.entityId in known) return@mapNotNull null
  val payload = row.command.payload() as? ContactCreate ?: return@mapNotNull null
  ContactView(
      id = row.entityId,
      name = payload.name,
      phone = payload.phone.orEmpty(),
      kind = payload.kind,
      category = payload.category.orEmpty(),
      note = payload.note.orEmpty(),
      version = 0,
      pending = false,
      createdAt = row.createdAt,
      createdBy = "",
      operationId = row.operationId,
      whatsappCode = payload.whatsappCode ?: "970",
  )
}

@OptIn(ExperimentalCoroutinesApi::class)
fun AppGraph.peopleSource(): PeopleSource {
  val graph = this
  return object : PeopleSource {
    override val summariesAndOutbox: Flow<Pair<List<ContactSummaryEntity>, List<OutboxRow>>?> =
        graph.current.flatMapLatest { scope ->
          scope?.let {
            combine(it.database.contactSummaryDao().observe(), it.outbox.rows) { summaries, rows ->
              summaries to rows
            }
          } ?: flowOf(null)
        }

    override val summaryReady: Flow<Boolean> =
        graph.current.flatMapLatest { scope ->
          scope?.repository?.summaryReady ?: flowOf(false)
        }
  }
}
