package com.sadad.app.feature.contactform

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sadad.app.R
import com.sadad.app.data.ledger.LedgerRepository
import com.sadad.app.data.ledger.Refusal
import com.sadad.app.data.ledger.WhatsAppPhone
import com.sadad.app.feature.contactpicker.PickedContact
import com.sadad.ledger.ContactKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A visible Contact that already has the phone the form was given. */
data class DuplicateContact(val contactId: String, val name: String)

data class ContactFormState(
    val edit: Boolean = false,
    val validationAttempt: Int = 0,
    val name: String = "",
    /** The WhatsApp country code the chips choose: `970` or `972`. */
    val prefix: String = "970",
    val phone: String = "",
    /** The Contact's type, chosen in the full form. */
    val kind: ContactKind = ContactKind.CUSTOMER,
    val category: String = "",
    val note: String = "",
    val nameError: String? = null,
    val phoneError: String? = null,
    val duplicate: DuplicateContact? = null,
    val busy: Boolean = false,
    val message: String? = null,
    /** Set once the Contact is queued, so the screen opens its record. */
    val savedContactId: String? = null,
    /** Whether the queued save was «حفظ وتسجيل دين» (`true`) or «حفظ فقط» (`false`). */
    val record: Boolean = false,
    /** A picked contact that already lives in the store: the screen opens its page. */
    val openContactId: String? = null,
    /** A picked contact of the archive: the screen opens the trash. */
    val openTrash: Boolean = false,
)

/**
 * The contact form's state. Every field is written to [SavedStateHandle], so a recreated ViewModel
 * shows what the user typed. Writes only leave through [LedgerRepository], after [validate].
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
  // The duplicate check reads Room, so one save runs at a time across the validation and the write.
  private var validating = false
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
          )
      )
  val state: StateFlow<ContactFormState> = mutable.asStateFlow()

  // Saved, not just a field: otherwise a recreated edit form would overwrite what the user typed.
  private var prefilled = saved.get<Boolean>(PREFILLED) ?: (contactId == null)

  init {
    // The edit form waits for the Contact, then copies it into the fields and the saved state once.
    viewModelScope.launch {
      repository.view.collect { projected ->
        val editing = contactId
        if (!prefilled && editing != null && projected != null) {
          val contact = projected.contacts.firstOrNull { it.id == editing }
          if (contact != null) {
            prefilled = true
            saved[PREFILLED] = true
            val prefix = contact.whatsappCode
            val number = contact.phone
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

  fun dismissDuplicate() = mutable.update { it.copy(duplicate = null) }

  /**
   * What the contacts picker handed back: a new person fills name, phone and code; a person the
   * store already keeps, or one of the archive, is routed by the screen's own outcome state.
   */
  fun onPicked(picked: PickedContact) {
    when (picked) {
      is PickedContact.New -> {
        edit(NAME, picked.name) { copy(name = picked.name, nameError = null) }
        edit(PREFIX, picked.code) { copy(prefix = picked.code, phoneError = null) }
        edit(PHONE, picked.phone.orEmpty()) {
          copy(phone = picked.phone.orEmpty(), phoneError = null, duplicate = null)
        }
      }
      is PickedContact.Existing ->
          mutable.update { it.copy(openContactId = picked.contactId, openTrash = false) }
      is PickedContact.Archived ->
          mutable.update { it.copy(openTrash = true, openContactId = null) }
    }
  }

  /** The screen has opened the picked person's page or the trash. */
  fun onPageHandled() = mutable.update { it.copy(openContactId = null, openTrash = false) }

  /** Edit mode: validates and queues the Contact update with no review in between. */
  fun save() = persist(record = false)

  /** Add mode: «حفظ وتسجيل دين» queues the Contact as it is and ends on the amount screen. */
  fun saveAndRecord() = persist(record = true)

  /** Add mode: «حفظ فقط» queues the Contact as it is and ends on their page. */
  fun saveOnly() = persist(record = false)

  /** Validates, then queues the Contact (with no debt; the debt comes from QuickAdd). */
  private fun persist(record: Boolean) {
    if (mutable.value.busy || validating) return
    // The duplicate check reads Room off the UI thread, so the whole save runs in one coroutine.
    validating = true
    viewModelScope.launch {
      try {
        val draft = validate() ?: return@launch
        mutable.update { it.copy(busy = true, message = null) }
        val editing = contactId
        val outcome: Result<String> =
            if (editing == null) {
              repository.addContact(
                  draft.name,
                  draft.phone,
                  draft.category,
                  draft.note,
                  null,
                  kind = draft.kind,
                  whatsappCode = draft.whatsappCode,
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
                      draft.whatsappCode,
                  )
                  .map { editing }
            }
        mutable.update { current ->
          outcome.fold(
              onSuccess = { current.copy(busy = false, savedContactId = it, record = record) },
              onFailure = { current.copy(busy = false, message = refusalText(it)) },
          )
        }
      } finally {
        validating = false
      }
    }
  }

  private suspend fun validate(): Draft? {
    mutable.update {
      it.copy(
          validationAttempt = it.validationAttempt + 1,
          nameError = null,
          phoneError = null,
          duplicate = null,
      )
    }
    val current = mutable.value
    val name = current.name.trim()
    if (name.isEmpty() || name.codePointCount(0, name.length) > MAX_NAME) {
      mutable.update { it.copy(nameError = appContext.getString(R.string.contactform_name_error)) }
      return null
    }
    val rawPhone = current.phone.trim()
    val phone =
        if (rawPhone.isEmpty()) null
        else {
          val parsed = WhatsAppPhone.parse(rawPhone)
          val error =
              when {
                parsed == null -> R.string.contactform_phone_invalid
                parsed.code != null && parsed.code != current.prefix ->
                    R.string.contactform_phone_prefix_mismatch
                else -> null
              }
          if (error != null) {
            mutable.update { it.copy(phoneError = appContext.getString(error)) }
            return null
          }
          checkNotNull(parsed).phone
        }
    val duplicate = phone?.let { value -> repository.duplicateContact(value, contactId) }
    if (duplicate != null) {
      mutable.update {
        it.copy(
            duplicate = DuplicateContact(duplicate.id, duplicate.name),
            phoneError =
                appContext.getString(R.string.contactform_duplicate_message, duplicate.name),
        )
      }
      return null
    }
    return Draft(
        name = name,
        phone = phone,
        whatsappCode = current.prefix,
        kind = current.kind,
        category =
            current.category.trim().ifEmpty {
              appContext.getString(R.string.contactform_default_category)
            },
        note = current.note.trim().ifEmpty { null },
    )
  }

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
      val whatsappCode: String,
      val kind: ContactKind,
      val category: String,
      val note: String?,
  )

  private companion object {
    const val MAX_NAME = 120
    const val NAME = "name"
    const val PREFIX = "prefix"
    const val PHONE = "phone"
    const val KIND = "kind"
    const val CATEGORY = "category"
    const val NOTE = "note"
    const val PREFILLED = "prefilled"
  }
}
