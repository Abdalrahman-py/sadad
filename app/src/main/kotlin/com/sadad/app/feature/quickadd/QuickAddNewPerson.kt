package com.sadad.app.feature.quickadd

import android.content.Context
import com.sadad.app.R
import com.sadad.app.data.ledger.WhatsAppPhone
import com.sadad.app.feature.contactform.ContactFormState
import com.sadad.app.feature.contactform.DuplicateContact
import com.sadad.ledger.ContactView

/**
 * What the new-person step decided about its three fields: the person to record for, or the same
 * fields with the reason they cannot be saved yet. A phone that belongs to someone already in the
 * store names them, so the step can offer their record instead.
 */
internal sealed interface NewPersonCheck {
  data class Valid(val name: String, val phone: String?) : NewPersonCheck

  data class Invalid(val fields: ContactFormState) : NewPersonCheck
}

/**
 * The contact form's own rules (name 1–120, `WhatsAppPhone.parse`, code match, duplicate phone).
 */
internal fun checkNewPerson(
    fields: ContactFormState,
    contacts: List<ContactView>,
    context: Context,
): NewPersonCheck {
  val cleared =
      fields.copy(
          validationAttempt = fields.validationAttempt + 1,
          nameError = null,
          phoneError = null,
          duplicate = null,
      )
  val name = cleared.name.trim()
  if (name.isEmpty() || name.codePointCount(0, name.length) > MAX_NAME) {
    return NewPersonCheck.Invalid(
        cleared.copy(nameError = context.getString(R.string.contactform_name_error))
    )
  }
  val raw = cleared.phone.trim()
  if (raw.isEmpty()) return NewPersonCheck.Valid(name, null)
  val parsed = WhatsAppPhone.parse(raw)
  val error =
      when {
        parsed == null -> R.string.contactform_phone_invalid
        parsed.code != null && parsed.code != cleared.prefix ->
            R.string.contactform_phone_prefix_mismatch
        else -> null
      }
  if (error != null) {
    return NewPersonCheck.Invalid(cleared.copy(phoneError = context.getString(error)))
  }
  val phone = checkNotNull(parsed).phone
  val duplicate = contacts.firstOrNull { it.phone == phone }
  if (duplicate != null) {
    return NewPersonCheck.Invalid(
        cleared.copy(
            duplicate = DuplicateContact(duplicate.id, duplicate.name),
            phoneError = context.getString(R.string.contactform_duplicate_message, duplicate.name),
        )
    )
  }
  return NewPersonCheck.Valid(name, phone)
}

/** The longest name the server takes, in code points. */
private const val MAX_NAME = 120
