package com.sadad.app.crash

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.sentry.Hint
import io.sentry.Sentry
import io.sentry.SentryEvent
import io.sentry.protocol.SentryException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CrashReportingTest {
  private val context = ApplicationProvider.getApplicationContext<Context>()

  @Before
  fun closeBeforeTest() {
    Sentry.close()
  }

  @After
  fun closeAfterTest() {
    Sentry.close()
  }

  @Test
  fun `empty DSN does not initialize Sentry`() {
    CrashReporting.init(context, dsn = "")

    assertFalse(Sentry.isEnabled())
  }

  @Test
  fun `a DSN initializes Sentry with the scrubbers and no default PII`() {
    CrashReporting.init(context, dsn = TEST_DSN)

    assertTrue(Sentry.isEnabled())
    val options = Sentry.getCurrentScopes().options
    assertEquals(TEST_DSN, options.dsn)
    assertNotNull(options.beforeSend)
    assertNotNull(options.beforeBreadcrumb)
    assertFalse(options.isSendDefaultPii)
  }

  @Test
  fun `the wired beforeSend is the scrubber`() {
    CrashReporting.init(context, dsn = TEST_DSN)
    val beforeSend = requireNotNull(Sentry.getCurrentScopes().options.beforeSend)

    val exception =
        SentryException().apply {
          type = "RuntimeException"
          value = "Ali 120.50"
        }
    val event =
        SentryEvent().apply {
          setExtra("ledger", "Ali 120.50")
          exceptions = listOf(exception)
        }

    val scrubbed = requireNotNull(beforeSend.execute(event, Hint()))

    // Not just "a callback is set": the callback the SDK will run is the one that drops the PII.
    assertEquals("RuntimeException", scrubbed.exceptions?.single()?.value)
    assertTrue(scrubbed.extras.isNullOrEmpty())
  }

  private companion object {
    const val TEST_DSN = "https://public@o0.ingest.sentry.io/1"
  }
}
