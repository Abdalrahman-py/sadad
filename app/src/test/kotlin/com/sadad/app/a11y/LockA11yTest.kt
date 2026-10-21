package com.sadad.app.a11y

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.sadad.app.feature.lock.AppLock
import com.sadad.app.feature.lock.BiometricAuth
import com.sadad.app.feature.lock.LockGate
import com.sadad.app.feature.lock.LockTag
import com.sadad.app.ui.UiTestApplication
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val KEYBOARD_UP_DP = 360

/** The lock screen (PIN field, open button, biometric button) at both font sizes (ticket 76). */
@RunWith(RobolectricTestRunner::class)
@Config(application = UiTestApplication::class, qualifiers = PHONE_QUALIFIERS)
class LockA11yTest {
  @get:Rule val compose = createComposeRule()
  @get:Rule val temporary = TemporaryFolder()
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  @After fun close() = scope.cancel()

  @Test fun lock() = lock(NORMAL_FONT)

  @Test fun lockAtLargestFont() = lock(LARGEST_FONT)

  private fun lock(fontScale: Float) {
    val lock =
        AppLock(File(temporary.root, "lock.preferences_pb"), scope, { 100_000L }, { 1 }).also {
          runBlocking {
            it.setPin("1234")
            it.setBiometricEnabled(true)
          }
        }
    // A PIN is typed with the keyboard up: a 640 dp tall phone keeps about this much for the
    // screen.
    compose.showAt(fontScale) {
      Box(Modifier.height(KEYBOARD_UP_DP.dp).clipToBounds()) {
        LockGate(lock, true, Sensor) { Text("المحتوى") }
      }
    }
    compose.waitUntil(5_000) { lockShown() }
    compose.assertAccessible(hasTestTag(LockTag.PIN), hasTestTag(LockTag.OPEN))
  }

  private fun lockShown() =
      compose.onAllNodes(hasTestTag(LockTag.PIN)).fetchSemanticsNodes().isNotEmpty()

  /** A sensor that is present but never answers, so the biometric button shows. */
  private object Sensor : BiometricAuth {
    override fun available() = true

    override fun authenticate(onSuccess: () -> Unit) = Unit
  }
}
