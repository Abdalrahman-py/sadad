package com.sadad.app.feature.history

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sadad.app.R
import com.sadad.app.data.ledger.OutboxState
import com.sadad.app.ui.components.EmptyState
import com.sadad.app.ui.theme.sadadPalette
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The activity tab: this phone's refused or cancelled rows first, each dismissible once nothing
 * else depends on it, then the ledger's newest events, one line each as the Java log printed them.
 */
@Composable
fun ActivityTab(activity: ActivityLogState, onDismiss: (String) -> Unit) {
  val p = sadadPalette
  if (activity.unaccepted.isEmpty() && activity.entries.isEmpty()) {
    EmptyState(
        title = stringResource(R.string.history_activity_empty_title),
        detail = stringResource(R.string.history_activity_empty_detail),
        icon = R.drawable.ic_list,
        modifier = Modifier.fillMaxWidth().padding(18.dp),
    )
    return
  }
  LazyColumn(
      modifier = Modifier.fillMaxSize().testTag(HistoryTags.ACTIVITY),
      contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 20.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    if (activity.unaccepted.isNotEmpty()) {
      item {
        Text(
            text = stringResource(R.string.history_unaccepted_title),
            color = p.owes,
            fontSize = 13.sp,
            lineHeight = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() },
        )
      }
      items(activity.unaccepted, key = { "unaccepted:${it.operationId}" }) { entry ->
        UnacceptedRow(entry = entry, onDismiss = onDismiss, modifier = Modifier.animateItem())
      }
      item { Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(p.line)) }
    }
    items(activity.entries, key = { "event:${it.sequence}" }) { entry ->
      Column(
          Modifier.fillMaxWidth()
              .background(p.card, RoundedCornerShape(13.dp))
              .border(1.dp, p.line, RoundedCornerShape(13.dp))
              .padding(horizontal = 14.dp, vertical = 12.dp),
          verticalArrangement = Arrangement.spacedBy(2.dp),
      ) {
        Text(
            text = actionLine(entry),
            color = if (entry.refused != null) p.owes else p.text,
            fontSize = 15.sp,
            lineHeight = 22.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text =
                actorLine(entry) +
                    " · " +
                    STAMP_FORMAT.withZone(ZoneId.systemDefault()).format(entry.at),
            color = p.muted,
            fontSize = 12.sp,
            lineHeight = 18.sp,
        )
      }
    }
  }
}

/** A refused or cancelled row as the Notifications board's «تحتاج إجراء» card. */
@Composable
private fun UnacceptedRow(
    entry: UnacceptedEntry,
    onDismiss: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
  val p = sadadPalette
  val blocked = entry.blockedBy != null
  Row(
      modifier =
          modifier
              .fillMaxWidth()
              .background(p.card, RoundedCornerShape(13.dp))
              .border(1.5.dp, if (blocked) p.line else p.owes, RoundedCornerShape(13.dp))
              .testTag("history:unaccepted:${entry.operationId}")
              .padding(14.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Box(
        Modifier.size(36.dp).background(p.owesSoft, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
      Text("!", color = p.owes, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text(
          text = stringResource(entry.action.label),
          color = p.text,
          fontSize = 15.sp,
          lineHeight = 22.sp,
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
                entry.state == OutboxState.UNSENDABLE ->
                    stringResource(R.string.history_unaccepted_unsendable)
                else -> stringResource(R.string.history_unaccepted_cancelled)
              },
          color = if (blocked) p.muted else p.owes,
          fontSize = 13.sp,
          lineHeight = 20.sp,
      )
      Text(
          text = STAMP_FORMAT.withZone(ZoneId.systemDefault()).format(entry.at),
          color = p.muted,
          fontSize = 12.sp,
      )
    }
    if (!blocked) {
      Surface(
          onClick = { onDismiss(entry.operationId) },
          color = p.surface,
          contentColor = if (p.isDark) p.accent else p.primary,
          shape = RoundedCornerShape(10.dp),
          modifier = Modifier.heightIn(min = 44.dp),
      ) {
        Box(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), Alignment.Center) {
          Text(
              stringResource(R.string.history_dismiss),
              fontSize = 14.sp,
              fontWeight = FontWeight.Bold,
          )
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
