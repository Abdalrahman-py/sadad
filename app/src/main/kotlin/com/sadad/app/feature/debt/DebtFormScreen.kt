package com.sadad.app.feature.debt

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.sadad.app.R
import com.sadad.app.SadadApplication
import com.sadad.app.feature.lock.AppLock
import com.sadad.app.ui.components.ConfirmDialog
import com.sadad.app.ui.components.SadadButton
import com.sadad.app.ui.components.SadadField
import com.sadad.app.ui.components.VoiceDraftButton
import com.sadad.app.ui.components.formatCents
import com.sadad.app.ui.components.parseDueDate
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

internal object DebtTag {
  const val AMOUNT = "debt:amount"
  const val DUE_DATE = "debt:due-date"
  const val CLEAR_DUE_DATE = "debt:clear-due-date"
  const val NOTE = "debt:note"
  const val SAVE = "debt:save"
}

@Composable
fun DebtFormScreen(
    contactId: String,
    modifier: Modifier = Modifier,
    onSaved: () -> Unit = {},
) {
  val viewModel = debtViewModel(contactId)
  DebtFormScreen(viewModel, onSaved, modifier)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DebtFormScreen(
    viewModel: DebtFormViewModel,
    onSaved: () -> Unit,
    modifier: Modifier = Modifier,
) {
  val state by viewModel.state.collectAsState()
  val context = LocalContext.current
  val snackbar = remember { SnackbarHostState() }
  val success = stringResource(R.string.debt_success)
  val noPhone = stringResource(R.string.debt_phone_none)
  val noDue = stringResource(R.string.debt_due_none)
  var pickingDate by remember { mutableStateOf(false) }
  LaunchedEffect(state.error) {
    val error = state.error ?: return@LaunchedEffect
    snackbar.showSnackbar(error)
    viewModel.clearError()
  }
  // The success text is part of the record, so it is shown before the form closes.
  LaunchedEffect(state.saved) {
    if (!state.saved) return@LaunchedEffect
    snackbar.showSnackbar(success, duration = SnackbarDuration.Short)
    onSaved()
  }
  Scaffold(modifier = modifier, snackbarHost = { SnackbarHost(snackbar) }) { insets ->
    Column(
        Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text(stringResource(R.string.title_debt), style = MaterialTheme.typography.headlineSmall)
      if (state.missing) {
        Text(stringResource(R.string.debt_no_contact), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.debt_no_contact_detail))
        return@Column
      }
      Text(stringResource(R.string.debt_subtitle, state.name))
      SadadField(
          value = state.amount,
          onValueChange = viewModel::onAmountChange,
          singleLine = true,
          enabled = !state.busy && !state.saved,
          label = stringResource(R.string.debt_amount_label),
          placeholder = stringResource(R.string.debt_amount_placeholder),
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
          fieldModifier = Modifier.fillMaxWidth().testTag(DebtTag.AMOUNT),
      )
      Text(stringResource(R.string.debt_due_label), style = MaterialTheme.typography.bodyMedium)
      OutlinedButton(
          onClick = { pickingDate = true },
          enabled = !state.busy && !state.saved,
          modifier = Modifier.fillMaxWidth().testTag(DebtTag.DUE_DATE),
      ) {
        Text(state.dueDate ?: stringResource(R.string.debt_due_hint))
      }
      if (state.dueDate != null) {
        TextButton(
            onClick = viewModel::removeDueDate,
            enabled = !state.busy && !state.saved,
            modifier = Modifier.testTag(DebtTag.CLEAR_DUE_DATE),
        ) {
          Text(stringResource(R.string.debt_due_remove))
        }
      }
      SadadField(
          value = state.note,
          onValueChange = viewModel::onNoteChange,
          enabled = !state.busy && !state.saved,
          minLines = 2,
          label = stringResource(R.string.debt_note_label),
          fieldModifier = Modifier.fillMaxWidth().testTag(DebtTag.NOTE),
          singleLine = false,
      )
      // Locking is skipped before the recognizer opens, so coming back does not ask for the PIN.
      VoiceDraftButton(
          onText = viewModel::onNoteChange,
          onLaunch = { AppLock.get(context).skipNextLock() },
      )
      SadadButton(
          text = stringResource(R.string.debt_save),
          onClick = { viewModel.save(context) },
          enabled = !state.busy && !state.saved && state.loaded && !state.missing,
          modifier = Modifier.fillMaxWidth().testTag(DebtTag.SAVE),
      )
    }
  }
  if (state.anomaly != null) {
    ConfirmDialog(
        title = stringResource(R.string.debt_anomaly_title),
        message = stringResource(R.string.debt_anomaly_message),
        confirmText = stringResource(R.string.debt_anomaly_confirm),
        dismissText = stringResource(R.string.debt_anomaly_dismiss),
        onConfirm = viewModel::confirmAnomaly,
        onDismiss = viewModel::dismissAnomaly,
    )
  }
  val review = state.review
  if (review != null) {
    ConfirmDialog(
        title = stringResource(R.string.debt_review_title),
        message =
            listOfNotNull(
                    stringResource(R.string.debt_review_person, review.name),
                    stringResource(R.string.debt_review_phone, review.phone.ifEmpty { noPhone }),
                    stringResource(R.string.debt_review_amount, formatCents(review.amount)),
                    stringResource(R.string.debt_review_due, review.dueDate ?: noDue),
                    review.note?.let { stringResource(R.string.debt_review_note, it) },
                )
                .joinToString("\n") + "\n\n" + stringResource(R.string.debt_review_footer),
        confirmText = stringResource(R.string.debt_review_confirm),
        dismissText = stringResource(R.string.debt_review_dismiss),
        onConfirm = { viewModel.confirm(context) },
        onDismiss = viewModel::dismissReview,
    )
  }
  if (pickingDate) {
    val start = state.dueDate?.let(::parseDueDate) ?: LocalDate.now()
    val pickerState =
        androidx.compose.material3.rememberDatePickerState(
            initialSelectedDateMillis =
                start.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        )
    DatePickerDialog(
        onDismissRequest = { pickingDate = false },
        confirmButton = {
          TextButton(
              onClick = {
                pickerState.selectedDateMillis?.let { millis ->
                  val date = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                  viewModel.onDueDateChange(date.toString())
                }
                pickingDate = false
              }
          ) {
            Text(stringResource(R.string.debt_picker_confirm))
          }
        },
        dismissButton = {
          TextButton(onClick = { pickingDate = false }) {
            Text(stringResource(R.string.debt_picker_cancel))
          }
        },
    ) {
      DatePicker(state = pickerState)
    }
  }
}

@Composable
private fun debtViewModel(contactId: String): DebtFormViewModel {
  val app = LocalContext.current.applicationContext as SadadApplication
  return viewModel(
      factory =
          viewModelFactory {
            initializer { DebtFormViewModel(app.graph, contactId, createSavedStateHandle()) }
          }
  )
}
