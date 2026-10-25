package com.sadad.app.crash

import io.sentry.Breadcrumb
import io.sentry.SentryEvent

internal object SentryScrubber {
  private val allowedContexts = setOf("device", "os", "app")
  private val routeKeys = setOf("from", "to", "screen")

  fun beforeSend(event: SentryEvent?): SentryEvent? {
    if (event == null) return null
    event.message = null
    event.setExtras(null)
    event.request = null
    event.user = null
    event.contexts
        .entrySet()
        .map { it.key }
        .forEach { key ->
          if (key !in allowedContexts) event.contexts.remove(key)
        }
    event.breadcrumbs = event.breadcrumbs?.mapNotNull(::beforeBreadcrumb)
    event.exceptions?.forEach { it.value = it.type }
    return event
  }

  fun beforeBreadcrumb(crumb: Breadcrumb): Breadcrumb? {
    if (crumb.category != "navigation") return null
    return Breadcrumb(crumb.timestamp).apply {
      type = "navigation"
      category = "navigation"
      routeKeys.forEach { key ->
        (crumb.getData(key) as? String)?.let { route -> setData(key, routeClass(route)) }
      }
    }
  }

  private fun routeClass(route: String): String =
      route.substringBefore('/').substringBefore('?').substringBefore('(').substringAfterLast('.')
}
