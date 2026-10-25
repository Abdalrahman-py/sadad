package com.sadad.app.crash

import io.sentry.Breadcrumb
import io.sentry.SentryEvent
import io.sentry.protocol.Message
import io.sentry.protocol.Request
import io.sentry.protocol.SentryException
import io.sentry.protocol.SentryStackFrame
import io.sentry.protocol.SentryStackTrace
import io.sentry.protocol.User
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SentryScrubberTest {
  @Test
  fun `event keeps only safe fields and stack frames`() {
    val frame = SentryStackFrame().apply { function = "save" }
    val exception =
        SentryException().apply {
          type = "RuntimeException"
          value = "Ali 120.50 +970599…"
          stacktrace = SentryStackTrace(listOf(frame))
        }
    val event =
        SentryEvent().apply {
          message = Message().apply { message = "Ali 120.50" }
          setExtra("ledger", "Ali 120.50")
          request = Request().apply { url = "https://example.test/Ali" }
          user = User().apply { username = "Ali" }
          contexts.set("device", mapOf("model" to "Pixel"))
          contexts.set("os", mapOf("name" to "Android"))
          contexts.set("app", mapOf("version" to "3.0.0"))
          contexts.set("runtime", mapOf("note" to "Ali"))
          exceptions = listOf(exception)
        }

    assertSame(event, SentryScrubber.beforeSend(event))
    assertNull(event.message)
    assertTrue(event.extras.isNullOrEmpty())
    assertNull(event.request)
    assertNull(event.user)
    assertEquals(setOf("device", "os", "app"), event.contexts.entrySet().map { it.key }.toSet())
    assertEquals("RuntimeException", exception.value)
    assertSame(frame, exception.stacktrace?.frames?.single())
  }

  @Test
  fun `breadcrumbs drop UI text and reduce navigation routes to class names`() {
    assertNull(SentryScrubber.beforeBreadcrumb(Breadcrumb.ui("click", "Ali")))

    val navigation =
        Breadcrumb.navigation(
                "com.sadad.app.ui.Home",
                "com.sadad.app.ui.ContactDetail/Ali?amount=120.50",
            )
            .apply {
              message = "Ali"
              setData("note", "Ali")
            }
    val scrubbed = SentryScrubber.beforeBreadcrumb(navigation)

    assertEquals("navigation", scrubbed?.category)
    assertEquals(
        mapOf("from" to "Home", "to" to "ContactDetail"),
        scrubbed?.data,
    )
    assertNull(scrubbed?.message)
  }

  @Test
  fun `null event stays null`() {
    assertNull(SentryScrubber.beforeSend(null))
  }
}
