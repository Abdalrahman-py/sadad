package com.sadad.app.feature.lock

import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.sadad.app.ui.UiTestApplication
import com.sadad.app.ui.theme.SadadTheme
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val CONTENT = "المحتوى"
private const val LOCKED = "التطبيق مقفل"

@RunWith(RobolectricTestRunner::class)
@Config(application = UiTestApplication::class)
class LockGateTest {
  @get:Rule val compose = createComposeRule()
  @get:Rule val temporary = TemporaryFolder()
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val owner = TestOwner()
  private var now = 100_000L

  @After fun close() = scope.cancel()

  private fun lockWithPin(): AppLock =
      AppLock(File(temporary.root, "lock.preferences_pb"), scope, { now }, { 1 }).also {
        runBlocking { it.setPin("1234") }
      }

  private fun show(
      lock: AppLock,
      hasSession: Boolean = true,
      biometric: BiometricAuth = NoBiometric,
  ) {
    compose.setContent {
      SadadTheme {
        CompositionLocalProvider(LocalLifecycleOwner provides owner) {
          LockGate(lock, hasSession, biometric) { Text(CONTENT) }
        }
      }
    }
  }

  private fun leaveFor(millis: Long) {
    compose.runOnUiThread { owner.send(Lifecycle.Event.ON_STOP) }
    now += millis
    compose.runOnUiThread { owner.send(Lifecycle.Event.ON_START) }
  }

  private fun shown(text: String) =
      compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

  private fun waitFor(text: String) = compose.waitUntil(5_000) { shown(text) }

  @Test
  fun stoppingForTwoSecondsLocksButOneSecondDoesNot() {
    val lock = lockWithPin().also { it.markUnlocked() }
    show(lock)
    compose.onNodeWithText(CONTENT).assertIsDisplayed()

    leaveFor(1_000)
    compose.waitForIdle()
    compose.onNodeWithText(CONTENT).assertIsDisplayed()
    assertFalse(shown(LOCKED))

    leaveFor(2_000)
    waitFor(LOCKED)
    assertFalse(shown(CONTENT))
  }

  @Test
  fun aLockSwitchedOffNeverLocks() {
    val lock = lockWithPin().also { it.markUnlocked() }
    runBlocking { lock.setLockEnabled(false) }
    show(lock)
    leaveFor(2_000)
    waitFor(CONTENT)
    assertFalse(shown(LOCKED))
  }

  @Test
  fun withoutAStoreSessionNothingLocks() {
    show(lockWithPin(), hasSession = false)
    waitFor(CONTENT)
    assertFalse(shown(LOCKED))
  }

  @Test
  fun skipNextLockSuppressesExactlyOneLock() {
    val lock = lockWithPin().also { it.markUnlocked() }
    show(lock)
    lock.skipNextLock()
    leaveFor(2_000)
    compose.waitForIdle()
    compose.onNodeWithText(CONTENT).assertIsDisplayed()

    leaveFor(2_000)
    waitFor(LOCKED)
  }

  @Test
  fun theAppStartsLockedAndOnlyTheRightPinOpensIt() {
    show(lockWithPin())
    waitFor(LOCKED)
    assertFalse(shown(CONTENT))

    compose.onNodeWithTag(LockTag.PIN).performTextInput("9999")
    compose.onNodeWithTag(LockTag.OPEN).performClick()
    waitFor("رمز القفل غير صحيح")
    assertFalse(shown(CONTENT))

    compose.onNodeWithTag(LockTag.PIN).performTextInput("1234")
    compose.onNodeWithTag(LockTag.OPEN).performClick()
    waitFor(CONTENT)
  }

  @Test
  fun aBiometricMatchOpensTheApp() {
    val lock = lockWithPin()
    runBlocking { lock.setBiometricEnabled(true) }
    val sensor = Sensor(available = true)
    show(lock, biometric = sensor)
    waitFor(CONTENT)
    assertEquals(1, sensor.prompts)
  }

  @Test
  fun withoutASensorTheBiometricButtonIsAbsent() {
    val lock = lockWithPin()
    runBlocking { lock.setBiometricEnabled(true) }
    show(lock, biometric = Sensor(available = false))
    waitFor(LOCKED)
    assertFalse(shown("استخدام البصمة أو الوجه"))
  }

  private object NoBiometric : BiometricAuth {
    override fun available() = false

    override fun authenticate(onSuccess: () -> Unit) = Unit
  }

  private class Sensor(private val available: Boolean) : BiometricAuth {
    var prompts = 0

    override fun available() = available

    override fun authenticate(onSuccess: () -> Unit) {
      prompts++
      onSuccess()
    }
  }

  private class TestOwner : LifecycleOwner {
    private val registry = LifecycleRegistry.createUnsafe(this)
    override val lifecycle: Lifecycle
      get() = registry

    init {
      send(Lifecycle.Event.ON_CREATE)
      send(Lifecycle.Event.ON_START)
    }

    fun send(event: Lifecycle.Event) = registry.handleLifecycleEvent(event)
  }
}
