package com.sadad.app.feature.contactpicker

/** Tags the contact picker's tests look up on screen. */
object ContactPickerTags {
  const val SEARCH = "contactpicker:search"
  const val SETTINGS = "contactpicker:settings"

  fun row(name: String) = "contactpicker:row:$name"

  fun number(raw: String) = "contactpicker:number:$raw"
}
