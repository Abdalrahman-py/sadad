package com.sadad.app.feature.reports

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sadad.app.AppGraph
import com.sadad.app.R
import com.sadad.app.export.MovementKind
import com.sadad.app.export.Statement
import com.sadad.app.export.StatementExport
import com.sadad.app.export.StatementFormat
import com.sadad.app.export.StatementHeader
import com.sadad.app.feature.contacts.searchText
import com.sadad.app.feature.history.Period
import com.sadad.app.feature.history.PeriodPreset
import com.sadad.app.feature.history.bounds
import com.sadad.app.feature.history.encoded
import com.sadad.app.feature.history.periodLabel
import com.sadad.app.feature.history.periodOf
import com.sadad.app.feature.lock.AppLock
import com.sadad.ledger.Cents
import com.sadad.ledger.Direction
import com.sadad.ledger.LedgerView
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

/** [StatementExport.writePdf]'s shape: the document's `content://` URI for the viewer. */
typealias PdfWriterCall =
    suspend (Context, StatementHeader, LedgerView, String?, Instant?, Instant?) -> Uri

/**
 * The reports screen: the period and the search text live in [saved], so a recreated screen keeps
 * them. Totals and rows are projected on [Dispatchers.Default] from the one [LedgerView]; the
 * documents are written from that same view with ticket 56's [StatementExport].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReportsViewModel(
    private val graph: AppGraph,
    private val saved: SavedStateHandle,
    private val clock: Clock = Clock.systemDefaultZone(),
    /** Writes one Contact's PDF; a seam because Robolectric's `PdfDocument` cannot draw pages. */
    private val writePdf: PdfWriterCall = StatementExport::writePdf,
) : ViewModel() {
  private val period = MutableStateFlow(periodOf(saved.get<String>(PERIOD)))
  private val query = MutableStateFlow(saved.get<String>(QUERY).orEmpty())
  private val mutable =
      MutableStateFlow(
          ReportsState(period = period.value, query = query.value, queryInput = query.value)
      )
  val state: StateFlow<ReportsState> = mutable.asStateFlow()

  init {
    viewModelScope.launch {
      val base =
          combine(view(), period) { view, period ->
            if (view == null) ReportBase(null, period, ReportTotals(), emptySet(), emptyMap())
            else reportBaseOf(view, period, clock.zone)
          }
      combine(base, query, ::reportOf).flowOn(Dispatchers.Default).collect { projected ->
        // The field and the export state belong to the screen, not to the projection.
        mutable.update {
          projected.copy(
              queryInput = it.queryInput,
              busy = it.busy,
              saved = it.saved,
              error = it.error,
          )
        }
      }
    }
  }

  fun selectPreset(preset: PeriodPreset) = select(periodOf(preset, LocalDate.now(clock)))

  fun selectCustom(from: LocalDate, to: LocalDate) = select(Period.Custom(from, to))

  fun select(value: Period) {
    saved[PERIOD] = value.encoded()
    period.value = value
    mutable.update { it.copy(saved = false, error = null) }
  }

  fun onQueryChange(value: String) {
    saved[QUERY] = value
    query.value = value
    // The field reads this value; waiting for the projection on Dispatchers.Default would drop
    // characters typed faster than one round trip.
    mutable.update { it.copy(queryInput = value, saved = false, error = null) }
  }

  /**
   * Whether there is anything to write; otherwise the message is set. The screen asks before it
   * opens the document picker, which would create an empty document the export could not fill.
   */
  fun canExport(context: Context): Boolean {
    val view = graph.current.value?.repository?.view?.value
    if (view == null || state.value.busy) return false
    if (view.contacts.isEmpty()) {
      mutable.update {
        it.copy(error = context.getString(R.string.reports_nothing_to_export), saved = false)
      }
      return false
    }
    return true
  }

  /** Writes the whole statement for the chosen period to [uri], in the format the user picked. */
  fun saveStatement(activity: Context, uri: Uri, format: StatementFormat) {
    // The work outlives a rotation, so it must not hold the Activity.
    val context = activity.applicationContext
    val request = prepare(context, contactId = null) ?: return
    viewModelScope.launch {
      runCatching {
        StatementExport.save(
            context = context,
            uri = uri,
            format = format,
            header = request.header,
            view = request.view,
            contactId = null,
            from = request.from,
            to = request.to,
        )
      }
          .onSuccess { finish(saved = true) }
          .onFailure { fail(context, it, R.string.reports_export_failed) }
    }
  }

  /** Writes one Contact's PDF statement and opens it in the phone's PDF viewer. */
  fun openStatement(activity: Context, contactId: String) {
    // The work outlives a rotation, so it must not hold the Activity.
    val context = activity.applicationContext
    val request = prepare(context, contactId) ?: return
    viewModelScope.launch {
      runCatching {
        val uri =
            writePdf(
                context,
                request.header,
                request.view,
                contactId,
                request.from,
                request.to,
            )
        withContext(Dispatchers.Main) {
          val view =
              Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, StatementFormat.PDF.mimeType)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
              }
          context.startActivity(view)
          // Armed right after the launch succeeds and before the main looper delivers the
          // background event, so a failed launch (no PDF viewer) does not skip a later lock.
          AppLock.get(context).skipNextLock()
        }
      }
          .onSuccess { finish(saved = false) }
          .onFailure { fail(context, it, R.string.reports_open_failed) }
    }
  }

  /** Writes one Contact's PDF statement and offers it to Android's share sheet. */
  fun shareStatement(activity: Context, contactId: String) {
    // The work outlives a rotation, so it must not hold the Activity.
    val context = activity.applicationContext
    val request = prepare(context, contactId) ?: return
    viewModelScope.launch {
      runCatching {
        StatementExport.sharePdf(
            context,
            request.header,
            request.view,
            contactId,
            request.from,
            request.to,
        )
      }
          .onSuccess { finish(saved = false) }
          .onFailure { fail(context, it, R.string.reports_open_failed) }
    }
  }

  private class Request(
      val view: LedgerView,
      val header: StatementHeader,
      val from: Instant?,
      val to: Instant?,
  )

  /** The newest view and the chosen period, or null when a document is already being written. */
  private fun prepare(context: Context, contactId: String?): Request? {
    if (state.value.busy) return null
    val view = graph.current.value?.repository?.view?.value
    if (view == null) return refuse(context, R.string.reports_export_failed)
    if (view.contacts.none { contactId == null || it.id == contactId }) {
      return refuse(context, R.string.reports_nothing_to_export)
    }
    mutable.update { it.copy(busy = true, error = null, saved = false) }
    val account = graph.current.value?.account
    val period = period.value
    val bounds = period.bounds(clock.zone)
    val header =
        StatementHeader(
            store =
                account?.storeName?.ifBlank { null }
                    ?: context.getString(R.string.reports_default_store),
            owner = account?.ownerName.orEmpty(),
            periodLabel = periodLabel(context, period),
        )
    return Request(view, header, bounds.from, bounds.to?.minusNanos(1))
  }

  private fun refuse(context: Context, message: Int): Request? {
    mutable.update { it.copy(error = context.getString(message), saved = false) }
    return null
  }

  private fun finish(saved: Boolean) {
    mutable.update { it.copy(busy = false, saved = saved) }
  }

  private fun fail(context: Context, failure: Throwable, message: Int) {
    if (failure is CancellationException) throw failure
    mutable.update {
      it.copy(busy = false, error = context.getString(message), saved = false)
    }
  }

  private fun view(): Flow<LedgerView?> =
      graph.current.flatMapLatest { scope -> scope?.repository?.view ?: flowOf(null) }

  private companion object {
    const val PERIOD = "reports:period"
    const val QUERY = "reports:query"
  }
}
