package com.sadad.app.feature.history

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sadad.app.R
import com.sadad.app.data.ledger.paymentGroups
import com.sadad.app.export.MovementKind
import com.sadad.app.export.StatementExport
import com.sadad.app.export.StatementFormat
import com.sadad.app.export.StatementHeader
import com.sadad.ledger.Cents
import com.sadad.ledger.DebtOrigin
import com.sadad.ledger.Direction
import com.sadad.ledger.LedgerView
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One recorded debt or saved payment, including allocations across multiple debts. */
data class HistoryMovement(
    val id: String,
    val contactId: String,
    val name: String,
    val kind: MovementKind,
    val direction: Direction,
    val amount: Cents,
    val createdAt: Instant,
    val pending: Boolean,
)

data class HistoryUiState(
    val period: Period = Period.All,
    val newestFirst: Boolean = true,
    val movements: List<HistoryMovement> = emptyList(),
    val loaded: Boolean = false,
    val saved: Boolean = false,
    val error: String? = null,
)

/** Read-only projection of visible debts and non-reversed payments, not raw ledger operations. */
internal fun historyMovements(
    view: LedgerView,
    bounds: PeriodBounds,
    newestFirst: Boolean,
): List<HistoryMovement> {
  val names = view.contacts.associate { it.id to it.name }
  val debts = view.debts.associateBy { it.id }
  val credits =
      view.debts
          .filter { it.origin == DebtOrigin.OVERPAYMENT && it.amount > Cents.ZERO }
          .associateBy { Triple(it.contactId, it.createdAt, it.createdBy) }
  val pairedCredits = HashSet<String>()
  val rows = mutableListOf<HistoryMovement>()
  for (parts in paymentGroups(view.payments.filter { !it.reversed }, debts)) {
    val first = parts.first()
    val direction = debts.getValue(first.debtId).direction
    val credit =
        credits[Triple(first.contactId, first.createdAt, first.createdBy)]?.takeIf {
          direction == Direction.RECEIVABLE
        }
    credit?.let { pairedCredits += it.id }
    if (first.createdAt !in bounds) continue
    val name = names[first.contactId] ?: continue
    rows +=
        HistoryMovement(
            id = first.id,
            contactId = first.contactId,
            name = name,
            kind = MovementKind.PAYMENT,
            direction = direction,
            amount = parts.fold(credit?.amount ?: Cents.ZERO) { sum, part -> sum + part.amount },
            createdAt = first.createdAt,
            pending = parts.any { it.pending } || credit?.pending == true,
        )
  }
  for (debt in view.debts) {
    if (debt.createdAt !in bounds || debt.id in pairedCredits) continue
    val name = names[debt.contactId] ?: continue
    val creditOnly = debt.origin == DebtOrigin.OVERPAYMENT
    if (creditOnly && debt.amount == Cents.ZERO) continue
    rows +=
        HistoryMovement(
            id = debt.id,
            contactId = debt.contactId,
            name = name,
            kind = if (creditOnly) MovementKind.PAYMENT else MovementKind.DEBT,
            direction = if (creditOnly) Direction.RECEIVABLE else debt.direction,
            amount = debt.amount,
            createdAt = debt.createdAt,
            pending = debt.pending,
        )
  }
  val order = compareBy<HistoryMovement> { it.createdAt }.thenBy { it.id }
  return rows.sortedWith(if (newestFirst) order.reversed() else order)
}

/** The movement feed follows the current store; period and order survive recreation. */
@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModel(
    private val source: StatementSource,
    private val saved: SavedStateHandle,
    private val clock: Clock = Clock.systemDefaultZone(),
) : ViewModel() {
  private val newestFirst = MutableStateFlow(saved.get<Boolean>(NEWEST_FIRST) ?: true)
  private val period = MutableStateFlow(periodOf(saved.get<String>(PERIOD)))
  private val export = MutableStateFlow(Export())

  /** The view the statement is written from: the newest one the screen has shown. */
  @Volatile private var currentView: LedgerView? = null

  private val rows: StateFlow<HistoryUiState> =
      combine(source.view, newestFirst, period, export) { view, newestFirst, period, export ->
            currentView = view
            HistoryUiState(
                newestFirst = newestFirst,
                period = period,
                movements =
                    if (view == null) emptyList()
                    else historyMovements(view, period.bounds(clock.zone), newestFirst),
                loaded = view != null,
                saved = export.saved,
                error = export.error,
            )
          }
          .flowOn(Dispatchers.Default)
          .stateIn(
              viewModelScope,
              SharingStarted.Eagerly,
              HistoryUiState(newestFirst = newestFirst.value, period = period.value),
          )

  val state: StateFlow<HistoryUiState> = rows

  fun setNewestFirst(value: Boolean) {
    saved[NEWEST_FIRST] = value
    newestFirst.value = value
  }

  fun selectPreset(preset: PeriodPreset) = select(periodOf(preset, LocalDate.now(clock)))

  fun selectCustom(from: LocalDate, to: LocalDate) = select(Period.Custom(from, to))

  fun select(value: Period) {
    saved[PERIOD] = value.encoded()
    period.value = value
  }

  /** Writes the whole statement for the chosen period to [uri], in the format the user picked. */
  /** The export's toast was shown: clear its result so it does not come back. */
  fun dismissExportResult() = export.update { it.copy(saved = false, error = null) }

  fun saveStatement(context: Context, uri: Uri, format: StatementFormat) {
    val view = currentView
    if (view == null) {
      export.update { it.copy(error = context.getString(R.string.history_export_failed)) }
      return
    }
    if (export.value.busy) return
    if (view.contacts.isEmpty()) {
      export.update {
        it.copy(saved = false, error = context.getString(R.string.history_export_empty))
      }
      return
    }
    export.update { it.copy(busy = true, saved = false, error = null) }
    val account = source.account()
    val header =
        StatementHeader(
            store =
                account?.storeName?.ifBlank { null }
                    ?: context.getString(R.string.history_default_store),
            owner = account?.ownerName.orEmpty(),
            periodLabel = periodLabel(context, period.value),
        )
    val bounds = period.value.bounds(clock.zone)
    viewModelScope.launch {
      runCatching {
        StatementExport.save(
            context = context,
            uri = uri,
            format = format,
            header = header,
            view = view,
            contactId = null,
            from = bounds.from,
            to = bounds.to,
        )
      }
          .onSuccess { export.update { it.copy(busy = false, saved = true) } }
          .onFailure { failure ->
            if (failure is CancellationException) throw failure
            export.update {
              it.copy(busy = false, error = context.getString(R.string.history_export_failed))
            }
          }
    }
  }

  fun consumeSaved() = export.update { it.copy(saved = false) }

  fun clearError() = export.update { it.copy(error = null) }

  private data class Export(
      val busy: Boolean = false,
      val saved: Boolean = false,
      val error: String? = null,
  )

  private companion object {
    const val NEWEST_FIRST = "history:newest-first"
    const val PERIOD = "history:period"
  }
}
