package com.sadad.app.feature.debt

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sadad.app.AppGraph
import com.sadad.app.R
import com.sadad.app.data.ledger.Refusal
import com.sadad.app.ui.components.parseCents
import com.sadad.app.ui.components.parseDueDate
import com.sadad.ledger.Cents
import com.sadad.ledger.Direction
import com.sadad.ledger.LedgerView
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** From this amount on, the form asks for a second look before it shows the review. */
val ANOMALY_LIMIT: Cents = Cents(500_000)

/** The values the review dialog shows, frozen when the user asks to save. */
data class DebtReview(
    val name: String,
    val phone: String,
    val amount: Cents,
    val dueDate: String?,
    val note: String?,
)

data class DebtFormState(
    val loaded: Boolean = false,
    /** The route's Contact is not in the ledger; the form offers nothing to record. */
    val missing: Boolean = false,
    val name: String = "",
    val phone: String = "",
    val amount: String = "",
    /** `yyyy-MM-dd`, exactly as the wire carries it. */
    val dueDate: String? = null,
    val note: String = "",
    /** An unusually large amount waiting for «المبلغ صحيح، متابعة»; nothing is queued yet. */
    val anomaly: Cents? = null,
    val review: DebtReview? = null,
    val busy: Boolean = false,
    /** The Debt is in the outbox: the screen shows the success text and goes back. */
    val saved: Boolean = false,
    val error: String? = null,
)

/**
 * The debt form for one Contact: amount, optional due date and details, the unusual-amount check,
 * then one review before [com.sadad.app.data.ledger.LedgerRepository.addDebt] queues a receivable
 * Debt. The three fields live in the [SavedStateHandle], so a recreated screen keeps them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DebtFormViewModel(
    private val graph: AppGraph,
    private val contactId: String,
    private val saved: SavedStateHandle,
) : ViewModel() {
  private val mutable =
      MutableStateFlow(
          DebtFormState(
              amount = saved.get<String>(AMOUNT).orEmpty(),
              dueDate = saved.get<String>(DUE),
              note = saved.get<String>(NOTE).orEmpty(),
          )
      )
  val state: StateFlow<DebtFormState> = mutable.asStateFlow()

  /**
   * The amount text the user already waved through the unusual-amount dialog, so the question is
   * asked once per entered value rather than once per tap.
   */
  private var anomalyApproved: String? = null

  init {
    viewModelScope.launch {
      view().collect { view ->
        val contact = view?.contacts?.firstOrNull { it.id == contactId }
        mutable.update { current ->
          current.copy(
              loaded = view != null,
              missing = view != null && contact == null,
              name = contact?.name.orEmpty(),
              phone = contact?.phone.orEmpty(),
          )
        }
      }
    }
  }

  fun onAmountChange(value: String) {
    if (mutable.value.busy || mutable.value.saved) return
    saved[AMOUNT] = value
    anomalyApproved = null
    mutable.update { it.copy(amount = value, anomaly = null, review = null, error = null) }
  }

  fun onNoteChange(value: String) {
    if (mutable.value.busy || mutable.value.saved) return
    saved[NOTE] = value
    mutable.update { it.copy(note = value, review = null) }
  }

  /** The date picker's `yyyy-MM-dd`, or null when the user removes the due date. */
  fun onDueDateChange(value: String?) {
    if (mutable.value.busy || mutable.value.saved) return
    val date = value?.let { parseDueDate(it)?.toString() }
    saved[DUE] = date
    mutable.update { it.copy(dueDate = date, review = null) }
  }

  fun removeDueDate() = onDueDateChange(null)

  /** Validates, then asks the unusual-amount question or opens the review. */
  fun save(context: Context) {
    val current = mutable.value
    if (current.busy || current.saved || !current.loaded || current.missing) return
    val amount = parseCents(current.amount)
    if (amount == null) {
      mutable.update { it.copy(error = context.getString(R.string.debt_amount_invalid)) }
      return
    }
    if (amount >= ANOMALY_LIMIT && anomalyApproved != current.amount.trim()) {
      mutable.update { it.copy(anomaly = amount, error = null) }
      return
    }
    mutable.update { it.copy(anomaly = null, error = null, review = reviewOf(current, amount)) }
  }

  /** «المبلغ صحيح، متابعة»: this entered value is never asked about again, and the review opens. */
  fun confirmAnomaly() {
    val current = mutable.value
    val amount = current.anomaly ?: return
    anomalyApproved = current.amount.trim()
    mutable.update { it.copy(anomaly = null, review = reviewOf(current, amount)) }
  }

  fun dismissAnomaly() = mutable.update { it.copy(anomaly = null) }

  fun dismissReview() = mutable.update { it.copy(review = null) }

  fun clearError() = mutable.update { it.copy(error = null) }

  /** «تأكيد تسجيل الدين»: the reviewed values are queued as one receivable Debt. */
  fun confirm(context: Context) {
    val current = mutable.value
    val review = current.review ?: return
    if (current.busy || current.saved) return
    mutable.update { it.copy(review = null, busy = true, error = null) }
    val repository = graph.current.value?.repository
    if (repository == null) {
      mutable.update { it.copy(busy = false, error = context.getString(R.string.debt_failed)) }
      return
    }
    viewModelScope.launch {
      repository
          .addDebt(contactId, Direction.RECEIVABLE, review.amount, review.note, review.dueDate)
          .fold(
              onSuccess = { mutable.update { it.copy(busy = false, saved = true) } },
              onFailure = { failure ->
                mutable.update { it.copy(busy = false, error = refusalText(context, failure)) }
              },
          )
    }
  }

  private fun reviewOf(state: DebtFormState, amount: Cents): DebtReview =
      DebtReview(
          name = state.name,
          phone = state.phone,
          amount = amount,
          dueDate = state.dueDate,
          note = state.note.trim().ifEmpty { null },
      )

  private fun view(): Flow<LedgerView?> =
      graph.current.flatMapLatest { scope -> scope?.repository?.view ?: flowOf(null) }

  private fun refusalText(context: Context, failure: Throwable): String =
      when (failure) {
        is Refusal.OnlineOnly,
        is Refusal.OfflineNotAllowed -> context.getString(R.string.debt_offline)
        is Refusal.Invalid -> failure.message
        else -> context.getString(R.string.debt_failed)
      }

  private companion object {
    const val AMOUNT = "debt:amount"
    const val DUE = "debt:due-date"
    const val NOTE = "debt:note"
  }
}
