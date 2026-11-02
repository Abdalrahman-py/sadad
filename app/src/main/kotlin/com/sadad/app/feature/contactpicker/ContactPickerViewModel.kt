package com.sadad.app.feature.contactpicker

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sadad.app.R
import com.sadad.app.data.ledger.WhatsAppPhone
import com.sadad.app.feature.quickadd.PersonSearch
import com.sadad.ledger.ContactView
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The permission's four states; denied for good is the only one that can open settings. */
sealed interface ContactPermission {
  data object NotAsked : ContactPermission

  data object Granted : ContactPermission

  data object Denied : ContactPermission

  data object DeniedForever : ContactPermission
}

/** The reason a device number cannot serve WhatsApp, as one resource, or null when valid. */
internal fun NumberStatus.label(): Int? =
    when (this) {
      is NumberStatus.InLedger -> R.string.contactpicker_in_ledger
      is NumberStatus.InArchive -> R.string.contactpicker_in_archive
      is NumberStatus.Invalid -> R.string.contactpicker_invalid
      is NumberStatus.Importable -> null
    }

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

  /** Not a valid mobile number: greyed, but the name can still be imported. */
  data object Invalid : NumberStatus

  /** The number already belongs to the store person [contactId]. */
  data class InLedger(val contactId: String) : NumberStatus

  /** The number belongs to the archived person [contactId]. */
  data class InArchive(val contactId: String) : NumberStatus
}

/** One device contact shown in the list; it has at least one number. */
data class ContactRow(val name: String, val numbers: List<ContactNumberRow>)

data class ContactPickerState(
    val permission: ContactPermission = ContactPermission.NotAsked,
    val query: String = "",
    val rows: List<ContactRow> = emptyList(),
    /** The contact whose several numbers ask which one; a dialog on the screen. */
    val chooser: ContactRow? = null,
)

/**
 * The mapping from the device's contacts and the store's own people to the rows the list shows.
 * Numbers are read through `WhatsAppPhone.parse` only; matching compares the 10 digits against the
 * active people first, then the archived ones. Search folds Arabic the way the quick-add list does.
 */
internal object ContactPickerRows {
  fun build(
      device: List<DeviceContact>,
      people: SadadPeople,
      query: String,
  ): List<ContactRow> {
    val folded = PersonSearch.normalize(query)
    val digits = folded.filter(Char::isDigit)
    val active = people.active.associateBy(ContactView::phone, ContactView::id)
    val archived = people.archived.associateBy(ContactView::phone, ContactView::id)
    return device
        .mapNotNull { contact ->
          val numbers = contact.numbers.filter(String::isNotBlank)
          if (numbers.isEmpty()) return@mapNotNull null
          val rows = numbers.map { raw ->
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
          }
          val matches =
              folded.isEmpty() ||
                  PersonSearch.normalize(contact.name).contains(folded) ||
                  (digits.length >= DIGITS_TO_MATCH &&
                      rows.any { it.phone?.contains(digits) == true })
          if (matches) ContactRow(contact.name, rows) else null
        }
        .sortedBy { PersonSearch.normalize(it.name) }
  }

  /** A query shorter than this in digits is not a phone search, or every number matches. */
  private const val DIGITS_TO_MATCH = 2
}

/**
 * The contacts list the form's «من جهات الاتصال» button opens. The device's contacts are read once
 * permission is granted, and picking one reports one [PickedContact] through [onResult] — the view
 * model never saves or uploads anything.
 */
class ContactPickerViewModel(
    private val device: DeviceContacts,
    people: Flow<SadadPeople>,
    /** Whether the permission is granted before this picker opens; the screen checks it. */
    private val grantedNow: () -> Boolean,
    private val onResult: (PickedContact) -> Unit,
) : ViewModel() {
  private val mutable = MutableStateFlow(ContactPickerState())
  val state: StateFlow<ContactPickerState> = mutable.asStateFlow()

  /** The last device list read; empty until the permission is granted. */
  private var loaded: List<DeviceContact> = emptyList()
  private var sadad = SadadPeople(emptyList(), emptyList())

  init {
    viewModelScope.launch {
      if (grantedNow()) {
        mutable.update { it.copy(permission = ContactPermission.Granted) }
        read()
      }
      people.collect { next ->
        sadad = next
        refresh()
      }
    }
  }

  fun onQueryChange(value: String) {
    mutable.update { it.copy(query = value) }
    refresh()
  }

  /**
   * What the permission launcher reports. A grant reads the list; a denial reads nothing, and only
   * «denied for good» turns the settings button on.
   */
  fun onPermissionResult(granted: Boolean, canAskAgain: Boolean = true) {
    val permission =
        when {
          granted -> ContactPermission.Granted
          canAskAgain -> ContactPermission.Denied
          else -> ContactPermission.DeniedForever
        }
    mutable.update { it.copy(permission = permission) }
    if (granted) read()
  }

  /** Tapping a row: one number is picked at once, several ask which. */
  fun onPick(row: ContactRow) {
    val number = row.numbers.singleOrNull()
    if (number == null) mutable.update { it.copy(chooser = row) } else resolve(row, number)
  }

  /** Tapping one number in the open chooser. */
  fun onChoose(number: ContactNumberRow) {
    val row = mutable.value.chooser ?: return
    resolve(row, number)
  }

  fun dismissChooser() = mutable.update { it.copy(chooser = null) }

  private fun resolve(row: ContactRow, number: ContactNumberRow) {
    mutable.update { it.copy(chooser = null) }
    val result =
        when (val status = number.status) {
          is NumberStatus.InLedger -> PickedContact.Existing(status.contactId)
          is NumberStatus.InArchive -> PickedContact.Archived(status.contactId)
          is NumberStatus.Invalid -> PickedContact.New(row.name, null, "970")
          is NumberStatus.Importable ->
              PickedContact.New(row.name, checkNotNull(number.phone), number.code ?: "970")
        }
    onResult(result)
  }

  private fun read() = viewModelScope.launch {
    loaded = device.list()
    refresh()
  }

  private fun refresh() {
    if (mutable.value.permission != ContactPermission.Granted) return
    mutable.update { it.copy(rows = ContactPickerRows.build(loaded, sadad, it.query)) }
  }
}
