package com.sadad.app.crash

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.sentry.Sentry
import org.junit.After
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
    CrashReporting.init(context, dsn = "", debuggable = false)

    assertFalse(Sentry.isEnabled())
  }

  @Test
  fun `debuggable build does not initialize Sentry`() {
    CrashReporting.init(context, dsn = TEST_DSN, debuggable = true)

    assertFalse(Sentry.isEnabled())
  }

  @Test
  fun `release DSN initializes Sentry with scrubbers and no default PII`() {
    CrashReporting.init(context, dsn = TEST_DSN, debuggable = false)

    assertTrue(Sentry.isEnabled())
    val options = Sentry.getCurrentScopes().options
    assertNotNull(options.beforeSend)
    assertNotNull(options.beforeBreadcrumb)
    assertFalse(options.isSendDefaultPii)
  }

  private companion object {
    const val TEST_DSN = "https://public@o0.ingest.sentry.io/1"
  }
}
