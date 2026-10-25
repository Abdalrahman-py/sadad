package com.sadad.app.feature.notifications

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sadad.app.R
import com.sadad.app.SadadApplication
import com.sadad.app.ui.components.ScreenHeader
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

@Composable
fun NotificationsScreen(
    reminders: DueReminders,
    storeUserId: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
) {
  val context = LocalContext.current
  val inbox = remember(reminders, storeUserId) { reminders.inbox(storeUserId) }
  val collected by inbox.collectAsState(initial = emptyList())
  val events = collected.filter { it.storeUserId == storeUserId }
  val snackbar = remember { SnackbarHostState() }
  val scope = rememberCoroutineScope()
  val dismissed = stringResource(R.string.reminder_dismissed)
  val undo = stringResource(R.string.reminder_undo)
  LaunchedEffect(storeUserId, events.map { it.id }) {
    if (storeUserId.isNotEmpty()) reminders.markAllRead(storeUserId)
  }
  Scaffold(
      modifier = modifier,
      topBar = { ScreenHeader(stringResource(R.string.reminders_title), onBack = onBack) },
      snackbarHost = { SnackbarHost(snackbar) },
  ) { padding ->
    Column(
        Modifier.fillMaxSize().padding(padding).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text(stringResource(R.string.reminders_subtitle))
      if (events.isEmpty()) Text(stringResource(R.string.reminders_empty))
      else
          LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(events, key = { it.id }) { event ->
              Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                  Text(event.contactName, style = MaterialTheme.typography.titleMedium)
                  Text(reminderText(context, event))
                  TextButton(
                      onClick = {
                        scope.launch {
                          val result =
                              snackbar.showSnackbar(
                                  dismissed,
                                  undo,
                                  duration = SnackbarDuration.Short,
                              )
                          if (result != SnackbarResult.ActionPerformed) reminders.dismiss(event.id)
                        }
                      }
                  ) {
                    Text(stringResource(R.string.reminder_mark_done))
                  }
                }
              }
            }
          }
    }
  }
}
