package com.sadad.app.feature.contactpicker

import android.content.ContentResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The device's contacts source for the contact picker: one `ContactsContract` query. */
fun contactSource(resolver: ContentResolver): DeviceContacts =
    object : DeviceContacts {
      override suspend fun list(): List<DeviceContact> =
          withContext(Dispatchers.IO) { readDeviceContacts(resolver) }
    }

/**
 * A contact in the phone's list: its name and the numbers it carries, raw. The query result stays
 * on the phone; nothing here is logged or uploaded.
 */
private fun readDeviceContacts(resolver: ContentResolver): List<DeviceContact> =
    try {
      resolver
          .query(
              android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
              arrayOf(
                  android.provider.ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                  android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
                  android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER,
              ),
              null,
              null,
              null,
          )
          ?.use { cursor ->
            val id =
                cursor.getColumnIndexOrThrow(
                    android.provider.ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                )
            val name =
                cursor.getColumnIndexOrThrow(
                    android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
                )
            val number =
                cursor.getColumnIndexOrThrow(
                    android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER
                )
            val byId = LinkedHashMap<Long, Pair<String, MutableList<String>>>()
            while (cursor.moveToNext()) {
              val digits = cursor.getString(number)?.trim().orEmpty()
              if (digits.isBlank()) continue
              val contactId = cursor.getLong(id)
              val existing = byId[contactId]
              if (existing == null) {
                byId[contactId] = cursor.getString(name).orEmpty() to mutableListOf(digits)
              } else {
                existing.second.add(digits)
              }
            }
            byId.map { (_, contact) -> DeviceContact(contact.first, contact.second) }
          } ?: emptyList()
    } catch (_: SecurityException) {
      // The permission was revoked during the read; an empty list, and no personal data anywhere.
      emptyList()
    }
