package com.sadad.app.feature.payment

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import com.sadad.app.ui.components.EmptyState
import com.sadad.app.ui.components.SadadButton
import com.sadad.app.ui.components.SadadField
import com.sadad.app.ui.components.VoiceDraftButton
import com.sadad.app.ui.components.formatCents
import com.sadad.app.ui.components.formatDate
import com.sadad.ledger.Cents
import com.sadad.ledger.PaymentMethod

internal object PaymentTag {
  const val AMOUNT = "payment:amount"
  const val NOTE = "payment:note"
  const val SAVE = "payment:save"

  fun method(method: PaymentMethod): String = "payment:method:${method.name.lowercase()}"
}

/** The methods the Java form offered, in its order; a plain `wallet` is not one of them. */
private val METHODS: List<PaymentMethod> =
    listOf(
        PaymentMethod.CASH,
        PaymentMethod.BANK,
        PaymentMethod.WALLET_JAWWAL,
        PaymentMethod.WALLET_PALPAY,
    )

@Composable
fun PaymentFormScreen(
    contactId: String,
    /** Ignored: the Java app recorded Contact-level payments only, never a payment on one Debt. */
    debtId: String?,
    modifier: Modifier = Modifier,
    onSaved: () -> Unit = {},
) {
  val viewModel = paymentViewModel(contactId)
  PaymentFormScreen(viewModel, onSaved, modifier)
}

@Composable
internal fun PaymentFormScreen(
    viewModel: PaymentFormViewModel,
    onSaved: () -> Unit,
    modifier: Modifier = Modifier,
) {
  val state by viewModel.state.collectAsState()
  val context = LocalContext.current
  val snackbar = remember { SnackbarHostState() }
  LaunchedEffect(state.error) {
    val error = state.error ?: return@LaunchedEffect
    snackbar.showSnackbar(error)
    viewModel.clearError()
  }
  Scaffold(modifier = modifier, snackbarHost = { SnackbarHost(snackbar) }) { insets ->
    Column(
        Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text(stringResource(R.string.title_payment), style = MaterialTheme.typography.headlineSmall)
      when {
        state.restricted -> {
          EmptyState(
              title = stringResource(R.string.payment_restricted),
              detail = stringResource(R.string.payment_restricted_detail),
          )
          return@Column
        }
        state.missing -> {
          EmptyState(
              title = stringResource(R.string.payment_no_contact),
              detail = stringResource(R.string.payment_no_contact_detail),
          )
          return@Column
        }
        state.loaded && state.openBalance == Cents.ZERO -> {
          EmptyState(
              title = stringResource(R.string.payment_no_open_balance),
              detail = stringResource(R.string.payment_no_open_balance_detail),
          )
          return@Column
        }
      }
      Text(stringResource(R.string.payment_subtitle, state.name))
      Text(
          stringResource(R.string.payment_balance, formatCents(state.openBalance)),
          style = MaterialTheme.typography.bodyMedium,
      )
      SadadField(
          value = state.amount,
          onValueChange = viewModel::onAmountChange,
          singleLine = true,
          enabled = !state.busy && !state.saved,
          label = stringResource(R.string.payment_amount_label),
          placeholder = stringResource(R.string.payment_amount_placeholder),
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
          fieldModifier = Modifier.fillMaxWidth().testTag(PaymentTag.AMOUNT),
      )
      Text(
          stringResource(R.string.payment_method_label),
          style = MaterialTheme.typography.bodyMedium,
      )
      METHODS.chunked(2).forEach { pair ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          for (method in pair) {
            FilterChip(
                selected = state.method == method,
                onClick = { viewModel.onMethodChange(method) },
                enabled = !state.busy && !state.saved,
                label = { Text(methodName(method)) },
                modifier = Modifier.testTag(PaymentTag.method(method)),
            )
          }
        }
      }
      SadadField(
          value = state.note,
          onValueChange = viewModel::onNoteChange,
          enabled = !state.busy && !state.saved,
          minLines = 2,
          label = stringResource(R.string.payment_note_label),
          placeholder = stringResource(R.string.payment_note_placeholder),
          fieldModifier = Modifier.fillMaxWidth().testTag(PaymentTag.NOTE),
          singleLine = false,
      )
      // Locking is skipped before the recognizer opens, so coming back does not ask for the PIN.
      VoiceDraftButton(
          onText = viewModel::onNoteChange,
          onLaunch = { AppLock.get(context).skipNextLock() },
      )
      SadadButton(
          text = stringResource(R.string.payment_save),
          onClick = { viewModel.save(context) },
          enabled = !state.busy && !state.saved && state.loaded,
          modifier = Modifier.fillMaxWidth().testTag(PaymentTag.SAVE),
      )
    }
  }
  val review = state.review
  if (review != null) {
    ConfirmDialog(
        title = stringResource(R.string.payment_review_title),
        message = reviewMessage(review),
        confirmText = stringResource(R.string.payment_review_confirm),
        dismissText = stringResource(R.string.payment_review_dismiss),
        onConfirm = { viewModel.confirm(context) },
        onDismiss = viewModel::dismissReview,
    )
  }
  val confirmation = state.confirmation
  if (confirmation != null) {
    AlertDialog(
        onDismissRequest = {
          viewModel.dismissConfirmation()
          onSaved()
        },
        title = { Text(stringResource(R.string.payment_success_title)) },
        text = {
          Text(
              stringResource(
                  R.string.payment_success,
                  formatCents(confirmation.amount),
                  confirmation.name,
              )
          )
        },
        confirmButton = {
          TextButton(
              onClick = {
                viewModel.dismissConfirmation()
                onSaved()
              }
          ) {
            Text(stringResource(R.string.payment_close))
          }
        },
    )
  }
}

/** The review body: who, how much, how, the note, then one line per Debt the payment covers. */
@Composable
private fun reviewMessage(review: PaymentReview): String {
  val head =
      listOfNotNull(
          stringResource(R.string.payment_review_person, review.name),
          stringResource(R.string.payment_review_amount, formatCents(review.amount)),
          stringResource(R.string.payment_review_method, methodName(review.method)),
          review.note?.let { note -> stringResource(R.string.payment_review_note, note) },
      )
  val parts =
      review.parts.map { part ->
        stringResource(
            R.string.payment_review_part,
            formatDate(part.createdAt),
            formatCents(part.amount),
        )
      }
  val footer = stringResource(R.string.payment_review_footer)
  return (head + "" + parts + footer).joinToString("\n")
}

@Composable
private fun methodName(method: PaymentMethod): String =
    stringResource(
        when (method) {
          PaymentMethod.CASH -> R.string.payment_method_cash
          PaymentMethod.BANK -> R.string.payment_method_bank
          PaymentMethod.WALLET -> R.string.payment_method_wallet
          PaymentMethod.WALLET_JAWWAL -> R.string.payment_method_jawwal
          PaymentMethod.WALLET_PALPAY -> R.string.payment_method_palpay
        }
    )

@Composable
private fun paymentViewModel(contactId: String): PaymentFormViewModel {
  val app = LocalContext.current.applicationContext as SadadApplication
  return viewModel(
      factory =
          viewModelFactory {
            initializer { PaymentFormViewModel(app.graph, contactId, createSavedStateHandle()) }
          }
  )
}
