package com.sadad.app.feature.notifications

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sadad.app.R
import com.sadad.app.SadadApplication
import com.sadad.app.ui.components.EmptyState
import com.sadad.app.ui.components.ScreenHeader
import com.sadad.app.ui.components.formatShekels
import com.sadad.app.ui.theme.sadadPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun NotificationsScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val storeUserId by
      produceState<String?>(null) {
        val graph =
            withContext(Dispatchers.IO) { (context.applicationContext as SadadApplication).graph }
        graph.accounts.state.collect { value = it.activeStoreUserId }
      }
  val reminders =
      remember(context.applicationContext) { DueReminders.get(context.applicationContext) }
  NotificationsScreen(reminders, storeUserId.orEmpty(), modifier, onBack)
}

/**
 * The Notifications board's «مواعيد الاستحقاق»: one card per reminder, unread ones marked with an
 * amber dot, read ones faded, and the footnote on when they arrive.
 */
@Composable
fun NotificationsScreen(
    reminders: DueReminders,
    storeUserId: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
) {
  val inbox = remember(reminders, storeUserId) { reminders.inbox(storeUserId) }
  val collected by inbox.collectAsState(initial = emptyList())
  val events = collected.filter { it.storeUserId == storeUserId }
  val snackbar = remember { SnackbarHostState() }
  val scope = rememberCoroutineScope()
  val dismissed = stringResource(R.string.reminder_dismissed)
  val undo = stringResource(R.string.reminder_undo)
  val p = sadadPalette
  LaunchedEffect(storeUserId, events.map { it.id }) {
    if (storeUserId.isNotEmpty()) reminders.markAllRead(storeUserId)
  }
  Scaffold(
      modifier = modifier,
      containerColor = Color.Transparent,
      topBar = { ScreenHeader(stringResource(R.string.reminders_title), onBack = onBack) },
      snackbarHost = { SnackbarHost(snackbar) },
  ) { padding ->
    LazyColumn(
        Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      item(key = "head") {
        Text(
            stringResource(R.string.reminder_channel),
            color = p.muted,
            fontSize = 13.sp,
            lineHeight = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() },
        )
      }
      if (events.isEmpty()) {
        item(key = "empty") {
          EmptyState(
              title = stringResource(R.string.reminders_empty),
              detail = stringResource(R.string.reminders_subtitle),
              icon = R.drawable.ic_notifications,
              modifier = Modifier.fillMaxWidth(),
          )
        }
      } else {
        items(events, key = { it.id }) { event ->
          ReminderRow(
              event = event,
              onDone = {
                scope.launch {
                  val result =
                      snackbar.showSnackbar(dismissed, undo, duration = SnackbarDuration.Short)
                  if (result != SnackbarResult.ActionPerformed) reminders.dismiss(event.id)
                }
              },
              modifier = Modifier.animateItem(),
          )
        }
      }
      item(key = "foot") {
        Text(
            stringResource(R.string.reminders_footnote),
            color = p.muted,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            modifier = Modifier.padding(top = 10.dp),
        )
      }
    }
  }
}

@Composable
private fun ReminderRow(event: Reminder, onDone: () -> Unit, modifier: Modifier = Modifier) {
  val p = sadadPalette
  val link = if (p.isDark) p.accent else p.primary
  Row(
      modifier
          .fillMaxWidth()
          .alpha(if (event.read) 0.6f else 1f)
          .background(p.card, RoundedCornerShape(13.dp))
          .border(1.dp, p.line, RoundedCornerShape(13.dp))
          .padding(horizontal = 14.dp, vertical = 12.dp)
          .animateContentSize(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Box(Modifier.size(8.dp).background(if (event.read) p.line else AMBER, CircleShape))
    Column(Modifier.weight(1f)) {
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            event.contactName,
            color = p.text,
            fontSize = 15.sp,
            lineHeight = 22.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            stringResource(R.string.reminder_owes, formatShekels(event.amountCents)),
            color = p.owes,
            fontSize = 15.sp,
            lineHeight = 22.sp,
            fontWeight = FontWeight.Bold,
        )
      }
      Text(
          stringResource(
              if (event.stage == "today") R.string.reminder_when_today
              else R.string.reminder_when_tomorrow,
              event.dueDate,
          ),
          color = if (event.stage == "today") p.credit else p.muted,
          fontSize = 12.sp,
          lineHeight = 18.sp,
      )
    }
    Surface(
        onClick = onDone,
        color = p.surface,
        contentColor = link,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.heightIn(min = 40.dp),
    ) {
      Box(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), Alignment.Center) {
        Text(
            stringResource(R.string.reminder_mark_done),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
        )
      }
    }
  }
}

private val AMBER = Color(0xFFE0A23A)
