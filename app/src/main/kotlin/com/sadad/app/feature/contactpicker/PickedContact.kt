package com.sadad.app.feature.contactpicker

import com.sadad.ledger.ContactView

/** One contact of the phone, as `ContactsContract` gives it: the name and its raw numbers. */
data class DeviceContact(val name: String, val numbers: List<String>)

/**
 * Reads the device's contacts for the in-app list. Nothing here leaves the phone: the query result
 * is for display only, and no log, analytics event or crash report carries a name or a number.
 */
interface DeviceContacts {
  /** The contacts that have at least one number, in no defined order. */
  suspend fun list(): List<DeviceContact>
}

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
