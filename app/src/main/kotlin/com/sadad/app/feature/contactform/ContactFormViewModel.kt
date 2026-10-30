package com.sadad.app.feature.contactform

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sadad.app.R
import com.sadad.app.data.ledger.FirstDebt
import com.sadad.app.data.ledger.LedgerRepository
import com.sadad.app.data.ledger.Refusal
import com.sadad.app.data.ledger.WhatsAppPhone
import com.sadad.app.ui.components.parseCents
import com.sadad.ledger.Cents
import com.sadad.ledger.ContactKind
import com.sadad.ledger.LedgerView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A visible Contact that already has the phone the form was given. */
data class DuplicateContact(val contactId: String, val name: String)

/** The values the review dialog shows; the form fields stay untouched behind it. */
data class ContactReview(
    val name: String,
    val phone: String?,
    val category: String,
    val amount: Cents?,
    val dueDate: String?,
)

data class ContactFormState(
    val edit: Boolean = false,
    val name: String = "",
    val prefix: String = "970",
    val phone: String = "",
    /** The Contact's type, chosen in the full form. */
    val kind: ContactKind = ContactKind.CUSTOMER,
    val category: String = "",
    val note: String = "",
    val withFirstDebt: Boolean = false,
    val debtAmount: String = "",
    val debtDue: String? = null,
    val debtNote: String = "",
    val nameError: String? = null,
    val phoneError: String? = null,
    val debtError: String? = null,
    val duplicate: DuplicateContact? = null,
    val review: ContactReview? = null,
    val busy: Boolean = false,
    val message: String? = null,
    /** Set once the Contact is queued, so the screen opens its record. */
    val savedContactId: String? = null,
)

/**
 * The contact form's state. Every field is written to [SavedStateHandle], so a recreated ViewModel
 * shows what the user typed. Writes only leave through [LedgerRepository], after [save] and then
 * [confirm] on the review dialog.
 */
class ContactFormViewModel(
    private val repository: LedgerRepository,
    val storeUserId: String,
    context: Context,
    private val saved: SavedStateHandle,
    private val contactId: String? = null,
    initialName: String = "",
) : ViewModel() {
  private val appContext = context.applicationContext
  private val mutable =
      MutableStateFlow(
          ContactFormState(
              edit = contactId != null,
              name = saved.get<String>(NAME) ?: initialName.takeIf { contactId == null }.orEmpty(),
              prefix = saved.get<String>(PREFIX) ?: "970",
              phone = saved.get<String>(PHONE).orEmpty(),
              kind =
                  saved.get<String>(KIND)?.let { kind ->
                    runCatching { ContactKind.valueOf(kind) }.getOrNull()
                  } ?: ContactKind.CUSTOMER,
              category =
                  saved.get<String>(CATEGORY)
                      ?: appContext.getString(R.string.contactform_default_category),
              note = saved.get<String>(NOTE).orEmpty(),
              withFirstDebt = saved.get<Boolean>(WITH_DEBT) ?: false,
              debtAmount = saved.get<String>(DEBT_AMOUNT).orEmpty(),
              debtDue = saved.get<String>(DEBT_DUE),
              debtNote = saved.get<String>(DEBT_NOTE).orEmpty(),
          )
      )
  val state: StateFlow<ContactFormState> = mutable.asStateFlow()

  private var view: LedgerView? = null
  // Saved, not just a field: otherwise a recreated edit form would overwrite what the user typed.
  private var prefilled = saved.get<Boolean>(PREFILLED) ?: (contactId == null)

  init {
    // The edit form waits for the Contact, then copies it into the fields and the saved state once.
    viewModelScope.launch {
      repository.view.collect { projected ->
        view = projected
        val editing = contactId
        if (!prefilled && editing != null && projected != null) {
          val contact = projected.contacts.firstOrNull { it.id == editing }
          if (contact != null) {
            prefilled = true
            saved[PREFILLED] = true
            val (prefix, number) = localPhone(contact.phone)
            val category =
                contact.category.ifBlank {
                  appContext.getString(R.string.contactform_default_category)
                }
            saved[PREFIX] = prefix
            saved[PHONE] = number
            saved[NAME] = contact.name
            saved[KIND] = contact.kind.name
            saved[CATEGORY] = category
            saved[NOTE] = contact.note
            mutable.update {
              it.copy(
                  name = contact.name,
                  prefix = prefix,
                  phone = number,
                  kind = contact.kind,
                  category = category,
                  note = contact.note,
              )
            }
          }
        }
      }
    }
  }

  fun onNameChange(value: String) = edit(NAME, value) { copy(name = value, nameError = null) }

  fun onPrefixChange(value: String) =
      edit(PREFIX, value) { copy(prefix = value, phoneError = null) }

  fun onPhoneChange(value: String) =
      edit(PHONE, value) { copy(phone = value, phoneError = null, duplicate = null) }

  fun onKindChange(value: ContactKind) = edit(KIND, value.name) { copy(kind = value) }

  fun onCategoryChange(value: String) = edit(CATEGORY, value) { copy(category = value) }

  fun onNoteChange(value: String) = edit(NOTE, value) { copy(note = value) }

  fun onFirstDebtChange(value: Boolean) =
      edit(WITH_DEBT, value) { copy(withFirstDebt = value, debtError = null) }

  fun onDebtAmountChange(value: String) =
      edit(DEBT_AMOUNT, value) { copy(debtAmount = value, debtError = null) }

  fun onDebtDueChange(value: String?) = edit(DEBT_DUE, value) { copy(debtDue = value) }

  fun onDebtNoteChange(value: String) = edit(DEBT_NOTE, value) { copy(debtNote = value) }

  fun dismissDuplicate() = mutable.update { it.copy(duplicate = null) }

  fun dismissReview() = mutable.update { it.copy(review = null) }

  /** Validates, then shows the review dialog; nothing is queued yet. */
  fun save() {
    if (mutable.value.busy) return
    val review = validate()?.review()
    mutable.update { it.copy(review = review) }
  }

  /** Queues the reviewed Contact (and its first Debt), or shows why the ledger refused. */
  fun confirm() {
    if (mutable.value.busy) return
    val draft = validate()
    if (draft == null) {
      mutable.update { it.copy(review = null) }
      return
    }
    mutable.update { it.copy(review = null, busy = true, message = null) }
    viewModelScope.launch {
      val editing = contactId
      val outcome: Result<String> =
          if (editing == null) {
            repository.addContact(
                draft.name,
                draft.phone,
                draft.category,
                draft.note,
                draft.amount?.let { FirstDebt(it, draft.debtNote, draft.due) },
                kind = draft.kind,
            )
          } else {
            repository
                .updateContact(
                    editing,
                    draft.name,
                    draft.phone,
                    draft.category,
                    draft.note,
                    draft.kind,
                )
                .map { editing }
          }
      mutable.update { current ->
        outcome.fold(
            onSuccess = { current.copy(busy = false, savedContactId = it) },
            onFailure = { current.copy(busy = false, message = refusalText(it)) },
        )
      }
    }
  }

  private fun validate(): Draft? {
    val current = mutable.value
    val name = current.name.trim()
    if (name.isEmpty() || name.codePointCount(0, name.length) > MAX_NAME) {
      mutable.update { it.copy(nameError = appContext.getString(R.string.contactform_name_error)) }
      return null
    }
    val rawPhone = current.phone.trim()
    val phone =
        if (rawPhone.isEmpty()) null
        else
            try {
              WhatsAppPhone.normalize(rawPhone, current.prefix)
            } catch (invalid: IllegalArgumentException) {
              mutable.update { it.copy(phoneError = invalid.message) }
              return null
            }
    val duplicate = phone?.let { value ->
      view?.contacts?.firstOrNull { it.id != contactId && it.phone == value }
    }
    if (duplicate != null) {
      mutable.update {
        it.copy(duplicate = DuplicateContact(duplicate.id, duplicate.name))
      }
      return null
    }
    val amount =
        if (current.withFirstDebt) {
          parseCents(current.debtAmount)
              ?: run {
                val zero = onlyZeroDigits(current.debtAmount)
                mutable.update {
                  it.copy(
                      debtError =
                          appContext.getString(
                              if (zero) R.string.contactform_amount_zero
                              else R.string.contactform_amount_invalid
                          )
                  )
                }
                return null
              }
        } else null
    mutable.update { it.copy(nameError = null, phoneError = null, debtError = null) }
    return Draft(
        name = name,
        phone = phone,
        kind = current.kind,
        category =
            current.category.trim().ifEmpty {
              appContext.getString(R.string.contactform_default_category)
            },
        note = current.note.trim().ifEmpty { null },
        amount = amount,
        due = current.debtDue,
        debtNote = current.debtNote.trim().ifEmpty { null },
    )
  }

  private fun Draft.review() = ContactReview(name, phone, category, amount, due)

  private fun refusalText(error: Throwable): String =
      when (error) {
        is Refusal.OnlineOnly,
        is Refusal.OfflineNotAllowed -> appContext.getString(R.string.contactform_offline)
        is Refusal.Invalid -> error.message ?: appContext.getString(R.string.contactform_failed)
        else -> appContext.getString(R.string.contactform_failed)
      }

  /** Writes one field to the saved state and the visible state together. */
  private inline fun edit(
      key: String,
      value: Any?,
      crossinline change: ContactFormState.() -> ContactFormState,
  ) {
    saved[key] = value
    mutable.update { it.change() }
  }

  private data class Draft(
      val name: String,
      val phone: String?,
      val kind: ContactKind,
      val category: String,
      val note: String?,
      val amount: Cents?,
      val due: String?,
      val debtNote: String?,
  )

  private companion object {
    const val MAX_NAME = 120
    const val NAME = "name"
    const val PREFIX = "prefix"
    const val PHONE = "phone"
    const val KIND = "kind"
    const val CATEGORY = "category"
    const val NOTE = "note"
    const val WITH_DEBT = "withFirstDebt"
    const val DEBT_AMOUNT = "debtAmount"
    const val DEBT_DUE = "debtDue"
    const val DEBT_NOTE = "debtNote"
    const val PREFILLED = "prefilled"

    /** `+970599123456` back to the prefix and the local number the form edits. */
    fun localPhone(phone: String): Pair<String, String> {
      val digits = phone.removePrefix("+")
      return when {
        digits.startsWith("972") -> "972" to digits.removePrefix("972")
        digits.startsWith("970") -> "970" to digits.removePrefix("970")
        else -> "970" to phone
      }
    }

    /** An amount text that is a number but nothing but zeros, e.g. `0` or `0.00`. */
    fun onlyZeroDigits(text: String): Boolean {
      val digits = text.filter { Character.digit(it, 10) >= 0 }
      return digits.isNotEmpty() && digits.all { Character.digit(it, 10) == 0 }
    }
  }
}
