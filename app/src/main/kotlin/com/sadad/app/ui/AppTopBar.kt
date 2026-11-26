package com.sadad.app.ui

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sadad.app.LocalGraph
import com.sadad.app.R
import com.sadad.app.feature.notifications.DueReminders
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

/** The bell's test tag, as it was on Home before the bar was shared by every tab. */
const val BELL_TAG = "home:bell"

/** The shared bar's test tag, so tests can tell its title from the rest of the screen. */
const val TOP_BAR_TAG = "shell:topbar"

/**
 * What the tab on screen adds to the shared bar beside the bell. The shell owns one; a screen fills
 * it with [TopBarActions].
 */
@Stable
class TopBarActionsHolder {
  internal var content by mutableStateOf<(@Composable RowScope.() -> Unit)?>(null)
}

val LocalTopBarActions = staticCompositionLocalOf<TopBarActionsHolder?> { null }

/**
 * Puts [content] in the shared top bar, before the bell, while the calling screen is composed. Off
 * the shell (a screen test) there is no bar, and nothing is drawn.
 */
@Composable
fun TopBarActions(content: @Composable RowScope.() -> Unit) {
  val holder = LocalTopBarActions.current ?: return
  val latest by rememberUpdatedState(content)
  val mine: @Composable RowScope.() -> Unit = remember { { latest() } }
  DisposableEffect(holder) {
    holder.content = mine
    // The next tab may have set its own actions before this one leaves; keep those.
    onDispose { if (holder.content === mine) holder.content = null }
  }
}

/**
 * The bar every tab shares: the tab's [title] at the start, then the screen's actions and the bell
 * with its unread count. The bar owns the status-bar inset.
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun AppTopBar(
    title: String,
    onOpenNotifications: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
) {
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
      title = title,
      unread = unread,
      onOpenNotifications = onOpenNotifications,
      actions = actions,
  )
}

/** [AppTopBar] without its data sources, as the screen tests draw it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppTopBarContent(
    title: String,
    unread: Int,
    onOpenNotifications: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
) {
  // The button's label carries the count, so TalkBack reads it once; the badge only draws it.
  val bellLabel =
      if (unread > 0) stringResource(R.string.home_bell_new, unread)
      else stringResource(R.string.home_bell)
  TopAppBar(
      title = {
        Text(
            title,
            modifier = Modifier.semantics { heading() },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
      },
      actions = {
        actions()
        IconButton(
            onClick = onOpenNotifications,
            modifier = Modifier.testTag(BELL_TAG).semantics { contentDescription = bellLabel },
        ) {
          BadgedBox(badge = { if (unread > 0) Badge { Text("$unread") } }) {
            Icon(
                painterResource(R.drawable.ic_notifications),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
            )
          }
        }
      },
      colors =
          NavigationBarDefaults.containerColor.let { bar ->
            // The bottom bar's tint at rest and when scrolled, so the bar never flashes.
            TopAppBarDefaults.topAppBarColors(containerColor = bar, scrolledContainerColor = bar)
          },
      modifier = Modifier.testTag(TOP_BAR_TAG),
  )
}
