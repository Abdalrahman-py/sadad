package com.sadad.app.feature.contactpicker

import com.sadad.ledger.ContactView

/** One contact the system picker returned: the name and its raw number. */
data class DeviceContact(val name: String, val numbers: List<String>)

/** The store's own people a pick is compared against. */
data class SadadPeople(val active: List<ContactView>, val archived: List<ContactView>)

/**
 * What picking a device contact decides; ticket 137 reuses all three cases. Serializable so it can
 * travel the navigation back stack's saved state.
 */
sealed interface PickedContact : java.io.Serializable {
  /**
   * A person the store does not have: the name, the 10 digits or null (the number was not a valid
   * mobile), and the WhatsApp code the number carried or `970`.
   */
  data class New(val name: String, val phone: String?, val code: String) : PickedContact

  /** The phone number already belongs to a person the store keeps. */
  data class Existing(val contactId: String) : PickedContact

  /** The phone number belongs to someone in the store's archive. */
  data class Archived(val contactId: String) : PickedContact
}
