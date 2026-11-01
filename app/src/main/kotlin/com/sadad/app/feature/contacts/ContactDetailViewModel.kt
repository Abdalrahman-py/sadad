package com.sadad.app.feature.contacts

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sadad.app.R
import com.sadad.app.data.ledger.Refusal
import com.sadad.app.data.ledger.paymentGroups
import com.sadad.app.export.MovementKind
import com.sadad.app.export.StatementExport
import com.sadad.app.export.StatementHeader
import com.sadad.ledger.Cents
import com.sadad.ledger.Commands
import com.sadad.ledger.DebtOrigin
import com.sadad.ledger.DebtView
import com.sadad.ledger.Direction
import com.sadad.ledger.LedgerView
import com.sadad.ledger.PaymentMethod
import com.sadad.ledger.PaymentView
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One row of a Contact's history: a Debt or a Payment, newest or oldest first. */
data class ContactMovement(
    val id: String,
    val kind: MovementKind,
    val direction: Direction,
    val amount: Cents,
    val method: PaymentMethod?,
    val note: String,
    val createdAt: Instant,
    val pending: Boolean,
    /** The payment ids this row stands for: a compound's parts, so a reversal names them all. */
    val parts: List<String> = listOf(id),
    /** The credit Debt the payment left, when it overpaid; it is never a row of its own. */
    val creditDebtId: String? = null,
    /** The part of [amount] that settled Debts, when the row is an overpayment. */
    val settled: Cents? = null,
    /** The part of [amount] the store now holds as credit. */
    val credit: Cents? = null,
    /** The credit a later Debt took, on a Debt that was paid from credit. */
    val offsetted: Cents? = null,
    /** A Debt corrected to zero: still in the history, struck through. */
    val struck: Boolean = false,
    /** Whether «عكس الدفعة» may run on this row. */
    val canReverse: Boolean = false,
    /** Why it may not, when [canReverse] is false for a reason worth naming. */
    val blockedReason: String? = null,
)

data class ContactDetailState(
    val loaded: Boolean = false,
    val missing: Boolean = false,
    val syncFailure: String? = null,
    val savedDebts: List<com.sadad.ledger.DebtCreate> = emptyList(),
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
    private val source: ContactDetailSource,
    private val contactId: String,
    private val appContext: Context,
    /** The Java banner's five seconds; tests shorten it. */
    private val undoWindowMs: Long = UNDO_WINDOW_MS,
    /**
     * The undo window runs here, not in the screen's composition, like the Java banner on the
     * Activity: leaving the Contact during the five seconds must not drop a delete the dialog
     * already promised. It runs off the Main dispatcher so a test dispatcher's virtual clock cannot
     * fire it early, and it is a parameter so a test can hold the job and join it instead of racing
     * a background thread it cannot see (#168).
     */
    private val undoScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
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

  private var undoJob: Job? = null

  @Volatile private var currentView: LedgerView? = null

  init {
    viewModelScope.launch {
      combine(source.records, dialogs, source.permissions) { records, ui, allowed ->
            currentView = records?.ledger
            val draft = records?.failed?.firstOrNull { it.contact.id == contactId }
            if (draft == null) project(records?.ledger, ui, allowed)
            else
                ContactDetailState(
                    loaded = true,
                    name = draft.contact.name,
                    phone = draft.contact.phone,
                    category = draft.contact.category,
                    note = draft.contact.note,
                    syncFailure = source.refusalText(draft.code),
                    savedDebts = draft.debts,
                    canRegisterPayments = false,
                    canDeleteContacts = false,
                )
          }
          .flowOn(Dispatchers.Default)
          .collect { mutable.value = it }
    }
  }

  fun toggleOrder() = dialogs.update { it.copy(newestFirst = !it.newestFirst) }

  fun requestDelete() {
    val current = mutable.value
    if (
        !current.loaded ||
            current.missing ||
            !current.canDeleteContacts ||
            dialogs.value.undoName != null
    )
        return
    val settling = maxOf(current.balance, current.payable)
    if (settling > Cents.ZERO) {
      dialogs.update { it.copy(settleFirst = settling) }
    } else {
      dialogs.update { it.copy(confirmDelete = true) }
    }
  }

  fun dismissSettleFirst() = dialogs.update { it.copy(settleFirst = null) }

  fun dismissConfirm() = dialogs.update { it.copy(confirmDelete = false) }

  fun confirmDelete() {
    if (!dialogs.value.confirmDelete || !mutable.value.canDeleteContacts) return
    dialogs.update { it.copy(confirmDelete = false, undoName = mutable.value.name) }
    undoJob?.cancel()
    undoJob = undoScope.launch {
      delay(undoWindowMs)
      commitDelete()
    }
  }

  fun undoDelete() {
    undoJob?.cancel()
    undoJob = null
    dialogs.update { it.copy(undoName = null) }
  }

  /** The banner is gone without an undo: nothing is queued. */
  fun clearUndo() = dialogs.update { it.copy(undoName = null) }

  /** The undo window ended: queue the archive now, keeping the rows if the server refuses it. */
  fun commitDelete() {
    undoJob?.cancel()
    undoJob = null
    if (dialogs.value.undoName == null) return
    dialogs.update { it.copy(undoName = null) }
    val store = source.open()
    if (store == null) {
      dialogs.update {
        it.copy(undoName = null, error = appContext.getString(R.string.contacts_session_closed))
      }
      return
    }
    // The outbox write is not cancelled with this screen: it is one short write, not background
    // work, and the dialog the user confirmed already promised it.
    undoScope.launch {
      store
          .archive(contactId, ARCHIVE_REASON)
          .fold(
              onSuccess = {
                dialogs.update { current -> current.copy(undoName = null, done = true) }
              },
              onFailure = { failure ->
                dialogs.update { current ->
                  current.copy(undoName = null, error = refusalText(failure))
                }
              },
          )
    }
  }

  fun clearError() = dialogs.update { it.copy(error = null) }

  /**
   * «عكس الدفعة»: reverse every part of the compound, then zero the credit Debt it left. Refused
   * once any of that credit was used — the later movement has to be corrected first.
   */
  fun reverse(movementId: String) {
    val movement = mutable.value.movements.firstOrNull { it.id == movementId } ?: return
    if (movement.kind != MovementKind.PAYMENT || !movement.canReverse) return
    val scope = source.open() ?: return
    if (!scope.isOnline()) {
      dialogs.update { it.copy(error = appContext.getString(R.string.contacts_reverse_offline)) }
      return
    }
    val origin = scope.origin() ?: return
    val view = currentView
    val at = Instant.now()
    val commands =
        movement.parts.map { Commands.paymentReverse(origin, at, it, REVERSE_REASON) } +
            listOfNotNull(
                movement.creditDebtId?.let { id ->
                  view
                      ?.debts
                      ?.firstOrNull { it.id == id }
                      ?.let { credit ->
                        Commands.debtCorrect(
                            origin,
                            at,
                            id,
                            credit.version,
                            Cents.ZERO,
                            REVERSE_REASON,
                        )
                      }
                }
            )
    // One short write, like the archive: the user already confirmed it, so it outlives the screen.
    undoScope.launch {
      scope.append(commands)
    }
  }

  fun reportError(text: String) = dialogs.update { it.copy(error = text) }

  /**
   * The digits WhatsApp needs, or null when the Contact's phone is not a full international number;
   * opening `wa.me` with a partial number sends the message nowhere.
   */
  fun whatsAppNumber(): String? =
      mutable.value.phone.filter(Char::isDigit).takeIf { it.length in 7..15 }

  suspend fun share(context: Context) {
    val view = currentView ?: return
    val account = source.account()
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
          if (failure is CancellationException) throw failure
          dialogs.update { current ->
            current.copy(error = context.getString(R.string.contacts_share_failed))
          }
        }
  }

  /** One row per saved payment, including its allocations and any excess kept as credit. */
  private fun paymentRows(
      groups: Collection<List<PaymentView>>,
      credits: Map<Instant, DebtView>,
      debts: Map<String, DebtView>,
  ): List<ContactMovement> = groups.map { parts ->
    val first = parts.first()
    val credit =
        credits[first.createdAt]?.takeIf {
          debts.getValue(first.debtId).direction == Direction.RECEIVABLE &&
              it.createdBy == first.createdBy
        }
    val settled = parts.fold(Cents.ZERO) { total, part -> total + part.amount }
    val pending = parts.any { it.pending } || credit?.pending == true
    // An offset, or a correction, out of the credit means the reversal belongs to a later step.
    val spent = credit != null && credit.remaining < credit.amount
    ContactMovement(
        id = first.id,
        kind = MovementKind.PAYMENT,
        direction = debts.getValue(first.debtId).direction,
        amount = settled + (credit?.amount ?: Cents.ZERO),
        method = first.method,
        note = first.note,
        createdAt = first.createdAt,
        pending = pending,
        parts = parts.map { it.id },
        creditDebtId = credit?.id,
        settled = settled.takeIf { credit != null },
        credit = credit?.amount,
        canReverse = !pending && !spent,
        blockedReason =
            if (spent) appContext.getString(R.string.contacts_reverse_credit_used) else null,
    )
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
    val ownById = own.associateBy { it.id }
    // A credit Debt is the second half of the payment that left it, never a row of its own: the
    // quick-add flow writes both commands at one instant, and that instant is how they pair here.
    val credits =
        own.filter { it.origin == DebtOrigin.OVERPAYMENT && it.amount > Cents.ZERO }
            .associateBy { it.createdAt }
    val visible = view.payments.filter { it.contactId == contactId && !it.reversed }
    val groups = paymentGroups(visible, ownById)
    val paymentTimes = visible.mapTo(HashSet()) { it.createdAt }
    val movements =
        own.filter { debt ->
              !(debt.origin == DebtOrigin.OVERPAYMENT && debt.createdAt in paymentTimes)
            }
            .map { debt ->
              ContactMovement(
                  id = debt.id,
                  kind = MovementKind.DEBT,
                  direction = debt.direction,
                  amount = debt.amount,
                  method = null,
                  note = debt.note,
                  createdAt = debt.createdAt,
                  pending = debt.pending || debt.hasPendingPayments,
                  offsetted = debt.offsetted.takeIf { it > Cents.ZERO },
                  struck = debt.amount == Cents.ZERO,
              )
            } + paymentRows(groups, credits, ownById)
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

  private fun refusalText(error: Throwable): String =
      when (error) {
        // Archiving needs a connection, and an offline write without a lease means the same fix.
        is Refusal.OnlineOnly,
        is Refusal.OfflineNotAllowed -> appContext.getString(R.string.contacts_delete_offline)
        is Refusal.Invalid -> error.message
        else -> appContext.getString(R.string.contacts_delete_failed)
      }

  private companion object {
    const val ARCHIVE_REASON = "حذف الزبون من التطبيق"

    /** The reason a reversal carries on the wire, so the log says why. */
    const val REVERSE_REASON = "عكس دفعة من التطبيق"

    /** The Java banner's undo window. */
    const val UNDO_WINDOW_MS = 5_000L
  }
}
