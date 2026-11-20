package com.sadad.app.feature.quickadd

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.sadad.app.R
import com.sadad.app.feature.contactform.PersonFields
import com.sadad.app.feature.contactpicker.rememberPhoneContactPicker
import com.sadad.app.ui.components.SadadButton
import com.sadad.app.ui.components.SadadButtonStyle
import com.sadad.app.ui.components.SadadPageHeading
import com.sadad.app.ui.theme.sadadPalette

/** Test tags of the new-person step and the picker's phone contacts. */
object QuickAddNewPersonTags {
  const val STEP = "quickadd:new-person"
  const val CONTINUE = "quickadd:new-person-continue"
  const val OPEN_RECORD = "quickadd:new-person-open-record"
  const val FROM_CONTACTS = "quickadd:from-contacts"
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
 * The picker's «من جهات الاتصال» row, under the search field: Android's own picker returns one
 * number, and someone the store keeps opens them while anyone else starts a new person. The app
 * reads only that one row. The launcher is registered here, outside the people list: a lazy row
 * scrolled away while the system picker is up would drop the result with it.
 */
@Composable
internal fun FromContactsRow(model: QuickAddViewModel) {
  val open = rememberPhoneContactPicker { contact -> contact?.let(model::pickPhoneContact) }
  Text(
      stringResource(R.string.quickadd_contacts_section),
      color = if (sadadPalette.isDark) sadadPalette.accent else sadadPalette.primary,
      style = MaterialTheme.typography.labelLarge,
      modifier =
          Modifier.fillMaxWidth()
              .heightIn(min = 48.dp)
              .testTag(QuickAddNewPersonTags.FROM_CONTACTS)
              .clip(RoundedCornerShape(12.dp))
              .clickable(onClick = open)
              .semantics { role = Role.Button }
              .padding(vertical = 12.dp),
  )
}
