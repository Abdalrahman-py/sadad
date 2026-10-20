package com.sadad.app.feature.contacts

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sadad.app.AppGraph
import com.sadad.app.R
import com.sadad.app.export.MovementKind
import com.sadad.app.export.StatementExport
import com.sadad.app.export.StatementHeader
import com.sadad.ledger.Cents
import com.sadad.ledger.Direction
import com.sadad.ledger.LedgerView
import com.sadad.ledger.PaymentMethod
import java.time.Instant
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One row of a Contact's history: a Debt or a Payment, newest or oldest first. */
data class ContactMovement(
    val id: String,
    val kind: MovementKind,
    val amount: Cents,
    val method: PaymentMethod?,
    val note: String,
    val createdAt: Instant,
    val pending: Boolean,
)

data class ContactDetailState(
    val loaded: Boolean = false,
    val missing: Boolean = false,
    val name: String = "",
    val phone: String = "",
    val category: String = "",
    val note: String = "",
    val balance: Cents = Cents.ZERO,
    val payable: Cents = Cents.ZERO,
    val movements: List<ContactMovement> = emptyList(),
    val newestFirst: Boolean = true,
    val canRegisterPayments: Boolean = true,
    val canDeleteContacts: Boolean = true,
    /** The amount to settle before deleting, when deleting is refused. */
    val settleFirst: Cents? = null,
    val confirmDelete: Boolean = false,
    /** The Contact waiting in the undo banner; nothing is queued until the banner expires. */
    val undoName: String? = null,
    val error: String? = null,
    /** The archive was queued: the screen goes back to the list. */
    val done: Boolean = false,
)

/**
 * One Contact's balance and history from the one [LedgerView], its delete flow (settle first, then
 * confirm, then undo before the archive is queued), and the two ways out of the app: WhatsApp and
 * the statement share sheet.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContactDetailViewModel(
    private val graph: AppGraph,
    private val contactId: String,
) : ViewModel() {
  private data class Dialogs(
      val newestFirst: Boolean = true,
      val settleFirst: Cents? = null,
      val confirmDelete: Boolean = false,
      val undoName: String? = null,
      val error: String? = null,
      val done: Boolean = false,
  )

  private val dialogs = MutableStateFlow(Dialogs())
  private val mutable = MutableStateFlow(ContactDetailState())
  val state: StateFlow<ContactDetailState> = mutable.asStateFlow()

  @Volatile private var currentView: LedgerView? = null

  init {
    viewModelScope.launch {
      combine(view(), dialogs, permissions()) { view, ui, allowed -> Triple(view, ui, allowed) }
          .flowOn(Dispatchers.Default)
          .collect { (view, ui, allowed) ->
            currentView = view
            mutable.value = project(view, ui, allowed)
          }
    }
  }

  fun toggleOrder() = dialogs.update { it.copy(newestFirst = !it.newestFirst) }

  fun requestDelete() {
    val current = mutable.value
    if (!current.loaded || current.missing) return
    val settling = maxOf(current.balance, current.payable)
    if (settling > Cents.ZERO) {
      dialogs.update { it.copy(settleFirst = settling) }
    } else {
      dialogs.update { it.copy(confirmDelete = true) }
    }
  }

  fun dismissSettleFirst() = dialogs.update { it.copy(settleFirst = null) }

  fun dismissConfirm() = dialogs.update { it.copy(confirmDelete = false) }

  fun confirmDelete() = dialogs.update {
    it.copy(confirmDelete = false, undoName = mutable.value.name)
  }

  fun undoDelete() = dialogs.update { it.copy(undoName = null) }

  /** The undo window ended: queue the archive now, keeping the rows if the server refuses it. */
  fun commitDelete(context: Context) {
    if (dialogs.value.undoName == null) return
    val repository = graph.current.value?.repository
    if (repository == null) {
      dialogs.update {
        it.copy(undoName = null, error = context.getString(R.string.contacts_session_closed))
      }
      return
    }
    viewModelScope.launch {
      repository
          .archiveContact(contactId, ARCHIVE_REASON)
          .fold(
              onSuccess = {
                dialogs.update { current -> current.copy(undoName = null, done = true) }
              },
              onFailure = { failure ->
                dialogs.update { current -> current.copy(undoName = null, error = failure.message) }
              },
          )
    }
  }

  fun clearError() = dialogs.update { it.copy(error = null) }

  fun reportError(text: String) = dialogs.update { it.copy(error = text) }

  /** The digits WhatsApp needs, or null when the Contact has no usable phone. */
  fun whatsAppNumber(): String? = mutable.value.phone.filter(Char::isDigit).ifEmpty { null }

  suspend fun share(context: Context) {
    val view = currentView ?: return
    val account = graph.current.value?.account
    val header =
        StatementHeader(
            store =
                account?.storeName?.ifBlank { null }
                    ?: context.getString(R.string.contacts_default_store),
            owner = account?.ownerName.orEmpty(),
            periodLabel = context.getString(R.string.contacts_all_periods),
        )
    runCatching {
      StatementExport.sharePdf(context, header, view, contactId, from = null, to = null)
    }
        .onFailure { failure ->
          dialogs.update { current -> current.copy(error = failure.message) }
        }
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

  private fun project(
      view: LedgerView?,
      dialogs: Dialogs,
      permissions: Map<String, Boolean>,
  ): ContactDetailState {
    val allowedPayments = permissions["registerPayments"] != false
    val allowedDelete = permissions["deleteContacts"] != false
    val contact = view?.contacts?.firstOrNull { it.id == contactId }
    if (contact == null) {
      return ContactDetailState(
          loaded = view != null,
          missing = view != null,
          newestFirst = dialogs.newestFirst,
          canRegisterPayments = allowedPayments,
          canDeleteContacts = allowedDelete,
          settleFirst = dialogs.settleFirst,
          confirmDelete = dialogs.confirmDelete,
          undoName = dialogs.undoName,
          error = dialogs.error,
          done = dialogs.done,
      )
    }
    val own = view.debts.filter { it.contactId == contactId }
    val balance =
        own.filter { it.direction == Direction.RECEIVABLE }
            .fold(Cents.ZERO) { total, debt -> total + debt.remaining }
    val payable =
        own.filter { it.direction == Direction.PAYABLE }
            .fold(Cents.ZERO) { total, debt -> total + debt.remaining }
    val movements =
        own.map { debt ->
          ContactMovement(
              id = debt.id,
              kind = MovementKind.DEBT,
              amount = debt.amount,
              method = null,
              note = debt.note,
              createdAt = debt.createdAt,
              pending = debt.pending || debt.hasPendingPayments,
          )
        } +
            view.payments
                .filter { it.contactId == contactId && !it.reversed }
                .map { payment ->
                  ContactMovement(
                      id = payment.id,
                      kind = MovementKind.PAYMENT,
                      amount = payment.amount,
                      method = payment.method,
                      note = payment.note,
                      createdAt = payment.createdAt,
                      pending = payment.pending,
                  )
                }
    val ordered =
        movements.sortedWith(
            if (dialogs.newestFirst) {
              compareByDescending<ContactMovement> { it.createdAt }.thenBy { it.id }
            } else {
              compareBy<ContactMovement> { it.createdAt }.thenBy { it.id }
            }
        )
    return ContactDetailState(
        loaded = true,
        name = contact.name,
        phone = contact.phone,
        category = contact.category,
        note = contact.note,
        balance = balance,
        payable = payable,
        movements = ordered,
        newestFirst = dialogs.newestFirst,
        canRegisterPayments = allowedPayments,
        canDeleteContacts = allowedDelete,
        settleFirst = dialogs.settleFirst,
        confirmDelete = dialogs.confirmDelete,
        undoName = dialogs.undoName,
        error = dialogs.error,
        done = dialogs.done,
    )
  }

  private companion object {
    const val ARCHIVE_REASON = "حذف الزبون من التطبيق"
  }
}
