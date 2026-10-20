package com.sadad.app.feature.payment

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sadad.app.AppGraph
import com.sadad.app.R
import com.sadad.app.data.ledger.Refusal
import com.sadad.app.ui.components.parseCents
import com.sadad.ledger.Cents
import com.sadad.ledger.Direction
import com.sadad.ledger.LedgerView
import com.sadad.ledger.PaymentAllocation
import com.sadad.ledger.PaymentMethod
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One Debt's share of the payment, as [PaymentAllocation] splits it. */
data class PaymentPart(val debtId: String, val createdAt: Instant, val amount: Cents)

/** The values the review dialog shows, frozen when the user asks to save. */
data class PaymentReview(
    val name: String,
    val amount: Cents,
    val method: PaymentMethod,
    val note: String?,
    val parts: List<PaymentPart>,
)

/** The record the success dialog shows once the parts are in the outbox. */
data class PaymentConfirmation(val name: String, val amount: Cents)

data class PaymentFormState(
    val loaded: Boolean = false,
    /** The route's Contact is not in the ledger; the form offers nothing to record. */
    val missing: Boolean = false,
    /** The installation may not register payments: the screen shows the restricted state only. */
    val restricted: Boolean = false,
    val name: String = "",
    /** Sum of `remaining` over the Contact's open receivable Debts. */
    val openBalance: Cents = Cents.ZERO,
    val amount: String = "",
    val method: PaymentMethod = PaymentMethod.CASH,
    val note: String = "",
    val review: PaymentReview? = null,
    /** The parts are queued: the screen shows the success text and closes. */
    val confirmation: PaymentConfirmation? = null,
    val busy: Boolean = false,
    val error: String? = null,
) {
  val saved: Boolean
    get() = confirmation != null
}

/**
 * A Contact's payment against its whole open balance: amount, method and note, the over-balance
 * check before the review, then one `payment.create` per part the review showed. The parts come
 * from [PaymentAllocation], the same function the repository's `payContact` queues with, so the
 * review never states a split the write would not make. The three fields live in the
 * [SavedStateHandle], so a recreated screen keeps them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PaymentFormViewModel(
    private val graph: AppGraph,
    private val contactId: String,
    private val saved: SavedStateHandle,
) : ViewModel() {
  private val mutable =
      MutableStateFlow(
          PaymentFormState(
              amount = saved.get<String>(AMOUNT).orEmpty(),
              method = saved.get<String>(METHOD)?.let(::methodOf) ?: PaymentMethod.CASH,
              note = saved.get<String>(NOTE).orEmpty(),
          )
      )
  val state: StateFlow<PaymentFormState> = mutable.asStateFlow()

  /** The view the last review was built from; the parts are allocated on it, never on a guess. */
  @Volatile private var currentView: LedgerView? = null

  init {
    viewModelScope.launch {
      combine(view(), permissions()) { view, allowed -> project(view, allowed) }
          .collect { facts ->
            mutable.update { current ->
              current.copy(
                  loaded = facts.loaded,
                  missing = facts.missing,
                  restricted = facts.restricted,
                  name = facts.name,
                  openBalance = facts.openBalance,
              )
            }
          }
    }
  }

  fun onAmountChange(value: String) {
    if (mutable.value.busy || mutable.value.saved) return
    saved[AMOUNT] = value
    mutable.update { it.copy(amount = value, review = null, error = null) }
  }

  fun onMethodChange(method: PaymentMethod) {
    if (mutable.value.busy || mutable.value.saved) return
    saved[METHOD] = method.name
    mutable.update { it.copy(method = method, review = null) }
  }

  fun onNoteChange(value: String) {
    if (mutable.value.busy || mutable.value.saved) return
    saved[NOTE] = value
    mutable.update { it.copy(note = value, review = null) }
  }

  /** «مراجعة وحفظ الدفعة»: refuses a bad or over-balance amount before anything is planned. */
  fun save(context: Context) {
    val current = mutable.value
    if (!current.loaded || current.missing || current.restricted) return
    if (current.busy || current.saved || current.review != null) return
    val amount = parseCents(current.amount)
    if (amount == null) {
      mutable.update { it.copy(error = context.getString(R.string.payment_amount_invalid)) }
      return
    }
    if (amount > current.openBalance) {
      mutable.update { it.copy(error = context.getString(R.string.payment_over_balance)) }
      return
    }
    val parts = partsOf(currentView, amount)
    if (parts.isEmpty()) {
      mutable.update { it.copy(error = context.getString(R.string.payment_amount_invalid)) }
      return
    }
    mutable.update {
      it.copy(
          review =
              PaymentReview(
                  name = current.name,
                  amount = amount,
                  method = current.method,
                  note = current.note.trim().ifEmpty { null },
                  parts = parts,
              ),
          error = null,
      )
    }
  }

  fun dismissReview() = mutable.update { it.copy(review = null) }

  fun dismissConfirmation() = mutable.update { it.copy(confirmation = null) }

  fun clearError() = mutable.update { it.copy(error = null) }

  /** «تأكيد تسجيل الدفعة»: the reviewed amount is split and queued, one part per Debt. */
  fun confirm(context: Context) {
    val current = mutable.value
    val review = current.review ?: return
    if (current.busy || current.saved || current.restricted) return
    mutable.update { it.copy(review = null, busy = true, error = null) }
    val repository = graph.current.value?.repository
    if (repository == null) {
      mutable.update { it.copy(busy = false, error = context.getString(R.string.payment_failed)) }
      return
    }
    viewModelScope.launch {
      repository
          .payContact(contactId, review.amount, review.method, review.note)
          .fold(
              onSuccess = {
                mutable.update {
                  it.copy(
                      busy = false,
                      confirmation = PaymentConfirmation(review.name, review.amount),
                  )
                }
              },
              onFailure = { failure ->
                mutable.update { it.copy(busy = false, error = refusalText(context, failure)) }
              },
          )
    }
  }

  /** The parts the review shows, from the one allocator the write uses. */
  private fun partsOf(view: LedgerView?, amount: Cents): List<PaymentPart> {
    if (view == null) return emptyList()
    val parts =
        runCatching { PaymentAllocation.allocate(view, contactId, amount) }.getOrNull()
            ?: return emptyList()
    val createdAt = view.debts.associate { debt -> debt.id to debt.createdAt }
    return parts.mapNotNull { part ->
      createdAt[part.debtId]?.let { at -> PaymentPart(part.debtId, at, part.amount) }
    }
  }

  private data class Facts(
      val loaded: Boolean = false,
      val missing: Boolean = false,
      val restricted: Boolean = false,
      val name: String = "",
      val openBalance: Cents = Cents.ZERO,
  )

  private fun project(view: LedgerView?, permissions: Map<String, Boolean>): Facts {
    currentView = view
    val restricted = permissions["registerPayments"] == false
    if (view == null) return Facts(restricted = restricted)
    val contact = view.contacts.firstOrNull { it.id == contactId }
    if (contact == null) return Facts(loaded = true, missing = true, restricted = restricted)
    // The same set PaymentAllocation splits: visible, receivable and still open.
    val openBalance =
        view.debts
            .filter {
              it.contactId == contactId &&
                  it.direction == Direction.RECEIVABLE &&
                  it.remaining > Cents.ZERO
            }
            .fold(Cents.ZERO) { total, debt -> total + debt.remaining }
    return Facts(
        loaded = true,
        restricted = restricted,
        name = contact.name,
        openBalance = openBalance,
    )
  }

  private fun view(): Flow<LedgerView?> =
      graph.current.flatMapLatest { scope -> scope?.repository?.view ?: flowOf(null) }

  private fun permissions(): Flow<Map<String, Boolean>> =
      graph.current.flatMapLatest { scope ->
        val storeUserId = scope?.account?.storeUserId
        graph.accounts.accounts.map { accounts ->
          accounts
              .firstOrNull { it.storeUserId == storeUserId }
              ?.installation
              ?.permissions
              .orEmpty()
        }
      }

  private fun refusalText(context: Context, failure: Throwable): String =
      when (failure) {
        // The only Invalid payContact raises for a form that already parsed the amount.
        is Refusal.Invalid -> context.getString(R.string.payment_over_balance)
        is Refusal.OnlineOnly,
        is Refusal.OfflineNotAllowed -> context.getString(R.string.payment_offline)
        else -> context.getString(R.string.payment_failed)
      }

  private fun methodOf(name: String): PaymentMethod? =
      PaymentMethod.entries.firstOrNull { it.name == name }

  private companion object {
    const val AMOUNT = "payment:amount"
    const val METHOD = "payment:method"
    const val NOTE = "payment:note"
  }
}
