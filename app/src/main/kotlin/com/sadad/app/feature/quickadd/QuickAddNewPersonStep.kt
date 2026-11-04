package com.sadad.app.feature.quickadd

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.sadad.app.R
import com.sadad.app.feature.contactform.PersonFields
import com.sadad.app.feature.contactpicker.ContactPermission
import com.sadad.app.feature.contactpicker.ContactRow
import com.sadad.app.feature.contactpicker.label
import com.sadad.app.ui.components.SadadButton
import com.sadad.app.ui.components.SadadButtonStyle
import com.sadad.app.ui.components.SadadPageHeading
import com.sadad.app.ui.theme.sadadPalette

/** Test tags of the new-person step and the picker's phone contacts. */
object QuickAddNewPersonTags {
  const val STEP = "quickadd:new-person"
  const val CONTINUE = "quickadd:new-person-continue"
  const val OPEN_RECORD = "quickadd:new-person-open-record"
  const val ALLOW_CONTACTS = "quickadd:allow-contacts"

  fun phoneContact(index: Int) = "quickadd:phone-contact-$index"
}

/**
 * A person who is not in Sadad yet: the contact form's three fields, then the amount. A phone that
 * someone in the store already has offers their record instead.
 */
@Composable
internal fun NewPersonStep(state: QuickAddState, model: QuickAddViewModel) {
  val fields = state.newPerson ?: return
  val context = LocalContext.current
  Column(
      Modifier.fillMaxSize().padding(16.dp).testTag(QuickAddNewPersonTags.STEP),
      verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    SadadPageHeading(
        title = stringResource(R.string.quickadd_new_person_title),
        onBack = model::leaveNewPerson,
    )
    Column(
        Modifier.weight(1f).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      PersonFields(
          fields,
          model::onNewPersonName,
          model::onNewPersonPrefix,
          model::onNewPersonPhone,
          model::onNewPersonKind,
      )
      if (fields.duplicate != null) {
        SadadButton(
            text = stringResource(R.string.quickadd_open_record),
            style = SadadButtonStyle.SURFACE,
            onClick = model::openDuplicate,
            modifier = Modifier.fillMaxWidth().testTag(QuickAddNewPersonTags.OPEN_RECORD),
        )
      }
    }
    SadadButton(
        text = stringResource(R.string.quickadd_new_person_continue),
        onClick = { model.confirmNewPerson(context) },
        modifier = Modifier.fillMaxWidth().testTag(QuickAddNewPersonTags.CONTINUE),
    )
  }
}

/**
 * The picker's «من جهات الاتصال» section under the store's own people: the phone contacts matching
 * the query, or one row asking for the permission. Nothing read here leaves the phone.
 */
internal fun LazyListScope.phoneContacts(state: QuickAddState, model: QuickAddViewModel) {
  item(key = "phone-contacts-heading") {
    Text(
        stringResource(R.string.quickadd_contacts_section),
        color = sadadPalette.muted,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 8.dp),
    )
  }
  if (state.contactsPermission != ContactPermission.Granted) {
    item(key = "phone-contacts-allow") { AllowContactsRow(model) }
    return
  }
  items(state.contactRows.size, key = { "phone-contact-$it" }) { index ->
    PhoneContactRow(state.contactRows[index], index) { model.pickPhoneContact(it) }
  }
}

@Composable
private fun AllowContactsRow(model: QuickAddViewModel) {
  val launcher =
      rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        model.onContactsPermission(granted)
      }
  Text(
      stringResource(R.string.quickadd_contacts_allow),
      color = if (sadadPalette.isDark) sadadPalette.accent else sadadPalette.primary,
      style = MaterialTheme.typography.labelLarge,
      modifier =
          Modifier.fillMaxWidth()
              .heightIn(min = 48.dp)
              .testTag(QuickAddNewPersonTags.ALLOW_CONTACTS)
              .clickable { launcher.launch(Manifest.permission.READ_CONTACTS) }
              .semantics { role = Role.Button }
              .padding(vertical = 12.dp),
  )
}

@Composable
private fun PhoneContactRow(row: ContactRow, index: Int, onPick: (ContactRow) -> Unit) {
  // One number shows its status («موجود», «في الأرشيف», «رقم غير صالح»); several are chosen later.
  val status = row.numbers.singleOrNull()?.status?.label()
  Column(
      Modifier.fillMaxWidth()
          .heightIn(min = 56.dp)
          .testTag(QuickAddNewPersonTags.phoneContact(index))
          .clickable { onPick(row) }
          .semantics { role = Role.Button }
          .padding(vertical = 8.dp),
  ) {
    Text(row.name, style = MaterialTheme.typography.titleSmall, color = sadadPalette.text)
    Text(
        listOfNotNull(
                row.numbers.joinToString(" · ") { it.raw },
                status?.let { stringResource(it) },
            )
            .joinToString(" — "),
        color = sadadPalette.muted,
        style = MaterialTheme.typography.bodySmall,
    )
  }
}

/** A phone contact with several numbers: which one. */
@Composable
internal fun PhoneNumberChooser(state: QuickAddState, model: QuickAddViewModel) {
  val row = state.contactChooser ?: return
  AlertDialog(
      onDismissRequest = model::dismissPhoneChooser,
      title = { Text(row.name) },
      text = {
        Column {
          row.numbers.forEach { number ->
            val status = number.status.label()
            Text(
                listOfNotNull(number.raw, status?.let { stringResource(it) }).joinToString(" — "),
                modifier =
                    Modifier.fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable { model.choosePhoneNumber(number) }
                        .padding(vertical = 12.dp),
            )
          }
        }
      },
      confirmButton = {},
      dismissButton = {
        TextButton(onClick = model::dismissPhoneChooser) {
          Text(stringResource(R.string.quickadd_cancel))
        }
      },
  )
}
