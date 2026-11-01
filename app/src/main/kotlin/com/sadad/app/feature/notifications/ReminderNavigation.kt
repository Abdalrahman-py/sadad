package com.sadad.app.feature.notifications

import android.content.Intent
import com.sadad.app.AppGraph
import com.sadad.app.ui.ContactDetail
import com.sadad.app.ui.Home

/** A notification may open a Contact only after that store passes the usual session gates. */
internal suspend fun reminderDestination(
    graph: AppGraph,
    intent: Intent?,
    reminders: DueReminders,
): Any {
  val uri = intent?.data
  // The id on the data URI is the only input: extras prove nothing about the intent's caller.
  val id =
      uri.takeIf { it != null && it.scheme == "sadad" && it.host == "reminder" }
          ?.pathSegments
          ?.singleOrNull() ?: return graph.startRoute()
  val recorded = reminders.find(id) ?: return graph.startRoute()
  val store = recorded.storeUserId
  if (graph.accounts.account(store) == null) return graph.startRoute()
  if (graph.accounts.file().activeStoreUserId != store) graph.sessions.switchTo(store)
  val start = graph.startRoute()
  return if (start == Home) ContactDetail(recorded.contactId) else start
}
