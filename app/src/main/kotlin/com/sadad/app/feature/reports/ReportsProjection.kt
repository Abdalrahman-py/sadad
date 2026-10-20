package com.sadad.app.feature.reports

import com.sadad.app.export.MovementKind
import com.sadad.app.export.Statement
import com.sadad.app.feature.contacts.searchText
import com.sadad.app.feature.history.Period
import com.sadad.app.feature.history.bounds
import com.sadad.ledger.Cents
import com.sadad.ledger.Direction
import com.sadad.ledger.LedgerView
import java.time.ZoneId

/** The four numbers the reports screen shows for the chosen period. */
data class ReportTotals(
    /** Contacts with at least one counted movement in the period. */
    val contacts: Int = 0,
    val debtCount: Int = 0,
    val debtTotal: Cents = Cents.ZERO,
    val paymentCount: Int = 0,
    val paymentTotal: Cents = Cents.ZERO,
    /** The whole store's open receivable balance now, whatever the period. */
    val openBalance: Cents = Cents.ZERO,
)

/** One row of the per-Contact statements list. */
data class ReportPerson(
    val contactId: String,
    val name: String,
    val phone: String,
    /** Sum of `remaining` over the Contact's receivable Debts. */
    val openBalance: Cents,
)

data class ReportsState(
    val loaded: Boolean = false,
    val period: Period = Period.All,
    /** The text the last projection filtered on. */
    val query: String = "",
    /** What the search field shows: updated on the keystroke, before the projection catches up. */
    val queryInput: String = "",
    val totals: ReportTotals = ReportTotals(),
    val people: List<ReportPerson> = emptyList(),
    /** Visible Contacts in the store, whatever the period or the search. */
    val registered: Int = 0,
    /** A document is being written; the open, share and save actions wait for it. */
    val busy: Boolean = false,
    /** The statement was written to the document the user picked. */
    val saved: Boolean = false,
    val error: String? = null,
)

/**
 * What the view and the period fix, whatever the search says: typing in the field does not redo it.
 * [view] is null until the store's Ledger is loaded.
 */
class ReportBase(
    val view: LedgerView?,
    val period: Period,
    val totals: ReportTotals,
    /** Contacts with at least one counted movement in the period. */
    val moved: Set<String>,
    /** Per Contact, the sum of `remaining` over its receivable Debts. */
    val remaining: Map<String, Cents>,
)

/**
 * The period totals, read off [Statement.build] for the same period the statements are written for,
 * so the screen and the documents share one money rule: it counts only receivable Debts and the
 * live Payments on them, and its end is inclusive, so the period's exclusive end is stepped back
 * one nanosecond.
 */
fun reportBaseOf(view: LedgerView, period: Period, zone: ZoneId): ReportBase {
  val bounds = period.bounds(zone)
  val statements = Statement.build(view, null, bounds.from, bounds.to?.minusNanos(1))
  var debtCount = 0
  var paymentCount = 0
  var debtTotal = Cents.ZERO
  var paymentTotal = Cents.ZERO
  val moved = HashSet<String>()
  for (statement in statements) {
    if (statement.rows.isNotEmpty()) moved += statement.contact.id
    debtTotal += statement.totalDebt
    paymentTotal += statement.totalPaid
    for (row in statement.rows) {
      if (row.kind == MovementKind.DEBT) debtCount++ else paymentCount++
    }
  }
  val remaining = HashMap<String, Cents>()
  for (debt in view.debts) {
    if (debt.direction != Direction.RECEIVABLE) continue
    remaining.merge(debt.contactId, debt.remaining, Cents::plus)
  }
  val totals =
      ReportTotals(
          contacts = moved.size,
          debtCount = debtCount,
          debtTotal = debtTotal,
          paymentCount = paymentCount,
          paymentTotal = paymentTotal,
          openBalance = view.receivable,
      )
  return ReportBase(view, period, totals, moved, remaining)
}

/** The Contacts of the period whose name or phone contains [query], by name. */
fun reportOf(base: ReportBase, query: String): ReportsState {
  val view = base.view ?: return ReportsState(period = base.period, query = query)
  val needle = searchText(query)
  val phoneNeedle = needle.takeIf { it.none(Char::isLetter) }?.filter(Char::isDigit).orEmpty()
  val people =
      view.contacts
          .filter { base.period == Period.All || it.id in base.moved }
          .filter { contact ->
            needle.isEmpty() ||
                searchText(contact.name).contains(needle) ||
                (phoneNeedle.isNotEmpty() &&
                    searchText(contact.phone).filter(Char::isDigit).contains(phoneNeedle))
          }
          .map { ReportPerson(it.id, it.name, it.phone, base.remaining[it.id] ?: Cents.ZERO) }
          .sortedBy { it.name }
  return ReportsState(
      loaded = true,
      period = base.period,
      query = query,
      totals = base.totals,
      people = people,
      registered = view.contacts.size,
  )
}
