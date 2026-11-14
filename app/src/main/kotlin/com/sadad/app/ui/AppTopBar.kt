package com.sadad.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sadad.app.LocalGraph
import com.sadad.app.R
import com.sadad.app.feature.notifications.DueReminders
import com.sadad.app.ui.theme.sadadPalette
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

/** The bell's test tag, as it was on Home before the bar was shared by every tab. */
const val BELL_TAG = "home:bell"

private val BadgeRed = Color(0xFFC13634)

/**
 * The bar every tab shares: the bell with its unread count. It owns the status-bar inset and the
 * page's side padding, so every tab's content starts at the same place.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Composable
internal fun AppTopBar(onOpenNotifications: () -> Unit) {
  val context = LocalContext.current
  val graph = LocalGraph.current
  val reminders = remember(context) { DueReminders.get(context) }
  val unread by
      remember(graph, reminders) {
            (graph?.current ?: flowOf(null)).flatMapLatest { scope ->
              if (scope == null) flowOf(0) else reminders.unreadCount(scope.account.storeUserId)
            }
          }
          .collectAsStateWithLifecycle(0)
  AppTopBarContent(
      unread = unread,
      onOpenNotifications = onOpenNotifications,
  )
}

/** [AppTopBar] without its data sources, as the screen tests draw it. */
@Composable
internal fun AppTopBarContent(unread: Int, onOpenNotifications: () -> Unit) {
  val p = sadadPalette
  val bellLabel =
      if (unread > 0) stringResource(R.string.home_bell_new, unread)
      else stringResource(R.string.home_bell)
  Row(
      Modifier.fillMaxWidth()
          .background(p.bg)
          .windowInsetsPadding(WindowInsets.statusBars)
          .heightIn(min = 64.dp)
          .padding(horizontal = 16.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Spacer(Modifier.weight(1f))
    Box(Modifier.size(44.dp)) {
      Box(
          Modifier.size(44.dp)
              .clip(CircleShape)
              .background(p.card)
              .border(1.dp, p.line, CircleShape)
              .clickable(onClick = onOpenNotifications)
              .testTag(BELL_TAG)
              .semantics {
                contentDescription = bellLabel
                role = Role.Button
              },
          contentAlignment = Alignment.Center,
      ) {
        Icon(
            painterResource(R.drawable.ic_notifications),
            contentDescription = null,
            tint = p.text,
            modifier = Modifier.size(24.dp),
        )
      }
      if (unread > 0) {
        Surface(
            color = BadgeRed,
            shape = CircleShape,
            modifier = Modifier.align(Alignment.TopEnd).heightIn(min = 16.dp).widthIn(min = 16.dp),
        ) {
          Box(Modifier.padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
            Text("$unread", style = MaterialTheme.typography.bodySmall, color = Color.White)
          }
        }
      }
    }
  }
}
