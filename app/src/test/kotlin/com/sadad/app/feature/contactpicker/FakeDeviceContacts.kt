package com.sadad.app.feature.contactpicker

/** The DeviceContacts fake the picker's tests use; reads are counted, not logged. */
open class FakeDeviceContacts(vararg stored: DeviceContact) : DeviceContacts {
  private val items: List<DeviceContact> = stored.toList()
  var reads = 0
    private set

  override suspend fun list(): List<DeviceContact> {
    reads += 1
    return items
  }
}
