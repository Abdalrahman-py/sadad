package com.sadad.app.feature.quickadd

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sadad.app.R
import com.sadad.app.data.db.ContactSummaryEntity
import com.sadad.app.data.ledger.traced
import com.sadad.app.feature.contactform.ContactFormState
import com.sadad.app.feature.contactpicker.ContactNumberRow
import com.sadad.app.feature.contactpicker.ContactPermission
import com.sadad.app.feature.contactpicker.ContactPickerRows
import com.sadad.app.feature.contactpicker.ContactRow
import com.sadad.app.feature.contactpicker.DeviceContact
import com.sadad.app.feature.contactpicker.NumberStatus
import com.sadad.app.feature.contactpicker.SadadPeople
import com.sadad.app.ui.components.formatAmount
import com.sadad.app.ui.components.parseCents
import com.sadad.ledger.Cents
import com.sadad.ledger.Commands
import com.sadad.ledger.ContactKind
import com.sadad.ledger.ContactView
import com.sadad.ledger.CreditAllocation
import com.sadad.ledger.DebtOrigin
import com.sadad.ledger.Direction
import com.sadad.ledger.LedgerCommand
import com.sadad.ledger.LedgerView
import com.sadad.ledger.PaymentAllocation
import com.sadad.ledger.PaymentMethod
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where the one recording flow stands. */
enum class QuickAddStep {
  PICKER,
  /** A person who is not in Sadad yet: name, phone, code and kind, then the amount. */
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
    /** The device was offline when it saved: the entry waits in the outbox for the network. */
    val offline: Boolean = false,
)

data class QuickAddState(
    val step: QuickAddStep = QuickAddStep.PICKER,
    val loaded: Boolean = false,
    /** The device is not approved, so it may not write. A lapsed offline lease never blocks. */
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
    /** The new person's fields; kept through the amount step, saved with the debt as one batch. */
    val newPerson: ContactFormState? = null,
    /** Whether the phone's contacts may be read; the picker asks only from its own row. */
    val contactsPermission: ContactPermission = ContactPermission.NotAsked,
    /** The phone's contacts that match the query, below the store's own people. */
    val contactRows: List<ContactRow> = emptyList(),
    /** A phone contact with several numbers, asking which one. */
    val contactChooser: ContactRow? = null,
    /** A picked number belongs to someone in the archive: the screen leaves for the trash. */
    val openTrash: Boolean = false,
) {
  /** The saved person the entry is about. */
  val personName: String
    get() = selected?.name.orEmpty()

  /**
   * Whether the amount on screen may be saved: a positive amount, and a supplier payment within the
   * debt. A customer's payment above what is open becomes credit instead of being refused.
   */
  val canSave: Boolean
    get() {
      val value = parseCents(amount) ?: return false
      if (value <= Cents.ZERO) return false
      if (blocked) return false
      if (payment && value > overBalance && selected?.kind != ContactKind.CUSTOMER) return false
      return true
    }
}

/**
 * The one flow that records anything: pick a saved person, type the amount, save with a sentence
 * that states what will be recorded, and undo for eight seconds. Nothing reaches the outbox before
 * the window ends: the commands are built here and handed to [QuickAddStore.append] on commit, so
 * «تراجع» is truly a no-op.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QuickAddViewModel(
    private val source: QuickAddSource,
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

  /** The typed query; the rows for it are found off the main thread, so typing never waits. */
  private val queries = MutableStateFlow("")

  /**
   * The chosen person's own ledger view, for the save: allocations read it, never the whole store.
   * Null until the first read for the watched person lands.
   */
  private val personView = MutableStateFlow<PersonView?>(null)

  /** The person the person view is being read for; prefetched when they are picked. */
  @Volatile private var watchedId: String? = null

  private var personJob: Job? = null

  /** A save waiting for the chosen person's first read; a new pick or save replaces it. */
  private var saveJob: Job? = null

  /** Every visible person, for the new-person checks and the phone-contacts lookup. */
  @Volatile private var allContacts: List<ContactView> = emptyList()

  /** The trash's contacts, for telling an archived phone from an importable one. */
  @Volatile private var archivedPeople: List<ContactView> = emptyList()

  /**
   * Each Contact's open receivable total, kept so a pick can show it without waiting for a re-emit.
   */
  @Volatile private var openBalances: Map<String, Cents> = emptyMap()

  /** Everyone the store holds, before the picker's query narrows it. */
  @Volatile private var allPeople: List<QuickAddPerson> = emptyList()

  /** The phone's contacts as last read, and their rows parsed against the store's people. */
  @Volatile private var deviceList: List<DeviceContact> = emptyList()
  @Volatile private var parsedRows: List<ContactRow> = emptyList()

  /** The entity id the new person's contact.create will carry. */
  private var newPersonId: String = newId()

  /** Whether the Contact this flow was opened for has been made the subject already. */
  private var startContactApplied = false

  /** The commands the done step holds; [commitJob] appends them when the window ends. */
  @Volatile private var pending: List<LedgerCommand> = emptyList()
  @Volatile private var commitJob: Job? = null
  private var anomalyApproved: String? = null

  init {
    if (source.contactsGranted()) {
      mutable.update { it.copy(contactsPermission = ContactPermission.Granted) }
      readContacts()
    }
    viewModelScope.launch {
      queries
          .mapLatest { query ->
            val searched = allPeople
            val parsed = parsedRows
            SearchResult(
                query,
                searched,
                parsed,
                PersonSearch.search(searched, query),
                ContactPickerRows.search(parsed, query),
            )
          }
          .flowOn(Dispatchers.Default)
          .collect { found ->
            mutable.update { current ->
              // A newer keystroke, or a newer store projection, already replaced this result: the
              // first search runs before the people are loaded and must not blank the list after.
              if (
                  current.query != found.query ||
                      found.searched !== allPeople ||
                      found.parsed !== parsedRows
              ) {
                current
              } else {
                current.copy(people = members(found.people, current), contactRows = found.contacts)
              }
            }
          }
    }
    viewModelScope.launch {
      combine(source.summaryReady, permissions(), availability()) { ready, permissions, blocked ->
            Triple(ready, permissions, blocked)
          }
          .flatMapLatest { (ready, permissions, blocked) ->
            if (ready) {
              combine(source.summaries, source.archived) { rows, archived ->
                projectSummaries(rows, archived, permissions, blocked)
              }
            } else {
              source.view.map { view -> projectView(view, permissions, blocked) }
            }
          }
          .flowOn(Dispatchers.Default)
          .collect { facts ->
            allPeople = facts.people
            mutable.update { current ->
              val people = facts.people
              // A new person is not in the store until the batch commits; keep them selected.
              val chosen =
                  current.selected?.let { selected -> people.firstOrNull { it.id == selected.id } }
                      ?: current.selected?.takeIf { current.newPerson != null }
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
              if (selected != null) watchPerson(selected.id)
              current.copy(
                  loaded = facts.loaded,
                  blocked = facts.blocked,
                  restricted = facts.restricted,
                  people = members(PersonSearch.search(people, current.query), current),
                  contactRows = ContactPickerRows.search(parsedRows, current.query),
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
    queries.value = value
  }

  fun clearQuery() = onQueryChange("")

  /** The picker's tap: the flow continues at the amount with that person's debts in view. */
  fun pick(personId: String) {
    mutable.update { current ->
      // Everyone, not only the query's rows: a phone contact can name someone the query missed.
      val person =
          allPeople.firstOrNull { it.id == personId }
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
    saveJob?.cancel()
    watchPerson(personId)
  }

  fun toDebt() = mutable.update { it.copy(payment = false, anomaly = null, error = null) }

  fun toPayment() {
    val current = mutable.value
    // A person created in this flow has nothing to pay yet.
    if (current.restricted || current.newPerson != null) return
    mutable.update { it.copy(payment = true, anomaly = null, error = null) }
  }

  fun backToPicker() {
    mutable.update {
      // A new person's amount goes back to their fields, not past them.
      val step = if (it.newPerson != null) QuickAddStep.NEW_PERSON else QuickAddStep.PICKER
      it.copy(step = step, amount = "", anomaly = null, error = null, done = null)
    }
  }

  /** The permission launcher's answer to «اسمح بقراءة جهات الاتصال». */
  fun onContactsPermission(granted: Boolean, canAskAgain: Boolean = true) {
    val permission =
        when {
          granted -> ContactPermission.Granted
          canAskAgain -> ContactPermission.Denied
          else -> ContactPermission.DeniedForever
        }
    mutable.update { it.copy(contactsPermission = permission) }
    if (granted) readContacts()
  }

  /** A phone contact's row: one number decides at once, several ask which. */
  fun pickPhoneContact(row: ContactRow) {
    val number = row.numbers.singleOrNull()
    if (number == null) mutable.update { it.copy(contactChooser = row) }
    else resolvePhoneContact(row, number)
  }

  fun choosePhoneNumber(number: ContactNumberRow) {
    val row = mutable.value.contactChooser ?: return
    resolvePhoneContact(row, number)
  }

  fun dismissPhoneChooser() = mutable.update { it.copy(contactChooser = null) }

  /** The screen has opened the trash for an archived match. */
  fun onTrashHandled() = mutable.update { it.copy(openTrash = false) }

  private fun resolvePhoneContact(row: ContactRow, number: ContactNumberRow) {
    mutable.update { it.copy(contactChooser = null) }
    when (val status = number.status) {
      is NumberStatus.InLedger -> pick(status.contactId)
      is NumberStatus.InArchive -> mutable.update { it.copy(openTrash = true) }
      is NumberStatus.Invalid -> startNewPerson(row.name, "", "970")
      is NumberStatus.Importable ->
          startNewPerson(row.name, checkNotNull(number.phone), number.code ?: "970")
    }
  }

  /** «إضافة «X» كشخص جديد»: the typed name, an empty phone. */
  fun addNewPerson() = startNewPerson(mutable.value.query.trim(), "", "970")

  /**
   * Opens the new-person step, unless the store is already at its person limit: the server stays
   * the authority, but a full store is told at once instead of after an offline save is refused.
   */
  private fun startNewPerson(name: String, phone: String, code: String) {
    val limit = source.contactLimit()
    if (limit != null && allContacts.size >= limit) {
      mutable.update { it.copy(error = source.refusalText("contact_limit_reached")) }
      return
    }
    // One id per new person, so saving again after «تراجع» names the same person.
    newPersonId = newId()
    mutable.update {
      it.copy(
          step = QuickAddStep.NEW_PERSON,
          payment = false,
          selected = null,
          newPerson = ContactFormState(name = name, phone = phone, prefix = code),
          error = null,
      )
    }
  }

  fun onNewPersonName(value: String) = newPerson { copy(name = value, nameError = null) }

  fun onNewPersonPrefix(value: String) = newPerson { copy(prefix = value, phoneError = null) }

  fun onNewPersonPhone(value: String) = newPerson {
    copy(phone = value, phoneError = null, duplicate = null)
  }

  fun onNewPersonKind(value: ContactKind) = newPerson { copy(kind = value) }

  private fun newPerson(change: ContactFormState.() -> ContactFormState) =
      mutable.update { current ->
        current.copy(newPerson = current.newPerson?.change())
      }

  /** «متابعة»: the fields are checked as the contact form checks them, then the amount opens. */
  fun confirmNewPerson(context: Context) {
    val current = mutable.value
    val fields = current.newPerson ?: return
    when (val check = checkNewPerson(fields, allContacts, context)) {
      is NewPersonCheck.Invalid -> mutable.update { it.copy(newPerson = check.fields) }
      is NewPersonCheck.Valid ->
          mutable.update {
            it.copy(
                step = QuickAddStep.AMOUNT,
                newPerson = fields.copy(name = check.name, phone = check.phone.orEmpty()),
                // The id the batch's contact.create will carry, so the debt and «فتح السجل» agree.
                selected =
                    QuickAddPerson(
                        id = newPersonId,
                        name = check.name,
                        phone = check.phone.orEmpty(),
                        kind = fields.kind,
                        net = Cents.ZERO,
                        lastActivity = null,
                    ),
                amount = "",
                overBalance = Cents.ZERO,
                error = null,
            )
          }
    }
  }

  /** «فتح سجل الشخص»: the typed phone is someone's already; record for them instead. */
  fun openDuplicate() {
    val id = mutable.value.newPerson?.duplicate?.contactId ?: return
    mutable.update { it.copy(newPerson = null, selected = null) }
    pick(id)
  }

  /** Back from the new-person step: the picker, with nothing kept. */
  fun leaveNewPerson() = mutable.update {
    it.copy(step = QuickAddStep.PICKER, newPerson = null, selected = null, error = null)
  }

  private fun readContacts() =
      viewModelScope.launch(Dispatchers.Default) {
        deviceList = source.contacts.list()
        reparseContacts()
        mutable.update { it.copy(contactRows = ContactPickerRows.search(parsedRows, it.query)) }
      }

  private fun reparseContacts() {
    if (deviceList.isEmpty()) return
    parsedRows = ContactPickerRows.build(deviceList, SadadPeople(allContacts, archivedPeople))
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
    // Saving is the amount step's action; the done step cannot queue the same entry again.
    if (current.step != QuickAddStep.AMOUNT) return
    val amount = parseCents(current.amount)
    if (!current.canSave || amount == null) return
    if (amount >= ANOMALY_LIMIT && anomalyApproved != current.amount) {
      mutable.update { it.copy(anomaly = amount) }
      return
    }
    // The commands read the chosen person's own view; until its first read lands, wait off the main
    // thread and save then. A person created in this flow has no view yet and needs none.
    val chosen = current.selected
    if (chosen != null && current.newPerson == null && personView.value?.id != chosen.id) {
      watchPerson(chosen.id)
      saveJob?.cancel()
      saveJob = viewModelScope.launch {
        personView.first { it?.id == chosen.id }
        // Save what was tapped: an edit made while waiting needs its own tap.
        val now = mutable.value
        if (
            now.selected?.id == chosen.id &&
                now.amount == current.amount &&
                now.payment == current.payment
        ) {
          save(context)
        }
      }
      return
    }
    val commands = commandsFor(current, amount, context.getString(R.string.quickadd_credit_note))
    if (commands.isEmpty()) {
      mutable.update { it.copy(error = context.getString(R.string.quickadd_failed)) }
      return
    }
    val sentence = sentence(context)
    // The window belongs to the store, not the screen: leaving (back, home, the lock) during it
    // must still end in the commands the done step promised.
    val store = source.open()
    if (store == null) {
      mutable.update { it.copy(error = context.getString(R.string.quickadd_failed)) }
      return
    }
    pending = commands
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
                  offline = !store.isOnline(),
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
    // The window closed and the commands are queued: this screen can no longer take them back.
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
    val amount = typedAmountLabel(current.amount)
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
    val supplier = current.selected?.kind == ContactKind.SUPPLIER
    if (current.payment && amount > current.overBalance) {
      if (supplier) {
        return context.getString(R.string.quickadd_preview_over, formatAmount(current.overBalance))
      }
      // A customer's excess is kept as credit: the statement says what settles and what is left.
      val owed = current.overBalance
      if (owed > Cents.ZERO) {
        return context.getString(
            R.string.quickadd_preview_overpay,
            formatAmount(owed),
            formatAmount(amount - owed),
        )
      }
      val all =
          previewWords(
              before = current.selected?.net ?: Cents.ZERO,
              amount = amount,
              payment = true,
              supplier = false,
          )
      return context.getString(R.string.quickadd_preview_overpay_all, after(context, all.after))
    }
    val words =
        previewWords(
            before = current.selected?.net ?: Cents.ZERO,
            amount = amount,
            payment = current.payment,
            supplier = supplier,
        )
    val line =
        if (words.deduct != null) {
          val left = words.after
          if (left < Cents.ZERO) {
            context.getString(
                R.string.quickadd_preview_deduct_left,
                formatAmount(words.deduct),
                formatAmount(Cents(-left.value)),
            )
          } else {
            context.getString(
                R.string.quickadd_preview_deduct,
                formatAmount(words.deduct),
                after(context, left),
            )
          }
        } else {
          after(context, words.after)
        }
    return if (!current.payment && amount >= ANOMALY_LIMIT) {
      context.getString(R.string.quickadd_preview_large, line)
    } else {
      line
    }
  }

  /** «يصبح مسددًا» / «يصبح عليه ₪ 85» / «يصبح له ₪ 85». */
  private fun after(context: Context, after: Cents): String =
      when {
        after == Cents.ZERO -> context.getString(R.string.quickadd_preview_settled)
        after > Cents.ZERO -> context.getString(R.string.quickadd_preview_owes, formatAmount(after))
        else ->
            context.getString(R.string.quickadd_preview_credit, formatAmount(Cents(-after.value)))
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
   * screen decides what is recorded; a debt names its pending Contact parent in `dependsOn`.
   *
   * A customer's payment above what is open records the excess as a payable Debt that marks itself
   * `overpayment` (the credit), and a new Debt on a person holding credit is settled from it with
   * one `debt.offset` per credit Debt, oldest first — the compound the ticket asks for.
   */
  private fun commandsFor(
      current: QuickAddState,
      amount: Cents,
      creditNote: String,
  ): List<LedgerCommand> {
    val origin = source.open()?.origin() ?: return emptyList()
    val person = current.selected ?: return emptyList()
    val at = Instant.now()
    val note = current.note.trim().ifEmpty { null }
    if (current.payment) {
      val view = personViewOf(person.id) ?: return emptyList()
      val contact = view.contacts.firstOrNull { it.id == person.id } ?: return emptyList()
      val direction = contact.kind.debtDirection
      val allocation =
          if (contact.kind == ContactKind.CUSTOMER) {
            PaymentAllocation.allocateWithCredit(view, person.id, amount, direction)
          } else {
            // Only a customer keeps credit; a supplier payment above the balance is refused.
            CreditAllocation(
                PaymentAllocation.allocate(view, person.id, amount, direction),
                Cents.ZERO,
            )
          }
      val payments =
          allocation.parts.map { part ->
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
      if (allocation.creditCents <= Cents.ZERO) return payments
      return payments +
          Commands.debtCreate(
              origin,
              at,
              person.id,
              Direction.PAYABLE,
              allocation.creditCents,
              creditNote,
              debtOrigin = DebtOrigin.OVERPAYMENT,
          )
    }
    // A person created in this flow travels in the same batch, ahead of their first debt.
    val fields = current.newPerson
    val create = fields?.let {
      Commands.contactCreate(
          origin,
          at,
          person.name,
          phone = person.phone.ifEmpty { null },
          kind = person.kind,
          whatsappCode = it.prefix,
          entityId = person.id,
      )
    }
    // A Debt on a Contact the server has not confirmed must name the command that creates it.
    val parent =
        create?.operationId
            ?: personViewOf(person.id)
                ?.contacts
                ?.firstOrNull { it.id == person.id && it.pending }
                ?.operationId
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
    val view = personViewOf(person.id)
    val credit =
        if (view != null && person.kind == ContactKind.CUSTOMER) {
          PaymentAllocation.allocateCredit(view, person.id, amount)
        } else {
          emptyList()
        }
    val offsets = credit.map { part ->
      Commands.debtOffset(
          origin,
          at,
          receivableDebtId = debt.entityId,
          payableDebtId = part.debtId,
          amount = part.amount,
          dependsOn =
              listOfNotNull(debt.operationId) +
                  if (part.debtPending) listOf(part.debtOperationId) else emptyList(),
      )
    }
    return listOfNotNull(create) + debt + offsets
  }

  /** Appends the held commands: the only place this flow writes to the outbox. */
  private fun commit(context: Context) {
    val commands = pending
    pending = emptyList()
    val store = source.open()
    if (commands.isEmpty() || store == null) return
    // The window is over the moment it is committed, so «تراجع» goes grey at once.
    mutable.update { it.copy(done = it.done?.copy(secondsLeft = 0)) }
    // The outbox write is not cancelled with this screen: it is one short write, not background
    // work, and the done step already told the user the record was made.
    store.jobs.launch {
      traced("save_debt") { store.append(commands) }
      mutable.update { it.copy(done = it.done?.copy(secondsLeft = 0)) }
    }
  }

  private fun members(people: List<QuickAddPerson>, current: QuickAddState): List<QuickAddPerson> {
    val selected = current.selected ?: return people
    return if (people.any { it.id == selected.id }) people else people + selected
  }

  /** A search and the lists it ran over, so a result from lists since replaced can be dropped. */
  private class SearchResult(
      val query: String,
      val searched: List<QuickAddPerson>,
      val parsed: List<ContactRow>,
      val people: List<QuickAddPerson>,
      val contacts: List<ContactRow>,
  )

  private data class Facts(
      val loaded: Boolean = false,
      val blocked: Boolean = false,
      val restricted: Boolean = false,
      val people: List<QuickAddPerson> = emptyList(),
      val openBalances: Map<String, Cents> = emptyMap(),
      val startContact: String? = null,
  )

  private fun projectView(
      view: LedgerView?,
      permissions: Map<String, Boolean>,
      blocked: Boolean,
  ): Facts {
    val restricted = permissions["registerPayments"] == false
    if (view == null) return Facts(blocked = blocked, restricted = restricted)
    allContacts = view.contacts
    archivedPeople = view.archivedContacts
    reparseContacts()
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

  private fun projectSummaries(
      rows: List<ContactSummaryEntity>?,
      archived: List<ContactView>,
      permissions: Map<String, Boolean>,
      blocked: Boolean,
  ): Facts {
    val restricted = permissions["registerPayments"] == false
    if (rows == null) return Facts(blocked = blocked, restricted = restricted)
    allContacts = rows.map { it.asContactView() }
    archivedPeople = archived
    reparseContacts()
    val people = rows.map { row ->
      QuickAddPerson(
          id = row.contactId,
          name = row.name,
          phone = row.phone,
          kind = row.kind,
          net = Cents(row.net),
          lastActivity = row.lastEntryAt,
      )
    }
    val open = rows.associate { row ->
      row.contactId to
          Cents((if (row.kind == ContactKind.CUSTOMER) row.openReceivable else row.openPayable))
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

  /** The person the save is about: their own ledger view, read since the pick; null until then. */
  private fun personViewOf(id: String): LedgerView? = personView.value?.takeIf { it.id == id }?.view

  private fun watchPerson(id: String) {
    if (watchedId == id) return
    watchedId = id
    personView.value = null
    personJob?.cancel()
    personJob = viewModelScope.launch {
      source.personView(id).collect { if (watchedId == id) personView.value = PersonView(id, it) }
    }
  }

  /** One person's own ledger view, tagged with whose it is; [view] is null when there is none. */
  private data class PersonView(val id: String, val view: LedgerView?)

  /** A summary row as a Contact: only the id, name and phone the checks read are real. */
  private fun ContactSummaryEntity.asContactView(): ContactView =
      ContactView(
          id = contactId,
          name = name,
          phone = phone,
          kind = kind,
          category = category,
          note = "",
          version = 0,
          pending = pending,
          createdAt = createdAt,
          createdBy = "",
          operationId = "",
      )

  /** The last time the store recorded anything with each Contact: a debt or a payment. */
  private fun lastActivity(view: LedgerView): Map<String, Instant> {
    val perContact = HashMap<String, Instant>()
    for (debt in view.debts) {
      val at = perContact[debt.contactId]
      if (at == null || debt.createdAt > at) perContact[debt.contactId] = debt.createdAt
    }
    for (payment in view.payments) {
      val at = perContact[payment.contactId]
      if (at == null || payment.createdAt > at) perContact[payment.contactId] = payment.createdAt
    }
    return perContact
  }

  private fun permissions(): Flow<Map<String, Boolean>> = source.permissions

  private fun availability(): Flow<Boolean> = source.blocked

  private fun methodOf(name: String): PaymentMethod? =
      PaymentMethod.entries.firstOrNull { it.name == name }

  companion object {
    /** The window «تراجع» offers, in milliseconds. */
    const val UNDO_WINDOW_MS = 8_000L

    private const val MAX_INTEGER_DIGITS = 7

    private const val AMOUNT = "quickadd:amount"
    private const val METHOD = "quickadd:method"
    private const val NOTE = "quickadd:note"
    private const val DUE = "quickadd:due-date"
  }
}

private fun newId(): String = UUID.randomUUID().toString()

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

/** The amount typed so far as the screen shows it: «0» until it is positive, then «4,999.99». */
internal fun typedAmountLabel(typed: String): String = parseCents(typed)?.let(::formatAmount) ?: "0"
