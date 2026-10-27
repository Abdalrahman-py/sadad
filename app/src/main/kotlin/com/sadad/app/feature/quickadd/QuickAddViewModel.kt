package com.sadad.app.feature.quickadd

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sadad.app.AppGraph
import com.sadad.app.R
import com.sadad.app.data.session.StoreScope
import com.sadad.app.ui.components.parseCents
import com.sadad.ledger.Cents
import com.sadad.ledger.Commands
import com.sadad.ledger.ContactKind
import com.sadad.ledger.LedgerCommand
import com.sadad.ledger.LedgerView
import com.sadad.ledger.PaymentAllocation
import com.sadad.ledger.PaymentMethod
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

/** Where the one recording flow stands. */
enum class QuickAddStep {
  PICKER,
  NEW_PERSON,
  AMOUNT,
  DONE,
}

/** What the saved step shows: the sentence that was recorded, and the person's balance after it. */
data class QuickAddDone(
    val name: String,
    val sentence: String,
    val net: Cents,
    val secondsLeft: Int,
)

data class QuickAddState(
    val step: QuickAddStep = QuickAddStep.PICKER,
    val loaded: Boolean = false,
    /** The device may not save now: the offline lease lapsed or the device is not approved. */
    val blocked: Boolean = false,
    /** The installation may not register payments, so payment mode is not offered at all. */
    val restricted: Boolean = false,
    val query: String = "",
    /** The picker's rows: everyone when the query is empty, the matches otherwise. */
    val people: List<QuickAddPerson> = emptyList(),
    val selected: QuickAddPerson? = null,
    /** True when the amount being entered is a payment; false for a debt. */
    val payment: Boolean = false,
    val amount: String = "",
    val method: PaymentMethod = PaymentMethod.CASH,
    /** `yyyy-MM-dd`, exactly as the wire carries it. */
    val dueDate: String? = null,
    val note: String = "",
    /** An unusually large amount waiting for «المبلغ صحيح، احفظ»; nothing is queued yet. */
    val anomaly: Cents? = null,
    /** The selected Contact's open Debts: a payment above this is refused inline. */
    val overBalance: Cents = Cents.ZERO,
    val error: String? = null,
    val busy: Boolean = false,
    val done: QuickAddDone? = null,
    val newName: String = "",
    val newPhone: String = "",
    val newKind: ContactKind = ContactKind.CUSTOMER,
    /** The name of the Contact already holding [newPhone], if any. */
    val duplicate: String? = null,
) {
  /** The person the entry is about, including one being created in this flow. */
  val personName: String
    get() = selected?.name.orEmpty()

  /**
   * Whether the amount on screen may be saved: a positive amount, and a payment within the debt.
   */
  val canSave: Boolean
    get() {
      val value = parseCents(amount) ?: return false
      if (value <= Cents.ZERO) return false
      if (blocked) return false
      if (payment && value > overBalance) return false
      return true
    }
}

/**
 * The one flow that records anything: pick the person (or create them), type the amount, save with
 * a sentence that states what will be recorded, and undo for eight seconds. Nothing reaches the
 * outbox before the window ends: the commands are built here and handed to [StoreScope.outbox] on
 * commit, so «تراجع» is truly a no-op.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QuickAddViewModel(
    private val graph: AppGraph,
    private val contactId: String?,
    private val payment: Boolean,
    private val saved: SavedStateHandle,
    private val undoWindowMs: Long = UNDO_WINDOW_MS,
) : ViewModel() {
  private val mutable =
      MutableStateFlow(
          QuickAddState(
              payment = payment,
              amount = saved.get<String>(AMOUNT).orEmpty(),
              note = saved.get<String>(NOTE).orEmpty(),
              dueDate = saved.get<String>(DUE),
              method = saved.get<String>(METHOD)?.let(::methodOf) ?: PaymentMethod.CASH,
          )
      )
  val state: StateFlow<QuickAddState> = mutable.asStateFlow()

  /** The view the last preview was built from; the commands are built against it, never a guess. */
  @Volatile private var currentView: LedgerView? = null

  /**
   * Each Contact's open receivable total, kept so a pick can show it without waiting for a re-emit.
   */
  @Volatile private var openBalances: Map<String, Cents> = emptyMap()

  /**
   * The person a pending «new person» step will create; the contact command is held, not queued.
   */
  private var pendingContact: LedgerCommand? = null
  private var pendingPersonId: String? = null

  /** Whether the Contact this flow was opened for has been made the subject already. */
  private var startContactApplied = false

  /** The commands the done step holds; [commitJob] appends them when the window ends. */
  @Volatile private var pending: List<LedgerCommand> = emptyList()
  @Volatile private var commitJob: Job? = null
  private var anomalyApproved: String? = null

  init {
    viewModelScope.launch {
      combine(view(), permissions(), availability()) { view, permissions, saveBlocked ->
            project(view, permissions, saveBlocked)
          }
          .collect { facts ->
            mutable.update { current ->
              val people = facts.people
              val chosen =
                  current.selected?.let { selected -> people.firstOrNull { it.id == selected.id } }
              // The route's Contact is applied once, on the first projection that can resolve it:
              // going back to the picker must not drag the flow to the amount step again.
              val started =
                  if (chosen == null && !startContactApplied) {
                    facts.startContact?.let { id -> people.firstOrNull { it.id == id } }
                  } else {
                    null
                  }
              if (started != null) startContactApplied = true
              val selected = chosen ?: started
              current.copy(
                  loaded = facts.loaded,
                  blocked = facts.blocked,
                  restricted = facts.restricted,
                  people = members(facts.people, current),
                  selected = selected,
                  overBalance =
                      selected?.let { person -> facts.openBalances[person.id] } ?: Cents.ZERO,
                  step =
                      when {
                        current.step == QuickAddStep.PICKER && started != null ->
                            QuickAddStep.AMOUNT
                        else -> current.step
                      },
              )
            }
          }
    }
  }

  fun onQueryChange(value: String) {
    mutable.update { it.copy(query = value, error = null) }
  }

  fun clearQuery() = mutable.update { it.copy(query = "") }

  /** The picker's tap: the flow continues at the amount with that person's debts in view. */
  fun pick(personId: String) {
    // Choosing somebody else drops the person this flow was about to create.
    pendingContact = null
    pendingPersonId = null
    mutable.update { current ->
      val person =
          current.people.firstOrNull { it.id == personId }
              ?: current.selected?.takeIf { it.id == personId }
      current.copy(
          selected = person,
          step = if (person == null) current.step else QuickAddStep.AMOUNT,
          amount = "",
          overBalance = openBalances[personId] ?: Cents.ZERO,
          anomaly = null,
          error = null,
      )
    }
  }

  fun startNewPerson() {
    mutable.update {
      it.copy(
          step = QuickAddStep.NEW_PERSON,
          newName = it.query.trim().ifEmpty { it.newName },
          newPhone = "",
          newKind = ContactKind.CUSTOMER,
          duplicate = null,
          error = null,
      )
    }
  }

  fun cancelNew() = mutable.update {
    it.copy(step = QuickAddStep.PICKER, duplicate = null, error = null)
  }

  fun onNewName(value: String) {
    mutable.update {
      it.copy(newName = value, duplicate = duplicateFor(name = value, phone = it.newPhone))
    }
  }

  fun onNewPhone(value: String) {
    mutable.update {
      it.copy(newPhone = value, duplicate = duplicateFor(name = it.newName, phone = value))
    }
  }

  fun onNewKind(kind: ContactKind) = mutable.update { it.copy(newKind = kind) }

  /**
   * «التسجيل لـX بدلًا من ذلك»: the person already holding the phone becomes the entry's subject.
   */
  fun useDuplicate() {
    val current = mutable.value
    val person = current.people.firstOrNull { it.name == current.duplicate } ?: return
    mutable.update {
      it.copy(
          selected = person,
          step = QuickAddStep.AMOUNT,
          amount = "",
          duplicate = null,
          error = null,
      )
    }
  }

  /** «متابعة إلى المبلغ»: the Contact is held as an unqueued command and becomes the subject. */
  fun createPerson() {
    val current = mutable.value
    val name = current.newName.trim()
    if (name.isEmpty() || current.duplicate != null) return
    val scope = graph.current.value ?: return
    val origin = scope.origin() ?: return
    val phone = normalizePhone(current.newPhone)
    val contact =
        Commands.contactCreate(
            origin,
            Instant.now(),
            name,
            phone.ifEmpty { null },
            category = null,
            note = null,
            kind = current.newKind,
        )
    pendingContact = contact
    pendingPersonId = contact.entityId
    mutable.update {
      it.copy(
          step = QuickAddStep.AMOUNT,
          selected =
              QuickAddPerson(
                  id = contact.entityId,
                  name = name,
                  phone = phone,
                  kind = current.newKind,
                  net = Cents.ZERO,
                  lastActivity = null,
              ),
          payment = false,
          amount = "",
          overBalance = Cents.ZERO,
          error = null,
      )
    }
  }

  fun toDebt() = mutable.update { it.copy(payment = false, anomaly = null, error = null) }

  fun toPayment() {
    val current = mutable.value
    if (current.restricted) return
    mutable.update { it.copy(payment = true, anomaly = null, error = null) }
  }

  fun backToPicker() {
    // «تغيير الشخص» drops the person this flow was about to create; a held command is not queued.
    pendingContact = null
    pendingPersonId = null
    mutable.update {
      it.copy(step = QuickAddStep.PICKER, amount = "", anomaly = null, error = null, done = null)
    }
  }

  /** One key of the on-screen keypad: a digit, or «.» for the decimal mark. */
  fun press(key: String) {
    if (mutable.value.step != QuickAddStep.AMOUNT) return
    val next =
        when (key) {
          "." -> {
            val amount = mutable.value.amount
            when {
              amount.contains('.') -> amount
              amount.isEmpty() -> "0."
              else -> "$amount."
            }
          }
          else -> {
            val amount = mutable.value.amount
            val dot = amount.indexOf('.')
            when {
              dot >= 0 && amount.length - dot > 2 -> amount
              dot < 0 && amount.trimStart('0').length >= MAX_INTEGER_DIGITS -> amount
              amount == "0" -> key
              else -> amount + key
            }
          }
        }
    if (next == mutable.value.amount) return
    saved[AMOUNT] = next
    anomalyApproved = null
    mutable.update { it.copy(amount = next, anomaly = null, error = null) }
  }

  fun delete() {
    if (mutable.value.step != QuickAddStep.AMOUNT) return
    val next = mutable.value.amount.dropLast(1)
    saved[AMOUNT] = next
    anomalyApproved = null
    mutable.update { it.copy(amount = next, anomaly = null, error = null) }
  }

  fun onMethodChange(method: PaymentMethod) {
    saved[METHOD] = method.name
    mutable.update { it.copy(method = method) }
  }

  fun onNoteChange(value: String) {
    saved[NOTE] = value
    mutable.update { it.copy(note = value) }
  }

  fun onDueDateChange(value: String?) {
    saved[DUE] = value
    mutable.update { it.copy(dueDate = value) }
  }

  /**
   * The save button is the review: it either asks about an unusually large amount or records the
   * sentence and holds the commands for the undo window.
   */
  fun save(context: Context) {
    val current = mutable.value
    val amount = parseCents(current.amount)
    if (!current.canSave || amount == null) return
    if (amount >= ANOMALY_LIMIT && anomalyApproved != current.amount) {
      mutable.update { it.copy(anomaly = amount) }
      return
    }
    val commands = commandsFor(current, amount)
    if (commands.isEmpty()) {
      mutable.update { it.copy(error = context.getString(R.string.quickadd_failed)) }
      return
    }
    val sentence = sentence(context)
    // The window belongs to the store, not the screen: leaving (back, home, the lock) during it
    // must still end in the commands the done step promised.
    val store = graph.current.value
    if (store == null) {
      mutable.update { it.copy(error = context.getString(R.string.quickadd_failed)) }
      return
    }
    pending = commands
    // The held Contact stays until the commands are committed: «تراجع» returns to the amount with
    // the same person, and saving again must carry the contact.create with its debt again.
    commitJob?.cancel()
    // One tick a second (the window split evenly, so a short test window still ends), counting
    // «تراجع · N ث» down to zero, where the entry is queued and the button goes grey.
    val ticks = maxOf(1, (undoWindowMs / 1_000).toInt())
    val tick = undoWindowMs / ticks
    commitJob =
        store.jobs.launch {
          var left = ticks
          while (left > 1) {
            delay(tick)
            left--
            mutable.update { it.copy(done = it.done?.copy(secondsLeft = left)) }
          }
          delay(tick)
          commit(context)
        }
    mutable.update {
      it.copy(
          step = QuickAddStep.DONE,
          anomaly = null,
          error = null,
          done =
              QuickAddDone(
                  name = current.personName,
                  sentence = sentence,
                  net = netAfter(current, amount),
                  secondsLeft = ticks,
              ),
      )
    }
  }

  fun confirmAnomaly() {
    val current = mutable.value
    if (current.anomaly == null) return
    anomalyApproved = current.amount
    mutable.update { it.copy(anomaly = null) }
  }

  fun dismissAnomaly() = mutable.update { it.copy(anomaly = null) }

  /** «تراجع»: the held commands are dropped, nothing was ever queued. */
  fun undo(context: Context) {
    // Past the window the entry is already queued: there is nothing left to take back.
    if (pending.isEmpty()) return
    commitJob?.cancel()
    commitJob = null
    pending = emptyList()
    mutable.update {
      it.copy(
          step = QuickAddStep.AMOUNT,
          done = null,
          error = null,
      )
    }
  }

  /** «تسجيل آخر»: the entry that is on screen is recorded first, then the picker starts empty. */
  fun another(context: Context) {
    if (pending.isNotEmpty()) {
      commitJob?.cancel()
      commitJob = null
      commit(context)
    }
    pendingContact = null
    pendingPersonId = null
    anomalyApproved = null
    saved[AMOUNT] = ""
    saved[NOTE] = ""
    saved[DUE] = null
    mutable.update {
      QuickAddState(
          step = QuickAddStep.PICKER,
          loaded = it.loaded,
          blocked = it.blocked,
          restricted = it.restricted,
          people = it.people,
      )
    }
  }

  /**
   * The user left the done step: the entry is theirs, so it is queued now instead of waiting out
   * the window. Called by every way out of the screen, so a closed flow never loses a record.
   */
  fun leave(context: Context) {
    if (pending.isNotEmpty()) {
      commitJob?.cancel()
      commitJob = null
      commit(context)
    }
  }

  /** The sentence the save button states and the done step repeats. */
  fun sentence(context: Context): String {
    val current = mutable.value
    val name = current.personName
    val amount = amountLabel(current.amount)
    val supplier = current.selected?.kind == ContactKind.SUPPLIER
    val template =
        when {
          supplier && !current.payment -> R.string.quickadd_sentence_supplier_debt
          supplier -> R.string.quickadd_sentence_supplier_payment
          current.payment -> R.string.quickadd_sentence_payment
          else -> R.string.quickadd_sentence_debt
        }
    return context.getString(template, amount, name)
  }

  /** The line under the amount that states what the entry does to the balance. */
  fun preview(context: Context): String {
    val current = mutable.value
    val amount = parseCents(current.amount) ?: Cents.ZERO
    if (amount <= Cents.ZERO) return context.getString(R.string.quickadd_preview_empty)
    if (current.payment && amount > current.overBalance) {
      return context.getString(R.string.quickadd_preview_over, money(current.overBalance))
    }
    val words =
        previewWords(
            before = current.selected?.net ?: Cents.ZERO,
            amount = amount,
            payment = current.payment,
            supplier = current.selected?.kind == ContactKind.SUPPLIER,
        )
    val line =
        if (words.deduct != null) {
          context.getString(
              R.string.quickadd_preview_deduct,
              money(words.deduct),
              after(context, words.after),
          )
        } else {
          after(context, words.after)
        }
    return if (!current.payment && amount >= ANOMALY_LIMIT) {
      context.getString(R.string.quickadd_preview_large, line)
    } else {
      line
    }
  }

  /** «يصبح مسددًا» / «يصبح عليه ₪ 85.00» / «يصبح له ₪ 85.00». */
  private fun after(context: Context, after: Cents): String =
      when {
        after == Cents.ZERO -> context.getString(R.string.quickadd_preview_settled)
        after > Cents.ZERO -> context.getString(R.string.quickadd_preview_owes, money(after))
        else -> context.getString(R.string.quickadd_preview_credit, money(Cents(-after.value)))
      }

  private fun money(value: Cents): String {
    val shekels = value.value / 100
    val agorot = Math.abs(value.value % 100)
    val grouped = shekels.toString().reversed().chunked(3).joinToString(",").reversed()
    return "$grouped.${agorot.toString().padStart(2, '0')}"
  }

  private fun netAfter(current: QuickAddState, amount: Cents): Cents =
      previewWords(
              before = current.selected?.net ?: Cents.ZERO,
              amount = amount,
              payment = current.payment,
              supplier = current.selected?.kind == ContactKind.SUPPLIER,
          )
          .after

  /**
   * The commands one save will queue, built from the view the previews came from. The mode on
   * screen decides what is recorded; the held Contact is only ever the parent of the entry, never
   * its kind, and a child names that parent in `dependsOn`.
   */
  private fun commandsFor(current: QuickAddState, amount: Cents): List<LedgerCommand> {
    val scope = graph.current.value ?: return emptyList()
    val origin = scope.origin() ?: return emptyList()
    val person = current.selected ?: return emptyList()
    val at = Instant.now()
    val note = current.note.trim().ifEmpty { null }
    // The Contact this flow is still holding, and only while it is the person on screen.
    val held = pendingContact?.takeIf { it.entityId == person.id }
    if (current.payment) {
      // A Contact created in this flow has no Debts yet, so there is nothing to pay.
      if (held != null) return emptyList()
      val view = currentView ?: return emptyList()
      val contact = view.contacts.firstOrNull { it.id == person.id } ?: return emptyList()
      return PaymentAllocation.allocate(view, person.id, amount, contact.kind.debtDirection).map {
          part ->
        Commands.paymentCreate(
            origin,
            at,
            part.debtId,
            part.amount,
            current.method,
            note,
            dependsOn = if (part.debtPending) listOf(part.debtOperationId) else emptyList(),
        )
      }
    }
    // A Debt on a Contact the server has not confirmed must name the command that creates it.
    val parent =
        held?.operationId
            ?: currentView?.contacts?.firstOrNull { it.id == person.id && it.pending }?.operationId
    val debt =
        Commands.debtCreate(
            origin,
            at,
            person.id,
            person.kind.debtDirection,
            amount,
            note,
            if (person.kind == ContactKind.CUSTOMER) current.dueDate else null,
            dependsOn = listOfNotNull(parent),
        )
    return listOfNotNull(held, debt)
  }

  /** Appends the held commands: the only place this flow writes to the outbox. */
  private fun commit(context: Context) {
    val commands = pending
    pending = emptyList()
    // The held Contact is in `commands` now (or there was none).
    pendingContact = null
    pendingPersonId = null
    val store = graph.current.value
    if (commands.isEmpty() || store == null) return
    // The window is over the moment it is committed, so «تراجع» goes grey at once.
    mutable.update { it.copy(done = it.done?.copy(secondsLeft = 0)) }
    // The outbox write is not cancelled with this screen: it is one short write, not background
    // work, and the done step already told the user the record was made.
    store.jobs.launch {
      store.outbox.append(commands)
      store.onQueued()
      mutable.update { it.copy(done = it.done?.copy(secondsLeft = 0)) }
    }
  }

  private fun members(people: List<QuickAddPerson>, current: QuickAddState): List<QuickAddPerson> {
    val selected = current.selected ?: return people
    return if (people.any { it.id == selected.id }) people else people + selected
  }

  private fun duplicateFor(name: String, phone: String): String? {
    val digits = normalizePhone(phone)
    if (digits.length < MIN_PHONE_DIGITS) return null
    return mutable.value.people
        .firstOrNull { person ->
          val stored = person.phone.filter { it.isDigit() }
          stored.isNotEmpty() &&
              (stored == digits ||
                  stored.takeLast(MIN_PHONE_DIGITS) == digits.takeLast(MIN_PHONE_DIGITS))
        }
        ?.name
  }

  private fun normalizePhone(value: String): String = value.filter { it.isDigit() }

  private data class Facts(
      val loaded: Boolean = false,
      val blocked: Boolean = false,
      val restricted: Boolean = false,
      val people: List<QuickAddPerson> = emptyList(),
      val openBalances: Map<String, Cents> = emptyMap(),
      val startContact: String? = null,
  )

  private fun project(
      view: LedgerView?,
      permissions: Map<String, Boolean>,
      blocked: Boolean,
  ): Facts {
    currentView = view
    val restricted = permissions["registerPayments"] == false
    if (view == null) return Facts(blocked = blocked, restricted = restricted)
    val lastActivity = lastActivity(view)
    val people =
        view.contacts.map { contact ->
          QuickAddPerson(
              id = contact.id,
              name = contact.name,
              phone = contact.phone,
              kind = contact.kind,
              net = view.net(contact.id),
              lastActivity = lastActivity[contact.id],
          )
        }
    val open =
        view.contacts.associate { contact ->
          contact.id to PaymentAllocation.openBalance(view, contact.id, contact.kind.debtDirection)
        }
    openBalances = open
    return Facts(
        loaded = true,
        blocked = blocked,
        restricted = restricted,
        people = people,
        openBalances = open,
        startContact = contactId?.takeIf { id -> people.any { it.id == id } },
    )
  }

  /** The last time the store recorded anything with each Contact: a debt or a payment. */
  private fun lastActivity(view: LedgerView): Map<String, Instant> {
    val perContact = HashMap<String, Instant>()
    for (debt in view.debts) {
      val at = perContact[debt.contactId]
      if (at == null || debt.createdAt > at) perContact[debt.contactId] = debt.createdAt
    }
    for (payment in view.payments) {
      val contactId = view.debts.firstOrNull { it.id == payment.debtId }?.contactId ?: continue
      val at = perContact[contactId]
      if (at == null || payment.createdAt > at) perContact[contactId] = payment.createdAt
    }
    return perContact
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

  /** Whether this device may record right now: online, or holding an offline lease. */
  private fun availability(): Flow<Boolean> =
      graph.current.flatMapLatest { scope ->
        if (scope == null) {
          flowOf(true)
        } else {
          graph.accounts.accounts.map {
            val active =
                it.firstOrNull { account -> account.storeUserId == scope.account.storeUserId }
                    ?.installation
                    ?.status
            val approved = active == null || active == "active"
            !approved || (!scope.isOnline() && !scope.offlineAllowed())
          }
        }
      }

  private fun methodOf(name: String): PaymentMethod? =
      PaymentMethod.entries.firstOrNull { it.name == name }

  companion object {
    /** The window «تراجع» offers, in milliseconds. */
    const val UNDO_WINDOW_MS = 8_000L

    private const val MAX_INTEGER_DIGITS = 7
    private const val MIN_PHONE_DIGITS = 9

    private const val AMOUNT = "quickadd:amount"
    private const val METHOD = "quickadd:method"
    private const val NOTE = "quickadd:note"
    private const val DUE = "quickadd:due-date"
  }
}

/** «من هذا المبلغ فصاعدًا يسأل التطبيق قبل الحفظ» — the old forms' rule, kept in one place. */
val ANOMALY_LIMIT: Cents = Cents(500_000)

/**
 * What the preview line says about an entry, before it is worded: a customer debt written on
 * somebody who is already in credit eats that credit first, so the line names the part deducted and
 * then the balance left. Supplier debts and payments never deduct.
 */
internal data class PreviewWords(val deduct: Cents?, val after: Cents)

internal fun previewWords(
    before: Cents,
    amount: Cents,
    payment: Boolean,
    supplier: Boolean,
): PreviewWords {
  val sign = if (supplier) (if (payment) 1 else -1) else (if (payment) -1 else 1)
  val after = if (sign > 0) before + amount else before - amount
  val deduct =
      if (!supplier && !payment && before < Cents.ZERO) {
        Cents(minOf(-before.value, amount.value))
      } else {
        null
      }
  return PreviewWords(deduct, after)
}

/** The digits of an amount with grouping and no trailing zeros: «85», «4,999.99». */
internal fun amountLabel(amount: String): String {
  if (amount.isEmpty()) return "0"
  val dot = amount.indexOf('.')
  val integer = if (dot < 0) amount else amount.substring(0, dot)
  val fraction = if (dot < 0) "" else amount.substring(dot + 1).trimEnd('0')
  val grouped = integer.reversed().chunked(3).joinToString(",").reversed().ifEmpty { "0" }
  return if (fraction.isEmpty()) grouped else "$grouped.$fraction"
}
