package com.sadad.app.feature.settings

import androidx.lifecycle.SavedStateHandle
import com.sadad.app.feature.lock.AppLock
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The lock section's module, built from the lock alone: no graph, preferences or scheduler. Every
 * wait is bounded, so a state that never matches fails the test instead of hanging CI.
 */
@RunWith(RobolectricTestRunner::class)
class SecurityModuleTest {
  @get:Rule val temporary = TemporaryFolder()

  @Test
  fun theLockSwitchFlipsTheLockOnAndOff() = runBlocking {
    withTimeout(10_000) {
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
      try {
        val lock = AppLock(File(temporary.root, "lock.preferences_pb"), scope, { 0L }, { 1 })
        lock.setPin("4839")
        val ui = SettingsUi(scope, SavedStateHandle())
        val security = SecurityModule(lock, { false }, ui)
        assertTrue(security.state.first().lockOn)

        security.setLockEnabled(false)
        assertFalse(security.state.first { !it.lockOn }.lockOn)

        security.setLockEnabled(true)
        val on = withTimeoutOrNull(5_000) { security.state.first { it.lockOn } }
        assertTrue(
            "lock=${lock.lockEnabled.first()} pin=${lock.pinSet.first()} ui=${ui.state.value}",
            on?.lockOn == true,
        )
      } finally {
        scope.cancel()
      }
    }
  }

  @Test
  fun theLockSwitchWithoutAPinAsksForOneAndStaysOff() = runBlocking {
    withTimeout(10_000) {
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
      try {
        val lock = AppLock(File(temporary.root, "lock.preferences_pb"), scope, { 0L }, { 1 })
        val ui = SettingsUi(scope, SavedStateHandle())
        val security = SecurityModule(lock, { false }, ui)

        security.setLockEnabled(true)

        assertEquals(Pending.SetPin, ui.state.first { it.pending != null }.pending)
        assertFalse(security.state.first().lockOn)
      } finally {
        scope.cancel()
      }
    }
  }
}
