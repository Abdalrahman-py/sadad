package com.sadad.app.feature.history

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sadad.app.AppGraph
import com.sadad.app.R
import com.sadad.app.export.StatementExport
import com.sadad.app.export.StatementFormat
import com.sadad.app.export.StatementHeader
import com.sadad.ledger.Cents
import com.sadad.ledger.Direction
import com.sadad.ledger.LedgerView
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One Contact with movements in the chosen period: the row the people tab lists. */
data class HistoryPerson(
    val contactId: String,
    val name: String,
    /** Sum of `remaining` over the Contact's receivable Debts, in and out of the period. */
    val openBalance: Cents,
    /** The newest counted movement, which orders the list. */
    val lastMovementAt: Instant,
    /** The newest receivable Debt's amount, whatever period it was recorded in. */
    val lastDebt: Cents?,
)

data class HistoryUiState(
    val tab: HistoryTab = HistoryTab.PEOPLE,
    val period: Period = Period.All,
    val people: List<HistoryPerson> = emptyList(),
    val loaded: Boolean = false,
    /** The statement was written to the document the user picked. */
    val saved: Boolean = false,
    val error: String? = null,
)

enum class HistoryTab {
  PEOPLE,
  ACTIVITY,
}

/**
 * The people tab's rows: Contacts with at least one receivable Debt or non-reversed Payment inside
 * [bounds], newest movement first, as the Java screen's `buildHistory` counted them. A Contact's
 * open balance and last Debt are its whole history, not only the period.
 */
fun historyPeople(view: LedgerView, bounds: PeriodBounds): List<HistoryPerson> {
  val receivable = view.debts.filter { it.direction == Direction.RECEIVABLE }
  val receivableIds = receivable.mapTo(HashSet()) { it.id }
  val byContact = receivable.groupBy { it.contactId }
  val names = view.contacts.associate { it.id to it.name }

  val movements = HashMap<String, MutableList<Instant>>()
  for (debt in receivable) {
    if (debt.createdAt !in bounds) continue
    movements.getOrPut(debt.contactId) { mutableListOf() } += debt.createdAt
  }
  for (payment in view.payments) {
    if (payment.reversed || payment.debtId !in receivableIds) continue
    if (payment.createdAt !in bounds) continue
    movements.getOrPut(payment.contactId) { mutableListOf() } += payment.createdAt
  }

  return movements
      .mapNotNull { (contactId, times) ->
        val name = names[contactId] ?: return@mapNotNull null
        val debts = byContact[contactId].orEmpty()
        HistoryPerson(
            contactId = contactId,
            name = name,
            openBalance = debts.fold(Cents.ZERO) { total, debt -> total + debt.remaining },
            lastMovementAt = times.max(),
            lastDebt = debts.maxByOrNull { it.createdAt }?.amount,
        )
      }
      .sortedWith(compareByDescending<HistoryPerson> { it.lastMovementAt }.thenBy { it.name })
}

/**
 * The history tab: the period and the People/Activity tab live in [saved], so a recreated screen
 * keeps them. The people list follows the open store's [LedgerView]; the statement is written from
 * that same view with ticket 56's [StatementExport], for every Contact and the chosen period.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModel(
    private val graph: AppGraph,
    private val saved: SavedStateHandle,
    private val clock: Clock = Clock.systemDefaultZone(),
) : ViewModel() {
  private val tab = MutableStateFlow(tabOf(saved.get<String>(TAB)))
  private val period = MutableStateFlow(periodOf(saved.get<String>(PERIOD)))
  private val export = MutableStateFlow(Export())

  /** The view the statement is written from: the newest one the screen has shown. */
  @Volatile private var currentView: LedgerView? = null

  private val rows: StateFlow<HistoryUiState> =
      combine(view(), tab, period, export) { view, tab, period, export ->
            currentView = view
            HistoryUiState(
                tab = tab,
                period = period,
                people =
                    if (view == null) emptyList()
                    else historyPeople(view, period.bounds(clock.zone)),
                loaded = view != null,
                saved = export.saved,
                error = export.error,
            )
          }
          .stateIn(
              viewModelScope,
              SharingStarted.Eagerly,
              HistoryUiState(tab = tab.value, period = period.value),
          )

  val state: StateFlow<HistoryUiState> = rows

  fun onTabChange(value: HistoryTab) {
    saved[TAB] = value.name
    tab.value = value
  }

  fun selectPreset(preset: PeriodPreset) = select(periodOf(preset, LocalDate.now(clock)))

  fun selectCustom(from: LocalDate, to: LocalDate) = select(Period.Custom(from, to))

  fun select(value: Period) {
    saved[PERIOD] = value.encoded()
    period.value = value
  }

  /** Writes the whole statement for the chosen period to [uri], in the format the user picked. */
  fun saveStatement(context: Context, uri: Uri, format: StatementFormat) {
    val view = currentView
    if (view == null) {
      export.update { it.copy(error = context.getString(R.string.history_export_failed)) }
      return
    }
    if (export.value.busy) return
    export.update { it.copy(busy = true, error = null) }
    val account = graph.current.value?.account
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

  private fun view() =
      graph.current.flatMapLatest { scope -> scope?.repository?.view ?: flowOf(null) }

  private fun tabOf(name: String?): HistoryTab =
      HistoryTab.entries.firstOrNull { it.name == name } ?: HistoryTab.PEOPLE

  private data class Export(
      val busy: Boolean = false,
      val saved: Boolean = false,
      val error: String? = null,
  )

  private companion object {
    const val TAB = "history:tab"
    const val PERIOD = "history:period"
  }
}
