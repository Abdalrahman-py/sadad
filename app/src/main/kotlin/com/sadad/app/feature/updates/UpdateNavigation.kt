package com.sadad.app.feature.updates

import android.content.Intent
import com.sadad.app.AppGraph
import com.sadad.app.ui.AppUpdate
import com.sadad.app.ui.Home

/**
 * A tap on the update notification opens the update screen, but only for someone already inside a
 * store: the shell decides the start screen, and an update is not a way around the lock or the
 * sign-in. Called off the main thread, like `startRoute` itself.
 */
internal suspend fun updateRoute(graph: AppGraph, intent: Intent?): Any? {
  if (intent?.data?.toString() != UpdateNotifications.URI) return null
  return if (graph.startRoute() == Home) AppUpdate else null
}
