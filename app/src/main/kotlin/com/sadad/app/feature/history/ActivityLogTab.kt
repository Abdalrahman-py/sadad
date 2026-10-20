package com.sadad.app.feature.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sadad.app.R
import com.sadad.app.data.ledger.OutboxState
import com.sadad.app.ui.components.EmptyState
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The activity tab: this phone's refused or cancelled rows first, each dismissible once nothing
 * else depends on it, then the ledger's newest events, one line each as the Java log printed them.
 */
@Composable
fun ActivityTab(activity: ActivityLogState, onDismiss: (String) -> Unit) {
  if (activity.unaccepted.isEmpty() && activity.entries.isEmpty()) {
    EmptyState(
        title = stringResource(R.string.history_activity_empty_title),
        detail = stringResource(R.string.history_activity_empty_detail),
        modifier = Modifier.fillMaxWidth(),
    )
    return
  }
  LazyColumn(
      modifier = Modifier.fillMaxSize().testTag(HistoryTags.ACTIVITY),
      verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    if (activity.unaccepted.isNotEmpty()) {
      item {
        Text(
            text = stringResource(R.string.history_unaccepted_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
      }
      items(activity.unaccepted, key = { "unaccepted:${it.operationId}" }) { entry ->
        UnacceptedRow(entry = entry, onDismiss = onDismiss)
      }
      item { HorizontalDivider(Modifier.padding(horizontal = 16.dp)) }
    }
    items(activity.entries, key = { "event:${it.sequence}" }) { entry ->
      Card(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
      ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
          Text(
              text = actionLine(entry),
              style = MaterialTheme.typography.bodyMedium,
              fontWeight = FontWeight.Bold,
          )
          Text(
              text = actorLine(entry),
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Text(
              text = STAMP_FORMAT.withZone(ZoneId.systemDefault()).format(entry.at),
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }
  }
}

@Composable
private fun UnacceptedRow(entry: UnacceptedEntry, onDismiss: (String) -> Unit) {
  Card(
      modifier =
          Modifier.fillMaxWidth()
              .padding(horizontal = 16.dp)
              .testTag("history:unaccepted:${entry.operationId}"),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
  ) {
    Row(
        modifier = Modifier.padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            text = stringResource(entry.action.label),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text =
                when {
                  entry.blockedBy != null ->
                      stringResource(
                          R.string.history_unaccepted_blocked,
                          entry.blockedBy.take(SHORT_ID),
                      )
                  entry.state == OutboxState.REJECTED && entry.refused != null ->
                      stringResource(R.string.history_unaccepted_rejected, entry.refused)
                  else -> stringResource(R.string.history_unaccepted_cancelled)
                },
            style = MaterialTheme.typography.labelSmall,
            color =
                if (entry.blockedBy != null) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.error,
        )
        Text(
            text = STAMP_FORMAT.withZone(ZoneId.systemDefault()).format(entry.at),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      if (entry.blockedBy == null) {
        TextButton(onClick = { onDismiss(entry.operationId) }) {
          Text(stringResource(R.string.history_dismiss))
        }
      }
    }
  }
}

@Composable
private fun actionLine(entry: ActivityEntry): String {
  val action = stringResource(entry.action.label)
  val refused = entry.refused
  return if (refused == null) action
  else action + " · " + stringResource(R.string.history_refused, refused)
}

@Composable
private fun actorLine(entry: ActivityEntry): String =
    if (entry.mine) stringResource(R.string.history_actor_unknown)
    else stringResource(R.string.history_actor_device, entry.installationId.take(SHORT_ID))

/** The Java log's eight characters of an id. */
private const val SHORT_ID = 8

private val STAMP_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd/MM/yyyy · HH:mm", Locale.US)
