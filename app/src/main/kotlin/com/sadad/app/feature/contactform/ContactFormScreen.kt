package com.sadad.app.feature.contactform

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.sadad.app.MainActivity
import com.sadad.app.R
import com.sadad.app.SadadApplication
import com.sadad.app.feature.lock.AppLock
import com.sadad.app.feature.notifications.DueReminderWorker
import com.sadad.app.ui.components.VoiceDraftButton
import com.sadad.app.ui.components.formatCents
import com.sadad.app.ui.components.parseDueDate
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@Composable
fun ContactFormScreen(contactId: String?, modifier: Modifier = Modifier) {
  ContactFormScreen(contactFormViewModel(contactId), modifier)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactFormScreen(viewModel: ContactFormViewModel, modifier: Modifier = Modifier) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  val context = LocalContext.current
  LaunchedEffect(state.savedContactId) {
    state.savedContactId?.let { openContact(context, viewModel.storeUserId, it) }
  }

  Column(
      modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text(
        text =
            stringResource(
                if (state.edit) R.string.contactform_edit_title else R.string.contactform_add_title
            ),
        style = MaterialTheme.typography.headlineSmall,
    )
    Text(
        text = stringResource(R.string.contactform_subtitle),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
        value = state.name,
        onValueChange = viewModel::onNameChange,
        label = { Text(stringResource(R.string.contactform_name)) },
        singleLine = true,
        enabled = !state.busy,
        isError = state.nameError != null,
        modifier = Modifier.fillMaxWidth(),
    )
    state.nameError?.let {
      Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
    Text(stringResource(R.string.contactform_prefix), style = MaterialTheme.typography.labelLarge)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
      val prefixes =
          listOf(
              "970" to stringResource(R.string.contactform_prefix_970),
              "972" to stringResource(R.string.contactform_prefix_972),
          )
      prefixes.forEachIndexed { index, (prefix, label) ->
        SegmentedButton(
            selected = state.prefix == prefix,
            onClick = { viewModel.onPrefixChange(prefix) },
            shape = SegmentedButtonDefaults.itemShape(index = index, count = prefixes.size),
            enabled = !state.busy,
        ) {
          Text(label)
        }
      }
    }
    OutlinedTextField(
        value = state.phone,
        onValueChange = viewModel::onPhoneChange,
        label = { Text(stringResource(R.string.contactform_phone)) },
        singleLine = true,
        enabled = !state.busy,
        isError = state.phoneError != null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
        modifier = Modifier.fillMaxWidth(),
    )
    state.phoneError?.let {
      Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
    OutlinedTextField(
        value = state.category,
        onValueChange = viewModel::onCategoryChange,
        label = { Text(stringResource(R.string.contactform_category)) },
        placeholder = { Text(stringResource(R.string.contactform_category_hint)) },
        singleLine = true,
        enabled = !state.busy,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = state.note,
        onValueChange = viewModel::onNoteChange,
        label = { Text(stringResource(R.string.contactform_note)) },
        minLines = 2,
        enabled = !state.busy,
        modifier = Modifier.fillMaxWidth(),
    )
    VoiceDraft { viewModel.onNoteChange(it) }
    if (!state.edit) {
      // The row is the one control, so the switch is announced with its label and the whole row
      // taps.
      Row(
          modifier =
              Modifier.fillMaxWidth()
                  .heightIn(min = 48.dp)
                  .toggleable(
                      value = state.withFirstDebt,
                      enabled = !state.busy,
                      role = Role.Switch,
                      onValueChange = viewModel::onFirstDebtChange,
                  ),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Text(stringResource(R.string.contactform_first_debt))
        Switch(checked = state.withFirstDebt, onCheckedChange = null, enabled = !state.busy)
      }
    }
    if (state.withFirstDebt && !state.edit) {
      FirstDebtFields(state, viewModel)
    }
    state.message?.let {
      Text(it, color = MaterialTheme.colorScheme.error)
    }
    Button(
        onClick = viewModel::save,
        enabled = !state.busy,
        modifier = Modifier.fillMaxWidth(),
    ) {
      Text(
          stringResource(
              if (state.edit) R.string.contactform_save_edit else R.string.contactform_save_add
          )
      )
    }
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
                openContact(context, viewModel.storeUserId, duplicate.contactId)
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
private fun FirstDebtFields(state: ContactFormState, viewModel: ContactFormViewModel) {
  var picking by remember { mutableStateOf(false) }
  OutlinedTextField(
      value = state.debtAmount,
      onValueChange = viewModel::onDebtAmountChange,
      label = { Text(stringResource(R.string.contactform_debt_amount)) },
      singleLine = true,
      enabled = !state.busy,
      isError = state.debtError != null,
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
      modifier = Modifier.fillMaxWidth(),
  )
  state.debtError?.let {
    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
  }
  Text(stringResource(R.string.contactform_debt_due), style = MaterialTheme.typography.labelLarge)
  OutlinedButton(
      onClick = { picking = true },
      enabled = !state.busy,
      modifier = Modifier.fillMaxWidth(),
  ) {
    Text(state.debtDue ?: stringResource(R.string.contactform_pick_date))
  }
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
  OutlinedTextField(
      value = state.debtNote,
      onValueChange = viewModel::onDebtNoteChange,
      label = { Text(stringResource(R.string.contactform_debt_details)) },
      minLines = 2,
      enabled = !state.busy,
      modifier = Modifier.fillMaxWidth(),
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

/**
 * The nav host lives in the ui package, which this ticket may not edit, so a Contact opens through
 * the same MainActivity deep link the due reminders use.
 */
private fun openContact(context: Context, storeUserId: String, contactId: String) {
  context.startActivity(
      Intent(context, MainActivity::class.java)
          .putExtra(DueReminderWorker.EXTRA_STORE, storeUserId)
          .putExtra(DueReminderWorker.EXTRA_CONTACT, contactId)
          .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
  )
}

@Composable
private fun contactFormViewModel(contactId: String?): ContactFormViewModel =
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
                )
              }
            }
    )
