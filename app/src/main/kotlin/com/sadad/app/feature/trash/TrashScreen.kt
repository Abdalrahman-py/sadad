package com.sadad.app.feature.trash

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.sadad.app.R
import com.sadad.app.SadadApplication
import com.sadad.app.feature.notifications.DueReminders
import com.sadad.app.ui.components.ConfirmDialog
import com.sadad.app.ui.components.formatCents

@Composable
fun TrashScreen(modifier: Modifier = Modifier) {
  TrashScreen(trashViewModel(), modifier)
}

@Composable
fun TrashScreen(viewModel: TrashViewModel, modifier: Modifier = Modifier) {
  val state by viewModel.state.collectAsStateWithLifecycle()

  Column(
      modifier = modifier.fillMaxSize().padding(24.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text(stringResource(R.string.title_trash), style = MaterialTheme.typography.headlineSmall)
    Text(
        stringResource(R.string.trash_subtitle),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    state.message?.let { Text(it) }
    if (state.items.isEmpty()) {
      Text(stringResource(R.string.trash_empty_title), style = MaterialTheme.typography.titleMedium)
      Text(
          stringResource(R.string.trash_empty_detail),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    } else {
      LazyColumn(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        items(state.items, key = { it.id }) { item ->
          TrashRow(
              item,
              onRestore = { viewModel.restore(item) },
              onDelete = { viewModel.askDelete(item) },
          )
        }
      }
    }
  }

  state.confirm?.let { item ->
    ConfirmDialog(
        title = stringResource(R.string.trash_confirm_title),
        message = stringResource(R.string.trash_confirm_message),
        confirmText = stringResource(R.string.trash_confirm_delete),
        dismissText = stringResource(R.string.trash_cancel),
        onConfirm = { viewModel.deleteForGood(item) },
        onDismiss = viewModel::dismissDelete,
    )
  }
}

@Composable
private fun TrashRow(item: TrashItem, onRestore: () -> Unit, onDelete: () -> Unit) {
  Card(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(item.title(), style = MaterialTheme.typography.titleMedium)
      Text(
          text = item.detail(),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onRestore, modifier = Modifier.weight(1f)) {
          Text(stringResource(R.string.trash_restore))
        }
        OutlinedButton(onClick = onDelete, modifier = Modifier.weight(1f)) {
          Text(stringResource(R.string.trash_delete))
        }
      }
    }
  }
}

@Composable
private fun TrashItem.title(): String =
    when (this) {
      is TrashItem.Contact -> contact.name
      is TrashItem.Notification -> reminder.contactName
    }

@Composable
private fun TrashItem.detail(): String =
    when (this) {
      is TrashItem.Contact ->
          stringResource(R.string.trash_kind_contact) +
              if (contact.phone.isBlank()) "" else " · ${contact.phone}"
      is TrashItem.Notification ->
          stringResource(R.string.trash_kind_notification) +
              " · ${formatCents(reminder.amountCents)} · ${reminder.dueDate}"
    }

@Composable
private fun trashViewModel(): TrashViewModel =
    viewModel(
        factory =
            viewModelFactory {
              initializer {
                val app = this[APPLICATION_KEY] as SadadApplication
                val store =
                    checkNotNull(app.graph.current.value) { "the trash needs an open store" }
                val storeUserId = store.account.storeUserId
                TrashViewModel(
                    store.repository,
                    DueReminders.get(app),
                    TrashHidden.get(app, storeUserId),
                    storeUserId,
                    app,
                )
              }
            }
    )
