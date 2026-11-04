package com.sadad.app.feature.contactform

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.sadad.app.R
import com.sadad.app.SadadApplication
import com.sadad.app.feature.contactpicker.PickedContact
import com.sadad.app.feature.lock.AppLock
import com.sadad.app.requireGraph
import com.sadad.app.ui.components.Chip
import com.sadad.app.ui.components.ChipFlow
import com.sadad.app.ui.components.SadadButton
import com.sadad.app.ui.components.SadadButtonStyle
import com.sadad.app.ui.components.SadadField
import com.sadad.app.ui.components.SadadPageHeading
import com.sadad.app.ui.components.VoiceDraftButton
import com.sadad.app.ui.theme.sadadPalette
import com.sadad.ledger.ContactKind

@Composable
fun ContactFormScreen(
    contactId: String?,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    onOpenContact: (String) -> Unit = {},
    initialName: String = "",
    onOpenPicker: () -> Unit = {},
    onOpenTrash: () -> Unit = {},
    /** The picker's result the route handed back; the form consumes it once. */
    picked: PickedContact? = null,
    onPickDelivered: () -> Unit = {},
    /** Where «حفظ وتسجيل دين» lands: QuickAdd for the person's first debt. */
    onRecord: (String) -> Unit = {},
) {
  ContactFormScreen(
      contactFormViewModel(contactId, initialName),
      modifier,
      onBack,
      onOpenContact,
      onOpenPicker,
      onOpenTrash,
      picked,
      onPickDelivered,
      onRecord,
  )
}

@Composable
fun ContactFormScreen(
    viewModel: ContactFormViewModel,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    onOpenContact: (String) -> Unit = {},
    onOpenPicker: () -> Unit = {},
    onOpenTrash: () -> Unit = {},
    picked: PickedContact? = null,
    onPickDelivered: () -> Unit = {},
    /** Where «حفظ وتسجيل دين» lands: QuickAdd for the person's first debt. */
    onRecord: (String) -> Unit = {},
) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  val context = LocalContext.current
  // The picker's result: a new person fills the fields; an archive hit opens the trash.
  LaunchedEffect(picked) {
    val result = picked
    if (result != null) {
      viewModel.onPicked(result)
      onPickDelivered()
    }
  }
  // The picked person already lives in the store; the archive hit opens the trash screen.
  LaunchedEffect(state.openContactId) { state.openContactId?.let { onOpenContact(it) } }
  LaunchedEffect(state.openTrash) {
    if (state.openTrash) onOpenTrash()
  }
  LaunchedEffect(state.openContactId, state.openTrash) {
    if (state.openContactId != null || state.openTrash) viewModel.onPageHandled()
  }
  // Saved: «حفظ وتسجيل دين» ends on the amount screen; «حفظ فقط» on the person's page.
  LaunchedEffect(state.savedContactId) {
    val id = state.savedContactId ?: return@LaunchedEffect
    if (state.record) onRecord(id) else onOpenContact(id)
  }

  Column(
      modifier = modifier.fillMaxSize().padding(18.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    SadadPageHeading(
        title =
            stringResource(
                if (state.edit) R.string.contactform_edit_title else R.string.contactform_add_title
            ),
        subtitle = stringResource(R.string.contactform_subtitle),
        onBack = onBack,
    )
    // The fields scroll; the save buttons stay pinned at the end (the shell pads the keyboard).
    Column(
        modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      // The device's contacts, add mode only: edit mode never replaces another editor's data.
      if (!state.edit) {
        SadadButton(
            text = stringResource(R.string.contactform_pick_contacts),
            style = SadadButtonStyle.SURFACE,
            onClick = onOpenPicker,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        )
      }
      PersonFields(
          state,
          viewModel::onNameChange,
          viewModel::onPrefixChange,
          viewModel::onPhoneChange,
      ) { kind ->
        viewModel.onKindChange(kind)
        // The old default belongs to the other kind: swap it, keep anything the user chose.
        if (state.category.isBlank() || state.category in generalCategories(state.kind, context)) {
          viewModel.onCategoryChange(generalCategories(kind, context).first())
        }
      }
      // Edit mode keeps the full form; add mode is a person and a kind and nothing else.
      if (state.edit) {
        Text(
            stringResource(R.string.contactform_category),
            style = MaterialTheme.typography.labelMedium,
            color = sadadPalette.text,
        )
        val general = generalCategories(state.kind, context)
        ChipFlow(
            chips =
                (general +
                        listOfNotNull(state.category.takeIf { it.isNotBlank() && it !in general }))
                    .map { Chip(it, it) },
            selected = state.category,
            onSelect = viewModel::onCategoryChange,
        )
        SadadField(
            value = state.note,
            onValueChange = viewModel::onNoteChange,
            label = stringResource(R.string.contactform_note),
            placeholder = stringResource(R.string.contactform_note_hint),
            minLines = 2,
            enabled = !state.busy,
            fieldModifier = Modifier.fillMaxWidth(),
            singleLine = false,
        )
        VoiceDraft { viewModel.onNoteChange(it) }
      }
      state.message?.let {
        Text(it, color = MaterialTheme.colorScheme.error)
      }
    }
    if (state.edit) {
      SadadButton(
          text = stringResource(R.string.contactform_save_edit),
          onClick = viewModel::save,
          enabled = !state.busy,
          modifier = Modifier.fillMaxWidth(),
      )
    } else {
      SadadButton(
          text = stringResource(R.string.contactform_save_record),
          onClick = viewModel::saveAndRecord,
          enabled = !state.busy,
          modifier = Modifier.fillMaxWidth(),
      )
      SadadButton(
          text = stringResource(R.string.contactform_save_only),
          style = SadadButtonStyle.SURFACE,
          onClick = viewModel::saveOnly,
          enabled = !state.busy,
          modifier = Modifier.fillMaxWidth(),
      )
    }
  }

  state.duplicate?.let { duplicate ->
    AlertDialog(
        onDismissRequest = viewModel::dismissDuplicate,
        title = { Text(stringResource(R.string.contactform_duplicate_title)) },
        text = { Text(stringResource(R.string.contactform_duplicate_message, duplicate.name)) },
        confirmButton = {
          TextButton(
              onClick = {
                onOpenContact(duplicate.contactId)
                viewModel.dismissDuplicate()
              }
          ) {
            Text(stringResource(R.string.contactform_open_contact))
          }
        },
        dismissButton = {
          TextButton(onClick = viewModel::dismissDuplicate) {
            Text(stringResource(R.string.contactform_back_to_edit))
          }
        },
    )
  }
}

/** Exempt only the actual speech launch, including keyboard and accessibility activation. */
@Composable
private fun VoiceDraft(onText: (String) -> Unit) {
  val context = LocalContext.current
  val lock = remember(context) { AppLock.get(context.applicationContext) }
  VoiceDraftButton(onText, onLaunch = lock::skipNextLock)
}

/** The categories offered for each kind; the first is what a new person starts with. */
internal fun generalCategories(kind: ContactKind, context: Context): List<String> =
    context.resources
        .getStringArray(
            if (kind == ContactKind.SUPPLIER) R.array.contactform_categories_supplier
            else R.array.contactform_categories_customer
        )
        .toList()

@Composable
private fun contactFormViewModel(contactId: String?, initialName: String): ContactFormViewModel {
  val graph = requireGraph()
  return viewModel(
      factory =
          viewModelFactory {
            initializer {
              val app = this[APPLICATION_KEY] as SadadApplication
              val store =
                  checkNotNull(graph.current.value) { "the contact form needs an open store" }
              ContactFormViewModel(
                  store.repository,
                  store.account.storeUserId,
                  app,
                  createSavedStateHandle(),
                  contactId,
                  initialName,
              )
            }
          }
  )
}
