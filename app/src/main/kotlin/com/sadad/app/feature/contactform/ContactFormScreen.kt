package com.sadad.app.feature.contactform

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.sadad.app.R
import com.sadad.app.SadadApplication
import com.sadad.app.feature.lock.AppLock
import com.sadad.app.ui.components.Chip
import com.sadad.app.ui.components.ChipFlow
import com.sadad.app.ui.components.SadadButton
import com.sadad.app.ui.components.SadadButtonStyle
import com.sadad.app.ui.components.SadadChoiceField
import com.sadad.app.ui.components.SadadField
import com.sadad.app.ui.components.SadadPageHeading
import com.sadad.app.ui.components.SadadSegments
import com.sadad.app.ui.components.SadadSwitchRow
import com.sadad.app.ui.components.VoiceDraftButton
import com.sadad.app.ui.components.formatCents
import com.sadad.app.ui.components.parseDueDate
import com.sadad.app.ui.theme.sadadPalette
import com.sadad.ledger.ContactKind
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@Composable
fun ContactFormScreen(
    contactId: String?,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    onOpenContact: (String) -> Unit = {},
    initialName: String = "",
) {
  ContactFormScreen(contactFormViewModel(contactId, initialName), modifier, onBack, onOpenContact)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactFormScreen(
    viewModel: ContactFormViewModel,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    onOpenContact: (String) -> Unit = {},
) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  val context = LocalContext.current
  val nameFocus = remember { FocusRequester() }
  val phoneFocus = remember { FocusRequester() }
  val debtFocus = remember { FocusRequester() }
  val nameView = remember { BringIntoViewRequester() }
  val phoneView = remember { BringIntoViewRequester() }
  val debtView = remember { BringIntoViewRequester() }
  LaunchedEffect(state.validationAttempt, state.duplicate) {
    if (state.validationAttempt == 0) return@LaunchedEffect
    val target =
        when {
          state.nameError != null -> nameFocus to nameView
          state.phoneError != null -> phoneFocus to phoneView
          state.debtError != null -> debtFocus to debtView
          else -> return@LaunchedEffect
        }
    // A duplicate dialog owns focus until dismissed; its phone field is already scrolled into view.
    if (state.duplicate == null) target.first.requestFocus()
    target.second.bringIntoView()
  }
  // Saved: the form is replaced by the person's page, so back from there is the list again.
  LaunchedEffect(state.savedContactId) { state.savedContactId?.let(onOpenContact) }

  Column(
      modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
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
    SadadField(
        value = state.name,
        onValueChange = viewModel::onNameChange,
        label = stringResource(R.string.contactform_name),
        placeholder = stringResource(R.string.contactform_name_hint),
        singleLine = true,
        enabled = !state.busy,
        isError = state.nameError != null,
        supportingText = state.nameError,
        modifier = Modifier.bringIntoViewRequester(nameView),
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
        onSelect = viewModel::onPrefixChange,
        enabled = !state.busy,
    )
    SadadField(
        value = state.phone,
        onValueChange = viewModel::onPhoneChange,
        label = stringResource(R.string.contactform_phone),
        placeholder = stringResource(R.string.contactform_phone_hint),
        singleLine = true,
        enabled = !state.busy,
        isError = state.phoneError != null,
        supportingText = state.phoneError,
        modifier = Modifier.bringIntoViewRequester(phoneView),
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
        onSelect = { kind ->
          viewModel.onKindChange(kind)
          // The old default belongs to the other kind: swap it, keep anything the user chose.
          if (
              state.category.isBlank() || state.category in generalCategories(state.kind, context)
          ) {
            viewModel.onCategoryChange(generalCategories(kind, context).first())
          }
        },
    )
    // General categories to pick from, instead of typing one by hand.
    Text(
        stringResource(R.string.contactform_category),
        style = MaterialTheme.typography.labelMedium,
        color = sadadPalette.text,
    )
    val general = generalCategories(state.kind, context)
    ChipFlow(
        chips =
            (general + listOfNotNull(state.category.takeIf { it.isNotBlank() && it !in general }))
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
    if (!state.edit) {
      SadadSwitchRow(
          title = stringResource(R.string.contactform_first_debt),
          checked = state.withFirstDebt,
          onCheckedChange = viewModel::onFirstDebtChange,
          enabled = !state.busy,
      )
    }
    if (state.withFirstDebt && !state.edit) {
      FirstDebtFields(state, viewModel, debtFocus, debtView)
    }
    state.message?.let {
      Text(it, color = MaterialTheme.colorScheme.error)
    }
    SadadButton(
        text =
            stringResource(
                if (state.edit) R.string.contactform_save_edit else R.string.contactform_save_add
            ),
        onClick = viewModel::save,
        enabled = !state.busy,
        modifier = Modifier.fillMaxWidth(),
    )
  }

  state.review?.let { review ->
    ReviewDialog(
        review = review,
        onConfirm = viewModel::confirm,
        onDismiss = { viewModel.dismissReview() },
    )
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FirstDebtFields(
    state: ContactFormState,
    viewModel: ContactFormViewModel,
    focus: FocusRequester,
    bringIntoView: BringIntoViewRequester,
) {
  var picking by remember { mutableStateOf(false) }
  SadadField(
      value = state.debtAmount,
      onValueChange = viewModel::onDebtAmountChange,
      label = stringResource(R.string.contactform_debt_amount),
      singleLine = true,
      enabled = !state.busy,
      isError = state.debtError != null,
      supportingText = state.debtError,
      modifier = Modifier.bringIntoViewRequester(bringIntoView),
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
      fieldModifier = Modifier.fillMaxWidth().focusRequester(focus),
  )
  Text(stringResource(R.string.contactform_debt_due), style = MaterialTheme.typography.labelLarge)
  SadadButton(
      text = state.debtDue ?: stringResource(R.string.contactform_pick_date),
      style = SadadButtonStyle.SURFACE,
      onClick = { picking = true },
      enabled = !state.busy,
      modifier = Modifier.fillMaxWidth(),
  )
  if (picking) {
    val initial =
        state.debtDue
            ?.let(::parseDueDate)
            ?.atStartOfDay(ZoneOffset.UTC)
            ?.toInstant()
            ?.toEpochMilli()
    val picker = rememberDatePickerState(initialSelectedDateMillis = initial)
    DatePickerDialog(
        onDismissRequest = { picking = false },
        confirmButton = {
          TextButton(
              onClick = {
                picker.selectedDateMillis?.let {
                  val date =
                      Instant.ofEpochMilli(it)
                          .atZone(ZoneOffset.UTC)
                          .toLocalDate()
                          .format(DateTimeFormatter.ISO_LOCAL_DATE)
                  viewModel.onDebtDueChange(date)
                }
                picking = false
              },
              enabled = picker.selectedDateMillis != null,
          ) {
            Text(stringResource(R.string.contactform_pick))
          }
        },
        dismissButton = {
          TextButton(onClick = { picking = false }) {
            Text(stringResource(R.string.contactform_cancel))
          }
        },
    ) {
      DatePicker(state = picker)
    }
  }
  SadadField(
      value = state.debtNote,
      onValueChange = viewModel::onDebtNoteChange,
      label = stringResource(R.string.contactform_debt_details),
      placeholder = stringResource(R.string.contactform_debt_details_hint),
      minLines = 2,
      enabled = !state.busy,
      fieldModifier = Modifier.fillMaxWidth(),
      singleLine = false,
  )
  VoiceDraft { viewModel.onDebtNoteChange(it) }
}

@Composable
private fun ReviewDialog(review: ContactReview, onConfirm: () -> Unit, onDismiss: () -> Unit) {
  AlertDialog(
      onDismissRequest = onDismiss,
      title = { Text(stringResource(R.string.contactform_review_title)) },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          ReviewRow(R.string.contactform_review_name, review.name)
          ReviewRow(
              R.string.contactform_review_phone,
              review.phone ?: stringResource(R.string.contactform_not_recorded),
          )
          ReviewRow(R.string.contactform_review_category, review.category)
          review.amount?.let {
            ReviewRow(R.string.contactform_review_amount, formatCents(it))
            ReviewRow(
                R.string.contactform_review_due,
                review.dueDate ?: stringResource(R.string.contactform_not_set),
            )
          }
          Text(stringResource(R.string.contactform_review_hint))
        }
      },
      confirmButton = {
        TextButton(onClick = onConfirm) {
          Text(stringResource(R.string.contactform_confirm_save))
        }
      },
      dismissButton = {
        TextButton(onClick = onDismiss) {
          Text(stringResource(R.string.contactform_back_to_edit))
        }
      },
  )
}

@Composable
private fun ReviewRow(label: Int, value: String) {
  Column {
    Text(
        stringResource(label),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(value)
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
private fun generalCategories(kind: ContactKind, context: android.content.Context): List<String> =
    context.resources
        .getStringArray(
            if (kind == ContactKind.SUPPLIER) R.array.contactform_categories_supplier
            else R.array.contactform_categories_customer
        )
        .toList()

@Composable
private fun contactFormViewModel(contactId: String?, initialName: String): ContactFormViewModel =
    viewModel(
        factory =
            viewModelFactory {
              initializer {
                val app = this[APPLICATION_KEY] as SadadApplication
                val store =
                    checkNotNull(app.graph.current.value) {
                      "the contact form needs an open store"
                    }
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
