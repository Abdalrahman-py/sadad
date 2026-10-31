package com.sadad.app.ui

import android.os.Looper
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.sadad.app.AppGraph
import com.sadad.app.SadadApplication
import com.sadad.app.testing.TestGraph
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Records which thread builds the graph and holds the build until the test lets it go. */
class GraphProbeApplication : SadadApplication() {
  // The real onCreate arms StrictMode, WorkManager and the update check; none of it is under test.
  override fun onCreate() {}

  override fun createGraph(): AppGraph {
    builtOnMain = Thread.currentThread() == Looper.getMainLooper().thread
    started.countDown()
    // Bounded: if the build runs on main (the bug) this stalls the test thread for 2 s, not
    // forever.
    release.await(2, TimeUnit.SECONDS)
    return checkNotNull(supplied)
  }

  companion object {
    @Volatile var supplied: AppGraph? = null
    @Volatile var builtOnMain: Boolean? = null
    var started = CountDownLatch(1)
    var release = CountDownLatch(1)

    fun reset() {
      supplied = null
      builtOnMain = null
      started = CountDownLatch(1)
      release = CountDownLatch(1)
    }
  }
}

/**
 * The first graph build opens the Keystore, so composition must not do it: `SadadApp` shows the
 * page and no screen while the build runs on another thread.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = GraphProbeApplication::class)
class SadadAppGraphTest {
  @get:Rule val compose = createComposeRule()
  private var testGraph: TestGraph? = null

  @After
  fun tearDown() {
    GraphProbeApplication.release.countDown()
    testGraph?.close()
    GraphProbeApplication.reset()
  }

  @Test
  fun `the first composition builds the graph off main and shows no screen until it lands`() {
    GraphProbeApplication.reset()
    testGraph = TestGraph.unsigned().also { GraphProbeApplication.supplied = it.graph }

    compose.setContent { SadadApp(dark = false) }
    compose.waitUntil(timeoutMillis = 5_000) { GraphProbeApplication.started.count == 0L }

    assertEquals(false, GraphProbeApplication.builtOnMain)
    compose.onNodeWithTag(ScreenTag.NAV_HOME).assertDoesNotExist()
  }
}
