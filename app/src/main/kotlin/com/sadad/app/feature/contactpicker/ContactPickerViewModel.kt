package com.sadad.app.feature.contactpicker

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sadad.app.data.ledger.WhatsAppPhone
import com.sadad.ledger.ContactView
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One phone number of one device contact, next to the store's own people. */
data class ContactNumberRow(
    /** The number as the owner wrote it on the phone. */
    val raw: String,
    /** The 10 digits when the number is a valid mobile, else null. */
    val phone: String?,
    /** The WhatsApp code the number carried when it had one, else null. */
    val code: String?,
    val status: NumberStatus,
)

sealed interface NumberStatus {
  data object Importable : NumberStatus

  /** Not a valid mobile number: the name can still be imported. */
  data object Invalid : NumberStatus

  /** The number already belongs to the store person [contactId]. */
  data class InLedger(val contactId: String) : NumberStatus

  /** The number belongs to the archived person [contactId]. */
  data class InArchive(val contactId: String) : NumberStatus
}

/** One device contact; it has at least one number. */
data class ContactRow(val name: String, val numbers: List<ContactNumberRow>)

/**
 * The mapping from a device contact and the store's own people to its row. Numbers are read through
 * `WhatsAppPhone.parse` only; matching compares the 10 digits against the active people first, then
 * the archived ones.
 */
internal object ContactPickerRows {
  fun build(device: List<DeviceContact>, people: SadadPeople): List<ContactRow> {
    val active = people.active.associateBy(ContactView::phone, ContactView::id)
    val archived = people.archived.associateBy(ContactView::phone, ContactView::id)
    return device.mapNotNull { contact ->
      val numbers = contact.numbers.filter(String::isNotBlank)
      if (numbers.isEmpty()) return@mapNotNull null
      ContactRow(
          contact.name,
          numbers.map { raw ->
            val parsed = WhatsAppPhone.parse(raw)
            val status =
                when {
                  parsed == null -> NumberStatus.Invalid
                  else ->
                      active[parsed.phone]?.let(NumberStatus::InLedger)
                          ?: archived[parsed.phone]?.let(NumberStatus::InArchive)
                          ?: NumberStatus.Importable
                }
            ContactNumberRow(raw, parsed?.phone, parsed?.code, status)
          },
      )
    }
  }

  /** What one picked number means for the store: a new person, or one it already keeps. */
  fun resolve(row: ContactRow, number: ContactNumberRow): PickedContact =
      when (val status = number.status) {
        is NumberStatus.InLedger -> PickedContact.Existing(status.contactId)
        is NumberStatus.InArchive -> PickedContact.Archived(status.contactId)
        is NumberStatus.Invalid -> PickedContact.New(row.name, null, "970")
        is NumberStatus.Importable ->
            PickedContact.New(row.name, checkNotNull(number.phone), number.code ?: "970")
      }
}

data class ContactPickerState(
    /**
     * What the pick decided, until the screen hands it to its caller. Held in the state, not in a
     * callback: the view model outlives an Activity recreation, and a callback would point at the
     * gone controller.
     */
    val picked: PickedContact? = null
)

/**
 * The form's «من جهات الاتصال»: the system picker returns one contact, and this decides what it
 * means against the store's people. Nothing here is saved, uploaded or logged.
 */
class ContactPickerViewModel(people: Flow<SadadPeople>) : ViewModel() {
  private val mutable = MutableStateFlow(ContactPickerState())
  val state: StateFlow<ContactPickerState> = mutable.asStateFlow()

  private val sadad = people.stateIn(viewModelScope, SharingStarted.Eagerly, null)

  /** The system picker's contact; waits for the store's people before deciding. */
  fun onDeviceContact(contact: DeviceContact) {
    viewModelScope.launch {
      val known = sadad.filterNotNull().first()
      val row = ContactPickerRows.build(listOf(contact), known).singleOrNull() ?: return@launch
      mutable.update { it.copy(picked = ContactPickerRows.resolve(row, row.numbers.first())) }
    }
  }

  /** The screen has handed the pick to its caller; it is a one-shot value. */
  fun pickDelivered() = mutable.update { it.copy(picked = null) }
}
