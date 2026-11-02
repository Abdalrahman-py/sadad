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
    /** The device read is running; the screen shows a spinner, not an empty list. */
    val loading: Boolean = false,
    /**
     * What picking decided, until the screen hands it to its caller. Held in the state, not in a
     * callback: the view model outlives an Activity recreation, and a callback would point at the
     * gone controller.
     */
    val picked: PickedContact? = null,
)

/**
 * The mapping from the device's contacts and the store's own people to the rows the list shows.
 * Numbers are read through `WhatsAppPhone.parse` only; matching compares the 10 digits against the
 * active people first, then the archived ones. Search folds Arabic the way the quick-add list does.
 */
internal object ContactPickerRows {
  /**
   * Every contact with a number; parsing and statuses happen here, once per read or people change.
   */
  fun build(device: List<DeviceContact>, people: SadadPeople): List<ContactRow> {
    val active = people.active.associateBy(ContactView::phone, ContactView::id)
    val archived = people.archived.associateBy(ContactView::phone, ContactView::id)
    return device
        .mapNotNull { contact ->
          val numbers = contact.numbers.filter(String::isNotBlank)
          if (numbers.isEmpty()) return@mapNotNull null
          ContactRow(
              contact.name,
              numbers.mapIndexed { _, raw ->
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
        .sortedBy { PersonSearch.normalize(it.name) }
  }

  /**
   * The rows the query leaves in: the Arabic-folded name, or the query's digits inside any raw
   * number's digits, so a landline is findable by its digits too.
   */
  fun search(rows: List<ContactRow>, query: String): List<ContactRow> {
    val folded = PersonSearch.normalize(query)
    val digits = folded.filter(Char::isDigit)
    if (folded.isEmpty()) return rows
    return rows.filter { row ->
      PersonSearch.normalize(row.name).contains(folded) ||
          (digits.length >= DIGITS_TO_MATCH &&
              row.numbers.any {
                PersonSearch.normalize(it.raw).filter(Char::isDigit).contains(digits)
              })
    }
  }

  /** A query shorter than this in digits is not a phone search, or every number matches. */
  private const val DIGITS_TO_MATCH = 2
}

/**
 * The contacts list the form's «من جهات الاتصال» button opens. The device's contacts are read once
 * permission is granted, and picking one sets [ContactPickerState.picked] — the screen hands it to
 * its caller. Nothing here is saved, uploaded or logged.
 */
class ContactPickerViewModel(
    private val device: DeviceContacts,
    people: Flow<SadadPeople>,
    /** Whether the permission is granted before this picker opens; the screen checks it. */
    private val grantedNow: () -> Boolean,
) : ViewModel() {
  private val mutable = MutableStateFlow(ContactPickerState())
  val state: StateFlow<ContactPickerState> = mutable.asStateFlow()

  /** The last device list read; empty until the permission is granted. */
  private var loaded: List<DeviceContact> = emptyList()
  private var sadad = SadadPeople(emptyList(), emptyList())

  /** The parsed rows of [loaded] with their statuses; the query only filters these. */
  private var parsedRows: List<ContactRow> = emptyList()

  init {
    viewModelScope.launch {
      if (grantedNow()) {
        mutable.update { it.copy(permission = ContactPermission.Granted) }
        read()
      }
      people.collect { next ->
        sadad = next
        parse()
      }
    }
  }

  fun onQueryChange(value: String) {
    // The rows are parsed already; a keystroke only filters them.
    mutable.update { it.copy(query = value) }
    refreshRows()
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
    // Held in the state for the Screen; the screen consumes it into its caller's callback.
    mutable.update { it.copy(picked = result) }
  }

  /** The screen has handed the pick to its caller; it is a one-shot value. */
  fun pickDelivered() = mutable.update { it.copy(picked = null) }

  private fun read() = viewModelScope.launch {
    mutable.update { it.copy(loading = true) }
    loaded = device.list()
    mutable.update { it.copy(loading = false) }
    parse()
  }

  /** Parses the loaded list against [sadad]; runs after each read or people change. */
  private fun parse() {
    parsedRows = ContactPickerRows.build(loaded, sadad)
    refreshRows()
  }

  /** The rows are already parsed; this only applies the query's filter. */
  private fun refreshRows() {
    if (mutable.value.permission != ContactPermission.Granted) return
    mutable.update { it.copy(rows = ContactPickerRows.search(parsedRows, it.query)) }
  }
}
