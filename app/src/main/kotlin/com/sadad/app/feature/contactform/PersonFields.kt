package com.sadad.app.feature.contactform

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.sadad.app.R
import com.sadad.app.ui.components.SadadChoiceField
import com.sadad.app.ui.components.SadadField
import com.sadad.app.ui.components.SadadSegments
import com.sadad.ledger.ContactKind

/** A new person needs exactly these: the name, the phone with its code chip, and زبون/مورد. */
@Composable
internal fun PersonFields(
    state: ContactFormState,
    onNameChange: (String) -> Unit,
    onPrefixChange: (String) -> Unit,
    onPhoneChange: (String) -> Unit,
    onKindChange: (ContactKind) -> Unit,
) {
  val nameFocus = remember { FocusRequester() }
  val phoneFocus = remember { FocusRequester() }
  val nameView = remember { BringIntoViewRequester() }
  val phoneView = remember { BringIntoViewRequester() }
  LaunchedEffect(state.validationAttempt, state.duplicate) {
    if (state.validationAttempt == 0) return@LaunchedEffect
    val target =
        when {
          state.nameError != null -> nameFocus to nameView
          state.phoneError != null -> phoneFocus to phoneView
          else -> return@LaunchedEffect
        }
    // A duplicate dialog owns focus until dismissed; its phone field is already scrolled into view.
    if (state.duplicate == null) target.first.requestFocus()
    target.second.bringIntoView()
  }
  SadadField(
      value = state.name,
      onValueChange = onNameChange,
      label = stringResource(R.string.contactform_name),
      placeholder = stringResource(R.string.contactform_name_hint),
      singleLine = true,
      enabled = !state.busy,
      isError = state.nameError != null,
      supportingText = state.nameError,
      modifier = Modifier.fillMaxWidth().bringIntoViewRequester(nameView),
      fieldModifier = Modifier.fillMaxWidth().focusRequester(nameFocus),
  )
  SadadChoiceField(
      label = stringResource(R.string.contactform_prefix),
      options =
          listOf(
              "970" to stringResource(R.string.contactform_prefix_970),
              "972" to stringResource(R.string.contactform_prefix_972),
          ),
      selected = state.prefix,
      onSelect = onPrefixChange,
      enabled = !state.busy,
  )
  SadadField(
      value = state.phone,
      onValueChange = onPhoneChange,
      label = stringResource(R.string.contactform_phone),
      placeholder = stringResource(R.string.contactform_phone_hint),
      singleLine = true,
      enabled = !state.busy,
      isError = state.phoneError != null,
      supportingText = state.phoneError,
      modifier = Modifier.fillMaxWidth().bringIntoViewRequester(phoneView),
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
      fieldModifier = Modifier.fillMaxWidth().focusRequester(phoneFocus),
  )
  // A customer or a supplier, whether the person is new or being edited.
  SadadSegments(
      options =
          listOf(
              ContactKind.CUSTOMER to stringResource(R.string.contactform_kind_customer),
              ContactKind.SUPPLIER to stringResource(R.string.contactform_kind_supplier),
          ),
      selected = state.kind,
      onSelect = onKindChange,
  )
}
