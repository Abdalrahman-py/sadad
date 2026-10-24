package com.sadad.app.feature.contacts

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.sadad.app.R
import com.sadad.app.SadadApplication
import com.sadad.app.export.MovementKind
import com.sadad.app.feature.lock.AppLock
import com.sadad.app.ui.ScreenTag
import com.sadad.app.ui.components.ConfirmDialog
import com.sadad.app.ui.components.EmptyState
import com.sadad.app.ui.components.PendingBadge
import com.sadad.app.ui.components.SadadButton
import com.sadad.app.ui.components.SadadButtonStyle
import com.sadad.app.ui.components.SadadPageHeading
import com.sadad.app.ui.components.SadadSearchField
import com.sadad.app.ui.components.SadadSegments
import com.sadad.app.ui.components.UndoSnackbar
import com.sadad.app.ui.components.formatCents
import com.sadad.app.ui.components.formatDate
import com.sadad.ledger.Cents
import com.sadad.ledger.Direction
import com.sadad.ledger.PaymentMethod
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal object ContactsTag {
  const val LIST = "contacts:list"
  const val SEARCH = "contacts:search"
  const val ADD = "contacts:add"
  const val MOVEMENTS = "contact:movements"

  fun row(id: String) = "contacts:row:$id"
}

@Composable
fun ContactsScreen(
    modifier: Modifier = Modifier,
    onOpenContact: (String) -> Unit = {},
    onAddContact: () -> Unit = {},
) {
  val viewModel = contactsViewModel()
  ContactsScreen(viewModel, onOpenContact, modifier, onAddContact)
}

@Composable
internal fun ContactsScreen(
    viewModel: ContactsViewModel,
    onOpenContact: (String) -> Unit,
    modifier: Modifier = Modifier,
    onAddContact: () -> Unit = {},
) {
  val state by viewModel.state.collectAsState()
  Box(modifier.fillMaxSize()) {
    ContactsList(state, viewModel, onOpenContact)
  }
}

@Composable
private fun ContactsList(
    state: ContactsState,
    viewModel: ContactsViewModel,
    onOpenContact: (String) -> Unit,
) {
  Column(Modifier.fillMaxSize()) {
    Column(
        Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      SadadPageHeading(
          title = stringResource(R.string.title_contacts),
          subtitle =
              pluralStringResource(
                  R.plurals.contacts_registered,
                  state.registered,
                  state.registered,
              ),
          titleModifier = Modifier.testTag(ScreenTag.CONTACTS),
      )
      SadadSearchField(
          value = state.queryInput,
          onValueChange = viewModel::onQueryChange,
          placeholder = stringResource(R.string.contacts_search),
          modifier = Modifier.fillMaxWidth().testTag(ContactsTag.SEARCH),
      )
      SadadSegments(
          options =
              listOf(
                  ContactSort.NAME to stringResource(R.string.contacts_sort_name),
                  ContactSort.HIGHEST_DEBT to stringResource(R.string.contacts_sort_debt),
                  ContactSort.NEWEST to stringResource(R.string.contacts_sort_newest),
              ),
          selected = state.sortChoice,
          onSelect = viewModel::onSortChange,
      )
    }
    // The bottom padding keeps the last row clear of the add button.
    LazyColumn(
        Modifier.fillMaxSize().testTag(ContactsTag.LIST),
        contentPadding = PaddingValues(bottom = 88.dp),
    ) {
      if (state.loaded && state.rows.isEmpty()) {
        item {
          EmptyState(
              title =
                  stringResource(
                      if (state.query.isBlank()) R.string.contacts_empty
                      else R.string.contacts_no_results
                  ),
              detail =
                  stringResource(
                      if (state.query.isBlank()) R.string.contacts_empty_detail
                      else R.string.contacts_no_results_detail
                  ),
          )
        }
      }
      items(state.rows, key = { it.id }) { row ->
        ContactRowItem(row, onOpenContact)
        HorizontalDivider()
      }
    }
  }
}

@Composable
private fun ContactRowItem(row: ContactRow, onOpenContact: (String) -> Unit) {
  Row(
      modifier =
          Modifier.fillMaxWidth()
              .clickable { onOpenContact(row.id) }
              .padding(horizontal = 16.dp, vertical = 10.dp)
              .testTag(ContactsTag.row(row.id)),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(Modifier.weight(1f)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = row.name, style = MaterialTheme.typography.titleMedium)
        if (row.pending) {
          Spacer(Modifier.width(6.dp))
          PendingBadge()
        }
      }
      Text(
          text = row.phone.ifBlank { stringResource(R.string.contacts_no_phone) },
          style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr),
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    Text(text = formatCents(row.balance), style = MaterialTheme.typography.titleMedium)
  }
}

@Composable
fun ContactDetailScreen(
    contactId: String,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    onRecordDebt: () -> Unit = {},
    onRecordPayment: () -> Unit = {},
    onEdit: () -> Unit = {},
) {
  val viewModel = contactDetailViewModel(contactId)
  ContactDetailScreen(viewModel, onBack, onRecordDebt, onRecordPayment, onEdit, modifier)
}

@Composable
internal fun ContactDetailScreen(
    viewModel: ContactDetailViewModel,
    onBack: () -> Unit,
    onRecordDebt: () -> Unit,
    onRecordPayment: () -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
  val state by viewModel.state.collectAsState()
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  LaunchedEffect(state.done) { if (state.done) onBack() }
  LaunchedEffect(state.error) {
    if (state.error != null) {
      delay(5_000)
      viewModel.clearError()
    }
  }
  Box(modifier.fillMaxSize()) {
    LazyColumn(Modifier.fillMaxSize().testTag(ContactsTag.MOVEMENTS)) {
      item { DetailHeader(state) }
      item {
        DetailActions(
            state = state,
            onRecordDebt = onRecordDebt,
            onRecordPayment = onRecordPayment,
            onEdit = onEdit,
            onWhatsApp = {
              val number = viewModel.whatsAppNumber()
              if (number == null) {
                viewModel.reportError(context.getString(R.string.contacts_whatsapp_invalid))
                return@DetailActions
              }
              try {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$number"))
                )
                // Armed only after a successful launch: a failed one must not bypass the PIN.
                AppLock.get(context).skipNextLock()
              } catch (_: ActivityNotFoundException) {
                viewModel.reportError(context.getString(R.string.contacts_whatsapp_missing))
              }
            },
        )
      }
      item {
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Text(
              text =
                  if (state.movements.isEmpty()) stringResource(R.string.contacts_movements_title)
                  else
                      stringResource(R.string.contacts_movements_title) +
                          " · " +
                          stringResource(R.string.contacts_movements_count, state.movements.size),
              style = MaterialTheme.typography.titleMedium,
          )
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = state.newestFirst,
                onClick = { if (!state.newestFirst) viewModel.toggleOrder() },
                label = { Text(stringResource(R.string.contacts_newest)) },
            )
            FilterChip(
                selected = !state.newestFirst,
                onClick = { if (state.newestFirst) viewModel.toggleOrder() },
                label = { Text(stringResource(R.string.contacts_oldest)) },
            )
          }
        }
      }
      if (state.movements.isEmpty()) {
        item {
          EmptyState(
              title = stringResource(R.string.contacts_no_movements),
              detail = stringResource(R.string.contacts_no_movements_detail),
          )
        }
      } else {
        items(state.movements, key = { "movement:${it.id}" }) { movement ->
          MovementRow(movement)
          HorizontalDivider()
        }
      }
      item {
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          OutlinedButton(
              onClick = { scope.launch { viewModel.share(context) } },
              enabled = state.loaded && !state.missing,
              modifier = Modifier.fillMaxWidth(),
          ) {
            Text(stringResource(R.string.contacts_share))
          }
          if (state.canDeleteContacts) {
            Button(
                onClick = viewModel::requestDelete,
                modifier = Modifier.fillMaxWidth(),
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
            ) {
              Text(stringResource(R.string.contacts_delete))
            }
          }
        }
      }
    }
    state.error?.let { message ->
      Snackbar(modifier = Modifier.align(Alignment.BottomCenter)) { Text(message) }
    }
    // The five-second window lives in the ViewModel, so leaving this screen still queues the
    // archive the confirm dialog promised.
    state.undoName?.let { name ->
      UndoSnackbar(
          message = stringResource(R.string.contacts_delete_undo, name),
          onUndo = viewModel::undoDelete,
          onCommit = viewModel::commitDelete,
          onDismiss = viewModel::clearUndo,
          modifier = Modifier.align(Alignment.BottomCenter),
      )
    }
  }
  state.settleFirst?.let { amount ->
    AlertDialog(
        onDismissRequest = viewModel::dismissSettleFirst,
        title = { Text(stringResource(R.string.contacts_settle_title)) },
        text = { Text(stringResource(R.string.contacts_settle_message, formatCents(amount))) },
        confirmButton = {
          if (state.balance > Cents.ZERO && state.canRegisterPayments) {
            TextButton(
                onClick = {
                  viewModel.dismissSettleFirst()
                  onRecordPayment()
                }
            ) {
              Text(stringResource(R.string.contacts_record_payment))
            }
          }
        },
        dismissButton = {
          TextButton(onClick = viewModel::dismissSettleFirst) {
            Text(stringResource(R.string.contacts_close))
          }
        },
    )
  }
  if (state.confirmDelete) {
    ConfirmDialog(
        title = stringResource(R.string.contacts_confirm_delete_title),
        message = stringResource(R.string.contacts_confirm_delete_message),
        confirmText = stringResource(R.string.contacts_delete),
        dismissText = stringResource(R.string.contacts_cancel),
        onConfirm = viewModel::confirmDelete,
        onDismiss = viewModel::dismissConfirm,
    )
  }
}

@Composable
private fun DetailHeader(state: ContactDetailState) {
  Column(
      Modifier.padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    if (state.missing) {
      Text(
          text = stringResource(R.string.contacts_missing),
          style = MaterialTheme.typography.headlineSmall,
          modifier = Modifier.testTag(ScreenTag.CONTACT_DETAIL),
      )
      Text(stringResource(R.string.contacts_missing_detail))
      return@Column
    }
    Text(
        text = state.name,
        style = MaterialTheme.typography.headlineSmall,
        modifier = Modifier.testTag(ScreenTag.CONTACT_DETAIL),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      if (state.category.isNotBlank())
          Text(
              text = state.category,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
      if (state.phone.isNotBlank())
          Text(
              text = state.phone,
              style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr),
              color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
    }
    if (state.note.isNotBlank()) {
      Text(
          text = stringResource(R.string.contacts_note, state.note),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    Surface(color = MaterialTheme.colorScheme.primary, shape = MaterialTheme.shapes.medium) {
      Text(
          text = stringResource(R.string.contacts_balance, formatCents(state.balance)),
          style = MaterialTheme.typography.titleMedium,
          color = MaterialTheme.colorScheme.onPrimary,
          modifier = Modifier.fillMaxWidth().padding(16.dp),
      )
    }
  }
}

@Composable
private fun DetailActions(
    state: ContactDetailState,
    onRecordDebt: () -> Unit,
    onRecordPayment: () -> Unit,
    onEdit: () -> Unit,
    onWhatsApp: () -> Unit,
) {
  if (state.missing) return
  Column(
      Modifier.padding(horizontal = 16.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      SadadButton(
          text = stringResource(R.string.contacts_record_debt),
          onClick = onRecordDebt,
          modifier = Modifier.weight(1f),
      )
      if (state.canRegisterPayments) {
        SadadButton(
            text = stringResource(R.string.contacts_record_payment),
            onClick = onRecordPayment,
            modifier = Modifier.weight(1f),
        )
      } else {
        Text(
            text = stringResource(R.string.contacts_payments_restricted),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
      }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      SadadButton(
          text = stringResource(R.string.contacts_edit),
          onClick = onEdit,
          style = SadadButtonStyle.SURFACE,
          modifier = Modifier.weight(1f),
      )
      SadadButton(
          text = stringResource(R.string.contacts_whatsapp),
          onClick = onWhatsApp,
          style = SadadButtonStyle.SURFACE,
          modifier = Modifier.weight(1f),
      )
    }
  }
}

@Composable
private fun MovementRow(movement: ContactMovement) {
  Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(Modifier.weight(1f)) {
      Text(
          text =
              stringResource(
                  when {
                    movement.kind == MovementKind.DEBT && movement.direction == Direction.PAYABLE ->
                        R.string.contacts_kind_payable
                    movement.kind == MovementKind.DEBT -> R.string.contacts_kind_debt
                    movement.direction == Direction.PAYABLE ->
                        R.string.contacts_kind_outgoing_payment
                    else -> R.string.contacts_kind_payment
                  }
              ) + " · " + formatDate(movement.createdAt),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      movement.method?.let { method ->
        Text(
            text = methodLabel(method),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      if (movement.note.isNotBlank()) {
        Text(text = movement.note, style = MaterialTheme.typography.bodyMedium)
      }
    }
    Text(text = formatCents(movement.amount), style = MaterialTheme.typography.titleMedium)
    if (movement.pending) {
      Spacer(Modifier.width(6.dp))
      PendingBadge()
    }
  }
}

@Composable
private fun methodLabel(method: PaymentMethod): String =
    stringResource(
        when (method) {
          PaymentMethod.CASH -> R.string.contacts_method_cash
          PaymentMethod.BANK -> R.string.contacts_method_bank
          PaymentMethod.WALLET -> R.string.contacts_method_wallet
          PaymentMethod.WALLET_JAWWAL -> R.string.contacts_method_jawwal
          PaymentMethod.WALLET_PALPAY -> R.string.contacts_method_palpay
        }
    )

@Composable
private fun contactsViewModel(): ContactsViewModel {
  val app = LocalContext.current.applicationContext as SadadApplication
  return viewModel(
      factory =
          viewModelFactory {
            initializer { ContactsViewModel(app.graph, createSavedStateHandle()) }
          }
  )
}

@Composable
private fun contactDetailViewModel(contactId: String): ContactDetailViewModel {
  val app = LocalContext.current.applicationContext as SadadApplication
  return viewModel(
      factory =
          viewModelFactory {
            initializer { ContactDetailViewModel(app.graph, contactId, app.applicationContext) }
          }
  )
}
