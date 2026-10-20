package com.sadad.app.feature.debt

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sadad.app.R
import com.sadad.app.data.ledger.LedgerRepository
import com.sadad.app.ui.components.parseCents
import com.sadad.ledger.Cents
import com.sadad.ledger.ContactView
import com.sadad.ledger.Direction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DebtReview(val contact: ContactView, val amount: Cents)

data class DebtFormState(
    val contact: ContactView? = null,
    val amount: String = "",
    val review: DebtReview? = null,
    val busy: Boolean = false,
    val saved: Boolean = false,
    val message: String? = null,
)

/** Queues a receivable only after the user confirms the reviewed values. */
class DebtFormViewModel(
    private val repository: LedgerRepository,
    val storeUserId: String,
    context: Context,
    private val savedState: SavedStateHandle,
    val contactId: String,
) : ViewModel() {
  private val app = context.applicationContext
  private val mutable = MutableStateFlow(DebtFormState(amount = savedState["amount"] ?: ""))
  val state = mutable.asStateFlow()

  init {
    viewModelScope.launch {
      repository.view.collect { view ->
        mutable.update { it.copy(contact = view?.contacts?.firstOrNull { it.id == contactId }) }
      }
    }
  }

  fun onAmountChange(value: String) {
    if (mutable.value.busy || mutable.value.saved) return
    savedState["amount"] = value
    mutable.update { it.copy(amount = value, review = null, message = null) }
  }

  fun save() {
    val current = mutable.value
    if (current.busy || current.saved) return
    val contact = current.contact ?: return
    val amount = parseCents(current.amount) ?: return
    mutable.update { it.copy(review = DebtReview(contact, amount)) }
  }

  fun confirm() {
    val current = mutable.value
    if (current.busy || current.saved) return
    val review = current.review ?: return
    mutable.update { it.copy(review = null, busy = true, message = null) }
    viewModelScope.launch {
      val result = repository.addDebt(contactId, Direction.RECEIVABLE, review.amount, null, null)
      mutable.update {
        result.fold(
            onSuccess = { _ -> it.copy(busy = false, saved = true) },
            onFailure = { _ ->
              it.copy(busy = false, message = app.getString(R.string.debt_failed))
            },
        )
      }
    }
  }
}
