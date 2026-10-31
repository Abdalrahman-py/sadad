package com.sadad.app.feature.reports

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sadad.app.R
import com.sadad.app.export.StatementExport
import com.sadad.app.export.StatementFormat
import com.sadad.app.export.StatementHeader
import com.sadad.app.feature.history.Period
import com.sadad.app.feature.history.PeriodPreset
import com.sadad.app.feature.history.StatementSource
import com.sadad.app.feature.history.bounds
import com.sadad.app.feature.history.encoded
import com.sadad.app.feature.history.periodLabel
import com.sadad.app.feature.history.periodOf
import com.sadad.app.feature.lock.AppLock
import com.sadad.ledger.LedgerView
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    private val source: StatementSource,
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
          combine(source.view, period) { view, period ->
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
              // «nothing to export» is stale once the first Contact arrives.
              error = it.error.takeUnless { _ -> it.registered == 0 && projected.registered > 0 },
          )
        }
      }
    }
  }

  fun selectPreset(preset: PeriodPreset) = select(periodOf(preset, LocalDate.now(clock)))

  fun selectCustom(from: LocalDate, to: LocalDate) {
    require(!from.isAfter(to)) { "The range must not end before it starts" }
    select(Period.Custom(from, to))
  }

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
    if (state.value.busy) return false
    val view = source.latest()?.view
    val message =
        when {
          view == null -> R.string.reports_export_failed
          view.contacts.isEmpty() -> R.string.reports_nothing_to_export
          else -> {
            mutable.update { it.copy(error = null, saved = false) }
            return true
          }
        }
    mutable.update { it.copy(error = context.getString(message), saved = false) }
    return false
  }

  /** The phone has no document picker: say so instead of leaving the tap without an answer. */
  fun pickerUnavailable(context: Context) {
    mutable.update {
      it.copy(error = context.getString(R.string.reports_export_failed), saved = false)
    }
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
    // One scope for the view and the header: a store switch must not mix two stores.
    val latest = source.latest()
    val view = latest?.view
    if (latest == null || view == null) return refuse(context, R.string.reports_export_failed)
    if (view.contacts.none { contactId == null || it.id == contactId }) {
      return refuse(context, R.string.reports_nothing_to_export)
    }
    mutable.update { it.copy(busy = true, error = null, saved = false) }
    val account = latest.account
    val period = period.value
    val bounds = period.bounds(clock.zone)
    val header =
        StatementHeader(
            store =
                account.storeName.ifBlank { null }
                    ?: context.getString(R.string.reports_default_store),
            owner = account.ownerName,
            periodLabel = periodLabel(context, period),
        )
    return Request(view, header, bounds.from, bounds.to)
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

  private companion object {
    const val PERIOD = "reports:period"
    const val QUERY = "reports:query"
  }
}
