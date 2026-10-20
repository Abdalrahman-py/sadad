package com.sadad.app.feature.notifications

import android.content.Intent
import com.sadad.app.AppGraph
import com.sadad.app.ui.ContactDetail
import com.sadad.app.ui.Home

/** A notification may open a Contact only after that store passes the usual session gates. */
internal suspend fun reminderDestination(graph: AppGraph, intent: Intent?): Any {
  val store = intent?.getStringExtra(DueReminderWorker.EXTRA_STORE)
  val contact = intent?.getStringExtra(DueReminderWorker.EXTRA_CONTACT)
  if (!store.isNullOrBlank() && !contact.isNullOrBlank() && graph.accounts.account(store) != null) {
    if (graph.accounts.file().activeStoreUserId != store) graph.sessions.switchTo(store)
    val start = graph.startRoute()
    return if (start == Home) ContactDetail(contact) else start
  }
  return graph.startRoute()
}
